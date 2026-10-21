package dev.lamp;

/// A stage of the work oillamp does, as [LampEvent.PhaseStarted] and [LampEvent.PhaseFinished]
/// report it. `oillamp at` and [LampDirectory#start] go through all four, in this order; other
/// commands report the one their work belongs to, such as [#IMAGE] for `oillamp update`.
public enum LampStartupPhase {
    /// Check and repair the host itself: packages, subuid ranges, a working rootless podman.
    HOST,
    /// Create or load the lamp: config, identity, lock, keys, per-session files.
    LAMP,
    /// Make sure a sandbox image matching the current configuration exists.
    IMAGE,
    /// Start the container, the relays and the windows, then supervise.
    SESSION
}
