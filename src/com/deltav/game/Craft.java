package com.deltav.game;

import java.util.ArrayList;
import java.util.List;

/**
 * A vessel: a stack of parts, a state vector, and a staging list.
 *
 * The craft is one rigid body. It does not flex, parts do not break off under
 * load, and there is no torque from an off-axis engine. That is a real
 * simplification, and it is the right one: the interesting decisions in this
 * game are about delta-v, staging and when to burn, not about whether your
 * rocket is structurally sound.
 */
public class Craft {

    /** One part on the vehicle, with its own consumable state. */
    public static class Unit {
        public Part type;
        public double fuel;          // kg remaining
        public int stage;            // which stage it belongs to
        public boolean deployed;     // chutes, legs, panels
        public boolean detached;
        public double deployAnim;    // 0..1

        public Unit(Part t, int stage) {
            this.type = t; this.stage = stage; this.fuel = t.fuel;
        }
        public double mass() { return type.dryMass + fuel; }
    }

    public final List<Unit> units = new ArrayList<Unit>();
    public String name = "UNNAMED";

    /** State, in the frame of the dominant body. */
    public double x, y, vx, vy;
    public double angle = Math.PI / 2;    // heading, radians; up is +y at launch
    public double angVel;
    public double throttle;
    public boolean rcsOn;
    public boolean sasOn = true;

    public Body soi;
    public boolean landed, destroyed;
    public double landedAngle;            // surface angle when landed
    public int stage;                     // current stage index, counts down

    public double heat;                   // 0..1, re-entry
    public double maxAlt, maxSpeed;       // records, for science

    // =====================================================================
    // MASS AND PERFORMANCE
    // =====================================================================

    public double mass() {
        double m = 0;
        for (Unit u : units) if (!u.detached) m += u.mass();
        return Math.max(1, m);
    }

    public double dryMass() {
        double m = 0;
        for (Unit u : units) if (!u.detached) m += u.type.dryMass;
        return Math.max(1, m);
    }

    /** Live engines: attached, in the active stage or below, with fuel to feed them. */
    public List<Unit> activeEngines() {
        List<Unit> out = new ArrayList<Unit>();
        for (Unit u : units) {
            if (u.detached || u.type.kind != Part.ENGINE) continue;
            if (u.stage != stage) continue;
            out.add(u);
        }
        return out;
    }

    public double fuelInStage(int s) {
        double f = 0;
        for (Unit u : units) {
            if (u.detached || u.type.fuel <= 0) continue;
            if (u.stage == s) f += u.fuel;
        }
        return f;
    }

    public double totalFuel() {
        double f = 0;
        for (Unit u : units) if (!u.detached) f += u.fuel;
        return f;
    }

    /** Thrust available right now, at the ambient pressure given. */
    public double thrust(double pressureRatio) {
        if (fuelInStage(stage) <= 0) return 0;
        double t = 0;
        for (Unit u : activeEngines()) t += u.type.thrustAt(pressureRatio);
        return t;
    }

    /** Mass flow at full throttle, kg/s. */
    public double massFlow(double pressureRatio) {
        double f = 0;
        for (Unit u : activeEngines()) {
            double isp = u.type.ispAt(pressureRatio);
            if (isp > 0) f += u.type.thrustAt(pressureRatio) / (isp * 9.80665);
        }
        return f;
    }

    /**
     * Delta-v of a stage, by the rocket equation.
     *
     * This is the single most important number the game shows. It answers the
     * only question that matters before launch — can this design get where I
     * am pointing it — and it is why the builder computes it live rather than
     * letting the player find out at 30 km.
     */
    public double stageDeltaV(int s, double pressureRatio) {
        double wet = 0, dry = 0, thr = 0, flow = 0;
        for (Unit u : units) {
            if (u.detached) continue;
            if (u.stage < s) continue;              // stages below are still aboard
            wet += u.mass();
            dry += u.type.dryMass;
            if (u.stage == s && u.type.kind == Part.ENGINE) {
                double is = u.type.ispAt(pressureRatio);
                thr += u.type.thrustAt(pressureRatio);
                if (is > 0) flow += u.type.thrustAt(pressureRatio) / (is * 9.80665);
            }
        }
        // fuel that actually belongs to this stage
        double stageFuel = 0;
        for (Unit u : units) {
            if (!u.detached && u.stage == s) stageFuel += u.fuel;
        }
        if (thr <= 0 || stageFuel <= 0 || flow <= 0) return 0;
        double veff = thr / flow;
        double start = wet, end = wet - stageFuel;
        if (end <= 0) return 0;
        return veff * Math.log(start / end);
    }

