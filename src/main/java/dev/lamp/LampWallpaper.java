package dev.lamp;

import java.util.Locale;
import java.util.Optional;

/// The picture behind the sandbox desktop's windows: `display.wallpaper` in a lamp's
/// `oillamp.toml`.
///
/// [#LAMP], the default, is the lamp itself on a panel. The two "waves" wallpapers are the inside
/// of the lamp instead, three rows of waves under its carved name, one light and one dark.
public enum LampWallpaper {
    LAMP, WAVES_LIGHT, WAVES_DARK;

    /// How `oillamp.toml` and `oillamp at --wallpaper` spell it, such as `waves-dark`.
    public String configName() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /// The wallpaper with this name, as `oillamp.toml` and `--wallpaper` spell it.
    public static Optional<LampWallpaper> named(String text) {
        for (LampWallpaper wallpaper : values())
            if (wallpaper.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(wallpaper);
        return Optional.empty();
    }

    /// The names, as a configuration problem or a usage text lists them.
    public static String names() { return "\"lamp\", \"waves-light\" or \"waves-dark\""; }
}
