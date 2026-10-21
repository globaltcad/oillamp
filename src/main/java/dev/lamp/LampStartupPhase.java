package dev.lamp;

/// The phases of `oillamp at`, in order.
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
