package io.homescreenwidget.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Runs a widget "http" action in the background (the app is not opened). */
public class WidgetHttpReceiver extends BroadcastReceiver {

    static final String ACTION_HTTP = "io.homescreenwidget.plugin.ACTION_HTTP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_HTTP.equals(intent.getAction())) return;
        final JSONObject a = WidgetStore.parse(intent.getStringExtra(WidgetStore.EXTRA_ACTION));
        final String key = WidgetStore.key(a.optString("WidgetKey", ""));
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String url = a.optString("Url", "");
                if (!url.isEmpty()) {
                    boolean changed = WidgetHttp.requestAndStore(app, key, url, a.optString("Method", ""), a.optString("Body", ""));
                    if (!changed) {
                        // Response was not widget data: re-read from RefreshUrl if there is one.
                        String refreshUrl = WidgetStore.load(app, key).optString("RefreshUrl", "");
                        if (!refreshUrl.isEmpty()) WidgetHttp.requestAndStore(app, key, refreshUrl, "GET", null);
                    }
                }
                GenericWidgetProvider.refreshAll(app);
            } finally {
                pending.finish();
            }
        }).start();
    }
}
