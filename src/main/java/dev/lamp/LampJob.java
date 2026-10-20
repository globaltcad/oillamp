package dev.lamp;

import sprouts.Tuple;

import java.time.Instant;
import java.util.Optional;

/// One job on a lamp's schedule: a prompt that wakes the agent at a set time, or again and
/// again. Jobs only run while a session runs.
///
/// @param id       the job's name, such as `job-3`
/// @param when     when it runs, as it was written: a cron expression such as `0 9 * * 1-5`,
///                or `once at` and a time
/// @param next     when it runs next; empty when it will not run again, or is disabled
/// @param prompt   what the agent is asked when it wakes
/// @param author   who added it
/// @param created  when it was added
/// @param expires  when it is taken off the schedule; empty for a job that stays until removed
/// @param enabled  false for a job the user switched off, which keeps its place but does not run
/// @param upcoming when it runs after the moment the schedule was read and within seven days
///                of it, before it expires, earliest first; at most 200 times. Empty when it is
///                switched off. A `next` at or before that moment is not among them: the job
///                came due, and runs as soon as a session can run it
public record LampJob(
        String id,
        String when,
        Optional<Instant> next,
        String prompt,
        LampEvent.JobAuthor author,
        Instant created,
        Optional<Instant> expires,
        boolean enabled,
        Tuple<Instant> upcoming
) { }
