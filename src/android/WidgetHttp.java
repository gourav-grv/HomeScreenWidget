package io.homescreenwidget.plugin;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Small blocking HTTP helper (call it off the main thread). */
final class WidgetHttp {
    private WidgetHttp() { }

    /** Returns the response body, or null on network/HTTP error. */
    static String request(Context c, String url, String method, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            String m = (method == null || method.isEmpty()) ? (body == null || body.isEmpty() ? "GET" : "POST") : method.toUpperCase();
            conn.setRequestMethod(m);
            conn.setRequestProperty("Accept", "application/json");
            JSONObject headers = WidgetStore.headers(c);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                conn.setRequestProperty(k, headers.optString(k, ""));
            }
            if (body != null && !body.isEmpty() && !"GET".equals(m)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Runs a request and, if the response is widget data, stores it (in the slot of its Size, or of
     * defaultSize when the response has none). Returns true when data changed.
     */
    static boolean requestAndStore(Context c, String url, String method, String body, String defaultSize) {
        String resp = request(c, url, method, body);
        if (resp == null) return false;
        JSONObject o = WidgetStore.parse(resp.trim());
        if (WidgetStore.isWidgetData(o)) {
            WidgetStore.save(c, o.toString(), defaultSize);
            return true;
        }
        return false;
    }
}
