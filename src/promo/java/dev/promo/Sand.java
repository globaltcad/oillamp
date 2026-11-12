package dev.promo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.SplittableRandom;

import dev.gui.model.Palette;

/// The ground behind the window while the app is shown: the night grown light, as dusk, and
/// sand in it, grain by grain, carried gently by the wind. The air the sand is in flows as a
/// fluid does: it is turned and folded by slow, wide eddies, and those by smaller ones, so that
/// the sand is drawn out into veils and streams that bend and part and come together, without
/// ever clumping. All of it is in depth, seen in perspective: what is near is larger, brighter
/// and goes by faster; what is far is fine and faint.
///
/// Where every grain is depends on nothing but the film's clock, so the sand goes on through
/// cuts between scenes, and a still shows it as the film does.
final class Sand {

    private Sand() {}

    private static final int W = Film.WIDTH, H = Film.HEIGHT;

    private static final BufferedImage CANVAS = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private static final int[] PIXELS = ((DataBufferInt) CANVAS.getRaster().getDataBuffer()).getData();
    /// How much sand there is at each pixel of the picture being made.
    private static final float[] SAND = new float[W * H];
    /// The same, four pixels to one and blurred: where there is much sand, it is a soft veil of light.
    private static final int VW = W / 2, VH = H / 2;
    private static final float[] VEIL = new float[VW * VH], BLURRED = new float[VW * VH];

    private static final Color NIGHT = Draw.mix(Palette.DARK.NIGHT(), Color.BLACK, 0.35);
    private static final Color GRAIN = new Color(0xf4, 0xd6, 0xa6);
    /// The dusk: dark above, warmer and lighter towards the ground, lightest a little below the
    /// middle, where the window is.
    private static final int[] DUSK = new int[W * H];

    static {
        Color high = new Color(0x2e, 0x27, 0x3b), low = new Color(0x60, 0x49, 0x40), warm = new Color(0x78, 0x5a, 0x45);
        for (int y = 0; y < H; y++) {
            Color row = Draw.mix(high, low, Math.pow(y / (double) H, 1.2));
            for (int x = 0; x < W; x++) {
                double dx = (x - W / 2.0) / (W * 0.55), dy = (y - H * 0.58) / (H * 0.7);
                double glow = Math.max(0, 1 - Math.sqrt(dx * dx + dy * dy));
                Color c = Draw.mix(row, warm, 0.55 * glow * glow);
                DUSK[y * W + x] = c.getRGB() & 0xffffff;
            }
        }
    }

    // ─── the grains ────────────────────────────────────────────────────────────────────────

    private static final int GRAINS = 340_000;
    /// How far the sand's space reaches across, from the middle; a grain the wind takes out at
    /// one side comes in again at the other, out of sight.
    private static final double ACROSS = 2.3;

    /// Where the sheets of sand lie in still air, from the top of the picture down.
    private static final double[] SHEETS = {-0.85, -0.35, 0.1, 0.5, 0.95};

    /// For each grain: where it would be in still air, how fast the wind takes it, and how bright it is.
    private static final float[] X = new float[GRAINS], Y = new float[GRAINS], Z = new float[GRAINS], PACE = new float[GRAINS], BRIGHT = new float[GRAINS];

    static {
        SplittableRandom random = new SplittableRandom(0x5a17d);
        for (int i = 0; i < GRAINS; i++) {
            X[i] = (float) ((random.nextDouble() * 2 - 1) * ACROSS);
            Z[i] = (float) (random.nextDouble() * 2.9 - 1.25);
            PACE[i] = (float) (0.8 + 0.4 * random.nextDouble());
            if (i % 10 < 7) {
                // Most of the sand in thin sheets, which the eddies bend and fold into veils.
                int sheet = i % SHEETS.length;
                Y[i] = (float) (SHEETS[sheet] + gauss(random) * 0.05 + 0.08 * Z[i] * (sheet % 2 == 0 ? 1 : -1));
                BRIGHT[i] = (float) (0.55 + 0.45 * random.nextDouble());
            } else {
                // The rest a haze, all through the air.
                Y[i] = (float) (random.nextDouble() * 2.8 - 1.4);
                BRIGHT[i] = (float) (0.2 + 0.4 * Math.pow(random.nextDouble(), 2));
            }
        }
    }

