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
 * The assembly bay.
 *
 * Laid out as three bands that never overlap, in either orientation: a top bar
 * of actions, the vehicle in the middle with its edit controls beside it, and
 * a parts drawer along the bottom. The previous version placed every control
 * from the top-left and let them collide with the readout — buttons landed on
 * top of the mass and cost figures, which made both unreadable.
 *
 * Parts are grouped into drawers rather than listed as thirty entries in one
 * column, and a blueprint can be loaded whole so that flying does not require
 * solving vehicle design first.
 */
public class BuilderView extends View {

    public interface Host {
        void onLaunch(Craft c);
        void onBack();
    }

    private Host host;
    public Tech tech;
    public Craft craft = new Craft();
    public Universe universe;

    /** Autopilot plan chosen before launch. -1 is manual. */
    /** The editable flight plan. Empty means manual flight. */
    public Mission mission = new Mission();
    private boolean showPlan;

    private final Scene scene = new Scene();

    private float unit, pad;
    private boolean portrait;
    private int selected = -1;
    private int group = Part.G_POD;
    private boolean showBlueprints;

    // bands
    private final RectF topBar = new RectF(), drawer = new RectF(),
            actionBar = new RectF();

    // top bar
    private final RectF rBack = new RectF(), rClear = new RectF(),
            rBlueprint = new RectF(), rPlan = new RectF(), rLaunch = new RectF();

    // edit controls, beside the vehicle
    private final RectF rUp = new RectF(), rDown = new RectF(), rDel = new RectF(),
            rStageUp = new RectF(), rStageDown = new RectF();

    // drawer
    private final List<RectF> tabs = new ArrayList<RectF>();
    private final List<RectF> slots = new ArrayList<RectF>();
    private final List<Part> shown = new ArrayList<Part>();
    private final List<RectF> bpSlots = new ArrayList<RectF>();

    private final List<RectF> stackRects = new ArrayList<RectF>();

    private float drawerScroll;
    private float dragX, dragY, scrollStart;
    private boolean dragging;

    public BuilderView(Context c) { super(c); setClickable(true); }

    public void setHost(Host h) { host = h; }

    // =====================================================================
    // LAYOUT — three bands, computed from the viewport every time
    // =====================================================================

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    private void layout(int w, int h) {
        portrait = h >= w;
        unit = Math.min(w, h) * 0.13f;
        pad = unit * 0.22f;

        float barH = unit * 0.62f;
        // Actions sit at the BOTTOM, next to the parts drawer and under the
        // thumb, not across the top of the screen out of reach.
        topBar.set(0, 0, w, 0);

        float drawerH = portrait ? unit * 3.0f : unit * 2.4f;
        float actionH = barH + pad * 1.6f;
        drawer.set(0, h - drawerH, w, h);
        actionBar.set(0, drawer.top - actionH, w, drawer.top);

        // ---- actions: five, evenly divided, along the bottom -------------
        float n = 5;
        float bw = (w - pad * (n + 1)) / n;
        float by = actionBar.top + pad * 0.8f;
        rBack.set(pad, by, pad + bw, by + barH);
        rClear.set(pad * 2 + bw, by, pad * 2 + bw * 2, by + barH);
        rBlueprint.set(pad * 3 + bw * 2, by, pad * 3 + bw * 3, by + barH);
        rPlan.set(pad * 4 + bw * 3, by, pad * 4 + bw * 4, by + barH);
        rLaunch.set(pad * 5 + bw * 4, by, pad * 5 + bw * 5, by + barH);

        // ---- edit controls: a column on the right of the bay -------------
        float ew = unit * 0.66f, eh = unit * 0.46f;
        float ex = w - pad - ew;
        float ey = pad * 2;
        rUp.set(ex, ey, ex + ew, ey + eh);              ey += eh + pad * 0.35f;
        rDown.set(ex, ey, ex + ew, ey + eh);            ey += eh + pad * 0.35f;
        rDel.set(ex, ey, ex + ew, ey + eh);             ey += eh + pad * 0.8f;
        rStageUp.set(ex, ey, ex + ew, ey + eh);         ey += eh + pad * 0.35f;
        rStageDown.set(ex, ey, ex + ew, ey + eh);
        buildDrawer(w, h);
    }

