package dev.promo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;

import dev.gui.model.Palette;

/// What scenes hand over to one another with: words drawn into a point of light, and the point
/// bursting into what comes next.
final class Effects {

    private Effects() {}

    /// Where the question of the first scene is drawn to, and where the window's first button
    /// bursts from, in the film's pixels: both scenes know it, so either can be filmed alone.
    static final Point2D.Double SEED = new Point2D.Double(1400, 770);

    /// Where the window draws itself into a point, and the genies one might ask burst from.
    static final Point2D.Double WISHES = new Point2D.Double(960, 520);

    /// Where those genies go as their scene ends: into the lamp of the film's last picture.
    static final Point2D.Double LAMP = new Point2D.Double(505, 455);

    /// Letters drawn into `point`, from `from` to `to` seconds: each sets out a little after the
    /// one before, swings around the point as it nears, and shrinks to nothing in it.
    static Letters.Holding drawnInto(Point2D point, double t, double from, double to) {
        return letter -> {
            double delay = (letter.index() * 0.618 % 1.0) * 0.35 * (to - from);
            double p = Draw.clamp((t - from - delay) / ((to - from) * 0.65));
            if (p <= 0) return Letters.Held.STILL;
            double pull = p * p * (3 - 2 * p), near = Math.pow(1 - pull, 1.6);
            double rx = letter.x() - point.getX(), ry = letter.y() - point.getY(), swing = 2.4 * pull * pull;
            double x = point.getX() + (rx * Math.cos(swing) - ry * Math.sin(swing)) * near;
            double y = point.getY() + (rx * Math.sin(swing) + ry * Math.cos(swing)) * near;
            return new Letters.Held(x - letter.x(), y - letter.y(), Math.max(0, 1 - pull * 0.92), swing * 1.5, pull > 0.9 ? (1 - pull) * 10 : 1);
        };
    }

    /// A point of light at `point`, `strength` strong, from 0 to 1 and a little beyond.
    static void light(Graphics2D g, Point2D point, double strength) {
        if (strength <= 0) return;
        Color flame = Palette.DARK.FLAME(), hot = new Color(0xff, 0xf1, 0xc2);
        float halo = (float) (30 + 190 * strength);
        g.setPaint(new RadialGradientPaint(point, halo, new float[] {0f, 0.25f, 1f},
            new Color[] {Draw.alpha(flame, 0.55 * Math.min(1, strength)), Draw.alpha(flame, 0.16 * Math.min(1, strength)), Draw.alpha(flame, 0)}));
        g.fill(new Ellipse2D.Double(point.getX() - halo, point.getY() - halo, 2 * halo, 2 * halo));
        double core = 3 + 9 * strength;
        g.setColor(Draw.alpha(hot, Math.min(1, strength * 1.6)));
        g.fill(new Ellipse2D.Double(point.getX() - core, point.getY() - core, 2 * core, 2 * core));
    }

    /// How long the light takes to run once around a border: it does so once, and is gone.
    static final double LAP = 1.4;

    /// A border around `shape` in the flame's colour, `shown` visible, with a light that runs
    /// around it once, setting out and arriving gently, `since` seconds after it began.
    static void border(Graphics2D g, java.awt.Shape shape, double since, double shown) {
        Color flame = Palette.DARK.FLAME(), hot = new Color(0xff, 0xf1, 0xc2);
        for (int ring = 3; ring >= 1; ring--) {
            g.setStroke(new BasicStroke(ring * 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Draw.alpha(flame, 0.07 * shown));
            g.draw(shape);
        }
        g.setStroke(new BasicStroke(2.2f));
        g.setColor(Draw.alpha(flame, 0.75 * shown));
        g.draw(shape);
        // The outline as points, to walk along it.
        java.util.List<Point2D> points = new java.util.ArrayList<>();
        double[] at = new double[6];
        for (java.awt.geom.PathIterator path = new java.awt.geom.FlatteningPathIterator(shape.getPathIterator(null), 0.5); !path.isDone(); path.next())
            if (path.currentSegment(at) != java.awt.geom.PathIterator.SEG_CLOSE) points.add(new Point2D.Double(at[0], at[1]));
        // The light grows out of the start, runs once around, and fades into the border as it arrives.
        double light = Draw.ease(since, 0, 0.25) * (1 - Draw.ease(since, LAP * 0.7, LAP + 0.15));
        if (points.size() < 2 || light <= 0) return;
        double[] along = new double[points.size() + 1];
        for (int i = 0; i < points.size(); i++) along[i + 1] = along[i] + points.get(i).distance(points.get((i + 1) % points.size()));
        double around = along[points.size()], head = Draw.ease(since, 0, LAP) * around, tail = Math.min(around * 0.34, 700) * Math.min(1, since / 0.3);
        int steps = 90;
        for (int width = 3; width >= 1; width--) {
            for (int step = 0; step < steps; step++) {
                double near = 1 - step / (double) steps;
                Point2D a = where(points, along, head - tail * (step + 1) / steps), b = where(points, along, head - tail * step / steps);
                g.setStroke(new BasicStroke((float) (width == 3 ? 16 * near : width == 2 ? 7 * near : 3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(Draw.alpha(width == 1 ? hot : flame, (width == 3 ? 0.06 : width == 2 ? 0.18 : 0.95) * near * near * shown * light));
                g.draw(new Line2D.Double(a, b));
            }
        }
    }

    /// The point `far` along the outline `points`, whose lengths up to each point are `along`.
    private static Point2D where(java.util.List<Point2D> points, double[] along, double far) {
        double around = along[points.size()], s = ((far % around) + around) % around;
        int i = 0;
        while (i < points.size() - 1 && along[i + 1] < s) i++;
        Point2D a = points.get(i), b = points.get((i + 1) % points.size());
        double part = along[i + 1] - along[i] <= 0 ? 0 : (s - along[i]) / (along[i + 1] - along[i]);
        return new Point2D.Double(Draw.mix(a.getX(), b.getX(), part), Draw.mix(a.getY(), b.getY(), part));
    }

    /// The point at `point` bursting, `since` seconds ago: a ring that widens and fades, and
    /// sparks that fly out, over `reach` pixels.
    static void burst(Graphics2D g, Point2D point, double since, double reach) {
        if (since < 0 || since > 0.9) return;
        Color flame = Palette.DARK.FLAME(), hot = new Color(0xff, 0xf1, 0xc2);
        double out = Draw.arrive(since, 0, 0.9), left = 1 - since / 0.9;
        g.setStroke(new BasicStroke((float) (1 + 7 * left)));
        g.setColor(Draw.alpha(hot, 0.8 * left * left));
        double r = 12 + reach * out;
        g.draw(new Ellipse2D.Double(point.getX() - r, point.getY() - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke((float) (1 + 3 * left), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int spark = 0; spark < 22; spark++) {
            double angle = spark * 2.399963, far = reach * (0.75 + 0.9 * ((spark * 0.618) % 1.0)) * out;
            double tail = far - (18 + 30 * left) * left;
            g.setColor(Draw.alpha(spark % 3 == 0 ? hot : flame, 0.9 * left));
            g.draw(new Line2D.Double(point.getX() + Math.cos(angle) * Math.max(0, tail), point.getY() + Math.sin(angle) * Math.max(0, tail),
                point.getX() + Math.cos(angle) * far, point.getY() + Math.sin(angle) * far));
        }
    }
}
