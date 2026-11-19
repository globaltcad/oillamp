package dev.lamp;

import java.util.Locale;
import java.util.Optional;

/// What every host request passes through before it happens: `host_access.guards` in a lamp's
/// `oillamp.toml`.
///
/// A chain, always in the same order, and each value adds one guard to the one before it:
///
/// 1. the allowlists: a path the agent names must be inside `read_allowlist` or
///    `change_allowlist` to be read, and inside `change_allowlist` to be changed or to run a
///    command in. oillamp checks this itself, before anything else;
/// 2. the checker: a model judges the request against the user's checker instructions;
/// 3. the user: asked about every command, and about a path outside the allowlists, which is
///    then not refused at the first step.
public enum LampHostAccessGuards {
    /// The allowlists alone. No model judges anything, and nobody is asked. With
    /// [LampHostAccess#MAY_READ_CHANGE_RUN] that lets commands run unchecked.
    ALLOWLISTS,
    /// The allowlists, then the checker.
    ALLOWLISTS_CHECKER,
    /// The allowlists, then the checker, then the user, for commands and for paths outside the
    /// allowlists.
    ALLOWLISTS_CHECKER_USER;

    /// The value as `oillamp.toml` and `oillamp host-access --guards` write it, such as
    /// `allowlists_checker`.
    public String configName() { return name().toLowerCase(Locale.ROOT); }

    /// Whether a model judges each request.
    public boolean checks() { return this != ALLOWLISTS; }

    /// Whether the user is asked about commands, and about paths outside the allowlists.
    public boolean asksTheUser() { return this == ALLOWLISTS_CHECKER_USER; }

    /// The value written as [#configName()] names it, or empty for any other text.
    public static Optional<LampHostAccessGuards> named(String text) {
        for (LampHostAccessGuards guards : values())
            if (guards.configName().equals(text)) return Optional.of(guards);
        return Optional.empty();
    }
}
