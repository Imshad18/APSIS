package com.deltav.game;

/**
 * Guidance. Flies the profiles that are tedious to fly by hand.
 *
 * Every mode here is a controller, not a cheat: it moves the same throttle and
 * the same heading the player has, under the same physics, and it can fail.
 * An ascent with too little delta-v still runs out; a landing burn started too
 * late still hits the ground. That matters, because an autopilot that cannot
 * fail teaches you nothing about why your rocket was wrong.
 */
public class Autopilot {

    public static final int OFF = 0, ASCENT = 1, CIRCULARISE = 2, LAND = 3,
            BOOSTER = 4, DOCK = 5, HOLD_PRO = 6, HOLD_RETRO = 7, HOVER = 8;

    public static final String[] NAMES = {
        "OFF", "ASCENT", "CIRCULARISE", "LANDING", "BOOSTER RTLS",
        "DOCKING", "HOLD PROGRADE", "HOLD RETROGRADE", "HOVER"
    };

    public int mode = OFF;
    public String status = "";
    public double targetAlt = 120000;      // ascent target, metres above mean
    public Craft dockTarget;
    public double landingSiteAngle = Double.NaN;

    private double phaseT;
    private int subPhase;

    /**
     * The lowest pitch commanded so far. A gravity turn only ever goes over,
     * never back up.
     *
     * Pitch was a pure function of altitude, so engaging ascent partway into a
     * flight snapped the vehicle back toward vertical and flew the turn again
     * from the start. Holding a floor means taking over mid-flight continues
     * the turn instead of restarting it.
     */
    private double pitchFloor = Math.PI / 2;

    public void engage(int m, Flight f) {
        mode = m;
        phaseT = 0;
        subPhase = 0;
        status = "";
        if (m == ASCENT && f != null) {
            pitchFloor = (f.c.landed || f.speed() < 30)
                    ? Math.PI / 2
                    : Math.abs(Math.atan2(f.verticalSpeed(),
                            Math.max(1e-6, horizontalOf(f))));
            // A sensible default: high enough to be out of the air, low enough
            // to be cheap. Above the atmosphere by a clear margin.
            Body b = f.c.soi;
            targetAlt = b.hasAtmosphere() ? b.atmoHeight * 1.45 : b.radius * 0.12;
        }
    }

    public void disengage() { mode = OFF; status = ""; }

    public boolean active() { return mode != OFF; }

    // =====================================================================

    public void update(Flight f, double dt) {
        if (mode == OFF || f.c.destroyed) return;
        phaseT += dt;
        switch (mode) {
            case ASCENT:      ascent(f, dt); break;
            case CIRCULARISE: circularise(f, dt); break;
            case LAND:        land(f, dt); break;
            case BOOSTER:     booster(f, dt); break;
            case DOCK:        dock(f, dt); break;
            case HOVER:       hover(f, dt); break;
            case HOLD_PRO:    f.steer(f.prograde(), dt); status = "PROGRADE"; break;
            case HOLD_RETRO:  f.steer(f.prograde() + Math.PI, dt); status = "RETROGRADE"; break;
        }
    }

    // =====================================================================
    // ASCENT — the gravity turn
    // =====================================================================

