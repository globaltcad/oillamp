package dev.lamp;

/// How risky a host request is, and how much it may change. The order is the ceiling: a
/// request is judged at most at its own tier, and the checker may only put it lower.
public enum LampHostRisk {
    /// Reads, and returns what it read. It cannot change anything.
    PASSIVE,
    /// Changes something on the host, but in a way the user can see and undo.
    ADDITIVE,
    /// Runs a program on the host. Closed unless the lamp names the programs.
    EXECUTING
}
