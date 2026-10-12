package com.deltav.game;

/**
 * A Keplerian orbit, derived from a state vector.
 *
 * The whole game rests on this class, so it is worth saying why it exists at
 * all rather than just integrating forces every frame.
 *
 * Numerical integration gives you where the ship is NOW. It cannot tell you
 * where the ship will be in forty minutes without running forty minutes of
 * simulation, and it accumulates error until a stable orbit slowly spirals.
 * A conic section gives you the entire future path in closed form: the shape,
 * the periapsis, the apoapsis, the period, and the position at any time — all
 * from six numbers, exactly, instantly, at any zoom level.
 *
 * So the game does both. Under thrust or inside an atmosphere the ship is
 * integrated, because forces are changing. In free fall it is propagated
 * analytically, which is exact, costs nothing, and is what makes 100000x time
 * warp possible at all.
 *
 * Two dimensions, so there is no inclination and no ascending node: an orbit
 * is (a, e, argument of periapsis, true anomaly) plus a direction of travel.
 */
public class Orbit {

    /** Standard gravitational parameter of the body being orbited, GM. */
    public double mu;

    public double a;        // semi-major axis, metres (negative for hyperbolae)
    public double e;        // eccentricity: 0 circle, <1 ellipse, >=1 escape
    public double argP;     // argument of periapsis, radians from +x
    public double nu;       // true anomaly, radians from periapsis
    public boolean retrograde;   // clockwise when true

    /** Specific orbital energy and angular momentum, kept for diagnostics. */
    public double energy, h;

    public boolean valid;

    // =====================================================================
    // FROM STATE VECTOR
    // =====================================================================

    /**
     * Builds the orbit from position and velocity relative to the body.
     *
     * This is the only way an Orbit is ever created. Everything the flight
     * computer displays — apoapsis, periapsis, period, time to node — is read
     * off the result, so getting this right once means every readout in the
     * game is right for free.
     */
    public static Orbit from(double rx, double ry, double vx, double vy, double mu) {
        Orbit o = new Orbit();
        o.mu = mu;

        double r = Math.sqrt(rx * rx + ry * ry);
        double v2 = vx * vx + vy * vy;

        if (r < 1e-6 || mu <= 0) { o.valid = false; return o; }

        // Specific angular momentum. In 2D the cross product is a scalar, and
        // its sign is the direction of travel — the only place "which way
        // round" is recorded.
        o.h = rx * vy - ry * vx;
        o.retrograde = o.h < 0;

        // vis-viva rearranged: energy fixes the semi-major axis
        o.energy = v2 / 2.0 - mu / r;
        o.a = -mu / (2.0 * o.energy);

        // Eccentricity vector points at periapsis, which gives e and argP in
        // one step and stays well-behaved as e approaches zero.
        double ex = (v2 / mu - 1.0 / r) * rx - (rx * vx + ry * vy) / mu * vx;
        double ey = (v2 / mu - 1.0 / r) * ry - (rx * vx + ry * vy) / mu * vy;
        o.e = Math.sqrt(ex * ex + ey * ey);

        if (o.e < 1e-9) {
            // A circle has no periapsis; pick the current position as the
            // reference so true anomaly stays continuous instead of jumping.
            o.argP = Math.atan2(ry, rx);
            o.nu = 0;
        } else {
            o.argP = Math.atan2(ey, ex);
            // True anomaly by rotating the position into the perifocal frame,
            // NOT by acos of the dot product. acos throws away half the
            // available precision as its argument approaches plus or minus
            // one — which is exactly at periapsis and apoapsis, the two places
            // the whole game points a marker at. atan2 on the rotated
            // components has no such cliff and needs no sign repair.
            double cw = Math.cos(o.argP), sw = Math.sin(o.argP);
            double xp =  rx * cw + ry * sw;
            double yp = -rx * sw + ry * cw;
            if (o.retrograde) yp = -yp;
            o.nu = Math.atan2(yp, xp);
        }
        o.valid = true;
        return o;
    }

    // =====================================================================
    // READOUTS
    // =====================================================================

    public boolean closed() { return valid && e < 1.0 && a > 0; }

    public double periapsis() { return a * (1 - e); }

    /** Infinite for an escape trajectory — callers must check {@link #closed}. */
    public double apoapsis() { return closed() ? a * (1 + e) : Double.POSITIVE_INFINITY; }

    public double period() {
        if (!closed()) return Double.POSITIVE_INFINITY;
        return 2 * Math.PI * Math.sqrt(a * a * a / mu);
    }

    /** Distance from the focus at a given true anomaly. */
    public double radiusAt(double trueAnomaly) {
        double denom = 1 + e * Math.cos(trueAnomaly);
        if (Math.abs(denom) < 1e-12) return Double.POSITIVE_INFINITY;
        return a * (1 - e * e) / denom;
    }

    /** World position at a true anomaly, relative to the body. */
    public void pointAt(double trueAnomaly, double[] out) {
        double r = radiusAt(trueAnomaly);
        double ang = argP + (retrograde ? -trueAnomaly : trueAnomaly);
        out[0] = r * Math.cos(ang);
        out[1] = r * Math.sin(ang);
    }

    /**
     * True anomaly at which the orbit crosses a given radius, or NaN.
     * Used for atmospheric entry markers and impact prediction.
     */
    public double anomalyAtRadius(double r) {
        if (!valid || r <= 0) return Double.NaN;
        double c = (a * (1 - e * e) / r - 1) / (e == 0 ? 1e-12 : e);
        if (c < -1 || c > 1) return Double.NaN;
        return Math.acos(c);
    }

