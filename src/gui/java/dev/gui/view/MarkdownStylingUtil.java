package dev.gui.view;

import java.awt.Color;

import dev.gui.model.Palette;

import sprouts.Tuple;
import swingtree.api.Configurator;
import swingtree.style.FontConf;

/// Markdown runs, set in the lamp's colours, as the pieces of styled text SwingTree paints.
///
/// While an answer streams in, its newest characters materialise: the last [#TAIL] characters
/// are drawn from nearly transparent at the very end to opaque, and `settled` lifts them all
/// towards opaque as the moment of their arrival passes. So text appears the way ink spreads,
/// rather than in jumps, without any clock in the data: each new piece restarts `settled` at 0.
final class MarkdownStylingUtil {

    private MarkdownStylingUtil() {}

    /// How many of the newest characters fade in.
    static final int TAIL = 48;

    /// How finely the fade is stepped. Characters of the same step share one styled run.
    private static final int STEPS = 8;

    static final int BODY = 14;

    /// How much of what lies behind code its background covers: as little as keeps it a
    /// readable tint, so that the user's selection, painted under the text's background,
    /// still shows through it.
    static final double CODE_COVER = 0.35;

    /// @param theme   the colours and the fonts to set the runs in
    /// @param ground  what the text lies on, so that code's translucent background looks on it
    ///                as the palette's colour for code would
    /// @param settled 0 when the newest characters just arrived, 1 once they are fully there
    static Tuple<StyledPiece> of(Theme theme, Color ground, Tuple<MarkdownParsingUtil.Run> runs, double settled) {
        int total = 0;
        for (MarkdownParsingUtil.Run run : runs) total += run.text().length();
        Tuple<StyledPiece> out = Tuple.of(StyledPiece.class);
        int position = 0;
        for (MarkdownParsingUtil.Run run : runs) {
            String text = run.text();
            int start = 0;
            while (start < text.length()) {
                int step = step(total - (position + start), settled);
                int end = start + 1;
                while (end < text.length() && step(total - (position + end), settled) == step) end++;
                double alpha = (double) step / STEPS;
                out = out.add(new StyledPiece(look(theme, ground, run, alpha), text.substring(start, end)));
                start = end;
            }
            position += text.length();
        }
        return out;
    }

    /// The fade step of a character `fromEnd` characters before the end: 1 is the last one.
    static int step(int fromEnd, double settled) {
        double alpha = Math.min(1.0, (double) fromEnd / TAIL + Math.max(0, Math.min(1, settled)));
        return Math.max(1, (int) Math.ceil(alpha * STEPS));
    }

    private static Configurator<FontConf> look(Theme theme, Color ground, MarkdownParsingUtil.Run run, double alpha) {
        Palette p = theme.palette();
        Color colour = switch (run.inline()) {
            case CODE -> run.block() == MarkdownParsingUtil.Block.CODE ? p.TEXT() : p.CODE_TEXT();
            case LINK -> p.FLAME();
            case STRIKE -> p.SUBTEXT();
            case MARKER -> run.block() == MarkdownParsingUtil.Block.RULE || run.block() == MarkdownParsingUtil.Block.TABLE ? p.BORDER() : p.BRASS();
            default -> run.block() == MarkdownParsingUtil.Block.QUOTE ? p.SUBTEXT() : p.TEXT();
        };
        int size = switch (run.block()) { case H1 -> 20; case H2 -> 17; case H3 -> 15; default -> BODY; };
        boolean heading =
            run.block() == MarkdownParsingUtil.Block.H1 ||
            run.block() == MarkdownParsingUtil.Block.H2 ||
            run.block() == MarkdownParsingUtil.Block.H3;
        boolean bold =
            heading ||
            run.inline() == MarkdownParsingUtil.Inline.BOLD ||
            run.inline() == MarkdownParsingUtil.Inline.BOLD_ITALIC;
        boolean italic =
            run.inline() == MarkdownParsingUtil.Inline.ITALIC ||
            run.inline() == MarkdownParsingUtil.Inline.BOLD_ITALIC ||
            (run.block() == MarkdownParsingUtil.Block.QUOTE && run.inline() != MarkdownParsingUtil.Inline.MARKER);
        boolean code = run.inline() == MarkdownParsingUtil.Inline.CODE;
        boolean even = code || run.block() == MarkdownParsingUtil.Block.TABLE;
        Color shown = faded(colour, alpha);
        Color behind = faded(veil(run.block() == MarkdownParsingUtil.Block.CODE ? p.SMOKE() : p.RAISED(), ground, CODE_COVER), alpha);
        return f -> {
            FontConf set =
                f.family(even ? theme.fonts().MONO() : theme.fonts().FONT())
                .size(even ? size - 1 : size)
                .color(shown)
                .weight(bold ? 2 : 1)
                .posture(italic ? 0.2f : 0f);
            if (code) set = set.backgroundColor(behind);
            if (run.inline() == MarkdownParsingUtil.Inline.STRIKE) set = set.strikeThrough(true);
            if (run.inline() == MarkdownParsingUtil.Inline.LINK) set = set.underlined(true);
            return set;
        };
    }

    /// A colour as opaque as `cover` that, painted on `ground`, looks like `wanted`: the
    /// difference between the two, made stronger by as much as it is thinned. Where a channel
    /// would need more than there is, it gives what there is, and comes out a little nearer
    /// the ground.
    static Color veil(Color wanted, Color ground, double cover) {
        return new Color(
            through(wanted.getRed(), ground.getRed(), cover),
            through(wanted.getGreen(), ground.getGreen(), cover),
            through(wanted.getBlue(), ground.getBlue(), cover),
            (int) Math.round(255 * cover)
        );
    }

    private static int through(int wanted, int ground, double cover) {
        return Math.clamp(Math.round(ground + (wanted - ground) / cover), 0, 255);
    }

    private static Color faded(Color colour, double alpha) {
        return alpha >= 1
            ? colour
            : new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), (int) Math.round(colour.getAlpha() * alpha));
    }
}
