package dev.promo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.UUID;

import dev.gui.view.PromoArt;

/// The scenes without the window, after the first ([Problem]): what one might ask for, and the end. Their words are in [Storyboard], which makes them.
final class Acts {

    private Acts() {}

    /// What one might ask a genie for.
    record Wish(UUID genie, PromoArt.Pose pose, String words) {}

    /// Genies side by side, each with something to build.
    static Scene anything(String headline, List<Wish> wishes, String line) {
        return new ArtScene("anything", 10) {
            @Override void paint(Graphics2D g, double t) {
                night(g);
                // The point the window drew itself into bursts, and the genies fly from it to
                // their places; as the scene ends they fly on, into the lamp of the last one.
                double leaving = Draw.ease(t, seconds - 1.25, seconds - 0.1);
                Effects.light(g, Effects.WISHES, 1 - Draw.ease(t, 0.05, 0.5));
                Effects.burst(g, Effects.WISHES, t - 0.05, 420);
                Effects.light(g, Effects.LAMP, Draw.ease(t, seconds - 0.9, seconds));
                Letters said = Letters.centred(headline, Draw.font(70, true, -0.015), MIDDLE, 210);
                said.paint(g, P.TEXT(), said.wordByWord(t, 0.9, 0.12, seconds - 0.7));
                double apart = 1720.0 / wishes.size();
                for (int i = 0; i < wishes.size(); i++) {
                    Wish wish = wishes.get(i);
                    double in = 0.15 + i * 0.12, there = Draw.spring((t - in) / 1.1), told = Draw.arrive(t, 2.0 + i * 0.55, 2.7 + i * 0.55);
                    if (t < in) continue;
                    double homeX = 100 + apart * (i + 0.5), floats = Math.sin((t + i * 0.9) * 2.4) * 6 * (1 - leaving);
                    double x = Draw.mix(Draw.mix(Effects.WISHES.x, homeX, there), Effects.LAMP.x, leaving);
                    double y = Draw.mix(Draw.mix(Effects.WISHES.y, 500, there), Effects.LAMP.y, leaving) + floats;
                    double pixel = 10 * Math.max(0.02, Math.min(1, there)) * (1 - 0.9 * leaving);
                    var before = Draw.fade(g, Draw.clamp((t - in) * 5) * (1 - Draw.ease(t, seconds - 0.5, seconds - 0.1)));
                    Color colour = PromoArt.colourOf(wish.genie());
                    // Its light, as in the lamp.
                    float r = (float) (23 * pixel);
                    g.setPaint(new java.awt.RadialGradientPaint(new Point2D.Double(x, y), r, new float[] {0f, 0.45f, 1f},
                        new Color[] {Draw.alpha(colour, 0.2), Draw.alpha(colour, 0.07), Draw.alpha(colour, 0)}));
                    g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
                    PromoArt.genie(g, wish.genie(), wish.pose(), (t * 0.9 + i * 0.3) % 1.0, x - 10 * pixel, y - 10 * pixel, pixel);
                    g.setComposite(before);
                    before = Draw.fade(g, told * (1 - Draw.ease(t, seconds - 1.5, seconds - 0.9)));
                    g.setFont(Draw.font(31, true));
                    g.setColor(P.TEXT());
                    List<String> rows = List.of(wish.words().split("\n"));
                    for (int row = 0; row < rows.size(); row++) Draw.centred(g, rows.get(row), homeX, 700 + row * 42 + 12 * (1 - told));
                    g.setComposite(before);
                }
                Letters last = Letters.centred(line, Draw.font(40, false), MIDDLE, 920);
                last.paint(g, P.FLAME(), last.wordByWord(t, 2.4 + wishes.size() * 0.55, 0.14, seconds - 1.0));
            }
        };
    }

    /// The end: the genie in its lamp, the name, and where to get it.
    static Scene end(UUID genie, String title, String line, List<String> facts, String where) {
        return new ArtScene("end", 12) {
            @Override void paint(Graphics2D g, double t) {
                night(g);
                Graphics2D picture = (Graphics2D) g.create();
                picture.translate(-470, 20);
                // Well into its play: the genie is there, and waves first.
                PromoArt.welcome(picture, P, Film.WIDTH, 800, genie, PromoArt.FORMED + 0.2 + t);
                picture.dispose();
                double left = 860;
                line(g, t, title, Draw.font(150, true, -0.02), P.TEXT(), left, 470, 0.6);
                line(g, t, line, Draw.font(42, false), P.FLAME(), left + 6, 548, 1.5);
                for (int i = 0; i < facts.size(); i++)
                    line(g, t, facts.get(i), Draw.font(31, false), P.SUBTEXT(), left + 6, 640 + i * 48, 2.6 + i * 0.5);
                line(g, t, where, Draw.mono(34), P.TEXT(), left + 6, 660 + facts.size() * 48 + 50, 3.2 + facts.size() * 0.5);
                // Out of the light the genies of the scene before flew into.
                double dark = 1 - Draw.ease(t, 0, 0.7);
                if (dark > 0) {
                    g.setColor(Draw.alpha(Draw.mix(P.NIGHT(), Color.BLACK, 0.35), dark));
                    g.fillRect(0, 0, Film.WIDTH, Film.HEIGHT);
                }
                Effects.light(g, Effects.LAMP, 1 - Draw.ease(t, 0.0, 0.6));
                Effects.burst(g, Effects.LAMP, t - 0.02, 380);
                curtain(g, t, 0, 1.6);
            }

            private void line(Graphics2D g, double t, String text, java.awt.Font font, Color colour, double x, double y, double in) {
                double there = Draw.arrive(t, in, in + 0.9);
                if (there <= 0) return;
                var before = Draw.fade(g, there);
                g.setFont(font);
                g.setColor(colour);
                g.drawString(text, (float) (x + 22 * (1 - there)), (float) y);
                g.setComposite(before);
            }
        };
    }
}
