package dev.lamp;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/// The picture behind the sandbox desktop's windows: `display.wallpaper` in a lamp's
/// `oillamp.toml`, drawn on the desktop's background colour, `display.background`.
///
/// [#LAMP], the default, is the lamp itself on a panel. [#WAVES] is the inside of the lamp
/// instead, three rows of waves under its carved name. It has no colour of its own, only light and
/// shadow fading out towards the edges of the screen, so it takes on the background colour: dark on
/// a dark one, light on a light one. [#WAVES_LIGHT] and [#WAVES_DARK] are the same picture on a
/// light and on a dark colour of their own, the paper and the night of the Genies application.
public enum LampWallpaper {
    LAMP("#161a22"), WAVES("#161a22"), WAVES_LIGHT("#f4efe6"), WAVES_DARK("#15121c");

    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final String background;

    LampWallpaper(String background) { this.background = background; }

    /// How `oillamp.toml` and `oillamp at --wallpaper` spell it, such as `waves-dark`.
    public String configName() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /// The background colour the wallpaper is drawn on where `display.background` names none, as
    /// `#rrggbb`.
    public String background() { return background; }

    /// The wallpaper with this name, as `oillamp.toml` and `--wallpaper` spell it.
    public static Optional<LampWallpaper> named(String text) {
        for (LampWallpaper wallpaper : values())
            if (wallpaper.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(wallpaper);
        return Optional.empty();
    }

    /// The names, as a configuration problem or a usage text lists them.
    public static String names() { return "\"lamp\", \"waves\", \"waves-light\" or \"waves-dark\""; }

    /// Whether `text` is a background colour as `display.background` and `--background` take it:
    /// `#` and six hexadecimal digits, such as `#15121c`. Nothing else is accepted, for the colour
    /// ends up in the sandbox's compositor configuration.
    public static boolean isColour(String text) { return COLOUR.matcher(text).matches(); }
}