    // =====================================================================
    // PROPAGATION
    // =====================================================================

    /**
     * Advances the orbit by dt seconds and writes the new state vector.
     *
     * Kepler's equation, M = E - e·sin E, has no closed-form inverse, so it is
     * solved with Newton-Raphson. Six iterations is comfortably enough for the
     * near-circular and moderately eccentric orbits the game actually
     * produces; highly eccentric ones start from a better guess so they
     * converge in the same budget.
     */
    public void propagate(double dt, double[] outPos, double[] outVel) {
        if (!valid) return;

        if (closed()) {
            double n = Math.sqrt(mu / (a * a * a));            // mean motion
            double E0 = eccentricFromTrue(nu, e);
            double M = E0 - e * Math.sin(E0) + n * dt;
            M = ((M % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);

            double E = solveKepler(M, e);
            nu = trueFromEccentric(E, e);
            stateFrom(E, outPos, outVel);
        } else {
            // Hyperbolic: same idea with sinh, and a is negative.
            double n = Math.sqrt(mu / Math.abs(a * a * a));
            double H0 = hyperbolicFromTrue(nu, e);
            double M = e * Math.sinh(H0) - H0 + n * dt;
            double H = solveKeplerHyperbolic(M, e);
            nu = trueFromHyperbolic(H, e);
            stateFromHyperbolic(H, outPos, outVel);
        }
    }

    private static double solveKepler(double M, double e) {
        // Starting guess matters more than iteration count here. Plain M is a
        // poor start for anything but a near-circle: an eccentric orbit spends
        // most of its period near apoapsis where E crawls, and most of its
        // motion near periapsis where E races, so Newton can step past the
        // root and crawl back. M + e·sin M leans the guess the right way and
        // converges in three or four steps across the whole range.
        double E = M + e * Math.sin(M);
        if (e > 0.8) E = Math.PI;
        for (int i = 0; i < 30; i++) {
            double f = E - e * Math.sin(E) - M;
            double fp = 1 - e * Math.cos(E);
            if (Math.abs(fp) < 1e-14) break;
            double d = f / fp;
            E -= d;
            if (Math.abs(d) < 1e-14) break;
        }
        return E;
    }

    private static double solveKeplerHyperbolic(double M, double e) {
        double H = Math.abs(M) < 6 ? M / (e - 1) : Math.signum(M) * Math.log(2 * Math.abs(M) / e + 1.8);
        for (int i = 0; i < 40; i++) {
            double f = e * Math.sinh(H) - H - M;
            double fp = e * Math.cosh(H) - 1;
            double d = f / fp;
            H -= d;
            if (Math.abs(d) < 1e-14) break;
        }
        return H;
    }

    private static double eccentricFromTrue(double nu, double e) {
        double cosNu = Math.cos(nu), sinNu = Math.sin(nu);
        double denom = 1 + e * cosNu;
        return Math.atan2(Math.sqrt(1 - e * e) * sinNu / denom, (e + cosNu) / denom);
    }

    private static double trueFromEccentric(double E, double e) {
        return 2 * Math.atan2(Math.sqrt(1 + e) * Math.sin(E / 2),
                              Math.sqrt(1 - e) * Math.cos(E / 2));
    }

    private static double hyperbolicFromTrue(double nu, double e) {
        double t = Math.sqrt((e - 1) / (e + 1)) * Math.tan(nu / 2);
        return 2 * atanh(Math.max(-0.999999, Math.min(0.999999, t)));
    }

    private static double trueFromHyperbolic(double H, double e) {
        return 2 * Math.atan2(Math.sqrt(e + 1) * Math.sinh(H / 2),
                              Math.sqrt(e - 1) * Math.cosh(H / 2));
    }

    private static double atanh(double x) { return 0.5 * Math.log((1 + x) / (1 - x)); }

    /** Position and velocity from eccentric anomaly, rotated into world axes. */
    private void stateFrom(double E, double[] p, double[] v) {
        double cosE = Math.cos(E), sinE = Math.sin(E);
        double b = a * Math.sqrt(1 - e * e);

        // In the orbital frame, periapsis on +x
        double px = a * (cosE - e);
        double py = b * sinE;

        double r = a * (1 - e * cosE);
        double edot = Math.sqrt(mu / (a * a * a)) / (1 - e * cosE);
        double vx = -a * sinE * edot;
        double vy = b * cosE * edot;

        rotateOut(px, py, vx, vy, p, v);
    }

    private void stateFromHyperbolic(double H, double[] p, double[] v) {
        double coshH = Math.cosh(H), sinhH = Math.sinh(H);
        double b = -a * Math.sqrt(e * e - 1);

        double px = a * (coshH - e);      // a is negative here
        double py = b * sinhH;

        double hdot = Math.sqrt(mu / Math.abs(a * a * a)) / (e * coshH - 1);
        double vx = a * sinhH * hdot;
        double vy = b * coshH * hdot;

        rotateOut(px, py, vx, vy, p, v);
    }

    /** Rotates the perifocal frame into world coordinates, honouring direction. */
    private void rotateOut(double px, double py, double vx, double vy,
                           double[] p, double[] v) {
        if (retrograde) { py = -py; vy = -vy; }
        double c = Math.cos(argP), s = Math.sin(argP);
        p[0] = px * c - py * s;
        p[1] = px * s + py * c;
        v[0] = vx * c - vy * s;
        v[1] = vx * s + vy * c;
    }
}
