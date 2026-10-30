package dev.oillamp;

import java.util.Locale;
import java.util.Optional;

/// The `display.wallpaper` setting: the picture behind the sandbox desktop's windows.
///
/// [#LAMP], the default, is the lamp itself on a panel. The two "waves" wallpapers are the inside
/// of the lamp instead, three rows of waves under its carved name, one light and one dark. The
/// pictures are in the image, in `/usr/share/oillamp`; the entrypoint picks one by its name.
enum Wallpaper {
    LAMP, WAVES_LIGHT, WAVES_DARK;

    public String configName() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /// The wallpaper with this name, as `oillamp.toml` and `--wallpaper` spell it.
    static Optional<Wallpaper> named(String text) {
        for (Wallpaper wallpaper : values())
            if (wallpaper.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(wallpaper);
        return Optional.empty();
    }

    /// The names, as a configuration problem or a usage text lists them.
    static String names() { return "\"lamp\", \"waves-light\" or \"waves-dark\""; }
}
