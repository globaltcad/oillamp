package dev.promo;

/// How a part of the window is held in a picture of the film: where it is from its own place, in
/// the window's units, how large, how far turned, and how visible. [#LANDED] is a part in its
/// place, as the window paints it; anything else is painted as a layer of its own, above the
/// window, which is then painted without it.
///
/// @param dx    units to the right of its place
/// @param dy    units below its place
/// @param scale its size, of its own
/// @param turn  radians turned, clockwise
/// @param alpha how visible, from 0 to 1
record Pose(double dx, double dy, double scale, double turn, double alpha) {

    static final Pose LANDED = new Pose(0, 0, 1, 0, 1);
    /// Not there yet: the window is painted without it, and it is not painted.
    static final Pose AWAY = new Pose(0, 0, 1, 0, 0);

    boolean landed() { return equals(LANDED); }

    /// On the way from this pose to `other`, `progress` of it.
    Pose toward(Pose other, double progress) {
        double p = progress;
        if (p <= 0) return this;
        return new Pose(Draw.mix(dx, other.dx, p), Draw.mix(dy, other.dy, p), Draw.mix(scale, other.scale, p),
            Draw.mix(turn, other.turn, p), Draw.clamp(Draw.mix(alpha, other.alpha, p)));
    }

    /// A part that comes from `dx`, `dy` units away and lands in its place, between `from` and
    /// `to` seconds: it fades in as it sets out, overshoots its place a little, and settles.
    static Pose landing(double t, double from, double to, double dx, double dy) {
        if (t <= from) return AWAY;
        if (t >= to) return LANDED;
        double x = (t - from) / (to - from), left = 1 - Draw.spring(x);
        return new Pose(dx * left, dy * left, 1, 0, Draw.clamp(x * 3.5));
    }

    /// A part that grows into its place out of nothing, between `from` and `to` seconds, a
    /// little past its size and back.
    static Pose popping(double t, double from, double to) {
        if (t <= from) return AWAY;
        if (t >= to) return LANDED;
        double x = (t - from) / (to - from);
        return new Pose(0, 0, Math.max(0.01, Draw.spring(x)), 0, Draw.clamp(x * 4));
    }

    /// A part that drops onto the window from above it, from `from` seconds on: larger at first,
    /// as what is nearer is, it lands in its place on a spring.
    static Pose dropping(double t, double from) {
        double to = from + 0.7;
        if (t <= from) return AWAY;
        if (t >= to) return LANDED;
        double x = (t - from) / (to - from), left = 1 - Draw.spring(x);
        return new Pose(0, -22 * left, 1 + 0.75 * left, 0, Draw.clamp(x * 5));
    }

    /// A part that fades in where it is, between `from` and `to` seconds.
    static Pose fading(double t, double from, double to) {
        if (t <= from) return AWAY;
        if (t >= to) return LANDED;
        return new Pose(0, 0, 1, 0, Draw.ease(t, from, to));
    }
}
