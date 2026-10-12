package com.deltav.game;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Persistence: tech state and the last vehicle, as flat text.
 *
 * Text rather than serialization so a save survives a class changing shape.
 * A game that loses your tech tree because a field was added is worse than
 * one with no save at all.
 */
public class Save {

    private static File f(Context c) { return new File(c.getFilesDir(), "deltav.sav"); }

    public static void write(Context ctx, Tech t, Craft design) {
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new FileWriter(f(ctx)));
            w.write("v1\n");
            w.write("level " + t.level + "\n");
            w.write("science " + t.science + "\n");
            w.write("funds " + t.funds + "\n");
            w.write("sandbox " + (t.sandbox ? 1 : 0) + "\n");
            for (String s : t.flown) w.write("flown " + s + "\n");
            if (design != null) {
                w.write("craft " + design.name + "\n");
                for (Craft.Unit u : design.units) {
                    w.write("part " + u.type.id + " " + u.stage + "\n");
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) {}
        }
    }

    public static Tech readTech(Context ctx) {
        Tech t = new Tech();
        BufferedReader r = null;
        try {
            File file = f(ctx);
            if (!file.exists()) return t;
            r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(" ", 2);
                if (p.length < 2) continue;
                if (p[0].equals("level")) t.level = Integer.parseInt(p[1].trim());
                else if (p[0].equals("science")) t.science = Double.parseDouble(p[1].trim());
                else if (p[0].equals("funds")) t.funds = Double.parseDouble(p[1].trim());
                else if (p[0].equals("sandbox")) t.sandbox = p[1].trim().equals("1");
                else if (p[0].equals("flown")) t.flown.add(p[1].trim());
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return t;
    }

    public static Craft readCraft(Context ctx) {
        Craft c = new Craft();
        BufferedReader r = null;
        try {
            File file = f(ctx);
            if (!file.exists()) return null;
            r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(" ");
                if (p[0].equals("craft") && p.length > 1) c.name = p[1];
                else if (p[0].equals("part") && p.length > 2) {
                    Part t = Part.byId(p[1]);
                    if (t != null) c.units.add(new Craft.Unit(t, Integer.parseInt(p[2])));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return c.units.isEmpty() ? null : c;
    }
}
