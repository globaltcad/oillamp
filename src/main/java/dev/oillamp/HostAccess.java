package dev.oillamp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.lamp.LampEvent;
import dev.lamp.Problem;
import dev.lamp.LampRun;
import dev.lamp.LampHostVerdict;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sprouts.Tuple;

/// Supervised access to the host: the socket the sandbox asks on, the activity each request is
/// judged by, and the order in which a request becomes an effect or a refusal.
///
/// One request, in order:
///
/// 1. [Control.Server] reads one JSON line and calls [#answer] on that connection's thread.
/// 2. The line is read into a [HostTool.Call][HostTool.Call]. Anything else is refused there,
///    and not logged: it is not a request.
/// 3. The tier is checked against the lamp's settings, without a model: a closed tier is a
///    refusal even if the checker would allow it.
/// 4. Every path the tool names is resolved on the host. One inside the lamp is refused; one
///    outside the lamp's roots is refused too, unless `ask_the_user` lets the person decide.
/// 5. The checker is asked, with the requirements, the run's instruction, its recent tool calls,
///    its last reasoning, and the request. It has its own conversation and never sees the
///    agent's messages. Which run that is, the session knows: the sandbox does not get to say.
/// 6. A refusal is the answer. An allow is carried out here, with the paths resolved again at the
///    moment of the effect, because a link can be swapped in while a model is thinking.
///
/// The effect happens on the host. The pi extension in the sandbox only asks: it has nothing to
/// fake, because it has no capability of its own.
///
/// All of it is written to `logs/host-access-<session>.jsonl`, as one object per request. The
/// requirements and the reasoning are not in the log: they belong to the user and to the agent,
/// and the log is a record of what happened, not a copy of what was said.
final class HostAccess implements AutoCloseable {

    /// How long a person has to answer when the feature asks them. No answer is a refusal.
    static final Duration USER_PATIENCE = Duration.ofSeconds(60);

    /// How many tool calls of the current run the checker sees. Enough to show what the run is
    /// doing; short enough that the request itself stays the largest thing in the prompt.
    static final int ACTIVITY_KEPT = 12;

    /// The most of a request the agent gets back. A file read is cut to `max_read_bytes` before
    /// this, so this only bounds the shape of an answer, not the tool.
    static final int ANSWER_SENT = 128 * 1024;

    /// A request the checker allowed that the person must allow too, as the person is shown it.
    ///
    /// @param run  the run the request came from, or empty when the agent asked outside one
    /// @param tool the tool, such as `host_run`
    /// @param what what is about to happen, in words
    record RequestForPermission(String run, String tool, String what) {}

    private final Machine machine;
    private final LampLayout layout;
    private final LampConfig config;
    private final SessionId session;
    /// The user's home on the host, as the host was probed: what `~` means in a request, and the
    /// one root when the lamp names none. Never the sandbox's `/home/agent`.
    private final Path home;
    private final HostAccessChecker checker;
    private final Consumer<LampEvent> sink;
    /// Asks the session to show the person a request for permission, and returns whether the
    /// person allowed it. The session decides whether there is a person to ask at all; this only
    /// puts the request on its queue.
    private final Function<RequestForPermission, CompletableFuture<Boolean>> decisions;
    /// The run the agent is working on now, as the session's runner knows it. The request does
    /// not name its run: anything the sandbox sends is the agent's word, and the agent must not
    /// choose whose instruction the checker reads.
    private final Supplier<Optional<LampRun>> currentRun;
    /// What each run did, as the session's state holds it: read from the event loop's value, never
    /// written from here.
    private final Function<String, RunLog> activityNow;
    private final HostAccessTools tools;

    private final Object logLock = new Object();

    HostAccess(Machine machine, LampLayout layout, LampConfig config, SessionId session, Path home,
               HostAccessChecker checker, Consumer<LampEvent> sink,
               Function<RequestForPermission, CompletableFuture<Boolean>> decisions,
               Supplier<Optional<LampRun>> currentRun, Function<String, RunLog> activityNow) {
        this.machine = machine;
        this.layout = layout;
        this.config = config;
        this.session = session;
        this.home = home;
        this.checker = checker;
        this.sink = sink;
        this.decisions = decisions;
        this.currentRun = currentRun;
        this.activityNow = activityNow;
        this.tools = new HostAccessTools(machine, config.hostAccess(), layout.agentDir());
    }

