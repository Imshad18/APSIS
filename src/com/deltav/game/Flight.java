package com.deltav.game;

/**
 * The simulation step: gravity, thrust, drag, terrain, and sphere-of-influence
 * handovers.
 *
 * Two regimes, chosen automatically:
 *
 *   COASTING  — no thrust, no atmosphere, nowhere near the ground. The craft
 *               is on a conic and is advanced by solving Kepler's equation.
 *               Exact, O(1), and unaffected by step size, which is what makes
 *               100000x warp possible without the orbit decaying into nonsense.
 *
 *   POWERED   — thrust, drag, or anywhere near a surface. Forces are changing
 *               or there is something to hit, so the craft is integrated with
 *               velocity Verlet at a bounded step.
 *
 * Mixing the two is the whole trick. Integrating everything would make warp
 * ruinous; propagating everything would fly you through mountains, because a
 * conic has no idea the planet is solid.
 */
public class Flight {

    public Universe u;
    public Craft c;

    /** Warp factor. 1 is real time. */
    public double warp = 1;
    public static final double[] WARPS = {1, 2, 5, 10, 50, 100, 1000, 10000, 100000};

    public double gLoad;
    public double lastAccel;
    public String warning = "";

    /** Set when the craft leaves or enters a sphere of influence this step. */
    public String soiEvent;

    private final double[] out = new double[2];

    public Flight(Universe u, Craft c) { this.u = u; this.c = c; }

    // =====================================================================
    // READOUTS
    // =====================================================================

    public double altitude() {
        double r = Math.hypot(c.x, c.y);
        return r - c.soi.surfaceAt(Math.atan2(c.y, c.x));
    }

    public double altitudeMean() { return Math.hypot(c.x, c.y) - c.soi.radius; }

    public double speed() { return Math.hypot(c.vx, c.vy); }

    /** Speed relative to the rotating surface — the number that kills you. */
    public double surfaceSpeed() {
        // A landed craft is stationary ON the ground, whatever the ground is
        // doing around the axis. Reporting the surface rotation speed had the
        // launch pad reading 18 m/s before the engine was even lit.
        if (c.landed) return Math.abs(roverSpeed);
        double r = Math.hypot(c.x, c.y);
        if (r < 1e-6 || c.soi.rotPeriod <= 0) return speed();
        double w = 2 * Math.PI / c.soi.rotPeriod;
        double svx = -c.y * w, svy = c.x * w;
        return Math.hypot(c.vx - svx, c.vy - svy);
    }

    public double verticalSpeed() {
        double r = Math.hypot(c.x, c.y);
        if (r < 1e-6) return 0;
        return (c.x * c.vx + c.y * c.vy) / r;
    }

    public double pressureRatio() {
        if (!c.soi.hasAtmosphere()) return 0;
        double d = c.soi.density(Math.max(0, altitudeMean()));
        return Math.min(1, d / c.soi.atmoDensity);
    }

    public Orbit orbit() { return Orbit.from(c.x, c.y, c.vx, c.vy, c.soi.mu); }

    public double prograde() { return Math.atan2(c.vy, c.vx); }
    public double radialOut() { return Math.atan2(c.y, c.x); }

    // =====================================================================
    // STEP
    // =====================================================================

    public void step(double dt) {
        if (c.destroyed) return;

        boolean thrusting = c.throttle > 0.001 && c.thrust(pressureRatio()) > 0;
        boolean inAir = c.soi.hasAtmosphere() && altitudeMean() < c.soi.atmoHeight;

        // Anything close to the ground is integrated, never propagated. A
        // conic does not know the planet is solid: analytic propagation will
        // happily place the craft on the far side of a mountain it should have
        // hit, and a ballistic descent would pass clean through the surface
        // without a single collision test being run.
        double gr = Math.hypot(c.x, c.y);
        boolean nearGround = gr < c.soi.radius + Math.abs(c.soi.terrainAmp) + 3000;

        boolean coasting = !thrusting && !inAir && !c.landed && !nearGround;

        // Further out, cap the warp so a descent cannot be stepped over.
        if (coasting) {
            Orbit pre = orbit();
            boolean falling = (c.x * c.vx + c.y * c.vy) < 0;
            if (pre.valid && falling
                    && pre.periapsis() < c.soi.radius + Math.abs(c.soi.terrainAmp)) {
                warp = Math.min(warp, 10);
            }
        }

        double total = dt * warp;

        if (c.landed && !thrusting) {
            u.update(u.time + total);
            driveStep(Math.min(total, 0.1));
            stayLanded();
            checkSoi();
            updateAnimations(dt);
            return;
        }

        if (coasting) {
            coast(total);
        } else {
            integrateSpan(total);
            u.update(u.time + total);
        }
        checkSoi();
        updateAnimations(dt);
    }

