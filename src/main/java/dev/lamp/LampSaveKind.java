package dev.lamp;

/// What made a snapshot.
public enum LampSaveKind {
    /// oillamp saved as a session started, before the sandbox ran.
    STARTUP("startup save"),
    /// Someone saved while a session was running. Programs in the sandbox may have been
    /// writing at that moment.
    RUNNING("running save"),
    /// oillamp saved as a session ended, after the sandbox had stopped.
    SHUTDOWN("shutdown save"),
    /// Someone saved while no session was running.
    IDLE("idle save"),
    /// oillamp saved just before a restore, so that the restore can be undone.
    BEFORE_RESTORE("safety save before a restore"),
    /// A restore: the lamp was brought back to an earlier snapshot.
    RESTORE("restore"),
    /// oillamp saved just before waking the agent, so that what the user changed and what
    /// the agent then did are two separate snapshots.
    BEFORE_RUN("save before a run"),
    /// oillamp saved as a run ended. The message holds the agent's last words.
    RUN("run");

    private final String label;

    LampSaveKind(String label) {
        this.label = label;
    }

    /// How oillamp names it, such as `startup save`.
    public String label() {
        return label;
    }
}