    /// Tells the user, once, that the way out exists and what it is bounded by.
    void announce() {
        sink.accept(new LampEvent.HostAccessEnabled(HostTool.offeredBy(config.hostAccess()),
                Optional.of(checkerModelName())));
    }

    String checkerModelName() {
        return checker.model();
    }

    // ─── what each run did, as values ──────────────────────────────────────────────────────

    /// What one run did, for the checker to see. It is only ever replaced whole, by
    /// [#rememberRun], which the event loop calls. The gate reads the value it is given and never
    /// changes it, so the activity has one writer, like the rest of the session.
    ///
    /// @param instruction     the prompt of the run
    /// @param reasoning       the agent's own words, cut to the last [HostAccessChecker#REASONING_SENT]
    ///                        characters
    /// @param recentToolCalls the last [ACTIVITY_KEPT] tool calls, oldest first
    record RunLog(String run, String instruction, String reasoning, Tuple<String> recentToolCalls) {
        /// The log for a run nothing is known about yet, or that the session forgot: an old run's
        /// question is judged on the question alone, which is exactly how it used to be.
        static RunLog unknown(String run) {
            return new RunLog(run, "", "", Tuple.of(String.class));
        }

        HostAccessChecker.Activity activity() {
            return new HostAccessChecker.Activity(run, instruction, recentToolCalls, reasoning);
        }
    }

    /// The runs the session remembers, most recently changed first. Small on purpose: an old
    /// run's request is judged without its activity, because nobody keeps every run forever.
    static final int RUNS_REMEMBERED = 4;

    /// Folds what just happened into the run's log, which becomes the first; the run changed
    /// longest ago is forgotten when there are more than [#RUNS_REMEMBERED]. Called by the event
    /// loop, which is the one writer of every session value; a connection's thread never calls
    /// this.
    static Tuple<RunLog> rememberRun(Tuple<RunLog> known, RunLog change) {
        Tuple<RunLog> out = Tuple.of(RunLog.class, change);
        for (RunLog log : known)
            if (!log.run().equals(change.run()) && out.size() < RUNS_REMEMBERED) out = out.add(log);
        return out;
    }

    /// The log a run has in `known`, or an empty one.
    static RunLog logOf(Tuple<RunLog> known, String run) {
        for (RunLog log : known) if (log.run().equals(run)) return log;
        return RunLog.unknown(run);
    }

    /// The run's log with one more tool call, `tool: summary`; the oldest beyond
    /// [#ACTIVITY_KEPT] is dropped.
    static RunLog withToolCall(RunLog log, String tool, String summary) {
        Tuple<String> kept = log.recentToolCalls().add(tool + (summary.isBlank() ? "" : ": " + summary));
        while (kept.size() > ACTIVITY_KEPT) kept = kept.removeFirst();
        return new RunLog(log.run(), log.instruction(), log.reasoning(), kept);
    }

    /// The run's log after another piece of the agent's reasoning.
    static RunLog withThinking(RunLog log, String more) {
        String together = log.reasoning() + more;
        int most = HostAccessChecker.REASONING_SENT;
        if (together.length() > most) together = together.substring(together.length() - most);
        return new RunLog(log.run(), log.instruction(), together, log.recentToolCalls());
    }

    // ─── one request ───────────────────────────────────────────────────────────────────────

