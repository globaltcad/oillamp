package dev.lamp;

import java.util.Locale;
import java.util.Optional;

/// What the agent may do on the host: `host_access.may` in a lamp's `oillamp.toml`.
///
/// A ladder: each step allows everything the one before it does, and one tier more. What the
/// agent may reach is bounded besides by the lamp's allowlists and by its [guards][LampHostAccessGuards].
public enum LampHostAccess {
    /// Nothing: host access is off. The session serves no socket and no tools, and the agent
    /// cannot reach the host at all.
    NOTHING,
    /// Read: list folders, read files, copy a file into the sandbox, show a document. The
    /// [passive tier][LampHostRisk#PASSIVE].
    MAY_READ,
    /// Read, and change: also write and edit files, copy a file out of the sandbox, open a web
    /// page, show a notification. The [additive tier][LampHostRisk#ADDITIVE] too.
    MAY_READ_CHANGE,
    /// Read, change, and run commands with `host_bash`, which can do anything the user can. The
    /// [executing tier][LampHostRisk#EXECUTING] too.
    MAY_READ_CHANGE_RUN;

    /// The value as `oillamp.toml` and `oillamp host-access --may` write it, such as `read_change`.
    public String configName() { return name().replaceFirst("^MAY_", "").toLowerCase(Locale.ROOT); }

    /// The value written as [#configName()] names it, or empty for any other text.
    public static Optional<LampHostAccess> named(String text) {
        for (LampHostAccess may : values())
            if (may.configName().equals(text)) return Optional.of(may);
        return Optional.empty();
    }
}
