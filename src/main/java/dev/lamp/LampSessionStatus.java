package dev.lamp;

import java.time.Duration;

/// A summary of a session's state, such as "running, up 3 minutes, one extra shell".
///
/// This is a description of the supervisor's internal state, not the state itself, so the
/// internal states can change without changing this public type.
public record LampSessionStatus(String state, String detail, Duration uptime, int extraShells) {}