    /** Integrates a whole interval in bounded sub-steps. */
    private void integrateSpan(double total) {
        double remaining = total;
        int guard = 0;
        while (remaining > 1e-9 && guard++ < 6000) {
            double h = Math.min(remaining, powerStep());
            integrate(h);
            remaining -= h;
            if (c.destroyed || c.landed) break;
        }
    }

    /**
     * Step size for powered flight.
     *
     * Bounded by how far the craft moves, not by wall-clock: at 10 km/s a
     * tenth of a second covers a kilometre, and anything that coarse turns
     * terrain collision into a matter of luck. Never skip more than 400 m.
     */
    private double powerStep() {
        double alt = Math.max(0, altitudeMean());
        double sp = Math.max(1, speed());
        double byMotion = Math.max(400, alt * 0.05) / sp;
        return Math.max(0.002, Math.min(0.05, byMotion));
    }

    /**
     * Free fall, advanced analytically.
     *
     * The conic is recomputed from the current state, propagated by the full
     * interval in one shot, and written back. Nothing accumulates, so a
     * circular orbit stays circular through hours of warp.
     */
    private void coast(double dt) {
        Orbit o = orbit();
        if (!o.valid) { u.update(u.time + dt); return; }

        double[] p = new double[2], v = new double[2];
        o.propagate(dt, p, v);

        // Last line of defence. If the conic put the craft underground, this
        // step jumped over the surface — throw it away and integrate instead,
        // so the collision test actually gets to run.
        double nr = Math.hypot(p[0], p[1]);
        if (nr < c.soi.surfaceAt(Math.atan2(p[1], p[0]))) {
            warp = 1;
            integrateSpan(Math.min(dt, 1.0));
            u.update(u.time + Math.min(dt, 1.0));
            return;
        }

        c.x = p[0]; c.y = p[1]; c.vx = v[0]; c.vy = v[1];
        u.update(u.time + dt);
        gLoad = 0;

        if (nr < c.soi.radius + (c.soi.hasAtmosphere() ? c.soi.atmoHeight : 6000)) {
            warp = Math.min(warp, 10);
        }
    }

    private void integrate(double h) {
        double pr = pressureRatio();

        accel(c.x, c.y, c.vx, c.vy, pr, out);
        double ax0 = out[0], ay0 = out[1];

        // velocity Verlet: position from the current acceleration, then
        // velocity from the average of old and new. Second order, and stable
        // under sustained thrust where plain Euler would gain energy.
        double nx = c.x + c.vx * h + 0.5 * ax0 * h * h;
        double ny = c.y + c.vy * h + 0.5 * ay0 * h * h;

        double pvx = c.vx + ax0 * h, pvy = c.vy + ay0 * h;
        accel(nx, ny, pvx, pvy, pr, out);
        double ax1 = out[0], ay1 = out[1];

        c.vx += 0.5 * (ax0 + ax1) * h;
        c.vy += 0.5 * (ay0 + ay1) * h;
        c.x = nx; c.y = ny;

        if (c.throttle > 0.001) {
            double flow = c.massFlow(pr) * c.throttle;
            if (flow > 0) c.consume(flow * h);
        }

        double am = Math.hypot(ax1, ay1);
        lastAccel = am;
        gLoad = am / 9.80665;
        if (gLoad > 12) warning = "STRUCTURAL LOAD";

        ground();
        heating(h);
    }

