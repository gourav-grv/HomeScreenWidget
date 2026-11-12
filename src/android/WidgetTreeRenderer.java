package io.homescreenwidget.plugin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders WidgetData.Nodes (a flat list of nodes with Id / ParentId) as a native widget.
 *
 * Node types: row, column, stack (containers) and text, icon, image, ring, bar, divider, button,
 * checkbox, toggle, clock, spacer. Any node can carry an action (ActionType clientAction / http / openApp).
 */
final class WidgetTreeRenderer {
    static final int MAX_NODES = 80;
    private static final int DEFAULT_TEXT = 0xFF111827;
    private static final int DEFAULT_ACCENT = 0xFF6366F1;

    private WidgetTreeRenderer() { }

    static boolean hasTree(JSONObject d) {
        JSONArray a = d == null ? null : d.optJSONArray("Nodes");
        return a != null && a.length() > 0;
    }

    /** Renders on a worker thread when called from the main thread (images are downloaded). */
    static void render(final Context c, final AppWidgetManager manager, final int widgetId, final JSONObject d) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            new Thread(() -> renderNow(c, manager, widgetId, d)).start();
        } else {
            renderNow(c, manager, widgetId, d);
        }
    }

    private static void renderNow(Context c, AppWidgetManager manager, int widgetId, JSONObject d) {
        try {
            manager.updateAppWidget(widgetId, new Builder(c, widgetId, d, manager).build());
        } catch (Exception e) {
            // Bad data must never break the widget: show a minimal error view instead.
            RemoteViews v = new RemoteViews(c.getPackageName(), WidgetStore.res(c, "layout", "hswt_text"));
            v.setTextViewText(WidgetStore.res(c, "id", "hswt_v"), "Widget layout error");
            manager.updateAppWidget(widgetId, v);
        }
    }

    private static final class Builder {
        final Context c;
        final String pkg;
        final int widgetId;
        final JSONObject data;
        final float density;
        final int widthPx;
        final int heightPx;
        final Map<String, List<JSONObject>> children = new HashMap<>();
        final List<JSONObject> roots = new ArrayList<>();
        int requestCounter = 0;

        Builder(Context c, int widgetId, JSONObject data, AppWidgetManager manager) {
            this.c = c;
            this.pkg = c.getPackageName();
            this.widgetId = widgetId;
            this.data = data;
            this.density = c.getResources().getDisplayMetrics().density;
            Bundle o = manager.getAppWidgetOptions(widgetId);
            int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            this.widthPx = dp(wDp > 0 ? wDp : 250);
            this.heightPx = dp(hDp > 0 ? hDp : 110);

            JSONArray nodes = data.optJSONArray("Nodes");
            Map<String, JSONObject> byId = new HashMap<>();
            int n = Math.min(nodes.length(), MAX_NODES);
            for (int i = 0; i < n; i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node != null) byId.put(node.optString("Id", "n" + i), node);
            }
            for (int i = 0; i < n; i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) continue;
                String parent = node.optString("ParentId", "");
                if (parent.isEmpty() || !byId.containsKey(parent) || parent.equals(node.optString("Id", ""))) {
                    roots.add(node);
                } else {
                    List<JSONObject> list = children.get(parent);
                    if (list == null) { list = new ArrayList<>(); children.put(parent, list); }
                    list.add(node);
                }
            }
        }

        RemoteViews build() {
            RemoteViews root = new RemoteViews(pkg, layout("hswt_root"));
            JSONObject top;
            if (roots.size() == 1) {
                top = roots.get(0);
            } else {
                top = new JSONObject();
                try { top.put("Id", "__root"); top.put("Type", "column"); } catch (Exception ignored) { }
                children.put("__root", roots);
            }

            // Widget background: rounded bitmap of the top node's Background (default white card)
            String bg = top.optString("Background", "");
            int[] colors = parseColors(bg.isEmpty() ? "#FFFFFF" : bg);
            if (colors != null) {
                root.setImageViewBitmap(id("hswt_root_bg"),
                        WidgetBitmaps.background(widthPx, heightPx, dp(20), colors));
            }
            int pad = top.has("Padding") && top.optInt("Padding", 0) > 0 ? dp(top.optInt("Padding")) : dp(12);
            root.setViewPadding(id("hswt_root_content"), pad, pad, pad, pad);

            RemoteViews content = node(top, true, DEFAULT_TEXT);
            root.addView(id("hswt_root_content"), content);

            PendingIntent click = hasAction(top) ? action(top) : action(openAppNode());
            root.setOnClickPendingIntent(id("hswt_root"), click);
            return root;
        }

        RemoteViews node(JSONObject n, boolean isTop, int inheritedColor) {
            String type = n.optString("Type", "text").toLowerCase();
            int color = WidgetBitmaps.color(n.optString("Color", ""), inheritedColor);
            RemoteViews v;
            int rootId;
            switch (type) {
                case "row":
                case "column":
                case "stack":
                    v = container(n, type, color, isTop);
                    rootId = id("hswt_c");
                    if (!isTop) applyBackground(v, rootId, n.optString("Background", ""));
                    applyPadding(v, rootId, n, isTop ? 0 : -1);
                    break;
                case "image":
                    v = image(n);
                    rootId = id("hswt_v");
                    break;
                case "ring":
                    v = ring(n, inheritedColor);
                    rootId = id("hswt_c");
                    break;
                case "bar":
                case "divider":
                    v = bar(n, type.equals("divider"));
                    rootId = id("hswt_v");
                    break;
                case "checkbox":
                case "toggle":
                    v = check(n, type.equals("toggle"), color);
                    rootId = id("hswt_c");
                    break;
                case "button":
                    v = new RemoteViews(pkg, layout("hswt_button"));
                    rootId = id("hswt_v");
                    v.setTextViewText(rootId, n.optString("Text", ""));
                    v.setTextColor(rootId, WidgetBitmaps.color(n.optString("Color", ""), 0xFFFFFFFF));
                    textSize(v, rootId, n, 13);
                    String bbg = n.optString("Background", "");
                    applyBackground(v, rootId, bbg.isEmpty() ? "accent" : bbg);
                    applyPadding(v, rootId, n, -1);
                    break;
                case "clock":
                    v = new RemoteViews(pkg, layout(n.optBoolean("Bold", false) ? "hswt_clock_bold" : "hswt_clock"));
                    rootId = id("hswt_v");
                    String fmt = n.optString("Text", "");
                    if (fmt.isEmpty()) fmt = "HH:mm";
                    v.setCharSequence(rootId, "setFormat12Hour", fmt);
                    v.setCharSequence(rootId, "setFormat24Hour", fmt);
                    v.setTextColor(rootId, color);
                    textSize(v, rootId, n, 28);
                    break;
                case "spacer":
                    v = new RemoteViews(pkg, layout("hswt_spacer"));
                    rootId = id("hswt_v");
                    int sw = dp(n.optInt("Width", 0));
                    int sh = dp(n.optInt("Height", 0));
                    v.setViewPadding(rootId, sw, sh, 0, 0);
                    break;
                case "icon":
                case "text":
                default:
                    boolean bold = n.optBoolean("Bold", false);
                    v = new RemoteViews(pkg, layout(bold ? "hswt_text_bold" : "hswt_text"));
                    rootId = id("hswt_v");
                    v.setTextViewText(rootId, n.optString("Text", ""));
                    v.setTextColor(rootId, color);
                    textSize(v, rootId, n, type.equals("icon") ? 20 : 13);
                    v.setInt(rootId, "setGravity", gravity(n.optString("Align", ""), Gravity.START | Gravity.CENTER_VERTICAL));
                    applyBackground(v, rootId, n.optString("Background", ""));
                    applyPadding(v, rootId, n, -1);
                    break;
            }
            if (hasAction(n)) v.setOnClickPendingIntent(rootId, action(n));
            return v;
        }

        RemoteViews container(JSONObject n, String type, int color, boolean isTop) {
            // The top container fills the whole widget so children with Weight can share the height
            // (e.g. a list that shrinks while a footer stays visible).
            RemoteViews v = new RemoteViews(pkg, layout("hswt_" + type + (isTop ? "_fill" : "")));
            int cid = id("hswt_c");
            int defaultGravity = type.equals("row") ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.START | Gravity.TOP;
            if (!type.equals("stack")) v.setInt(cid, "setGravity", gravity(n.optString("Align", ""), defaultGravity));
            List<JSONObject> kids = children.get(n.optString("Id", ""));
            if (kids == null) return v;
            int gap = dp(n.optInt("Gap", 0));
            boolean first = true;
            for (JSONObject child : kids) {
                double weight = child.optDouble("Weight", 0);
                String ctype = child.optString("Type", "").toLowerCase();
                if (ctype.equals("spacer") && child.optInt("Width", 0) == 0 && child.optInt("Height", 0) == 0 && weight <= 0) {
                    weight = 1; // a spacer without a size fills the free space
                }
                String slotName;
                if (type.equals("stack")) slotName = "hswt_slot_stack";
                else if (type.equals("row")) slotName = weight > 0 ? "hswt_slot_flex_h" : "hswt_slot";
                else slotName = weight > 0 ? "hswt_slot_flex_v" : "hswt_slot_fill_w";
                RemoteViews slot = new RemoteViews(pkg, layout(slotName));
                int sid = id("hswt_slot");
                String align = child.optString("Align", "");
                int g;
                if (type.equals("stack")) g = gravity(align, Gravity.CENTER);
                else if (type.equals("row")) g = Gravity.CENTER_VERTICAL | horizontal(align, Gravity.START);
                else g = horizontal(align, Gravity.START) | Gravity.CENTER_VERTICAL;
                slot.setInt(sid, "setGravity", g);
                if (!first && gap > 0 && !type.equals("stack")) {
                    if (type.equals("row")) slot.setViewPadding(sid, gap, 0, 0, 0);
                    else slot.setViewPadding(sid, 0, gap, 0, 0);
                }
                slot.addView(sid, node(child, false, color));
                v.addView(cid, slot);
                first = false;
            }
            return v;
        }

        RemoteViews image(JSONObject n) {
            RemoteViews v = new RemoteViews(pkg, layout("hswt_image"));
            int vid = id("hswt_v");
            int w = n.optInt("Width", 0) > 0 ? dp(n.optInt("Width")) : 0;
            int h = n.optInt("Height", 0) > 0 ? dp(n.optInt("Height")) : 0;
            Bitmap b = WidgetBitmaps.download(n.optString("ImageUrl", ""), w > 0 ? w : Math.min(widthPx, dp(320)));
            if (b != null && (w > 0 || h > 0)) {
                int tw = w > 0 ? w : Math.round(b.getWidth() * (h / (float) b.getHeight()));
                int th = h > 0 ? h : Math.round(b.getHeight() * (w / (float) b.getWidth()));
                b = Bitmap.createScaledBitmap(b, Math.max(1, tw), Math.max(1, th), true);
            }
            if (b != null) v.setImageViewBitmap(vid, b);
            return v;
        }

        RemoteViews ring(JSONObject n, int labelColor) {
            RemoteViews v = new RemoteViews(pkg, layout("hswt_ring"));
            int size = dp(n.optInt("Width", 0) > 0 ? n.optInt("Width") : (n.optInt("Height", 0) > 0 ? n.optInt("Height") : 64));
            int ringColor = WidgetBitmaps.color(n.optString("Color", ""), DEFAULT_ACCENT);
            int track = (ringColor & 0x00FFFFFF) | 0x33000000;
            v.setImageViewBitmap(id("hswt_v"), WidgetBitmaps.ring(size, fraction(n), ringColor, track, Math.max(dp(4), size / 10f)));
            int tid = id("hswt_t");
            String label = n.optString("Text", "");
            v.setTextViewText(tid, label);
            v.setTextColor(tid, labelColor);
            int fs = n.optInt("FontSize", 0);
            v.setTextViewTextSize(tid, TypedValue.COMPLEX_UNIT_SP, fs > 0 ? fs : Math.max(10, size / density / 5f));
            return v;
        }

        RemoteViews bar(JSONObject n, boolean divider) {
            RemoteViews v = new RemoteViews(pkg, layout("hswt_bar"));
            int h = dp(n.optInt("Height", 0) > 0 ? n.optInt("Height") : (divider ? 1 : 6));
            int color = WidgetBitmaps.color(n.optString("Color", ""), divider ? 0xFFE5E7EB : DEFAULT_ACCENT);
            int track = divider ? color : WidgetBitmaps.color(n.optString("Background", ""), (color & 0x00FFFFFF) | 0x33000000);
            float f = divider ? 1f : fraction(n);
            v.setImageViewBitmap(id("hswt_v"), WidgetBitmaps.bar(Math.max(widthPx, dp(40)), h, f, color, track));
            return v;
        }

        RemoteViews check(JSONObject n, boolean toggle, int inheritedColor) {
            RemoteViews v = new RemoteViews(pkg, layout("hswt_check"));
            int accent = WidgetBitmaps.color(n.optString("Background", ""), DEFAULT_ACCENT);
            boolean on = n.optBoolean("Checked", false);
            Bitmap b = toggle ? WidgetBitmaps.toggle(dp(40), dp(22), on, accent)
                    : WidgetBitmaps.checkbox(dp(n.optInt("Height", 0) > 0 ? n.optInt("Height") : 20), on, accent);
            v.setImageViewBitmap(id("hswt_v"), b);
            int tid = id("hswt_t");
            v.setTextViewText(tid, n.optString("Text", ""));
            v.setTextColor(tid, on && !toggle ? 0xFF9CA3AF : WidgetBitmaps.color(n.optString("Color", ""), inheritedColor));
            textSize(v, tid, n, 14);
            return v;
        }

        // ---- helpers

        float fraction(JSONObject n) {
            double max = n.optDouble("Max", 0);
            if (Double.isNaN(max) || max <= 0) max = 100;
            double val = n.optDouble("Value", 0);
            if (Double.isNaN(val)) val = 0;
            return (float) Math.max(0, Math.min(1, val / max));
        }

        void textSize(RemoteViews v, int vid, JSONObject n, int def) {
            int fs = n.optInt("FontSize", 0);
            v.setTextViewTextSize(vid, TypedValue.COMPLEX_UNIT_SP, fs > 0 ? fs : def);
        }

        /** Padding: node.Padding (dp). fallback &lt; 0 = leave the layout default. */
        void applyPadding(RemoteViews v, int vid, JSONObject n, int fallback) {
            int p = n.optInt("Padding", 0);
            if (p > 0) v.setViewPadding(vid, dp(p), dp(p), dp(p), dp(p));
            else if (fallback >= 0) v.setViewPadding(vid, fallback, fallback, fallback, fallback);
        }

        /** Background: token (card, light, dark, glass, chip, accent, circle), "#RRGGBB" or "#A,#B" (first colour used). */
        void applyBackground(RemoteViews v, int vid, String spec) {
            if (spec == null || spec.trim().isEmpty()) return;
            spec = spec.trim();
            if (!spec.startsWith("#")) {
                int res = WidgetStore.res(c, "drawable", "hswt_bg_" + spec.toLowerCase());
                if (res != 0) v.setInt(vid, "setBackgroundResource", res);
                return;
            }
            int[] colors = parseColors(spec);
            if (colors == null) return;
            if (Build.VERSION.SDK_INT >= 31) {
                v.setInt(vid, "setBackgroundResource", WidgetStore.res(c, "drawable", "hswt_bg_tint"));
                v.setColorStateList(vid, "setBackgroundTintList", ColorStateList.valueOf(colors[0]));
            } else {
                v.setInt(vid, "setBackgroundColor", colors[0]);
            }
        }

        int[] parseColors(String spec) {
            if (spec == null || !spec.trim().startsWith("#")) {
                // tokens used as widget background
                if ("dark".equalsIgnoreCase(spec)) return new int[]{0xFF0F172A, 0xFF1E293B};
                if ("light".equalsIgnoreCase(spec)) return new int[]{0xFFF3F4F6};
                if ("none".equalsIgnoreCase(spec) || "transparent".equalsIgnoreCase(spec)) return new int[]{0x00000000};
                return new int[]{0xFFFFFFFF};
            }
            String[] parts = spec.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = WidgetBitmaps.color(parts[i], 0xFFFFFFFF);
            return out;
        }

        int gravity(String align, int def) {
            if (align == null) return def;
            switch (align.toLowerCase()) {
                case "center": return Gravity.CENTER;
                case "start": case "left": return Gravity.START | Gravity.CENTER_VERTICAL;
                case "end": case "right": return Gravity.END | Gravity.CENTER_VERTICAL;
                case "top": return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                case "bottom": return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                default: return def;
            }
        }

        int horizontal(String align, int def) {
            if (align == null) return def;
            switch (align.toLowerCase()) {
                case "center": return Gravity.CENTER_HORIZONTAL;
                case "end": case "right": return Gravity.END;
                case "start": case "left": return Gravity.START;
                default: return def;
            }
        }

        boolean hasAction(JSONObject n) {
            return !n.optString("ActionType", "").isEmpty();
        }

        JSONObject openAppNode() {
            JSONObject o = new JSONObject();
            try {
                o.put("ActionType", "openApp");
                o.put("ActionName", "Open");
            } catch (Exception ignored) { }
            return o;
        }

        PendingIntent action(JSONObject n) {
            JSONObject a = new JSONObject();
            try {
                a.put("ActionType", n.optString("ActionType", "openApp"));
                a.put("ActionName", n.optString("ActionName", ""));
                a.put("RowId", n.optString("Id", ""));
                a.put("ButtonId", n.optString("Id", ""));
                a.put("Payload", n.optString("Payload", ""));
                a.put("Url", n.optString("Url", ""));
                a.put("Method", n.optString("Method", ""));
                a.put("Body", n.optString("Body", ""));
                String dl = n.optString("DeepLink", "");
                a.put("DeepLink", dl.isEmpty() ? data.optString("DeepLink", "") : dl);
            } catch (Exception ignored) { }
            Intent i = new Intent(c, WidgetActionActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra(WidgetStore.EXTRA_ACTION, a.toString());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            int code = 700000 + (widgetId % 1000) * 1000 + (requestCounter++ % 1000);
            return PendingIntent.getActivity(c, code, i, flags);
        }

        int layout(String name) { return WidgetStore.res(c, "layout", name); }

        int id(String name) { return WidgetStore.res(c, "id", name); }

        int dp(float v) { return Math.round(v * density); }
    }
}
