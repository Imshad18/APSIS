package com.deltav.game;

import java.util.ArrayList;
import java.util.List;

/**
 * A mission with an objective and a payment.
 *
 * Contracts exist to point the player at the next thing worth attempting.
 * Without them progression is "go further", which is a direction rather than a
 * goal, and it is very easy to grind suborbital hops forever without ever
 * being told that a Luna flyby is now within reach of what you can build.
 */
public class Contract {

    public static final int ALTITUDE = 0, ORBIT = 1, LAND = 2, FLYBY = 3,
            STATION = 4, RECOVER = 5, ROVER = 6;

    public int kind;
    public String title, detail;
    public String bodyName;
    public double param;         // altitude, distance driven, etc.
    public double reward, scienceReward;
    public boolean done, taken;
    public int requiresTier;

    public static List<Contract> catalogue() {
        List<Contract> out = new ArrayList<Contract>();

        out.add(c(ALTITUDE, "FIRST FLIGHT", "Reach 8 km altitude.",
                "TERRA", 8000, 900, 4, 0));
        out.add(c(ALTITUDE, "EDGE OF AIR", "Reach 70 km — above Terra's atmosphere.",
                "TERRA", 70000, 2600, 12, 1));
        out.add(c(ORBIT, "FIRST ORBIT", "Achieve a stable orbit of Terra.",
                "TERRA", 0, 6500, 30, 2));
        out.add(c(RECOVER, "SAFE RETURN", "Orbit Terra and land the crew safely.",
                "TERRA", 0, 9000, 40, 3));
        out.add(c(FLYBY, "LUNAR FLYBY", "Enter Luna's sphere of influence.",
                "LUNA", 0, 14000, 60, 4));
        out.add(c(LAND, "LUNAR LANDING", "Land a vessel on Luna.",
                "LUNA", 0, 26000, 110, 5));
        out.add(c(LAND, "VESPER DESCENT", "Land on Vesper, under its thin air.",
                "VESPER", 0, 34000, 150, 6));
        out.add(c(STATION, "ORBITAL OUTPOST", "Dock two vessels in Terra orbit.",
                "TERRA", 0, 42000, 190, 6));
        out.add(c(ROVER, "SURFACE SURVEY", "Drive 2 km on any surface.",
                "", 2000, 30000, 140, 7));
        out.add(c(LAND, "EMBER LANDING", "Land on Ember, closest to the star.",
                "EMBER", 0, 56000, 240, 8));
        out.add(c(FLYBY, "REACH FROST", "Enter Frost's sphere of influence.",
                "FROST", 0, 72000, 300, 8));
        out.add(c(LAND, "ICE MOON", "Land on Shard, Cinder or Thule.",
                "FROST", 0, 96000, 380, 9));
        out.add(c(FLYBY, "EVENT HORIZON", "Enter the sphere of influence of The Maw.",
                "THE MAW", 0, 180000, 700, 11));
        return out;
    }

    private static Contract c(int kind, String title, String detail, String body,
                              double param, double reward, double sci, int tier) {
        Contract k = new Contract();
        k.kind = kind; k.title = title; k.detail = detail;
        k.bodyName = body; k.param = param;
        k.reward = reward; k.scienceReward = sci; k.requiresTier = tier;
        return k;
    }

    /**
     * Tests the objective against the state of a flight.
     *
     * Checked continuously rather than at recovery, so a flyby counts the
     * moment you cross the boundary — losing the vehicle on the way home
     * should not erase the fact that you got there.
     */
    public boolean check(Craft c, Flight f, Fleet fleet, double driven) {
        if (done) return false;
        Body b = c.soi;
        switch (kind) {
            case ALTITUDE:
                if (c.maxAlt >= param && b.name.equals(bodyName)) return complete();
                break;
            case ORBIT: {
                if (!b.name.equals(bodyName)) break;
                Orbit o = f.orbit();
                double floor = b.radius + (b.hasAtmosphere() ? b.atmoHeight : 0);
                if (o.closed() && o.periapsis() > floor) return complete();
                break;
            }
            case LAND:
                if (c.landed && (bodyName.isEmpty() || matchesBody(b))) return complete();
                break;
            case FLYBY:
                if (matchesBody(b)) return complete();
                break;
            case STATION:
                if (fleet != null && fleet.stationCount() > 0
                        && c.count(Part.DOCK) >= 2) return complete();
                break;
            case RECOVER:
                if (c.landed && c.maxAlt > 70000 && c.crew() > 0
                        && b.name.equals(bodyName)) return complete();
                break;
            case ROVER:
                if (driven >= param) return complete();
                break;
        }
        return false;
    }

    /** FROST contracts are satisfied by Frost or any of its moons. */
    private boolean matchesBody(Body b) {
        if (b.name.equals(bodyName)) return true;
        return b.parent != null && b.parent.name.equals(bodyName);
    }

    private boolean complete() { done = true; return true; }
}
