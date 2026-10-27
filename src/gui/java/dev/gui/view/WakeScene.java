package dev.gui.view;

import dev.gui.model.Genie;

import java.util.UUID;

/// The wake picture's presentation, separate from the lamp's real lifecycle. While the sandbox
/// starts, the flame catches and burns; only once it is ready does the genie emerge. A fast
/// start still shows the emergence, and a failed start or another genie cancels it immediately.
record WakeScene(UUID id, Genie.Phase phase, double seconds, double released) {

    private static final double EMERGING = 4.6;
    private static final double TAKE = 2.2;
    private static final double HELLO = 1.6;

    static WakeScene of(Genie genie) {
        return new WakeScene(genie.id(), genie.phase(), 0, -1);
    }

    WakeScene follows(Genie genie) {
        if (!id.equals(genie.id())) return of(genie);
        if (phase == genie.phase()) return this;
        if (phase == Genie.Phase.WAKING && genie.phase().isAwake())
            return new WakeScene(id, genie.phase(), seconds, seconds);
        if (phase.isAwake() && genie.phase().isAwake())
            return new WakeScene(id, genie.phase(), seconds, released);
        return of(genie);
    }

    WakeScene after(double step) {
        return new WakeScene(id, phase, seconds + Math.max(0, step), released);
    }

    boolean emerging() {
        return phase.isAwake() && released >= 0 && seconds - released < TAKE + HELLO;
    }

    boolean moving() {
        return phase == Genie.Phase.WAKING || emerging();
    }

    /// Seconds on the shared welcome scene's timeline. The lamp is already there when waking
    /// begins. A long start stays in its calm-burning interval, with a flickering flame.
    double moment() {
        if (phase == Genie.Phase.WAKING) {
            double caught = 1 + 2 * seconds;
            return caught < EMERGING ? caught : 3.6 + (seconds % 0.9);
        }
        if (phase.isAwake())
            return released < 0
                ? WelcomeScene.FORMED + 1 + seconds
                : EMERGING
                  + Math.min(1, (seconds - released) / TAKE) * (WelcomeScene.FORMED + 1 - EMERGING)
                  + Math.max(0, seconds - released - TAKE);
        return 1;
    }

    WelcomeScene.Play play() {
        GenieSvgUtil.Appearance appearance = GenieSvgUtil.appearanceOf(id);
        return new WelcomeScene.Play(appearance, 0, appearance, -1);
    }
}
