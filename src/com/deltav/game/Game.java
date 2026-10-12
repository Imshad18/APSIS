package com.deltav.game;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The activity. Owns the screens and the transitions between them.
 *
 * configChanges in the manifest keeps this instance alive across a rotation,
 * so the simulation is never destroyed and rebuilt mid-flight. Every view
 * recomputes its layout from the new viewport in onSizeChanged, which is why
 * turning the phone during a burn changes nothing but the shape of the screen.
 */
public class Game extends Activity implements FlightView.Host, BuilderView.Host {

    private FrameLayout root;
    private FlightView flightView;
    private BuilderView builderView;
    private MenuView menuView;

    private Universe universe;
    private Tech tech;
    private Craft design;
    private java.util.List<Contract> contracts = Contract.catalogue();
    { for (Contract k : contracts) k.taken = true; }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Art.density = getResources().getDisplayMetrics().density;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        universe = Universe.standard();
        tech = Save.readTech(this);
        design = Save.readCraft(this);
        if (design == null) design = starterCraft();

        root = new FrameLayout(this);
        root.setBackgroundColor(Art.VOID);

        menuView = new MenuView(this);
        menuView.host = this;
        menuView.tech = tech;

        builderView = new BuilderView(this);
        builderView.setHost(this);
        builderView.tech = tech;
        builderView.universe = universe;
        builderView.craft = design;

        flightView = new FlightView(this);
        flightView.setHost(this);

        root.addView(flightView, match());
        root.addView(builderView, match());
        root.addView(menuView, match());
        setContentView(root);

