package dev.promo;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

import dev.gui.model.Palette;

/// A scene without the window: words and pictures on the night, in the app's own colours and
/// type.
abstract class ArtScene extends Scene {

    static final Palette P = Palette.DARK;
    /// The colour of what is far away and not yours: a cold blue, against the lamp's amber.
    static final Color COLD = new Color(0x7f, 0xa6, 0xd9);
    static final double MIDDLE = Film.WIDTH / 2.0;

    ArtScene(String name, double seconds) { super(name, seconds); }

    static void night(Graphics2D g) { AppScene.backdrop(g); }

    /// Writes `text` with its middle at `x`, its baseline at `y`: it comes at `in`, rising a
    /// little as it fades in, and goes at `out`.
    static void words(Graphics2D g, double t, String text, Font font, Color colour, double x, double y, double in, double out) {
        double shown = Draw.arrive(t, in, in + 0.9) * (1 - Draw.ease(t, out - 0.6, out));
        if (shown <= 0) return;
        var before = Draw.fade(g, shown);
        g.setFont(font);
        g.setColor(colour);
        Draw.centred(g, text, x, y + 16 * (1 - Draw.arrive(t, in, in + 0.9)));
        g.setComposite(before);
    }

    /// Fades the whole picture in from the night as the scene begins, and out as it ends.
    void curtain(Graphics2D g, double t, double in, double out) {
        double dark = 1 - Math.min(in <= 0 ? 1 : Draw.ease(t, 0, in), out <= 0 ? 1 : 1 - Draw.ease(t, seconds - out, seconds));
        if (dark <= 0) return;
        g.setColor(Draw.alpha(Draw.mix(P.NIGHT(), Color.BLACK, 0.35), dark));
        g.fillRect(0, 0, Film.WIDTH, Film.HEIGHT);
    }
}