    /**
     * A gravity turn, not a pitch program on a timer.
     *
     * The pitch is a function of ALTITUDE, not of elapsed time, so it works
     * for a sluggish overweight stack and a screaming light one alike — the
     * heavy rocket simply spends longer at each pitch. Turning too early flips
     * you on your back in thick air; too late and you spend the whole ascent
     * fighting gravity straight up, which is the single most expensive way to
     * reach orbit.
     */
    private void ascent(Flight f, double dt) {
        Craft c = f.c;
        Body b = c.soi;
        double alt = f.altitudeMean();
        Orbit o = f.orbit();

        if (c.landed) { f.unland(); c.throttle = 1; }

        double turnStart = b.hasAtmosphere() ? b.atmoHeight * 0.02 : 200;
        double turnEnd   = b.hasAtmosphere() ? b.atmoHeight * 0.85 : b.radius * 0.05;

        // vertical (90 deg) at the pad, easing to horizontal (0 deg) by turnEnd
        double frac = (alt - turnStart) / Math.max(1, turnEnd - turnStart);
        frac = Math.max(0, Math.min(1, frac));
        double pitch = (1 - Math.sqrt(frac)) * (Math.PI / 2);
        pitch = Math.min(pitch, pitchFloor);
        pitchFloor = pitch;

        double up = f.radialOut();
        // east is 90 degrees round from up, in the direction the body spins
        // East, and east is to the RIGHT once the view has rotated local up to
        // screen up. It was up + PI/2, which comes out on the left.
        double east = up - Math.PI / 2;
        double heading = up * 0 + interpolateAngle(east, up, pitch / (Math.PI / 2));
        f.steer(heading, dt);

        // Throttle back through max-Q so the stack is not fighting the air
        double q = 0.5 * b.density(Math.max(0, alt)) * f.surfaceSpeed() * f.surfaceSpeed();
        double thr = 1.0;
        if (q > 18000) thr = 0.55;
        else if (q > 12000) thr = 0.75;

        // Ease off as apoapsis approaches the target, or it overshoots badly
        double apAlt = o.closed() ? o.apoapsis() - b.radius : Double.MAX_VALUE;
        if (apAlt > targetAlt * 0.96) thr = 0.25;
        if (apAlt >= targetAlt) {
            c.throttle = 0;
            status = "COAST TO APOAPSIS";
            // out of the air and apoapsis is high enough: hand over
            if (alt > (b.hasAtmosphere() ? b.atmoHeight : 0)) {
                engage(CIRCULARISE, f);
            }
            return;
        }
        c.throttle = thr;

        // stage automatically when the current one is dry
        if (c.fuelInStage(c.stage) <= 0 && c.stage <= c.maxStage()) {
            c.doStage();
        }
        status = "PITCH " + Math.round(Math.toDegrees(pitch)) + "°  AP "
                + Art.dist(Math.max(0, apAlt));
    }

