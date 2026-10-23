package dev.lamp;

import java.time.Instant;
import java.util.Optional;

/// One saved state of a lamp: the agent's home, the lamp's `oillamp.toml` and its schedule, as
/// they were at one moment. `oillamp history <dir> restore` brings a lamp back to it.
///
/// @param id           the snapshot's name, 40 hexadecimal characters. Any unique beginning of it
///                     of at least four characters names it too, such as the eight `oillamp
///                     history` shows
/// @param at           when it was saved
/// @param kind         what made it
/// @param message      what the person who saved it wrote, or empty
/// @param session      the session running at the time, such as `20260928-120000`, or empty
/// @param run          for a snapshot made as a run began or ended, the run, such as `run-12`
/// @param job          for a snapshot made as a job's run ended, the job, such as `job-3`
/// @param outcome      for a snapshot made as a run ended, how it ended
/// @param conversation for a snapshot made as a run ended, the id of the conversation the run
///                had; empty when the agent did not get as far as opening one
public record LampSave(
        String id,
        Instant at,
        LampSaveKind kind,
        String message,
        Optional<String> session,
        Optional<String> run,
        Optional<String> job,
        Optional<LampRunOutcome> outcome,
        Optional<String> conversation
) {
    /// The first eight characters of [#id()], which is how oillamp shows a snapshot.
    public String shortId() {
        return id.substring(0, Math.min(8, id.length()));
    }
}
