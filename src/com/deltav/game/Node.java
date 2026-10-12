package com.deltav.game;

/**
 * A planned burn: where on the orbit, and how much in which direction.
 *
 * This is the difference between a game where you fire the engine and hope,
 * and one where you can say "burn 840 m/s prograde at apoapsis and I will
 * arrive at Luna". The node computes the orbit you WOULD have, so the decision
 * is made on the ground rather than discovered halfway through the burn.
 *
 * Deliberately restricted to prograde/radial components in the orbital plane —
 * there is no plane to change in two dimensions, so a node is two numbers and
 * a time, and that is the whole planner.
 */
public class Node {

    public boolean active;

    /** Seconds from now until the burn. */
    public double eta;

    /** Delta-v components, metres per second. */
    public double prograde, radial;

    /** Cached prediction, recomputed whenever anything changes. */
    public Orbit result;
    public Body resultBody;
    public double burnSeconds;

    /** Where the craft will be when the burn starts. */
    public double px, py, pvx, pvy;

    public void clear() { active = false; result = null; }

    public double magnitude() { return Math.hypot(prograde, radial); }

    /**
     * Recomputes the position at the node and the orbit the burn would produce.
     *
     * The burn is modelled as instantaneous. Over a 900 m/s burn lasting a
     * minute that is a real approximation — the craft moves a long way while
     * thrusting — but it is the same approximation every space agency uses for
     * planning, and correcting for it is what the burn timer and the live
     * orbit line are for.
     */
    public void solve(Flight f) {
        if (!active) { result = null; return; }

        Craft c = f.c;
        Body b = c.soi;
        resultBody = b;

        Orbit now = f.orbit();
        if (!now.valid) { result = null; return; }

        double[] p = new double[2], v = new double[2];
        now.propagate(eta, p, v);
        px = p[0]; py = p[1]; pvx = v[0]; pvy = v[1];

        double sp = Math.hypot(v[0], v[1]);
        if (sp < 1e-6) { result = null; return; }

        // prograde is along velocity; radial is outward from the body
        double ux = v[0] / sp, uy = v[1] / sp;
        double r = Math.hypot(p[0], p[1]);
        double rx = r > 0 ? p[0] / r : 0, ry = r > 0 ? p[1] / r : 0;

        double nvx = v[0] + ux * prograde + rx * radial;
        double nvy = v[1] + uy * prograde + ry * radial;

        result = Orbit.from(p[0], p[1], nvx, nvy, b.mu);
        burnSeconds = burnTime(c, f, magnitude());
    }

    /**
     * How long the burn will take at full throttle.
     *
     * Needed because a node is executed by starting HALF the burn time early
     * and splitting it either side of the node — burning entirely after the
     * node puts all of the impulse in the wrong place and misses the target.
     */
    public static double burnTime(Craft c, Flight f, double dv) {
        if (dv <= 0) return 0;
        double pr = f.pressureRatio();
        double thrust = c.thrust(pr);
        if (thrust <= 0) return Double.POSITIVE_INFINITY;
        double flow = c.massFlow(pr);
        if (flow <= 0) return Double.POSITIVE_INFINITY;
        double m0 = c.mass();
        double ve = thrust / flow;
        // rocket equation solved for burned mass, then divided by flow rate
        double mf = m0 / Math.exp(dv / ve);
        double burned = m0 - mf;
        double have = c.totalFuel();
        if (burned > have) return Double.POSITIVE_INFINITY;
        return burned / flow;
    }

    /** Direction to point for the burn, in world radians. */
    public double heading() {
        double sp = Math.hypot(pvx, pvy);
        if (sp < 1e-6) return 0;
        double ux = pvx / sp, uy = pvy / sp;
        double r = Math.hypot(px, py);
        double rx = r > 0 ? px / r : 0, ry = r > 0 ? py / r : 0;
        double dx = ux * prograde + rx * radial;
        double dy = uy * prograde + ry * radial;
        if (Math.hypot(dx, dy) < 1e-9) return Math.atan2(uy, ux);
        return Math.atan2(dy, dx);
    }

    /**
     * Snaps the node to the next apoapsis or periapsis.
     *
     * Almost every useful burn happens at an apsis — circularising, raising
     * the far side, lowering the near side — so putting them one tap away
     * removes the fiddliest part of planning without removing the choice.
     */
    public void snapToApoapsis(Flight f) {
        Orbit o = f.orbit();
        if (!o.valid || !o.closed()) return;
        eta = timeTo(o, Math.PI);
        solve(f);
    }

    public void snapToPeriapsis(Flight f) {
        Orbit o = f.orbit();
        if (!o.valid) return;
        eta = timeTo(o, 0);
        solve(f);
    }

    /** Seconds until the orbit reaches a given true anomaly. */
    public static double timeTo(Orbit o, double targetNu) {
        if (!o.closed()) return 0;
        double n = Math.sqrt(o.mu / (o.a * o.a * o.a));
        double m0 = meanFromTrue(o.nu, o.e);
        double m1 = meanFromTrue(targetNu, o.e);
        double dm = m1 - m0;
        while (dm < 0) dm += 2 * Math.PI;
        return dm / n;
    }

    private static double meanFromTrue(double nu, double e) {
        double E = 2 * Math.atan2(Math.sqrt(1 - e) * Math.sin(nu / 2),
                                  Math.sqrt(1 + e) * Math.cos(nu / 2));
        return E - e * Math.sin(E);
    }

    // =====================================================================
    // EXECUTION
    // =====================================================================

    /**
     * Flies the node: point, wait, burn, stop.
     *
     * Returns true while it still has work to do. The burn starts half its
     * duration before the node and ends half after, which is what keeps a long
     * burn centred on the point it was planned for.
     */
    public boolean execute(Flight f, double dt) {
        if (!active || result == null) return false;
        Craft c = f.c;

        eta -= dt * f.warp;

        double dv = magnitude();
        if (dv < 0.05) { clear(); return false; }

        double half = Math.min(burnSeconds, 600) * 0.5;
        f.steer(heading(), dt);

        if (eta > half + 8) {
            c.throttle = 0;
            return true;
        }

        if (eta > half) {
            // final approach: warp down so the start is not overshot
            f.warp = 1;
            c.throttle = 0;
            return true;
        }

        // burning. Track how much has been applied and stop at the target.
        double pr = f.pressureRatio();
        double a = c.thrust(pr) / c.mass();
        applied += a * dt;

        if (applied >= dv || c.totalFuel() <= 0) {
            c.throttle = 0;
            clear();
            applied = 0;
            return false;
        }
        // ease off at the end so it does not overshoot by a whole frame's worth
        double left = dv - applied;
        c.throttle = left < a * 0.6 ? Math.max(0.06, left / Math.max(0.01, a * 0.6)) : 1;

        if (c.fuelInStage(c.stage) <= 0 && c.stage <= c.maxStage()) c.doStage();
        return true;
    }

    private double applied;

    public void resetProgress() { applied = 0; }

    public double remaining() { return Math.max(0, magnitude() - applied); }
}
