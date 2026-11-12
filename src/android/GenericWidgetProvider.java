package io.homescreenwidget.plugin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Renders the stored JSON (header + rows) and runs http actions / RefreshUrl in the background. */
public class GenericWidgetProvider extends AppWidgetProvider {

    static final String ACTION_HTTP = "io.homescreenwidget.plugin.ACTION_HTTP";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) render(context, manager, id);
        manager.notifyAppWidgetViewDataChanged(ids, WidgetStore.res(context, "id", "hsw_list"));

        // Periodic refresh: if the data has a RefreshUrl, fetch fresh data without opening the app.
        final String refreshUrl = WidgetStore.load(context).optString("RefreshUrl", "");
        if (!refreshUrl.isEmpty()) {
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    if (WidgetHttp.requestAndStore(app, refreshUrl, "GET", null)) refreshAll(app);
                } finally {
                    pending.finish();
                }
            }).start();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_HTTP.equals(intent.getAction())) {
            final JSONObject a = WidgetStore.parse(intent.getStringExtra(WidgetStore.EXTRA_ACTION));
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    String url = a.optString("Url", "");
                    if (!url.isEmpty()) {
                        boolean changed = WidgetHttp.requestAndStore(app, url, a.optString("Method", ""), a.optString("Body", ""));
                        // Response was not widget data: re-read from RefreshUrl if there is one.
                        if (!changed) {
                            String refreshUrl = WidgetStore.load(app).optString("RefreshUrl", "");
                            if (!refreshUrl.isEmpty()) WidgetHttp.requestAndStore(app, refreshUrl, "GET", null);
                        }
                    }
                    refreshAll(app);
                } finally {
                    pending.finish();
                }
            }).start();
            return;
        }
        super.onReceive(context, intent);
    }

    /** Re-renders every placed widget of this app. */
    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, GenericWidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        for (int id : ids) render(context, manager, id);
        manager.notifyAppWidgetViewDataChanged(ids, WidgetStore.res(context, "id", "hsw_list"));
    }

    private static void render(Context c, AppWidgetManager manager, int widgetId) {
        JSONObject d = WidgetStore.load(c);
        // Custom UI (WidgetData.Nodes) -> node renderer; otherwise the classic header + rows layout.
        if (WidgetTreeRenderer.hasTree(d)) {
            WidgetTreeRenderer.render(c, manager, widgetId, d);
            return;
        }
        RemoteViews v = new RemoteViews(c.getPackageName(), WidgetStore.res(c, "layout", "hsw_widget"));
        int accent = WidgetStore.accent(d);

        String title = d.optString("Title", "");
        if (title.isEmpty()) title = c.getApplicationInfo().loadLabel(c.getPackageManager()).toString();
        v.setTextViewText(id(c, "hsw_title"), title);
        setOptionalText(c, v, "hsw_subtitle", d.optString("Subtitle", ""));
        v.setInt(id(c, "hsw_accent"), "setBackgroundColor", accent);

        String countLabel = d.optString("CountLabel", "");
        if (countLabel.isEmpty() && d.optInt("Count", 0) == 0) {
            v.setViewVisibility(id(c, "hsw_count_box"), View.GONE);
        } else {
            v.setViewVisibility(id(c, "hsw_count_box"), View.VISIBLE);
            v.setTextViewText(id(c, "hsw_count"), String.valueOf(d.optInt("Count", 0)));
            v.setTextColor(id(c, "hsw_count"), accent);
            v.setTextViewText(id(c, "hsw_count_label"), countLabel);
        }

        setOptionalText(c, v, "hsw_footer", d.optString("Footer", ""));
        String empty = d.optString("EmptyText", "");
        if (empty.isEmpty()) empty = c.getString(WidgetStore.res(c, "string", "hsw_empty_default"));
        v.setTextViewText(id(c, "hsw_empty"), empty);

        Intent svc = new Intent(c, WidgetListService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(id(c, "hsw_list"), svc);
        v.setEmptyView(id(c, "hsw_list"), id(c, "hsw_empty"));

        // Header / empty area / footer: open the app (event ActionType "openApp", RowId "")
        JSONObject open = new JSONObject();
        try {
            open.put("ActionType", "openApp");
            open.put("ActionName", "Open");
            open.put("DeepLink", d.optString("DeepLink", ""));
        } catch (Exception ignored) { }
        PendingIntent openPi = actionIntent(c, widgetId * 10 + 1, open.toString(), false);
        v.setOnClickPendingIntent(id(c, "hsw_header"), openPi);
        v.setOnClickPendingIntent(id(c, "hsw_empty"), openPi);
        v.setOnClickPendingIntent(id(c, "hsw_footer"), openPi);

        // Rows and row buttons: one template, each view adds its own action JSON as fill-in
        v.setPendingIntentTemplate(id(c, "hsw_list"), actionIntent(c, widgetId * 10 + 2, null, true));

        manager.updateAppWidget(widgetId, v);
    }

    private static PendingIntent actionIntent(Context c, int requestCode, String actionJson, boolean mutable) {
        Intent i = new Intent(c, WidgetActionActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (actionJson != null) i.putExtra(WidgetStore.EXTRA_ACTION, actionJson);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31 && mutable) flags |= PendingIntent.FLAG_MUTABLE;
        else if (Build.VERSION.SDK_INT >= 23 && !mutable) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, requestCode, i, flags);
    }

    private static void setOptionalText(Context c, RemoteViews v, String name, String text) {
        int vid = id(c, name);
        if (text == null || text.isEmpty()) {
            v.setViewVisibility(vid, View.GONE);
        } else {
            v.setViewVisibility(vid, View.VISIBLE);
            v.setTextViewText(vid, text);
        }
    }

    private static int id(Context c, String name) { return WidgetStore.res(c, "id", name); }
}
