package io.homescreenwidget.plugin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Draws the visuals RemoteViews cannot express (rings, bars, toggles, gradients) as bitmaps. */
final class WidgetBitmaps {
    private WidgetBitmaps() { }

    private static Paint paint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    /** Parses "#RRGGBB", "#AARRGGBB"; returns fallback when empty/invalid. */
    static int color(String s, int fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty()) return fallback;
        try {
            return Color.parseColor(s.startsWith("#") ? s : "#" + s);
        } catch (Exception e) {
            return fallback;
        }
    }

    static Bitmap ring(int sizePx, float fraction, int color, int track, float strokePx) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, sizePx), Math.max(1, sizePx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokePx);
        p.setStrokeCap(Paint.Cap.ROUND);
        float inset = strokePx / 2f + 1;
        RectF r = new RectF(inset, inset, sizePx - inset, sizePx - inset);
        p.setColor(track);
        c.drawArc(r, 0, 360, false, p);
        p.setColor(color);
        float sweep = 360f * Math.max(0f, Math.min(1f, fraction));
        if (sweep > 0) c.drawArc(r, -90, sweep, false, p);
        return b;
    }

    static Bitmap bar(int widthPx, int heightPx, float fraction, int color, int track) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, widthPx), Math.max(1, heightPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = paint();
        float r = heightPx / 2f;
        p.setColor(track);
        c.drawRoundRect(new RectF(0, 0, widthPx, heightPx), r, r, p);
        float w = widthPx * Math.max(0f, Math.min(1f, fraction));
        if (w > 0) {
            p.setColor(color);
            c.drawRoundRect(new RectF(0, 0, Math.max(w, heightPx), heightPx), r, r, p);
        }
        return b;
    }

    static Bitmap checkbox(int sizePx, boolean checked, int color) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, sizePx), Math.max(1, sizePx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = paint();
        float stroke = Math.max(2f, sizePx / 10f);
        RectF r = new RectF(stroke, stroke, sizePx - stroke, sizePx - stroke);
        float radius = sizePx / 5f;
        if (checked) {
            p.setColor(color);
            c.drawRoundRect(r, radius, radius, p);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke * 1.2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            Path tick = new Path();
            tick.moveTo(sizePx * 0.28f, sizePx * 0.52f);
            tick.lineTo(sizePx * 0.44f, sizePx * 0.68f);
            tick.lineTo(sizePx * 0.74f, sizePx * 0.34f);
            c.drawPath(tick, p);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(color);
            c.drawRoundRect(r, radius, radius, p);
        }
        return b;
    }

    static Bitmap toggle(int widthPx, int heightPx, boolean on, int color) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, widthPx), Math.max(1, heightPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = paint();
        float r = heightPx / 2f;
        p.setColor(on ? color : 0xFF9CA3AF);
        c.drawRoundRect(new RectF(0, 0, widthPx, heightPx), r, r, p);
        p.setColor(Color.WHITE);
        float knob = r - heightPx * 0.12f;
        c.drawCircle(on ? widthPx - r : r, r, knob, p);
        return b;
    }

    /** Rounded rectangle filled with one colour or a vertical/diagonal gradient. */
    static Bitmap background(int widthPx, int heightPx, float radiusPx, int[] colors) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, widthPx), Math.max(1, heightPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = paint();
        if (colors.length > 1) {
            p.setShader(new LinearGradient(0, 0, widthPx, heightPx, colors, null, Shader.TileMode.CLAMP));
        } else {
            p.setColor(colors[0]);
        }
        c.drawRoundRect(new RectF(0, 0, widthPx, heightPx), radiusPx, radiusPx, p);
        return b;
    }

    /** Blocking download (call off the main thread); scaled so its width is at most maxWidthPx. */
    static Bitmap download(String url, int maxWidthPx) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (InputStream is = conn.getInputStream()) {
                Bitmap b = BitmapFactory.decodeStream(is);
                if (b == null) return null;
                if (b.getWidth() > maxWidthPx) {
                    int h = Math.round(b.getHeight() * (maxWidthPx / (float) b.getWidth()));
                    b = Bitmap.createScaledBitmap(b, maxWidthPx, Math.max(1, h), true);
                }
                return b;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
