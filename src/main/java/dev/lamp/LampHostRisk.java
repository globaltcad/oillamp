package dev.lamp;

/// How much a host request can do, from reading to running a program.
///
/// Every host tool has a fixed tier, its ceiling. The checker model may judge a request lower
/// than its tool's tier, never higher: a request it judges higher is refused.
public enum LampHostRisk {
    /// Reads, and returns what it read. It cannot change anything.
    PASSIVE,
    /// Changes something on the host, in a way the user can see and undo, or sends something
    /// out, as opening a page does. Closed unless the lamp's `host_access.allow_additive` is on.
    ADDITIVE,
    /// Runs a program on the host. Closed unless the lamp's `host_access.executables` names the
    /// program, and the user is asked every time.
    EXECUTING
}
