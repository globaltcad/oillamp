package dev.lamp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import sprouts.Tuple;

/// Everything oillamp reports, as values.
///
/// oillamp never writes to the console directly. It emits these events, and the console
/// renderer prints them. Tests inspect them, and a future graphical front end could display them
/// its own way.
///
/// Public because any caller that wants to show progress, including a GUI, needs to see them.
public sealed interface LampEvent {

    /// This event as one line of JSON, the form in which an embedded oillamp reports it to the
    /// application that started it. [#fromJson] reads it back into an equal value.
    default String toJson() { return Wire.write(this); }

    /// Reads a line written by [#toJson].
    ///
    /// Empty for a line that is not an event this version of oillamp knows, such as one from a
    /// newer version, so that a reader can skip it rather than fail.
    static Optional<LampEvent> fromJson(String line) { return Wire.read(line); }

    record PhaseStarted(LampStartupPhase phase)         implements LampEvent {}
    record PhaseFinished(LampStartupPhase phase, Duration took) implements LampEvent {}

    /// A check that passed, e.g. `[host] ✓ podman 5.4.2, rootless, crun`.
    record Ok(String area, String text)                 implements LampEvent {}
    /// A neutral remark, e.g. why the session fell back to software rendering.
    record Info(String area, String text)               implements LampEvent {}

    /// Emitted instead of performing a step when `--dry-run` is given.
    record StepPlanned(LampStep step)                   implements LampEvent {}
    record StepStarted(LampStep step)                   implements LampEvent {}
    record StepSucceeded(LampStep step, Duration took)  implements LampEvent {}
    record StepSkipped(LampStep step, String why)       implements LampEvent {}

    /// A line of output from a subprocess, tagged with its source (e.g. `"image"`, `"sway"`).
    record Output(String sourceTag, String line)        implements LampEvent {}

    /// What the user asked to see: the effective configuration, the version, the usage text.
    ///
    /// Unlike [Output], which is a subprocess's output and hidden unless `--verbose`,
    /// an answer is always shown.
    record Answer(String text)                          implements LampEvent {}

    /// A summary of a session's state, such as "running, up 3 minutes, one extra shell".
    ///
    /// This is a description of the supervisor's internal state, not the state itself, so the
    /// internal states can change without changing this public type.
    record SessionStatus(String state, String detail, Duration uptime, int extraShells) {}

    record SessionStateChanged(SessionStatus status)    implements LampEvent {}

    /// The session is running, and this is how to run a command in its sandbox.
    ///
    /// Reported once per session. An application reads the command from here rather than working
    /// out socket paths itself, because the engine is the one that knows them.
    ///
    /// @param session the session id, such as `20260928-120000`
    /// @param command an `ssh` command line. Append a command, as ssh expects it, and it runs in
    ///                the sandbox as the agent user, with the same environment as the agent's
    ///                shell, and with no terminal. It counts as an extra shell while it runs
    /// @param desktop the Unix socket of the sandbox's desktop. It speaks VNC (RFB 3.8) with no
    ///                password, because only this user can open it
    /// @param desktopWidth  the desktop's own width in pixels, `display.width`. A viewer may give
    ///                the desktop another size for a while, such as the size of a panel it shows
    ///                it in; this is the size to give it back
    /// @param desktopHeight the desktop's own height in pixels, `display.height`
    record SessionOpened(String session, Tuple<String> command, Path desktop, int desktopWidth, int desktopHeight)
            implements LampEvent {}

    /// The agent asks the user to look at its desktop, because it shows them something there.
    /// An application that shows the desktop can open it now.
    ///
    /// @param what what the agent shows, in its own words, such as "the chart you asked for".
    ///             Control characters are removed, and it is at most 200 characters long. Empty
    ///             when the agent did not say
    record LookAtDesktop(String what)                   implements LampEvent {}

    /// A window oillamp opened on the user's desktop: the terminal or a viewer.
    ///
    /// It carries the full command line, because when a window opens and closes again, the first
    /// question is what exactly was run.
    record WindowOpened(String what, Tuple<String> argv) implements LampEvent {}

    /// A block of lines with a title, such as the briefing when a session starts or the summary when it ends.
    record Summary(String title, Tuple<String> lines)   implements LampEvent {}

    /// A save made a snapshot. A save that finds nothing changed makes none, and reports an
    /// [Info] instead.
    ///
    /// @param files how many files the snapshot holds; 0 where that was not counted
    record Saved(LampSave snapshot, int files)          implements LampEvent {}

