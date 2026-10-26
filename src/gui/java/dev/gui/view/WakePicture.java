package dev.gui.view;

import dev.gui.model.Genie;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/// Owns only the animation clock. Changes from the genie are marshalled by SwingTree onto the
/// UI thread; one animation loop advances an immutable frame, and painters only read that frame.
final class WakePicture {

    private record Cue(UUID id, Genie.Phase phase) {}

    private final Val<Genie> genie;
    private final Val<Cue> cue;
    private final Var<WakeScene> scene;
    private boolean playing;

    WakePicture(Val<Genie> genie) {
        this.genie = genie;
        cue = genie.viewAs(Cue.class, it -> new Cue(it.id(), it.phase()));
        scene = Var.of(WakeScene.of(genie.get()));
    }

    Val<Boolean> emerging() {
        return scene.viewAs(Boolean.class, WakeScene::emerging);
    }

    UIForAnySwing<?, ?> view() {
        return UI.box().withMinSize(0, 0).withPrefSize(320, 220)
                .onView(Viewable.cast(cue), _ -> follow())
                .onShown(_ -> follow())
                .withStyle(scene, (frame, it) -> {
                    int width = UI.scale(it.componentWidth());
                    int height = UI.scale(it.componentHeight());
                    return it.backgroundColor(Palette.TRANSPARENT).painter(UI.Layer.CONTENT, g -> {
                        g.scale(1 / UI.scale(), 1 / UI.scale());
                        if (frame.phase() == Genie.Phase.BROKEN) {
                            swingtree.style.SvgIcon.of(LampSvgUtil.lamp(Genie.Phase.BROKEN))
                                    .withIconSize(100, 100).paintIcon(null, g, (width - UI.scale(100)) / 2, height - UI.scale(100));
                        } else {
                            WelcomeScene.light(g, width, height, 0, frame.play(), frame.moment());
                            WelcomeScene.paint(g, width, height, frame.play(), frame.moment());
                        }
                    });
                });
    }

    private void follow() {
        scene.update(it -> it.follows(genie.get()));
        if (playing || !scene.get().moving()) return;
        playing = true;
        UI.animateFor(LifeTime.of(1, TimeUnit.SECONDS)).asLongAs(_ -> scene.get().moving()).go(new Animation() {
            private double previous;

            @Override public void run(AnimationStatus status) {
                double elapsed = status.repeats() + status.progress();
                scene.update(it -> it.after(elapsed - previous));
                previous = elapsed;
            }

            @Override public void finish(AnimationStatus status) {
                playing = false;
                // A new wake may have arrived between the last frame and this callback.
                follow();
            }
        });
    }
}
