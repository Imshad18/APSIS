package com.deltav.game;

import java.util.ArrayList;
import java.util.List;

/**
 * The system, and the rules for moving between bodies.
 *
 * Scaled to roughly a tenth of reality. That is not laziness — at true scale a
 * transfer to another planet is months of time warp and a launch to orbit is
 * eight minutes of holding a throttle, and neither is a game. At a tenth,
 * orbit takes about ninety seconds, a moon transfer takes minutes, and every
 * orbital relationship stays exactly true.
 */
public class Universe {

    public final List<Body> bodies = new ArrayList<Body>();
    public Body star;
    public Body home;

    public double time;          // mission elapsed time, seconds

    public static Universe standard() {
        Universe u = new Universe();

        Body sol = body(u, "SOL", Body.STAR, null, 4.68e16, 5.2e7, 0, 0, 0);
        sol.colour = 0xFFFFE9A8; sol.colourDark = 0xFFFFC24A;
        sol.rotPeriod = 900000;
        sol.science = 0.2;
        u.star = sol;

        // ---- EMBER: scorched, airless, close in --------------------------
        Body ember = body(u, "EMBER", Body.ROCK, sol, 8.4e10, 6.4e4, 1.05e9, 0.9, 1.9e6);
        ember.colour = 0xFFB4553A; ember.colourDark = 0xFF5A2418;
        ember.terrainAmp = 1720; ember.seed = 771;
        ember.science = 4.0;

        // ---- TERRA: home ---------------------------------------------------
        Body terra = body(u, "TERRA", Body.ROCK, sol, 1.41e11, 1.2e5, 2.7e9, 2.3, 2.1e5);
        terra.colour = 0xFF3E7FA8; terra.colourDark = 0xFF12304A;
        terra.atmoHeight = 16000; terra.atmoDensity = 1.225; terra.atmoScale = 2100;
        terra.atmoColour = 0xFF5AA8E8;
        terra.terrainAmp = 800; terra.seed = 1337;
        terra.science = 1.0;
        u.home = terra;

        Body luna = body(u, "LUNA", Body.MOON, terra, 2.6e9, 4.0e4, 2.4e6, 1.1, 1.4e5);
        luna.colour = 0xFF9A9A94; luna.colourDark = 0xFF3A3A38;
        luna.terrainAmp = 1050; luna.seed = 42;
        luna.science = 3.0;

        Body vesper = body(u, "VESPER", Body.MOON, terra, 7.6e8, 2.6e4, 6.2e6, 4.4, 3.1e5);
        vesper.colour = 0xFFC8B48A; vesper.colourDark = 0xFF5A4E32;
        vesper.terrainAmp = 575; vesper.seed = 909;
        vesper.atmoHeight = 2500; vesper.atmoDensity = 0.08; vesper.atmoScale = 850;
        vesper.atmoColour = 0xFFD8B070;
        vesper.science = 5.0;

        // ---- FROST: outer, thin air, three moons ---------------------------
        Body frost = body(u, "FROST", Body.GAS, sol, 1.16e12, 2.8e5, 8.2e9, 3.9, 3.6e4);
        frost.colour = 0xFF6EA8C8; frost.colourDark = 0xFF1E4A66;
        frost.atmoHeight = 29000; frost.atmoDensity = 2.4; frost.atmoScale = 3000;
        frost.atmoColour = 0xFF8ED8FF;
        frost.science = 8.0;

        Body shard = body(u, "SHARD", Body.MOON, frost, 3.28e8, 1.8e4, 8.8e5, 0.4, 8.2e4);
        shard.colour = 0xFFD0D8E0; shard.colourDark = 0xFF5A6470;
        shard.terrainAmp = 460; shard.seed = 5150; shard.science = 9.0;

        Body cinder = body(u, "CINDER", Body.MOON, frost, 1.24e9, 3.2e4, 1.92e6, 2.6, 1.9e5);
        cinder.colour = 0xFFB4703A; cinder.colourDark = 0xFF4A2A12;
        cinder.terrainAmp = 1300; cinder.seed = 6060; cinder.science = 10.0;

        Body thule = body(u, "THULE", Body.MOON, frost, 5.6e8, 2.2e4, 4.4e6, 5.2, 4.4e5);
        thule.colour = 0xFF8AC0B0; thule.colourDark = 0xFF2E4A44;
        thule.terrainAmp = 690; thule.seed = 7070; thule.science = 12.0;

        // ---- THE MAW: the endgame -------------------------------------------
        Body maw = body(u, "THE MAW", Body.HOLE, sol, 3.55e13, 5.2e3, 2.8e10, 5.8, 0);
        maw.colour = 0xFF000000; maw.colourDark = 0xFFFF8A3C;
        maw.science = 40.0;

        for (Body b : u.bodies) b.computeSoi();
        u.update(0);
        return u;
    }

    private static Body body(Universe u, String name, int kind, Body parent,
                             double mu, double radius, double orbitR,
                             double phase, double rotPeriod) {
        Body b = new Body();
        b.name = name; b.kind = kind; b.parent = parent;
        b.mu = mu; b.radius = radius;
        b.orbitRadius = orbitR; b.orbitPhase = phase; b.rotPeriod = rotPeriod;
        if (parent != null && orbitR > 0) {
            b.orbitPeriod = 2 * Math.PI * Math.sqrt(orbitR * orbitR * orbitR / parent.mu);
        }
        u.bodies.add(b);
        return b;
    }

    /** Advances every body to a mission time. Order matters: parents first. */
    public void update(double t) {
        time = t;
        for (Body b : bodies) b.updateAt(t);
    }

    public Body byName(String n) {
        for (Body b : bodies) if (b.name.equals(n)) return b;
        return null;
    }

    /** Moons and planets orbiting a given body. */
    public List<Body> childrenOf(Body p) {
        List<Body> out = new ArrayList<Body>();
        for (Body b : bodies) if (b.parent == p) out.add(b);
        return out;
    }

    /**
     * Which body should be pulling on a craft at this world position.
     *
     * Walks down from the star: if the craft is inside a child's sphere of
     * influence, that child takes over, and so on. Deepest wins, which is what
     * makes a moon capture a ship out of a planetary orbit automatically.
     */
    public Body dominant(double wx, double wy) {
        Body cur = star;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Body b : bodies) {
                if (b.parent != cur) continue;
                double dx = wx - b.x, dy = wy - b.y;
                if (dx * dx + dy * dy < b.soi * b.soi) { cur = b; moved = true; break; }
            }
        }
        return cur;
    }
}
