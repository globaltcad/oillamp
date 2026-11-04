package dev.gui.view;

/// How far an explanation that folds away has unfolded, from 0, folded, to 1, open. It unfolds
/// in two acts, so that nothing pops in: first the panel opens, easing in and out, and then,
/// as its own last act, the words fade in, easing out into the still picture. Folding away
/// plays the same backwards: the words fade first, then the panel closes.
///
///      progress   0 ─────────── HEIGHT_UNTIL ──── 1
///      the panel  opens ─────────┘
///      the words             WORDS_FROM ──── fade in
///
/// @param progress how far it has unfolded, from 0 to 1
record Unfolding(double progress) {

    static final Unfolding FOLDED = new Unfolding(0);
    /// How long unfolding takes, both acts, and folding away too.
    static final double SECONDS = 1.1;
    private static final double HEIGHT_UNTIL = 0.6;
    private static final double WORDS_FROM = 0.45;

    /// This, `seconds` later, on its way to open or to folded.
    Unfolding toward(boolean open, double seconds) {
        double step = seconds / SECONDS;
        return new Unfolding(open ? Math.min(1, progress + step) : Math.max(0, progress - step));
    }

    /// Whether it has arrived where it is on its way to.
    boolean arrived(boolean open) { return progress == (open ? 1 : 0); }

    /// How much of its height the panel has, from 0 to 1.
    double height() { return easeInAndOut(part(progress / HEIGHT_UNTIL)); }

    /// How much the words have faded in, from 0 to 1.
    double words() { return easeOut(part((progress - WORDS_FROM) / (1 - WORDS_FROM))); }

    private static double part(double of) { return Math.max(0, Math.min(1, of)); }

    private static double easeInAndOut(double t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

    private static double easeOut(double t) { return 1 - Math.pow(1 - t, 3); }
}
