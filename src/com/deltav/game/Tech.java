package com.deltav.game;

/**
 * Progression: science, funds, and what is unlocked.
 *
 * Science comes from doing things that have not been done before — a new
 * altitude record, a first landing on a body, an instrument run somewhere new.
 * It is awarded for firsts rather than per flight, so grinding the same
 * suborbital hop does not pay, and the only way forward is outward.
 */
public class Tech {

    public static final String[] NODES = {
        "START", "SOUNDING ROCKETS", "STABILITY", "SURVIVABILITY",
        "HEAVY LIFT", "ORBITAL SYSTEMS", "LANDING SYSTEMS", "ROVERS",
        "DEEP SPACE", "ADVANCED PROPULSION", "STATIONS", "ELECTRIC PROPULSION",
        "INTERPLANETARY"
    };

    public static final int[] COST = {
        0, 8, 18, 34, 60, 95, 140, 200, 280, 380, 500, 660, 900
    };

    public int level = 0;
    public double science, funds = 12000;
    public boolean sandbox;

    /** Which bodies have been visited, orbited, landed on. */
    public final java.util.Set<String> flown = new java.util.HashSet<String>();

    public boolean unlocked(Part p) {
        return sandbox || p.techLevel <= level;
    }

    public boolean canAdvance() {
        return level + 1 < NODES.length && science >= COST[level + 1];
    }

    public boolean advance() {
        if (!canAdvance()) return false;
        level++;
        science -= COST[level];
        return true;
    }

    public String nextName() {
        return level + 1 < NODES.length ? NODES[level + 1] : "COMPLETE";
    }

    public double nextCost() {
        return level + 1 < COST.length ? COST[level + 1] : 0;
    }

    /**
     * Awards science for a flight, once per achievement.
     *
     * Returns a human-readable list of what was earned, because a number going
     * up on its own tells the player nothing about which part of the flight
     * was the part worth repeating.
     */
    public String debrief(Craft c, Flight f, Body body, boolean landed, boolean recovered) {
        StringBuilder sb = new StringBuilder();
        double gained = 0;

        gained += first("alt10k", c.maxAlt > 10000, 3, "10 km altitude", sb);
        gained += first("alt70k", c.maxAlt > 70000, 6, "left the atmosphere", sb);
        gained += first("orbit-" + body.name, !landed && orbitStable(f), 12 * body.science,
                "orbited " + body.name, sb);
        gained += first("land-" + body.name, landed, 20 * body.science,
                "landed on " + body.name, sb);
        gained += first("visit-" + body.name, true, 5 * body.science,
                "reached " + body.name, sb);

        if (c.has(Part.SCIENCE)) {
            gained += first("sci-" + body.name + (landed ? "-surf" : "-orb"), true,
                    9 * body.science, "instrument data at " + body.name, sb);
        }
        if (recovered) {
            double refund = c.cost() * 0.55;
            funds += refund;
            sb.append("recovered vehicle: +").append(Math.round(refund)).append(" funds\n");
        }
        science += gained;
        funds += gained * 22;
        if (gained > 0) sb.append("\nSCIENCE +").append(Math.round(gained));
        else if (sb.length() == 0) sb.append("Nothing new this flight.");
        return sb.toString();
    }

    private boolean orbitStable(Flight f) {
        Orbit o = f.orbit();
        return o.closed() && o.periapsis() > f.c.soi.radius
                + (f.c.soi.hasAtmosphere() ? f.c.soi.atmoHeight : 0);
    }

    private double first(String key, boolean done, double value, String label, StringBuilder sb) {
        if (!done || flown.contains(key)) return 0;
        flown.add(key);
        sb.append(label).append(": +").append(Math.round(value)).append("\n");
        return value;
    }
}
