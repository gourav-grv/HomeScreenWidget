#!/usr/bin/env node
/*
 * iOS setup for cordova-plugin-homescreen-widget (runs after_prepare, idempotent):
 *  1. Writes the App Group into the app's Info.plist (HSWAppGroup) and entitlements.
 *  2. Adds the "HomeScreenWidgetExtension" WidgetKit target to the Xcode project.
 *
 * Preferences read from the app's config.xml (set them in the app's Extensibility Configurations):
 *   HSWAppGroup      - optional, default group.<bundle id>
 *   HSWWidgetProfile - optional provisioning profile specifier for the widget extension
 */
'use strict';

var fs = require('fs');
var path = require('path');

var TARGET = 'HomeScreenWidgetExtension';
var PLUGIN_ID = 'cordova-plugin-homescreen-widget';

function loadXcode(projectRoot) {
    var candidates = [
        'xcode',
        path.join(projectRoot, 'node_modules', 'xcode'),
        path.join(projectRoot, 'node_modules', 'cordova-ios', 'node_modules', 'xcode'),
        path.join(projectRoot, 'platforms', 'ios', 'cordova', 'node_modules', 'xcode')
    ];
    for (var i = 0; i < candidates.length; i++) {
        try { return require(candidates[i]); } catch (e) { /* try next */ }
    }
    throw new Error('[HomeScreenWidget] Could not load the "xcode" npm module.');
}

function readPreference(projectRoot, iosDir, name) {
    var files = [path.join(projectRoot, 'config.xml')];
    try {
        fs.readdirSync(iosDir).forEach(function (d) {
            var f = path.join(iosDir, d, 'config.xml');
            if (fs.existsSync(f)) files.push(f);
        });
    } catch (e) { /* ignore */ }
    var re = new RegExp('<preference\\s+name="' + name + '"\\s+value="([^"]*)"', 'i');
    for (var i = files.length - 1; i >= 0; i--) {
        try {
            var m = re.exec(fs.readFileSync(files[i], 'utf8'));
            if (m && m[1].trim()) return m[1].trim();
        } catch (e) { /* ignore */ }
    }
    return '';
}

var END_OF_PLIST = /<\/dict>\s*<\/plist>\s*$/;

/** Adds HSWAppGroup to the app Info.plist and the App Group to its entitlements. */
function patchMainApp(iosDir, appName, appGroup) {
    var appDir = path.join(iosDir, appName);

    var infoPath = path.join(appDir, appName + '-Info.plist');
    if (fs.existsSync(infoPath)) {
        var info = fs.readFileSync(infoPath, 'utf8');
        if (/<key>HSWAppGroup<\/key>/.test(info)) {
            info = info.replace(/(<key>HSWAppGroup<\/key>\s*<string>)[^<]*(<\/string>)/, '$1' + appGroup + '$2');
        } else {
            info = info.replace(END_OF_PLIST,
                '\t<key>HSWAppGroup</key>\n\t<string>' + appGroup + '</string>\n</dict>\n</plist>\n');
        }
        fs.writeFileSync(infoPath, info);
    }

    var groupBlock = '\t<key>com.apple.security.application-groups</key>\n\t<array>\n\t\t<string>' +
        appGroup + '</string>\n\t</array>\n';
    ['Entitlements-Debug.plist', 'Entitlements-Release.plist'].forEach(function (f) {
        var ePath = path.join(appDir, f);
        if (!fs.existsSync(ePath)) return;
        var e = fs.readFileSync(ePath, 'utf8');
        if (e.indexOf('<string>' + appGroup + '</string>') !== -1) return;
        var keyRe = /(<key>com\.apple\.security\.application-groups<\/key>\s*<array>)/;
        if (keyRe.test(e)) {
            e = e.replace(keyRe, '$1\n\t\t<string>' + appGroup + '</string>');
        } else if (/<dict\s*\/>/.test(e)) {
            e = e.replace(/<dict\s*\/>/, '<dict>\n' + groupBlock + '</dict>');
        } else {
            e = e.replace(END_OF_PLIST, groupBlock + '</dict>\n</plist>\n');
        }
        fs.writeFileSync(ePath, e);
    });
}

function readConfig(projectRoot) {
    var xml = fs.readFileSync(path.join(projectRoot, 'config.xml'), 'utf8');
    var widget = /<widget\b[^>]*>/.exec(xml);
    var tag = widget ? widget[0] : '';
    function attr(name) {
        var m = new RegExp('\\s' + name + '="([^"]*)"').exec(tag);
        return m ? m[1] : '';
    }
    var nameMatch = /<name[^>]*>([^<]*)<\/name>/.exec(xml);
    return {
        bundleId: attr('ios-CFBundleIdentifier') || attr('id'),
        packageName: attr('id'),
        version: attr('ios-CFBundleShortVersionString') || attr('version') || '1.0.0',
        build: attr('ios-CFBundleVersion') || attr('version') || '1',
        name: nameMatch ? nameMatch[1].trim() : 'App'
    };
}

function unquote(v) { return typeof v === 'string' ? v.replace(/^"(.*)"$/, '$1') : v; }

