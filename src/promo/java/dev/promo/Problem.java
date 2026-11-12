package dev.promo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

import dev.gui.model.Palette;

/// The first scene: agents that get hands, and whose hands they are. A hand of light, alone; then
/// what it belongs to, a data centre far away, which reaches out for each of the user's devices,
/// warm with what is theirs, takes hold, and draws what is on them over to itself, until the
/// devices are cold and the data centre shines with the user's light.
///
/// The picture shows each thing first; then the words say it, one line at a time, and have the
/// stage while they do: the picture goes dim under them and comes back as they go. The camera
/// makes two slow moves. The words are in [Storyboard]; the picture is drawn in a world as large
/// as the film, which the camera looks at.
final class Problem {

    private Problem() {}

    private static final Palette P = ArtScene.P;
    private static final Color COLD = ArtScene.COLD;
    private static final Color WARM_SCREEN = new Color(0x4a, 0x34, 0x1d), COLD_SCREEN = new Color(0x17, 0x1e, 0x2b);

    /// What is on a device, as a sign on its screen, and what flows from it.
    enum Sign { MAIL, PHOTO, CHAT, PAGE, HEART, PIN, VOICE }

    enum Kind { DESKTOP, LAPTOP, TABLET, PHONE, WATCH, SPEAKER }

    /// One of the user's devices: where it stands, where a hand takes hold of it, from its
    /// middle, and what it shows.
    private record Device(Kind kind, double x, double y, double gripX, double gripY, Sign sign) {}

    /// Two columns, so that each arm reaches its device between those nearer the data centre.
    private static final List<Device> DEVICES = List.of(
        new Device(Kind.WATCH, 700, 285, 31, 0, Sign.HEART),
        new Device(Kind.DESKTOP, 380, 420, 95, -20, Sign.PAGE),
        new Device(Kind.LAPTOP, 700, 540, 75, -14, Sign.MAIL),
        new Device(Kind.TABLET, 380, 660, 52, 0, Sign.PHOTO),
        new Device(Kind.SPEAKER, 700, 790, 35, 0, Sign.VOICE),
        new Device(Kind.PHONE, 400, 880, 28, 0, Sign.CHAT)
    );

    /// The data centre: its middle, and its left wall, where the arms come out.
    private static final double CENTRE_X = 1560, CENTRE_Y = 565, WALL = 1360;

    /// The device whose hand is seen first, alone, before anything else is there.
    private static final int FIRST = 2;

    /// When the hand of each of the other devices sets out: those nearer the data centre first.
    private static double reaches(int device) {
        int order = 0;
        for (int other = 0; other < device; other++) if (other != FIRST) order++;
        return 3.7 + order * 0.26;
    }
    private static final double REACH = 1.5;

    /// How far a hand has come at a moment, from the data centre (0) to its device (1), how
    /// closed it is, and how much there is of it.
    private record Reach(double reached, double closed, double there) {}

    private static Reach reach(int device, double t) {
        if (device == FIRST) {
            // Out of nothing, close by, curled; it opens as it comes, waits, and takes hold once
            // its device is there.
            double coming = Draw.ease(t, 0.1, 2.4), taking = Draw.ease(t, 4.3, 5.2);
            double closed = Math.max(0.7 * (1 - Draw.ease(t, 0.2, 2.2)), Draw.ease(t, 4.85, 5.6));
            return new Reach(Draw.mix(Draw.mix(0.66, 0.93, coming), 1, taking), closed, Draw.ease(t, 0.1, 1.2));
        }
        // Curled a little as it sets out, opening on the way, closing on arrival.
        double from = reaches(device);
        double closed = Math.max(0.5 * (1 - Draw.ease(t, from, from + 0.9)), Draw.ease(t, from + REACH - 0.25, from + REACH + 0.45));
        return new Reach(Draw.ease(t, from, from + REACH), closed, Draw.ease(t, from, from + 0.5));
    }

    /// When the hand of `device` takes hold of it.
    private static double holds(int device) {
        return device == FIRST ? 5.2 : reaches(device) + REACH;
    }

    /// When the picture drops out of sight, once all is said.
    private static final double LEAVES = 17.4;

    /// When what is on the devices begins to flow, and the devices go cold.
    private static final double FLOWS = 10.4;

    /// Where the camera looks, arriving `at` seconds in after `glide`: the point of the world in
    /// the middle of the picture, and how near.
    private record Look(double at, double glide, double x, double y, double zoom) {}

    /// Two moves, both slow: from the first hand back to all there is, then nearer to where
    /// it all goes.
    private static final List<Look> LOOKS = List.of(
        new Look(0, 0, 700, 526, 2.6),
        new Look(7.0, 4.0, 975, 585, 0.92),
        new Look(16.4, 5.4, 1110, 590, 1.08)
    );

    /// What is said, and when: the picture first shows it, then the words have the stage. While
    /// they are there, the picture goes `dim` into the night, and comes back as they go.
    private record Said(int line, double in, double out, double dim) {}

    private static final List<Said> SAID = List.of(
        new Said(0, 0.7, 3.3, 0.3),
        new Said(1, 6.6, 10.6, 0.84),
        new Said(2, 11.7, 15.0, 0.82),
        new Said(3, 15.3, 17.6, 0.7)
    );

