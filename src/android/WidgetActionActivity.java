package io.homescreenwidget.plugin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * Invisible activity that receives every widget tap (activities started from a widget are
 * always allowed to run, unlike broadcasts on Android 12+). It decides what to do and finishes.
 *
 *  - http:         sends the request in the background (via WidgetHttpReceiver) - app stays closed
 *  - clientAction: if the app is running and listening, delivers the action to JavaScript
 *                  (app stays in background); otherwise queues it and opens the app
 *  - openApp:      queues/delivers the action (so the app knows which row) and opens the app
 */
public class WidgetActionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent());
        } catch (Exception ignored) {
            // never crash on a widget tap
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void handle(Intent intent) {
        String raw = intent == null ? null : intent.getStringExtra(WidgetStore.EXTRA_ACTION);
        JSONObject a = WidgetStore.parse(raw);
        String type = a.optString("ActionType", "openApp");

        if ("http".equalsIgnoreCase(type)) {
            Intent b = new Intent(this, WidgetHttpReceiver.class);
            b.setAction(WidgetHttpReceiver.ACTION_HTTP);
            b.putExtra(WidgetStore.EXTRA_ACTION, a.toString());
            sendBroadcast(b);
            return;
        }

        JSONObject event = toEvent(a);
        if ("clientAction".equalsIgnoreCase(type)) {
            if (HomeScreenWidgetPlugin.deliverIfListening(event)) return; // handled in background
            WidgetStore.addPending(this, event);
            openApp(a.optString("DeepLink", ""));
            return;
        }

        // openApp (default) - tell the app which row/button was tapped, then open it
        if (!HomeScreenWidgetPlugin.deliverIfListening(event)) WidgetStore.addPending(this, event);
        openApp(a.optString("DeepLink", ""));
    }

    /** The object handed to the OutSystems OnWidgetAction event. */
    private static JSONObject toEvent(JSONObject a) {
        JSONObject e = new JSONObject();
        try {
            e.put("ActionType", a.optString("ActionType", "openApp"));
            e.put("ActionName", a.optString("ActionName", ""));
            e.put("WidgetKey", a.optString("WidgetKey", ""));
            e.put("RowId", a.optString("RowId", ""));
            e.put("ButtonId", a.optString("ButtonId", ""));
            e.put("Payload", a.optString("Payload", ""));
        } catch (Exception ignored) { }
        return e;
    }

    private void openApp(String deepLink) {
        Intent open = null;
        if (deepLink != null && !deepLink.isEmpty()) {
            open = new Intent(Intent.ACTION_VIEW, Uri.parse(deepLink));
            open.setPackage(getPackageName());
            if (open.resolveActivity(getPackageManager()) == null) open = null;
        }
        if (open == null) open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) return;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
    }
}
