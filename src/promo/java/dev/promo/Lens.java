package dev.promo;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.stream.IntStream;

/// What the camera's lens does to a picture of the window: seen at an angle, the window lies in
/// the picture as a surface with depth, nearer at one edge than at the other; and a lens that is
/// sharp only in a band leaves what is nearer and farther soft, as a photograph of something
/// small does.
final class Lens {

    private Lens() {}

    /// How the window is seen.
    ///
    /// @param pitch radians the window leans back, its top away from the viewer
    /// @param yaw   radians it is turned, its right edge away from the viewer
    /// @param soft  how soft what is far from the sharp band is, from 0 to 1
    record Tilt(double pitch, double yaw, double soft) {

        static final Tilt NONE = new Tilt(0, 0, 0);

        boolean flat() { return Math.abs(pitch) < 1e-4 && Math.abs(yaw) < 1e-4 && soft < 1e-3; }

        Tilt toward(Tilt other, double progress) {
            return new Tilt(Draw.mix(pitch, other.pitch, progress), Draw.mix(yaw, other.yaw, progress), Draw.mix(soft, other.soft, progress));
        }
    }

    /// How far the viewer is from the picture, in its pixels: nearer, and depth shows more.
    private static final double DISTANCE = 2600;

    private static BufferedImage seen = new BufferedImage(Film.WIDTH, Film.HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE);

    /// A picture as large as the film, with nothing in it, to paint the window into before it
    /// is [#seen] at an angle.
    static BufferedImage plane() { return new BufferedImage(Film.WIDTH, Film.HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE); }

    /// Paints `plane` onto `g` as `tilt` sees it, turned about the film's point `pivotX`, `pivotY`.
    static void seen(Graphics2D g, BufferedImage plane, Tilt tilt, double pivotX, double pivotY) {
        int w = plane.getWidth(), h = plane.getHeight();
        if (seen.getWidth() != w || seen.getHeight() != h) seen = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] from = ((DataBufferInt) plane.getRaster().getDataBuffer()).getData();
        int[] to = ((DataBufferInt) seen.getRaster().getDataBuffer()).getData();
        // A point x, y of the window's surface, from the pivot, is turned about the upright axis
        // by the yaw, then leans back by the pitch; what it then is in depth makes it smaller.
        double cp = Math.cos(tilt.pitch()), sp = Math.sin(tilt.pitch()), cy = Math.cos(tilt.yaw()), sy = Math.sin(tilt.yaw());
        double f = DISTANCE;
        double[] m = {
            f * cy, 0, 0,
            f * sp * sy, f * cp, 0,
            cp * sy, -sp, f
        };
        double[] back = inverse(m);
        IntStream.range(0, h).parallel().forEach(row -> {
            double v = row - pivotY;
            for (int column = 0; column < w; column++) {
                double u = column - pivotX;
                double z = back[6] * u + back[7] * v + back[8];
                double x = (back[0] * u + back[1] * v + back[2]) / z + pivotX, y = (back[3] * u + back[4] * v + back[5]) / z + pivotY;
                to[row * w + column] = z <= 0 ? 0 : sample(from, w, h, x, y);
            }
        });
        g.drawImage(seen, 0, 0, null);
        if (tilt.soft() > 0) soften(g, seen, pivotY, tilt.soft());
    }

    /// The colour of `pixels` at the point `x`, `y`, between its four pixels there.
    private static int sample(int[] pixels, int w, int h, double x, double y) {
        if (x < -1 || y < -1 || x > w || y > h) return 0;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        int a = at(pixels, w, h, x0, y0), b = at(pixels, w, h, x0 + 1, y0), c = at(pixels, w, h, x0, y0 + 1), d = at(pixels, w, h, x0 + 1, y0 + 1);
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            double top = ((a >>> shift) & 0xff) * (1 - fx) + ((b >>> shift) & 0xff) * fx;
            double bottom = ((c >>> shift) & 0xff) * (1 - fx) + ((d >>> shift) & 0xff) * fx;
            result |= ((int) Math.round(top * (1 - fy) + bottom * fy) & 0xff) << shift;
        }
        return result;
    }

    private static int at(int[] pixels, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : pixels[y * w + x];
    }

    /// Paints `picture` again, soft, above and below a band around `sharpY` that stays sharp.
    private static void soften(Graphics2D g, BufferedImage picture, double sharpY, double soft) {
        int w = picture.getWidth(), h = picture.getHeight();
        // Made small and large again, a picture is soft; twice, softer and without steps.
        BufferedImage softened = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        BufferedImage small = new BufferedImage(w / 6, h / 6, BufferedImage.TYPE_INT_ARGB_PRE), smaller = new BufferedImage(w / 12, h / 12, BufferedImage.TYPE_INT_ARGB_PRE);
        draw(picture, small);
        draw(small, smaller);
        draw(smaller, small);
        draw(small, softened);
        Graphics2D s = softened.createGraphics();
        s.setComposite(AlphaComposite.DstIn);
        double band = h * 0.16, reach = h * 0.42;
        Color clear = new Color(0, 0, 0, 0), full = new Color(0, 0, 0, (int) Math.round(255 * Draw.clamp(soft)));
        s.setPaint(new GradientPaint(0, (float) (sharpY - band - reach), full, 0, (float) (sharpY - band), clear));
        s.fillRect(0, 0, w, (int) Math.max(0, sharpY));
        s.setPaint(new GradientPaint(0, (float) (sharpY + band), clear, 0, (float) (sharpY + band + reach), full));
        s.fillRect(0, (int) Math.max(0, sharpY), w, h);
        s.dispose();
        g.drawImage(softened, 0, 0, null);
    }

    private static void draw(BufferedImage from, BufferedImage to) {
        Graphics2D g = to.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(from, 0, 0, to.getWidth(), to.getHeight(), null);
        g.dispose();
    }

    private static double[] inverse(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        return new double[] {
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det
        };
    }
}
