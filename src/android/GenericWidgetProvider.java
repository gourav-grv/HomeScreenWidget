package io.homescreenwidget.plugin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Base widget provider. The build hook generates one subclass per widget type
 * (io.homescreenwidget.plugin.HswWidget_<key>) and registers it in the manifest;
 * the key is taken from the class name.
 */
public class GenericWidgetProvider extends AppWidgetProvider {

    /** Widget type key of this provider (from the generated class name). */
    protected String widgetKey() {
        return WidgetStore.keyFromClassName(getClass().getName());
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final String key = widgetKey();
        for (int id : ids) render(context, manager, id, key);
        manager.notifyAppWidgetViewDataChanged(ids, WidgetStore.res(context, "id", "hsw_list"));

        // Periodic refresh: if this type has a RefreshUrl, fetch fresh data without opening the app.
        final String refreshUrl = WidgetStore.load(context, key).optString("RefreshUrl", "");
        if (!refreshUrl.isEmpty()) {
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    if (WidgetHttp.requestAndStore(app, key, refreshUrl, "GET", null)) refreshKey(app, key);
                } finally {
                    pending.finish();
                }
            }).start();
        }
    }

    /** All widget provider components of this app (one per widget type). */
    static List<ComponentName> providers(Context c) {
        List<ComponentName> out = new ArrayList<>();
        Intent probe = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        probe.setPackage(c.getPackageName());
        List<ResolveInfo> list = c.getPackageManager().queryBroadcastReceivers(probe, 0);
        if (list == null) return out;
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            if (!ri.activityInfo.name.contains(WidgetStore.PROVIDER_PREFIX)) continue;
            out.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        }
        return out;
    }

    /** Re-renders every placed widget of every type. */
    static void refreshAll(Context context) {
        for (ComponentName cn : providers(context)) refreshComponent(context, cn);
    }

    /** Re-renders every placed widget of one type. */
    static void refreshKey(Context context, String key) {
        String cls = WidgetStore.providerClassName(key);
        for (ComponentName cn : providers(context)) {
            if (cn.getClassName().equals(cls)) refreshComponent(context, cn);
        }
    }

    private static void refreshComponent(Context context, ComponentName cn) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        String key = WidgetStore.keyFromClassName(cn.getClassName());
        for (int id : ids) render(context, manager, id, key);
        manager.notifyAppWidgetViewDataChanged(ids, WidgetStore.res(context, "id", "hsw_list"));
    }

    static int placedCount(Context context, String key) {
        int n = 0;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (ComponentName cn : providers(context)) {
            if (key == null || cn.getClassName().equals(WidgetStore.providerClassName(key))) {
                int[] ids = manager.getAppWidgetIds(cn);
                n += ids == null ? 0 : ids.length;
            }
        }
        return n;
    }

    private static void render(Context c, AppWidgetManager manager, int widgetId, String key) {
        JSONObject d = WidgetStore.load(c, key);
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
        svc.putExtra(WidgetStore.EXTRA_KEY, key);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(id(c, "hsw_list"), svc);
        v.setEmptyView(id(c, "hsw_list"), id(c, "hsw_empty"));

        // Header / empty area / footer: open the app
        JSONObject open = new JSONObject();
        try {
            open.put("ActionType", "openApp");
            open.put("ActionName", "Open");
            open.put("WidgetKey", key);
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