    /// Answers one request from the sandbox. Called on a connection's own thread, never on the
    /// event loop, because it waits for a model and may run a program.
    Control.Reply answer(Control.Request request) {
        Instant started = machine.now();
        String line = request.arguments().get("request").orElse("");
        Optional<LampRun> working = currentRun.get();
        String run = working.map(LampRun::id).orElse("");
        Result<HostTool.Call> read = HostTool.read(line);
        if (read instanceof Result.Err<HostTool.Call> bad) {
            String why = bad.problems().first().whatHappened();
            sink.accept(new LampEvent.HostAccessRefused(run, "?", why));
            return Control.Reply.failed(why);
        }
        HostTool.Call call = ((Result.Ok<HostTool.Call>) read).value();
        Attempt attempt = new Attempt(started, run, HostTool.nameOf(call.tool()),
                HostTool.tierOf(call.tool()).name().toLowerCase(Locale.ROOT), tuplesOf(call));

        // 1. The tier is the user's setting. Closed is closed, whatever a model would say.
        Optional<String> closed = HostTool.closedBecause(call.tool(), config.hostAccess());
        if (closed.isPresent()) return refuse(attempt, Tuple.of(HostPaths.ResolvedPath.class), closed.get());

        // 2. The paths, before anything else looks at them.
        Tuple<HostPaths.ResolvedPath> resolved = Tuple.of(HostPaths.ResolvedPath.class);
        boolean outside = false;
        for (String asked : HostTool.pathsOf(call.tool())) {
            Result<HostPaths.ResolvedPath> path = HostPaths.locate(asked, home);
            if (path instanceof Result.Err<HostPaths.ResolvedPath> bad)
                return refuse(attempt, resolved, bad.problems().first().whatHappened());
            HostPaths.ResolvedPath good = ((Result.Ok<HostPaths.ResolvedPath>) path).value();
            resolved = resolved.add(good);
            // `.oillamp` holds the keys, the logs and the history, and the lamp's own files are
            // not the agent's to read through this door or any other.
            if (insideTheLamp(good.hostPath()))
                return refuse(attempt, resolved, good.asked() + " is inside the lamp's own state, which no host request may reach");
            if (!HostPaths.within(good.hostPath(), home, config.hostAccess().roots())) {
                // The roots are the outer bound. Only the person may let a request past them.
                if (!config.hostAccess().askTheUser())
                    return refuse(attempt, resolved, HostAccessProblems.outsideTheRoots(asked,
                            "it is not under the lamp's host_access.roots").whatHappened());
                outside = true;
            }
        }
        // 3. What the tool itself rules out, whatever the checker would say.
        Optional<String> unfit = unfit(call.tool(), resolved);
        if (unfit.isPresent()) return refuse(attempt, resolved, unfit.get());
        sink.accept(new LampEvent.HostAccessRequested(run, attempt.tool(), HostTool.describe(call.tool())));

        // 4. The checker.
        Result<LampHostVerdict> verdict;
        try {
            verdict = checker.check(call, activityOf(working), resolved);
        } catch (RuntimeException bug) {
            verdict = Result.err(HostAccessProblems.checkerUnreachable(ProblemCatalogUtil.reason(bug)));
        }
        if (verdict instanceof Result.Err<LampHostVerdict> noVerdict)
            return refuse(attempt, resolved, noVerdict.problems().first().whatHappened());
        LampHostVerdict decided = ((Result.Ok<LampHostVerdict>) verdict).value();
        String risk = decided.risk().name().toLowerCase(Locale.ROOT);
        // The checker's own refusal is reported once, as its decision; it is not a second event.
        sink.accept(new LampEvent.HostAccessDecided(run, attempt.tool(), decided,
                Duration.between(started, machine.now())));
        if (!decided.allowed()) {
            log(attempt, resolved, "refused", risk, decided.reason(), 0);
            return Control.Reply.failed("refused: " + decided.reason());
        }
        // The checker may put a request lower than its tool's tier; it may not put it higher.
        if (decided.risk().compareTo(HostTool.tierOf(call.tool())) > 0)
            return refuse(attempt, resolved, "the checker called this " + risk + ", which is more than "
                    + attempt.tool() + " can do; nothing was done");

        // 5. The person's permission first, for a program, and for a path past the roots.
        if (call.tool() instanceof HostTool.Run || outside) {
            Optional<String> no = askTheUser(new RequestForPermission(run, attempt.tool(), HostTool.describe(call.tool())
                    + (outside ? " (outside the lamp's roots)" : "")));
            if (no.isPresent()) return refuse(attempt, resolved, no.get());
        }

        // 6. The work. Paths are resolved once more here, so a link swapped in while the model
        // thought cannot move the effect to another file.
        Optional<Problem> moved = recheckPaths(call, resolved);
        if (moved.isPresent()) return refuse(attempt, resolved, moved.get().whatHappened());

        Result<String> done;
        try {
            done = tools.perform(call, resolved);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            done = Result.err(HostAccessProblems.failed(attempt.tool(), "it was stopped"));
        } catch (IOException | RuntimeException failed) {
            done = Result.err(HostAccessProblems.failed(attempt.tool(), ProblemCatalogUtil.reason(failed)));
        }
        if (done instanceof Result.Err<String> failed) {
            Problem problem = failed.problems().first();
            sink.accept(new LampEvent.HostAccessRefused(run, attempt.tool(), problem.whatHappened()));
            log(attempt, resolved, "failed", risk, problem.whatHappened(), 0);
            return Control.Reply.failed(problem.whatHappened());
        }
        String answer = ((Result.Ok<String>) done).value();
        log(attempt, resolved, "done", risk, decided.reason(), answer.length());
        return Control.Reply.ok().with("result", answer.length() <= ANSWER_SENT
                ? answer : answer.substring(0, ANSWER_SENT) + "\n[…] (the rest was cut)");
    }