    /** Total acceleration at a state: gravity, thrust, drag. */
    private void accel(double px, double py, double pvx, double pvy,
                       double pr, double[] o) {
        double r2 = px * px + py * py;
        double r = Math.sqrt(r2);
        if (r < 1) { o[0] = 0; o[1] = 0; return; }

        double g = -c.soi.mu / (r2 * r);
        double ax = g * px, ay = g * py;

        double m = c.mass();

        if (c.throttle > 0.001) {
            double t = c.thrust(pr) * c.throttle;
            ax += Math.cos(c.angle) * t / m;
            ay += Math.sin(c.angle) * t / m;
        }

        if (c.soi.hasAtmosphere()) {
            double alt = r - c.soi.radius;
            double rho = c.soi.density(alt);
            if (rho > 0) {
                // Drag opposes motion through the AIR, and the air turns with
                // the planet. That is why launching with the rotation is
                // cheaper, and why the number that matters on re-entry is
                // surface-relative speed rather than orbital speed.
                double w = c.soi.rotPeriod > 0 ? 2 * Math.PI / c.soi.rotPeriod : 0;
                double avx = -py * w, avy = px * w;
                double rvx = pvx - avx, rvy = pvy - avy;
                double sp = Math.hypot(rvx, rvy);
                if (sp > 0.01) {
                    double f = 0.5 * rho * sp * sp * c.dragArea() * 0.4;
                    ax -= f * rvx / sp / m;
                    ay -= f * rvy / sp / m;
                }
            }
        }
        o[0] = ax; o[1] = ay;
    }

    // =====================================================================
    // GROUND
    // =====================================================================

    private void ground() {
        double r = Math.hypot(c.x, c.y);
        double ang = Math.atan2(c.y, c.x);
        double surf = c.soi.surfaceAt(ang);
        if (r > surf) return;

        if (c.soi.isHole()) { c.destroyed = true; warning = "LOST"; return; }

        double vs = Math.abs(surfaceSpeed());
        boolean legs = false;
        for (Craft.Unit un : c.units) {
            if (!un.detached && un.type.kind == Part.LEG && un.deployAnim > 0.5) legs = true;
        }
        double limit = legs ? 12.0 : 6.0;

        if (vs > limit) {
            c.destroyed = true;
            warning = "IMPACT AT " + Math.round(vs) + " M/S";
            return;
        }

        c.landed = true;
        c.landedAngle = ang;
        c.x = Math.cos(ang) * surf;
        c.y = Math.sin(ang) * surf;
        c.vx = 0; c.vy = 0;
        c.throttle = 0;
        warning = "";
    }

    // =====================================================================
    // SURFACE DRIVING
    // =====================================================================

    /** Metres driven this mission, for the survey contract. */
    public double driven;

    /** -1, 0 or 1. Set by the rover controls while landed. */
    public int drive;

    /**
     * Drives a landed craft along the surface.
     *
     * Motion is in SURFACE ANGLE, not in metres, which is what keeps a rover
     * on the ground: converting a velocity into a new position and then
     * re-projecting onto the terrain would have it climbing out of hollows and
     * sinking into peaks as the height changed under it. Moving along the
     * angle and reading the height there means the wheels stay on the surface
     * whatever the terrain does.
     *
     * Slope costs speed. A rover crawls uphill and runs away from you
     * downhill, which is the whole character of driving one.
     */
    private void driveStep(double dt) {
        if (drive == 0 || !c.has(Part.WHEEL)) { roverSpeed *= Math.max(0, 1 - dt * 2.2); return; }

        Body b = c.soi;
        double g = b.surfaceG();

        // local slope: how fast the surface height changes with angle
        double eps = 1e-4;
        double h0 = b.surfaceAt(c.landedAngle - eps);
        double h1 = b.surfaceAt(c.landedAngle + eps);
        double alongMetres = 2 * eps * b.radius;
        double slope = alongMetres > 0 ? (h1 - h0) / alongMetres : 0;

        int wheels = c.count(Part.WHEEL);
        double power = wheels * 900.0;                 // newtons of tractive effort
        double m = c.mass();
        double accel = power / m - slope * g * drive;

        roverSpeed += accel * drive * dt;
        // rolling resistance and a top speed that depends on how many wheels
        double top = 6 + wheels * 3.5;
        roverSpeed -= roverSpeed * dt * 0.55;
        roverSpeed = Math.max(-top, Math.min(top, roverSpeed));

        double dAngle = roverSpeed * dt / Math.max(1, b.radius);
        c.landedAngle += dAngle;
        driven += Math.abs(roverSpeed * dt);

        // A rover that hits a steep enough drop leaves the ground.
        if (Math.abs(roverSpeed) > top * 0.92 && slope < -0.35) {
            unland();
            c.vx += Math.cos(c.landedAngle + Math.PI / 2) * roverSpeed;
            c.vy += Math.sin(c.landedAngle + Math.PI / 2) * roverSpeed;
        }
    }

