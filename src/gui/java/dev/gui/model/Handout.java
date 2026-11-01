package dev.gui.model;

import java.util.Locale;

/// A file the genie put in `~/outbox` for the user.
///
/// @param name  the file's name in the outbox
/// @param bytes its size
public record Handout(String name, long bytes) {

    /// Its size in the unit that reads best, such as "312 bytes", "4.2 KB" or "1.5 MB", in `lang`.
    public String readableSize(Lang lang) {
        if (bytes < 1024) return lang.CHAT().BYTES(bytes);
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
