var exec = require('cordova/exec');

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, 'HomeScreenWidget', action, args || []);
    });
}

var listeners = [];
var listening = false;

function dispatch(event) {
    if (typeof event === 'string') {
        try { event = JSON.parse(event); } catch (e) { return; }
    }
    if (!event || typeof event !== 'object') return;
    listeners.slice().forEach(function (cb) {
        try { cb(event); } catch (e) { console.error('[HomeScreenWidget] listener error', e); }
    });
}

/**
 * Widget data (all optional):
 * { Title, Subtitle, Count, CountLabel, Footer, EmptyText, DeepLink, AccentColor, RefreshUrl,
 *   Rows: [ { Id, Type: "item"|"text"|"progress"|"image", Text, Subtext, IsDone, Progress, ImageUrl, DeepLink,
 *             Buttons: [ { Id, Label, ActionType: "openApp"|"clientAction"|"http", ActionName, Payload, Url, Method, Body } ] } ] }
 *
 * Action event delivered to onAction listeners:
 * { ActionType, ActionName, RowId, ButtonId, Payload }
 */
/**
 * Widget types come from the app's config.xml preference HSWWidgetTypes ("key|Label;key2|Label2").
 * Every method below takes the widget type key; empty = "main" (the single default type).
 */
module.exports = {
    update: function (data, widgetKey) {
        var json = typeof data === 'string' ? data : JSON.stringify(data || {});
        return call('update', [json, widgetKey || '']);
    },
    clear: function (widgetKey) { return call('clear', [widgetKey || '']); },
    isSupported: function () { return call('isSupported'); },
    requestPin: function (widgetKey) { return call('requestPin', [widgetKey || '']); },
    /** Headers sent with http actions and RefreshUrl, e.g. { "Authorization": "Bearer ..." }. */
    setHeaders: function (headers) {
        var json = typeof headers === 'string' ? headers : JSON.stringify(headers || {});
        return call('setHeaders', [json]);
    },
    /** Fetches RefreshUrl now (if set) and re-renders the widget. */
    refresh: function (widgetKey) { return call('refresh', [widgetKey || '']); },
    /**
     * Registers a listener for widget taps (clientAction / openApp). Actions tapped while the app
     * was closed are delivered as soon as the first listener registers. Returns an unsubscribe function.
     */
    onAction: function (cb) {
        if (typeof cb !== 'function') return function () {};
        listeners.push(cb);
        if (!listening) {
            listening = true;
            exec(dispatch, function () { listening = false; }, 'HomeScreenWidget', 'listen', []);
        }
        return function () {
            var i = listeners.indexOf(cb);
            if (i >= 0) listeners.splice(i, 1);
        };
    }
};