    private static double gauss(SplittableRandom random) {
        double u = Math.max(1e-9, random.nextDouble()), v = random.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v) * 0.5;
    }

    /// A sine from a table: the sand asks for millions of them a picture.
    private static final int TURN = 4096;
    private static final float[] SINE = new float[TURN + 1];
    static {
        for (int i = 0; i <= TURN; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / TURN);
    }
    private static float sin(double a) {
        double at = a * (TURN / (2 * Math.PI));
        double floor = Math.floor(at);
        int i = (int) floor & (TURN - 1);
        float f = (float) (at - floor);
        return SINE[i] + (SINE[i + 1] - SINE[i]) * f;
    }

    // ─── painting ──────────────────────────────────────────────────────────────────────────

    /// Paints the ground, `lit` from the night (0) to dusk with sand in it (1), on the film's
    /// clock at `time`. Once `drawn` is above 0, all of it is drawn into the film's point
    /// `into`, swirling, light and sand together, until at 1 there is only the night.
    static void paint(Graphics2D g, double time, double lit, Point2D into, double drawn) {
        if (lit <= 0.001 || drawn >= 0.999) {
            g.setColor(NIGHT);
            g.fillRect(0, 0, W, H);
            return;
        }
        Arrays.fill(SAND, 0f);
        Arrays.fill(VEIL, 0f);
        double cx = into.getX(), cy = into.getY(), fade = lit * (1 - Draw.ease(drawn, 0.8, 1));
        double t = time;
        for (int i = 0; i < GRAINS; i++) {
            double z0 = Z[i], near = 3.0 / (3.0 + z0);
            // The wind: across, faster the nearer, as it seems to the eye.
            double x0 = X[i] + 0.07 * PACE[i] * near * t;
            x0 = ((x0 + ACROSS) % (2 * ACROSS) + 2 * ACROSS) % (2 * ACROSS) - ACROSS;
            double y0 = Y[i];
            // Wide, slow eddies turn the air...
            double x1 = x0 + 0.42 * sin(1.05 * y0 + 0.8 * z0 + 0.21 * t) + 0.12 * sin(2.2 * y0 - 1.6 * z0 + 0.29 * t + 1.3);
            double y1 = y0 + 0.34 * sin(1.25 * x0 + 0.6 * z0 - 0.18 * t + 2.1) + 0.1 * sin(2.5 * x0 + 1.8 * z0 + 0.25 * t);
            double z1 = z0 + 0.3 * sin(0.9 * x0 + 1.15 * y0 + 0.14 * t + 4.0);
            // ...and smaller ones fold what they turned.
            double x = x1 + 0.1 * sin(2.4 * y1 + 1.7 * z1 - 0.33 * t + 0.7);
            double y = y1 + 0.09 * sin(2.7 * x1 - 1.3 * z1 + 0.37 * t + 2.9);
            double z = z1 + 0.08 * sin(2.2 * x1 + 2.0 * y1 + 0.3 * t);
            // Fading out where it goes round, out of sight, so that none appears from nothing.
            double edge = Draw.clamp((ACROSS - Math.abs(x0)) / 0.25);
            // Two great, slow eddies drift through it, and wind the veils round themselves.
            for (int e = 0; e < 2; e++) {
                double ex = (e == 0 ? -0.7 : 0.8) + 0.5 * sin(0.05 * t + e * 2.5), ey = (e == 0 ? -0.15 : 0.3) + 0.25 * sin(0.07 * t + e * 1.1 + 1);
                double dx = x - ex, dy = y - ey, r2 = dx * dx + dy * dy;
                double turn = (e == 0 ? 1.4 : -1.2) * Math.exp(-r2 / 0.22);
                if (turn > 0.01 || turn < -0.01) {
                    double c = sin(turn + Math.PI / 2), sn = sin(turn);
                    x = ex + dx * c - dy * sn;
                    y = ey + dx * sn + dy * c;
                }
            }
            grain(x, y, z, BRIGHT[i] * 0.4 * fade * edge, cx, cy, drawn);
        }
        blur();
        compose(lit, cx, cy, drawn);
        g.drawImage(CANVAS, 0, 0, null);
    }

    /// Adds a grain at `x`, `y`, `z` of the sand's space to the picture, as bright as `bright`
    /// and as near as it is; drawn towards the film's `cx`, `cy` as much as `drawn` says.
    private static void grain(double x, double y, double z, double bright, double cx, double cy, double drawn) {
        if (bright <= 0 || z <= -2.5) return;
        double near = 3.0 / (3.0 + z);
        double sx = W / 2.0 + x * 760 * near, sy = H / 2.0 + y * 760 * near;
        // Far grains are fine and faint, as in a haze; near ones bright.
        bright *= near * near * (0.35 + 0.65 * Draw.clamp((1.6 - z) / 2.9));
        if (drawn > 0) {
            double dx = sx - cx, dy = sy - cy, r = Math.sqrt(dx * dx + dy * dy);
            // What is nearer the point goes first, round and round, faster as it comes in.
            double pull = Draw.ease(drawn * 1.7 - r / 2600, 0, 1);
            double turn = 2.6 * pull * pull, keep = 1 - pull;
            double c = Math.cos(turn), s = Math.sin(turn);
            sx = cx + (dx * c - dy * s) * keep;
            sy = cy + (dx * s + dy * c) * keep;
            bright *= 1 + 1.5 * pull;
        }
        if (sx < 1 || sy < 1 || sx >= W - 2 || sy >= H - 2) return;
        int ix = (int) sx, iy = (int) sy;
        float fx = (float) (sx - ix), fy = (float) (sy - iy), v = (float) bright;
        int at = iy * W + ix;
        if (near > 1.35) {
            // Near, a grain is larger than a pixel: spread a little wider, and softer.
            v *= 0.5f;
            SAND[at - 1] += v * 0.25f; SAND[at + 2] += v * 0.25f; SAND[at - W] += v * 0.25f; SAND[at + 2 * W] += v * 0.25f;
        }
        VEIL[(iy >> 1) * VW + (ix >> 1)] += v;
        // Spread over the four pixels it lies between, so that it moves smoothly.
        SAND[at] += v * (1 - fx) * (1 - fy);
        SAND[at + 1] += v * fx * (1 - fy);
        SAND[at + W] += v * (1 - fx) * fy;
        SAND[at + W + 1] += v * fx * fy;
    }

    /// How much light sand gives, by how much of it there is: a little alone, never more than
    /// all, however much is together.
    private static final float[] GLOWS = new float[1024];
    static {
        for (int i = 0; i < GLOWS.length; i++) GLOWS[i] = (float) (0.85 * (1 - Math.exp(-i / 256.0 * 1.6)));
    }

    /// How much light the veils give, for the sand that makes them.
    private static final float VEIL_LIGHT = 1.3f;

    /// Blurs the veils: twice across and twice down, each time over nine of them.
    private static void blur() {
        float[] from = VEIL, to = BLURRED;
        for (int pass = 0; pass < 4; pass++) {
            boolean across = pass % 2 == 0;
            int r = 4;
            for (int line = 0; line < (across ? VH : VW); line++) {
                int length = across ? VW : VH, step = across ? 1 : VW, start = across ? line * VW : line;
                float sum = 0;
                for (int k = -r; k <= r; k++) sum += from[start + Math.clamp(k, 0, length - 1) * step];
                for (int k = 0; k < length; k++) {
                    to[start + k * step] = sum / (2 * r + 1);
                    sum += from[start + Math.min(length - 1, k + r + 1) * step] - from[start + Math.max(0, k - r) * step];
                }
            }
            float[] swap = from; from = to; to = swap;
        }
        // Four passes end where they began.
        if (from != BLURRED) System.arraycopy(from, 0, BLURRED, 0, from.length);
    }

    /// The night, the dusk as far as it is lit there, and the sand on it.
    private static void compose(double lit, double cx, double cy, double drawn) {
        int nr = NIGHT.getRed(), ng = NIGHT.getGreen(), nb = NIGHT.getBlue();
        int sr = GRAIN.getRed(), sg = GRAIN.getGreen(), sb = GRAIN.getBlue();
        // Drawn in, the dusk is lit only within a circle round the point, which grows small.
        double reach = 2400 * Math.pow(1 - drawn, 1.6), soft = Math.max(1, reach * 0.55);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int at = y * W + x;
                double m = lit;
                if (drawn > 0) {
                    double dx = x - cx, dy = y - cy;
                    m *= Draw.clamp((reach - Math.sqrt(dx * dx + dy * dy)) / soft);
                }
                int dusk = DUSK[at];
                int r = (int) (nr + (((dusk >> 16) & 0xff) - nr) * m), gg = (int) (ng + (((dusk >> 8) & 0xff) - ng) * m), b = (int) (nb + ((dusk & 0xff) - nb) * m);
                float sand = SAND[at] * 0.8f + BLURRED[(y >> 1) * VW + (x >> 1)] * VEIL_LIGHT;
                if (sand > 0) {
                    float glow = GLOWS[Math.min(1023, (int) (sand * 256))];
                    r += (int) ((sr - r) * glow);
                    gg += (int) ((sg - gg) * glow);
                    b += (int) ((sb - b) * glow);
                }
                PIXELS[at] = (r << 16) | (gg << 8) | b;
            }
        }
    }
}