module.exports = function (context) {
    var projectRoot = context.opts.projectRoot;
    var iosDir = path.join(projectRoot, 'platforms', 'ios');
    if (!fs.existsSync(iosDir)) return;

    var projDir = fs.readdirSync(iosDir).filter(function (f) { return /\.xcodeproj$/.test(f); })[0];
    if (!projDir) { console.warn('[HomeScreenWidget] No .xcodeproj found.'); return; }
    var pbxPath = path.join(iosDir, projDir, 'project.pbxproj');

    var cfg = readConfig(projectRoot);
    // Optional override; by default the App Group is derived from the bundle id so the
    // consuming app needs no Extensibility Configuration of its own.
    var appGroup = readPreference(projectRoot, iosDir, 'HSWAppGroup') || ('group.' + cfg.bundleId);
    var profile = readPreference(projectRoot, iosDir, 'HSWWidgetProfile');
    patchMainApp(iosDir, projDir.replace(/\.xcodeproj$/, ''), appGroup);

    var xcode = loadXcode(projectRoot);
    var proj = xcode.project(pbxPath);
    proj.parseSync();

    var nativeTargets = proj.pbxNativeTargetSection();
    var exists = Object.keys(nativeTargets).some(function (k) {
        var t = nativeTargets[k];
        return t && typeof t === 'object' && unquote(t.name) === TARGET;
    });
    if (exists) {
        console.log('[HomeScreenWidget] Widget target already present.');
        return;
    }

    var widgetBundleId = cfg.bundleId + '.HomeWidget';

    // 1. Copy extension sources into platforms/ios/HomeScreenWidgetExtension
    var pluginDir = context.opts.plugin ? context.opts.plugin.dir : path.join(projectRoot, 'plugins', PLUGIN_ID);
    var srcDir = path.join(pluginDir, 'src', 'ios', 'WidgetExtension');
    var dstDir = path.join(iosDir, TARGET);
    if (!fs.existsSync(dstDir)) fs.mkdirSync(dstDir);
    ['HomeScreenWidgetExtension.swift', 'Info.plist', 'HomeScreenWidgetExtension.entitlements'].forEach(function (f) {
        var text = fs.readFileSync(path.join(srcDir, f), 'utf8')
            .replace(/__APP_GROUP__/g, appGroup)
            .replace(/__SCHEME__/g, 'hsw-' + cfg.packageName)
            .replace(/__DISPLAY_NAME__/g, cfg.name)
            .replace(/__VERSION__/g, cfg.version)
            .replace(/__BUILD__/g, cfg.build);
        fs.writeFileSync(path.join(dstDir, f), text);
    });

    // 2. Create the app-extension target (xcode adds the dependency + embed phase)
    var target = proj.addTarget(TARGET, 'app_extension', TARGET, widgetBundleId);
    proj.addBuildPhase([], 'PBXSourcesBuildPhase', 'Sources', target.uuid);
    proj.addBuildPhase([], 'PBXResourcesBuildPhase', 'Resources', target.uuid);
    proj.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', target.uuid);

    var group = proj.addPbxGroup(['Info.plist', 'HomeScreenWidgetExtension.entitlements'], TARGET, TARGET);
    proj.addToPbxGroup(group.uuid, proj.getFirstProject().firstProject.mainGroup);
    proj.addSourceFile('HomeScreenWidgetExtension.swift', { target: target.uuid }, group.uuid);

    // 3. Build settings; copy signing team/style from the main app target
    var configs = proj.pbxXCBuildConfigurationSection();
    var appTeam = '', appSignStyle = '';
    Object.keys(configs).forEach(function (k) {
        var bs = configs[k].buildSettings;
        if (bs && unquote(bs.PRODUCT_BUNDLE_IDENTIFIER) === cfg.bundleId) {
            appTeam = appTeam || unquote(bs.DEVELOPMENT_TEAM) || '';
            appSignStyle = appSignStyle || unquote(bs.CODE_SIGN_STYLE) || '';
        }
    });
    Object.keys(configs).forEach(function (k) {
        var bs = configs[k].buildSettings;
        if (!bs || unquote(bs.PRODUCT_NAME) !== TARGET) return;
        bs.PRODUCT_BUNDLE_IDENTIFIER = '"' + widgetBundleId + '"';
        bs.INFOPLIST_FILE = '"' + TARGET + '/Info.plist"';
        bs.CODE_SIGN_ENTITLEMENTS = '"' + TARGET + '/HomeScreenWidgetExtension.entitlements"';
        bs.SWIFT_VERSION = '5.0';
        bs.IPHONEOS_DEPLOYMENT_TARGET = '15.0';
        bs.TARGETED_DEVICE_FAMILY = '"1,2"';
        bs.SKIP_INSTALL = 'YES';
        bs.CLANG_ENABLE_MODULES = 'YES';
        bs.LD_RUNPATH_SEARCH_PATHS = '"$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks"';
        bs.MARKETING_VERSION = cfg.version;
        bs.CURRENT_PROJECT_VERSION = cfg.build;
        if (appTeam) bs.DEVELOPMENT_TEAM = appTeam;
        if (appSignStyle) bs.CODE_SIGN_STYLE = appSignStyle;
        if (profile) bs.PROVISIONING_PROFILE_SPECIFIER = '"' + profile + '"';
    });

    // 4. Make sure the .appex is embedded (older xcode module versions skip this)
    var raw = proj.writeSync();
    if (raw.indexOf(TARGET + '.appex in Embed App Extensions') === -1 &&
        raw.indexOf(TARGET + '.appex in Copy Files') === -1) {
        proj.addBuildPhase([TARGET + '.appex'], 'PBXCopyFilesBuildPhase', 'Embed App Extensions',
            proj.getFirstTarget().uuid, 'app_extension');
    }

    fs.writeFileSync(pbxPath, proj.writeSync());
    console.log('[HomeScreenWidget] Added widget target ' + TARGET + ' (' + widgetBundleId + ').');
};
