package com.deltav.game;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.List;

/**
 * Everything in the world, drawn: stars, planets, orbits, the ship, its plume.
 *
 * Kept apart from the view so the flight screen and the map are the same
 * renderer at different zooms rather than two drawings that can disagree about
 * where the ship is.
 */
public class Scene {

    private final double[] pt = new double[2];
    private final Path path = new Path();

    // =====================================================================
    // BACKDROP
    // =====================================================================

    /**
     * Starfield with parallax.
     *
     * Positions come from a hash of the star's index, not from a stored array,
     * so a thousand stars cost nothing to keep and are identical every frame.
     * The three layers drift at different rates against the camera, which is
     * the only depth cue available when everything else is a flat disc.
     */
    /**
     * Starfield with parallax.
     *
     * The hash matters more than it looks. The first version multiplied the
     * index by a constant and took high bits with {@code >>> 31} — but for a
     * few hundred stars that product never exceeds about 7e11, so shifting off
     * 31 bits left values between 0 and 321. Every star ended up with almost
     * the same y, and the sky came out as horizontal streaks of dots instead
     * of a scatter. Small indices need a real avalanche, not a shift.
     */
    public void stars(Canvas c, Camera cam, int count) {
        int w = cam.w, h = cam.h;
        for (int layer = 0; layer < 3; layer++) {
            double par = 0.00004 * (layer + 1) * (layer + 1);
            float sz = Art.dp(0.45f + layer * 0.5f);
            int col = Art.alpha(0xFFFFFFFF, 0.26f + layer * 0.24f);
            int n = count / (layer + 1);
            for (int i = 0; i < n; i++) {
                long s = mix(i * 3266489917L + layer * 668265263L);
                double fx = frac(s);
                double fy = frac(mix(s));
                double x = fx * w - cam.cx * par;
                double y = fy * h + cam.cy * par;
                x = ((x % w) + w) % w;
                y = ((y % h) + h) % h;
                int cc = (mix(s + 7) & 31) == 0
                        ? Art.alpha(Art.CYAN, 0.5f + layer * 0.2f) : col;
                c.drawCircle((float) x, (float) y, sz, Art.fill(cc));
            }
        }
    }

    /** splitmix64 finalizer: avalanches even for tiny sequential inputs. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double frac(long z) {
        return ((z >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
    }

    // =====================================================================
    // BODIES
    // =====================================================================

    public void bodies(Canvas c, Camera cam, Universe u, Body skip) {
        for (Body b : u.bodies) {
            // The body we are standing on is drawn by surface() as a
            // landscape. Drawing its disc as well puts a 240,000 pixel circle
            // of flat colour straight over the horizon.
            if (b == skip) continue;
            double drawR = b.radius * (b.hasAtmosphere() ? 1.6 : 1.25);
            if (!cam.visible(b.x, b.y, Math.max(drawR, b.soi * 0.02))) continue;
            body(c, cam, b, u);
        }
    }

    private void body(Canvas c, Camera cam, Body b, Universe u) {
        float x = cam.sx(b.x), y = cam.sy(b.y);
        float r = cam.len(b.radius);

        if (b.isHole()) { hole(c, cam, b, x, y, r); return; }

        if (b.kind == Body.STAR) {
            Art.glow(c, x, y, Math.max(r * 6, Art.dp(60)), b.colour, 0.55f);
            Art.glow(c, x, y, Math.max(r * 2.2f, Art.dp(24)), 0xFFFFFFFF, 0.8f);
            c.drawCircle(x, y, Math.max(r, Art.dp(3)), Art.fill(0xFFFFF6D8));
            return;
        }

        // direction to the star, so the terminator faces the right way
        float lightAngle = (float) Math.atan2(-b.y, -b.x);
        if (b.parent == null) lightAngle = 0;

        float atmoFrac = b.hasAtmosphere() ? (float) (b.atmoHeight / b.radius) : 0;
        Art.sphere(c, x, y, Math.max(r, Art.dp(1.5f)),
                b.colour, b.colourDark, b.atmoColour, atmoFrac, lightAngle);

        // Terrain relief, only once it is big enough on screen to read. Below
        // that it is a smooth disc, because a jagged one-pixel edge just looks
        // like an artefact.
        if (r > Art.dp(70) && b.terrainAmp > 0) {
            path.reset();
            int steps = 220;
            for (int i = 0; i <= steps; i++) {
                double a = i * 2 * Math.PI / steps;
                double rr = b.surfaceAt(a - b.rot);
                float px = cam.sx(b.x + Math.cos(a) * rr);
                float py = cam.sy(b.y + Math.sin(a) * rr);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            c.drawPath(path, Art.fill(b.colourDark));
            c.drawPath(path, Art.stroke(1.2f, Art.mix(b.colour, 0xFFFFFFFF, 0.25f)));
        }

        if (r > Art.dp(4)) {
            Paint n = Art.text(7, Art.alpha(Art.INK_DIM, 0.9f), true);
            n.setLetterSpacing(0.24f);
            Art.textCenter(c, b.name, x, y - Math.max(r, Art.dp(6)) - Art.dp(8), n);
        }
    }

    /** The Maw: an accretion disc, a photon ring, and a hole in the sky. */
    private void hole(Canvas c, Camera cam, Body b, float x, float y, float r) {
        float rr = Math.max(r, Art.dp(2));
        for (int i = 6; i >= 1; i--) {
            float d = rr * (2.4f + i * 1.5f);
            c.drawCircle(x, y, d, Art.stroke(1.4f,
                    Art.alpha(Art.mix(Art.AMBER, Art.RED, i / 6f), 0.16f)));
        }
        Art.glow(c, x, y, rr * 7, Art.AMBER, 0.35f);
        c.drawCircle(x, y, rr * 1.5f, Art.stroke(2.2f, Art.alpha(0xFFFFE0A0, 0.95f)));
        c.drawCircle(x, y, rr, Art.fill(0xFF000000));
        Paint n = Art.text(7, Art.AMBER, true);
        n.setLetterSpacing(0.3f);
        Art.textCenter(c, b.name, x, y - rr * 2.4f - Art.dp(8), n);
    }

