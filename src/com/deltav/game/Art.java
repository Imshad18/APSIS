package com.deltav.game;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Drawing primitives and the palette.
 *
 * Paints are pooled and mutated rather than allocated. At sixty frames a
 * second with a few hundred draw calls each, allocating a Paint per call hands
 * the garbage collector several thousand short-lived objects a second, and the
 * pauses land as visible stutter — which on a game about precise burns is not
 * a cosmetic problem.
 */
public final class Art {

    private Art() {}

    public static float density = 3f;
    public static float dp(float v) { return v * density; }

    // ---- palette -----------------------------------------------------------
    public static final int VOID      = 0xFF04060C;
    public static final int VOID_2    = 0xFF0A0F1A;
    public static final int GRID      = 0xFF16202E;
    public static final int EDGE      = 0xFF2A3A50;
    public static final int INK       = 0xFFE6F0FF;
    public static final int INK_DIM   = 0xFF7A8CA6;
    public static final int CYAN      = 0xFF4BE0FF;
    public static final int CYAN_DIM  = 0xFF1C7A94;
    public static final int AMBER     = 0xFFFFB03A;
    public static final int GREEN     = 0xFF5AFF9E;
    public static final int RED       = 0xFFFF4D62;
    public static final int MAGENTA   = 0xFFD46BFF;
    public static final int PLUME_HOT = 0xFFFFF4D0;
    public static final int PLUME     = 0xFF7ED0FF;