    /// What the checker is told about the run in progress. The state may not have heard of a run
    /// that began a moment ago, so its instruction comes from the runner, which started it.
    private HostAccessChecker.Activity activityOf(Optional<LampRun> working) {
        if (working.isEmpty()) return RunLog.unknown("").activity();
        RunLog log = activityNow.apply(working.get().id());
        return log.instruction().isEmpty()
                ? new RunLog(log.run(), working.get().prompt(), log.reasoning(), log.recentToolCalls()).activity()
                : log.activity();
    }

    /// One request on its way through [#answer], for the log and the events.
    private record Attempt(Instant started, String run, String tool, String tier, Tuple<String> arguments) {}

    /// What a tool rules out by itself, before any model: a `host_show` of something the desktop
    /// would run, and a `host_copy_out` from outside the sandbox's home.
    private static Optional<String> unfit(HostTool tool, Tuple<HostPaths.ResolvedPath> resolved) {
        return switch (tool) {
            case HostTool.Show _ -> HostAccessTools.whyNotShown(resolved.first().hostPath());
            case HostTool.CopyOut copy -> HostAccessTools.sourceInAgentHome(copy.from()) instanceof Result.Err<java.nio.file.Path> bad
                    ? Optional.of(bad.problems().first().whatHappened()) : Optional.empty();
            case HostTool.ListDir _, HostTool.Stat _, HostTool.ReadFile _, HostTool.ClipboardRead _,
                 HostTool.OpenUrl _, HostTool.WriteFile _, HostTool.ClipboardWrite _, HostTool.Notify _,
                 HostTool.Run _ -> Optional.empty();
        };
    }

    /// Whether a path is somewhere no host request may reach: the lamp itself, its state, its
    /// keys, its history, and the agent's home in it. Those are the user's, and oillamp's, not
    /// the agent's. Both sides have their links followed: a lamp reached through a link is the
    /// same lamp.
    private boolean insideTheLamp(Path path) {
        return path.startsWith(HostPaths.real(layout.root()))
            || path.startsWith(HostPaths.real(layout.stateDir()));
    }

    /// Checks the same paths again, immediately before the effect. A path that resolved one way
    /// when the checker was asked and another way now is a refusal, not a retry.
    private Optional<Problem> recheckPaths(HostTool.Call call, Tuple<HostPaths.ResolvedPath> before) {
        Tuple<String> asked = HostTool.pathsOf(call.tool());
        for (int i = 0; i < asked.size(); i++) {
            Result<HostPaths.ResolvedPath> path = HostPaths.locate(asked.get(i), home);
            if (path instanceof Result.Err<HostPaths.ResolvedPath> bad)
                return Optional.of(bad.problems().first());
            Path now = ((Result.Ok<HostPaths.ResolvedPath>) path).value().hostPath();
            if (!now.equals(before.get(i).hostPath()) || insideTheLamp(now))
                return Optional.of(HostAccessProblems.outsideTheRoots(asked.get(i),
                        "it changed while the request was being judged, which is never a good sign"));
        }
        return Optional.empty();
    }

