package io.homescreenwidget.plugin;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;

/** JS bridge: window.HomeScreenWidget.* */
public class HomeScreenWidgetPlugin extends CordovaPlugin {

    private static volatile HomeScreenWidgetPlugin instance;
    private volatile CallbackContext listener;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        instance = this;
    }

    @Override
    public void onReset() {
        listener = null; // page reloaded - JS listeners are gone
        super.onReset();
    }

    @Override
    public void onDestroy() {
        listener = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** Called by WidgetActionActivity. Returns true when the running app received the action. */
    static boolean deliverIfListening(JSONObject event) {
        HomeScreenWidgetPlugin p = instance;
        if (p == null) return false;
        CallbackContext l = p.listener;
        if (l == null) return false;
        PluginResult r = new PluginResult(PluginResult.Status.OK, event);
        r.setKeepCallback(true);
        l.sendPluginResult(r);
        return true;
    }

    private Context context() { return cordova.getActivity().getApplicationContext(); }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext cb) {
        switch (action) {
            case "listen":
                listener = cb;
                JSONArray pending = WidgetStore.takePending(context());
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject e = pending.optJSONObject(i);
                    if (e == null) continue;
                    PluginResult r = new PluginResult(PluginResult.Status.OK, e);
                    r.setKeepCallback(true);
                    cb.sendPluginResult(r);
                }
                PluginResult keep = new PluginResult(PluginResult.Status.NO_RESULT);
                keep.setKeepCallback(true);
                cb.sendPluginResult(keep);
                return true;
            case "update":
            case "clear":
            case "isSupported":
            case "requestPin":
            case "isPlaced":
            case "setHeaders":
            case "refresh":
                cordova.getThreadPool().execute(() -> run(action, args, cb));
                return true;
            default:
                return false;
        }
    }

    private void run(String action, JSONArray args, CallbackContext cb) {
        try {
            Context c = context();
            switch (action) {
                case "update": {
                    String json = args.optString(0, "{}");
                    new JSONObject(json); // validate
                    WidgetStore.save(c, json);
                    GenericWidgetProvider.refreshAll(c);
                    cb.success();
                    break;
                }
                case "clear":
                    WidgetStore.clear(c);
                    GenericWidgetProvider.refreshAll(c);
                    cb.success();
                    break;
                case "setHeaders": {
                    String json = args.optString(0, "{}");
                    new JSONObject(json);
                    WidgetStore.setHeaders(c, json);
                    cb.success();
                    break;
                }
                case "refresh": {
                    // Every size that has its own data with a RefreshUrl
                    for (String size : WidgetStore.SIZES) {
                        String url = WidgetStore.loadOwn(c, size).optString("RefreshUrl", "");
                        if (!url.isEmpty()) WidgetHttp.requestAndStore(c, url, "GET", null, size);
                    }
                    GenericWidgetProvider.refreshAll(c);
                    cb.success();
                    break;
                }
                case "isSupported":
                    cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, true));
                    break;
                case "requestPin": {
                    // args[0] = size ("small" | "medium" | "large"); empty = medium
                    boolean ok = false;
                    if (Build.VERSION.SDK_INT >= 26) {
                        AppWidgetManager m = AppWidgetManager.getInstance(c);
                        if (m.isRequestPinAppWidgetSupported()) {
                            Class<?> provider = WidgetStore.providerFor(args.optString(0, ""));
                            ok = m.requestPinAppWidget(new ComponentName(c, provider), null, null);
                        }
                    }
                    cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, ok));
                    break;
                }
                case "isPlaced": {
                    // True when a widget of this size is on the home screen
                    boolean placed = GenericWidgetProvider.widgetIds(c, args.optString(0, "")).length > 0;
                    cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, placed));
                    break;
                }
                default:
                    cb.error("Unknown action " + action);
            }
        } catch (Exception e) {
            cb.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
