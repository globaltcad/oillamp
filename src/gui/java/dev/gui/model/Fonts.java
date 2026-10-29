package dev.gui.model;

import java.awt.Font;

/// The font families Genies writes in, by name: a value, as [Palette] is, so that they can be
/// changed as the colours can.
///
/// @param FONT the family of all text but code
/// @param MONO the family of code, in answers and wherever a command is shown
public record Fonts(
    String FONT,
    String MONO
) {

    /// The fonts Genies brings along, installed before any is named.
    static {
        com.formdev.flatlaf.fonts.inter.FlatInterFont.install();
        com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont.install();
        com.formdev.flatlaf.fonts.roboto_mono.FlatRobotoMonoFont.install();
    }

    public static final Fonts DEFAULT = new Fonts(
        "Inter",
        regular("JetBrains Mono", "Roboto Mono")
    );

    /// The first of `families` whose plain face is its regular one. A family also installed on
    /// the desktop, in other weights, wins over the copy Genies brings, and its plain face can
    /// then be bold or light: JetBrains Mono shows bold everywhere on a desktop that has it as
    /// a set of separate weights. The last family is used when none fits.
    private static String regular(String... families) {
        for (String family : families)
            if (new Font(family, Font.PLAIN, 13).getFontName().equals(family + " Regular")) return family;
        return families[families.length - 1];
    }
}