    private void buildDrawer(int w, int h) {
        tabs.clear(); slots.clear(); shown.clear(); bpSlots.clear();

        float tabH = unit * 0.44f;
        float tw = (w - pad * 2) / Part.GROUPS.length;
        for (int i = 0; i < Part.GROUPS.length; i++) {
            tabs.add(new RectF(pad + i * tw, drawer.top + pad * 0.5f,
                    pad + (i + 1) * tw - pad * 0.3f, drawer.top + pad * 0.5f + tabH));
        }

        for (Part p : Part.ALL) {
            if (p.group == group && (tech == null || tech.unlocked(p))) shown.add(p);
        }

        // Parts run ACROSS in a scrolling strip, so the drawer is a fixed
        // height whatever the group holds.
        float sw = unit * 1.55f, sh = drawer.height() - tabH - pad * 2.2f;
        float sy = drawer.top + tabH + pad * 1.2f;
        for (int i = 0; i < shown.size(); i++) {
            float sx = pad + i * (sw + pad * 0.5f);
            slots.add(new RectF(sx, sy, sx + sw, sy + sh));
        }
        for (int i = 0; i < Blueprint.ALL.length; i++) {
            float sx = pad + i * (sw * 1.5f + pad * 0.5f);
            bpSlots.add(new RectF(sx, sy, sx + sw * 1.5f, sy + sh));
        }
    }

    public void refreshCatalogue() { layout(getWidth(), getHeight()); invalidate(); }

