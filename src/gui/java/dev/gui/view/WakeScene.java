package dev.gui.view;

import dev.gui.model.Genie;

import java.util.UUID;

/// The wake picture's presentation, separate from the lamp's real lifecycle. While the sandbox
/// starts, the flame catches and burns; only once it is ready does the genie emerge. A fast
/// start still shows the emergence, and a failed start or another genie cancels it immediately.
///
/// Put to sleep, the same play runs backwards: the genie sinks into the flame it came from, and
/// the flame burns on, calmly, for as long as the sandbox takes to stop and the home to be saved.
/// Only once the lamp is really out does the flame dwindle onto the wick, slowly, and then a
/// wisp of smoke rises from it and settles: the last act, which ends the play gently. A fast
/// shutdown still shows the genie go back in.
///
/// @param seconds  how long the scene has played, since the genie began to wake or fall asleep
/// @param released when, in `seconds`, waking ended or the lamp went out; negative before that
record WakeScene(UUID id, Genie.Phase phase, double seconds, double released) {

    private static final double EMERGING = 4.6;
    private static final double TAKE = 2.2;
    private static final double HELLO = 1.6;
    /// How fast the play runs backwards as the genie falls asleep, in its seconds per second.
    private static final double BACKWARDS = 2;
    /// Where the flame burns calmly, on the welcome's timeline: the genie goes back into the
    /// flame until here, and the flame goes out from here.
    private static final double BURNING = 3.6;
    /// Where the flame is gone from the wick, on the welcome's timeline.
    private static final double OUT = 1.2;
    /// Where the lamp is cold, with a wisp of smoke above its wick, as a sleeping genie's is.
    private static final double COLD = 1;
    /// How long the genie takes to sink back into the flame.
    private static final double SINKING = (WelcomeScene.FORMED - BURNING) / BACKWARDS;
    /// How fast the flame dwindles once the lamp is out, in the welcome's seconds per second:
    /// slower than the genie sinks, as a flame starved of oil does.
    private static final double DWINDLING = 1.2;
    /// How long the flame takes to go out once the lamp is out.
    private static final double FLAME_OUT = (BURNING - OUT) / DWINDLING;
    /// How long the smoke then takes to rise and settle.
    private static final double SMOKING = 3;
    /// How long the whole ending takes, once the lamp is out.
    private static final double GOING_OUT = FLAME_OUT + SMOKING;

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
        // The genie goes back from wherever the picture is: formed, or still taking form.
        if (genie.phase() == Genie.Phase.FALLING_ASLEEP)
            return new WakeScene(id, genie.phase(), Math.max(0, (WelcomeScene.FORMED - moment()) / BACKWARDS), -1);
        if (phase == Genie.Phase.FALLING_ASLEEP && genie.phase() == Genie.Phase.ASLEEP)
            return new WakeScene(id, genie.phase(), seconds, seconds);
        return of(genie);
    }

    WakeScene after(double step) {
        return new WakeScene(id, phase, seconds + Math.max(0, step), released);
    }

    boolean emerging() {
        return phase.isAwake() && released >= 0 && seconds - released < TAKE + HELLO;
    }

    /// Whether the lamp is out, and the picture still shows the genie going back and the flame
    /// going out.
    boolean goingOut() {
        return phase == Genie.Phase.ASLEEP && released >= 0 && seconds - wentOut() < GOING_OUT;
    }

    /// How far the smoke of the flame that went out has risen, from 0, while the flame still
    /// dwindles, to 1, as it stands above a sleeping genie's lamp; negative when no flame goes
    /// out, and the lamp is painted as it is.
    double smoke() {
        if (!goingOut()) return -1;
        return Math.clamp((seconds - wentOut() - FLAME_OUT) / SMOKING, 0, 1);
    }

    /// Whether the picture still plays how waking or falling asleep ended, after the genie's
    /// phase already says so.
    boolean playing() {
        return emerging() || goingOut();
    }

    boolean moving() {
        return phase.isBetween() || playing();
    }

    /// When the flame begins to go out: as the lamp went out, though not before the genie is
    /// back in the flame.
    private double wentOut() {
        return Math.max(released, SINKING);
    }

    /// Seconds on the shared welcome scene's timeline. The lamp is already there when waking
    /// begins. A long start stays in its calm-burning interval, with a flickering flame, and so
    /// does a long shutdown.
    double moment() {
        if (phase == Genie.Phase.WAKING) {
            double caught = 1 + 2 * seconds;
            return caught < EMERGING ? caught : BURNING + (seconds % 0.9);
        }
        if (phase == Genie.Phase.FALLING_ASLEEP)
            return seconds < SINKING
                ? WelcomeScene.FORMED - BACKWARDS * seconds
                : BURNING + ((seconds - SINKING) % 0.9);
        if (phase == Genie.Phase.ASLEEP && released >= 0)
            return seconds < SINKING
                ? WelcomeScene.FORMED - BACKWARDS * seconds
                : seconds - wentOut() < FLAME_OUT ? BURNING - DWINDLING * (seconds - wentOut()) : COLD;
        if (phase.isAwake())
            return released < 0
                ? WelcomeScene.FORMED + 1 + seconds
                : EMERGING
                  + Math.min(1, (seconds - released) / TAKE) * (WelcomeScene.FORMED + 1 - EMERGING)
                  + Math.max(0, seconds - released - TAKE);
        return COLD;
    }

    WelcomeScene.Play play() {
        GenieSvgUtil.Appearance appearance = GenieSvgUtil.appearanceOf(id);
        return new WelcomeScene.Play(appearance, 0, appearance, -1);
    }
}