        show(MENU);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    /**
     * Full screen, and drawn into the cutout.
     *
     * A game that reserves a status-bar's height at the top loses it in every
     * orientation; drawing into the cutout and letting the views place their
     * own controls means the whole panel is usable.
     */
    private void immersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    /** Rotation keeps the activity; the views re-layout themselves. */
    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        immersive();
        root.requestLayout();
    }

    // =====================================================================

    static final int MENU = 0, BUILDER = 1, FLIGHT = 2;
    private int screen = MENU;

    private void show(int s) {
        screen = s;
        menuView.setVisibility(s == MENU ? View.VISIBLE : View.GONE);
        builderView.setVisibility(s == BUILDER ? View.VISIBLE : View.GONE);
        flightView.setVisibility(s == FLIGHT ? View.VISIBLE : View.GONE);
        if (s == FLIGHT) flightView.start(); else flightView.stop();
        if (s == BUILDER) builderView.refreshCatalogue();
        menuView.invalidate();
    }

    @Override protected void onPause() {
        super.onPause();
        flightView.stop();
        Save.write(this, tech, design);
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen == FLIGHT) flightView.start();
    }

    @Override public void onBackPressed() {
        if (screen == FLIGHT) { show(MENU); return; }
        if (screen == BUILDER) { show(MENU); return; }
        super.onBackPressed();
    }

    // =====================================================================
    // HOST CALLBACKS
    // =====================================================================

    void openBuilder() {
        builderView.craft = design;
        show(BUILDER);
    }

    void openSandbox() {
        tech.sandbox = !tech.sandbox;
        builderView.refreshCatalogue();
        menuView.invalidate();
    }

    @Override public void onLaunch(Craft c) {
        design = c;
        Save.write(this, tech, design);

        // Fly a COPY. The design in the bay must survive the flight, however
        // the flight goes — losing your rocket should cost you the vehicle,
        // not the blueprint.
        Craft fly = c.copy();
        Body pad = universe.home;
        universe.update(0);
        fly.soi = pad;
        fly.landed = true;
        fly.landedAngle = 0;
        double s = pad.surfaceAt(0);
        fly.x = s; fly.y = 0;
        fly.angle = 0;                    // pointing radially out, i.e. up
        fly.stage = 0;
        universe.time = 0;

        flightView.begin(universe, fly);
        flightView.fleet.add(fly);
        flightView.fleet.active = fly;
        flightView.tech = tech;
        flightView.contracts = contracts;
        // Fly the plan built in the bay. An empty plan means manual flight —
        // the autopilot never takes the vehicle unless it was asked to.
        flightView.ap.disengage();
        flightView.mission = builderView.mission.copy();
        if (!flightView.mission.steps.isEmpty()) flightView.mission.start();
        show(FLIGHT);
    }

    @Override public void onBack() { show(MENU); }

    @Override public void onOpenMap() { }

    @Override public void onOpenBuilder() { openBuilder(); }

    @Override public void onRecover(boolean success) {
        Craft c = flightView.craft;
        Flight f = flightView.flight;
        if (c != null && f != null) {
            String report = tech.debrief(c, f, c.soi, c.landed, success && c.landed);
            menuView.lastReport = report;
        }
        Save.write(this, tech, design);
        show(MENU);
    }

    private Craft starterCraft() {
        Craft c = new Craft();
        c.name = "SOUNDING-1";
        c.units.add(new Craft.Unit(Part.byId("pod1"), 1));
        c.units.add(new Craft.Unit(Part.byId("chute"), 1));
        c.units.add(new Craft.Unit(Part.byId("tankS"), 0));
        c.units.add(new Craft.Unit(Part.byId("eng1"), 0));
        return c;
    }

    // =====================================================================
    // MENU
    // =====================================================================

    static class MenuView extends View {
        Game host;
        Tech tech;
        String lastReport = "";

        private final RectF rBuild = new RectF(), rSandbox = new RectF(),
                rTech = new RectF();
        private float unit;
        private double phase;

        MenuView(Context c) { super(c); setClickable(true); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            unit = Math.min(w, h) * 0.13f;
            boolean portrait = h >= w;
            float bw = portrait ? w * 0.62f : w * 0.34f;
            float bh = unit * 0.66f;
            float cx = w * 0.5f;
            float y = portrait ? h * 0.52f : h * 0.46f;
            rBuild.set(cx - bw / 2, y, cx + bw / 2, y + bh);
            y += bh + unit * 0.22f;
            rTech.set(cx - bw / 2, y, cx + bw / 2, y + bh);
            y += bh + unit * 0.22f;
            rSandbox.set(cx - bw / 2, y, cx + bw / 2, y + bh);
        }

        @Override protected void onDraw(Canvas c) {
            phase += 0.016;
            int w = getWidth(), h = getHeight();
            c.drawColor(Art.VOID);

            // a slow starfield, so the menu is not a dead screen
            for (int i = 0; i < 160; i++) {
                long hs = i * 2654435761L;
                float x = ((hs >>> 11) % 10000) / 10000f * w;
                float y = (float) ((((hs >>> 31) % 10000) / 10000f * h
                        + phase * 6 * (1 + (i % 3))) % h);
                c.drawCircle(x, y, Art.dp(0.6f + (i % 3) * 0.4f),
                        Art.fill(Art.alpha(0xFFFFFFFF, 0.25f + (i % 3) * 0.2f)));
            }

            Paint t = Art.text(30f, Art.CYAN, true);
            t.setLetterSpacing(0.34f);
            Art.textCenter(c, "APSIS", w * 0.5f, h * (h >= w ? 0.26f : 0.22f), t);
            Paint s = Art.text(7f, Art.INK_DIM, false);
            s.setLetterSpacing(0.42f);
            Art.textCenter(c, "AN ORBIT IS A PERPETUAL FALL",
                    w * 0.5f, h * (h >= w ? 0.30f : 0.28f), s);

            if (tech != null) {
                Paint st = Art.text(7.5f, Art.AMBER, true);
                Art.textCenter(c, "SCIENCE " + Math.round(tech.science)
                        + "     FUNDS " + Math.round(tech.funds)
                        + "     TIER " + tech.level,
                        w * 0.5f, h * (h >= w ? 0.37f : 0.34f), st);
                Art.textCenter(c, Tech.NODES[Math.min(tech.level, Tech.NODES.length - 1)],
                        w * 0.5f, h * (h >= w ? 0.40f : 0.38f),
                        Art.text(6.5f, Art.INK_DIM, false));
            }

            button(c, rBuild, "ASSEMBLY BAY", Art.CYAN);
            button(c, rTech, tech != null && tech.canAdvance()
                    ? "RESEARCH: " + tech.nextName()
                    : "RESEARCH (" + (tech == null ? 0 : Math.round(tech.nextCost())) + ")",
                    tech != null && tech.canAdvance() ? Art.GREEN : Art.EDGE);
            button(c, rSandbox, tech != null && tech.sandbox
                    ? "SANDBOX: ON" : "SANDBOX: OFF",
                    tech != null && tech.sandbox ? Art.MAGENTA : Art.INK_DIM);

            if (lastReport != null && lastReport.length() > 0) {
                Paint p = Art.text(6.4f, Art.GREEN, false);
                float y = h * 0.86f;
                for (String line : lastReport.split("\n")) {
                    Art.textCenter(c, line, w * 0.5f, y, p);
                    y += Art.dp(11);
                }
            }
            postInvalidateOnAnimation();
        }

        private void button(Canvas c, RectF r, String label, int colour) {
            Art.panel(c, r, colour, Art.alpha(colour, 0.14f), 6);
            Paint p = Art.text(8f, colour, true);
            p.setLetterSpacing(0.2f);
            Art.textCenter(c, label, r.centerX(), r.centerY() + Art.dp(3), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float x = e.getX(), y = e.getY();
            if (rBuild.contains(x, y)) { lastReport = ""; host.openBuilder(); }
            else if (rSandbox.contains(x, y)) host.openSandbox();
            else if (rTech.contains(x, y) && tech != null && tech.advance()) {
                host.builderView.refreshCatalogue();
                invalidate();
            }
            return true;
        }
    }
}
