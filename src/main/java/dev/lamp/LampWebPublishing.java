package dev.lamp;

import java.util.Locale;
import java.util.Optional;

/// Which web servers inside a lamp's sandbox the host's browser may open: `web.publish` in a
/// lamp's `oillamp.toml`.
///
/// A ladder, like [LampHostAccess]: each step publishes what the one before it does, and more.
/// A page opened from a published port runs in the person's browser, where it can send requests
/// to their other local web servers and to other lamps' published ports; that is why `off`
/// exists.
public enum LampWebPublishing {
    /// Nothing is published, whatever `web.ports` lists.
    OFF,
    /// The ports `web.ports` lists, and no others.
    LISTED,
    /// The listed ports, and each port the agent starts listening on during a session, once the
    /// person allowed it. The session asks with a [LampEvent.WebPortAsked].
    ASK,
    /// The listed ports, and every port the agent starts listening on during a session, unasked.
    ANY;

    /// The value as `oillamp.toml` and `oillamp web --publish` write it, such as `listed`.
    public String configName() { return name().toLowerCase(Locale.ROOT); }

    /// The value written as [#configName()] names it, or empty for any other text.
    public static Optional<LampWebPublishing> named(String text) {
        for (LampWebPublishing publish : values())
            if (publish.configName().equals(text)) return Optional.of(publish);
        return Optional.empty();
    }
}
