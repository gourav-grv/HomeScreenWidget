package io.homescreenwidget.plugin;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Supplies the widget rows. Row types (WidgetRow.Type):
 *   "item"     (default) check mark + Text + Subtext + up to 2 buttons
 *   "text"     plain text line (Text, optional Subtext)
 *   "progress" Text + progress bar (Progress 0-100)
 *   "image"    image from ImageUrl (+ optional Text caption)
 */
public class WidgetListService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        String key = intent == null ? null : intent.getStringExtra(WidgetStore.EXTRA_KEY);
        return new Factory(getApplicationContext(), WidgetStore.key(key));
    }

    static class Factory implements RemoteViewsFactory {
        private final Context context;
        private final String key;
        private JSONArray rows = new JSONArray();
        private int accent = WidgetStore.DEFAULT_ACCENT;
        private String dataDeepLink = "";

        Factory(Context context, String key) {
            this.context = context;
            this.key = key;
        }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            JSONObject d = WidgetStore.load(context, key);
            JSONArray a = d.optJSONArray("Rows");
            rows = a == null ? new JSONArray() : a;
            accent = WidgetStore.accent(d);
            dataDeepLink = d.optString("DeepLink", "");
        }

        @Override public void onDestroy() { rows = new JSONArray(); }

        @Override public int getCount() { return Math.min(rows.length(), WidgetStore.MAX_ROWS); }

        @Override public int getViewTypeCount() { return 4; }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public long getItemId(int position) { return position; }

        @Override public boolean hasStableIds() { return false; }

        @Override
        public RemoteViews getViewAt(int position) {
            JSONObject row = rows.optJSONObject(position);
            if (row == null) row = new JSONObject();
            String type = row.optString("Type", "item").toLowerCase();
            RemoteViews v;
            switch (type) {
                case "text": v = textRow(row); break;
                case "progress": v = progressRow(row); break;
                case "image": v = imageRow(row); break;
                default: v = itemRow(row); break;
            }
            // Tapping the row itself: openApp with this row's id
            JSONObject tap = new JSONObject();
            try {
                tap.put("ActionType", "openApp");
                tap.put("ActionName", "RowTap");
                tap.put("RowId", row.optString("Id", ""));
                tap.put("WidgetKey", key);
                String dl = row.optString("DeepLink", "");
                tap.put("DeepLink", dl.isEmpty() ? dataDeepLink : dl);
            } catch (Exception ignored) { }
            v.setOnClickFillInIntent(id("hsw_row_root"), fill(tap));
            return v;
        }

        private RemoteViews itemRow(JSONObject row) {
            RemoteViews v = new RemoteViews(context.getPackageName(), layout("hsw_row_item"));
            boolean done = row.optBoolean("IsDone", false);
            v.setTextViewText(id("hsw_row_check"), done ? "✔" : "○");
            v.setTextColor(id("hsw_row_check"), done ? 0xFF9AA0A6 : accent);
            v.setTextViewText(id("hsw_row_text"), row.optString("Text", ""));
            v.setTextColor(id("hsw_row_text"), done ? 0xFF9AA0A6 : 0xFF202124);
            optional(v, "hsw_row_subtext", row.optString("Subtext", ""));

            JSONArray buttons = row.optJSONArray("Buttons");
            String[] ids = {"hsw_row_btn1", "hsw_row_btn2"};
            for (int i = 0; i < ids.length; i++) {
                JSONObject b = buttons == null ? null : buttons.optJSONObject(i);
                if (b == null) {
                    v.setViewVisibility(id(ids[i]), View.GONE);
                    continue;
                }
                v.setViewVisibility(id(ids[i]), View.VISIBLE);
                v.setTextViewText(id(ids[i]), b.optString("Label", "→"));
                v.setTextColor(id(ids[i]), accent);
                v.setOnClickFillInIntent(id(ids[i]), fill(buttonAction(row, b)));
            }
            return v;
        }

        private RemoteViews textRow(JSONObject row) {
            RemoteViews v = new RemoteViews(context.getPackageName(), layout("hsw_row_text"));
            v.setTextViewText(id("hsw_row_text"), row.optString("Text", ""));
            optional(v, "hsw_row_subtext", row.optString("Subtext", ""));
            return v;
        }

        private RemoteViews progressRow(JSONObject row) {
            RemoteViews v = new RemoteViews(context.getPackageName(), layout("hsw_row_progress"));
            int p = Math.max(0, Math.min(100, row.optInt("Progress", 0)));
            v.setTextViewText(id("hsw_row_text"), row.optString("Text", ""));
            v.setTextViewText(id("hsw_row_value"), p + "%");
            v.setTextColor(id("hsw_row_value"), accent);
            v.setProgressBar(id("hsw_row_progress"), 100, p, false);
            return v;
        }

        private RemoteViews imageRow(JSONObject row) {
            RemoteViews v = new RemoteViews(context.getPackageName(), layout("hsw_row_image"));
            Bitmap bmp = download(row.optString("ImageUrl", ""));
            if (bmp != null) {
                v.setImageViewBitmap(id("hsw_row_image"), bmp);
                v.setViewVisibility(id("hsw_row_image"), View.VISIBLE);
            } else {
                v.setViewVisibility(id("hsw_row_image"), View.GONE);
            }
            optional(v, "hsw_row_text", row.optString("Text", ""));
            return v;
        }

        /** Runs on a binder thread, so a blocking download is allowed here. */
        private Bitmap download(String url) {
            if (url == null || url.isEmpty()) return null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (InputStream is = conn.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(is);
                    if (b == null) return null;
                    int max = 600; // keep the RemoteViews payload small
                    if (b.getWidth() > max) {
                        int h = Math.round(b.getHeight() * (max / (float) b.getWidth()));
                        b = Bitmap.createScaledBitmap(b, max, Math.max(1, h), true);
                    }
                    return b;
                }
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private JSONObject buttonAction(JSONObject row, JSONObject b) {
            JSONObject a = new JSONObject();
            try {
                a.put("ActionType", b.optString("ActionType", "openApp"));
                a.put("ActionName", b.optString("ActionName", ""));
                a.put("RowId", row.optString("Id", ""));
                a.put("WidgetKey", key);
                a.put("ButtonId", b.optString("Id", ""));
                a.put("Payload", b.optString("Payload", ""));
                a.put("Url", b.optString("Url", ""));
                a.put("Method", b.optString("Method", ""));
                a.put("Body", b.optString("Body", ""));
                String dl = row.optString("DeepLink", "");
                a.put("DeepLink", dl.isEmpty() ? dataDeepLink : dl);
            } catch (Exception ignored) { }
            return a;
        }

        private Intent fill(JSONObject action) {
            Intent i = new Intent();
            i.putExtra(WidgetStore.EXTRA_ACTION, action.toString());
            return i;
        }

        private void optional(RemoteViews v, String name, String text) {
            if (text == null || text.isEmpty()) {
                v.setViewVisibility(id(name), View.GONE);
            } else {
                v.setViewVisibility(id(name), View.VISIBLE);
                v.setTextViewText(id(name), text);
            }
        }

        private int id(String name) { return WidgetStore.res(context, "id", name); }

        private int layout(String name) { return WidgetStore.res(context, "layout", name); }
    }
}
