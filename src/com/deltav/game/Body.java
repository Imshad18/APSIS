package com.deltav.game;

/**
 * A celestial body: star, planet, moon, or black hole.
 *
 * Bodies are on rails. Their orbits never change, they are unaffected by the
 * player, and their position at any time is a closed-form function of that
 * time. That is a deliberate simplification and it buys the two things the
 * game cannot work without: time warp to any factor, and the ability to draw
 * an encounter prediction for a transfer that has not happened yet.
 */
public class Body {

    public static final int STAR = 0, ROCK = 1, GAS = 2, MOON = 3, HOLE = 4;

    public String name;
    public int kind;
    public Body parent;

    /** Physical. */
    public double mu;            // GM, m^3/s^2
    public double radius;        // surface, m
    public double soi;           // sphere of influence, m (infinite for the star)
    public double rotPeriod;     // seconds per rotation; drives surface velocity
    public double rot;           // current rotation angle

    /** Orbit around the parent. Zero for the star. */
    public double orbitRadius;   // circular, for simplicity and stability
    public double orbitPhase;    // where it starts
    public double orbitPeriod;

    /** Atmosphere. */
    public double atmoHeight;    // 0 for none
    public double atmoDensity;   // kg/m^3 at sea level
    public double atmoScale;     // scale height, m

    /** Look. */
    public int colour, colourDark, atmoColour;
    public double terrainAmp;    // metres of surface relief
    public long seed;

    /** Science value multiplier for landing or orbiting here. */
    public double science = 1.0;

    /** Cached world position, updated once per frame by Universe. */
    public double x, y, vx, vy;

    public boolean hasAtmosphere() { return atmoHeight > 0; }

    public boolean isHole() { return kind == HOLE; }

    /** Air density at an altitude above sea level. */
    public double density(double alt) {
        if (atmoHeight <= 0 || alt >= atmoHeight || alt < 0) return 0;
        return atmoDensity * Math.exp(-alt / atmoScale);
    }

    /** Surface gravity, for the readouts and for terminal-velocity sums. */
    public double surfaceG() { return mu / (radius * radius); }

    /** Speed a point on the equator is carried at by rotation. */
    public double surfaceSpeed() {
        if (rotPeriod <= 0) return 0;
        return 2 * Math.PI * radius / rotPeriod;
    }

    /** Circular orbit speed at a radius. */
    public double circularSpeed(double r) { return Math.sqrt(mu / r); }

    public double escapeSpeed(double r) { return Math.sqrt(2 * mu / r); }

    /**
     * Terrain height at a surface angle, in metres above the mean radius.
     *
     * Deterministic from the seed, so the same mountain is in the same place
     * every time you fly over it, and so a landed craft stays landed across a
     * save. Layered sine waves rather than real noise: three octaves is enough
     * to look like terrain at the scale you actually see it, and it is exactly
     * invertible, which matters because the collision test needs the height at
     * a point rather than a heightmap lookup.
     */
    public double terrain(double angle) {
        if (terrainAmp <= 0) return 0;
        double s = seed * 0.001;
        double h = 0;
        h += Math.sin(angle * 3.0 + s) * 0.55;
        h += Math.sin(angle * 7.3 + s * 2.7) * 0.28;
        h += Math.sin(angle * 17.1 + s * 5.1) * 0.12;
        h += Math.sin(angle * 41.0 + s * 9.3) * 0.05;
        return h * terrainAmp;
    }

    /** Radius of the solid surface below a given world angle. */
    public double surfaceAt(double angle) { return radius + terrain(angle); }

    /**
     * How far the body's gravity dominates its parent's.
     *
     * The standard sphere-of-influence radius. Outside it the game hands the
     * craft to the parent body; inside, the parent is ignored entirely. That
     * discontinuity is a lie — real gravity does not switch off — but it is
     * the lie that makes every orbit a perfect conic, and conics are what let
     * the map draw your entire future in one frame.
     */
    public void computeSoi() {
        if (parent == null || orbitRadius <= 0) { soi = Double.MAX_VALUE; return; }
        soi = orbitRadius * Math.pow(mu / parent.mu, 0.4);
    }

    /** Position at a given mission time, on rails. */
    public void updateAt(double t) {
        rot = rotPeriod > 0 ? (2 * Math.PI * t / rotPeriod) % (2 * Math.PI) : 0;
        if (parent == null || orbitRadius <= 0) { x = y = vx = vy = 0; return; }
        double n = 2 * Math.PI / orbitPeriod;
        double a = orbitPhase + n * t;
        x = parent.x + orbitRadius * Math.cos(a);
        y = parent.y + orbitRadius * Math.sin(a);
        double v = orbitRadius * n;
        vx = parent.vx - v * Math.sin(a);
        vy = parent.vy + v * Math.cos(a);
    }
}
