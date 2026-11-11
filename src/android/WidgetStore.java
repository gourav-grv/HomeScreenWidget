package io.homescreenwidget.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

/** Storage shared by the plugin, the widget providers and the action activity. Data is stored per widget type (key). */
final class WidgetStore {
    private static final String PREFS = "hsw_widget_prefs";
    private static final String KEY_DATA = "data_";
    private static final String KEY_HEADERS = "headers";
    private static final String KEY_PENDING = "pending";
    static final String EXTRA_ACTION = "hsw_action";
    static final String EXTRA_KEY = "hsw_key";
    static final String DEFAULT_KEY = "main";
    static final String PROVIDER_PREFIX = "HswWidget_";
    static final int MAX_ROWS = 25;
    static final int DEFAULT_ACCENT = 0xFF1068EB;

    private WidgetStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Widget type keys are limited to letters, digits and underscore (they become class names). */
    static String key(String raw) {
        String k = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]", "_");
        return k.isEmpty() ? DEFAULT_KEY : k;
    }

    /** io.homescreenwidget.plugin.HswWidget_pending -> "pending" */
    static String keyFromClassName(String className) {
        int i = className == null ? -1 : className.lastIndexOf(PROVIDER_PREFIX);
        return i < 0 ? DEFAULT_KEY : key(className.substring(i + PROVIDER_PREFIX.length()));
    }

    static String providerClassName(String key) {
        return "io.homescreenwidget.plugin." + PROVIDER_PREFIX + key(key);
    }

    // ---- widget data (per key)
    static void save(Context c, String key, String json) { prefs(c).edit().putString(KEY_DATA + key(key), json).apply(); }

    static void clear(Context c, String key) { prefs(c).edit().remove(KEY_DATA + key(key)).apply(); }

    static JSONObject load(Context c, String key) { return parse(prefs(c).getString(KEY_DATA + key(key), null)); }

    // ---- HTTP headers (global)
    static void setHeaders(Context c, String json) { prefs(c).edit().putString(KEY_HEADERS, json).apply(); }

    static JSONObject headers(Context c) { return parse(prefs(c).getString(KEY_HEADERS, null)); }

    // ---- actions waiting for the app
    static synchronized void addPending(Context c, JSONObject action) {
        JSONArray a = parseArray(prefs(c).getString(KEY_PENDING, null));
        a.put(action);
        JSONArray trimmed = new JSONArray();
        for (int i = Math.max(0, a.length() - 20); i < a.length(); i++) trimmed.put(a.opt(i));
        prefs(c).edit().putString(KEY_PENDING, trimmed.toString()).commit();
    }

    static synchronized JSONArray takePending(Context c) {
        JSONArray a = parseArray(prefs(c).getString(KEY_PENDING, null));
        prefs(c).edit().remove(KEY_PENDING).commit();
        return a;
    }

    // ---- helpers
    static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }

    static JSONArray parseArray(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    static int accent(JSONObject d) {
        String hex = d.optString("AccentColor", "").trim();
        if (hex.isEmpty()) return DEFAULT_ACCENT;
        try {
            return Color.parseColor(hex.startsWith("#") ? hex : "#" + hex);
        } catch (Exception e) {
            return DEFAULT_ACCENT;
        }
    }

    static boolean isWidgetData(JSONObject o) {
        return o != null && (o.has("Rows") || o.has("Title"));
    }

    static int res(Context c, String type, String name) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }
}
