package dev.gui.model;

import java.awt.Color;

/// The colours of Genies. [#DARK] is a lamp burning at night: deep plum for the dark, brass for
/// the lamp, and the amber of its flame for whatever is alive or asks to be clicked. [#LIGHT] is
/// the same lamp by day: warm paper, plum ink, and the flame a deeper amber, so that it still
/// reads against the light.
///
/// The palette is a value, so that Genies can be shown in more than one: a light one beside the
/// dark, say. It is the only part of the model that knows [java.awt]; a [Color] is as immutable
/// as any other value here, and a palette of anything else would only be turned into one by
/// every view that paints with it.
///
/// @param CODE_TEXT       inline code in an answer: warm, so it stands out from the text around it
/// @param SELECTION       behind the characters the user selected in an answer: the flame, thin
///                        enough that the text on it stays readable
/// @param SELECTION_EDGE  the edge of that selection
/// @param SELECTION_ASIDE the selection while the keyboard is elsewhere, as when the user went
///                        back to the composer
public record Palette(
    Color NIGHT,
    Color SIDEBAR,
    Color CARD,
    Color RAISED,
    Color BORDER,
    Color TEXT,
    Color SUBTEXT,
    Color FLAME,
    Color ON_FLAME,
    Color BRASS,
    Color CODE_TEXT,
    Color YOURS,
    Color GENIES,
    Color SMOKE,
    Color TROUBLE,
    Color TROUBLE_WASH,
    Color CONTENT,
    Color GLOW,
    Color SELECTION,
    Color SELECTION_EDGE,
    Color SELECTION_ASIDE,
    Color TRANSPARENT
) {

    public static final Palette DARK = new Palette(
        new Color(0x15, 0x12, 0x1c),
        new Color(0x1a, 0x16, 0x22),
        new Color(0x22, 0x1d, 0x2c),
        new Color(0x2b, 0x25, 0x37),
        new Color(0x3a, 0x32, 0x48),
        new Color(0xef, 0xe6, 0xd8),
        new Color(0xa8, 0x9c, 0x8a),
        new Color(0xf0, 0xa9, 0x40),
        new Color(0x1d, 0x14, 0x06),
        new Color(0xc8, 0x96, 0x3e),
        new Color(0xf3, 0xc6, 0x77),
        new Color(0x3b, 0x2d, 0x18),
        new Color(0x26, 0x20, 0x33),
        new Color(0x1d, 0x19, 0x26),
        new Color(0xe0, 0x64, 0x5a),
        new Color(0x3a, 0x1c, 0x1e),
        new Color(0x8f, 0xd1, 0x9e),
        new Color(0xf0, 0xa9, 0x40, 38),
        new Color(0xf0, 0xa9, 0x40, 64),
        new Color(0xf0, 0xa9, 0x40, 120),
        new Color(0xa8, 0x9c, 0x8a, 48),
        new Color(0, 0, 0, 0)
    );

    public static final Palette LIGHT = new Palette(
        new Color(0xf4, 0xef, 0xe6),
        new Color(0xec, 0xe5, 0xd9),
        new Color(0xff, 0xfb, 0xf4),
        new Color(0xf0, 0xe9, 0xdd),
        new Color(0xdc, 0xd1, 0xc1),
        new Color(0x2b, 0x23, 0x33),
        new Color(0x6e, 0x63, 0x57),
        new Color(0xad, 0x66, 0x0c),
        new Color(0xff, 0xfa, 0xf2),
        new Color(0x8f, 0x64, 0x20),
        new Color(0x9a, 0x4a, 0x12),
        new Color(0xf5, 0xe4, 0xc6),
        new Color(0xec, 0xe6, 0xf1),
        new Color(0xe9, 0xe2, 0xd6),
        new Color(0xb8, 0x3a, 0x2e),
        new Color(0xf7, 0xe0, 0xdb),
        new Color(0x2f, 0x7a, 0x4a),
        new Color(0xe8, 0x9a, 0x30, 46),
        new Color(0xe8, 0x9a, 0x30, 80),
        new Color(0xc2, 0x74, 0x10, 140),
        new Color(0x6e, 0x63, 0x57, 40),
        new Color(0, 0, 0, 0)
    );

    /// Whether this is a palette for the dark: its night is darker than the middle grey, so
    /// text and light are painted on dark ground. What the palette does not name follows it,
    /// such as the look-and-feel Genies builds on and the shadows under its cards.
    public boolean isDark() {
        return NIGHT.getRed() * 299 + NIGHT.getGreen() * 587 + NIGHT.getBlue() * 114 < 128_000;
    }

    /// The colour of a branch on the history page, one of [Lines#COLOURS]. Each is the flame at
    /// another hue, softened, so they all stand out against the night as the flame does, in
    /// whatever the night is; and none comes nearer the flame's own hue than a ninth of the circle,
    /// which stays the genie's, and says where it lives.
    public Color branch(int colour) {
        float[] flame = Color.RGBtoHSB(FLAME.getRed(), FLAME.getGreen(), FLAME.getBlue(), null);
        float hue = flame[0] + (1 + Math.floorMod(colour, Lines.COLOURS)) / (Lines.COLOURS + 1f);
        return Color.getHSBColor(hue % 1f, flame[1] * 0.55f, flame[2] * 0.95f);
    }
}
