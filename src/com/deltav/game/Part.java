package com.deltav.game;

/**
 * A rocket part. Definitions are static; a craft holds instances.
 *
 * Every number here is in real units — kilograms, newtons, seconds — because
 * the moment they are not, the delta-v readout stops being the thing the
 * player can actually trust, and delta-v is the entire currency of the game.
 */
public class Part {

    public static final int G_POD = 0, G_TANK = 1, G_ENGINE = 2, G_STRUCT = 3,
            G_LAND = 4, G_UTIL = 5, G_STATION = 6, G_BOOSTER = 7;
    public static final String[] GROUPS =
            {"PODS", "TANKS", "ENGINES", "BOOSTERS", "STRUCTURE",
             "LANDING", "UTILITY", "STATION"};

    public static final int POD = 0, TANK = 1, ENGINE = 2, DECOUPLER = 3,
            CHUTE = 4, LEG = 5, DOCK = 6, WHEEL = 7, PANEL = 8, SCIENCE = 9,
            RCS = 10, NOSE = 11, FIN = 12, STRUT = 13;

    public String id, name;
    /** Which drawer it lives in. */
    public int group;
    /** Mounts on the SIDE of the stack rather than in it. */
    public boolean radial;
    public int kind;
    public double dryMass;        // kg
    public double fuel;           // kg of propellant carried
    public double thrust;         // N at vacuum
    public double isp;            // s, vacuum
    public double ispSea;         // s, sea level
    public double width, height;  // metres, for drawing and stacking
    public double drag;           // coefficient contribution
    public int cost;
    public int techLevel;         // tech tree node that unlocks it
    public double chuteArea;      // m^2 when deployed
    public double crew;
    public String blurb;

    public static final Part[] ALL = {
        // --- pods ---------------------------------------------------------
        p("pod1", "MK1 CAPSULE", POD, 780, 0, 0, 0, 0, 1.25, 1.2, 0.2, 300, 0, 1,
          "One seat. No frills. It comes home if you are lucky."),
        p("pod2", "MK2 CAPSULE", POD, 1250, 0, 0, 0, 0, 1.9, 1.6, 0.2, 900, 3, 2,
          "Two seats, reaction wheels, and a heat shield worth the name."),
        p("probe", "PROBE CORE", POD, 90, 0, 0, 0, 0, 0.7, 0.4, 0.1, 200, 1, 0,
          "No crew, no return needed. The cheapest way to learn something."),
        p("lander", "LANDER CAN", POD, 1400, 0, 0, 0, 0, 2.4, 1.4, 0.25, 1400, 6, 2,
          "Wide, low, and built to sit down on something."),

        // --- tanks --------------------------------------------------------
        p("tankS", "FUEL TANK S", TANK, 120, 900, 0, 0, 0, 1.25, 1.6, 0.15, 220, 0, 0,
          "900 kg of propellant."),
        p("tankM", "FUEL TANK M", TANK, 260, 2200, 0, 0, 0, 1.9, 2.6, 0.18, 500, 2, 0,
          "2.2 tonnes. The workhorse."),
        p("tankL", "FUEL TANK L", TANK, 640, 6400, 0, 0, 0, 2.6, 4.2, 0.22, 1300, 5, 0,
          "6.4 tonnes. Heavy, and worth it."),
        p("tankXL","FUEL TANK XL", TANK,1500,16000, 0, 0, 0, 3.6, 6.5, 0.28, 3400, 9, 0,
          "16 tonnes. This is how you leave the system."),

        // --- engines ------------------------------------------------------
        p("eng1", "TD-4 ENGINE", ENGINE, 340, 0, 62000, 290, 250, 1.25, 1.1, 0.1, 400, 0, 0,
          "Sea-level workhorse. Loud, thirsty, dependable."),
        p("eng2", "TD-9 ENGINE", ENGINE, 720, 0, 178000, 305, 265, 1.9, 1.6, 0.12, 1100, 3, 0,
          "The one that gets a real stack off the pad."),
        p("engV", "VAC-1 ENGINE", ENGINE, 280, 0, 44000, 355, 120, 1.25, 1.5, 0.1, 1300, 5, 0,
          "Terrible in air, superb once you are out of it."),
        p("engH", "HEAVY LIFTER", ENGINE, 1900, 0, 640000, 300, 280, 3.6, 2.4, 0.15, 4200, 8, 0,
          "Six hundred kilonewtons. Hold on."),
        p("engI", "ION DRIVE", ENGINE, 160, 0, 900, 3800, 400, 0.8, 0.8, 0.05, 5000, 12, 0,
          "Almost no thrust and almost no fuel use. Patience is the fuel."),

        // --- structure ----------------------------------------------------
        p("dec", "DECOUPLER", DECOUPLER, 60, 0, 0, 0, 0, 1.25, 0.35, 0.05, 80, 1, 0,
          "Explosive bolts. What makes a stage a stage."),
        p("nose", "NOSE CONE", NOSE, 45, 0, 0, 0, 0, 1.25, 1.0, -0.35, 90, 2, 0,
          "Cuts drag. Matters more than you think below 20 km."),
        p("fin", "AERO FIN", FIN, 80, 0, 0, 0, 0, 0.9, 0.9, 0.06, 120, 2, 0,
          "Puts the centre of pressure behind the centre of mass. Keeps you pointed."),
        p("strut", "STRUCTURAL RING", STRUT, 90, 0, 0, 0, 0, 1.9, 0.4, 0.04, 60, 4, 0,
          "Adapts one diameter to another."),

        // --- landing ------------------------------------------------------
        p("chute", "PARACHUTE", CHUTE, 110, 0, 0, 0, 0, 1.0, 0.5, 0.05, 260, 1, 0,
          "Opens below 12 km. Slows you to something survivable."),
        p("chuteL","DROGUE CHUTE", CHUTE, 180, 0, 0, 0, 0, 1.3, 0.6, 0.05, 420, 4, 0,
          "Deploys higher and faster. Use it before the mains."),
        p("leg", "LANDING LEG", LEG, 130, 0, 0, 0, 0, 0.6, 1.1, 0.03, 180, 3, 0,
          "Four of these and a soft touch and you walk away."),
        p("wheel", "ROVER WHEEL", WHEEL, 95, 0, 0, 0, 0, 0.8, 0.8, 0.03, 240, 7, 0,
          "Motorised. Drive it once you are down."),

        // --- utility ------------------------------------------------------
        p("dock", "DOCKING PORT", DOCK, 70, 0, 0, 0, 0, 1.25, 0.3, 0.04, 340, 6, 0,
          "Magnetic capture within half a metre and a quarter of a metre a second."),
        p("panel", "SOLAR PANEL", PANEL, 55, 0, 0, 0, 0, 1.4, 0.3, 0.04, 300, 4, 0,
          "Power for the instruments, out as far as Frost."),
        p("rcs", "RCS BLOCK", RCS, 45, 60, 2400, 240, 220, 0.5, 0.5, 0.03, 260, 5, 0,
          "Translation thrust. Docking is impossible without it."),
        p("sci1", "BAROMETER", SCIENCE, 40, 0, 0, 0, 0, 0.6, 0.4, 0.03, 150, 1, 0,
          "Reads pressure. Worth science in every atmosphere."),
        p("sci2", "SPECTROMETER", SCIENCE, 120, 0, 0, 0, 0, 0.9, 0.6, 0.04, 800, 6, 0,
          "Reads composition. Worth far more, everywhere."),

        // --- side boosters: mount on the flanks, drop away together --------
        p("boostS", "SIDE BOOSTER S", TANK, 340, 2600, 0, 0, 0, 1.0, 3.2, 0.12, 700, 4, 0,
          "Straps to the side. Lights with the core and falls away early."),
        p("boostL", "SIDE BOOSTER L", TANK, 900, 7800, 0, 0, 0, 1.4, 5.0, 0.16, 1900, 7, 0,
          "A great deal of thrust for the first forty seconds."),
        p("boostE", "BOOSTER MOTOR", ENGINE, 420, 0, 240000, 285, 265, 1.0, 1.2, 0.1, 900, 4, 0,
          "Fixed nozzle. Cheap, loud, and only useful low down."),

        // --- station modules ------------------------------------------------
        p("hab", "HABITAT MODULE", POD, 2100, 0, 0, 0, 0, 2.6, 3.4, 0.2, 3200, 10, 4,
          "Somewhere for a crew to live while the station is assembled."),
        p("truss", "TRUSS SECTION", STRUT, 260, 0, 0, 0, 0, 1.9, 2.2, 0.08, 700, 10, 0,
          "Spine for a station. Docking ports and panels bolt to it."),
        p("lab", "SCIENCE LAB", SCIENCE, 1600, 0, 0, 0, 0, 2.6, 2.8, 0.16, 4200, 11, 2,
          "Processes instrument data in orbit for far more science."),
        p("tankO", "ORBITAL TANK", TANK, 480, 3800, 0, 0, 0, 2.6, 2.4, 0.18, 1500, 10, 0,
          "Propellant depot module. Refuels anything docked to it."),
    };

