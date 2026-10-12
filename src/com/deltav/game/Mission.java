package com.deltav.game;

import java.util.ArrayList;
import java.util.List;

/**
 * An editable sequence of autopilot steps.
 *
 * Three fixed presets were not a flight plan, they were three buttons. A plan
 * is a list you build: climb to this altitude, circularise, go to that body,
 * land. The autopilot walks it one step at a time and stops if a step cannot
 * be completed, which is the honest behaviour — a plan that quietly skips the
 * step it could not fly is worse than one that stops and says so.
 */
public class Mission {

    public static final int ASCEND = 0, CIRCULARISE = 1, TRANSFER = 2,
            LAND = 3, HOVER = 4, BOOSTER = 5, DOCK = 6, WARP = 7;

    public static final String[] NAMES = {
        "ASCEND TO", "CIRCULARISE", "TRANSFER TO", "LAND", "HOVER TEST",
        "RECOVER BOOSTER", "DOCK", "WARP"
    };

    public static class Step {
        public int kind;
        public double value;        // altitude in metres, or warp seconds
        public String target;       // body name for TRANSFER

        public Step(int kind, double value, String target) {
            this.kind = kind; this.value = value; this.target = target;
        }

        public String label() {
            switch (kind) {
                case ASCEND:   return "ASCEND TO " + Art.dist(value);
                case TRANSFER: return "TRANSFER TO " + (target == null ? "?" : target);
                case WARP:     return "WARP " + Art.clock(value);
                default:       return NAMES[kind];
            }
        }
    }

    public final List<Step> steps = new ArrayList<Step>();
    public int current = -1;        // -1 means not running
    public String status = "";

    public boolean running() { return current >= 0 && current < steps.size(); }

    public void start() { current = steps.isEmpty() ? -1 : 0; status = ""; }

    public void stop() { current = -1; status = ""; }

    public Step step() { return running() ? steps.get(current) : null; }

    public void advance() {
        current++;
        if (current >= steps.size()) { current = -1; status = "PLAN COMPLETE"; }
    }

    /** The default plan for a new vehicle: get to orbit and stay there. */
    public static Mission toOrbit(Body home) {
        Mission m = new Mission();
        double alt = home != null && home.hasAtmosphere()
                ? home.atmoHeight * 1.6 : 40000;
        m.steps.add(new Step(ASCEND, alt, null));
        m.steps.add(new Step(CIRCULARISE, 0, null));
        return m;
    }

    public Mission copy() {
        Mission m = new Mission();
        for (Step s : steps) m.steps.add(new Step(s.kind, s.value, s.target));
        return m;
    }

    /**
     * Drives the autopilot from the plan.
     *
     * Each step maps onto an existing controller; the plan's only job is to
     * decide when one is finished and the next should begin. Keeping the
     * flying in Autopilot and the sequencing here means a step can be tested
     * on its own.
     */
    public void update(Autopilot ap, Flight f, Universe u, double dt) {
        if (!running()) return;
        Step s = steps.get(current);
        Craft c = f.c;

        switch (s.kind) {
            case ASCEND:
                if (ap.mode != Autopilot.ASCENT) {
                    ap.engage(Autopilot.ASCENT, f);
                    ap.targetAlt = s.value;
                }
                if (f.orbit().closed()
                        && f.orbit().apoapsis() - c.soi.radius >= s.value * 0.97) {
                    ap.disengage(); advance();
                }
                if (c.totalFuel() <= 0) { status = "OUT OF FUEL"; stop(); }
                break;

            case CIRCULARISE:
                if (ap.mode != Autopilot.CIRCULARISE) ap.engage(Autopilot.CIRCULARISE, f);
                if (ap.mode == Autopilot.OFF) advance();
                break;

            case TRANSFER: {
                Body target = u.byName(s.target);
                if (target == null) { status = "NO TARGET"; stop(); break; }
                if (c.soi == target || (target.parent != null && c.soi == target)) {
                    ap.disengage(); advance(); break;
                }
                // Raise apoapsis until it reaches the target's orbit, then coast.
                Orbit o = f.orbit();
                double want = target.parent == c.soi ? target.orbitRadius : c.soi.soi * 0.9;
                if (o.closed() && o.apoapsis() >= want) {
                    c.throttle = 0;
                    status = "COASTING TO " + s.target;
                    f.warp = Math.max(f.warp, 100);
                } else {
                    if (ap.mode != Autopilot.HOLD_PRO) ap.engage(Autopilot.HOLD_PRO, f);
                    c.throttle = 1;
                    status = "RAISING ORBIT";
                    if (c.fuelInStage(c.stage) <= 0 && c.stage <= c.maxStage()) c.doStage();
                    if (c.totalFuel() <= 0) { status = "OUT OF FUEL"; stop(); }
                }
                break;
            }

            case LAND:
                if (ap.mode != Autopilot.LAND) ap.engage(Autopilot.LAND, f);
                if (c.landed) { ap.disengage(); advance(); }
                break;

            case HOVER:
                if (ap.mode != Autopilot.HOVER) ap.engage(Autopilot.HOVER, f);
                break;

            case BOOSTER:
                if (ap.mode != Autopilot.BOOSTER) ap.engage(Autopilot.BOOSTER, f);
                if (c.landed) { ap.disengage(); advance(); }
                break;

            case DOCK:
                if (ap.mode != Autopilot.DOCK) ap.engage(Autopilot.DOCK, f);
                if (ap.mode == Autopilot.OFF) advance();
                break;

            case WARP:
                s.value -= dt * f.warp;
                f.warp = Math.max(f.warp, 100);
                status = "WARPING";
                if (s.value <= 0) { f.warp = 1; advance(); }
                break;
        }
    }
}
