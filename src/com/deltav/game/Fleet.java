package com.deltav.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Every vessel that exists, not just the one being flown.
 *
 * A station is only interesting if it is still there when you come back with
 * the next module, so vessels persist and keep moving while you are elsewhere.
 * They are advanced analytically rather than simulated: a craft in a stable
 * orbit is a conic, and a conic can be moved forward ten hours in one
 * evaluation. Anything landed simply stays landed.
 */
public class Fleet {

    public final List<Craft> vessels = new ArrayList<Craft>();
    public Craft active;

    public void add(Craft c) {
        if (!vessels.contains(c)) vessels.add(c);
    }

    public void remove(Craft c) { vessels.remove(c); }

    /**
     * Advances every inactive vessel to the current mission time.
     *
     * Only orbits are propagated. A vessel under thrust is by definition the
     * one you are flying, and a landed one does not move relative to its
     * surface, so there is nothing else that needs stepping.
     */
    public void advance(Universe u, double dt) {
        double[] p = new double[2], v = new double[2];
        for (Craft c : vessels) {
            if (c == active || c.destroyed) continue;
            if (c.landed) {
                double s = c.soi.surfaceAt(c.landedAngle);
                c.x = Math.cos(c.landedAngle) * s;
                c.y = Math.sin(c.landedAngle) * s;
                c.vx = 0; c.vy = 0;
                continue;
            }
            Orbit o = Orbit.from(c.x, c.y, c.vx, c.vy, c.soi.mu);
            if (!o.valid) continue;
            o.propagate(dt, p, v);
            c.x = p[0]; c.y = p[1]; c.vx = v[0]; c.vy = v[1];
        }
    }

    /** Vessels within a body's sphere of influence, nearest first. */
    public List<Craft> near(Craft me, double maxRange) {
        List<Craft> out = new ArrayList<Craft>();
        for (Craft c : vessels) {
            if (c == me || c.destroyed || c.soi != me.soi) continue;
            double d = Math.hypot(c.x - me.x, c.y - me.y);
            if (d <= maxRange) out.add(c);
        }
        // insertion sort by distance; the list is never long
        for (int i = 1; i < out.size(); i++) {
            Craft k = out.get(i);
            double dk = Math.hypot(k.x - me.x, k.y - me.y);
            int j = i - 1;
            while (j >= 0 && Math.hypot(out.get(j).x - me.x, out.get(j).y - me.y) > dk) {
                out.set(j + 1, out.get(j)); j--;
            }
            out.set(j + 1, k);
        }
        return out;
    }

    /**
     * Joins two vessels into one.
     *
     * The docked result keeps the target's state vector rather than averaging
     * them, because the target is the thing already in a known orbit and the
     * arriving craft is the one that just spent fuel matching it. Averaging
     * would nudge a station's orbit every time something docked.
     */
    public Craft dock(Craft arriving, Craft target) {
        for (Craft.Unit u : arriving.units) {
            if (u.detached) continue;
            Craft.Unit copy = new Craft.Unit(u.type, target.maxStage() + 1);
            copy.fuel = u.fuel;
            copy.deployed = u.deployed;
            copy.deployAnim = u.deployAnim;
            target.units.add(copy);
        }
        remove(arriving);
        target.name = target.name + "+";
        return target;
    }

    public int stationCount() {
        int n = 0;
        for (Craft c : vessels) {
            if (!c.destroyed && !c.landed && c.count(Part.DOCK) > 0) n++;
        }
        return n;
    }

    /** A short label for the switch list. */
    public static String describe(Craft c, Flight f) {
        String where = c.soi == null ? "?" : c.soi.name;
        if (c.landed) return c.name + " · landed on " + where;
        double alt = Math.hypot(c.x, c.y) - (c.soi == null ? 0 : c.soi.radius);
        return c.name + " · " + Art.dist(alt) + " over " + where;
    }
}
