'use strict';
/*
 * Shared helper: reads the widget types from the app's config.xml preference
 *   <preference name="HSWWidgetTypes" value="pending|Pending Tasks;done|Done Today" />
 * Each entry is "key|Label". Keys: letters, digits, underscore. Without the preference there is
 * one widget type with key "main" labelled with the app name.
 */
var fs = require('fs');
var path = require('path');

function readPreference(files, name) {
    var re = new RegExp('<preference\\s+name="' + name + '"\\s+value="([^"]*)"', 'i');
    for (var i = files.length - 1; i >= 0; i--) {
        try {
            var m = re.exec(fs.readFileSync(files[i], 'utf8'));
            if (m && m[1].trim()) return m[1].trim();
        } catch (e) { /* ignore */ }
    }
    return '';
}

function sanitizeKey(raw) {
    var k = String(raw || '').trim().replace(/[^A-Za-z0-9_]/g, '_');
    return k || 'main';
}

function decodeXml(s) {
    return String(s).replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}

function parseTypes(value) {
    var types = [];
    var seen = {};
    String(value || '').split(';').forEach(function (part) {
        part = part.trim();
        if (!part) return;
        var bits = part.split('|');
        var key = sanitizeKey(bits[0]);
        if (seen[key]) return;
        seen[key] = true;
        var label = (bits.slice(1).join('|') || '').trim();
        types.push({ key: key, label: label });
    });
    if (!types.length) types.push({ key: 'main', label: '' });
    return types;
}

/** files: config.xml paths to search (root first, platform last wins). */
function readTypes(files) {
    // config.xml stores the value XML-escaped (e.g. &apos;); decode before splitting on ';'
    return parseTypes(decodeXml(readPreference(files, 'HSWWidgetTypes')));
}

function escapeXml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, "\\'");
}

module.exports = { readTypes: readTypes, readPreference: readPreference, parseTypes: parseTypes, sanitizeKey: sanitizeKey, escapeXml: escapeXml };