    /// A lamp's snapshots, newest first. What `oillamp history` answers.
    record History(Tuple<LampSave> snapshots)           implements LampEvent {}

    /// The lamp was brought back to `target`, and `result` is the snapshot that records it.
    /// When the lamp already was as `target` holds it, nothing changed and `result` is `target`.
    record Restored(LampSave target, LampSave result)   implements LampEvent {}

    // ─── the schedule, and the runs that wake the agent ───────────────────────────────────

    /// A lamp's schedule. What `oillamp schedule` answers.
    ///
    /// @param enabled whether `schedule.enabled` is on in `oillamp.toml`. When it is off, no job runs
    /// @param paused  whether the user paused the schedule with `oillamp schedule <dir> pause`
    /// @param zone    the time zone the jobs' times are read in, such as `Europe/Berlin`
    record Schedule(boolean enabled, boolean paused, String zone, Tuple<LampJob> jobs)
            implements LampEvent {}

    /// A job was added to the schedule, by the user or by the agent.
    record JobAdded(LampJob job)                        implements LampEvent {}
    /// A job was taken off the schedule.
    ///
    /// @param why for example "removed by the user", "expired" or "ran once, as it was meant to"
    record JobRemoved(LampJob job, String why)          implements LampEvent {}
    /// A job was switched on or off, or the whole schedule paused or resumed.
    record ScheduleChanged(String what)                 implements LampEvent {}

    /// One time the agent is woken, by a job or by someone asking. Runs happen one at a time.
    ///
    /// @param id           `run-` and a number counted per lamp, such as `run-12`. The snapshots
    ///                     taken as the run began and ended carry it too
    /// @param job          the job that woke the agent; empty when someone asked
    /// @param prompt       the job's prompt, or the question, as written
    /// @param conversation the conversation the run continues; empty for a new one
    record Run(String id, Optional<String> job, String prompt, Optional<String> conversation) {}

    /// A run waits, because the agent is busy.
    ///
    /// @param ahead runs waiting before this one, not counting the one in progress
    record RunQueued(Run run, int ahead)                implements LampEvent {}
    record RunStarted(Run run)                          implements LampEvent {}
    /// A run ended, and the lamp was saved.
    ///
    /// @param answer       the agent's last message; for a failed run, the error
    /// @param snapshot     the snapshot taken as the run ended; empty only when that save failed
    /// @param took         from [RunStarted] to this event
    /// @param conversation the conversation the run happened in; empty when pi failed before it
    ///                     opened one
    record RunFinished(Run run, RunOutcome outcome, String answer,
                       Optional<LampSave> snapshot, Duration took,
                       Optional<String> conversation) implements LampEvent {}

    /// The session queued a question as `run`. What `oillamp ask --no-wait` and [Lamp#send] report.
    record RunAccepted(Run run)                         implements LampEvent {}

    /// Something the agent did during a run, as pi reports it.
    ///
    /// @param run the run's id
    record RunProgress(String run, Progress progress)   implements LampEvent {}

    /// In one run: [Opened] once, then per message of the agent's, [Thought] and [Said] pieces and
    /// an [Answered], followed by a [ToolStarted] and a [ToolFinished] for each tool it calls.
    sealed interface Progress {
        /// The conversation the run happens in.
        record Opened(String conversation) implements Progress {}
        /// Part of the agent's message. The parts, joined, are the text [Answered] reports.
        record Said(String text) implements Progress {}
        /// Part of the model's thinking, for models that show it.
        record Thought(String text) implements Progress {}
        /// A message of the agent's is complete. The last one of a run is its answer.
        ///
        /// @param text   the message; when it failed, the error
        /// @param failed the model failed, or the message was stopped
        /// @param model  the model that wrote it, or failed to, as the service names it, such as
        ///               `azure/gpt-6.1-sol`; empty when pi did not say
        record Answered(String text, boolean failed, Optional<String> model) implements Progress {}
        /// A tool call began.
        ///
        /// @param call    the id the matching [ToolFinished] has
        /// @param summary the command for `bash`, the path for the file tools, otherwise the
        ///                arguments as JSON; one line, at most 160 characters
        record ToolStarted(String call, String tool, String summary) implements Progress {}
        /// A tool call ended.
        ///
        /// @param failed the tool reported an error
        /// @param output the first 4,000 characters of the output
        record ToolFinished(String call, boolean failed, String output) implements Progress {}
        /// A request to the model failed, and pi tries it again.
        ///
        /// @param attempt the try this is, from 1
        /// @param most    the tries pi makes before the run fails
        /// @param why     what the model service said
        record Retrying(int attempt, int most, String why) implements Progress {}
    }

