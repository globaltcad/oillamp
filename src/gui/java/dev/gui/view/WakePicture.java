package dev.gui.view;

import dev.gui.model.Genie;
import dev.gui.model.Palette;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;

import javax.swing.SwingUtilities;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/// Owns only the animation clock. Changes from the genie are marshalled by SwingTree onto the
/// UI thread; one animation loop advances an immutable frame, and painters only read that frame.
final class WakePicture {

    private record Cue(UUID id, Genie.Phase phase) {}

    private final Val<Genie> genie;
    private final Val<Palette> palette;
    private final Val<Cue> cue;
    private final Var<WakeScene> scene;
    private Val<Boolean> visible = Val.of(false);
    private boolean playing;

    /// A moment of the scene, and the palette it is painted in.
    private record Painted(WakeScene scene, Palette palette) {}

    WakePicture(Val<Genie> genie, Val<Palette> palette) {
        this.genie = genie;
        this.palette = palette;
        cue = genie.viewAs(Cue.class, it -> new Cue(it.id(), it.phase()));
        scene = Var.of(WakeScene.of(genie.get()));
    }

    Val<Boolean> emerging() {
        return scene.viewAs(Boolean.class, WakeScene::emerging);
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        this.visible = visible;
        return
            UI.box().withMinSize(0, 0).withPrefSize(320, 220)
            // Followed once the change that called is done: the scene decides whether the picture
            // is shown, so changing it in the middle of a change, such as the picture being shown
            // or hidden, has the components that are still to hear of that change apply its
            // outdated value after this one, and show the chat and the picture at odds with it.
            .onView(Viewable.cast(cue), _ -> followLater())
            .onView(Viewable.cast(visible), _ -> followLater())
            .onShown(_ -> followLater())
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            // The scene in the palette, so that it is painted anew when either changes.
            .withStyle(Viewable.of(Painted.class, scene, palette, Painted::new), (painted, it) -> {
                WakeScene frame = painted.scene();
                Palette p = painted.palette();
                int width = UI.scale(it.componentWidth());
                int height = UI.scale(it.componentHeight());
                return it.painter(UI.Layer.CONTENT, g -> {
                    g.scale(1 / UI.scale(), 1 / UI.scale());
                    if (frame.phase() == Genie.Phase.BROKEN) {
                        swingtree.style.SvgIcon.of(LampSvgUtil.lamp(Genie.Phase.BROKEN))
                        .withIconSize(100, 100)
                        .paintIcon(null, g, (width - UI.scale(100)) / 2, height - UI.scale(100));
                    } else {
                        WelcomeScene.light(g, p, width, height, 0, frame.play(), frame.moment());
                        WelcomeScene.paint(g, p, width, height, frame.play(), frame.moment());
                    }
                });
            });
    }

    private void followLater() {
        SwingUtilities.invokeLater(this::follow);
    }

    private void follow() {
        scene.update(it -> it.follows(genie.get()));
        if (playing || !moving()) return;
        playing = true;
        UI.animateFor(LifeTime.of(1, TimeUnit.SECONDS)).asLongAs(_ -> moving()).go(new Animation() {
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

    private boolean moving() {
        return scene.get().moving() || (visible.get() && scene.get().phase().isAwake());
    }
}