    public double roverSpeed;

    /** A landed craft rides the surface; the frame turns with the planet. */
    private void stayLanded() {
        double s = c.soi.surfaceAt(c.landedAngle);
        c.x = Math.cos(c.landedAngle) * s;
        c.y = Math.sin(c.landedAngle) * s;
        c.vx = 0; c.vy = 0;
        gLoad = 1;
    }

    public void unland() {
        if (!c.landed) return;
        c.landed = false;
        double ang = c.landedAngle;
        double w = c.soi.rotPeriod > 0 ? 2 * Math.PI / c.soi.rotPeriod : 0;
        double s = c.soi.surfaceAt(ang) + 0.5;
        c.x = Math.cos(ang) * s;
        c.y = Math.sin(ang) * s;
        c.vx = -c.y * w; c.vy = c.x * w;
    }

    // =====================================================================
    // HEATING
    // =====================================================================

    private void heating(double h) {
        if (!c.soi.hasAtmosphere()) { c.heat = Math.max(0, c.heat - h * 0.2); return; }
        double rho = c.soi.density(Math.max(0, altitudeMean()));
        double v = surfaceSpeed();
        // Heat flux goes as density times velocity CUBED. That cube is why
        // re-entry is survivable at all: shed the speed high, where the air is
        // thin, and it works for you instead of against you.
        double q = rho * v * v * v * 1e-8;
        c.heat += (q - c.heat * 0.6) * h;
        c.heat = Math.max(0, Math.min(2, c.heat));
        if (c.heat > 1.0) warning = "HEAT SHIELD FAILING";
        if (c.heat > 1.6) { c.destroyed = true; warning = "BURNED UP"; }
    }

    // =====================================================================
    // SPHERE OF INFLUENCE
    // =====================================================================

    private void checkSoi() {
        soiEvent = null;
        double wx = c.soi.x + c.x, wy = c.soi.y + c.y;

        if (c.soi.parent != null) {
            double r = Math.hypot(c.x, c.y);
            if (r > c.soi.soi) {
                Body p = c.soi.parent;
                double vwx = c.soi.vx + c.vx, vwy = c.soi.vy + c.vy;
                c.x = wx - p.x; c.y = wy - p.y;
                c.vx = vwx - p.vx; c.vy = vwy - p.vy;
                c.soi = p;
                c.landed = false;
                soiEvent = "ESCAPED TO " + p.name;
                warp = Math.min(warp, 100);
                return;
            }
        }
        for (Body b : u.bodies) {
            if (b.parent != c.soi) continue;
            double dx = wx - b.x, dy = wy - b.y;
            if (dx * dx + dy * dy < b.soi * b.soi) {
                double vwx = c.soi.vx + c.vx, vwy = c.soi.vy + c.vy;
                c.x = dx; c.y = dy;
                c.vx = vwx - b.vx; c.vy = vwy - b.vy;
                c.soi = b;
                soiEvent = "ENTERED " + b.name;
                warp = Math.min(warp, 100);
                return;
            }
        }
    }

    private void updateAnimations(double dt) {
        for (Craft.Unit un : c.units) {
            double target = un.deployed ? 1 : 0;
            if (un.type.kind == Part.CHUTE && un.deployed) {
                // A chute only inflates where there is air to inflate it.
                double rho = c.soi.density(Math.max(0, altitudeMean()));
                target = rho > 0.002 ? 1 : 0;
            }
            un.deployAnim += (target - un.deployAnim) * Math.min(1, dt * 2.5);
        }
        double am = altitudeMean();
        if (am > c.maxAlt) c.maxAlt = am;
        double ss = surfaceSpeed();
        if (ss > c.maxSpeed) c.maxSpeed = ss;
    }

    /** Points the craft. SAS turns it faster and holds it steadier. */
    public void steer(double target, double dt) {
        double d = target - c.angle;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        double rate = c.sasOn ? 2.6 : 1.4;
        c.angle += Math.max(-rate * dt, Math.min(rate * dt, d * 4 * dt));
        c.angle = ((c.angle % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
    }
}