    /// What `oillamp status` and [Lamp#agentStatus] report about the agent.
    ///
    /// @param current the run in progress; empty when the agent is idle
    /// @param waiting the runs waiting, in the order they will run
    record AgentStatus(Optional<Run> current, Tuple<Run> waiting) implements LampEvent {
        public boolean busy() { return current.isPresent() || !waiting.isEmpty(); }
    }

    /// How a run ended.
    enum RunOutcome {
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

    // ─── conversations ─────────────────────────────────────────────────────────────────────

    /// The conversations the agent had in a lamp, the most recent first. What
    /// `oillamp conversations <dir>` answers.
    record Conversations(Tuple<Lamp.Conversation> conversations) implements LampEvent {}

    /// One conversation, in full. What `oillamp conversations <dir> <conversation>` answers.
    record ConversationShown(Lamp.Conversation conversation) implements LampEvent {}

    // ─── host access ───────────────────────────────────────────────────────────────────────

    /// How risky a host request is, and how much it may change. The order is the ceiling: a
    /// request is judged at most at its own tier, and the checker may only put it lower.
    enum Risk {
        /// Reads, and returns what it read. It cannot change anything.
        PASSIVE,
        /// Changes something on the host, but in a way the user can see and undo.
        ADDITIVE,
        /// Runs a program on the host. Closed unless the lamp names the programs.
        EXECUTING
    }

    /// What the checker decided about one host request, and why.
    record Verdict(boolean allowed, Risk risk, String reason) {}

    /// The session has the host tools, and the agent can see them.
    ///
    /// @param tools   the tool names the agent is offered, such as `["host_read_file", …]`
    /// @param checker the model that judges requests as the session starts: the lamp's
    ///                `checker_model`, or else the model the agent answers with, which the
    ///                checker then follows when it changes
    record HostAccessEnabled(Tuple<String> tools, Optional<String> checker) implements LampEvent {}

    /// The agent asked the host for something, and the request passed the checks that need no
    /// model; the checker is asked next.
    ///
    /// @param run  the run the request came from, such as `run-12`; empty when the agent asked
    ///             outside a run, such as from a shell
    ///
    /// @param tool the tool it named, such as `host_read_file`
    /// @param said the arguments as one line, with no secret in it (the tool decides what that is)
    record HostAccessRequested(String run, String tool, String said) implements LampEvent {}

    /// The checker answered, and the request was then done or refused.
    ///
    /// @param verdict what it decided, its risk tier and its reason in its own words
    /// @param took    from the request arriving to this event
    record HostAccessDecided(String run, String tool, Verdict verdict, Duration took) implements LampEvent {}

    /// A request was refused other than by the checker's own decision: before the checker was
    /// asked, because it broke a rule that needs no judgement (a path outside the lamp's roots, a
    /// closed tier, a tool it does not have); because the checker could not be asked or answered
    /// beyond the tool's tier; because the person said no; or because the host could not do it.
    record HostAccessRefused(String run, String tool, String why) implements LampEvent {}

    /// The user is being asked about a request the checker allowed, and needs to look. The answer
    /// goes back on the session's control socket as `host-access-answer`, naming `what`.
    ///
    /// @param what what is about to happen, in words, and where
    record HostAccessAsked(String run, String tool, String what) implements LampEvent {}

    /// The values of `enabled` and `requirements` in the `[host_access]` table of a lamp's
    /// `oillamp.toml`. Reported by `oillamp host-access`, after reading them or after writing them.
    ///
    /// @param enabled      the value of `enabled`: whether a session gives the agent the host tools
    /// @param requirements the value of `requirements`: the text the checker model judges every
    ///                     host request against
    record HostAccessSettings(boolean enabled, String requirements) implements LampEvent {}

    /// The values under `[autosave]` in a lamp's `oillamp.toml`: when oillamp saves the lamp by
    /// itself. Reported by `oillamp autosave`, after reading them or after writing them.
    ///
    /// @param startup   as a session starts
    /// @param shutdown  as a session ends
    /// @param beforeRun just before a run wakes the agent
    /// @param afterRun  as a run ends
    record AutosaveSettings(boolean startup, boolean shutdown, boolean beforeRun, boolean afterRun)
            implements LampEvent {}

    /// Something went wrong but oillamp carried on.
    record Warning(Problem problem)                     implements LampEvent {}
    /// Something went wrong and oillamp stopped.
    record Failure(Problem problem)                     implements LampEvent {}
}
