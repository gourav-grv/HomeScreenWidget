package io.homescreenwidget.plugin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Base class of the static demo widgets (design gallery). Each subclass only names its layout.
 * Independent of the data-driven GenericWidgetProvider: it never reads or writes widget data.
 * Tapping a demo opens the app.
 */
public abstract class HswDemoProvider extends AppWidgetProvider {

    protected abstract String layoutName();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        String pkg = context.getPackageName();
        int layout = context.getResources().getIdentifier(layoutName(), "layout", pkg);
        int root = context.getResources().getIdentifier("hswd_root", "id", pkg);
        if (layout == 0) return;
        Intent open = context.getPackageManager().getLaunchIntentForPackage(pkg);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(pkg, layout);
            if (open != null && root != 0) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                v.setOnClickPendingIntent(root, PendingIntent.getActivity(context, 50000 + id, open, flags));
            }
            manager.updateAppWidget(id, v);
        }
    }
}
