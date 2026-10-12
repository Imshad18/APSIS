package com.deltav.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The flight screen: world, HUD, and controls.
 *
 * Layout is computed from the viewport every time it changes rather than from
 * constants, so portrait and landscape are the same code with different
 * numbers. Nothing is positioned by a magic offset from a corner; every
 * control is placed relative to the shorter edge, which is what makes a
 * rotation mid-burn a non-event.
 */
public class FlightView extends View {

    public interface Host {
        void onRecover(boolean success);
        void onOpenMap();
        void onOpenBuilder();
    }

    private Host host;
    public Universe u;
    public Craft craft;
    public Flight flight;
    public Autopilot ap = new Autopilot();
    public Node node = new Node();
    public Mission mission = new Mission();
    public Fleet fleet = new Fleet();
    public java.util.List<Contract> contracts = new java.util.ArrayList<Contract>();
    public Tech tech;

    private final Camera cam = new Camera();
    private final Scene scene = new Scene();

    private boolean running;
    private long lastNs;
    private double phase;
    public boolean mapMode;

    /** Zoom the player has chosen, remembered per mode. */
    // Launch is framed ON THE ROCKET. Everything else is context you can
    // pinch out to; the vehicle is the thing you are actually flying.
    private double flightZoom = -1.7, mapZoom = 5.2;

    /**
     * How far the world is rotated so that "up" is up.
     *
     * The camera is world-axis-aligned, and the launch site sits at angle zero
     * on the planet — so radially outward is world +x, which is screen RIGHT.
     * The rocket was pointing exactly where it should and lying on its side
     * anyway. Rotating the world layer so the local vertical is screen
     * vertical is what makes a launch look like a launch.
     *
     * Smoothed, because snapping it every frame as the craft moves around the
     * body would make the whole world twitch.
     */
    private double viewRot, viewRotTarget;

    private boolean needsFrame;

    /**
     * Set the moment the player pinches.
     *
     * Until then the camera keeps re-framing the vehicle every frame. A
     * one-shot flag was the wrong shape: it depends on running exactly once,
     * at exactly the right point in the launch sequence, and anything that
     * consumed it early left the camera wherever it happened to be. Enforcing
     * the framing continuously cannot be defeated by ordering.
     */
    private boolean playerZoomed;

    /**
     * Zooms so the vehicle fills the screen, from its ACTUAL height and the
     * REAL viewport.
     *
     * The launch zoom used to be a hardcoded -1.7. That is a guess about two
     * things it cannot see: how big the rocket is, and how big the screen is.
     * A four-metre sounding rocket and a forty-metre heavy lifter are not the
     * same picture, and 0.02 m per pixel put the starter craft at a fifth of
     * the screen height. Solving for the scale that makes the stack fill a
     * fixed fraction of the shorter edge is correct for every vehicle on every
     * display, and cannot drift out of step with anything.
     */
    private void frameCraft() {
        if (cam.w <= 0 || cam.h <= 0 || craft == null) return;
        double hMetres = Math.max(1.5, craft.height());
        double targetPx = Math.min(cam.w, cam.h) * 0.45;
        flightZoom = Math.max(-2.2, Math.min(7.0, Math.log10(hMetres / targetPx)));
        if (needsFrame) {
            // First frame of a flight: jump, do not glide in from wherever
            // the camera happened to be left.
            cam.snap(craft.soi.x + craft.x, craft.soi.y + craft.y, flightZoom);
            needsFrame = false;
        } else {
            cam.zoomTo(flightZoom);
        }
    }

    public FlightView(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
    }

    public void setHost(Host h) { host = h; }

    public void begin(Universe u, Craft c) {
        this.u = u;
        this.craft = c;
        this.flight = new Flight(u, c);
        cam.recentre();
        // The viewport is not known yet — begin() runs while this view is
        // still GONE, so width and height are zero. Flag it and frame the
        // vehicle on the first frame that has real dimensions.
        needsFrame = true;
        playerZoomed = false;
        cam.snap(c.soi.x + c.x, c.soi.y + c.y, flightZoom);
    }