    // =====================================================================
    // ORBITS
    // =====================================================================

    /**
     * Draws a conic as a polyline, sampled in TRUE ANOMALY rather than in
     * time.
     *
     * Sampling in time bunches every point near apoapsis, where the craft
     * spends most of its period, and leaves the periapsis — the part you are
     * actually trying to read — as three coarse segments through the fastest
     * turn of the whole orbit. Sampling in angle puts the detail where the
     * curvature is.
     */
    public void orbit(Canvas c, Camera cam, Orbit o, Body around,
                      int colour, float widthDp, boolean dashed) {
        if (o == null || !o.valid) return;

        path.reset();
        boolean started = false;
        int steps = 220;

        double span, from;
        if (o.closed()) { from = 0; span = 2 * Math.PI; }
        else {
            // a hyperbola only exists between its asymptotes
            double lim = Math.acos(-1.0 / o.e) * 0.985;
            from = -lim; span = 2 * lim;
        }

        for (int i = 0; i <= steps; i++) {
            double nu = from + span * i / steps;
            double rr = o.radiusAt(nu);
            if (Double.isInfinite(rr) || rr > around.soi * 4) { started = false; continue; }
            o.pointAt(nu, pt);
            float px = cam.sx(around.x + pt[0]);
            float py = cam.sy(around.y + pt[1]);
            if (Math.abs(px) > 1e6 || Math.abs(py) > 1e6) { started = false; continue; }
            if (!started) { path.moveTo(px, py); started = true; }
            else path.lineTo(px, py);
        }

        Paint p = Art.stroke(widthDp, colour);
        if (dashed) {
            p.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{Art.dp(5), Art.dp(6)}, 0));
        }
        c.drawPath(path, p);
        p.setPathEffect(null);
    }

    /** Apoapsis and periapsis markers, with their altitudes. */
    public void apsides(Canvas c, Camera cam, Orbit o, Body around) {
        if (o == null || !o.valid) return;

        o.pointAt(0, pt);
        marker(c, cam.sx(around.x + pt[0]), cam.sy(around.y + pt[1]),
                Art.GREEN, "PE " + Art.dist(o.periapsis() - around.radius));

        if (o.closed()) {
            o.pointAt(Math.PI, pt);
            marker(c, cam.sx(around.x + pt[0]), cam.sy(around.y + pt[1]),
                    Art.CYAN, "AP " + Art.dist(o.apoapsis() - around.radius));
        }
    }

    private void marker(Canvas c, float x, float y, int colour, String label) {
        float s = Art.dp(4);
        c.drawLine(x - s, y, x + s, y, Art.stroke(1.4f, colour));
        c.drawLine(x, y - s, x, y + s, Art.stroke(1.4f, colour));
        c.drawCircle(x, y, s * 0.55f, Art.stroke(1.2f, colour));
        Paint p = Art.text(6.5f, colour, true);
        p.setLetterSpacing(0.1f);
        Art.textCenter(c, label, x, y - Art.dp(9), p);
    }

    /** The sphere-of-influence boundary, so a transfer has a visible target. */
    public void soi(Canvas c, Camera cam, Body b) {
        if (b.parent == null) return;
        float r = cam.len(b.soi);
        if (r < Art.dp(14) || r > Art.dp(4000)) return;
        Paint p = Art.stroke(1f, Art.alpha(Art.MAGENTA, 0.35f));
        p.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{Art.dp(3), Art.dp(9)}, 0));
        c.drawCircle(cam.sx(b.x), cam.sy(b.y), r, p);
        p.setPathEffect(null);
    }

    // =====================================================================
    // THE SHIP
    // =====================================================================

    /**
     * The craft, drawn part by part along its stack.
     *
     * Below a few pixels it collapses to a single lit dot with a heading tick,
     * because at map zoom a faithful drawing of a nine-metre rocket is a
     * smudge, and a smudge tells you nothing about which way it is pointing.
     */
    public void craft(Canvas c, Camera cam, Craft cr, Body soiBody,
                      double phase, boolean detail) {
        float x = cam.sx(soiBody.x + cr.x);
        float y = cam.sy(soiBody.y + cr.y);
        float scale = cam.len(1.0);          // pixels per metre

        if (!detail || scale * cr.height() < Art.dp(2.5f)) {
            float a = (float) -cr.angle;
            c.drawCircle(x, y, Art.dp(2.6f), Art.fill(Art.CYAN));
            Art.glow(c, x, y, Art.dp(9), Art.CYAN, 0.5f);
            c.drawLine(x, y,
                    x + (float) Math.cos(a) * Art.dp(9),
                    y + (float) Math.sin(a) * Art.dp(9),
                    Art.stroke(1.4f, Art.alpha(Art.CYAN, 0.85f)));
            return;
        }

        c.save();
        c.translate(x, y);
        // screen y is inverted, so a world heading of angle draws at -angle,
        // and the stack is modelled pointing +y in its own frame
        c.rotate((float) Math.toDegrees(-cr.angle) + 90f);

        double totalH = cr.height();
        double yy = -totalH / 2;   // model-space, metres, from the tail

        // plume first, so the nozzle overlaps it
        plume(c, cr, scale, (float) (yy * scale), phase);

        for (int i = cr.units.size() - 1; i >= 0; i--) {
            Craft.Unit un = cr.units.get(i);
            if (un.detached) continue;
            drawPart(c, un, scale, (float) yy);
            yy += un.type.height;
        }

        if (cr.heat > 0.05) heatGlow(c, cr, scale, (float) (-totalH / 2 * scale), phase);
        c.restore();
    }

    /**
     * One part's silhouette.
     *
     * Shapes, not rectangles. A rocket drawn as a column of identical grey
     * boxes tells you nothing about what you built — you cannot see where the
     * engine is, which end is the nose, or that the thing on top is a capsule.
     * Every kind gets an outline you can read at a glance, and the widths
     * differ so the stack has a profile instead of being a wall.
     */
    private void drawPart(Canvas c, Craft.Unit un, float scale, float yMetres) {
        Part t = un.type;
        float w = (float) (t.width * scale);
        float h = (float) (t.height * scale);
        float top = -(yMetres + (float) t.height) * scale;
        float bot = top + h;
        float hw = w / 2;

        int body, edge, trim;
        switch (t.kind) {
            case Part.POD:       body = 0xFFE9EEF6; edge = 0xFF8A96A8; trim = 0xFF3E6E96; break;
            case Part.TANK:      body = 0xFFC9D2DE; edge = 0xFF6E7A8C; trim = 0xFF9AA6B6; break;
            case Part.ENGINE:    body = 0xFF7E848E; edge = 0xFF3A3F48; trim = 0xFF52585F; break;
            case Part.DECOUPLER: body = 0xFF8A7038; edge = 0xFF4A3A18; trim = 0xFFB8A060; break;
            case Part.CHUTE:     body = 0xFFAEC0D2; edge = 0xFF5E7284; trim = 0xFFFF8A4A; break;
            case Part.NOSE:      body = 0xFFEDF2FA; edge = 0xFF8A96A8; trim = 0xFFD03A4A; break;
            case Part.SCIENCE:   body = 0xFF74C8E4; edge = 0xFF246A80; trim = 0xFFFFFFFF; break;
            case Part.DOCK:      body = 0xFFAAB4C2; edge = 0xFF56606C; trim = 0xFF4BE0FF; break;
            case Part.RCS:       body = 0xFF9AA2AE; edge = 0xFF4A525C; trim = 0xFF4BE0FF; break;
            case Part.PANEL:     body = 0xFF8A94A2; edge = 0xFF464E58; trim = 0xFF1E3A6A; break;
            case Part.LEG:       body = 0xFF9AA4B0; edge = 0xFF4A525C; trim = 0xFF9AA4B0; break;
            case Part.WHEEL:     body = 0xFF6A727C; edge = 0xFF2A3038; trim = 0xFF3A4048; break;
            case Part.FIN:       body = 0xFFD8DEE8; edge = 0xFF6E7A8C; trim = 0xFFD03A4A; break;
            default:             body = 0xFFC0C8D4; edge = 0xFF666E7A; trim = 0xFF8A94A2; break;
        }

        Path p = new Path();

        switch (t.kind) {
            case Part.NOSE: {
                // an ogive, not a triangle — the curve is the whole point
                p.moveTo(0, top);
                p.quadTo(hw * 0.92f, top + h * 0.55f, hw, bot);
                p.lineTo(-hw, bot);
                p.quadTo(-hw * 0.92f, top + h * 0.55f, 0, top);
                p.close();
                c.drawPath(p, Art.fill(body));
                c.drawPath(p, Art.stroke(0.9f, edge));
                c.drawLine(0, top + h * 0.12f, 0, bot, Art.stroke(0.6f, Art.alpha(trim, 0.5f)));
                break;
            }
            case Part.POD: {
                // capsule: narrow shoulders at the top, flared heat shield below
                float sw = hw * 0.52f;
                p.moveTo(-sw, top);
                p.lineTo(sw, top);
                p.lineTo(hw, bot - h * 0.16f);
                p.quadTo(hw * 0.75f, bot, 0, bot);
                p.quadTo(-hw * 0.75f, bot, -hw, bot - h * 0.16f);
                p.close();
                c.drawPath(p, Art.fill(body));
                c.drawPath(p, Art.stroke(1f, edge));
                // window
                float wy = top + h * 0.30f;
                c.drawCircle(0, wy, Math.max(1f, w * 0.13f), Art.fill(trim));
                c.drawCircle(0, wy, Math.max(1f, w * 0.13f), Art.stroke(0.7f, edge));
                // shield line
                c.drawLine(-hw * 0.95f, bot - h * 0.16f, hw * 0.95f, bot - h * 0.16f,
                        Art.stroke(0.8f, Art.alpha(0xFF6A4A38, 0.9f)));
                break;
            }
            case Part.ENGINE: {
                // a mount, a throat, and a bell that flares outward
                float mount = hw * 0.42f, throat = hw * 0.30f;
                p.moveTo(-mount, top);
                p.lineTo(mount, top);
                p.lineTo(throat, top + h * 0.34f);
                p.lineTo(hw, bot);
                p.lineTo(-hw, bot);
                p.lineTo(-throat, top + h * 0.34f);
                p.close();
                c.drawPath(p, Art.fill(body));
                c.drawPath(p, Art.stroke(0.9f, edge));
                // bell ribs
                for (int i = 1; i <= 3; i++) {
                    float f = i / 4f;
                    float y = top + h * (0.34f + 0.66f * f);
                    float ew = throat + (hw - throat) * f;
                    c.drawLine(-ew, y, ew, y, Art.stroke(0.5f, Art.alpha(trim, 0.75f)));
                }
                break;
            }
            case Part.TANK: {
                RectF r = new RectF(-hw, top, hw, bot);
                c.drawRect(r, Art.fill(body));
                // fuel level, so you can watch a tank drain
                float frac = t.fuel > 0 ? (float) (un.fuel / t.fuel) : 0;
                if (t.fuel > 0) {
                    float fy = bot - h * frac;
                    c.drawRect(new RectF(-hw, fy, hw, bot),
                            Art.fill(Art.alpha(Art.CYAN, 0.22f)));
                    c.drawLine(-hw, fy, hw, fy, Art.stroke(0.9f, Art.alpha(Art.CYAN, 0.85f)));
                }
                c.drawRect(r, Art.stroke(0.9f, edge));
                // girth bands
                for (int i = 1; i <= 2; i++) {
                    float y = top + h * i / 3f;
                    c.drawLine(-hw, y, hw, y, Art.stroke(0.6f, Art.alpha(trim, 0.55f)));
                }
                // a highlight down the left, so it reads as a cylinder
                c.drawLine(-hw * 0.62f, top + h * 0.06f, -hw * 0.62f, bot - h * 0.06f,
                        Art.stroke(1.2f, Art.alpha(0xFFFFFFFF, 0.35f)));
                break;
            }
            case Part.DECOUPLER: {
                RectF r = new RectF(-hw * 1.02f, top, hw * 1.02f, bot);
                c.drawRect(r, Art.fill(body));
                c.drawRect(r, Art.stroke(0.9f, edge));
                // hazard ticks
                int n = Math.max(3, (int) (w / Math.max(2f, Art.dp(3))));
                for (int i = 0; i < n; i++) {
                    float x = -hw + w * i / (float) n;
                    c.drawLine(x, top, x + w / n * 0.5f, bot, Art.stroke(0.7f, trim));
                }
                break;
            }
            case Part.CHUTE: {
                RectF r = new RectF(-hw * 0.8f, top, hw * 0.8f, bot);
                c.drawRect(r, Art.fill(body));
                c.drawRect(r, Art.stroke(0.9f, edge));
                c.drawLine(-hw * 0.8f, top + h * 0.5f, hw * 0.8f, top + h * 0.5f,
                        Art.stroke(1.1f, trim));
                break;
            }
            case Part.DOCK: {
                RectF r = new RectF(-hw, top, hw, bot);
                c.drawRect(r, Art.fill(body));
                c.drawRect(r, Art.stroke(0.9f, edge));
                c.drawLine(-hw * 0.55f, top + h * 0.5f, hw * 0.55f, top + h * 0.5f,
                        Art.stroke(1.4f, trim));
                break;
            }
            case Part.SCIENCE: {
                RectF r = new RectF(-hw * 0.7f, top, hw * 0.7f, bot);
                c.drawRect(r, Art.fill(body));
                c.drawRect(r, Art.stroke(0.8f, edge));
                c.drawCircle(0, top + h * 0.5f, Math.max(1f, w * 0.16f), Art.fill(trim));
                break;
            }
            case Part.STRUT:
            case Part.RCS:
            default: {
                RectF r = new RectF(-hw, top, hw, bot);
                c.drawRect(r, Art.fill(body));
                c.drawRect(r, Art.stroke(0.8f, edge));
                break;
            }
        }

        // ---- attachments that hang off the side ----
        if (t.kind == Part.FIN) {
            Path f1 = new Path();
            f1.moveTo(-hw, top); f1.lineTo(-hw * 2.5f, bot); f1.lineTo(-hw, bot); f1.close();
            Path f2 = new Path();
            f2.moveTo(hw, top); f2.lineTo(hw * 2.5f, bot); f2.lineTo(hw, bot); f2.close();
            c.drawPath(f1, Art.fill(body)); c.drawPath(f1, Art.stroke(0.8f, edge));
            c.drawPath(f2, Art.fill(body)); c.drawPath(f2, Art.stroke(0.8f, edge));
        }

        if (t.kind == Part.WHEEL) {
            float rad = h * 0.55f;
            for (int s = -1; s <= 1; s += 2) {
                float wx = hw * 1.5f * s;
                c.drawCircle(wx, bot - rad, rad, Art.fill(body));
                c.drawCircle(wx, bot - rad, rad, Art.stroke(1f, edge));
                c.drawCircle(wx, bot - rad, rad * 0.4f, Art.fill(trim));
                c.drawLine(0, bot - rad, wx, bot - rad, Art.stroke(1f, edge));
            }
        }

        if (t.kind == Part.LEG && un.deployAnim > 0.02f) {
            float sp = (float) un.deployAnim;
            float len = h * 1.3f * sp;
            Paint lp = Art.stroke(1.8f, body);
            for (int s = -1; s <= 1; s += 2) {
                float footX = s * (hw + len * 0.8f);
                float footY = bot + len * 0.55f;
                c.drawLine(s * hw, top, footX, footY, lp);
                c.drawLine(s * hw, bot, footX, footY, Art.stroke(1.1f, Art.alpha(body, 0.8f)));
                c.drawLine(footX - len * 0.16f, footY, footX + len * 0.16f, footY,
                        Art.stroke(2f, body));
            }
        }

        if (t.kind == Part.PANEL && un.deployAnim > 0.02f) {
            float sp = (float) un.deployAnim;
            float len = w * 2.6f * sp;
            for (int s = -1; s <= 1; s += 2) {
                float x0 = s > 0 ? hw : -hw - len;
                RectF a = new RectF(x0, top + h * 0.2f, x0 + len, bot - h * 0.2f);
                c.drawRect(a, Art.fill(trim));
                c.drawRect(a, Art.stroke(0.7f, Art.CYAN_DIM));
                for (int i = 1; i < 4; i++) {
                    float gx = a.left + a.width() * i / 4f;
                    c.drawLine(gx, a.top, gx, a.bottom, Art.stroke(0.5f, Art.alpha(Art.CYAN, 0.4f)));
                }
            }
        }

        if (t.kind == Part.CHUTE && un.deployAnim > 0.02f) {
            float sp = (float) un.deployAnim;
            float rad = w * 3.6f * sp;
            RectF arc = new RectF(-rad, top - rad * 1.6f, rad, top + rad * 0.35f);
            c.drawArc(arc, 180, 180, true, Art.fill(Art.alpha(0xFFFF7A4A, 0.92f * sp)));
            c.drawArc(arc, 180, 180, false, Art.stroke(1.2f, 0xFFFFC08A));
            Paint line = Art.stroke(0.8f, Art.alpha(0xFFCFD8E4, sp));
            c.drawLine(-rad * 0.8f, top - rad * 0.45f, 0, top, line);
            c.drawLine(0, top - rad * 0.6f, 0, top, line);
            c.drawLine(rad * 0.8f, top - rad * 0.45f, 0, top, line);
        }
    }

    /**
     * Engine exhaust.
     *
     * Length tracks throttle and expansion tracks ambient pressure, which is
     * the visible version of the thing the physics already models: a vacuum
     * plume spreads because there is no air to hold it in.
     */
    private void plume(Canvas c, Craft cr, float scale, float tailY, double phase) {
        if (cr.throttle <= 0.01) return;
        double fuel = cr.fuelInStage(cr.stage);
        if (fuel <= 0) return;

        float w = (float) (cr.widest() * scale);
        float len = (float) (w * (2.2 + 5.5 * cr.throttle));
        float flick = (float) (1 + 0.10 * Math.sin(phase * 41) + 0.06 * Math.sin(phase * 97));
        len *= flick;

        float y0 = -tailY;   // tail is at the bottom in model space after rotate
        Art.glow(c, 0, y0 + len * 0.35f, len * 0.75f, Art.PLUME, 0.55f);

        Path p = new Path();
        p.moveTo(-w * 0.30f, y0);
        p.lineTo(w * 0.30f, y0);
        p.lineTo(w * 0.16f * flick, y0 + len);
        p.lineTo(-w * 0.16f * flick, y0 + len);
        p.close();
        c.drawPath(p, Art.fill(Art.alpha(Art.PLUME, 0.55f)));

        Path core = new Path();
        core.moveTo(-w * 0.16f, y0);
        core.lineTo(w * 0.16f, y0);
        core.lineTo(0, y0 + len * 0.62f);
        core.close();
        c.drawPath(core, Art.fill(Art.alpha(Art.PLUME_HOT, 0.92f)));
    }

    private void heatGlow(Canvas c, Craft cr, float scale, float noseY, double phase) {
        float w = (float) (cr.widest() * scale);
        float k = (float) Math.min(1.4, cr.heat);
        int col = Art.mix(Art.AMBER, Art.RED, Math.min(1, k));
        float wob = (float) (1 + 0.08 * Math.sin(phase * 33));
        Art.glow(c, 0, noseY, w * 3.2f * k * wob, col, 0.75f);
        Path p = new Path();
        p.moveTo(-w * 0.9f * k, noseY + w * 0.6f);
        p.quadTo(0, noseY - w * 1.5f * k, w * 0.9f * k, noseY + w * 0.6f);
        c.drawPath(p, Art.fill(Art.alpha(col, 0.55f)));
    }

    // =====================================================================
    // ATMOSPHERE HAZE, drawn over everything when low
    // =====================================================================

    /**
     * Sky and ground, for when the body is too big to be a disc.
     *
     * At launch the planet is six hundred kilometres of radius across a screen
     * two kilometres wide. Drawing that as a circle fills every pixel with one
     * flat colour — which is exactly what it looked like: a blue screen with a
     * diagonal line across it, because a 240,000-pixel circle has no visible
     * curvature and no visible edge.
     *
     * So once the horizon is wider than the viewport, the body stops being a
     * disc and becomes a landscape — a sky that darkens with altitude and a
     * ground silhouette sampled from the same terrain function the collision
     * test uses, so what you see is what you will hit.
     *
     * Returns true when it has taken over, so the caller skips the disc.
     */
    public boolean surface(Canvas c, Camera cam, Body b, double altitude) {
        float screenR = cam.len(b.radius);
        if (screenR < Math.max(cam.w, cam.h) * 2.2f) return false;

        if (b.hasAtmosphere()) {
            double f = Math.max(0, Math.min(1, 1 - altitude / b.atmoHeight));
            f = f * f;
            int high = Art.mix(Art.VOID, b.atmoColour, (float) (f * 0.32));
            int low  = Art.mix(Art.VOID, b.atmoColour, (float) (f * 0.88));
            // Oversized: the world layer is rotated, so an axis-aligned fill
            // of exactly the viewport leaves the corners bare.
            float d = (float) Math.hypot(cam.w, cam.h) * 0.62f;
            Art.vgrad(c, new RectF(cam.w * 0.5f - d, cam.h * 0.5f - d,
                    cam.w * 0.5f + d, cam.h * 0.5f + d), high, low);
        }

        // Sample the terrain across exactly the angular span the viewport
        // covers, so the relief under the ship is the relief it will land on.
        double centreAngle = Math.atan2(cam.cy - b.y, cam.cx - b.x);
        double halfSpan = (cam.w * cam.scale()) / Math.max(1, b.radius);
        halfSpan = Math.min(Math.PI * 0.9, Math.max(1e-7, halfSpan * 0.75));

        path.reset();
        int steps = 180;
        for (int i = 0; i <= steps; i++) {
            double a = centreAngle - halfSpan + 2 * halfSpan * i / steps;
            double rr = b.surfaceAt(a);
            float px = cam.sx(b.x + Math.cos(a) * rr);
            float py = cam.sy(b.y + Math.sin(a) * rr);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        // Close the polygon at the body CENTRE, making a pie slice.
        //
        // It used to close at screen points "below the viewport" — but the
        // world layer is rotated so that local up is screen up, and at the
        // launch site that is a 90 degree rotation. Those points swung round
        // to the side, so the ground filled a wedge across the middle of the
        // screen instead of everything under the horizon. A slice down to the
        // centre is correct at any rotation and any zoom.
        path.lineTo(cam.sx(b.x), cam.sy(b.y));
        path.close();

        c.drawPath(path, Art.fill(b.colourDark));
        c.drawPath(path, Art.stroke(1.6f, Art.mix(b.colour, 0xFFFFFFFF, 0.35f)));
        return true;
    }

    /** Thin haze over everything, only once genuinely deep in the air. */
    public void atmosphere(Canvas c, Camera cam, Body b, double altitude) {
        if (!b.hasAtmosphere() || altitude > b.atmoHeight) return;
        float f = (float) Math.max(0, Math.min(1, 1 - altitude / b.atmoHeight));
        int sky = Art.alpha(b.atmoColour, 0.10f * f * f);
        float d = (float) Math.hypot(cam.w, cam.h) * 0.62f;
        c.drawRect(new RectF(cam.w * 0.5f - d, cam.h * 0.5f - d,
                cam.w * 0.5f + d, cam.h * 0.5f + d), Art.fill(sky));
    }

    /**
     * The vehicle drawn upright, for the assembly bay.
     *
     * Shares drawPart with the flight renderer rather than having its own
     * rectangles, so what you assemble is exactly what you watch fly. Two
     * drawings of the same rocket will always drift apart; one drawing cannot.
     *
     * Returns the screen rect of each part, top to bottom, for hit testing.
     */
    public java.util.List<RectF> stack(Canvas c, Craft cr, float cx, float centreY,
                                       float scale, int selected) {
        java.util.List<RectF> rects = new java.util.ArrayList<RectF>();
        double totalH = cr.height();
        c.save();
        c.translate(cx, centreY);

        // Side boosters first, so the core stack draws over their inner edge.
        float coreW = (float) (cr.widest() * scale);
        int side = -1;
        for (Craft.Unit un : cr.units) {
            if (un.detached || !un.type.radial) continue;
            float bw = (float) (un.type.width * scale);
            float bh = (float) (un.type.height * scale);
            float bx = side * (coreW * 0.5f + bw * 0.55f);
            float by = (float) (totalH * scale) * 0.5f - bh;
            c.save();
            c.translate(bx, by - bh * 0.1f);
            drawRadial(c, un, bw, bh);
            c.restore();
            side = -side;
        }

        double yy = -totalH / 2;
        for (int i = cr.units.size() - 1; i >= 0; i--) {
            Craft.Unit un = cr.units.get(i);
            if (un.detached || un.type.radial) continue;
            drawPart(c, un, scale, (float) yy);
            float w = (float) (un.type.width * scale);
            float h = (float) (un.type.height * scale);
            float top = -(float) ((yy + un.type.height) * scale);
            rects.add(0, new RectF(cx - w / 2, centreY + top,
                    cx + w / 2, centreY + top + h));
            yy += un.type.height;
        }
        c.restore();

        // radial units still need a row in the hit list, so they can be picked
        for (int i = 0; i < cr.units.size(); i++) {
            Craft.Unit un = cr.units.get(i);
            if (!un.type.radial) continue;
            float bw = (float) (un.type.width * scale);
            float bh = (float) (un.type.height * scale);
            float bx = cx + coreW * 0.6f;
            float by = centreY + (float) (totalH * scale) * 0.5f - bh;
            if (i < rects.size()) rects.add(i, new RectF(bx, by, bx + bw, by + bh));
            else rects.add(new RectF(bx, by, bx + bw, by + bh));
        }

        if (selected >= 0 && selected < rects.size()) {
            RectF r = rects.get(selected);
            Art.brackets(c, new RectF(r.left - Art.dp(5), r.top - Art.dp(5),
                    r.right + Art.dp(5), r.bottom + Art.dp(5)), Art.CYAN, 9, 1.8f);
        }
        return rects;
    }

    /** A strap-on booster: a slim body with a nozzle and a nose fairing. */
    private void drawRadial(Canvas c, Craft.Unit un, float w, float h) {
        float hw = w / 2;
        RectF body = new RectF(-hw, -h * 0.82f, hw, h * 0.86f);
        c.drawRect(body, Art.fill(0xFFBFC8D6));
        c.drawRect(body, Art.stroke(0.9f, 0xFF6E7A8C));

        Path nose = new Path();
        nose.moveTo(0, -h);
        nose.quadTo(hw * 0.9f, -h * 0.9f, hw, -h * 0.82f);
        nose.lineTo(-hw, -h * 0.82f);
        nose.quadTo(-hw * 0.9f, -h * 0.9f, 0, -h);
        nose.close();
        c.drawPath(nose, Art.fill(0xFFE4EAF4));
        c.drawPath(nose, Art.stroke(0.8f, 0xFF6E7A8C));

        Path bell = new Path();
        bell.moveTo(-hw * 0.5f, h * 0.86f);
        bell.lineTo(hw * 0.5f, h * 0.86f);
        bell.lineTo(hw * 0.8f, h * 1.12f);
        bell.lineTo(-hw * 0.8f, h * 1.12f);
        bell.close();
        c.drawPath(bell, Art.fill(0xFF7E848E));
        c.drawPath(bell, Art.stroke(0.8f, 0xFF3A3F48));

        float frac = un.type.fuel > 0 ? (float) (un.fuel / un.type.fuel) : 0;
        if (un.type.fuel > 0) {
            float fy = body.bottom - body.height() * frac;
            c.drawRect(new RectF(body.left, fy, body.right, body.bottom),
                    Art.fill(Art.alpha(Art.CYAN, 0.22f)));
        }
    }

    /**
     * The launch pad: a deck, legs, and a service tower at the origin.
     *
     * Without it the ground is an empty line and there is nothing to leave
     * from — no sense of scale, and no way to tell a rocket sitting still from
     * a rocket drifting. It is drawn from the same terrain function as the
     * ground, so it sits on the surface rather than hovering above it.
     */
    public void pad(Canvas c, Camera cam, Body b, double siteAngle) {
        float ppm = cam.len(1.0);                 // pixels per metre
        if (ppm < 0.02f) return;                  // too far out to matter

        double surf = b.surfaceAt(siteAngle);
        float cx = cam.sx(b.x + Math.cos(siteAngle) * surf);
        float cy = cam.sy(b.y + Math.sin(siteAngle) * surf);

        float deckW = 26f * ppm;                  // 26 m across
        float deckH = 1.6f * ppm;
        if (deckW < 3f) return;

        c.save();
        c.translate(cx, cy);
        // the pad's own "up" is radially out, which the world rotation has
        // already made screen-up, so no extra rotation is needed here
        RectF deck = new RectF(-deckW / 2, -deckH, deckW / 2, 0);
        c.drawRect(deck, Art.fill(0xFF3A424E));
        c.drawRect(deck, Art.stroke(1f, 0xFF67707E));

        // flame trench
        c.drawRect(new RectF(-deckW * 0.16f, -deckH, deckW * 0.16f, 0),
                Art.fill(0xFF14181E));

        // legs
        Paint leg = Art.stroke(1.4f, 0xFF525B68);
        for (int s = -1; s <= 1; s += 2) {
            c.drawLine(s * deckW * 0.36f, 0, s * deckW * 0.46f, deckH * 2.2f, leg);
        }

        // service tower to one side
        float tw = deckW * 0.10f, th = deckH * 14f;
        RectF tower = new RectF(deckW * 0.34f, -th, deckW * 0.34f + tw, -deckH);
        c.drawRect(tower, Art.fill(0xFF2E3640));
        c.drawRect(tower, Art.stroke(0.9f, 0xFF67707E));
        for (int i = 1; i < 7; i++) {
            float y = -deckH - th * i / 7f;
            c.drawLine(tower.left, y, tower.right, y, Art.stroke(0.6f, 0xFF4A525C));
        }
        // a light at the top
        c.drawCircle(tower.centerX(), -th, Math.max(1f, tw * 0.30f),
                Art.fill(Art.alpha(Art.RED, 0.9f)));

        if (deckW > 60) {
            Paint n = Art.text(6f, Art.alpha(Art.INK_DIM, 0.8f), true);
            n.setLetterSpacing(0.3f);
            Art.textCenter(c, "PAD 01", 0, deckH * 4f, n);
        }
        c.restore();
    }

    /**
     * A single part drawn as a catalogue icon.
     *
     * Uses drawPart, so the picture in the drawer is the same shape that ends
     * up on the rocket. A separate set of icons would drift from the parts the
     * moment either changed, and a drawer of thirty text labels is not a parts
     * bin — it is a spreadsheet.
     */
    public void icon(Canvas c, Part t, float cx, float cy, float maxHalf) {
        Craft.Unit u = new Craft.Unit(t, 0);
        u.deployed = false;
        // scale so the longer dimension fits the box
        double longest = Math.max(t.width, t.height);
        float scale = (float) (maxHalf * 2 / Math.max(0.3, longest));
        c.save();
        c.translate(cx, cy);
        // drawPart lays out from a model y; centre it by offsetting half a part
        drawPart(c, u, scale, (float) (-t.height / 2));
        c.restore();
    }
}
