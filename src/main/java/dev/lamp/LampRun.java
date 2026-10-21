package dev.lamp;

import java.util.Optional;

/// One time the agent is woken, by a job or by someone asking. Runs happen one at a time.
///
/// @param id           `run-` and a number counted per lamp, such as `run-12`. The snapshots
///                     taken as the run began and ended carry it too
/// @param job          the job that woke the agent; empty when someone asked
/// @param prompt       the job's prompt, or the question, as written
/// @param conversation the conversation the run continues; empty for a new one
public record LampRun(String id, Optional<String> job, String prompt, Optional<String> conversation) {}
