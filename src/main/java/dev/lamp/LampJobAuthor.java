package dev.lamp;

/// Who added a job. The user's jobs are theirs alone: the agent may read them but not change
/// or remove them.
public enum LampJobAuthor {
    /// The person, with `oillamp schedule` or [LampDirectory#repeat] and [LampDirectory#once].
    USER,
    /// The agent, from inside the sandbox.
    AGENT
}