    /// @param lines the opening, what Big Tech does with it, how, what that comes to, the
    ///              question that ends the scene, and its answer
    static Scene scene(List<String> lines) {
        return new ArtScene("problem", 23.4) {
            @Override void paint(Graphics2D g, double t) {
                night(g);
                // At the end the picture leaves: it drops away, faster and faster, while the
                // camera lifts into the night above it.
                double picture = 1 - Draw.ease(t, 18.0, 18.4);
                if (picture > 0) {
                    Look look = look(t);
                    look = new Look(look.at(), look.glide(), look.x(), look.y() - 320 * Draw.ease(t, LEAVES - 0.1, LEAVES + 1.0), look.zoom());
                    double falling = Math.max(0, t - LEAVES), fall = 1500 * falling * falling;
                    var before = Draw.fade(g, picture);
                    dust(g, t, look);
                    Graphics2D world = (Graphics2D) g.create();
                    world.translate(Film.WIDTH / 2.0, Film.HEIGHT / 2.0);
                    world.scale(look.zoom(), look.zoom());
                    world.translate(-look.x(), -look.y() + fall);
                    world(world, t);
                    world.dispose();
                    vignette(g);
                    g.setComposite(before);
                }

                // The words, one line at a time, over a picture gone dim for them.
                double dim = 0;
                for (Said said : SAID) dim = Math.max(dim, said.dim() * Draw.shown(t, said.in() - 0.3, said.out(), 0.6));
                if (dim > 0) {
                    g.setColor(Draw.alpha(Draw.mix(P.NIGHT(), Color.BLACK, 0.35), dim));
                    g.fillRect(0, 0, Film.WIDTH, Film.HEIGHT);
                }
                // The first beside the hand it speaks of; the others in the middle of it all.
                Said first = SAID.get(0);
                said(g, t, lines.get(first.line()), Draw.font(96, true, -0.02), P.TEXT(), true, 170, 540, 760, first);
                for (int which = 1; which < SAID.size(); which++) {
                    Said said = SAID.get(which);
                    boolean last = which == SAID.size() - 1;
                    said(g, t, lines.get(said.line()), Draw.font(last ? 66 : 58, true, -0.015), last ? P.FLAME() : P.TEXT(), false, MIDDLE, 540, 1380, said);
                }

                // The question comes word by word, and the answer under it, its "your" underlined
                // as it is said; then both are drawn into a point of light, which the next scene
                // has burst into the window's first button.
                if (t >= 18.0) {
                    Letters question = Letters.centred(lines.get(4), Draw.font(64, true, -0.015), MIDDLE, 500);
                    Letters.Holding coming = question.wordByWord(t, 18.2, 0.16, 99), going = Effects.drawnInto(Effects.SEED, t, 21.3, 23.2);
                    question.paint(g, P.TEXT(), letter -> t < 21.3 ? coming.of(letter) : going.of(letter));
                    Letters answer = Letters.centred(lines.get(5), Draw.font(48, true, -0.01), MIDDLE, 590);
                    Letters.Holding answering = answer.wordByWord(t, 19.9, 0.2, 99), taken = Effects.drawnInto(Effects.SEED, t, 21.4, 23.2);
                    answer.paint(g, P.FLAME(), letter -> t < 21.4 ? answering.of(letter) : taken.of(letter));
                    underline(g, answer, 1, 590 + 9, Draw.ease(t, 20.45, 20.85) * (1 - Draw.ease(t, 21.35, 21.6)), P.FLAME());
                    Effects.light(g, Effects.SEED, Draw.ease(t, 21.6, 23.3) * (1 + 0.06 * Math.sin(t * 30)));
                }
                curtain(g, t, 0.8, 0);
            }
        };
    }

