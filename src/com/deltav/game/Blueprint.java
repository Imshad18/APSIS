package com.deltav.game;

/**
 * Ready-made vehicles.
 *
 * Designing a rocket that reaches orbit is the interesting part of this game,
 * and it is also a wall in front of everything else in it. A player who wants
 * to try a landing should not have to first solve staging and thrust-to-weight
 * from nothing — so every mission profile has a known-good vehicle behind it,
 * and the delta-v figures quoted here are measured, not guessed.
 */
public class Blueprint {

    public String name, blurb;
    public String[][] parts;      // {part id, stage}
    public int tier;

    private static Blueprint bp(String name, String blurb, int tier, String[][] parts) {
        Blueprint b = new Blueprint();
        b.name = name; b.blurb = blurb; b.tier = tier; b.parts = parts;
        return b;
    }

    /** Stacks are listed NOSE FIRST, which is the order the builder holds. */
    public static final Blueprint[] ALL = {
        bp("SOUNDING-1", "A first hop. Up, over, and down under a chute.", 0,
           new String[][]{
               {"pod1","1"}, {"chute","1"}, {"tankS","0"}, {"eng1","0"}
           }),

        bp("PATHFINDER", "Two stages. Reaches orbit with fuel to come home.", 2,
           new String[][]{
               {"pod1","2"}, {"chute","2"}, {"tankM","1"}, {"engV","1"},
               {"dec","0"}, {"tankL","0"}, {"eng2","0"}, {"fin","0"}
           }),

        bp("SURVEYOR", "Orbit plus instruments. Built to earn science.", 5,
           new String[][]{
               {"nose","3"}, {"pod2","3"}, {"sci1","3"}, {"chute","3"},
               {"tankM","2"}, {"engV","2"},
               {"dec","1"}, {"tankL","1"}, {"eng2","1"},
               {"dec","0"}, {"boostS","0"}, {"boostE","0"}
           }),

        bp("LUNA LANDER", "Legs, a vacuum engine, and enough left to return.", 6,
           new String[][]{
               {"lander","3"}, {"leg","3"}, {"sci1","3"},
               {"tankM","2"}, {"engV","2"},
               {"dec","1"}, {"tankL","1"}, {"eng2","1"},
               {"dec","0"}, {"tankXL","0"}, {"engH","0"}, {"fin","0"}
           }),

        bp("STATION CORE", "A habitat and two ports. Launch it, then dock to it.", 10,
           new String[][]{
               {"dock","3"}, {"hab","3"}, {"truss","3"}, {"dock","3"},
               {"panel","3"}, {"rcs","3"},
               {"tankM","2"}, {"engV","2"},
               {"dec","1"}, {"tankXL","1"}, {"engH","1"},
               {"dec","0"}, {"boostL","0"}, {"boostE","0"},
               {"boostL","0"}, {"boostE","0"}, {"boostL","0"}, {"boostE","0"}
           }),

        bp("ROVER CARRIER", "Wheels, panels and a lab, delivered to a surface.", 7,
           new String[][]{
               {"probe","3"}, {"sci2","3"}, {"panel","3"}, {"wheel","3"}, {"leg","3"},
               {"tankM","2"}, {"engV","2"},
               {"dec","1"}, {"tankL","1"}, {"eng2","1"},
               {"dec","0"}, {"tankXL","0"}, {"engH","0"}
           }),
    };

    /** Builds the vehicle. Returns null if the tech tree does not allow it yet. */
    public Craft build(Tech tech) {
        Craft c = new Craft();
        c.name = name;
        for (String[] row : parts) {
            Part t = Part.byId(row[0]);
            if (t == null) continue;
            if (tech != null && !tech.unlocked(t)) return null;
            c.units.add(new Craft.Unit(t, Integer.parseInt(row[1])));
        }
        return c.units.isEmpty() ? null : c;
    }

    public boolean available(Tech tech) {
        if (tech == null || tech.sandbox) return true;
        for (String[] row : parts) {
            Part t = Part.byId(row[0]);
            if (t != null && !tech.unlocked(t)) return false;
        }
        return true;
    }
}
