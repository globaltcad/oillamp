package dev.lamp;

import java.time.Duration;

/// What a session is doing, as [LampEvent.SessionStateChanged] reports it each time it changes.
///
/// It describes the supervisor's internal state rather than exposing it, so that those states
/// can change without changing this type.
///
/// @param state       one of `starting`, `awaiting-terminal`, `running`, `shutting-down` and
///                    `stopped`
/// @param detail      the same in a sentence for people, such as "waiting for your terminal window"
/// @param uptime      how long ago the session started
/// @param extraShells how many shells are attached besides the terminal window, such as
///                    `oillamp shell` or a command run with [Lamp#exec]
public record LampSessionStatus(String state, String detail, Duration uptime, int extraShells) {}