    private double interpolateAngle(double a, double b, double t) {
        double d = ((b - a + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
        return a + d * Math.max(0, Math.min(1, t));
    }

    // =====================================================================
    // CIRCULARISE
    // =====================================================================

    /**
     * Burns prograde at apoapsis until periapsis catches up.
     *
     * Waits for the node rather than burning immediately, because a
     * circularisation burn away from apoapsis raises the wrong side of the
     * orbit and costs more the further from the node you start it.
     */
    private void circularise(Flight f, double dt) {
        Craft c = f.c;
        Body b = c.soi;
        Orbit o = f.orbit();
        if (!o.valid) { c.throttle = 0; return; }

        f.steer(f.prograde(), dt);

        double target = o.closed() ? o.apoapsis() : Math.hypot(c.x, c.y);
        double pe = o.periapsis();
        double r = Math.hypot(c.x, c.y);

        // how far round to apoapsis, in true anomaly
        double toAp = Math.PI - Math.abs(o.nu);
        boolean nearAp = Math.abs(toAp) < 0.22 || !o.closed();

        if (pe >= target * 0.985) {
            c.throttle = 0;
            status = "ORBIT ESTABLISHED";
            disengage();
            return;
        }

        if (nearAp || r > target * 0.985) {
            c.throttle = 1;
            status = "CIRCULARISING  PE " + Art.dist(pe - b.radius);
        } else {
            c.throttle = 0;
            status = "COASTING TO APOAPSIS";
        }

        if (c.fuelInStage(c.stage) <= 0 && c.stage <= c.maxStage()) c.doStage();
        if (c.totalFuel() <= 0) { status = "OUT OF FUEL"; disengage(); }
    }

    // =====================================================================
    // LANDING — the suicide burn
    // =====================================================================

    /**
     * Powered descent, braking as late as it safely can.
     *
     * The stopping distance under maximum deceleration is v²/2a. Start the
     * burn much earlier than that and you hover, wasting propellant holding
     * yourself up; start it later and you arrive at the surface still moving.
     * So the controller computes the distance it needs, adds a margin, and
     * waits — which is why this looks alarming and is in fact the cheapest way
     * down.
     */
    private void land(Flight f, double dt) {
        Craft c = f.c;
        Body b = c.soi;

        if (c.landed) { c.throttle = 0; status = "DOWN"; disengage(); return; }

        double alt = f.altitude();
        double vs = f.verticalSpeed();
        double sp = f.surfaceSpeed();

        // kill horizontal motion first, high up, where there is room
        double up = f.radialOut();
        double retro = f.prograde() + Math.PI;

        double thrustN = c.thrust(f.pressureRatio());
        double m = c.mass();
        double aMax = thrustN / m;
        double g = b.mu / (Math.hypot(c.x, c.y) * Math.hypot(c.x, c.y));
        double aNet = aMax - g;

        if (aNet <= 0.1) {
            c.throttle = 1;
            f.steer(retro, dt);
            status = "INSUFFICIENT THRUST";
            return;
        }

        double stopDist = (sp * sp) / (2 * aNet);
        double margin = Math.max(60, alt * 0.04);

        // deploy legs on the way down, once
        if (subPhase == 0 && alt < 4000) { c.toggleLegs(); subPhase = 1; }

        if (alt > 12000 && Math.abs(vs) < 5) {
            // still in orbit: brake retrograde to drop the periapsis
            f.steer(retro, dt);
            c.throttle = 1;
            status = "DEORBIT BURN";
            return;
        }

        f.steer(alt > 900 ? retro : up, dt);

        if (alt <= stopDist + margin) {
            // proportional, so it settles instead of oscillating
            double want = Math.min(1, (sp / Math.max(1, Math.sqrt(2 * aNet * Math.max(1, alt)))) );
            c.throttle = Math.max(0.15, Math.min(1, want * 1.15));
            status = "LANDING BURN  " + Art.speed(sp);
        } else {
            c.throttle = 0;
            status = "FALLING  " + Art.dist(alt) + "  BURN AT " + Art.dist(stopDist + margin);
        }

        // final touchdown: hold a slow descent
        if (alt < 60) {
            double wantVs = -2.0;
            double err = wantVs - vs;
            c.throttle = Math.max(0, Math.min(1, 0.5 + err * -0.25));
            f.steer(up, dt);
            status = "TOUCHDOWN";
        }

        if (c.totalFuel() <= 0) { status = "NO FUEL"; c.throttle = 0; }
    }

    // =====================================================================
    // BOOSTER RETURN
    // =====================================================================

    /**
     * Flies a spent first stage back to the pad: flip, boostback, entry burn,
     * landing burn.
     *
     * Only meaningful on a craft that still has an engine and propellant after
     * separation — which is the point. Recovering a booster is a design
     * decision made in the builder, by carrying the fuel to do it, and the
     * autopilot only flies what you built.
     */
    private void booster(Flight f, double dt) {
        Craft c = f.c;
        Body b = c.soi;
        double alt = f.altitude();
        double retro = f.prograde() + Math.PI;
        double up = f.radialOut();

        if (c.landed) { c.throttle = 0; status = "BOOSTER RECOVERED"; disengage(); return; }
        if (c.totalFuel() <= 0) { c.throttle = 0; status = "BOOSTER LOST"; return; }

        switch (subPhase) {
            case 0:
                // flip and cancel downrange velocity
                f.steer(retro, dt);
                c.throttle = angleClose(c.angle, retro, 0.25) ? 1 : 0;
                status = "BOOSTBACK";
                if (Math.abs(horizontalSpeed(f)) < 60 || alt < 30000) subPhase = 1;
                break;
            case 1:
                // fall, holding retrograde so the engine end goes first
                f.steer(retro, dt);
                c.throttle = 0;
                status = "COASTING  " + Art.dist(alt);
                if (b.hasAtmosphere() && alt < b.atmoHeight * 0.55 && f.surfaceSpeed() > 900) {
                    subPhase = 2;
                } else if (alt < 9000) subPhase = 3;
                break;
            case 2:
                // entry burn: bleed speed before the air gets thick
                f.steer(retro, dt);
                c.throttle = 0.7;
                status = "ENTRY BURN";
                if (f.surfaceSpeed() < 620 || alt < 9000) subPhase = 3;
                break;
            default:
                mode = LAND;      // hand the last part to the landing controller
                subPhase = 0;
                break;
        }
    }

    private double horizontalOf(Flight f) { return horizontalSpeed(f); }

    private double horizontalSpeed(Flight f) {
        double r = Math.hypot(f.c.x, f.c.y);
        if (r < 1) return 0;
        double ux = f.c.x / r, uy = f.c.y / r;
        double vr = f.c.vx * ux + f.c.vy * uy;
        return Math.sqrt(Math.max(0, f.c.vx * f.c.vx + f.c.vy * f.c.vy - vr * vr));
    }

    private boolean angleClose(double a, double b, double tol) {
        double d = ((b - a + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
        return Math.abs(d) < tol;
    }

    // =====================================================================
    // HOVER — hold altitude, for practising landings
    // =====================================================================

    private void hover(Flight f, double dt) {
        Craft c = f.c;
        f.steer(f.radialOut(), dt);
        double vs = f.verticalSpeed();
        double err = -vs;
        c.throttle = Math.max(0, Math.min(1, 0.5 + err * 0.18));
        status = "HOVER  " + Art.speed(vs);
        if (c.totalFuel() <= 0) { c.throttle = 0; disengage(); }
    }

    // =====================================================================
    // DOCKING
    // =====================================================================

    /**
     * Closes on a target port: null out relative velocity, approach on the
     * axis, then creep in.
     *
     * Docking is a rendezvous problem pretending to be a parking problem. The
     * hard part is not lining up, it is that every correction you make to your
     * position also changes your velocity, so the controller kills the
     * relative velocity first and only then allows itself to close distance.
     */
    private void dock(Flight f, double dt) {
        Craft c = f.c;
        if (dockTarget == null) { status = "NO TARGET"; disengage(); return; }

        double dx = dockTarget.x - c.x, dy = dockTarget.y - c.y;
        double d = Math.hypot(dx, dy);
        double rvx = dockTarget.vx - c.vx, rvy = dockTarget.vy - c.vy;
        double rv = Math.hypot(rvx, rvy);

        if (d < 3.0 && rv < 0.35) {
            status = "DOCKED";
            c.throttle = 0;
            c.vx = dockTarget.vx; c.vy = dockTarget.vy;
            disengage();
            return;
        }

        // desired closing speed: fast when far, crawling when close
        double wantClose = Math.min(12, Math.max(0.4, d * 0.06));
        double ux = dx / Math.max(1e-6, d), uy = dy / Math.max(1e-6, d);

        // velocity we want, minus the one we have, is the correction to make
        double wx = ux * wantClose - (c.vx - dockTarget.vx);
        double wy = uy * wantClose - (c.vy - dockTarget.vy);
        double wmag = Math.hypot(wx, wy);

        if (wmag > 0.02) {
            f.steer(Math.atan2(wy, wx), dt);
            boolean pointing = angleClose(c.angle, Math.atan2(wy, wx), 0.30);
            c.throttle = pointing ? Math.min(1, wmag * 0.25) : 0;
            c.rcsOn = true;
        } else {
            c.throttle = 0;
        }
        status = "DOCKING  " + Art.dist(d) + "  " + Art.speed(rv);
    }
}