    public void start() {
        if (running) return;
        running = true; lastNs = 0; postOnAnimation(tick);
    }

    public void stop() { running = false; removeCallbacks(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.nanoTime();
            double dt = lastNs == 0 ? 1.0 / 60 : (now - lastNs) / 1e9;
            lastNs = now;
            // A long frame must not be handed to the integrator as one step;
            // clamp it, or a stall becomes a teleport through a planet.
            dt = Math.min(dt, 0.05);
            phase += dt;
            update(dt);
            invalidate();
            postOnAnimation(this);
        }
    };

    // =====================================================================
    // LAYOUT — recomputed on every size change, so rotation just works
    // =====================================================================

    private final RectF rThrottle = new RectF();
    private final RectF rStage = new RectF();
    private final RectF rWarpUp = new RectF(), rWarpDown = new RectF();
    private final RectF rMap = new RectF(), rAP = new RectF(), rCentre = new RectF(),
            rRecover = new RectF();
    private final RectF rLeft = new RectF(), rRight = new RectF();
    private final RectF rNav = new RectF();
    private final List<RectF> apButtons = new ArrayList<RectF>();
    private boolean apMenuOpen;

    private float unit;        // one layout unit, from the shorter edge

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cam.size(w, h);
        if (needsFrame) frameCraft();
        layout(w, h);
    }

    private void layout(int w, int h) {
        boolean portrait = h >= w;
        unit = Math.min(w, h) * 0.13f;
        float pad = unit * 0.28f;

        // Throttle always hugs the left edge at a comfortable thumb height;
        // in landscape it shortens so it never collides with the HUD strip.
        float tW = unit * 0.62f;
        float tH = portrait ? h * 0.34f : h * 0.55f;
        rThrottle.set(pad, h - pad - tH, pad + tW, h - pad);

        // Stage is the biggest target on screen. It is the one control you
        // press under time pressure, and missing it loses the vehicle.
        float sSize = unit * 1.15f;
        rStage.set(w - pad - sSize, h - pad - sSize, w - pad, h - pad);

        // Attitude pad sits beside the stage button, thumb-reachable.
        float navS = unit * 1.5f;
        rNav.set(w - pad - navS, h - pad - sSize - pad - navS,
                 w - pad, h - pad - sSize - pad);

        float bW = unit * 0.86f, bH = unit * 0.52f;
        rWarpDown.set(w - pad - bW * 2 - pad * 0.5f, pad, w - pad - bW - pad * 0.5f, pad + bH);
        rWarpUp.set(w - pad - bW, pad, w - pad, pad + bH);

        rCentre.set(w * 0.5f - bW * 0.5f, h - pad - bH * 1.15f,
                    w * 0.5f + bW * 0.5f, h - pad - bH * 0.15f);
        rRecover.set(w * 0.5f - bW * 0.75f, pad + bH + pad * 0.6f,
                     w * 0.5f + bW * 0.75f, pad + bH * 2.1f + pad * 0.6f);
        rAP.set(pad + bW + pad * 0.5f, pad, pad + bW * 2 + pad * 0.5f, pad + bH);

        // touch zones for steering: the whole left and right of the world area
        rLeft.set(0, pad + bH, w * 0.34f, h - pad - tH);
        rRight.set(w * 0.66f, pad + bH, w, h - pad - sSize - pad - navS);

        layoutApMenu(w, h, pad, bW, bH);
    }

    private void layoutApMenu(int w, int h, float pad, float bW, float bH) {
        apButtons.clear();
        float mw = Math.min(w * 0.52f, unit * 4.6f);
        float mh = bH * 0.92f;
        float x = pad;
        float y = pad + bH + pad * 0.6f;
        for (int i = 0; i < Autopilot.NAMES.length; i++) {
            apButtons.add(new RectF(x, y + i * (mh + pad * 0.32f),
                    x + mw, y + i * (mh + pad * 0.32f) + mh));
        }
    }

    // =====================================================================
    // UPDATE
    // =====================================================================

    private void update(double dt) {
        if (flight == null) return;

        // onSizeChanged does not fire when the screen size has not changed,
        // so returning to flight at the same size would never frame anything.
        if (!playerZoomed) frameCraft();

        double before = u.time;

        if (node.active && nodeExecuting) {
            if (!node.execute(flight, dt)) nodeExecuting = false;
        } else {
            // The plan drives the autopilot; the autopilot flies.
            if (mission.running()) mission.update(ap, flight, u, dt);
            ap.update(flight, dt);
            if (!ap.active()) manualSteer(dt);
        }
        flight.step(dt);
        fleet.advance(u, u.time - before);
        if (node.active && !nodeExecuting) node.solve(flight);

        for (Contract k : contracts) {
            if (k.taken && !k.done && k.check(craft, flight, fleet, flight.driven)) {
                if (tech != null) { tech.funds += k.reward; tech.science += k.scienceReward; }
                flash = "CONTRACT COMPLETE: " + k.title;
                flashT = 4.0;
            }
        }
        if (flashT > 0) flashT -= dt;

        Body b = craft.soi;
        double wx = b.x + craft.x, wy = b.y + craft.y;

        if (mapMode) {
            cam.zoomTo(mapZoom);
            cam.follow(b.x, b.y);
        } else {
            cam.zoomTo(flightZoom);
            cam.follow(wx, wy);
        }
        // Local vertical, unwrapped against the current value so a craft
        // crossing the +x axis does not spin the world through 360 degrees.
        if (!mapMode) {
            double up = Math.atan2(craft.y, craft.x);
            double want = up - Math.PI / 2;
            double d = want - viewRotTarget;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            viewRotTarget += d;
        } else {
            viewRotTarget = 0;
        }
        viewRot += (viewRotTarget - viewRot) * (1 - Math.exp(-dt * 6.0));

        cam.update(dt);

        if (craft.destroyed && host != null && !reported) {
            reported = true;
            postDelayed(new Runnable() {
                @Override public void run() { if (host != null) host.onRecover(false); }
            }, 1400);
        }
    }

    private boolean reported;
    public boolean nodeExecuting;
    private String flash = "";
    private double flashT;

    private void manualSteer(double dt) {
        if (steerDir != 0) {
            craft.angle += steerDir * (craft.sasOn ? 2.2 : 1.5) * dt;
            craft.angle = ((craft.angle % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        }
    }

    private int steerDir;

    // =====================================================================
    // DRAW
    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (flight == null) { c.drawColor(Art.VOID); return; }

        c.drawColor(Art.VOID);
        c.save();
        c.rotate((float) Math.toDegrees(viewRot), w * 0.5f, h * 0.5f);
        scene.stars(c, cam, 260);

        Body soi = craft.soi;
        double alt = flight.altitudeMean();

        // Close to a body it is a landscape, not a disc.
        boolean onSurface = !mapMode && scene.surface(c, cam, soi, alt);
        scene.bodies(c, cam, u, onSurface ? soi : null);
        if (!mapMode) scene.atmosphere(c, cam, soi, alt);

        // Orbit paths. In map mode every body's SOI is shown so a transfer has
        // something to aim at.
        Orbit o = flight.orbit();
        if (mapMode) {
            for (Body b : u.bodies) {
                if (b.parent == null || b.orbitRadius <= 0) continue;
                float r = cam.len(b.orbitRadius);
                if (r > Art.dp(8) && r < Art.dp(9000)) {
                    c.drawCircle(cam.sx(b.parent.x), cam.sy(b.parent.y), r,
                            Art.stroke(0.8f, Art.alpha(Art.EDGE, 0.75f)));
                }
                scene.soi(c, cam, b);
            }
        }
        // The conic belongs on the MAP. During an ascent the orbit is a
        // sliver from a periapsis deep inside the planet to an apoapsis a
        // couple of kilometres up, and drawn over the launch it is a pair of
        // enormous crossing lines that mean nothing — the two "triangles".
        // The numbers are already in the readout.
        if (mapMode) {
            scene.orbit(c, cam, o, soi, Art.alpha(Art.CYAN, 0.85f), 1.6f, false);
            scene.apsides(c, cam, o, soi);
        }

        if (!mapMode && soi == u.home) scene.pad(c, cam, soi, 0);
        scene.craft(c, cam, craft, soi, phase, !mapMode);

        c.restore();

        hud(c, w, h, o);
        controls(c, w, h);
        if (craft.destroyed) lost(c, w, h);
    }

    // =====================================================================
    // HUD
    // =====================================================================

    private void hud(Canvas c, int w, int h, Orbit o) {
        boolean portrait = h >= w;
        float pad = unit * 0.28f;
        Body b = craft.soi;

        // A craft sitting on the pad has zero velocity, and a conic through a
        // point with no velocity is a degenerate radial line — which is why
        // the pad used to report "AP ESCAPE" and a periapsis 600 km below the
        // surface. Neither is wrong; both are meaningless. Say LANDED.
        boolean onGround = craft.landed;
        String apTxt, peTxt;
        if (onGround) { apTxt = "LANDED"; peTxt = "—"; }
        else {
            apTxt = o.closed() ? Art.dist(o.apoapsis() - b.radius) : "ESCAPE";
            double pe = o.periapsis() - b.radius;
            peTxt = pe < 0 ? "IMPACT" : Art.dist(pe);
        }

        String[][] rows = {
            {"ALT",  Art.dist(flight.altitude())},
            {"SPD",  Art.speed(flight.surfaceSpeed())},
            {"V/S",  Art.speed(flight.verticalSpeed())},
            {"AP",   apTxt},
            {"PE",   peTxt},
            {"FUEL", Math.round(fuelFraction() * 100) + "%"},
            {"dV",   Art.speed(craft.totalDeltaV(flight.pressureRatio()))},
            {"TWR",  String.format(java.util.Locale.US, "%.2f",
                        craft.twr(b, flight.pressureRatio()))},
            {"G",    String.format(java.util.Locale.US, "%.1f", flight.gLoad)},
            // Diagnostic: what the camera is actually doing. If the vehicle
            // ever fails to fill the screen again, this says whether the zoom
            // is wrong or the drawing is.
            {"ZOOM", String.format(java.util.Locale.US, "%.3f m/px", cam.scale())},
            {"SHIP", Math.round(craft.height() / Math.max(1e-9, cam.scale())) + " px"},
        };

        Paint k = Art.text(6.2f, Art.INK_DIM, false);
        k.setLetterSpacing(0.16f);
        Paint v = Art.text(8.5f, Art.INK, true);

        // The readout must never sit on top of a control. In landscape it
        // starts to the RIGHT of the throttle, which is what it was colliding
        // with; in portrait the throttle is far below it and the full width is
        // available.
        float left = portrait ? pad : rThrottle.right + pad * 1.2f;
        // Below the TERRA / clock band, so nothing overlaps it.
        float top = pad + unit * 0.80f;
        int cols = portrait ? 3 : 3;
        float usable = w - left - pad - (portrait ? 0 : unit * 2.4f);
        float cw = usable / cols;
        float rowH = unit * 0.44f;

        for (int i = 0; i < rows.length; i++) {
            float x = left + (i % cols) * cw;
            float y = top + (i / cols) * rowH;
            c.drawText(rows[i][0], x, y, k);
            c.drawText(rows[i][1], x, y + unit * 0.21f, v);
        }

        Paint n = Art.text(8.5f, Art.CYAN, true);
        n.setLetterSpacing(0.26f);
        Art.textCenter(c, b.name, w * 0.5f, pad + unit * 0.30f, n);
        Paint t = Art.text(6.5f, Art.INK_DIM, false);
        Art.textCenter(c, "T+" + Art.clock(u.time)
                + (flight.warp > 1 ? "   " + (int) flight.warp + "x" : ""),
                w * 0.5f, pad + unit * 0.52f, t);

        if (mission.running() && mission.step() != null) {
            Paint mp = Art.text(6.6f, Art.MAGENTA, true);
            mp.setLetterSpacing(0.12f);
            Art.textCenter(c, "PLAN " + (mission.current + 1) + "/"
                    + mission.steps.size() + "  " + mission.step().label()
                    + (mission.status.isEmpty() ? "" : "  " + mission.status),
                    w * 0.5f, h - unit * 0.44f, mp);
        }
        if (ap.active()) {
            Paint a = Art.text(7f, Art.MAGENTA, true);
            a.setLetterSpacing(0.14f);
            Art.textCenter(c, Autopilot.NAMES[ap.mode] + "  " + ap.status,
                    w * 0.5f, h - unit * 0.20f, a);
        }
        if (flashT > 0) {
            Paint fp = Art.text(8f, Art.GREEN, true);
            Art.textCenter(c, flash, w * 0.5f, h * 0.20f, fp);
        }
        if (flight.warning.length() > 0 && !craft.destroyed) {
            Paint p = Art.text(9f, Art.RED, true);
            p.setLetterSpacing(0.18f);
            Art.textCenter(c, flight.warning, w * 0.5f, h * 0.30f, p);
        }
        if (flight.soiEvent != null) {
            Paint p = Art.text(9f, Art.AMBER, true);
            Art.textCenter(c, flight.soiEvent, w * 0.5f, h * 0.25f, p);
        }
        if (craft.heat > 0.05) {
            float bw = w * 0.34f;
            RectF r = new RectF(w * 0.5f - bw / 2, h * 0.36f,
                    w * 0.5f + bw / 2, h * 0.36f + Art.dp(4));
            c.drawRect(r, Art.fill(Art.alpha(Art.EDGE, 0.8f)));
            float f = (float) Math.min(1, craft.heat / 1.6);
            c.drawRect(new RectF(r.left, r.top, r.left + bw * f, r.bottom),
                    Art.fill(Art.mix(Art.AMBER, Art.RED, f)));
        }
    }

    /** Landed, still alive, and either dry or deliberately stopped. */
    private boolean canRecover() {
        return craft != null && craft.landed && !craft.destroyed;
    }

    private double fuelFraction() {
        double cap = 0, now = 0;
        for (Craft.Unit un : craft.units) {
            if (un.detached) continue;
            cap += un.type.fuel; now += un.fuel;
        }
        return cap > 0 ? now / cap : 0;
    }

    // =====================================================================
    // CONTROLS
    // =====================================================================

    private void controls(Canvas c, int w, int h) {
        // throttle
        Art.panel(c, rThrottle, Art.EDGE, Art.alpha(Art.VOID_2, 0.85f), 5);
        float fh = rThrottle.height() * (float) craft.throttle;
        RectF f = new RectF(rThrottle.left, rThrottle.bottom - fh,
                rThrottle.right, rThrottle.bottom);
        c.drawRect(f, Art.fill(Art.alpha(Art.CYAN, 0.42f)));
        c.drawLine(rThrottle.left, f.top, rThrottle.right, f.top,
                Art.stroke(1.6f, Art.CYAN));
        Paint tp = Art.text(6.5f, Art.INK_DIM, true);
        Art.textCenter(c, "THR", rThrottle.centerX(), rThrottle.top - Art.dp(5), tp);
        Art.textCenter(c, Math.round(craft.throttle * 100) + "",
                rThrottle.centerX(), rThrottle.bottom + Art.dp(11),
                Art.text(7.5f, Art.CYAN, true));

        // stage
        boolean canStage = craft.stage <= craft.maxStage();
        int sc = canStage ? Art.AMBER : Art.EDGE;
        Art.panel(c, rStage, sc, Art.alpha(sc, 0.16f), 8);
        Paint sp = Art.text(9f, sc, true);
        sp.setLetterSpacing(0.12f);
        Art.textCenter(c, "STAGE", rStage.centerX(), rStage.centerY() - Art.dp(1), sp);
        Art.textCenter(c, canStage ? (craft.maxStage() - craft.stage + 1) + " LEFT" : "SPENT",
                rStage.centerX(), rStage.centerY() + Art.dp(11),
                Art.text(6f, Art.INK_DIM, false));

        // attitude pad
        Art.panel(c, rNav, Art.EDGE, Art.alpha(Art.VOID_2, 0.8f), 6);
        float cx = rNav.centerX(), cy = rNav.centerY();
        float rr = Math.min(rNav.width(), rNav.height()) * 0.36f;
        c.drawCircle(cx, cy, rr, Art.stroke(1f, Art.alpha(Art.EDGE, 1f)));
        float a = (float) -craft.angle;
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr,
                Art.stroke(2f, Art.CYAN));
        float pa = (float) -flight.prograde();
        c.drawCircle(cx + (float) Math.cos(pa) * rr, cy + (float) Math.sin(pa) * rr,
                Art.dp(3), Art.stroke(1.4f, Art.GREEN));
        Art.textCenter(c, "◀", rNav.left + rNav.width() * 0.14f, cy + Art.dp(4),
                Art.text(11f, Art.INK_DIM, true));
        Art.textCenter(c, "▶", rNav.right - rNav.width() * 0.14f, cy + Art.dp(4),
                Art.text(11f, Art.INK_DIM, true));

        button(c, rWarpDown, "warp -", Art.INK_DIM);
        button(c, rWarpUp, "warp +", Art.INK_DIM);
        button(c, rMap, mapMode ? "FLIGHT" : "MAP", Art.CYAN);
        button(c, rAP, ap.active() ? "AUTO ON" : "AUTO", ap.active() ? Art.MAGENTA : Art.INK_DIM);
        button(c, rCentre, "FOCUS SHIP", Art.CYAN);
        // A landed craft with nothing left to burn is a finished flight. Give
        // it a way to end, or the only exit is the back key and the science
        // never gets counted.
        if (canRecover()) button(c, rRecover, "RECOVER VESSEL", Art.GREEN);

        if (apMenuOpen) {
            for (int i = 0; i < apButtons.size(); i++) {
                boolean on = ap.mode == i;
                button(c, apButtons.get(i), Autopilot.NAMES[i],
                        on ? Art.MAGENTA : Art.INK_DIM);
            }
        }
    }

    private void button(Canvas c, RectF r, String label, int colour) {
        Art.panel(c, r, colour, Art.alpha(colour, 0.12f), 4);
        Paint p = Art.text(6.8f, colour, true);
        p.setLetterSpacing(0.1f);
        Art.textCenter(c, label, r.centerX(), r.centerY() + Art.dp(2.4f), p);
    }

    private void lost(Canvas c, int w, int h) {
        c.drawColor(Art.alpha(0xFF200008, 0.55f));
        Paint p = Art.text(18f, Art.RED, true);
        p.setLetterSpacing(0.3f);
        Art.textCenter(c, "VEHICLE LOST", w * 0.5f, h * 0.46f, p);
        Art.textCenter(c, flight.warning, w * 0.5f, h * 0.52f,
                Art.text(8f, Art.INK_DIM, false));
    }

    // =====================================================================
    // TOUCH
    // =====================================================================

    private int throttlePointer = -1;
    private int steerPointer = -1;

    private float dragLastX, dragLastY;
    private int dragPointer = -1;
    private boolean dragMoved;

    @Override public boolean onTouchEvent(MotionEvent e) {
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        float x = e.getX(idx), y = e.getY(idx);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (rThrottle.contains(x, y)) { throttlePointer = id; setThrottleFrom(y); return true; }
                if (rStage.contains(x, y)) { craft.doStage(); return true; }
                if (rWarpUp.contains(x, y))   { warpStep(1); return true; }
                if (rWarpDown.contains(x, y)) { warpStep(-1); return true; }
                if (rMap.contains(x, y)) {
                    mapMode = !mapMode;
                    cam.recentre();
                    return true;
                }
                if (rAP.contains(x, y)) { apMenuOpen = !apMenuOpen; return true; }
                if (rCentre.contains(x, y)) {
                    // One tap back to the vehicle, framed, whatever the
                    // player has done with pinch and drag.
                    cam.recentre();
                    playerZoomed = false;
                    needsFrame = true;
                    return true;
                }
                if (canRecover() && rRecover.contains(x, y)) {
                    if (host != null) host.onRecover(true);
                    return true;
                }
                if (apMenuOpen) {
                    for (int i = 0; i < apButtons.size(); i++) {
                        if (apButtons.get(i).contains(x, y)) {
                            if (i == Autopilot.OFF) ap.disengage();
                            else {
                                if (i == Autopilot.DOCK) {
                                    java.util.List<Craft> n = fleet.near(craft, 2e6);
                                    ap.dockTarget = n.isEmpty() ? null : n.get(0);
                                }
                                ap.engage(i, flight);
                            }
                            apMenuOpen = false;
                            return true;
                        }
                    }
                }
                if (rNav.contains(x, y)) {
                    steerPointer = id;
                    steerDir = x < rNav.centerX() ? 1 : -1;
                    return true;
                }
                // Anywhere else is the world: this finger drags it.
                dragPointer = id;
                dragLastX = x; dragLastY = y;
                dragMoved = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == throttlePointer) setThrottleFrom(e.getY(i));
                }
                if (e.getPointerCount() >= 2) { pinch(e); return true; }

                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) != dragPointer) continue;
                    float nx = e.getX(i), ny = e.getY(i);
                    float dx = nx - dragLastX, dy = ny - dragLastY;
                    if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) {
                        // The world layer is rotated, so a screen-space drag
                        // must be turned back into the world frame or the
                        // planet slides off at an angle to the finger.
                        double ca = Math.cos(-viewRot), sa = Math.sin(-viewRot);
                        float rdx = (float) (dx * ca - dy * sa);
                        float rdy = (float) (dx * sa + dy * ca);
                        cam.pan(rdx, rdy);
                        // Move now rather than easing, or the world lags the
                        // finger and the drag feels loose.
                        cam.cx -= rdx * cam.scale();
                        cam.cy += rdy * cam.scale();
                    }
                    dragLastX = nx; dragLastY = ny;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (id == throttlePointer) throttlePointer = -1;
                if (id == steerPointer) { steerPointer = -1; steerDir = 0; }
                if (id == dragPointer) dragPointer = -1;
                pinchBase = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                throttlePointer = -1; steerPointer = -1; steerDir = 0;
                dragPointer = -1; pinchBase = -1;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private double pinchBase = -1, pinchZoom;

    private void pinch(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        double d = Math.hypot(dx, dy);
        if (pinchBase < 0) { pinchBase = d; pinchZoom = mapMode ? mapZoom : flightZoom; return; }
        double factor = Math.log10(Math.max(0.05, pinchBase / Math.max(1, d)));
        double z = pinchZoom + factor;
        if (mapMode) mapZoom = Math.max(3.0, Math.min(9.2, z));
        else { flightZoom = Math.max(-2.2, Math.min(7.0, z)); playerZoomed = true; }
    }

    private void setThrottleFrom(float y) {
        double t = (rThrottle.bottom - y) / rThrottle.height();
        craft.throttle = Math.max(0, Math.min(1, t));
        if (ap.active() && craft.throttle > 0) ap.disengage();
    }

    private void warpStep(int d) {
        double[] ws = Flight.WARPS;
        int cur = 0;
        for (int i = 0; i < ws.length; i++) if (Math.abs(ws[i] - flight.warp) < 0.5) cur = i;
        cur = Math.max(0, Math.min(ws.length - 1, cur + d));
        flight.warp = ws[cur];
    }
}
