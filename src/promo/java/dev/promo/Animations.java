package dev.promo;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/// Has SwingTree's animations take their step for the moment the film's clock says. SwingTree
/// steps them from timers of its own, which go by the computer's time; they would get there
/// too, a few milliseconds later. Stepped here, each picture of the film is painted with every
/// animation exactly where it is at that picture's moment.
final class Animations {

    private Animations() {}

    private static final Map<?, ?> RUNNERS;
    private static final Method RUN;

    static {
        try {
            Class<?> runner = Class.forName("swingtree.animation.AnimationRunner");
            Field instances = runner.getDeclaredField("_INSTANCES");
            instances.setAccessible(true);
            RUNNERS = (Map<?, ?>) instances.get(null);
            RUN = runner.getDeclaredMethod("_run", ActionEvent.class);
            RUN.setAccessible(true);
        } catch (ReflectiveOperationException changed) {
            throw new IllegalStateException("SwingTree runs its animations another way than the film knows: " + changed, changed);
        }
    }

    /// On Swing's event thread.
    static void step() {
        ActionEvent tick = new ActionEvent(Animations.class, ActionEvent.ACTION_PERFORMED, "film");
        try {
            for (Object runner : List.copyOf(RUNNERS.values())) RUN.invoke(runner, tick);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