    private static Part p(String id, String name, int kind, double dry, double fuel,
                          double thrust, double isp, double ispSea, double w, double h,
                          double drag, int cost, int tech, double crew, String blurb) {
        Part t = new Part();
        t.id = id; t.name = name; t.kind = kind;
        t.dryMass = dry; t.fuel = fuel; t.thrust = thrust;
        t.isp = isp; t.ispSea = ispSea;
        t.width = w; t.height = h; t.drag = drag;
        t.cost = cost; t.techLevel = tech; t.crew = crew; t.blurb = blurb;
        if (kind == CHUTE) t.chuteArea = id.equals("chute") ? 60 : 22;
        t.radial = id.startsWith("boost");
        t.group = groupFor(t);
        return t;
    }

    /** Which drawer a part belongs in. */
    private static int groupFor(Part t) {
        if (t.radial || t.id.equals("boostE")) return G_BOOSTER;
        if (t.id.equals("hab") || t.id.equals("truss") || t.id.equals("lab")
                || t.id.equals("tankO") || t.id.equals("dock")) return G_STATION;
        switch (t.kind) {
            case POD:       return G_POD;
            case TANK:      return G_TANK;
            case ENGINE:    return G_ENGINE;
            case DECOUPLER:
            case NOSE:
            case FIN:
            case STRUT:     return G_STRUCT;
            case CHUTE:
            case LEG:
            case WHEEL:     return G_LAND;
            default:        return G_UTIL;
        }
    }

    public static Part byId(String id) {
        for (Part t : ALL) if (t.id.equals(id)) return t;
        return null;
    }

    public double wetMass() { return dryMass + fuel; }

    /**
     * Specific impulse blended by ambient pressure.
     *
     * A bell optimised for vacuum is badly overexpanded at sea level and loses
     * a large slice of its efficiency; a sea-level bell wastes performance once
     * the air is gone. Interpolating on pressure ratio is what makes choosing
     * the right engine for the right stage an actual decision.
     */
    public double ispAt(double pressureRatio) {
        double r = Math.max(0, Math.min(1, pressureRatio));
        return isp + (ispSea - isp) * r;
    }

    public double thrustAt(double pressureRatio) {
        double i = ispAt(pressureRatio);
        return isp > 0 ? thrust * (i / isp) : thrust;
    }
}