    private Control.Reply refuse(Attempt attempt, Tuple<HostPaths.ResolvedPath> resolved, String why) {
        sink.accept(new LampEvent.HostAccessRefused(attempt.run(), attempt.tool(), why));
        log(attempt, resolved, "refused", "", why, 0);
        return Control.Reply.failed(why);
    }

    // ─── the user's turn ──────────────────────────────────────────────────────────────────

    /// Asks the person through the session, and waits for an answer.
    ///
    /// A person is the slowest and most careful checker there is, so this is reserved for the
    /// requests worth an interruption: running a program, and (with `ask_the_user`) a path past
    /// the roots. The session refuses at once when it has nobody to ask, and the request
    /// times out on its own, so this never waits longer than [#USER_PATIENCE].
    /// @return why the request may not go on, or empty when the person said yes
    private Optional<String> askTheUser(RequestForPermission request) {
        try {
            boolean yes = Boolean.TRUE.equals(decisions.apply(request)
                    .get(USER_PATIENCE.toMillis() + 1000, TimeUnit.MILLISECONDS));
            return yes ? Optional.empty() : Optional.of(HostAccessProblems.userSaidNo(request.tool()).whatHappened());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Optional.of(HostAccessProblems.failed(request.tool(), "it was stopped").whatHappened());
        } catch (TimeoutException slow) {
            return Optional.of(HostAccessProblems.userDidNotAnswer(USER_PATIENCE).whatHappened());
        } catch (ExecutionException ended) {
            return Optional.of(ended.getCause() instanceof TimeoutException
                    ? HostAccessProblems.userDidNotAnswer(USER_PATIENCE).whatHappened()
                    : HostAccessProblems.userNotAsked(request.tool(), Optional.ofNullable(ended.getCause())
                            .map(Throwable::getMessage).orElse("the request was withdrawn")).whatHappened());
        }
    }

    // ─── the log ──────────────────────────────────────────────────────────────────────────

    private void log(Attempt attempt, Tuple<HostPaths.ResolvedPath> resolved, String outcome, String risk,
                     String reason, long bytes) {
        try {
            Files.createDirectories(layout.logsDir());
            ObjectNode entry = JsonUtil.object();
            entry.put("ts", attempt.started().toString());
            entry.put("run", attempt.run());
            entry.put("tool", attempt.tool());
            entry.put("tier", attempt.tier());
            ObjectNode args = entry.putObject("arguments");
            for (String argument : attempt.arguments()) {
                int equals = argument.indexOf('=');
                if (equals > 0) args.put(argument.substring(0, equals), argument.substring(equals + 1));
            }
            ArrayNode paths = entry.putArray("resolved");
            for (HostPaths.ResolvedPath path : resolved) paths.add(path.hostPath().toString());
            entry.put("outcome", outcome);
            if (!risk.isEmpty()) entry.put("risk", risk);
            entry.put("reason", reason);
            entry.put("checker", checkerModelName());
            entry.put("ms", Duration.between(attempt.started(), machine.now()).toMillis());
            if (bytes > 0) entry.put("bytes", bytes);
            synchronized (logLock) {
                try (OutputStream out = Files.newOutputStream(logFile(), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    out.write((entry.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException unwritable) {
            // The log is for the user, not for the decision: a log that cannot be written must
            // not turn an allowed request into a refused one, or the other way round.
        }
    }

    /// `logs/host-access-<session>.jsonl`: one file per session, like the network log.
    Path logFile() {
        return layout.logsDir().resolve("host-access-" + session.value() + ".jsonl");
    }

    private static Tuple<String> tuplesOf(HostTool.Call call) {
        Tuple<String> out = Tuple.of(String.class);
        for (var argument : call.arguments()) out = out.add(argument.first() + "=" + shorten(argument.second()));
        return out;
    }

    private static String shorten(String text) {
        String single = ConsoleRenderer.NOT_PRINTABLE.matcher(text).replaceAll(" ");
        return single.length() <= 200 ? single : single.substring(0, 200) + "…";
    }

    @Override public void close() {
        // Nothing of its own to close: the socket belongs to the session's Control.Server.
    }
}
