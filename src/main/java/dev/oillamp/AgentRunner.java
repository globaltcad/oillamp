package dev.oillamp;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import dev.lamp.LampEvent;
import dev.lamp.LampJob;
import dev.lamp.LampJobAuthor;
import dev.lamp.LampRunOutcome;
import dev.lamp.LampSaveKind;

import dev.lamp.LampSave;
import dev.lamp.LampRun;
import dev.lamp.LampAgentProgress;
import sprouts.Association;
import sprouts.Tuple;

/// Hands prompts to the agent (pi, in the sandbox) one at a time, and waits for each answer.
/// Prompts come from two places: jobs on the schedule that are due, and `oillamp ask`. They wait in
/// one queue, so a question asked while a job is being worked on waits until it is done. Handling
/// one prompt is called a run (`run-12`).
///
/// A prompt is not passed on as written: [WakePromptUtil] puts the agent's notes and what recent
/// runs did in front of it. The lamp is saved before a run if anything changed, and after it
/// always, so the history keeps what the user did apart from what the agent did. A run is stopped
/// after `schedule.max_run_minutes`.
///
/// Due jobs are queued only while `schedule.enabled` is on, and only while they are late by no more
/// than `schedule.max_delay_minutes`; a later one is missed, and [LampEvent.RunMissed] says so. The
/// agent changes its own schedule through [#answerAgent], under the same rules as the user.
///
/// A run is numbered as it is queued, one above the highest number the history and this session
/// know of ([#numberRun]).
///
/// The queue is kept in memory only: prompts still waiting when the session ends are dropped. The
/// pi process belongs to [Harness], not to this class.
final class AgentRunner {

    /// How often the schedule is looked at when nothing has changed.
    static final Duration TICK = Duration.ofSeconds(30);
    /// How long the end of a session waits for a run to stop and be saved.
    static final Duration WIND_DOWN = Duration.ofMinutes(6);
    /// The most of an agent's last message kept in the snapshot's message.
    private static final int ANSWER_KEPT = 16 * 1024;

    private final Machine machine;
    private final Context context;
    private final LampLayout layout;
    private final LampConfig.Schedule config;
    private final LampConfig.Autosave autosave;
    private final LampConfig.History historyPolicy;
    private final SessionId session;
    private final ScheduleBook book;
    private final Harness harness;
    /// The session's queue. Every run's activity goes through it, so that one thread (the event
    /// loop) is the only writer of the state the host-access gate reads.
    private final java.util.function.Consumer<SessionEvent> post;

    private final LinkedBlockingDeque<Pending> queue = new LinkedBlockingDeque<>();
    private final Semaphore look = new Semaphore(0);
    private volatile Optional<Pending> current = Optional.empty();
    /// The run someone cancelled while it was in progress. Kept by name, so that a cancel that comes
    /// before the harness has started the run still stops it, and one that comes late stops no
    /// other run.
    private volatile Optional<String> cancelled = Optional.empty();
    private volatile boolean stopping;
    private volatile Optional<Thread> worker = Optional.empty();
    /// Why the schedule could not be read the last time it was looked at, so that the same reason
    /// is reported once, not every half minute. Used only by the thread that watches the schedule.
    private Optional<String> unreadable = Optional.empty();
    /// The highest run number this session has given out. A run's snapshots are made as it begins
    /// and ends, if at all, so the history does not yet know the runs that wait or work now.
    private int lastNumbered;

    /// A run waiting its turn, and whoever waits for its end.
    ///
    /// @param where where the question goes, or empty for a new conversation
    private record Pending(LampRun run, Optional<ScheduledJob> job, Optional<Harness.Target> where,
                           CompletableFuture<LampEvent.RunFinished> done) {}

    /// @param instructions what the agent is told in addition to pi's own instructions, for the
    ///                     whole session; empty to leave pi's instructions as they are
    /// @param autosave     whether a run is saved just before it begins and as it ends
    /// @param post         puts a run's activity on the session's queue, so that the one event loop
    ///                     keeps it in the state; the host-access gate reads it from there
    AgentRunner(Machine machine, Context context, LampLayout layout, LampConfig.Schedule config,
                 LampConfig.Autosave autosave, LampConfig.History historyPolicy, SessionId session,
                Optional<String> instructions, java.util.function.Consumer<SessionEvent> post) {
        this.machine = machine;
        this.historyPolicy = historyPolicy;
        this.context = context;
        this.layout = layout;
        this.config = config;
        this.autosave = autosave;
        this.session = session;
        this.book = new ScheduleBook(layout);
        this.post = post;
        this.harness = new Harness(machine, layout, context.options().model().id(), instructions);
    }

