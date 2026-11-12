package dev.promo;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.util.Map;

import dev.gui.model.Fonts;

/// What the scenes paint with: the film's type, and how things move.
final class Draw {

    private Draw() {}

    /// The app's own typeface, at `size` pixels of the film.
    static Font font(double size, boolean bold) {
        return new Font(Fonts.DEFAULT.FONT(), bold ? Font.BOLD : Font.PLAIN, 10).deriveFont((float) size);
    }

    /// The same, with its letters set `tracking` apart, in parts of its size.
    static Font font(double size, boolean bold, double tracking) {
        return font(size, bold).deriveFont(Map.of(TextAttribute.TRACKING, (float) tracking));
    }

    static Font mono(double size) {
        return new Font(Fonts.DEFAULT.MONO(), Font.PLAIN, 10).deriveFont((float) size);
    }

    static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    /// How far along `t` is from `from` to `to`, from 0 to 1, starting and arriving gently.
    static double ease(double t, double from, double to) {
        double x = clamp((t - from) / (to - from));
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    /// The same, as a camera moves that is in a hurry: off quickly, and a long, soft arrival.
    static double swift(double t, double from, double to) {
        double x = clamp((t - from) / (to - from)), off = x * x * (3 - 2 * x);
        return 1 - (1 - off) * (1 - off) * (1 - off * 0.4);
    }

    /// The same, arriving gently but starting at once.
    static double arrive(double t, double from, double to) {
        double x = 1 - clamp((t - from) / (to - from));
        return 1 - x * x * x;
    }

    /// From 0 to 1 as `x` goes from 0 to 1, as something on a spring arrives: quickly, a little
    /// past, and back.
    static double spring(double x) {
        double c = clamp(x);
        return 1 - Math.exp(-6.5 * c) * Math.cos(9.5 * c) * (1 - c);
    }

    static double mix(double a, double b, double progress) { return a + (b - a) * progress; }

    static Color mix(Color a, Color b, double progress) {
        double p = clamp(progress);
        return new Color(
            (int) Math.round(mix(a.getRed(), b.getRed(), p)),
            (int) Math.round(mix(a.getGreen(), b.getGreen(), p)),
            (int) Math.round(mix(a.getBlue(), b.getBlue(), p)),
            (int) Math.round(mix(a.getAlpha(), b.getAlpha(), p))
        );
    }

    static Color alpha(Color colour, double alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), (int) Math.round(colour.getAlpha() * clamp(alpha)));
    }

    /// How visible something is that comes from `in` over `fade` seconds and goes the same way
    /// before `out`.
    static double shown(double t, double in, double out, double fade) {
        return Math.min(ease(t, in, in + fade), 1 - ease(t, out - fade, out));
    }

    /// Paints with everything `alpha` as visible, and gives back what to restore.
    static Composite fade(Graphics2D g, double alpha) {
        Composite before = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) clamp(alpha)));
        return before;
    }

    /// Writes `text` with its middle at `x`, its baseline at `y`.
    static void centred(Graphics2D g, String text, double x, double y) {
        double width = g.getFontMetrics().getStringBounds(text, g).getWidth();
        g.drawString(text, (float) (x - width / 2), (float) y);
    }

    static double width(Graphics2D g, String text) {
        return g.getFontMetrics().getStringBounds(text, g).getWidth();
    }
}
