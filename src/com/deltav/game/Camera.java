package com.deltav.game;

/**
 * World-to-screen mapping across eleven orders of magnitude.
 *
 * A lander is ten metres and a planetary system is ten billion. No single
 * linear zoom covers both, and a float cannot even hold the difference — so
 * zoom is stored as a logarithm and every world coordinate is converted to
 * screen space relative to the camera centre in double precision BEFORE being
 * cast down to float. Subtracting first is what keeps a one-metre landing leg
 * from being swallowed by the rounding error in a billion-metre coordinate.
 */
public class Camera {

    /** Metres per pixel, as a base-10 log. */
    public double logScale = 1.0;
    public double cx, cy;          // world centre
    public double targetLog = 1.0;
    public double tcx, tcy;

    public int w, h;

    public double scale() { return Math.pow(10, logScale); }

    public void size(int w, int h) { this.w = w; this.h = h; }

    public void snap(double x, double y, double log) {
        cx = tcx = x; cy = tcy = y; logScale = targetLog = log;
    }

    public void follow(double x, double y) { tcx = x + panX; tcy = y + panY; }

    public void zoomTo(double log) { targetLog = clamp(log); }

    public void nudgeZoom(double d) { targetLog = clamp(targetLog + d); }

    // Down to about 6 mm per pixel. A nine-metre rocket has to be able to
    // fill the screen on the pad, and -1.2 left it 57 pixels tall.
    private double clamp(double l) { return Math.max(-2.2, Math.min(9.4, l)); }

    /** Player-dragged offset from whatever the camera is following. */
    public double panX, panY;

    public void pan(double dxPixels, double dyPixels) {
        panX -= dxPixels * scale();
        panY += dyPixels * scale();
    }

    public void recentre() { panX = 0; panY = 0; }

    public boolean panned() {
        double s = scale();
        return Math.abs(panX / s) > 8 || Math.abs(panY / s) > 8;
    }

    /** Critically-damped-ish follow, frame-rate independent. */
    /**
     * Position tracks EXACTLY. Only the zoom is eased.
     *
     * Easing the position looks smooth and is wrong. A lag of k per frame
     * settles at v·dt/k metres behind the target — at 173 m/s that is twenty
     * metres. Zoomed in on a rocket the screen covers about thirty-six metres
     * total, so the vehicle slid straight off the edge the moment it started
     * moving, and stayed off. The camera looked broken and the zoom got the
     * blame.
     *
     * Smoothing a follow only makes sense when the lag is small compared to
     * the view. Across eleven orders of magnitude of zoom it never is, so the
     * subject is pinned and the world moves under it.
     */
    public void update(double dt) {
        cx = tcx;
        cy = tcy;
        double kz = 1 - Math.exp(-dt * 7.0);
        logScale += (targetLog - logScale) * kz;
    }

    public float sx(double worldX) {
        return (float) ((worldX - cx) / scale() + w * 0.5);
    }

    public float sy(double worldY) {
        // world +y is up; screen +y is down
        return (float) (h * 0.5 - (worldY - cy) / scale());
    }

    /** Length in pixels of a world distance. */
    public float len(double metres) { return (float) (metres / scale()); }

    public double worldX(float screenX) { return cx + (screenX - w * 0.5) * scale(); }
    public double worldY(float screenY) { return cy - (screenY - h * 0.5) * scale(); }

    /** Is a world circle anywhere near the viewport? */
    public boolean visible(double wx, double wy, double radius) {
        float x = sx(wx), y = sy(wy), r = len(radius);
        float m = Math.max(w, h) * 0.75f + r;
        return x > -m && x < w + m && y > -m && y < h + m;
    }
}