    public double totalDeltaV(double pressureRatio) {
        double dv = 0;
        for (int s = 0; s <= maxStage(); s++) dv += stageDeltaV(s, pressureRatio);
        return dv;
    }

    public int maxStage() {
        int m = 0;
        for (Unit u : units) if (u.stage > m) m = u.stage;
        return m;
    }

    /** Thrust to weight against a body's surface gravity. Below 1 you sit there. */
    public double twr(Body b, double pressureRatio) {
        if (b == null) return 0;
        double w = mass() * b.surfaceG();
        return w > 0 ? thrust(pressureRatio) / w : 0;
    }

    public double dragArea() {
        double d = 0;
        for (Unit u : units) {
            if (u.detached) continue;
            d += u.type.drag * u.type.width;
            if (u.type.kind == Part.CHUTE && u.deployed) {
                d += u.type.chuteArea * u.deployAnim;
            }
            if (u.type.kind == Part.LEG && u.deployed) d += 0.4;
        }
        return Math.max(0.1, d);
    }

    public double height() {
        // Side boosters sit ALONGSIDE the stack, so they add width, not
        // length. Counting them in the height made a boosted rocket draw as a
        // tower twice as tall as it is.
        double h = 0;
        for (Unit u : units) if (!u.detached && !u.type.radial) h += u.type.height;
        return h;
    }

    public double widest() {
        double w = 0;
        for (Unit u : units) if (!u.detached && u.type.width > w) w = u.type.width;
        return Math.max(0.5, w);
    }

    /** Any side-mounted boosters aboard? */
    public boolean hasRadial() {
        for (Unit u : units) if (!u.detached && u.type.radial) return true;
        return false;
    }

    public boolean has(int kind) {
        for (Unit u : units) if (!u.detached && u.type.kind == kind) return true;
        return false;
    }

    public int count(int kind) {
        int n = 0;
        for (Unit u : units) if (!u.detached && u.type.kind == kind) n++;
        return n;
    }

    public double crew() {
        double c = 0;
        for (Unit u : units) if (!u.detached) c += u.type.crew;
        return c;
    }

    public int cost() {
        int c = 0;
        for (Unit u : units) c += u.type.cost;
        return c;
    }

    // =====================================================================
    // ACTIONS
    // =====================================================================

    /**
     * Drops the current stage and lights the next.
     *
     * Everything in the departing stage detaches at once — spent tanks and the
     * engines that fed on them — which is the whole point: you are throwing
     * away the mass you no longer need so the rocket equation stops punishing
     * you for carrying it.
     */
    public boolean doStage() {
        if (stage > maxStage()) return false;
        boolean any = false;
        for (Unit u : units) {
            if (!u.detached && u.stage == stage) {
                // a chute in this stage deploys rather than falls off
                if (u.type.kind == Part.CHUTE) { u.deployed = true; any = true; continue; }
                if (u.type.kind == Part.LEG || u.type.kind == Part.PANEL
                        || u.type.kind == Part.DOCK) { u.deployed = true; any = true; continue; }
                u.detached = true;
                any = true;
            }
        }
        stage++;
        return any;
    }

    /** Burns propellant from the active stage, draining tanks in order. */
    public void consume(double kg) {
        for (Unit u : units) {
            if (kg <= 0) break;
            if (u.detached || u.fuel <= 0) continue;
            if (u.stage != stage) continue;
            double take = Math.min(u.fuel, kg);
            u.fuel -= take;
            kg -= take;
        }
    }

    public void deployChutes() {
        for (Unit u : units) {
            if (!u.detached && u.type.kind == Part.CHUTE) u.deployed = true;
        }
    }

    public void toggleLegs() {
        boolean any = false;
        for (Unit u : units) if (!u.detached && u.type.kind == Part.LEG && !u.deployed) any = true;
        for (Unit u : units) {
            if (!u.detached && u.type.kind == Part.LEG) u.deployed = any;
        }
    }

    public Craft copy() {
        Craft c = new Craft();
        c.name = name;
        for (Unit u : units) {
            Unit n = new Unit(u.type, u.stage);
            n.fuel = u.fuel;
            c.units.add(n);
        }
        return c;
    }
}
