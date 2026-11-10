package dev.gui.view;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.Map;
import java.util.UUID;

import dev.gui.model.Palette;

/// What the promo film borrows from the window's own pictures: the welcome, in which a lamp is
/// lit and a genie takes form from its flame, and the genies themselves. It is in this package
/// because those pictures are the window's alone; nothing else of the film is here.
public final class PromoArt {

    private PromoArt() {}

    /// How a genie holds itself.
    public enum Pose { AWAKE, ASLEEP, THINKING, WORKING, DIZZY, WAVING }

    /// How long the welcome takes until the genie has taken form.
    public static final double FORMED = WelcomeScene.FORMED;

    /// The welcome `clock` seconds into its play, with its light: the lamp stands at the bottom
    /// of a picture `width` by `height`, in its middle, and the genie `genie` takes form above it.
    /// The light shines as far as `g` reaches.
    public static void welcome(Graphics2D g, Palette palette, int width, int height, UUID genie, double clock) {
        GenieSvgUtil.Appearance pip = GenieSvgUtil.appearanceOf(genie);
        WelcomeScene.Play play = new WelcomeScene.Play(pip, 0, pip, -1);
        Graphics2D picture = (Graphics2D) g.create();
        try {
            WelcomeScene.light(picture, palette, width, height, 0, play, clock);
            WelcomeScene.paint(picture, palette, width, height, play, clock);
        } finally {
            picture.dispose();
        }
    }

    /// The genie `genie`, its twenty pixels each `pixel` across, with its top left corner at
    /// `x`, `y`; `progress`, from 0 to 1, is how far it is through a pose that moves.
    public static void genie(Graphics2D g, UUID genie, Pose pose, double progress, double x, double y, double pixel) {
        GenieSvgUtil.Appearance appearance = GenieSvgUtil.appearanceOf(genie);
        GenieSvgUtil.Pose held = GenieSvgUtil.Pose.valueOf(pose.name());
        char[][] pixels = GenieSvgUtil.pixels(appearance, held, GenieSvgUtil.frameAt(held, progress));
        Map<Character, Color> colours = GenieSvgUtil.colours(appearance);
        for (int row = 0; row < pixels.length; row++)
            for (int column = 0; column < pixels[row].length; column++) {
                char letter = pixels[row][column];
                if (letter == '.') continue;
                Color colour = colours.get(letter);
                if (colour == null) continue;
                g.setColor(colour);
                // A hair wider than its place, so that no seam shows between two pixels.
                g.fill(new Rectangle2D.Double(x + column * pixel, y + row * pixel, pixel + 0.4, pixel + 0.4));
            }
    }

    /// The colour of the genie `genie`.
    public static Color colourOf(UUID genie) {
        return GenieSvgUtil.appearanceOf(genie).colour();
    }
}
