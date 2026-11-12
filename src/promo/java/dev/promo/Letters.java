package dev.promo;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/// A line of text as its letters, each a shape of its own with a place in the film, so that a
/// scene can move them one by one: words that arrive one after the other, or letters that fly off.
///
/// @param letters every letter that shows, in reading order
/// @param words   how many words the line has
record Letters(List<Letter> letters, int words) {

    /// One letter.
    ///
    /// @param shape its outline, where it stands in the film
    /// @param x     the middle of it
    /// @param y     the middle of it
    /// @param word  which word of the line it is in, from 0
    /// @param index which letter of the line it is, from 0
    record Letter(Shape shape, double x, double y, int word, int index) {}

    /// How a letter is painted at a moment: moved by `dx`, `dy` pixels of the film, `scale` its
    /// size, turned by `turn` radians, `alpha` visible.
    record Held(double dx, double dy, double scale, double turn, double alpha) {
        static final Held STILL = new Held(0, 0, 1, 0, 1), GONE = new Held(0, 0, 1, 0, 0);
    }

    /// `text` in `font`, with its middle at `x` and its baseline at `y`.
    static Letters centred(String text, Font font, double x, double y) {
        return from(text, font, x - width(text, font) / 2, y);
    }

    /// How wide `text` is in `font`, in pixels of the film.
    static double width(String text, Font font) {
        return font.getStringBounds(text, new FontRenderContext(null, true, true)).getWidth();
    }

    /// `text` broken into lines no wider than `width` in `font`, at its spaces.
    static List<String> wrapped(String text, Font font, double width) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : java.util.Arrays.asList(text.split(" ", -1))) {
            String longer = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(longer, font) > width) {
                lines.add(line);
                line = word;
            } else {
                line = longer;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    /// `text` in `font`, beginning at `left`, with its baseline at `y`.
    static Letters from(String text, Font font, double left, double y) {
        FontRenderContext context = new FontRenderContext(null, true, true);
        GlyphVector glyphs = font.layoutGlyphVector(context, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);
        List<Letter> letters = new ArrayList<>();
        int word = 0;
        boolean inWord = false;
        for (int i = 0; i < glyphs.getNumGlyphs(); i++) {
            int character = glyphs.getGlyphCharIndex(i);
            boolean space = Character.isWhitespace(text.charAt(Math.min(character, text.length() - 1)));
            if (space) {
                if (inWord) word++;
                inWord = false;
                continue;
            }
            inWord = true;
            Shape outline = AffineTransform.getTranslateInstance(left, y).createTransformedShape(glyphs.getGlyphOutline(i));
            Rectangle2D box = outline.getBounds2D();
            if (box.isEmpty()) continue;
            letters.add(new Letter(outline, box.getCenterX(), box.getCenterY(), word, letters.size()));
        }
        return new Letters(letters, word + (inWord ? 1 : 0));
    }

    /// Says how each letter is held at a moment.
    interface Holding { Held of(Letter letter); }

    void paint(Graphics2D g, Color colour, Holding holding) {
        for (Letter letter : letters) {
            Held held = holding.of(letter);
            if (held.alpha() <= 0 || held.scale() <= 0) continue;
            AffineTransform place = new AffineTransform();
            place.translate(letter.x() + held.dx(), letter.y() + held.dy());
            place.rotate(held.turn());
            place.scale(held.scale(), held.scale());
            place.translate(-letter.x(), -letter.y());
            g.setColor(Draw.alpha(colour, held.alpha()));
            g.fill(place.createTransformedShape(letter.shape()));
        }
    }

    /// The line arriving word by word from `in` seconds, each word `apart` seconds after the one
    /// before, rising a little as it fades in; and leaving all at once before `out`.
    Holding wordByWord(double t, double in, double apart, double out) {
        double leaving = 1 - Draw.ease(t, out - 0.45, out);
        return letter -> {
            double from = in + letter.word() * apart, there = Draw.arrive(t, from, from + 0.55);
            return new Held(0, 14 * (1 - there), 1, 0, there * leaving);
        };
    }

    /// When the last word of a line arriving [#wordByWord] has arrived.
    double arrived(double in, double apart) { return in + Math.max(0, words - 1) * apart + 0.55; }
}