    /// Starts working, once the sandbox is up. Runs asked for before this wait until now.
    ///
    /// First the session's instructions and model are written into pi's files in the agent's
    /// home, so that pi, which the first run starts, reads them.
    void begin() {
        if (worker.isPresent()) return;
        harness.prepare(context.sink());
        worker = Optional.of(Thread.ofVirtual().name("oillamp-runs").start(this::performQueuedRuns));
        if (config.enabled()) Thread.ofVirtual().name("oillamp-schedule").start(this::watchTheSchedule);
    }

    /// A queued question: its run, and a future completed when the run ends.
    record Asked(LampRun run, CompletableFuture<LampEvent.RunFinished> done) {}

    /// Queues `prompt` as a new run.
    ///
    /// @param conversation the conversation it continues, or empty for a new one
    /// @param where        where in that conversation the question goes
    /// @return the queued run; an error when the session is ending
    Result<Asked> ask(String prompt, Optional<String> conversation, Optional<Harness.Target> where) {
        if (stopping) return Result.err(ProblemCatalogUtil.runRefused("the session is ending"));
        CompletableFuture<LampEvent.RunFinished> done = new CompletableFuture<>();
        LampRun run = new LampRun(numberRun(), Optional.empty(), prompt.strip(), conversation);
        enqueue(new Pending(run, Optional.empty(), where, done));
        return Result.ok(new Asked(run, done));
    }

    /// Has every run from the next one on answered by `model`, as the service names it, and
    /// makes it pi's default for new conversations.
    void useModel(String model) { harness.useModel(model, context.sink()); }

    /// The model the next run is answered by, as [#useModel] or `--model` last said; empty when
    /// the session leaves it to pi.
    Optional<String> model() { return harness.model(); }

    /// Stops a run: the one in progress, or one still waiting, which then never starts.
    ///
    /// @param run the run, or empty for the one in progress
    /// @return what was cancelled, or why nothing was
    Result<String> cancel(Optional<String> run) {
        Optional<Pending> working = current;
        if (working.isPresent() && run.map(id -> id.equals(working.get().run().id())).orElse(true)) {
            cancelled = Optional.of(working.get().run().id());
            return Result.ok(working.get().run().id());
        }
        if (run.isEmpty()) return Result.err(ProblemCatalogUtil.runRefused("the agent is not working on anything"));
        for (Pending waiting : queue)
            if (waiting.run().id().equals(run.get()) && queue.remove(waiting)) {
                LampEvent.RunFinished finished = new LampEvent.RunFinished(waiting.run(), LampRunOutcome.CANCELLED,
                        "cancelled before the agent got to it", Optional.empty(), Duration.ZERO, waiting.run().conversation());
                context.sink().accept(finished);
                waiting.done().complete(finished);
                return Result.ok(run.get());
            }
        return Result.err(ProblemCatalogUtil.runRefused("no run called " + run.get() + " is in progress or waiting"));
    }

    /// The run in progress and those waiting, for `oillamp status`.
    LampEvent.AgentStatus status() {
        Tuple<LampRun> waiting = Tuple.of(LampRun.class);
        for (Pending pending : queue) waiting = waiting.add(pending.run());
        return new LampEvent.AgentStatus(current.map(Pending::run), waiting);
    }

    /// Looks at the schedule now, because it changed.
    void scheduleChanged() { look.release(); }

    /// What the agent is doing, for `oillamp status`.
    String describe() {
        String waiting = queue.isEmpty() ? "" : ", " + queue.size() + " more waiting";
        return current.map(pending -> "working on " + pending.run().id() + " ("
                        + pending.run().job().map(job -> "job " + job).orElse("asked") + ")" + waiting)
                .orElse(queue.isEmpty() ? "idle" : "starting" + waiting);
    }

    boolean busy() { return current.isPresent() || !queue.isEmpty(); }

    /// The run in progress, if any. The host-access gate judges a request by this run, because
    /// the session knows which run the agent is in and the agent's own word for it is no fact.
    Optional<LampRun> currentRun() { return current.map(Pending::run); }

