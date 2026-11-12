package dev.promo;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/// One scene of the film: so many seconds, painted from its own clock, which starts at zero.
/// What happens in it at set moments, such as a message being sent, are its cues.
abstract class Scene {

    /// Something that happens once, `at` seconds into the scene.
    record Cue(double at, Runnable what) {}

    final String name;
    final double seconds;
    private final List<Cue> cues = new ArrayList<>();
    private int next = 0;

    Scene(String name, double seconds) {
        this.name = name;
        this.seconds = seconds;
    }

    /// Has `what` happen `at` seconds into the scene. Cues are given in the order of their times.
    final void at(double at, Runnable what) { cues.add(new Cue(at, what)); }

    /// Sets the scene up from nothing, so that it can be filmed without the scenes before it.
    void begin() {}

    /// Paints the scene as it is `t` seconds in, on a picture [Film#WIDTH] by [Film#HEIGHT].
    abstract void paint(Graphics2D g, double t);

    void end() {}

    /// Starts the scene over: its cues are yet to come.
    final void rewind() {
        cues.sort((a, b) -> Double.compare(a.at(), b.at()));
        next = 0;
    }

    /// Has everything happen that is due `t` seconds in.
    final void due(double t) {
        while (next < cues.size() && cues.get(next).at() <= t) cues.get(next++).what().run();
    }
}