    private static final Paint FILL   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHADER = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        TEXT.setTypeface(Typeface.MONOSPACE);
    }

    public static Paint fill(int c) {
        FILL.setShader(null); FILL.setColor(c); FILL.setStyle(Paint.Style.FILL);
        return FILL;
    }

    public static Paint stroke(float wDp, int c) {
        STROKE.setShader(null); STROKE.setColor(c);
        STROKE.setStrokeWidth(dp(wDp));
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setPathEffect(null);
        return STROKE;
    }

    public static Paint text(float sizeDp, int c, boolean bold) {
        TEXT.setShader(null); TEXT.setColor(c);
        TEXT.setTextSize(dp(sizeDp));
        TEXT.setFakeBoldText(bold);
        TEXT.setLetterSpacing(0f);
        TEXT.setTextAlign(Paint.Align.LEFT);
        return TEXT;
    }

    public static int alpha(int c, float a) {
        int base = (int) (((c >>> 24) & 255) * Math.max(0, Math.min(1, a)));
        return (base << 24) | (c & 0x00FFFFFF);
    }

    /** Blends two ARGB colours. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (((int) (aa + (ba - aa) * t)) << 24)
             | (((int) (ar + (br - ar) * t)) << 16)
             | (((int) (ag + (bg - ag) * t)) << 8)
             |  ((int) (ab + (bb - ab) * t));
    }

    public static void textCenter(Canvas c, String s, float x, float y, Paint p) {
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    public static void textRight(Canvas c, String s, float x, float y, Paint p) {
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** A soft radial glow — the workhorse behind engines, stars and planets. */
    public static void glow(Canvas c, float x, float y, float r, int colour, float strength) {
        if (r <= 0.5f) return;
        SHADER.setShader(new RadialGradient(x, y, r,
                new int[]{alpha(colour, strength), alpha(colour, strength * 0.35f), 0},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        SHADER.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, SHADER);
        SHADER.setShader(null);
    }

    /** A vertical two-stop gradient, for atmosphere bands and panels. */
    public static void vgrad(Canvas c, RectF r, int top, int bottom) {
        SHADER.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                top, bottom, Shader.TileMode.CLAMP));
        SHADER.setStyle(Paint.Style.FILL);
        c.drawRect(r, SHADER);
        SHADER.setShader(null);
    }

    /** A planet: lit limb, dark terminator, and an atmospheric rim if it has one. */
    public static void sphere(Canvas c, float x, float y, float r,
                              int light, int dark, int atmo, float atmoFrac,
                              float lightAngle) {
        if (r <= 0.4f) { c.drawCircle(x, y, Math.max(0.6f, r), fill(light)); return; }

        if (atmo != 0 && atmoFrac > 0) {
            float ar = r * (1 + atmoFrac);
            SHADER.setShader(new RadialGradient(x, y, ar,
                    new int[]{0, alpha(atmo, 0.30f), alpha(atmo, 0.55f), 0},
                    new float[]{0f, r / ar * 0.97f, r / ar, 1f}, Shader.TileMode.CLAMP));
            SHADER.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, ar, SHADER);
            SHADER.setShader(null);
        }

        float lx = x - (float) Math.cos(lightAngle) * r * 0.42f;
        float ly = y - (float) Math.sin(lightAngle) * r * 0.42f;
        SHADER.setShader(new RadialGradient(lx, ly, r * 1.55f,
                new int[]{light, mix(light, dark, 0.55f), dark},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        SHADER.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, SHADER);
        SHADER.setShader(null);
    }

    /** Bracket corners — used to frame selections and targets. */
    public static void brackets(Canvas c, RectF r, int colour, float legDp, float wDp) {
        Paint p = stroke(wDp, colour);
        float l = dp(legDp);
        c.drawLine(r.left, r.top, r.left + l, r.top, p);
        c.drawLine(r.left, r.top, r.left, r.top + l, p);
        c.drawLine(r.right, r.top, r.right - l, r.top, p);
        c.drawLine(r.right, r.top, r.right, r.top + l, p);
        c.drawLine(r.left, r.bottom, r.left + l, r.bottom, p);
        c.drawLine(r.left, r.bottom, r.left, r.bottom - l, p);
        c.drawLine(r.right, r.bottom, r.right - l, r.bottom, p);
        c.drawLine(r.right, r.bottom, r.right, r.bottom - l, p);
    }

    /** A chamfered instrument panel. */
    public static void panel(Canvas c, RectF r, int edge, int fillCol, float cutDp) {
        float k = dp(cutDp);
        Path path = new Path();
        path.moveTo(r.left + k, r.top);
        path.lineTo(r.right - k, r.top);
        path.lineTo(r.right, r.top + k);
        path.lineTo(r.right, r.bottom - k);
        path.lineTo(r.right - k, r.bottom);
        path.lineTo(r.left + k, r.bottom);
        path.lineTo(r.left, r.bottom - k);
        path.lineTo(r.left, r.top + k);
        path.close();
        if (fillCol != 0) c.drawPath(path, fill(fillCol));
        if (edge != 0) c.drawPath(path, stroke(1f, edge));
    }

    /** Compact engineering notation: 12.4 km, 3.10 Mm, 940 m. */
    public static String dist(double m) {
        double a = Math.abs(m);
        if (a < 1000) return Math.round(m) + " m";
        if (a < 1e6) return trim(m / 1e3) + " km";
        if (a < 1e9) return trim(m / 1e6) + " Mm";
        return trim(m / 1e9) + " Gm";
    }

    public static String speed(double v) {
        double a = Math.abs(v);
        if (a < 1000) return trim(v) + " m/s";
        return trim(v / 1000) + " km/s";
    }

    public static String mass(double kg) {
        if (Math.abs(kg) < 1000) return Math.round(kg) + " kg";
        return trim(kg / 1000) + " t";
    }

    private static String trim(double v) {
        double a = Math.abs(v);
        if (a >= 100) return String.valueOf(Math.round(v));
        if (a >= 10) return String.format(java.util.Locale.US, "%.1f", v);
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** Mission clock, as days/hours/minutes/seconds. */
    public static String clock(double s) {
        long t = (long) Math.max(0, s);
        long d = t / 86400; t %= 86400;
        long h = t / 3600;  t %= 3600;
        long m = t / 60;    long sec = t % 60;
        if (d > 0) return String.format(java.util.Locale.US, "%dd %02d:%02d:%02d", d, h, m, sec);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec);
    }

    /** Short countdown, for time-to-apoapsis style readouts. */
    public static String countdown(double s) {
        if (Double.isInfinite(s) || Double.isNaN(s)) return "--";
        return clock(s);
    }
}