    /// Stops the run in progress, if any, and every waiting one, as the session ends. Waits until
    /// the run in progress is saved, for at most [#WIND_DOWN].
    void stop() {
        stopping = true;
        harness.stop();
        look.release();
        // A worker with no run waits for the next one with a timed poll, and would notice
        // `stopping` when the poll times out. That time-out has been seen to come more than a
        // minute late on a virtual thread, holding up the end of the session, so an idle worker
        // is woken at once. Never one working on a run: an interrupt could break the save at its
        // end. The worker sets `current` before it looks at `stopping`, and this method the other
        // way round, so one of the two always sees the other: either the worker is woken here, or
        // it sees `stopping` and leaves its run to be reported below as never started.
        if (current.isEmpty()) worker.ifPresent(Thread::interrupt);
        worker.ifPresent(thread -> {
            try {
                thread.join(WIND_DOWN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (Pending left; (left = queue.poll()) != null; ) {
            LampEvent.RunFinished finished = new LampEvent.RunFinished(left.run(), LampRunOutcome.INTERRUPTED,
                    "the session ended before the agent got to it", Optional.empty(), Duration.ZERO,
                    left.run().conversation());
            // An application that sent the question waits for this event, not for the future.
            context.sink().accept(finished);
            left.done().complete(finished);
        }
        harness.close();
    }

    // ─── the schedule ──────────────────────────────────────────────────────────────────────

    private void watchTheSchedule() {
        while (!stopping) {
            removeFinishedJobsAndQueueDueOnes();
            try {
                look.tryAcquire(TICK.toMillis(), TimeUnit.MILLISECONDS);
                look.drainPermits();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void removeFinishedJobsAndQueueDueOnes() {
        Instant now = machine.now();
        ZoneId zone = machine.zone();
        Result<Schedule> read = book.read();
        if (!(read instanceof Result.Ok<Schedule>(Schedule schedule, var _))) {
            Optional<String> why = Optional.of(read.problems().first().whatHappened());
            if (!why.equals(unreadable)) context.sinkAcceptProblems(read.problems().map(ProblemCatalogUtil::asWarning));
            unreadable = why;
            return;
        }
        if (unreadable.isPresent()) context.sink().accept(new LampEvent.Ok("schedule", "the schedule can be read again"));
        unreadable = Optional.empty();
        for (ScheduledJob finished : schedule.finished(now, zone))
            if (book.update(current -> Result.ok(current.without(finished.id())), s -> s).isOk())
                context.sink().accept(new LampEvent.JobRemoved(finished.describe(zone, now), finished.expiredAt(now)
                        ? "it expired" : "it ran, and will not run again"));
        for (ScheduledJob due : schedule.due(now, zone)) {
            if (stopping || isQueued(due.id())) continue;
            Optional<Instant> missed = due.missedAt(now, zone, config.maxDelay());
            if (missed.isPresent()) {
                passOver(due, missed.get(), now, zone);
                continue;
            }
            if (due.author() == LampJobAuthor.AGENT && agentRunsInADay(now) >= config.maxAgentRunsPerDay()) {
                book.update(current -> Result.ok(current.ran(due.id(), now)), s -> s);
                context.sink().accept(new LampEvent.Info("schedule",
                        due.id() + " was due, but was skipped: the agent's jobs ran "
                        + config.maxAgentRunsPerDay() + " times in the last 24 hours, the most "
                        + "`schedule.max_agent_runs_per_day` allows"));
                continue;
            }
            enqueue(new Pending(new LampRun(numberRun(), Optional.of(due.id()), due.prompt(), Optional.empty()),
                    Optional.of(due), Optional.empty(), new CompletableFuture<>()));
        }
    }

    /// Leaves out a run that is too late, and tells whoever listens, so that an application can
    /// still have the job's prompt answered if it wants to. A repeating job then waits for its
    /// next time after now; a job that runs once has none, and comes off the schedule.
    private void passOver(ScheduledJob job, Instant due, Instant now, ZoneId zone) {
        boolean once = job.when() instanceof ScheduledJob.When.Once;
        if (!book.update(current -> Result.ok(once ? current.without(job.id()) : current.ran(job.id(), now)), s -> s).isOk())
            return;
        LampJob passed = job.ranAt(now).describe(zone, now);
        context.sink().accept(new LampEvent.RunMissed(passed, due, Duration.between(due, now)));
        if (once) context.sink().accept(new LampEvent.JobRemoved(passed, "its one run, due at "
                + TimeNotationUtil.show(due, zone) + ", was missed: it was more than "
                + config.maxDelayMinutes() + " minutes late, the most `schedule.max_delay_minutes` allows"));
    }

    /// The name of a new run, such as `run-12`: one above the highest number on any branch of the
    /// history and the highest this session gave out, whichever is higher. Not a counter in the
    /// schedule file, because a restore or a switch brings that file back, and the counter with it.
    /// Every branch, because each branch has its own runs, and a number must name one run of the
    /// lamp, whichever branch it was on. Not the history alone either, because the runs that wait or
    /// work now are not in it yet. A history that cannot be read counts as one without runs.
    private synchronized String numberRun() {
        int saved = new History(layout).highestRun().orElseGet(problems -> 0);
        lastNumbered = Math.max(lastNumbered, saved) + 1;
        return "run-" + lastNumbered;
    }

    private boolean isQueued(String job) {
        return current.flatMap(pending -> pending.run().job()).filter(job::equals).isPresent()
            || queue.stream().anyMatch(pending -> pending.run().job().filter(job::equals).isPresent());
    }

    /// How many runs of the agent's own jobs began in the last 24 hours: those the history
    /// records on any branch, since every run costs tokens whichever branch it was on, and those in
    /// progress or waiting now.
    private int agentRunsInADay(Instant now) {
        int counted = (int) queue.stream().filter(pending -> pending.job().filter(job -> job.author() == LampJobAuthor.AGENT).isPresent()).count()
                + (current.flatMap(Pending::job).filter(job -> job.author() == LampJobAuthor.AGENT).isPresent() ? 1 : 0);
        return counted + new History(layout).agentRunsInADay(now).orElseGet(_ -> 0);
    }

    private void enqueue(Pending pending) {
        int ahead = queue.size() + (current.isPresent() ? 1 : 0);
        queue.add(pending);
        if (ahead > 0) context.sink().accept(new LampEvent.RunQueued(pending.run(), ahead - 1));
    }

    // ─── a run ─────────────────────────────────────────────────────────────────────────────

    private void performQueuedRuns() {
        while (!stopping) {
            Pending next;
            try {
                next = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (next == null) continue;
            current = Optional.of(next);
            if (stopping) {
                current = Optional.empty();
                queue.addFirst(next);
                return;
            }
            LampEvent.RunFinished finished;
            Optional<RuntimeException> crashed = Optional.empty();
            try {
                finished = perform(next);
            } catch (RuntimeException bug) {
                crashed = Optional.of(bug);
                // Whoever heard the run start must hear it end, or it seems to go on for ever.
                finished = new LampEvent.RunFinished(next.run(), LampRunOutcome.FAILED,
                        ProblemCatalogUtil.reason(bug), Optional.empty(), Duration.ZERO, next.run().conversation());
            }
            // Reported here, once, and outside the catch above: a listener that fails on this
            // event must not make the run look failed and finish a second time. Whoever waits for
            // the run is answered even then.
            try {
                if (crashed.isPresent())
                    context.sink().accept(new LampEvent.Warning(
                            ProblemCatalogUtil.runFailed(next.run().id(), ProblemCatalogUtil.reason(crashed.get()))));
                context.sink().accept(finished);
            } finally {
                next.done().complete(finished);
                current = Optional.empty();
            }
        }
    }

    private LampEvent.RunFinished perform(Pending pending) {
        LampRun run = pending.run();
        Instant started = machine.now();
        ZoneId zone = machine.zone();
        pending.job().ifPresent(job -> startedJob(job, started, zone));
        context.sink().accept(new LampEvent.RunStarted(run));
        post.accept(new SessionEvent.RunBegan(run.id(), run.prompt()));

        History history = new History(layout, historyPolicy, context.sink());
        try {
        Association<String, String> trailers = Association.between(String.class, String.class)
                .put(GitObjectUtil.RUN_TRAILER, run.id());
        Optional<String> base = Optional.empty();
        if (autosave.beforeRun()) {
            Result<History.Saving> before = history.save(LampSaveKind.BEFORE_RUN, "before " + run.id(), Optional.of(session),
                    trailers, false, started);
            if (before instanceof Result.Ok<History.Saving>(History.Saving saving, var _)) {
                saving.made().ifPresent(made -> context.sink().accept(new LampEvent.Saved(made, saving.files())));
                base = saving.latest().map(LampSave::id);
            } else {
                context.sinkAcceptProblems(before.problems().map(ProblemCatalogUtil::asWarning));
            }
        } else {
            // Without a save just before, the newest snapshot is the nearest to where the run
            // began, and what the run changed then also holds what changed since that snapshot.
            base = history.pinLatest().orElseGet(_ -> Optional.empty());
        }

        // A job wakes an agent that knows nothing of why, so its prompt carries the agent's notes
        // and recent runs. What a person asks goes as they wrote it: it is what a chat shows as
        // their message, and AGENTS.md already tells the agent to read its notes.
        String prompt = pending.job().isPresent() ? wakePrompt(pending, history, started, zone) : run.prompt();
        // A job's conversation is named after its run; a question someone asked is known by itself.
        // LampConversation#job reads the job back from this name, so the two change together.
        Optional<String> name = run.job().map(job -> run.id() + " (" + job + ")");
        Harness.Answer answer = harness.run(name, prompt, config.maxRun(), pending.where(),
                () -> cancelled.equals(Optional.of(run.id())),
                progress -> remember(progress, run.id()));
        if (answer.outcome() == LampRunOutcome.FAILED && !answer.text().isBlank() && answer.text().startsWith("pi "))
            context.sink().accept(new LampEvent.Warning(ProblemCatalogUtil.runFailed(run.id(), answer.text())));
        Instant ended = machine.now();

        String outcome = answer.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String said = answer.text().strip();
        if (said.length() > ANSWER_KEPT) said = said.substring(0, ANSWER_KEPT) + "\n[…]";
        Association<String, String> after = trailers
                .put(GitObjectUtil.AUTHOR_TRAILER, pending.job().map(job -> job.author() == LampJobAuthor.AGENT ? "agent" : "user").orElse("user"))
                .put(GitObjectUtil.OUTCOME_TRAILER, outcome);
        if (pending.job().isPresent()) after = after.put(GitObjectUtil.JOB_TRAILER, pending.job().get().id());
        if (base.isPresent()) after = after.put(GitObjectUtil.BASE_TRAILER, base.get());
        Optional<String> conversation = answer.conversation().or(run::conversation);
        if (conversation.isPresent()) after = after.put(GitObjectUtil.CONVERSATION_TRAILER, conversation.get());
        String message = run.id() + " (" + run.job().map(job -> "job " + job).orElse("asked") + ") " + outcome
                + (said.isEmpty() ? "" : "\n\n" + said);
        Optional<LampSave> snapshot = Optional.empty();
        if (autosave.afterRun()) {
            Result<History.Saving> saved = history.save(LampSaveKind.RUN, message, Optional.of(session), after, true, ended);
            if (saved instanceof Result.Ok<History.Saving>(History.Saving saving, var _)) {
                snapshot = saving.made();
                if (!saving.skipped().isEmpty())
                    context.sink().accept(new LampEvent.Warning(
                            ProblemCatalogUtil.filesNotSaved(layout.root(), saving.skipped())));
            } else {
                context.sinkAcceptProblems(saved.problems().map(ProblemCatalogUtil::asWarning));
            }
        }
        return new LampEvent.RunFinished(run, answer.outcome(), answer.text().strip(),
                snapshot, Duration.between(started, ended), conversation);
        } finally {
            history.releaseBase();
        }
    }

    /// Puts what the run did on the session's queue first, and only then tells the application:
    /// whoever has heard of a tool call can count on a host request made after it being judged
    /// with that call among the run's activity.
    private void remember(LampAgentProgress progress, String run) {
        if (progress instanceof LampAgentProgress.ToolStarted tool)
            post.accept(new SessionEvent.RunToolCalled(run, tool.tool(), tool.summary()));
        if (progress instanceof LampAgentProgress.Thought thought)
            post.accept(new SessionEvent.RunThought(run, thought.text()));
        context.sink().accept(new LampEvent.RunProgress(run, progress));
    }

    /// Records that a job's run began. A job that runs once is then done, and comes off the schedule.
    private void startedJob(ScheduledJob job, Instant now, ZoneId zone) {
        boolean once = job.when() instanceof ScheduledJob.When.Once;
        book.update(schedule -> Result.ok(once ? schedule.without(job.id()) : schedule.ran(job.id(), now)), s -> s);
        if (once) context.sink().accept(
                new LampEvent.JobRemoved(job.describe(zone, now), "it runs once, and this is that run"));
    }

    private String wakePrompt(Pending pending, History history, Instant now, ZoneId zone) {
        Optional<String> notes = FilesystemUtil.readString(layout.workspace().resolve("NOTES.md"));
        Tuple<WakePromptUtil.Described> recent = recentRuns(history, WakePromptUtil.RECENT_RUNS);
        WakePromptUtil.Reason reason = pending.job().<WakePromptUtil.Reason>map(WakePromptUtil.Reason.ByJob::new)
                .orElseGet(WakePromptUtil.Reason.Asked::new);
        return WakePromptUtil.render(reason, pending.run().prompt(), notes, config.notesMaxKb() * 1024, recent,
                                     autosave.afterRun(), now, zone);
    }

    /// The last `count` runs on the current branch, newest first, with what each changed. Another
    /// branch's runs are left out: the agent remembers the state it is in, not the others.
    private static Tuple<WakePromptUtil.Described> recentRuns(History history, int count) {
        Tuple<WakePromptUtil.Described> found = Tuple.of(WakePromptUtil.Described.class);
        Result<Tuple<GitObjectUtil.Commit>> commits = history.commitList();
        if (!(commits instanceof Result.Ok<Tuple<GitObjectUtil.Commit>>(Tuple<GitObjectUtil.Commit> all, var _))) return found;
        for (GitObjectUtil.Commit commit : all) {
            if (found.size() >= count) break;
            Optional<WakePromptUtil.Past> past = WakePromptUtil.Past.of(commit);
            if (past.isEmpty()) continue;
            found = found.add(describe(history, past.get(), WakePromptUtil.CHANGES_SHOWN));
        }
        return found;
    }

    private static WakePromptUtil.Described describe(History history, WakePromptUtil.Past past, int most) {
        Optional<History.Changes> changes = history.runChanges(past.base(), past.tree(), most)
                .orElseGet(_ -> Optional.empty());
        return changes.map(found -> new WakePromptUtil.Described(past, found.shown(), found.more(), true))
                .orElseGet(() -> new WakePromptUtil.Described(past, Tuple.of(History.Change.class), false, false));
    }

    // ─── the agent's requests ──────────────────────────────────────────────────────────────

    /// Answers the agent, which asks through the schedule socket in the sandbox. Every request is
    /// judged by the same rules as the user's, plus the limits on the agent's own jobs; anything
    /// it sends is data, never a command to run.
    ///
    /// The answers are written for the agent to read: a tool shows the `text` of a reply, or its
    /// `error`, as they are.
    Control.Reply answerAgent(Control.Request request) {
        Instant now = machine.now();
        ZoneId zone = machine.zone();
        return switch (request.op()) {
            case "list" -> book.read().map(schedule -> Control.Reply.ok().with("text", listing(schedule, now, zone)))
                    .orElseGet(problems -> Control.Reply.failed(problems.first().whatHappened()));
            case "add" -> {
                Schedule.Request asked = new Schedule.Request(present(request, "cron"), present(request, "at"),
                        request.arguments().get("prompt").orElse(""), present(request, "expires"));
                Result<Schedule.Changed> added = book.update(schedule -> schedule.add(asked, LampJobAuthor.AGENT, now, zone, config),
                        Schedule.Changed::schedule);
                if (!(added instanceof Result.Ok<Schedule.Changed>(Schedule.Changed change, var _)))
                    yield Control.Reply.failed(added.problems().first().whatHappened());
                context.sink().accept(new LampEvent.JobAdded(change.job().describe(zone, now)));
                look.release();
                yield Control.Reply.ok().with("text", "Added " + change.job().id() + ": " + describe(change.job(), zone)
                        + (config.enabled() ? "" : " The schedule is switched off by the user, so it will not run until they switch it on."));
            }
            case "remove" -> {
                Result<Schedule.Changed> removed = book.update(schedule -> schedule.remove(
                        request.arguments().get("id").orElse(""), LampJobAuthor.AGENT, layout.root()), Schedule.Changed::schedule);
                if (!(removed instanceof Result.Ok<Schedule.Changed>(Schedule.Changed change, var _)))
                    yield Control.Reply.failed(removed.problems().first().whatHappened());
                context.sink().accept(
                        new LampEvent.JobRemoved(change.job().describe(zone, now), "removed by the agent"));
                yield Control.Reply.ok().with("text", "Removed " + change.job().id() + ".");
            }
            case "history" -> Control.Reply.ok().with("text", history(request.arguments().get("run").orElse("").strip(), zone));
            default -> Control.Reply.failed("unknown request: " + request.op());
        };
    }

    private static Optional<String> present(Control.Request request, String key) {
        return request.arguments().get(key).filter(value -> !value.isBlank());
    }

    private String listing(Schedule schedule, Instant now, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        out.append(!config.enabled() ? "The schedule is switched off by the user: no job runs.\n"
                 : schedule.paused() ? "The user has paused the schedule: no job runs until they resume it.\n" : "");
        out.append("Times are on the clock of ").append(zone.getId()).append("; it is now ").append(TimeNotationUtil.show(now, zone))
           .append(". Your jobs: ").append(schedule.byAgent().size()).append(" of at most ").append(config.maxAgentJobs())
           .append(".\n");
        if (schedule.jobs().isEmpty()) return out.append("There are no jobs.").toString();
        for (ScheduledJob job : schedule.jobs())
            out.append("\n- ").append(job.id()).append(job.author() == LampJobAuthor.AGENT ? " (yours)" : " (the user's; you cannot change it)")
               .append(": ").append(describe(job, zone)).append("\n  Task: ").append(job.prompt().strip().replace("\n", "\n  ")).append('\n');
        return out.toString();
    }

    private static String describe(ScheduledJob job, ZoneId zone) {
        return "runs " + (job.when() instanceof ScheduledJob.When.Once ? "" : "on \"") + job.describeWhen(zone)
                + (job.when() instanceof ScheduledJob.When.Once ? "" : "\"")
                + (!job.enabled() ? ", switched off by the user" : job.next(zone).map(next -> ", next at " + TimeNotationUtil.show(next, zone)).orElse(""))
                + job.expires().map(end -> ", until " + TimeNotationUtil.show(end, zone)).orElse("") + ".";
    }

    /// One run in full, or a list of the recent ones when `run` is empty: those on the current
    /// branch, as in the wake prompt.
    private String history(String run, ZoneId zone) {
        History history = new History(layout);
        Result<Tuple<GitObjectUtil.Commit>> commits = history.commitList();
        if (!(commits instanceof Result.Ok<Tuple<GitObjectUtil.Commit>>(Tuple<GitObjectUtil.Commit> all, var _)))
            return "The lamp's history cannot be read: " + commits.problems().first().whatHappened();
        Tuple<WakePromptUtil.Past> runs = Tuple.of(WakePromptUtil.Past.class);
        for (GitObjectUtil.Commit commit : all) runs = WakePromptUtil.Past.of(commit).map(runs::add).orElse(runs);
        if (run.isEmpty()) {
            if (runs.isEmpty()) return "There have been no runs yet.";
            StringBuilder out = new StringBuilder("The last runs, newest first:\n");
            for (WakePromptUtil.Past past : runs.stream().limit(20).toList())
                out.append("\n- ").append(past.run()).append(", ").append(TimeNotationUtil.show(past.at(), zone)).append(", ")
                   .append(past.job().orElse("asked by the user")).append(": ").append(past.outcome());
            return out.append("\n\nGive a run's name for what it changed and what you said at its end.").toString();
        }
        Optional<WakePromptUtil.Past> found = runs.stream().filter(past -> past.run().equals(run)).findFirst();
        if (found.isEmpty()) return "There is no run called '" + run + "'. Leave the run out to list the recent ones.";
        WakePromptUtil.Past past = found.get();
        WakePromptUtil.Described described = describe(history, past, 100);
        StringBuilder out = new StringBuilder();
        out.append(past.run()).append(", ").append(TimeNotationUtil.show(past.at(), zone)).append(", ")
           .append(past.job().map(job -> "for " + job).orElse("asked by the user")).append(": ").append(past.outcome()).append(".\n");
        out.append(!described.available() ? "\nChanges unavailable: the starting snapshot is no longer retained.\n"
                : described.changes().isEmpty() ? "\nIt changed no files.\n" : "\nIt changed:\n");
        for (History.Change change : described.changes())
            out.append("- ").append(change.path()).append(" (").append(change.kind()).append(")\n");
        if (described.more()) out.append("- and more\n");
        if (!past.answer().isBlank()) out.append("\nWhat you said at its end:\n\n").append(past.answer()).append('\n');
        return out.toString();
    }
}