    // =====================================================================
    // DRAW
    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Art.VOID);

        drawVehicle(c, w, h);
        drawReadout(c, w, h);
        drawTopBar(c, w, h);
        drawDrawer(c, w, h);
        drawDrag(c);        // ghost and drop line, on top of everything
    }

    private void drawTopBar(Canvas c, int w, int h) {
        c.drawRect(actionBar, Art.fill(Art.alpha(Art.VOID_2, 0.96f)));
        c.drawLine(0, actionBar.top, w, actionBar.top, Art.stroke(1f, Art.EDGE));

        button(c, rBack, "BACK", Art.INK_DIM);
        button(c, rClear, "CLEAR", Art.RED);
        button(c, rBlueprint, showBlueprints ? "PARTS" : "BLUEPRINTS", Art.AMBER);
        button(c, rPlan, mission.steps.isEmpty() ? "PLAN: MANUAL"
                : "PLAN: " + mission.steps.size() + " STEPS",
                mission.steps.isEmpty() ? Art.INK_DIM : Art.MAGENTA);
        button(c, rLaunch, "LAUNCH", flyable() ? Art.GREEN : Art.EDGE);
    }

    private void drawVehicle(Canvas c, int w, int h) {
        stackRects.clear();
        float top = pad * 2;
        float bot = actionBar.top - pad * 1.4f;

        if (craft.units.isEmpty()) {
            Art.textCenter(c, "EMPTY", w * 0.5f, (top + bot) * 0.5f,
                    Art.text(12f, Art.INK_DIM, true));
            Art.textCenter(c, "drag a part up from the drawer, or tap BLUEPRINTS",
                    w * 0.5f, (top + bot) * 0.5f + Art.dp(20),
                    Art.text(6.5f, Art.alpha(Art.INK_DIM, 0.8f), false));
            return;
        }

        float bayL = portrait ? w * 0.22f : w * 0.30f;
        float bayR = w - unit * 1.0f - pad * 2;
        float cx = (bayL + bayR) * 0.5f;

        double totalH = Math.max(0.6, craft.height());
        double widest = Math.max(0.6, craft.widest() * (craft.hasRadial() ? 2.6 : 1.0));
        float scale = (float) Math.min((bot - top) * 0.92 / totalH,
                                       (bayR - bayL) * 0.85 / widest);
        float cy = (top + bot) * 0.5f;

        stackRects.addAll(scene.stack(c, craft, cx, cy, scale, selected));

        // stage bands and names, clear of the vehicle
        for (int i = 0; i < stackRects.size() && i < craft.units.size(); i++) {
            RectF r = stackRects.get(i);
            Craft.Unit un = craft.units.get(i);
            int sc = stageColour(un.stage);
            float bx = cx + (float) (widest * scale * 0.5) + Art.dp(6);
            c.drawRect(new RectF(bx, r.top + 1, bx + Art.dp(4), r.bottom - 1), Art.fill(sc));
            if (r.height() > Art.dp(10)) {
                c.drawText("S" + un.stage, bx + Art.dp(7), r.centerY() + Art.dp(3),
                        Art.text(5.4f, sc, true));
                Art.textRight(c, un.type.name, cx - (float) (widest * scale * 0.5) - Art.dp(6),
                        r.centerY() + Art.dp(3),
                        Art.text(5.4f, i == selected ? Art.CYAN : Art.INK_DIM, i == selected));
            }
        }

        boolean sel = selected >= 0 && selected < craft.units.size();
        button(c, rUp,   "▲", sel ? Art.CYAN : Art.EDGE);
        button(c, rDown, "▼", sel ? Art.CYAN : Art.EDGE);
        button(c, rDel,  "DEL",    sel ? Art.RED : Art.EDGE);
        button(c, rStageUp,   "stg+", sel ? Art.AMBER : Art.EDGE);
        button(c, rStageDown, "stg-", sel ? Art.AMBER : Art.EDGE);
    }

    private void drawReadout(Canvas c, int w, int h) {
        float x = pad;
        float y = pad * 3f;
        float lh = unit * 0.30f;
        float colW = portrait ? w * 0.20f : w * 0.26f;

        Paint k = Art.text(5.6f, Art.INK_DIM, false);
        k.setLetterSpacing(0.14f);
        Paint v = Art.text(7.4f, Art.INK, true);

        Body home = universe != null ? universe.home : null;
        String[][] rows = {
            {"MASS",   Art.mass(craft.mass())},
            {"COST",   String.valueOf(craft.cost())},
            {"STAGES", String.valueOf(craft.maxStage() + 1)},
            {"dV VAC", Art.speed(craft.totalDeltaV(0))},
            {"dV SEA", Art.speed(craft.totalDeltaV(1))},
            {"TWR",    home == null ? "--"
                    : String.format(java.util.Locale.US, "%.2f", craft.twr(home, 1))},
        };
        for (String[] row : rows) {
            c.drawText(row[0], x, y, k);
            Art.textRight(c, row[1], x + colW, y + Art.dp(1), v);
            y += lh;
        }
        y += lh * 0.3f;
        for (int s = 0; s <= craft.maxStage() && s < 8; s++) {
            c.drawText("S" + s, x, y, Art.text(5.4f, stageColour(s), true));
            Art.textRight(c, Art.speed(craft.stageDeltaV(s, 0)), x + colW, y,
                    Art.text(5.8f, stageColour(s), false));
            y += lh * 0.72f;
        }

        // verdict, just above the drawer
        double dvv = craft.totalDeltaV(0);
        String verdict; int vc;
        if (craft.units.isEmpty()) { verdict = ""; vc = Art.INK_DIM; }
        else if (!craft.has(Part.POD)) { verdict = "NEEDS A CAPSULE OR PROBE"; vc = Art.RED; }
        else if (craft.thrust(1) <= 0) { verdict = "NEEDS AN ENGINE"; vc = Art.RED; }
        else if (craft.totalFuel() <= 0) { verdict = "NEEDS FUEL"; vc = Art.RED; }
        else if (craft.twr(home, 1) < 1.05) { verdict = "TOO HEAVY TO LIFT OFF"; vc = Art.RED; }
        else if (dvv < 1200) { verdict = "SUBORBITAL HOP"; vc = Art.AMBER; }
        else if (dvv < 2100) { verdict = "WILL NOT REACH ORBIT"; vc = Art.AMBER; }
        else if (dvv < 3200) { verdict = "LOW ORBIT"; vc = Art.GREEN; }
        else if (dvv < 4600) { verdict = "ORBIT + MANOEUVRING"; vc = Art.GREEN; }
        else if (dvv < 6500) { verdict = "CAN REACH LUNA"; vc = Art.CYAN; }
        else { verdict = "INTERPLANETARY"; vc = Art.MAGENTA; }
        if (verdict.length() > 0) {
            Paint p = Art.text(7f, vc, true);
            p.setLetterSpacing(0.14f);
            Art.textCenter(c, verdict, w * 0.5f, actionBar.top - Art.dp(6), p);
        }
    }

    private void drawDrawer(Canvas c, int w, int h) {
        c.drawRect(drawer, Art.fill(Art.alpha(Art.VOID_2, 0.97f)));
        c.drawLine(0, drawer.top, w, drawer.top, Art.stroke(1f, Art.EDGE));

        if (showPlan) { drawPlan(c, w, h); return; }
        if (showBlueprints) {
            Paint t = Art.text(6f, Art.AMBER, true);
            t.setLetterSpacing(0.22f);
            c.drawText("READY-MADE VEHICLES", pad, drawer.top + unit * 0.30f, t);
            c.save();
            c.clipRect(drawer);
            c.translate(-drawerScroll, 0);
            for (int i = 0; i < bpSlots.size(); i++) {
                Blueprint b = Blueprint.ALL[i];
                RectF r = bpSlots.get(i);
                boolean ok = b.available(tech);
                int col = ok ? Art.AMBER : Art.alpha(Art.INK_DIM, 0.4f);
                Art.panel(c, r, col, Art.alpha(Art.VOID, 0.8f), 5);
                Paint n = Art.text(6.6f, col, true);
                c.drawText(b.name, r.left + Art.dp(7), r.top + Art.dp(14), n);
                Paint d = Art.text(5.2f, Art.alpha(Art.INK_DIM, 0.85f), false);
                wrapText(c, b.blurb, r.left + Art.dp(7), r.top + Art.dp(27),
                        r.width() - Art.dp(14), d);
                if (!ok) Art.textRight(c, "LOCKED", r.right - Art.dp(7),
                        r.bottom - Art.dp(7), Art.text(5.2f, Art.RED, true));
            }
            c.restore();
            return;
        }

        for (int i = 0; i < tabs.size(); i++) {
            boolean on = i == group;
            RectF r = tabs.get(i);
            Art.panel(c, r, on ? Art.CYAN : Art.EDGE,
                    on ? Art.alpha(Art.CYAN, 0.16f) : 0, 4);
            Paint p = Art.text(5.2f, on ? Art.CYAN : Art.INK_DIM, on);
            Art.textCenter(c, Part.GROUPS[i], r.centerX(), r.centerY() + Art.dp(2), p);
        }

        c.save();
        c.clipRect(drawer.left, tabs.isEmpty() ? drawer.top : tabs.get(0).bottom,
                drawer.right, drawer.bottom);
        c.translate(-drawerScroll, 0);
        for (int i = 0; i < slots.size(); i++) {
            Part p = shown.get(i);
            RectF r = slots.get(i);
            boolean afford = tech == null || tech.sandbox || tech.funds >= p.cost;
            int col = afford ? Art.INK : Art.alpha(Art.RED, 0.7f);
            Art.panel(c, r, Art.EDGE, Art.alpha(Art.VOID, 0.7f), 4);
            Paint n = Art.text(5.6f, col, true);
            wrapText(c, p.name, r.left + Art.dp(5), r.top + Art.dp(12),
                    r.width() - Art.dp(10), n);
            // A picture of the actual part, drawn by the same renderer that
            // draws it on the rocket. A drawer of thirty text labels is not a
            // parts bin, it is a spreadsheet.
            scene.icon(c, p, r.centerX(), r.centerY() + Art.dp(4),
                    Math.min(r.width() * 0.44f, r.height() * 0.34f));
            Paint s = Art.text(5f, Art.alpha(Art.INK_DIM, 0.85f), false);
            c.drawText(Art.mass(p.wetMass()), r.left + Art.dp(5), r.bottom - Art.dp(17), s);
            if (p.thrust > 0) {
                c.drawText(Math.round(p.thrust / 1000) + " kN",
                        r.left + Art.dp(5), r.bottom - Art.dp(9), s);
            } else if (p.fuel > 0) {
                c.drawText(Math.round(p.fuel) + " kg", r.left + Art.dp(5),
                        r.bottom - Art.dp(9), s);
            }
            Art.textRight(c, String.valueOf(p.cost), r.right - Art.dp(5),
                    r.bottom - Art.dp(9), Art.text(5.2f, Art.AMBER, false));
            if (p.radial) {
                Art.textRight(c, "SIDE", r.right - Art.dp(5), r.top + Art.dp(11),
                        Art.text(4.8f, Art.MAGENTA, true));
            }
        }
        c.restore();
    }

    /** Two-line wrap for the narrow cards. */
    private void wrapText(Canvas c, String s, float x, float y, float maxW, Paint p) {
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        int drawn = 0;
        for (String word : words) {
            String probe = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(probe) > maxW && line.length() > 0) {
                c.drawText(line.toString(), x, y + drawn * Art.dp(8), p);
                drawn++;
                line.setLength(0); line.append(word);
                if (drawn >= 2) return;
            } else {
                line.setLength(0); line.append(probe);
            }
        }
        if (line.length() > 0) c.drawText(line.toString(), x, y + drawn * Art.dp(8), p);
    }

    private int stageColour(int s) {
        int[] cols = {Art.AMBER, Art.CYAN, Art.GREEN, Art.MAGENTA, Art.RED, 0xFFFFFFFF};
        return cols[Math.abs(s) % cols.length];
    }

    private boolean flyable() {
        return !craft.units.isEmpty() && craft.has(Part.POD) && craft.thrust(1) > 0;
    }

    private void button(Canvas c, RectF r, String label, int colour) {
        Art.panel(c, r, colour, Art.alpha(colour, 0.14f), 4);
        Paint p = Art.text(5.8f, colour, true);
        p.setLetterSpacing(0.06f);
        Art.textCenter(c, label, r.centerX(), r.centerY() + Art.dp(2.2f), p);
    }

    // =====================================================================
    // TOUCH
    // =====================================================================

    // =====================================================================
    // DRAG AND DROP
    // =====================================================================

    /** A part lifted out of the drawer and not yet dropped. */
    private Part carried;
    /** A part lifted out of the stack, by index, for reordering. */
    private int carriedIndex = -1;
    /** Where the finger is, and where the part would land. */
    private float ghostX, ghostY;
    private int dropAt = -1;
    private int pendingSlot = -1, pendingStack = -1;
    private float downX, downY;

    /**
     * Where a part dropped at this height would go.
     *
     * Measured against the MIDPOINT of each part already in the stack: above
     * the middle means it goes in front of that part, below means after it.
     * Using the whole rect instead would make the target flicker between two
     * answers as the finger crossed a boundary.
     */
    private int insertionFor(float y) {
        if (stackRects.isEmpty()) return 0;
        for (int i = 0; i < stackRects.size(); i++) {
            RectF r = stackRects.get(i);
            if (y < r.centerY()) return i;
        }
        return stackRects.size();
    }

    /** The ghost under the finger and the line showing where it lands. */
    private void drawDrag(Canvas c) {
        if (carried == null && carriedIndex < 0) return;

        Part t = carried != null ? carried : craft.units.get(carriedIndex).type;

        if (dropAt >= 0 && !stackRects.isEmpty()) {
            float y;
            if (dropAt < stackRects.size()) y = stackRects.get(dropAt).top;
            else y = stackRects.get(stackRects.size() - 1).bottom;
            float cx = stackRects.get(0).centerX();
            float halfW = Math.max(Art.dp(30), stackRects.get(0).width() * 0.9f);
            Paint p = Art.stroke(2.2f, Art.CYAN);
            c.drawLine(cx - halfW, y, cx + halfW, y, p);
            c.drawCircle(cx - halfW, y, Art.dp(3), Art.fill(Art.CYAN));
            c.drawCircle(cx + halfW, y, Art.dp(3), Art.fill(Art.CYAN));
        } else if (craft.units.isEmpty()) {
            Art.textCenter(c, "DROP TO PLACE", getWidth() * 0.5f,
                    (topBar.bottom + drawer.top) * 0.5f,
                    Art.text(8f, Art.CYAN, true));
        }

        float w = Art.dp(34), h = Art.dp(34) * (float) (t.height / Math.max(0.3, t.width));
        h = Math.min(h, Art.dp(70));
        RectF g = new RectF(ghostX - w / 2, ghostY - h / 2, ghostX + w / 2, ghostY + h / 2);
        Art.panel(c, g, Art.CYAN, Art.alpha(Art.CYAN, 0.22f), 4);
        Paint n = Art.text(5f, Art.CYAN, true);
        Art.textCenter(c, t.name, ghostX, g.bottom + Art.dp(9), n);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x; downY = y;
                dragX = x; dragY = y;
                scrollStart = drawerScroll;
                dragging = false;
                pendingSlot = -1; pendingStack = -1;
                carried = null; carriedIndex = -1; dropAt = -1;

                // Remember what is under the finger so a drag can lift it.
                if (drawer.contains(x, y) && !showBlueprints) {
                    float sx = x + drawerScroll;
                    for (int i = 0; i < slots.size(); i++) {
                        if (slots.get(i).contains(sx, y)) { pendingSlot = i; break; }
                    }
                } else {
                    for (int i = 0; i < stackRects.size(); i++) {
                        RectF r = stackRects.get(i);
                        RectF grab = new RectF(r.left - Art.dp(18), r.top,
                                r.right + Art.dp(18), r.bottom);
                        if (grab.contains(x, y)) { pendingStack = i; break; }
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                float slop = Art.dp(10);

                if (carried != null || carriedIndex >= 0) {
                    ghostX = x; ghostY = y;
                    dropAt = drawer.contains(x, y) ? -1 : insertionFor(y);
                    invalidate();
                    return true;
                }

                // Lifting a part out of the drawer: an upward drag, not a
                // sideways one, which is how the strip is scrolled.
                if (pendingSlot >= 0 && dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                    carried = shown.get(pendingSlot);
                    ghostX = x; ghostY = y;
                    dragging = true;
                    invalidate();
                    return true;
                }
                // Lifting a part out of the stack to reorder it.
                if (pendingStack >= 0 && Math.abs(dy) > slop) {
                    carriedIndex = pendingStack;
                    selected = pendingStack;
                    ghostX = x; ghostY = y;
                    dragging = true;
                    invalidate();
                    return true;
                }

                if (Math.abs(dx) > slop) dragging = true;
                if (dragging && drawer.contains(x, y)) {
                    float content = showBlueprints
                            ? (bpSlots.isEmpty() ? 0 : bpSlots.get(bpSlots.size() - 1).right)
                            : (slots.isEmpty() ? 0 : slots.get(slots.size() - 1).right);
                    float max = Math.max(0, content + pad - getWidth());
                    drawerScroll = Math.max(0, Math.min(max, scrollStart - dx));
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (carried != null) {
                    if (!drawer.contains(x, y)) {
                        int at = Math.max(0, Math.min(craft.units.size(), insertionFor(y)));
                        int stage = at > 0 && at <= craft.units.size()
                                ? craft.units.get(at - 1).stage : 0;
                        craft.units.add(at, new Craft.Unit(carried, stage));
                        selected = at;
                    }
                    carried = null; dropAt = -1; dragging = false;
                    invalidate();
                    return true;
                }
                if (carriedIndex >= 0) {
                    int at = Math.max(0, Math.min(craft.units.size(), insertionFor(y)));
                    if (drawer.contains(x, y)) {
                        // dropped back into the drawer: that means remove it
                        craft.units.remove(carriedIndex);
                        selected = -1;
                    } else {
                        Craft.Unit u = craft.units.remove(carriedIndex);
                        if (at > carriedIndex) at--;
                        at = Math.max(0, Math.min(craft.units.size(), at));
                        craft.units.add(at, u);
                        selected = at;
                    }
                    carriedIndex = -1; dropAt = -1; dragging = false;
                    invalidate();
                    return true;
                }
                if (!dragging) tap(x, y);
                dragging = false;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                carried = null; carriedIndex = -1; dropAt = -1; dragging = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void tap(float x, float y) {
        if (rBack.contains(x, y)) { if (host != null) host.onBack(); return; }
        if (rClear.contains(x, y)) { craft = new Craft(); selected = -1; return; }
        if (rBlueprint.contains(x, y)) {
            showBlueprints = !showBlueprints; drawerScroll = 0; return;
        }
        if (rPlan.contains(x, y)) { showPlan = !showPlan; showBlueprints = false; return; }
        if (rLaunch.contains(x, y)) {
            if (flyable() && host != null) host.onLaunch(craft);
            return;
        }

        boolean sel = selected >= 0 && selected < craft.units.size();
        if (rUp.contains(x, y) && sel && selected > 0) {
            craft.units.add(selected - 1, craft.units.remove(selected));
            selected--; return;
        }
        if (rDown.contains(x, y) && sel && selected < craft.units.size() - 1) {
            craft.units.add(selected + 1, craft.units.remove(selected));
            selected++; return;
        }
        if (rDel.contains(x, y) && sel) {
            craft.units.remove(selected); selected = -1; return;
        }
        if (rStageUp.contains(x, y) && sel) { craft.units.get(selected).stage++; return; }
        if (rStageDown.contains(x, y) && sel) {
            Craft.Unit u = craft.units.get(selected);
            u.stage = Math.max(0, u.stage - 1);
            return;
        }

        if (drawer.contains(x, y)) {
            if (showPlan) { tapPlan(x, y); return; }
            if (!showBlueprints) {
                for (int i = 0; i < tabs.size(); i++) {
                    if (tabs.get(i).contains(x, y)) {
                        group = i; drawerScroll = 0; buildDrawer(getWidth(), getHeight());
                        return;
                    }
                }
                float sx = x + drawerScroll;
                for (int i = 0; i < slots.size(); i++) {
                    if (slots.get(i).contains(sx, y)) { addPart(shown.get(i)); return; }
                }
            } else {
                float sx = x + drawerScroll;
                for (int i = 0; i < bpSlots.size(); i++) {
                    if (bpSlots.get(i).contains(sx, y)) {
                        Craft b = Blueprint.ALL[i].build(tech);
                        if (b != null) { craft = b; selected = -1; showBlueprints = false; }
                        return;
                    }
                }
            }
            return;
        }

        for (int i = 0; i < stackRects.size(); i++) {
            RectF r = stackRects.get(i);
            RectF grab = new RectF(r.left - Art.dp(18), r.top,
                    r.right + Art.dp(18), r.bottom);
            if (grab.contains(x, y)) { selected = (selected == i) ? -1 : i; return; }
        }
        selected = -1;
    }

    // =====================================================================
    // FLIGHT PLAN EDITOR
    // =====================================================================

    private final List<RectF> planRows = new ArrayList<RectF>();
    private final List<RectF> planAdd = new ArrayList<RectF>();

    /** Steps you can append, in the order they are usually wanted. */
    private static final int[] ADDABLE = {
        Mission.ASCEND, Mission.CIRCULARISE, Mission.TRANSFER,
        Mission.WARP, Mission.LAND, Mission.BOOSTER, Mission.DOCK, Mission.HOVER
    };

    private void drawPlan(Canvas c, int w, int h) {
        planRows.clear(); planAdd.clear();

        Paint t = Art.text(6f, Art.MAGENTA, true);
        t.setLetterSpacing(0.2f);
        c.drawText("FLIGHT PLAN  —  tap a step to remove it", pad,
                drawer.top + unit * 0.28f, t);

        float rowH = unit * 0.36f;
        float y = drawer.top + unit * 0.42f;
        float colW = (w - pad * 3) * 0.5f;

        // the plan itself, down the left
        if (mission.steps.isEmpty()) {
            c.drawText("empty — the vehicle flies manually", pad, y + Art.dp(11),
                    Art.text(5.4f, Art.INK_DIM, false));
        }
        for (int i = 0; i < mission.steps.size() && i < 6; i++) {
            RectF r = new RectF(pad, y, pad + colW, y + rowH - Art.dp(3));
            planRows.add(r);
            Art.panel(c, r, Art.MAGENTA, Art.alpha(Art.MAGENTA, 0.12f), 3);
            c.drawText((i + 1) + ".  " + mission.steps.get(i).label(),
                    r.left + Art.dp(6), r.centerY() + Art.dp(3),
                    Art.text(5.4f, Art.INK, false));
            y += rowH;
        }

        // things you can add, down the right
        float ax = pad * 2 + colW;
        float ay = drawer.top + unit * 0.42f;
        for (int i = 0; i < ADDABLE.length; i++) {
            float cx = ax + (i % 2) * (colW * 0.5f);
            float cy = ay + (i / 2) * (rowH - Art.dp(2));
            RectF r = new RectF(cx, cy, cx + colW * 0.5f - Art.dp(3),
                    cy + rowH - Art.dp(5));
            planAdd.add(r);
            Art.panel(c, r, Art.CYAN, Art.alpha(Art.CYAN, 0.10f), 3);
            Art.textCenter(c, "+ " + Mission.NAMES[ADDABLE[i]], r.centerX(),
                    r.centerY() + Art.dp(3), Art.text(4.9f, Art.CYAN, true));
        }
    }

    private boolean tapPlan(float x, float y) {
        for (int i = 0; i < planRows.size(); i++) {
            if (planRows.get(i).contains(x, y)) {
                // tapping a step cycles its value, holding removes it
                Mission.Step s = mission.steps.get(i);
                if (s.kind == Mission.ASCEND) {
                    double[] alts = {25000, 40000, 60000, 100000, 200000};
                    int idx = 0;
                    for (int k = 0; k < alts.length; k++) if (alts[k] <= s.value) idx = k;
                    s.value = alts[(idx + 1) % alts.length];
                } else if (s.kind == Mission.TRANSFER) {
                    String[] bodies = {"LUNA", "VESPER", "FROST", "EMBER", "THE MAW"};
                    int idx = 0;
                    for (int k = 0; k < bodies.length; k++) {
                        if (bodies[k].equals(s.target)) idx = k;
                    }
                    s.target = bodies[(idx + 1) % bodies.length];
                } else if (s.kind == Mission.WARP) {
                    double[] w = {60, 300, 1800, 7200, 43200};
                    int idx = 0;
                    for (int k = 0; k < w.length; k++) if (w[k] <= s.value) idx = k;
                    s.value = w[(idx + 1) % w.length];
                } else {
                    mission.steps.remove(i);
                }
                return true;
            }
        }
        for (int i = 0; i < planAdd.size(); i++) {
            if (planAdd.get(i).contains(x, y)) {
                int kind = ADDABLE[i];
                double v = kind == Mission.ASCEND ? 40000 : kind == Mission.WARP ? 300 : 0;
                String tgt = kind == Mission.TRANSFER ? "LUNA" : null;
                if (mission.steps.size() < 8) {
                    mission.steps.add(new Mission.Step(kind, v, tgt));
                }
                return true;
            }
        }
        return false;
    }

    private void addPart(Part p) {
        int at = (selected >= 0 && selected < craft.units.size())
                ? selected + 1 : craft.units.size();
        int stage = at > 0 && at <= craft.units.size()
                ? craft.units.get(at - 1).stage : 0;
        craft.units.add(at, new Craft.Unit(p, stage));
        selected = at;
    }
}
