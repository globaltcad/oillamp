package dev.gui.view;

import java.awt.Color;

/// How well a shape stands out from the ground it is on, and an edge for it where it does not.
///
/// A palette is chosen for its text, which stands out on every ground of it. Pictures and
/// surfaces bring colours of their own, such as a genie's body or a bubble, and these may come
/// out as light as the ground they are on, or as dark: a lemon genie on the paper of the light
/// palette, say. Rather than a rule for each such pair, the views measure the contrast, the way
/// the Web Content Accessibility Guidelines do, and give the shape an edge as strong as it lacks.
final class ContrastUtil {

    private ContrastUtil() {}

    /// The contrast at which a shape stands out by itself: three to one, what the guidelines ask
    /// of parts of a user interface that are not text.
    static final double ENOUGH = 3.0;

    /// The ink an edge on a light ground is mixed towards: the deep plum of the dark palette.
    private static final Color INK = new Color(0x1f, 0x16, 0x26);

    /// The relative luminance of `colour`, from 0 for black to 1 for white, as the guidelines
    /// define it: the light the screen gives off, not how light the colour looks.
    static double luminance(Color colour) {
        return 0.2126 * linear(colour.getRed()) + 0.7152 * linear(colour.getGreen()) + 0.0722 * linear(colour.getBlue());
    }

    /// The contrast between `one` and `other`, from 1, none, to 21, black on white.
    static double ratio(Color one, Color other) {
        double a = luminance(one);
        double b = luminance(other);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /// How much `shape` lacks of standing out on `ground`: 0 at [#ENOUGH] contrast or more, and
    /// up to 1 where the two are equally light.
    static double lack(Color shape, Color ground) {
        return Math.clamp((ENOUGH - ratio(shape, ground)) / (ENOUGH - 1), 0, 1);
    }

    /// An edge for `shape` on `ground`: a deep shade of the shape itself on a light ground, and
    /// a pale one on a dark ground, so that it reads as the shape's own outline or shadow rather
    /// than as a frame. It is as opaque as `strongest` where the shape lacks all contrast, less
    /// the less it lacks, and transparent where the shape stands out by itself.
    static Color edge(Color shape, Color ground, int strongest) {
        double lack = lack(shape, ground);
        boolean lightGround = luminance(ground) > 0.18;
        Color shade = mix(shape, lightGround ? INK : Color.WHITE, lightGround ? 0.6 : 0.5);
        return new Color(shade.getRed(), shade.getGreen(), shade.getBlue(), (int) Math.round(strongest * lack));
    }

    /// A fine rim for a surface of `fill`, such as a bubble or a sheet, lying on `ground`: the
    /// [#edge] of the fill, at most as opaque as `strongest`, but only on a light ground. On a
    /// dark one, a light line around a surface reads as a frame, and the dark sets a surface
    /// apart well enough by itself; on paper, a surface nearly as light as the paper needs its
    /// edge drawn.
    static Color rim(Color fill, Color ground, int strongest) {
        return luminance(ground) > 0.18 ? edge(fill, ground, strongest) : new Color(0, 0, 0, 0);
    }

    /// `from`, turned `part` of the way into `to`.
    static Color mix(Color from, Color to, double part) {
        double p = Math.clamp(part, 0, 1);
        return new Color(
            (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * p),
            (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * p),
            (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * p)
        );
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
