package dev.lamp;

/// How a run ended.
public enum LampRunOutcome {
    /// The agent finished and said so.
    FINISHED,
    /// The agent, or the model behind it, failed. The answer says why.
    FAILED,
    /// The run took longer than `schedule.max_run_minutes` and was stopped.
    TIMED_OUT,
    /// The session ended while the agent was working.
    INTERRUPTED,
    /// Someone stopped it, with `oillamp cancel` or [Lamp#cancel()].
    CANCELLED
}