    /// A line under word `word` of `line`, at `y`, drawn from its left `drawn` of the way along,
    /// and gone as `drawn` falls again.
    private static void underline(Graphics2D g, Letters line, int word, double y, double drawn, Color colour) {
        if (drawn <= 0) return;
        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE;
        for (Letters.Letter letter : line.letters()) {
            if (letter.word() != word) continue;
            java.awt.geom.Rectangle2D box = letter.shape().getBounds2D();
            left = Math.min(left, box.getMinX());
            right = Math.max(right, box.getMaxX());
        }
        if (left > right) return;
        g.setColor(colour);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(left, y, Draw.mix(left, right, drawn), y));
    }

    // ─── the camera ────────────────────────────────────────────────────────────────────────

    private static Look look(double t) {
        Look now = LOOKS.get(0);
        for (Look next : LOOKS.subList(1, LOOKS.size())) {
            double p = Draw.ease(t, next.at() - next.glide(), next.at());
            if (p <= 0) break;
            now = new Look(next.at(), next.glide(), Draw.mix(now.x(), next.x(), p), Draw.mix(now.y(), next.y(), p),
                Math.exp(Draw.mix(Math.log(now.zoom()), Math.log(next.zoom()), p)));
        }
        return now;
    }

    /// The night at the edges of the picture.
    private static void vignette(Graphics2D g) {
        Color night = Draw.mix(P.NIGHT(), Color.BLACK, 0.35);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(Film.WIDTH / 2.0, Film.HEIGHT / 2.0), 1150f, new float[] {0f, 0.45f, 0.85f},
            new Color[] {Draw.alpha(night, 0), Draw.alpha(night, 0), Draw.alpha(night, 0.6)}));
        g.fillRect(0, 0, Film.WIDTH, Film.HEIGHT);
    }

    /// Specks in the night, farther away than the picture, so that they move less as the camera does.
    private static void dust(Graphics2D g, double t, Look look) {
        for (int i = 0; i < 70; i++) {
            double depth = 0.25 + 0.35 * ((i * 0.381) % 1.0);
            double wx = (i * 487.3) % 2600 - 340, wy = (i * 263.7) % 1500 - 210 - t * 6 * depth;
            double zoom = 1 + (look.zoom() - 1) * depth;
            double x = Film.WIDTH / 2.0 + (wx - look.x()) * zoom * depth + (wx - Film.WIDTH / 2.0) * (1 - depth);
            double y = Film.HEIGHT / 2.0 + (wy - look.y()) * zoom * depth + (wy - Film.HEIGHT / 2.0) * (1 - depth);
            double r = 1 + 1.6 * depth, twinkle = 0.5 + 0.5 * Math.sin(t * 1.3 + i);
            g.setColor(Draw.alpha(P.SUBTEXT(), (0.05 + 0.12 * twinkle) * depth * 2));
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
    }

    // ─── the words ─────────────────────────────────────────────────────────────────────────

    /// Writes `text` in lines no wider than `width`: from `x` or around it, with the middle of
    /// all its lines at `y`. Word by word it comes, rising a little; all at once it goes,
    /// rising on into the night.
    private static void said(Graphics2D g, double t, String text, java.awt.Font font, Color colour, boolean fromLeft,
                              double x, double y, double width, Said said) {
        if (t < said.in() || t > said.out()) return;
        // Lines of about the same length, rather than a full one and a word or two under it.
        List<String> rows = Letters.wrapped(text, font, width);
        if (rows.size() > 1) {
            List<String> even = Letters.wrapped(text, font, Letters.width(text, font) / rows.size() + font.getSize2D() * 1.5);
            if (even.size() == rows.size()) rows = even;
        }
        double tall = font.getSize2D() * 1.12, top = y - tall * rows.size() / 2 + font.getSize2D() * 0.78;
        double leaving = Draw.ease(t, said.out() - 0.55, said.out()), apart = 0.13;
        int before = 0;
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            double baseline = top + row * tall - 14 * leaving;
            Letters letters = fromLeft ? Letters.from(line, font, x, baseline) : Letters.centred(line, font, x, baseline);
            int earlier = before;
            letters.paint(g, colour, letter -> {
                double from = said.in() + (earlier + letter.word()) * apart, there = Draw.arrive(t, from, from + 0.6);
                return new Letters.Held(0, 18 * (1 - there), 1, 0, there * (1 - leaving));
            });
            before += letters.words();
        }
    }

    // ─── the world ─────────────────────────────────────────────────────────────────────────

    private static void world(Graphics2D g, double t) {
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
        double filled = Draw.ease(t, FLOWS + 0.6, FLOWS + 4.6);
        dataCentre(g, t, filled);
        double drained = 0.85 * Draw.ease(t, FLOWS + 0.3, FLOWS + 4.4);
        for (int i = 0; i < DEVICES.size(); i++) {
            Device d = DEVICES.get(i);
            double in = 3.2 + i * 0.15;
            if (t < in) continue;
            double grown = Draw.spring((t - in) / 0.9);
            device(g, d, pulled(i, t), Math.max(0.0001, grown), Draw.clamp((t - in) * 4), 1 - drained, t);
        }
        for (int i = 0; i < DEVICES.size(); i++) arm(g, i, t);
        for (int i = 0; i < DEVICES.size(); i++) flow(g, i, t);
    }

    /// How far the device has been pulled towards the data centre `t` seconds in: a tug as the
    /// hand takes hold, and a slow pull while it is emptied.
    private static double pulled(int device, double t) {
        double held = holds(device);
        double tug = t < held ? 0 : 12 * Draw.spring((t - held) / 0.7);
        return tug + 26 * Draw.ease(t, FLOWS, FLOWS + 6);
    }

    private static Point2D.Double grip(int device, double t) {
        Device d = DEVICES.get(device);
        return new Point2D.Double(d.x() + d.gripX() + pulled(device, t), d.y() + d.gripY());
    }

    /// Where the arm to `device` comes out of the data centre: in the order of the devices from
    /// the top, so that no two arms cross.
    private static Point2D.Double shoulder(int device) {
        double y = DEVICES.get(device).y() + DEVICES.get(device).gripY();
        return new Point2D.Double(WALL + 4, 390 + (y - 285) / (880 - 285) * 360);
    }

    /// The arm as a curve, from the data centre (0) to the wrist of the hand on the device (1),
    /// so far back from where it takes hold that the knuckles are there.
    private static Point2D.Double along(int device, double t, double u) {
        Point2D a = shoulder(device), held = grip(device, t), d = new Point2D.Double(held.getX() + 40 * HAND, held.getY());
        double c1x = a.getX() - 330, c1y = a.getY(), c2x = d.getX() + 250, c2y = d.getY();
        double v = 1 - u;
        return new Point2D.Double(
            v * v * v * a.getX() + 3 * v * v * u * c1x + 3 * v * u * u * c2x + u * u * u * d.getX(),
            v * v * v * a.getY() + 3 * v * v * u * c1y + 3 * v * u * u * c2y + u * u * u * d.getY());
    }

    // ─── the hands ─────────────────────────────────────────────────────────────────────────

    /// The light the hands are made of.
    private static final Color GHOST = new Color(0xb4, 0xcd, 0xf0);
    /// How large a hand is, and how wide its arm where it meets the palm.
    private static final double HAND = 1.25, WRIST = 30 * HAND;

    /// A hand and its arm, one shape of light without an edge: as wide as the wrist where they
    /// meet, narrowing slowly all the way back to a point on the data centre's wall, and fainter
    /// the nearer it is to there. The hand is in depth: each part of it covers what is behind it,
    /// and is the darker, the farther from the eye it is.
    private static void arm(Graphics2D g, int device, double t) {
        Reach reach = reach(device, t);
        if (reach.there() <= 0 || reach.reached() < 0.01) return;
        int steps = 60;
        Point2D[] middle = new Point2D[steps + 1];
        for (int step = 0; step <= steps; step++) middle[step] = along(device, t, reach.reached() * step / steps);
        Path2D outline = new Path2D.Double();
        for (int side = 0; side < 2; side++) {
            for (int k = 0; k <= steps; k++) {
                int step = side == 0 ? k : steps - k;
                Point2D before = middle[Math.max(0, step - 1)], after = middle[Math.min(steps, step + 1)];
                double dx = after.getX() - before.getX(), dy = after.getY() - before.getY(), length = Math.max(1e-6, Math.hypot(dx, dy));
                double half = WRIST / 2 * Math.pow(step / (double) steps, 0.75) * (side == 0 ? 1 : -1);
                double px = middle[step].getX() - dy / length * half, py = middle[step].getY() + dx / length * half;
                if (side == 0 && k == 0) outline.moveTo(px, py); else outline.lineTo(px, py);
            }
        }
        outline.closePath();
        Point2D wrist = middle[steps], back = middle[steps - 2];
        double angle = Math.atan2(wrist.getY() - back.getY(), wrist.getX() - back.getX());
        Hand hand = hand(wrist, angle, reach.closed(), t, device);
        Area arm = new Area(outline), ghost = new Area(arm);
        ghost.add(hand.shape());
        arm.subtract(hand.shape());

        Point2D shoulder = middle[0];
        double there = reach.there();
        // A glow around it all; both it and the arm fade towards where the arm comes from.
        for (int glow = 0; glow < 2; glow++) {
            float wide = glow == 0 ? 16f : 7f;
            double strength = (glow == 0 ? 0.05 : 0.08) * there;
            g.setPaint(new java.awt.LinearGradientPaint(shoulder, wrist, new float[] {0f, 0.6f, 1f},
                new Color[] {Draw.alpha(GHOST, 0), Draw.alpha(GHOST, strength * 0.5), Draw.alpha(GHOST, strength)}));
            g.setStroke(new BasicStroke(wide, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(ghost);
        }
        g.setPaint(new java.awt.LinearGradientPaint(shoulder, wrist, new float[] {0f, 0.55f, 1f},
            new Color[] {Draw.alpha(GHOST, 0.03 * there), Draw.alpha(GHOST, 0.14 * there), lit(DEPTH_WRIST, there)}));
        g.fill(arm);
        // The hand, from the back to the front, each part shaded along its length by its depth,
        // and casting a little shadow on what it lies over.
        Area behind = new Area();
        Color shade = Draw.mix(P.NIGHT(), Color.BLACK, 0.4);
        for (Piece piece : hand.pieces()) {
            if (!behind.isEmpty()) {
                Shape clip = g.getClip();
                g.clip(behind);
                for (int soft = 0; soft < 3; soft++) {
                    g.setStroke(new BasicStroke((float) ((7 - 2 * soft) * HAND), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(Draw.alpha(shade, 0.07 * there));
                    g.draw(piece.shape());
                }
                g.setClip(clip);
            }
            behind.add(piece.shape());
            Point2D from = piece.from(), to = piece.to();
            if (from.distance(to) < 0.5) g.setColor(lit(piece.nearFrom(), there));
            else g.setPaint(new java.awt.LinearGradientPaint(from, to, new float[] {0f, 1f},
                new Color[] {lit(piece.nearFrom(), there), lit(piece.nearTo(), there)}));
            g.fill(piece.shape());
        }
    }

    /// How near to the eye a point of the hand is, from the farthest a hand comes (0) to the
    /// nearest (1), and how near its wrist is, where it meets the arm.
    private static double near(double depth) { return Draw.clamp((depth + 13) / 28); }
    private static final double DEPTH_WRIST = 0.5;

    /// The light of the hand where it is `near`: bright and clear in front, dark and faint behind.
    private static Color lit(double near, double there) {
        Color colour = Draw.mix(Draw.mix(P.NIGHT(), GHOST, 0.3), GHOST, near);
        return Draw.alpha(colour, (0.16 + 0.34 * near) * there);
    }

    /// A hand, drawn: all of it, and its parts from the farthest to the nearest, each without what
    /// is in front of it.
    private record Hand(Area shape, List<Piece> pieces) {}
    /// A part of a hand: its shape, and how near it is at two points of it.
    private record Piece(Area shape, Point2D from, double nearFrom, Point2D to, double nearTo) {}

    /// Where the knuckles of the index, middle, ring and little finger are on the back of the
    /// hand, from the wrist along the arm (x), across (y, the thumb's side negative) and out of
    /// the back of the hand (z, so the palm's side negative); their bones' lengths from the
    /// knuckle out, and how thick each finger is.
    private static final double[][] KNUCKLES = {{41, -11.6, -0.5}, {43.5, -3.9, 0.9}, {41.5, 3.9, 0.4}, {37.5, 11.2, -1.2}};
    private static final double[][] PHALANGES = {{19, 12, 9}, {21, 13.5, 10}, {20, 13, 9.5}, {15.5, 9.5, 8.5}};
    private static final double[] THICK = {1, 1.05, 0.97, 0.86};
    /// The outline of the palm, from the wrist on the thumb's side round to the little finger's.
    private static final double[][] PALM = {{-2, -15}, {18, -16.5}, {38, -17.2}, {44.5, -9}, {46, -1}, {44, 8}, {38.5, 15.8}, {18, 16.8}, {-2, 15}};

    /// A hand reaching like a claw, its wrist at `wrist`, its arm pointing along `angle`. It is
    /// built on its bones and seen turned in space: its palm towards the eye and down, the index
    /// finger on top and nearest, the others each lower and farther behind, the little finger
    /// farthest, and the thumb under it all and near again, across from the index. A finger that
    /// bends grows shorter to the eye. Reaching, it is open and at ease, the wrist lifted a little,
    /// each finger bent a little more than the one before it, all of it breathing; as it `closes`
    /// it bows at the wrist and the fingers, the little one first, curl round what it takes hold
    /// of while the thumb comes up to meet them.
    private static Hand hand(Point2D wrist, double angle, double closes, double t, int device) {
        double breath = Math.sin(t * 1.5 + device * 1.7);
        double bow = Draw.mix(-0.18 + 0.05 * breath, 0.25, closes), turn = Draw.mix(0.55, 0.7, closes) + 0.03 * breath;
        double cb = Math.cos(bow), sb = Math.sin(bow), ct = Math.cos(turn), st = Math.sin(turn);
        // From the hand to the picture: bowed at the wrist, then turned about the arm so that
        // the index's side is up and towards the eye, and the palm's side down and towards it.
        // Gives where it is across the arm (up negative) and how near it is.
        java.util.function.Function<double[], double[]> seen = p -> {
            double x = p[0] * cb + p[2] * sb, z = -p[0] * sb + p[2] * cb;
            return new double[] {x, p[1] * ct - z * st, -p[1] * st - z * ct};
        };
        AffineTransform place = AffineTransform.getTranslateInstance(wrist.getX(), wrist.getY());
        place.rotate(angle);
        // Up on the picture is negative across the arm when it points left; this keeps it up
        // whichever way the arm points.
        place.scale(HAND, Math.cos(angle) < 0 ? -HAND : HAND);

        // The palm, cupping as it closes.
        Path2D outline = new Path2D.Double();
        double[][] rim = new double[PALM.length][];
        for (int k = 0; k < PALM.length; k++) {
            double y = PALM[k][1], cup = (1.5 + 4 * closes) * (y / 16) * (y / 16) - 1.5;
            rim[k] = seen.apply(new double[] {PALM[k][0], y, cup});
        }
        smooth(outline, rim);
        double[] top = seen.apply(new double[] {24, -16, 0}), bottom = seen.apply(new double[] {24, 16, 0});
        List<Piece> pieces = new java.util.ArrayList<>();
        pieces.add(new Piece(new Area(outline), new Point2D.Double(top[0], top[1]), near(top[2]), new Point2D.Double(bottom[0], bottom[1]), near(bottom[2])));

        List<Piece> fingers = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            // The little finger closes first, the index last; each breathes on its own.
            double c = Draw.ease(closes, 0.1 * (3 - i), 0.7 + 0.1 * (3 - i)), sway = 0.06 * Math.sin(t * 1.5 + device * 1.7 + i * 0.8);
            double spread = Draw.mix((i - 1.5) * (0.075 + 0.015 * breath), (i - 1.5) * 0.08, c);
            double[] bends = {Draw.mix(0.06 + 0.06 * i + sway, 0.45 + 0.05 * i, c), Draw.mix(0.16 + 0.08 * i + sway, 0.8 + 0.05 * i, c), Draw.mix(0.1 + 0.05 * i, 0.55, c)};
            double[][] joints = new double[4][];
            joints[0] = KNUCKLES[i].clone();
            double bent = 0;
            for (int b = 0; b < 3; b++) {
                bent += bends[b];
                double l = PHALANGES[i][b];
                double[] from = joints[b];
                joints[b + 1] = new double[] {from[0] + l * Math.cos(bent) * Math.cos(spread), from[1] + l * Math.cos(bent) * Math.sin(spread), from[2] - l * Math.sin(bent)};
            }
            double w = THICK[i];
            fingers.add(tube(seen, joints, new double[] {5.0 * w, 4.5 * w, 3.9 * w, 3.2 * w}));
        }
        // The thumb, from low on the wrist by the index, across under the palm, its tip below
        // the little finger: pointing on while the hand reaches, up to meet the fingers as it closes.
        double c = Draw.ease(closes, 0, 0.75);
        double[][] open = {{0.6, 0.35, -0.7}, {0.92, 0.15, -0.3}, {0.98, 0, -0.08}};
        double[][] up = {{0.6, 0.35, -0.72}, {0.9, 0.15, -0.1}, {0.6, -0.1, 0.6}};
        double[] lengths = {18, 12.5, 10.5};
        double[][] joints = new double[4][];
        joints[0] = new double[] {4, -9.5, -3};
        for (int b = 0; b < 3; b++) {
            double[] d = new double[3];
            double length = 0;
            for (int k = 0; k < 3; k++) { d[k] = Draw.mix(open[b][k], up[b][k], c); length += d[k] * d[k]; }
            length = Math.sqrt(length);
            for (int k = 0; k < 3; k++) d[k] = joints[b][k] + d[k] / length * lengths[b];
            joints[b + 1] = d;
        }
        fingers.add(tube(seen, joints, new double[] {8.5, 6.0, 5.0, 3.9}));
        // From the back to the front, each finger by how near it is on the whole.
        fingers.sort(java.util.Comparator.comparingDouble(piece -> piece.nearFrom() + piece.nearTo()));
        pieces.addAll(fingers);

        Area whole = new Area();
        for (Piece piece : pieces) whole.add(piece.shape());
        // Each without what is in front of it, so that the nearer covers the farther.
        List<Piece> placed = new java.util.ArrayList<>();
        for (int k = 0; k < pieces.size(); k++) {
            Area shape = new Area(pieces.get(k).shape());
            for (Piece nearer : pieces.subList(k + 1, pieces.size())) shape.subtract(nearer.shape());
            Piece piece = pieces.get(k);
            placed.add(new Piece(shape.createTransformedArea(place), place.transform(piece.from(), null), piece.nearFrom(),
                place.transform(piece.to(), null), piece.nearTo()));
        }
        return new Hand(whole.createTransformedArea(place), placed);
    }

    /// A finger or the thumb, as the eye sees it: one smooth tube through its `joints`, `r` thick
    /// at each and rounded at the tip, with how near it is at its root and at its tip.
    private static Piece tube(java.util.function.Function<double[], double[]> seen, double[][] joints, double[] r) {
        int n = joints.length, per = 8;
        double[][] at = new double[n + 2][];
        for (int k = 0; k < n; k++) at[k + 1] = seen.apply(joints[k]);
        at[0] = new double[] {2 * at[1][0] - at[2][0], 2 * at[1][1] - at[2][1]};
        at[n + 1] = new double[] {2 * at[n][0] - at[n - 1][0], 2 * at[n][1] - at[n - 1][1]};
        // Along it, through the joints, smoothly.
        int samples = (n - 1) * per + 1;
        double[] xs = new double[samples], ys = new double[samples], rs = new double[samples];
        for (int k = 0; k < samples; k++) {
            int seg = Math.min(n - 2, k / per);
            double u = (k - seg * per) / (double) per;
            double[] a = at[seg], b = at[seg + 1], c = at[seg + 2], d = at[seg + 3];
            for (int axis = 0; axis < 2; axis++) {
                double value = 0.5 * (2 * b[axis] + (-a[axis] + c[axis]) * u + (2 * a[axis] - 5 * b[axis] + 4 * c[axis] - d[axis]) * u * u
                    + (-a[axis] + 3 * b[axis] - 3 * c[axis] + d[axis]) * u * u * u);
                if (axis == 0) xs[k] = value; else ys[k] = value;
            }
            rs[k] = Draw.mix(r[seg], r[seg + 1], u);
        }
        Path2D outline = new Path2D.Double();
        double[] nx = new double[samples], ny = new double[samples];
        for (int k = 0; k < samples; k++) {
            double dx = xs[Math.min(samples - 1, k + 1)] - xs[Math.max(0, k - 1)], dy = ys[Math.min(samples - 1, k + 1)] - ys[Math.max(0, k - 1)];
            double length = Math.max(1e-6, Math.hypot(dx, dy));
            nx[k] = -dy / length; ny[k] = dx / length;
        }
        for (int k = 0; k < samples; k++) {
            double x = xs[k] + nx[k] * rs[k], y = ys[k] + ny[k] * rs[k];
            if (k == 0) outline.moveTo(x, y); else outline.lineTo(x, y);
        }
        // Round over the tip.
        int last = samples - 1;
        double from = Math.atan2(ny[last], nx[last]);
        for (int k = 1; k < 10; k++) {
            double a = from - Math.PI * k / 10;
            outline.lineTo(xs[last] + Math.cos(a) * rs[last], ys[last] + Math.sin(a) * rs[last]);
        }
        for (int k = last; k >= 0; k--) outline.lineTo(xs[k] - nx[k] * rs[k], ys[k] - ny[k] * rs[k]);
        outline.closePath();
        Area finger = new Area(outline);
        finger.add(new Area(new Ellipse2D.Double(xs[0] - r[0], ys[0] - r[0], 2 * r[0], 2 * r[0])));
        return new Piece(finger, new Point2D.Double(at[1][0], at[1][1]), near(at[1][2]), new Point2D.Double(at[n][0], at[n][1]), near(at[n][2]));
    }

    /// A closed curve through `points` (x, y first), round at each.
    private static void smooth(Path2D path, double[][] points) {
        int n = points.length;
        path.moveTo(points[0][0], points[0][1]);
        for (int k = 0; k < n; k++) {
            double[] a = points[(k - 1 + n) % n], b = points[k], c = points[(k + 1) % n], d = points[(k + 2) % n];
            path.curveTo(b[0] + (c[0] - a[0]) / 6, b[1] + (c[1] - a[1]) / 6, c[0] - (d[0] - b[0]) / 6, c[1] - (d[1] - b[1]) / 6, c[0], c[1]);
        }
        path.closePath();
    }

    // ─── what flows ────────────────────────────────────────────────────────────────────────

    /// What is on the device, sign by sign, from its screen to its edge and along the arm into
    /// the data centre.
    private static void flow(Graphics2D g, int device, double t) {
        double begins = FLOWS + device * 0.14;
        if (t < begins) return;
        Device d = DEVICES.get(device);
        Sign[] signs = Sign.values();
        double gap = 0.36, travel = 1.9;
        for (int k = 0; begins + k * gap <= t; k++) {
            double went = (t - begins - k * gap) / travel;
            if (went > 1) continue;
            Point2D at;
            if (went < 0.18) {
                double p = Draw.ease(went, 0, 0.18);
                Point2D wrist = along(device, t, 1);
                at = new Point2D.Double(Draw.mix(d.x() + pulled(device, t), wrist.getX(), p), Draw.mix(d.y() + d.gripY(), wrist.getY(), p));
            } else {
                at = along(device, t, 1 - (went - 0.18) / 0.82);
            }
            double alpha = Draw.ease(went, 0, 0.08) * (1 - Draw.ease(went, 0.9, 1)), size = 17 * (1 - 0.4 * Draw.ease(went, 0.85, 1));
            Sign sign = k % 3 == 0 ? d.sign() : signs[(k * 3 + device) % signs.length];
            float r = (float) (size * 1.3);
            g.setPaint(new RadialGradientPaint(at, r, new float[] {0f, 1f}, new Color[] {Draw.alpha(P.FLAME(), 0.45 * alpha), Draw.alpha(P.FLAME(), 0)}));
            g.fill(new Ellipse2D.Double(at.getX() - r, at.getY() - r, 2 * r, 2 * r));
            sign(g, sign, at.getX(), at.getY(), size, Draw.alpha(new Color(0xff, 0xd9, 0x9a), alpha), t);
        }
    }

    // ─── the data centre ───────────────────────────────────────────────────────────────────

    /// A building of racks, cold, that shines more and more with the colour of what flows in.
    private static void dataCentre(Graphics2D g, double t, double filled) {
        double rise = Draw.arrive(t, 4.6, 5.7);
        if (rise <= 0) return;
        var before = Draw.fade(g, rise);
        double dy = 50 * (1 - rise), left = WALL, top = 330 + dy, w = 2 * (CENTRE_X - WALL), h = 470;
        Color glow = Draw.mix(COLD, P.FLAME(), filled);
        float r = 420f;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(CENTRE_X, CENTRE_Y + dy), r, new float[] {0f, 1f},
            new Color[] {Draw.alpha(glow, 0.12 + 0.12 * filled), Draw.alpha(glow, 0)}));
        g.fill(new Ellipse2D.Double(CENTRE_X - r, CENTRE_Y + dy - r, 2 * r, 2 * r));
        // On the roof, what keeps it cool, fans turning.
        for (int unit = 0; unit < 3; unit++) {
            double x = left + 40 + unit * 120;
            g.setColor(new Color(0x1a, 0x21, 0x30));
            g.fill(new RoundRectangle2D.Double(x, top - 40, 90, 40, 8, 8));
            g.setColor(Draw.alpha(COLD, 0.5));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new RoundRectangle2D.Double(x, top - 40, 90, 40, 8, 8));
            for (int fan = 0; fan < 2; fan++) {
                double fx = x + 24 + fan * 42, fy = top - 20, turned = t * 7 + unit + fan;
                g.draw(new Ellipse2D.Double(fx - 13, fy - 13, 26, 26));
                for (int blade = 0; blade < 3; blade++) {
                    double a = turned + blade * Math.PI * 2 / 3;
                    g.draw(new Line2D.Double(fx, fy, fx + Math.cos(a) * 11, fy + Math.sin(a) * 11));
                }
            }
        }
        g.setColor(new Color(0x15, 0x1b, 0x27));
        g.fill(new RoundRectangle2D.Double(left, top, w, h, 22, 22));
        g.setColor(Draw.alpha(COLD, 0.6));
        g.setStroke(new BasicStroke(2.2f));
        g.draw(new RoundRectangle2D.Double(left, top, w, h, 22, 22));
        for (int rack = 0; rack < 3; rack++) {
            double x = left + 38 + rack * 112;
            g.setColor(Draw.alpha(COLD, 0.07));
            g.fill(new RoundRectangle2D.Double(x - 8, top + 24, 104, h - 48, 10, 10));
            for (int unit = 0; unit < 9; unit++) {
                double y = top + 34 + unit * 45;
                g.setColor(Draw.alpha(COLD, 0.15));
                g.fill(new RoundRectangle2D.Double(x, y, 88, 33, 7, 7));
                boolean blinks = Math.sin(t * (5 + 4 * filled) + rack * 2.1 + unit * 1.3) > 0.2;
                // Lit with what came in: more of its units, the fuller it is.
                boolean ours = ((rack * 9 + unit) * 0.618) % 1.0 < filled;
                Color led = ours ? P.FLAME() : COLD;
                g.setColor(Draw.alpha(led, blinks ? 0.95 : 0.35));
                g.fill(new Ellipse2D.Double(x + 9, y + 12, 9, 9));
                g.setColor(Draw.alpha(led, ours ? 0.55 : 0.3));
                g.fill(new RoundRectangle2D.Double(x + 27, y + 14, 50, 6, 6, 6));
            }
        }
        g.setComposite(before);
    }

    // ─── the devices ───────────────────────────────────────────────────────────────────────

    /// A device, `dx` pulled from its place, grown to `grown` of its size, `warm` with what is
    /// on it: 1 is the user's, the lamp's colour; 0 is emptied, cold.
    private static void device(Graphics2D g, Device d, double dx, double grown, double alpha, double warm, double t) {
        var before = Draw.fade(g, alpha);
        Graphics2D on = (Graphics2D) g.create();
        on.translate(d.x() + dx, d.y());
        on.scale(grown, grown);
        Color light = Draw.mix(COLD, P.FLAME(), warm), screen = Draw.mix(COLD_SCREEN, WARM_SCREEN, warm);
        Color body = new Color(0x26, 0x23, 0x2e), edge = Draw.alpha(Draw.mix(COLD, P.BRASS(), warm), 0.9);
        float r = 170f;
        on.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), r, new float[] {0f, 1f},
            new Color[] {Draw.alpha(light, 0.10 + 0.08 * warm), Draw.alpha(light, 0)}));
        on.fill(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));
        on.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double signX = 0, signY = 0, signSize = 40;
        switch (d.kind()) {
            case DESKTOP -> {
                part(on, new RoundRectangle2D.Double(-12, 41, 24, 26, 4, 4), body, edge);
                part(on, new RoundRectangle2D.Double(-50, 63, 100, 11, 10, 10), body, edge);
                part(on, new RoundRectangle2D.Double(-95, -81, 190, 122, 16, 16), body, edge);
                screen(on, new RoundRectangle2D.Double(-86, -72, 172, 104, 8, 8), screen, light, warm);
                signY = -20; signSize = 50;
            }
            case LAPTOP -> {
                Path2D base = new Path2D.Double();
                base.moveTo(-82, 34); base.lineTo(82, 34); base.lineTo(96, 47); base.lineTo(-96, 47); base.closePath();
                part(on, base, body, edge);
                part(on, new RoundRectangle2D.Double(-75, -62, 150, 96, 12, 12), body, edge);
                screen(on, new RoundRectangle2D.Double(-67, -54, 134, 80, 6, 6), screen, light, warm);
                signY = -14; signSize = 42;
            }
            case TABLET -> {
                part(on, new RoundRectangle2D.Double(-52, -70, 104, 140, 16, 16), body, edge);
                screen(on, new RoundRectangle2D.Double(-44, -60, 88, 120, 8, 8), screen, light, warm);
                signSize = 46;
            }
            case PHONE -> {
                part(on, new RoundRectangle2D.Double(-28, -55, 56, 110, 15, 15), body, edge);
                screen(on, new RoundRectangle2D.Double(-23, -48, 46, 96, 9, 9), screen, light, warm);
                on.setColor(body);
                on.fill(new RoundRectangle2D.Double(-9, -46, 18, 5, 5, 5));
                signSize = 28;
            }
            case WATCH -> {
                part(on, new RoundRectangle2D.Double(-17, -58, 34, 116, 12, 12), new Color(0x1d, 0x1b, 0x24), edge);
                part(on, new RoundRectangle2D.Double(27, -7, 6, 14, 3, 3), body, edge);
                part(on, new RoundRectangle2D.Double(-28, -32, 56, 64, 18, 18), body, edge);
                screen(on, new RoundRectangle2D.Double(-21, -25, 42, 50, 11, 11), screen, light, warm);
                signSize = 24;
            }
            case SPEAKER -> {
                part(on, new RoundRectangle2D.Double(-35, -48, 70, 96, 34, 30), body, edge);
                on.setColor(Draw.alpha(edge, 0.35));
                for (int row = 0; row < 5; row++)
                    for (int col = 0; col < 5; col++)
                        on.fill(new Ellipse2D.Double(-20 + col * 10 - 1.6, -4 + row * 9 - 1.6, 3.2, 3.2));
                // Its ring of light, listening.
                double listening = 0.6 + 0.4 * Math.sin(t * 3);
                on.setColor(Draw.alpha(light, 0.5 + 0.5 * listening * warm));
                on.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                on.draw(new Ellipse2D.Double(-24, -40, 48, 12));
                signY = -24; signSize = 0;
            }
        }
        if (signSize > 0) sign(on, d.sign(), signX, signY, signSize, Draw.alpha(light, 0.95), t);
        on.dispose();
        g.setComposite(before);
    }

    private static void part(Graphics2D g, Shape shape, Color fill, Color edge) {
        g.setColor(fill);
        g.fill(shape);
        g.setColor(edge);
        g.draw(shape);
    }

    /// A screen, lit from within.
    private static void screen(Graphics2D g, RoundRectangle2D shape, Color colour, Color light, double warm) {
        g.setColor(colour);
        g.fill(shape);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(shape.getCenterX(), shape.getCenterY()), (float) (shape.getWidth() * 0.7),
            new float[] {0f, 1f}, new Color[] {Draw.alpha(light, 0.22 * warm + 0.05), Draw.alpha(light, 0)}));
        g.fill(shape);
    }

    // ─── the signs ─────────────────────────────────────────────────────────────────────────

    /// A sign of what is on a device, `size` wide, with its middle at `x`, `y`.
    private static void sign(Graphics2D g, Sign sign, double x, double y, double size, Color colour, double t) {
        Graphics2D s = (Graphics2D) g.create();
        s.translate(x, y);
        s.scale(size / 20, size / 20);
        s.setColor(colour);
        s.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (sign) {
            case MAIL -> {
                s.draw(new RoundRectangle2D.Double(-10, -7, 20, 14, 3, 3));
                Path2D flap = new Path2D.Double();
                flap.moveTo(-9, -6); flap.lineTo(0, 1); flap.lineTo(9, -6);
                s.draw(flap);
            }
            case PHOTO -> {
                s.draw(new RoundRectangle2D.Double(-10, -8, 20, 16, 3, 3));
                Path2D hills = new Path2D.Double();
                hills.moveTo(-9, 6); hills.lineTo(-3, -1); hills.lineTo(1, 3); hills.lineTo(4, 0); hills.lineTo(9, 6);
                s.draw(hills);
                s.fill(new Ellipse2D.Double(3, -6, 4, 4));
            }
            case CHAT -> {
                Path2D bubble = new Path2D.Double();
                bubble.append(new RoundRectangle2D.Double(-10, -8, 20, 13, 7, 7), false);
                s.draw(bubble);
                Path2D tail = new Path2D.Double();
                tail.moveTo(-5, 5); tail.lineTo(-7, 9); tail.lineTo(-1, 5);
                s.draw(tail);
                for (int dot = 0; dot < 3; dot++) {
                    double bob = Math.sin(t * 6 - dot) * 0.8;
                    s.fill(new Ellipse2D.Double(-5.5 + dot * 4.5, -2.5 + bob, 2.4, 2.4));
                }
            }
            case PAGE -> {
                Path2D page = new Path2D.Double();
                page.moveTo(-7, -10); page.lineTo(3, -10); page.lineTo(8, -5); page.lineTo(8, 10); page.lineTo(-7, 10); page.closePath();
                s.draw(page);
                for (int line = 0; line < 3; line++) s.draw(new Line2D.Double(-4, -2 + line * 4, 5, -2 + line * 4));
            }
            case HEART -> {
                double beat = 1 + 0.08 * Math.max(0, Math.sin(t * 7));
                s.scale(beat, beat);
                Path2D heart = new Path2D.Double();
                heart.moveTo(0, 8);
                heart.curveTo(-12, 0, -9, -10, 0, -4);
                heart.curveTo(9, -10, 12, 0, 0, 8);
                s.fill(heart);
            }
            case PIN -> {
                Path2D pin = new Path2D.Double();
                pin.moveTo(0, 10);
                pin.curveTo(-9, 0, -8, -10, 0, -10);
                pin.curveTo(8, -10, 9, 0, 0, 10);
                s.draw(pin);
                s.draw(new Ellipse2D.Double(-3, -6, 6, 6));
            }
            case VOICE -> {
                double[] heights = {6, 12, 18, 12, 6};
                for (int bar = 0; bar < heights.length; bar++) {
                    double h = heights[bar] * (0.6 + 0.4 * Math.abs(Math.sin(t * 5 + bar * 1.3)));
                    s.draw(new Line2D.Double(-8 + bar * 4, -h / 2, -8 + bar * 4, h / 2));
                }
            }
        }
        s.dispose();
    }
}
