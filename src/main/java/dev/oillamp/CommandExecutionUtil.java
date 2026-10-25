package dev.oillamp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.lamp.*;

import sprouts.Tuple;

/// Runs what the command line asks for. [#execute] parses it into a [Command] and calls the method
/// for that command.
///
/// These methods run the phases in order, turn their results into exit codes, and report
/// through [Context]. The decisions are made by the pure planners they call.
final class CommandExecutionUtil {

    /// How long `oillamp ask` waits for its answer. The agent may be busy with other runs first,
    /// each of which may take up to `schedule.max_run_minutes`. If the session ends meanwhile,
    /// the connection closes, and the wait ends with it.
    private static final Duration ASK_PATIENCE = Duration.ofDays(1);

    /// One oillamp container, as `podman ps` describes it.
    private record RunningSandbox(String name, String state, String lamp) {}


    private CommandExecutionUtil() {}

    static LampExitStatus execute(
        final Machine machine,
        final Consumer<LampEvent> sink,
        final ConsoleRenderer console,
        final String version,
        final Tuple<String> arguments
    ) {
        // Before anything is printed, so that even a usage error comes without colour.
        if (arguments.contains("--no-color")) console.withoutColour();
        // Likewise: an application reading standard output must never see a line that is not JSON.
        if (arguments.contains("--embedded")) console.asJsonLines();

        Result<Command> parsed = Command.parse(arguments);
        if (parsed instanceof Result.Err<Command> refused) {
            console.banner(version, "");
            for (LampProblem problem : refused.problems()) sink.accept(new LampEvent.Failure(problem));
            return LampExitStatus.USAGE;
        }
        Command command = ((Result.Ok<Command>) parsed).value();

        // The renderer was created before the options were known, so tell it now.
        console.verbose(command.options().verbose());
        Context context = new Context(sink, command.options(), version);

        return switch (command) {
            case Command.NoCommand _ -> {
                console.banner(version, "");
                sink.accept(new LampEvent.Answer(Command.usage()));
                yield LampExitStatus.USAGE;
            }
            case Command.Version _ -> {
                sink.accept(new LampEvent.Answer("oillamp " + version + "\njava " + Runtime.version()));
                yield LampExitStatus.SUCCESS;
            }
            case Command.Help _ -> {
                sink.accept(new LampEvent.Answer(Command.usage()));
                yield LampExitStatus.SUCCESS;
            }
            case Command.About _ -> {
                sink.accept(new LampEvent.Answer(IntroductionTextUtil.about(version)));
                yield LampExitStatus.SUCCESS;
            }
            case Command.Guide _ -> {
                sink.accept(new LampEvent.Answer(IntroductionTextUtil.guide()));
                yield LampExitStatus.SUCCESS;
            }
            case Command.Completion _ -> {
                // Straight to stdout, with no banner: the output is meant to be evaluated by a
                // shell, and anything else printed would be evaluated along with it.
                console.plain(GeneratedFileTextUtil.bashCompletion());
                yield LampExitStatus.SUCCESS;
            }
            // Explain that there is no such command, instead of answering "unknown command".
            case Command.Image _ -> {
                sink.accept(new LampEvent.Failure(ProblemCatalogUtil.usage(
                        "there is no 'image' command: oillamp builds the sandbox image again by itself "
                      + "when its settings or oillamp's own image files change. To get newer agent "
                      + "tools, or newer Debian packages, use `oillamp update <dir>` or "
                      + "`oillamp update <dir> --system`",
                        Command.usage())));
                yield LampExitStatus.USAGE;
            }
            case Command.Doctor doctor -> {
                console.banner(version, doctor.lamp().map(Path::toString).orElse(""));
                yield doctor(machine, context, doctor.lamp());
            }
            case Command.Config config -> switch (config.action()) {
                case CHECK          -> checkConfig(machine, context, config.lamp());
                case SHOW_EFFECTIVE -> showEffectiveConfig(machine, context, config.lamp());
                case PATH -> {
                    sink.accept(new LampEvent.Answer(config.lamp().resolve("oillamp.toml").toString()));
                    yield LampExitStatus.SUCCESS;
                }
            };
            case Command.At at -> {
                console.banner(version, at.lamp().toString());
                yield at(machine, context, at.lamp());
            }
            case Command.Update update -> {
                console.banner(version, update.lamp().toString());
                yield update(machine, context, update.lamp(), update.system());
            }
            // These five talk to a running session through its control socket. None of them
            // sets anything up.
            case Command.View view     -> view(machine, context, view.lamp(), view.viewOnly());
            case Command.Shell shell   -> shell(machine, context, shell.lamp());
            case Command.Stop stop     -> stop(machine, context, stop.lamp());
            case Command.Status status -> status(machine, context, status.lamp());
            case Command.Follow follow -> follow(machine, context, follow.lamp());
            case Command.List _        -> list(machine, context);
            // Talks to no session, and refuses if one answers.
            case Command.Remove remove -> {
                console.banner(version, remove.lamps().size() == 1 ? remove.lamps().first().toString()
                                                                    : remove.lamps().size() + " lamps");
                yield remove(machine, context, remove.lamps(), remove.confirmed());
            }
            case Command.Recordings recordings ->
                    recordings(machine, context, recordings.lamp(), recordings.open(), recordings.prune());
            // The lamp's history. `restore`, `switch`, `reset` and writing `autosave` or `max-growth` need the lamp
            // stopped; the others do not.
            case Command.History history -> switch (history.action()) {
                case Command.History.Action.ListSnapshots list -> history(machine, context, history.lamp(), list.all());
                case Command.History.Action.ListBranches _ -> branches(machine, context, history.lamp());
                case Command.History.Action.Prune prune -> whileStopped(machine, context, history.lamp(), "prune", layout -> {
                    Result<Optional<LampEvent.HistoryPruned>> result = historyWriter(machine, context, layout)
                            .flatMap(writer -> writer.prune(prune.confirmed(), machine.now()));
                    if (result instanceof Result.Err<Optional<LampEvent.HistoryPruned>> failure) {
                        context.sinkAcceptProblems(failure.problems());
                        return exitStatusFor(failure.problems());
                    }
                    Optional<LampEvent.HistoryPruned> event = ((Result.Ok<Optional<LampEvent.HistoryPruned>>) result).value();
                    event.ifPresent(e -> context.sink().accept(prune.confirmed() ? e
                            : new LampEvent.HistoryPrunePlanned(e.removed(), e.boundaries(), e.bytesFreed(), e.remainingBytes(), e.limitBytes())));
                    if (event.isEmpty()) context.sink().accept(new LampEvent.Info("history", "nothing to prune"));
                    return !prune.confirmed() && event.filter(e -> !e.removed().isEmpty() || e.bytesFreed() > 0).isPresent()
                            ? LampExitStatus.USAGE : LampExitStatus.SUCCESS;
                });
                case Command.History.Action.Save save -> {
                    console.banner(version, history.lamp().toString());
                    yield save(machine, context, history.lamp(), save.message());
                }
                case Command.History.Action.Restore restore -> {
                    console.banner(version, history.lamp().toString());
                    yield restore(machine, context, history.lamp(), restore.snapshot());
                }
                case Command.History.Action.CreateBranch create -> {
                    console.banner(version, history.lamp().toString());
                    yield createBranch(machine, context, history.lamp(), create.name(), create.snapshot());
                }
                case Command.History.Action.DeleteBranch delete -> {
                    console.banner(version, history.lamp().toString());
                    yield deleteBranch(machine, context, history.lamp(), delete.name(), delete.confirmed());
                }
                case Command.History.Action.Switch switched -> {
                    console.banner(version, history.lamp().toString());
                    yield switchBranch(machine, context, history.lamp(), switched.branch());
                }
                case Command.History.Action.Reset reset -> {
                    console.banner(version, history.lamp().toString());
                    yield reset(machine, context, history.lamp(), reset.snapshot(), reset.confirmed());
                }
                case Command.History.Action.Autosave asked -> autosave(machine, context, history.lamp(), asked);
                case Command.History.Action.MaxGrowth asked -> maxGrowth(machine, context, history.lamp(), asked.gb());
            };
            // The schedule, and the agent it wakes. `schedule` works with or without a session;
            // `ask` needs one, since the session is what holds the agent.
            case Command.Schedule schedule -> schedule(machine, context, schedule.lamp(), schedule.action());
            case Command.Conversations conversations ->
                    conversations(machine, context, conversations.lamp(), conversations.conversation());
            case Command.Ask ask       -> ask(machine, context, ask);
            case Command.Cancel cancel -> cancel(machine, context, cancel.lamp(), cancel.run());
            case Command.Model model   -> model(machine, context, model.lamp(), model.model());
            case Command.HostAccess access -> hostAccess(machine, context, access.lamp(), access.enabled(),
                                                          access.requirementsEnv());
            case Command.Answer answer -> answer(machine, context, answer.lamp(), answer.yes(), answer.question());
        };
    }

    /// `oillamp doctor [<dir>]`: check the host (and the lamp's configuration), change nothing.
    ///
    /// Does not require a graphical session, because finding out that you are on a plain SSH
    /// login is one of the reasons to run it.
    private static LampExitStatus doctor(
        final Machine machine,
        final Context context,
        final Optional<Path> lampPath
    ) {
        Context.Options original = context.options();
        HostPhase host = new HostPhase(machine, new Context(context.sink(),
                original.withDryRun(true).withAutoInstall(false), context.version()));
        HostPhase.Outcome outcome = host.prepare(lampPath.orElse(Path.of(".")), false, Installing.NEVER);

        if (!outcome.succeeded()) {
            context.sinkAcceptProblems(outcome.result().problems());
            return HostPhase.exitStatusFor(outcome.result().problems());
        }
        context.sinkAcceptProblems(outcome.result().warnings());

        if (lampPath.isEmpty()) {
            context.sink().accept(new LampEvent.Ok("host", "this machine can run oillamp sandboxes"));
            return LampExitStatus.SUCCESS;
        }
        return checkConfiguration(machine, context, lampPath.get(), false);
    }

    /// What the agent is told in addition to pi's own instructions in this session: the text of
    /// the variable `--instructions-env` names, or empty without that option.
    ///
    /// @return an error when the variable is not set, is empty, or holds more than
    ///         [PiSetupUtil#LONGEST_INSTRUCTIONS]
    private static Result<Optional<String>> instructions(Machine machine, Context context) {
        Optional<String> variable = context.options().instructionsEnv();
        if (variable.isEmpty()) return Result.ok(Optional.empty());
        Optional<String> text = machine.environmentVariable(variable.get());
        Optional<String> wrong = text.isEmpty() ? Optional.of("the variable is not set, or empty")
                               : PiSetupUtil.instructionsProblem(text.get());
        if (wrong.isPresent())
            return Result.err(ProblemCatalogUtil.usage("--instructions-env " + variable.get() + ": " + wrong.get(),
                    Command.usageOf("at")));
        return Result.ok(text);
    }

    /// `oillamp at <dir>`: prepare the host and the lamp, then run a session.
    ///
    /// Runs the phases in order: host, lamp, image and sandbox, then the [Supervisor]. It
    /// does not return when the sandbox is up, but when the session is over, so that one Ctrl-C,
    /// one closed window or one `oillamp stop` takes down everything it created.
    private static LampExitStatus at(
        final Machine machine,
        final Context context,
        final Path lampPath
    ) {
        // Read now, before anything changes on the machine, so that a missing variable stops
        // the session before it starts rather than leave the agent without its instructions.
        Result<Optional<String>> instructions = instructions(machine, context);
        if (instructions instanceof Result.Err<Optional<String>> wrong) {
            context.sinkAcceptProblems(wrong.problems());
            return LampExitStatus.USAGE;
        }
        // Only a session that opens windows needs the host's display.
        boolean opensWindows = !context.options().embedded() && context.options().openWindows();
        HostPhase.Outcome host = new HostPhase(machine, context)
                .prepare(lampPath, opensWindows, installing(machine, context, lampPath));
        if (!host.succeeded()) {
            context.sinkAcceptProblems(host.result().problems());
            return HostPhase.exitStatusFor(host.result().problems());
        }
        context.sinkAcceptProblems(host.result().warnings());

        Result<LampPhase.Prepared> lamp = new LampPhase(machine, context).prepare(lampPath, host.facts());
        if (lamp instanceof Result.Err<LampPhase.Prepared> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampPhase.Prepared prepared = ((Result.Ok<LampPhase.Prepared>) lamp).value();
        context.sinkAcceptProblems(lamp.warnings());

        // A dry run also plans the image and container steps, so the full podman command is
        // shown. It takes no lock, so it cannot block a session that is really running.
        if (context.options().dryRun()) {
            Result<SandboxPhase.Running> planned =
                    new SandboxPhase(machine, context).start(prepared, host.facts());
            if (planned instanceof Result.Err<SandboxPhase.Running> failure) {
                context.sinkAcceptProblems(failure.problems());
                return LampExitStatus.ERROR;
            }
            context.sinkAcceptProblems(planned.warnings());
            context.sink().accept(new LampEvent.Info("lamp", "dry run — nothing above was actually done"));
            return LampExitStatus.SUCCESS;
        }

        // One session per lamp. The OS releases the lock when the process dies, however it dies,
        // so a crashed supervisor never blocks the next run.
        Optional<LampLock> lock;
        try {
            lock = LampLock.tryAcquire(prepared.layout().lockFile());
        } catch (IOException e) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.lampNotWritable(prepared.layout().root(), ProblemCatalogUtil.reason(e))));
            return LampExitStatus.ERROR;
        }
        if (lock.isEmpty()) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class, busyProblem(prepared)));
            return LampExitStatus.LAMP_BUSY;
        }

        LampLock held = lock.get();
        // A process killed during a run leaves its base pin behind. No session is alive while we
        // hold this lock, so that pin is no longer in use and must not defeat future retention.
        new History(prepared.layout()).releaseBase();
        // Before the sandbox runs, so the snapshot holds the lamp exactly as the last session
        // left it, and this session can always be undone; when `autosave.startup` is on. A save
        // that fails is a warning: the session is still worth having.
        if (prepared.config().autosave().startup())
            for (LampProblem problem : saveLamp(context, prepared.layout(), LampSaveKind.STARTUP, "",
                                            Optional.of(prepared.session()), machine.now(), prepared.config().history()))
                context.sink().accept(new LampEvent.Warning(problem));
        // Until the supervisor takes over, nothing else would remove a container this run started:
        // not a failed start, and not a Ctrl-C while the image builds or the desktop comes up.
        ContainerName container = prepared.layout().containerName();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread abandon = new Thread(() -> {
            interrupted.set(true);
            context.sink().accept(new LampEvent.Info("session",
                    "interrupted while the sandbox was starting — removing it"));
            removeUnfinishedSandbox(machine, context, container, "interrupted while the sandbox was starting");
        }, "oillamp-abandon-start");
        Runtime.getRuntime().addShutdownHook(abandon);
        try {
            context.sink().accept(new LampEvent.Ok("lamp", "ready — agent " + prepared.layout().agentId()
                    + ", desktop " + prepared.config().display().size()
                    + ", renderer " + prepared.gpu().renderer()));

            Result<SandboxPhase.Running> sandbox =
                    new SandboxPhase(machine, context).start(prepared, host.facts());
            if (sandbox instanceof Result.Err<SandboxPhase.Running> failure) {
                // After a Ctrl-C the start fails because the hook removed the container. Saying
                // "the sandbox stopped while starting up" would blame the sandbox for the user's
                // own interrupt.
                if (interrupted.get()) return LampExitStatus.INTERRUPTED;
                context.sinkAcceptProblems(failure.problems());
                removeUnfinishedSandbox(machine, context, container, "it did not start");
                return LampExitStatus.ERROR;
            }
            context.sinkAcceptProblems(sandbox.warnings());

            SandboxPhase.Running running = ((Result.Ok<SandboxPhase.Running>) sandbox).value();
            context.sink().accept(new LampEvent.Ok("session", "sandbox running — container " + running.container()));
            // oillamp connected to both sockets (CheckEndpoints) before printing this.
            context.sink().accept(new LampEvent.Ok("session", "desktop and shell both answering — "
                    + "oillamp connected to each socket before handing it over"));

            // From here `at` does not return until the session is over, and the supervisor
            // removes the container however the session ends.
            stopWatching(abandon);
            return new Supervisor(machine, context, host.facts(), prepared, running,
                                  ((Result.Ok<Optional<String>>) instructions).value()).run();
        } catch (RuntimeException bug) {
            // A bug while the sandbox starts reaches the user as a problem; the container it
            // started must not outlive it.
            removeUnfinishedSandbox(machine, context, container, "oillamp failed while it started");
            throw bug;
        } finally {
            stopWatching(abandon);
            try {
                held.close();
            } catch (IOException e) {
                context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                        ProblemCatalogUtil.internal("lock release", ProblemCatalogUtil.reason(e))));
            }
        }
    }

    /// `oillamp update <dir> [--system]`: build a new image for this lamp, with opencode, pi and
    /// opencode's model list installed again, and start nothing.
    ///
    /// Without `system`, podman's cache keeps every layer before the harness layer, and the base
    /// image is the one podman already has. With it, the base image is pulled again and every
    /// layer runs again, so Debian's packages are updated too.
    ///
    /// The lamp switches to the new image only after the build succeeded: only then is the new
    /// revision written to `.oillamp/image/tools-revision`, which every later start passes as a
    /// build argument. A failed update therefore leaves the lamp on the image it had. The agent's
    /// home is not touched.
    private static LampExitStatus update(Machine machine, Context context, Path lampPath, boolean system) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<LampConfig> loaded = loadConfig(machine, layout.root());
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig config = ((Result.Ok<LampConfig>) loaded).value();
        context.sinkAcceptProblems(loaded.warnings());
        Installing installing = !context.options().autoInstall() ? Installing.DECLINED
                : config.host().autoInstall() ? Installing.ALLOWED : Installing.DECLINED_IN_CONFIG;
        HostPhase.Outcome host = new HostPhase(machine, context).prepare(layout.root(), false, installing);
        if (!host.succeeded()) {
            context.sinkAcceptProblems(host.result().problems());
            return HostPhase.exitStatusFor(host.result().problems());
        }
        context.sinkAcceptProblems(host.result().warnings());

        // The same lock a session holds, so an update cannot build underneath a running sandbox.
        // A dry run takes no lock, as with `at`.
        Optional<LampLock> lock = Optional.empty();
        try {
            if (!context.options().dryRun()) {
                lock = LampLock.tryAcquire(layout.lockFile());
                if (lock.isEmpty()) {
                    context.sinkAcceptProblems(Tuple.of(LampProblem.class, busyProblem(layout)));
                    return LampExitStatus.LAMP_BUSY;
                }
            }
            String previous = FilesystemUtil.readString(layout.toolRevision()).orElse("").strip();
            ImageTag previousImage = ImageTag.ofHash(SandboxImageFilesUtil.hashOf(
                    SandboxPhase.buildArgumentsFor(config, previous)));
            String revision = nextToolRevision(layout.agentId(), previous, machine.now());
            var arguments = SandboxPhase.buildArgumentsFor(config, revision);
            // The tag is computed before strict mode is added, so it is exactly the tag a later
            // start computes, and that start finds this image.
            ImageTag image = ImageTag.ofHash(SandboxImageFilesUtil.hashOf(arguments));
            arguments.put("AGENT_TOOLS_STRICT", "true");

            context.sink().accept(new LampEvent.Info("tools", (system
                    ? "building the whole image again, from the newest " + config.image().base()
                      + "; this takes as long as a first build"
                    : "installing opencode and pi again, and fetching opencode's model list")
                  + "; the agent's home is not touched"));
            for (String tool : Tuple.of("opencode", "pi")) {
                Path executable = layout.agentDir().resolve(".local").resolve("bin").resolve(tool);
                if (FilesystemUtil.exists(executable))
                    context.sink().accept(new LampEvent.Info("tools", "⚠️ " + executable
                            + " takes precedence over /usr/bin/" + tool
                            + " inside the sandbox; update does not change that copy"));
            }
            Result<Plan> built = new StepRunner(machine, context).run(Plan.of(LampStartupPhase.IMAGE,
                    Tuple.of(Step.class, new Step.ExtractImageContext(layout.imageContext()),
                            new Step.BuildImage(image, layout.imageContext(),
                                    SandboxPhase.asAssociation(arguments), system))));
            if (built instanceof Result.Err<Plan> failure) {
                context.sinkAcceptProblems(failure.problems());
                context.sink().accept(new LampEvent.Info("tools",
                        "update failed — the lamp keeps the image it had"));
                return LampExitStatus.ERROR;
            }
            if (context.options().dryRun()) {
                context.sink().accept(new LampEvent.Info("tools", "dry run — no image built, the lamp is unchanged"));
                return LampExitStatus.SUCCESS;
            }
            FilesystemUtil.writeFile(layout.toolRevision(), revision + "\n", PosixMode.PRIVATE_FILE);
            context.sink().accept(new LampEvent.Ok("tools", "updated image " + image
                    + " — used from the next `oillamp at " + layout.root() + "`"));
            // The image this lamp used before. Only an earlier update's image belongs to this lamp
            // alone; the image of a lamp that was never updated may be shared with other lamps.
            if (!previous.isEmpty()) removeImage(machine, context, previousImage);
            // The other old images stay, and the user is told how to find and remove them.
            context.sink().accept(new LampEvent.Warning(ProblemCatalogUtil.imageLeftovers(image.value())));
            return LampExitStatus.SUCCESS;
        } catch (IOException e) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.lampNotWritable(layout.root(), ProblemCatalogUtil.reason(e))));
            return LampExitStatus.ERROR;
        } finally {
            lock.ifPresent(CommandExecutionUtil::release);
        }
    }

    /// The revision for the next update: the lamp's id and the time in milliseconds, such as
    /// `k3v7x2ab-1790000000000`.
    ///
    /// The lamp's id makes the updated image this lamp's alone, so the update after it may remove
    /// it. The time makes each update a new image. If the clock has not moved since the last
    /// update, the time is moved on by one, so the revision still changes.
    static String nextToolRevision(AgentId lamp, String previous, Instant now) {
        long millis = now.toEpochMilli();
        String prefix = lamp.value() + "-";
        if (previous.startsWith(prefix)) {
            try {
                millis = Math.max(millis, Long.parseLong(previous.substring(prefix.length())) + 1);
            } catch (NumberFormatException ignored) {
                // Not written by this version of oillamp. Any new revision differs from it.
            }
        }
        return prefix + millis;
    }

    /// Removes an image this lamp no longer uses. Failing to is only a warning: the update
    /// itself succeeded, and the image costs nothing but disk space. Podman refuses to remove an
    /// image a container still uses, which is what should happen.
    private static void removeImage(Machine machine, Context context, ImageTag image) {
        Machine.Outcome removed = machine.run(Machine.Command
                .of("podman", "image", "rm", image.value())
                .withTimeout(Duration.ofMinutes(1)).labelled("podman image rm"));
        if (removed.succeeded())
            context.sink().accept(new LampEvent.Info("tools", "removed the image the lamp used before, " + image));
        else
            context.sink().accept(new LampEvent.Warning(ProblemCatalogUtil.asWarning(ProblemCatalogUtil.podmanFailed(
                    "podman image rm " + image, removed.exitCode(), removed.errorOutput().strip()))));
    }

    /// Removes the container a start left behind, if there is one. Called when the start failed,
    /// and from a shutdown hook when the user pressed Ctrl-C before the supervisor took over.
    private static void removeUnfinishedSandbox(
        final Machine machine,
        final Context context,
        final ContainerName container,
        final String why
    ) {
        boolean exists = machine.run(Machine.Command.of("podman", "container", "exists", container.value())
                .withTimeout(Duration.ofSeconds(20)).labelled("podman container exists")
                .shieldedFromSignals()).succeeded();
        if (!exists) return;
        Result<Plan> removed = new StepRunner(machine, context).run(Plan.of(LampStartupPhase.SESSION,
                Tuple.of(Step.class, new Step.RemoveContainer(container, why))));
        if (removed instanceof Result.Err<Plan> failure) context.sinkAcceptProblems(failure.problems());
        else context.sink().accept(new LampEvent.Info("session",
                "removed the sandbox that did not start, so nothing is left running"));
    }

    private static void stopWatching(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The JVM is already running the hook; it removes the container itself.
        }
    }

    /// Whether `at` may install host packages: not with `--no-install`, and not when the
    /// configuration says `host.auto_install = false`.
    ///
    /// The host is prepared before the lamp, so the configuration is read here once already. If it
    /// cannot be read, installing stays allowed; the lamp phase reports what is wrong with it.
    private static Installing installing(
        final Machine machine,
        final Context context,
        final Path lampPath
    ) {
        if (!context.options().autoInstall()) return Installing.DECLINED;
        Result<LampConfig> config = ConfigLoadingUtil.load(LampPhase.configurationFiles(home(machine),
                lampPath.toAbsolutePath().resolve("oillamp.toml")));
        return config instanceof Result.Ok<LampConfig> ok && !ok.value().host().autoInstall()
                ? Installing.DECLINED_IN_CONFIG
                : Installing.ALLOWED;
    }

    private static Path home(Machine machine) {
        return machine.environmentVariable("HOME").map(Path::of).orElse(Path.of("/nonexistent"));
    }

    // ─── commands that talk to a running session through its control socket ─────────────

    /// `oillamp view <dir> [--view-only]`: open another viewer onto the same desktop.
    private static LampExitStatus view(Machine machine, Context context, Path lampPath, boolean viewOnly) {
        return askTheSession(machine, context, lampPath, "view",
                Control.Request.of("view").with("view_only", String.valueOf(viewOnly)),
                reply -> context.sink().accept(new LampEvent.Ok("view", "another viewer window is opening")));
    }

    /// `oillamp shell <dir>`: an extra shell in this terminal.
    ///
    /// The supervisor returns the ssh command and this process runs it, because the shell belongs
    /// in the terminal the user typed this into. Closing it does not end the session.
    private static LampExitStatus shell(Machine machine, Context context, Path lampPath) {
        Result<Control.Reply> reply = askTheSession(machine, lampPath, "shell", Control.Request.of("shell"));
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Tuple<String> argv = ((Result.Ok<Control.Reply>) reply).value().argv();
        if (argv.isEmpty()) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.internal("shell", "the session did not say how to reach it")));
            return LampExitStatus.ERROR;
        }
        context.sink().accept(new LampEvent.Info("shell", "connecting — closing this shell does not end the session"));
        int code = machine.launch(Machine.Command.of(argv).labelled("shell"),
                                  Machine.Window.Stdio.TERMINAL).waitFor();
        return code == 0 ? LampExitStatus.SUCCESS : LampExitStatus.ERROR;
    }

    /// `oillamp stop <dir>`: ask the running session to end.
    ///
    /// It asks the supervisor rather than removing the container, because the supervisor holds
    /// the lock, the relays and the recording and must run its own shutdown. If no supervisor
    /// answers, it removes a container left behind by one that died.
    private static LampExitStatus stop(Machine machine, Context context, Path lampPath) {
        Result<Control.Reply> reply = askTheSession(machine, lampPath, "stop", Control.Request.of("stop"));
        if (reply instanceof Result.Ok<Control.Reply>) {
            context.sink().accept(new LampEvent.Ok("stop", "the session is shutting down"));
            return LampExitStatus.SUCCESS;
        }
        return cleanUpAfterACrashedSession(machine, context, lampPath, reply.problems());
    }

    /// `oillamp remove <dir>...`: delete one lamp or several.
    ///
    /// A lamp cannot be deleted with `rm -rf`: the infra sockets and the recordings belong
    /// to the infra user, which is a subordinate id on the host that the lamp's owner cannot delete
    /// files of. This command deletes them through `podman unshare`.
    ///
    /// It deletes only what oillamp created. The lamp directory itself is removed only if nothing
    /// else is left in it, because the user may keep their own files there.
    ///
    /// It requires `--yes`, because it deletes the agent's home and all its work, and there
    /// is no interactive prompt. Without `--yes` it lists what would be deleted and exits 2.
    ///
    /// Several lamps are usually named by a shell pattern such as `test*`, which may match more
    /// than the user meant. So every one is checked before anything is deleted: if any of them is
    /// not a lamp, or is still running, nothing is removed and each problem is reported.
    private static LampExitStatus remove(Machine machine, Context context, Tuple<Path> lampPaths, boolean confirmed) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path lampPath : lampPaths) roots.add(LampPhase.resolve(machine, lampPath));

        Tuple<LampPlanUtil.Removal> removals = Tuple.of(LampPlanUtil.Removal.class);
        Tuple<LampProblem> refusals = Tuple.of(LampProblem.class);
        boolean anyInUse = false;
        for (Path root : roots) {
            Result<LampPlanUtil.Removal> examined = examineForRemoval(machine, root);
            if (examined instanceof Result.Ok<LampPlanUtil.Removal> ok) removals = removals.add(ok.value());
            else refusals = refusals.addAll(examined.problems());
            anyInUse |= examined.problems().stream().anyMatch(p -> p.code().equals(ProblemCatalogUtil.LAMP_STILL_RUNNING));
        }
        if (!refusals.isEmpty()) {
            context.sinkAcceptProblems(refusals);
            if (roots.size() > 1)
                context.sink().accept(new LampEvent.Answer("Nothing has been removed: " + refusals.size() + " of the "
                        + roots.size() + " directories cannot be. Name only the lamps to remove."));
            // A lamp in use is worth its own exit code: stopping it is all it takes to retry.
            return anyInUse ? LampExitStatus.LAMP_BUSY : LampExitStatus.USAGE;
        }

        boolean several = removals.size() > 1;
        for (LampPlanUtil.Removal found : removals)
            context.sink().accept(new LampEvent.Answer((several ? "── " + found.root() + "\n\n" : "")
                    + describeWhatWouldGo(found)));
        if (!confirmed && !context.options().dryRun()) {
            StringBuilder command = new StringBuilder("oillamp remove");
            for (LampPlanUtil.Removal found : removals) command.append(' ').append(found.root());
            context.sink().accept(new LampEvent.Answer("Nothing has been removed. To go ahead"
                    + (several ? " and delete all " + removals.size() + " lamps" : "") + ":\n\n  "
                    + command + " --yes"));
            return LampExitStatus.USAGE;
        }

        // Every lamp was checked above and the user confirmed them all, so one that fails to
        // delete does not stop the others.
        Tuple<LampProblem> failures = Tuple.of(LampProblem.class);
        int removed = 0;
        for (LampPlanUtil.Removal found : removals) {
            Result<Plan> done = new StepRunner(machine, context).run(LampPlanUtil.planRemoval(found));
            if (done instanceof Result.Err<Plan> failure) {
                context.sinkAcceptProblems(failure.problems());
                failures = failures.addAll(failure.problems());
                continue;
            }
            context.sinkAcceptProblems(done.warnings());
            if (context.options().dryRun()) continue;
            removed++;
            // The rest of the sentence names the directory, so it needs no prefix for several.
            context.sink().accept(new LampEvent.Ok("remove", "the lamp is gone" + removeTheRootIfEmpty(found.root())));
        }
        if (several && !context.options().dryRun())
            context.sink().accept(new LampEvent.Answer(removed == removals.size()
                    ? "All " + removed + " lamps were removed."
                    : removed + " of " + removals.size() + " lamps were removed; the others are "
                      + "reported above."));
        return failures.isEmpty() ? LampExitStatus.SUCCESS : exitStatusFor(failures);
    }

    /// What `remove` would delete for one lamp, or why it must not: the directory holds nothing
    /// of oillamp's, or the lamp is still running.
    private static Result<LampPlanUtil.Removal> examineForRemoval(Machine machine, Path root) {
        DirListing listing = FilesystemUtil.list(root);
        Tuple<Path> agentDirs = Tuple.of(Path.class);
        for (String entry : listing.entries())
            if (entry.startsWith(LampLayout.AGENT_DIR_PREFIX)) agentDirs = agentDirs.add(root.resolve(entry));

        boolean anythingOfOurs = !agentDirs.isEmpty()
                || FilesystemUtil.exists(LampLayout.stateDirOf(root))
                || FilesystemUtil.exists(LampLayout.configOf(root));
        if (!anythingOfOurs)
            return Result.err(ProblemCatalogUtil.lampNotWritable(root,
                    "this is not an oillamp lamp — there is nothing of oillamp's in " + root));

        // A lamp half-deleted by hand may have lost lamp.json, and with it the agent id that names
        // the container and the runtime directory. Everything else can still be removed.
        Optional<LampLayout> layout = layoutOf(machine, root) instanceof Result.Ok<LampLayout> ok
                ? Optional.of(ok.value())
                : Optional.empty();

        Optional<String> inUse = whatIsStillRunning(machine, root, layout);
        if (inUse.isPresent())
            return Result.err(ProblemCatalogUtil.lampStillRunning(root, inUse.get()));

        return Result.ok(new LampPlanUtil.Removal(root, agentDirs, layout.map(LampLayout::runtimeDir)));
    }

    /// What is still running for this lamp, if anything: a container, a supervisor that answers, or
    /// a container left behind by a supervisor that died. In each case the user is told to run
    /// `oillamp stop` first.
    private static Optional<String> whatIsStillRunning(Machine machine, Path root, Optional<LampLayout> layout) {
        Optional<String> container = runningSandboxFor(machine, root);
        if (container.isPresent())
            return Optional.of("the sandbox container " + container.get()
                    + " is still running for this lamp");
        if (layout.isPresent() && Control.ask(layout.get().controlSocket(), root,
                Control.Request.of("status"), "remove") instanceof Result.Ok<Control.Reply>)
            return Optional.of("a session is running: its supervisor is answering on "
                    + layout.get().controlSocket());
        if (layout.isPresent() && machine.run(Machine.Command.of("podman", "container", "exists",
                layout.get().containerName().value()).labelled("podman container exists")).succeeded())
            return Optional.of("the sandbox container " + layout.get().containerName().value()
                    + " is still there, left behind by a session that did not finish");
        return Optional.empty();
    }

    /// A container podman is running for this lamp, found by its `oillamp.lamp` label.
    ///
    /// Found by label rather than by name, because the name comes from `lamp.json`, which
    /// may already have been deleted by hand. The label holds the lamp's path.
    private static Optional<String> runningSandboxFor(Machine machine, Path root) {
        Machine.Outcome outcome = listRunningSandboxes(machine);
        if (!outcome.succeeded()) return Optional.empty();
        return runningSandboxesIn(outcome.output()).stream()
                .filter(sandbox -> sandbox.lamp().equals(root.toString()))
                .map(RunningSandbox::name).findFirst();
    }

    /// Every container with an `oillamp.agent-id` label that podman is running.
    ///
    /// JSON rather than a --format template: the template field names differ between podman
    /// 4 and 5, and this must work with both.
    private static Machine.Outcome listRunningSandboxes(Machine machine) {
        return machine.run(Machine.Command
                .of("podman", "ps", "--filter", "label=oillamp.agent-id", "--format", "json")
                .withTimeout(Duration.ofSeconds(20)).labelled("podman ps"));
    }

    /// Reads the output of [#listRunningSandboxes]. Output that is not the expected JSON counts
    /// as no containers at all, rather than as a failure.
    private static Tuple<RunningSandbox> runningSandboxesIn(String json) {
        Tuple<RunningSandbox> found = Tuple.of(RunningSandbox.class);
        com.fasterxml.jackson.databind.JsonNode listing =
                JsonUtil.parse(json).orElse(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        if (!listing.isArray()) return found;
        for (com.fasterxml.jackson.databind.JsonNode container : listing) {
            // podman 4 and 5 give the names as a list; older versions as one string.
            com.fasterxml.jackson.databind.JsonNode names = container.path("Names");
            String name = names.isArray() && !names.isEmpty() ? names.get(0).asText() : names.asText("");
            found = found.add(new RunningSandbox(name, JsonUtil.text(container, "State"),
                    container.path("Labels").path("oillamp.lamp").asText("")));
        }
        return found;
    }

    /// The list of what `remove` would delete, shown before the user adds `--yes`. The
    /// agent's home is listed entry by entry, so the user can see which repositories would go.
    private static String describeWhatWouldGo(LampPlanUtil.Removal found) {
        StringBuilder out = new StringBuilder("`oillamp remove` permanently deletes:\n\n");
        for (Path agentDir : found.agentDirs()) {
            out.append("  ").append(agentDir.getFileName()).append("/\n      the agent's home");
            DirListing home = FilesystemUtil.list(agentDir);
            if (!home.readable())    out.append(" (cannot be read from here)");
            else if (home.isEmpty()) out.append(" (empty)");
            else                     out.append(" — ").append(String.join(", ", home.entries()));
            out.append('\n');
        }
        if (FilesystemUtil.exists(LampLayout.stateDirOf(found.root())))
            out.append("  .oillamp/\n      this lamp's identity, keys, logs, recordings and sockets\n");
        if (FilesystemUtil.exists(LampLayout.configOf(found.root())))
            out.append("  oillamp.toml\n      your configuration for this lamp\n");
        if (FilesystemUtil.exists(LampLayout.readmeOf(found.root())))
            out.append("  README.txt\n");
        found.runtimeDir().ifPresent(runtime -> out.append("  ").append(runtime)
                .append("\n      the sockets this lamp uses while it runs\n"));
        out.append("\nThe directory itself, ").append(found.root())
           .append(", stays — unless this empties it.\n");
        return out.toString();
    }

    /// Removes the lamp directory itself, but only if nothing else is left in it.
    private static String removeTheRootIfEmpty(Path root) {
        DirListing left = FilesystemUtil.list(root);
        if (!left.readable() || !left.isEmpty())
            return " — " + root + " itself is untouched";
        try {
            FilesystemUtil.deleteIfPresent(root);
            return ", and " + root + " with it: oillamp was all that was in it";
        } catch (IOException e) {
            return " — " + root + " is empty now, but could not be removed: " + ProblemCatalogUtil.reason(e);
        }
    }

    /// `oillamp status <dir>`: what the running session is doing.
    private static LampExitStatus status(Machine machine, Context context, Path lampPath) {
        Result<Control.Reply> reply = askTheSession(machine, lampPath, "status", Control.Request.of("status"));
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Control.Reply answer = ((Result.Ok<Control.Reply>) reply).value();
        StringBuilder out = new StringBuilder();
        for (String key : Tuple.of(String.class, "state", "detail", "lamp", "session", "container",
                                            "desktop", "renderer", "uptime", "shells", "agent", "schedule", "viewer"))
            answer.values().get(key).ifPresent(value ->
                    out.append(pad(key, 12)).append(value).append('\n'));
        context.sink().accept(new LampEvent.Answer(out.toString().stripTrailing()));
        answer.values().get("agent_status").flatMap(LampEvent::fromJson).ifPresent(context.sink());
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp list`: every oillamp container running on this host, found by its
    /// `oillamp.agent-id` label. oillamp keeps no list of its own that could go out of date.
    private static LampExitStatus list(Machine machine, Context context) {
        Machine.Outcome outcome = listRunningSandboxes(machine);
        if (!outcome.succeeded()) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class, ProblemCatalogUtil.podmanFailed(
                    "podman ps", outcome.exitCode(), outcome.errorOutput().strip())));
            return LampExitStatus.ERROR;
        }
        Tuple<RunningSandbox> running = runningSandboxesIn(outcome.output());
        StringBuilder rows = new StringBuilder(pad("CONTAINER", 22) + pad("STATE", 22) + "LAMP");
        for (RunningSandbox sandbox : running)
            rows.append('\n').append(pad(sandbox.name(), 22)).append(pad(sandbox.state(), 22))
                .append(sandbox.lamp());
        context.sink().accept(new LampEvent.Answer(running.isEmpty()
                ? "no oillamp sandboxes are running on this host"
                : rows.toString()));
        return LampExitStatus.SUCCESS;
    }

    // ─── the lamp's history ────────────────────────────────────────────────────────────────

    /// `oillamp history <dir> save [--message <text>]`: take a snapshot of the lamp now.
    ///
    /// Works while a session is running, so a person can save just before letting the agent try
    /// something risky. The snapshot then says it was taken while the session ran, because a
    /// program writing at that moment may have left a file half written.
    private static LampExitStatus save(Machine machine, Context context, Path lampPath, String message) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        // Holding the lamp's lock while saving also keeps a session from starting halfway through.
        Result<LampConfig> config = loadConfig(machine, layout.root());
        if (config instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Optional<LampLock> idle;
        try {
            idle = LampLock.tryAcquire(layout.lockFile());
        } catch (IOException e) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.lampNotWritable(layout.root(), ProblemCatalogUtil.reason(e))));
            return LampExitStatus.ERROR;
        }
        try {
            if (idle.isPresent()) new History(layout).releaseBase();
            Optional<SessionId> session = idle.isPresent() ? Optional.empty()
                    : FilesystemUtil.readString(layout.sessionMeta()).flatMap(JsonUtil::parse)
                                .flatMap(json -> SessionId.parse(JsonUtil.text(json, "session")));
            if (idle.isEmpty())
                context.sink().accept(new LampEvent.Info("history",
                        "a session is running, so programs in the sandbox may be "
                        + "writing while this saves; the snapshot is marked as a running save"));
            Tuple<LampProblem> problems = saveLamp(context, layout,
                    idle.isPresent() ? LampSaveKind.IDLE : LampSaveKind.RUNNING, message, session, machine.now(),
                    ((Result.Ok<LampConfig>) config).value().history());
            context.sinkAcceptProblems(problems);
            return exitStatusFor(problems);
        } finally {
            idle.ifPresent(CommandExecutionUtil::release);
        }
    }

    /// `oillamp history <dir> [list] [--all]`: the snapshots on the lamp's current branch, newest
    /// first, or with `all` those on every branch, each with the branches it is on.
    private static LampExitStatus history(Machine machine, Context context, Path lampPath, boolean all) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        History history = new History(((Result.Ok<LampLayout>) found).value());
        Result<LampEvent> listed = history.currentBranch().flatMap(branch -> all
                ? history.snapshotsOfAllBranches().<LampEvent>map(snapshots -> new LampEvent.HistoryOfAllBranches(branch, snapshots))
                : history.snapshots().<LampEvent>map(snapshots -> new LampEvent.History(branch, snapshots)));
        if (listed instanceof Result.Err<LampEvent> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.sink().accept(((Result.Ok<LampEvent>) listed).value());
        history.boundaries().map(ids -> {
            if (!ids.isEmpty()) context.sink().accept(new LampEvent.Info("history",
                    "older snapshots were deleted to keep the history within [history].max_growth_gb; "
                    + "the oldest kept: " + ids.map(id -> id.substring(0, 8)).join(", ")));
            return ids;
        });
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp history <dir> branches`: every branch of the lamp's history, with its newest
    /// snapshot, and which one the lamp is on.
    private static LampExitStatus branches(Machine machine, Context context, Path lampPath) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Result<Tuple<LampBranch>> branches = new History(((Result.Ok<LampLayout>) found).value()).branches();
        if (branches instanceof Result.Err<Tuple<LampBranch>> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.sink().accept(new LampEvent.Branches(((Result.Ok<Tuple<LampBranch>>) branches).value()));
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp history <dir> branch <name> [<snapshot>]`: start a branch, and stay where the lamp is.
    ///
    /// Works while a session runs: it writes only the new branch's file in the history, which no
    /// session reads.
    private static LampExitStatus createBranch(Machine machine, Context context, Path lampPath, String name,
                                               Optional<String> snapshot) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        History history = new History(layout);
        Result<LampBranch> created = history.createBranch(name, snapshot);
        if (created instanceof Result.Err<LampBranch> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.sink().accept(new LampEvent.BranchCreated(((Result.Ok<LampBranch>) created).value()));
        context.sink().accept(new LampEvent.Info("history", "the lamp stays on "
                + history.currentBranch().orElseGet(_ -> History.FIRST_BRANCH)
                + "; to work on " + name + ", run: oillamp history " + layout.root() + " switch " + name));
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp history <dir> branch --delete <name> [--yes]`: delete a branch other than the
    /// current one. Without `--yes`, say how many snapshots only that branch holds, change
    /// nothing, and exit 2, as `remove` does.
    private static LampExitStatus deleteBranch(Machine machine, Context context, Path lampPath, String name,
                                               boolean confirmed) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<Integer> counted = new History(layout).deleteBranch(name, confirmed);
        if (counted instanceof Result.Err<Integer> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        int only = ((Result.Ok<Integer>) counted).value();
        if (confirmed) {
            context.sink().accept(new LampEvent.BranchDeleted(name, only));
            return LampExitStatus.SUCCESS;
        }
        context.sink().accept(new LampEvent.BranchDeletePlanned(name, only));
        context.sink().accept(new LampEvent.Answer("Nothing has been deleted. To go ahead:\n\n  oillamp history "
                + layout.root() + " branch --delete " + name + " --yes"));
        return LampExitStatus.USAGE;
    }

    /// `oillamp history <dir> switch <name>`: bring the lamp to another branch, and save to it from
    /// now on.
    ///
    /// Refused while a session runs, as a restore is. What changed since the last snapshot is
    /// saved first, on the branch the lamp leaves.
    private static LampExitStatus switchBranch(Machine machine, Context context, Path lampPath, String name) {
        return whileStopped(machine, context, lampPath, "switch", layout -> {
            Result<History.Switching> switched = recoveryWriter(machine, context, layout).switchTo(name, machine.now());
            context.sinkAcceptProblems(switched.warnings());
            if (switched instanceof Result.Err<History.Switching> failure) {
                context.sinkAcceptProblems(failure.problems());
                return exitStatusFor(failure.problems());
            }
            History.Switching done = ((Result.Ok<History.Switching>) switched).value();
            done.safety().ifPresent(safety -> context.sink().accept(new LampEvent.Saved(safety, 0)));
            context.sink().accept(new LampEvent.Switched(done.from(), done.to()));
            if (!done.from().equals(done.to().name()))
                context.sink().accept(new LampEvent.Info("history", "to go back, run: oillamp history "
                        + layout.root() + " switch " + done.from()));
            return LampExitStatus.SUCCESS;
        });
    }

    /// `oillamp history <dir> reset <snapshot> [--yes]`: bring the lamp and its current branch back
    /// to a snapshot on that branch, throwing away what came after.
    ///
    /// Refused while a session runs. Without `--yes`, say what would be lost, change nothing, and
    /// exit 2, as `remove` does; when nothing would be lost there is nothing to confirm, and it
    /// exits 0.
    private static LampExitStatus reset(Machine machine, Context context, Path lampPath, String snapshot,
                                        boolean confirmed) {
        return whileStopped(machine, context, lampPath, "reset", layout -> {
            Result<History.Resetting> reset = new History(layout).reset(snapshot, confirmed);
            if (reset instanceof Result.Err<History.Resetting> failure) {
                context.sinkAcceptProblems(failure.problems());
                return exitStatusFor(failure.problems());
            }
            History.Resetting done = ((Result.Ok<History.Resetting>) reset).value();
            if (confirmed) {
                context.sink().accept(new LampEvent.ResetDone(done.target(), done.dropped(), done.unsaved()));
                return LampExitStatus.SUCCESS;
            }
            context.sink().accept(new LampEvent.ResetPlanned(done.target(), done.dropped(), done.unsaved()));
            if (done.dropped() == 0 && !done.unsaved()) return LampExitStatus.SUCCESS;
            context.sink().accept(new LampEvent.Answer("Nothing has been changed. To go ahead:\n\n  oillamp history "
                    + layout.root() + " reset " + done.target().shortId() + " --yes"));
            return LampExitStatus.USAGE;
        });
    }

    /// `oillamp history <dir> restore <snapshot>`: bring the lamp back to an earlier snapshot on
    /// its current branch.
    ///
    /// Refused while a session runs. The lamp is saved first, so every restore can be undone by
    /// restoring that save.
    private static LampExitStatus restore(Machine machine, Context context, Path lampPath, String snapshot) {
        return whileStopped(machine, context, lampPath, "restore", layout -> {
            Result<History.Restoring> restored = recoveryWriter(machine, context, layout).restore(snapshot, machine.now());
            context.sinkAcceptProblems(restored.warnings());
            if (restored instanceof Result.Err<History.Restoring> failure) {
                context.sinkAcceptProblems(failure.problems());
                return exitStatusFor(failure.problems());
            }
            History.Restoring done = ((Result.Ok<History.Restoring>) restored).value();
            done.safety().ifPresent(safety -> context.sink().accept(new LampEvent.Saved(safety, 0)));
            context.sink().accept(new LampEvent.Restored(done.target(), done.result().orElse(done.target())));
            if (done.result().isEmpty()) return LampExitStatus.SUCCESS;
            done.safety().ifPresent(safety -> context.sink().accept(new LampEvent.Info("history",
                    "to undo this, run: oillamp history "
                    + layout.root() + " restore " + safety.shortId())));
            return LampExitStatus.SUCCESS;
        });
    }

    /// Does `work` on a lamp while holding the lamp's lock, so that no session starts meanwhile.
    /// Refused with `OIL-HISTORY-005` while a session runs.
    ///
    /// @param action what is refused, as the problem says it: `restore`, `switch` or `reset`
    private static LampExitStatus whileStopped(Machine machine, Context context, Path lampPath, String action,
                                               Function<LampLayout, LampExitStatus> work) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Optional<LampLock> lock;
        try {
            lock = LampLock.tryAcquire(layout.lockFile());
        } catch (IOException e) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.lampNotWritable(layout.root(), ProblemCatalogUtil.reason(e))));
            return LampExitStatus.ERROR;
        }
        if (lock.isEmpty()) {
            Tuple<LampProblem> refused = Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.restoreWhileRunning(layout.root(), action));
            context.sinkAcceptProblems(refused);
            return exitStatusFor(refused);
        }
        try {
            return work.apply(layout);
        } finally {
            release(lock.get());
        }
    }

    /// Saves a lamp, reports the snapshot or that nothing changed, and returns the problems to
    /// report. Also used by `at` as a session starts and by the supervisor as it ends.
    static Tuple<LampProblem> saveLamp(Context context, LampLayout layout, LampSaveKind kind, String message,
                                   Optional<SessionId> session, Instant now, LampConfig.History policy) {
        String activity = "saving lamp state now — this may take a while";
        context.sink().accept(new LampEvent.StepStarted(new LampStep(
                "SaveLamp", activity + " (" + kind.label() + ")",
                "save " + layout.root() + " (" + kind.label() + ")")));
        // Saved, Info (nothing changed), or the caller's Warning/Failure ends the activity line.
        Result<History.Saving> saved = new History(layout, policy, context.sink()).save(kind, message, session, now);
        if (saved instanceof Result.Err<History.Saving> failure) return failure.problems();
        History.Saving saving = ((Result.Ok<History.Saving>) saved).value();
        if (saving.made().isPresent())
            context.sink().accept(new LampEvent.Saved(saving.made().get(), saving.files()));
        else
            context.sink().accept(new LampEvent.Info("history", saving.latest()
                    .map(latest -> "nothing changed since " + latest.shortId() + " (" + latest.kind().label()
                                 + "), so there was nothing to save")
                    .orElse("nothing to save yet")));
        return saving.skipped().isEmpty() ? Tuple.of(LampProblem.class)
                : Tuple.of(LampProblem.class, ProblemCatalogUtil.filesNotSaved(layout.root(), saving.skipped()));
    }

    private static Result<History> historyWriter(Machine machine, Context context, LampLayout layout) {
        return loadConfig(machine, layout.root()).map(config -> new History(layout, config.history(), context.sink(), true));
    }

    /// A broken current config must not prevent restoring a saved, valid config.
    private static History recoveryWriter(Machine machine, Context context, LampLayout layout) {
        Result<LampConfig> loaded = loadConfig(machine, layout.root());
        if (loaded instanceof Result.Ok<LampConfig> ok)
            return new History(layout, ok.value().history(), context.sink(), true);
        context.sink().accept(new LampEvent.Warning(ProblemCatalogUtil.historyPruningFailed(layout.root(),
                "history pruning is disabled for this recovery because the current configuration is invalid")));
        return new History(layout, new LampConfig.History(0), context.sink(), true);
    }

    // ─── the schedule, and asking the agent ────────────────────────────────────────────────

    /// `oillamp schedule <dir> [<action>]`: list the lamp's jobs, or change them.
    ///
    /// Works whether or not a session runs. A running session reads the schedule again when it
    /// changes, so a job added now can run straight away.
    private static LampExitStatus schedule(Machine machine, Context context, Path lampPath, Command.Schedule.Action action) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<LampConfig> config = loadConfig(machine, layout.root());
        if (config instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.Schedule limits = scheduleInForce(layout, ((Result.Ok<LampConfig>) config).value().schedule());
        ScheduleBook book = new ScheduleBook(layout);
        ZoneId zone = machine.zone();
        Instant now = machine.now();
        Result<LampEvent> done = switch (action) {
            case Command.Schedule.Action.ListJobs _ -> book.read().map(schedule -> describe(schedule, limits, zone, now));
            case Command.Schedule.Action.Add add -> book.update(schedule -> schedule.add(new Schedule.Request(add.cron(),
                            add.at(), add.prompt(), add.expires()),
                            LampJobAuthor.USER, now, zone, limits), Schedule.Changed::schedule)
                    .map(added -> new LampEvent.JobAdded(added.job().describe(zone, now)));
            case Command.Schedule.Action.Remove remove -> book.update(schedule -> schedule.remove(remove.job(),
                            LampJobAuthor.USER, layout.root()), Schedule.Changed::schedule)
                    .map(removed -> new LampEvent.JobRemoved(removed.job().describe(zone, now), "removed by you"));
            case Command.Schedule.Action.Enable enable -> book.update(schedule -> schedule.enable(enable.job(),
                            true, now, layout.root()), Schedule.Changed::schedule)
                    .map(changed -> new LampEvent.ScheduleChanged(changed.job().id() + " is switched on"));
            case Command.Schedule.Action.Disable disable -> book.update(schedule -> schedule.enable(disable.job(),
                            false, now, layout.root()), Schedule.Changed::schedule)
                    .map(changed -> new LampEvent.ScheduleChanged(changed.job().id() + " is switched off"));
            case Command.Schedule.Action.Pause _ -> book.update(schedule -> Result.ok(schedule.paused(true, now)),
                            Function.<Schedule>identity())
                    .map(paused -> new LampEvent.ScheduleChanged(
                            "the schedule is paused: no job runs until `oillamp schedule " + layout.root() + " resume`"));
            case Command.Schedule.Action.Resume _ -> book.update(schedule -> Result.ok(schedule.paused(false, now)),
                            Function.<Schedule>identity())
                    .map(resumed -> new LampEvent.ScheduleChanged("the schedule runs again"));
        };
        if (done instanceof Result.Err<LampEvent> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.sink().accept(((Result.Ok<LampEvent>) done).value());
        if (!(action instanceof Command.Schedule.Action.ListJobs)) {
            if (!limits.enabled()) context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.scheduleOff(layout.config())));
            // A running session looks at the schedule every half minute; this makes it look now.
            // Without a session there is nothing to tell, and that is fine.
            if (FilesystemUtil.exists(layout.controlSocket()))
                Control.ask(layout.controlSocket(), layout.root(), Control.Request.of("schedule-changed"), "schedule");
        }
        return LampExitStatus.SUCCESS;
    }

    /// `configured`, switched on when a running session has the schedule on, as it does when it
    /// was started with `--enable-scheduling`.
    private static LampConfig.Schedule scheduleInForce(LampLayout layout, LampConfig.Schedule configured) {
        if (configured.enabled() || !FilesystemUtil.exists(layout.controlSocket())) return configured;
        boolean on = Boolean.TRUE.equals(Control.ask(layout.controlSocket(), layout.root(), Control.Request.of("status"), "schedule")
                .map(reply -> reply.values().get("schedule").map("on"::equals).orElse(false))
                .orElseGet(_ -> false));
        return on ? configured.switchedOn() : configured;
    }

    private static LampEvent describe(Schedule schedule, LampConfig.Schedule limits, ZoneId zone,
                                      Instant now) {
        Tuple<LampJob> jobs = Tuple.of(LampJob.class);
        for (ScheduledJob job : schedule.jobs()) jobs = jobs.add(job.describe(zone, now));
        return new LampEvent.Schedule(limits.enabled(), schedule.paused(), zone.getId(), jobs);
    }

    /// `oillamp conversations <dir> [<conversation>]`: the agent's conversations, or one in full.
    ///
    /// Read from pi's files in the agent's home, so it works whether or not a session runs.
    private static LampExitStatus conversations(Machine machine, Context context, Path lampPath, Optional<String> which) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Path root = ((Result.Ok<LampLayout>) found).value().root();
        if (which.isEmpty()) {
            context.sink().accept(new LampEvent.Conversations(Tuple.of(dev.lamp.LampConversation.class,
                    dev.lamp.Lamp.conversations(root))));
            return LampExitStatus.SUCCESS;
        }
        Optional<dev.lamp.LampConversation> conversation = dev.lamp.Lamp.conversation(root, which.get());
        if (conversation.isEmpty()) {
            Tuple<LampProblem> missing = Tuple.of(LampProblem.class, ProblemCatalogUtil.noSuchConversation(which.get(), root));
            context.sinkAcceptProblems(missing);
            return exitStatusFor(missing);
        }
        context.sink().accept(new LampEvent.ConversationShown(conversation.get()));
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp ask <dir> <prompt>`: wake the agent with a prompt, and wait for its answer.
    ///
    /// The session does the work, because it is the one that holds the agent: this sends the
    /// prompt over the control socket and waits. When the agent is busy, the prompt waits its turn.
    ///
    /// The question goes into a new conversation, unless `--in` names one; `--after` or
    /// `--instead-of` then name an entry in it. Without waiting, the run's id is reported, and the
    /// answer comes on the session's own events.
    private static LampExitStatus ask(Machine machine, Context context, Command.Ask ask) {
        String prompt = ask.prompt();
        if (prompt.isBlank()) {
            Tuple<LampProblem> refused = Tuple.of(LampProblem.class, ProblemCatalogUtil.usage(
                    "`oillamp ask` needs something to ask the agent", Command.usageOf("ask")));
            context.sinkAcceptProblems(refused);
            return LampExitStatus.USAGE;
        }
        Result<LampLayout> found = layoutOf(machine, ask.lamp());
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<Control.Request> request = requestFor(ask, layout.root());
        if (request instanceof Result.Err<Control.Request> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Control.Request toSend = ((Result.Ok<Control.Request>) request).value();
        if (!ask.waitForAnswer()) {
            Result<Control.Reply> taken = Control.ask(layout.controlSocket(), layout.root(),
                    toSend.with("no_wait", "true"), "ask");
            if (taken instanceof Result.Err<Control.Reply> failure) {
                context.sinkAcceptProblems(failure.problems());
                return exitStatusFor(failure.problems());
            }
            context.sink().accept(new LampEvent.RunAccepted(new LampRun(
                    ((Result.Ok<Control.Reply>) taken).value().values().get("run").orElse("run"),
                    Optional.empty(), prompt, ask.conversation())));
            return LampExitStatus.SUCCESS;
        }
        context.sink().accept(new LampEvent.Info("run",
                "asking the agent; its answer comes once it is done, which can take a while"));
        Result<Control.Reply> reply = Control.ask(layout.controlSocket(), layout.root(), toSend, "ask", ASK_PATIENCE);
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Control.Reply answer = ((Result.Ok<Control.Reply>) reply).value();
        LampRunOutcome outcome = LampRunOutcome.valueOf(answer.values().get("outcome").orElse("FAILED"));
        Optional<LampSave> snapshot = Optional.empty();
        Optional<String> saved = answer.values().get("snapshot");
        if (saved.isPresent() && new History(layout).snapshots() instanceof Result.Ok<Tuple<LampSave>>(
                Tuple<LampSave> all, var _))
            snapshot = all.stream().filter(s -> s.id().equals(saved.get())).findFirst();
        context.sink().accept(new LampEvent.RunFinished(
                new LampRun(answer.values().get("run").orElse("run"), Optional.empty(), prompt, ask.conversation()),
                outcome, answer.values().get("answer").orElse(""), snapshot,
                Duration.ofSeconds(Long.parseLong(answer.values().get("seconds").orElse("0"))),
                answer.values().get("conversation")));
        return outcome == LampRunOutcome.FINISHED ? LampExitStatus.SUCCESS : LampExitStatus.ERROR;
    }

    /// The request to the session, with the conversation found and the entry checked, so that a
    /// mistake is reported here, before anything is saved or started.
    private static Result<Control.Request> requestFor(Command.Ask ask, Path lamp) {
        Control.Request request = Control.Request.of("ask").with("prompt", ask.prompt());
        if (ask.conversation().isEmpty()) return Result.ok(request);
        Optional<dev.lamp.LampConversation> found = dev.lamp.Lamp.conversation(lamp, ask.conversation().get());
        if (found.isEmpty()) return Result.err(ProblemCatalogUtil.noSuchConversation(ask.conversation().get(), lamp));
        request = request.with("conversation", found.get().id()).with("file", found.get().file());
        Optional<String> entry = ask.after().or(ask::insteadOf);
        if (entry.isEmpty()) return Result.ok(request);
        Optional<dev.lamp.LampConversation.Entry> at = found.get().entry(entry.get());
        boolean question = at.filter(e -> e.kind() == dev.lamp.LampConversation.Kind.MESSAGE_TO_AGENT).isPresent();
        if (at.isEmpty())
            return Result.err(ProblemCatalogUtil.noSuchConversation(entry.get(), lamp));
        if (ask.after().isPresent() && question)
            return Result.err(ProblemCatalogUtil.usage("--after " + entry.get() + " names a question; to ask something "
                    + "instead of it, give --instead-of " + entry.get(), Command.usageOf("ask")));
        if (ask.insteadOf().isPresent() && !question)
            return Result.err(ProblemCatalogUtil.usage("--instead-of " + entry.get() + " does not name a question; to "
                    + "continue after it, give --after " + entry.get(), Command.usageOf("ask")));
        return Result.ok(request.with("move_to", entry.get()));
    }

    /// `oillamp cancel <dir> [<run>]`: stop the run in progress, or one that is waiting.
    private static LampExitStatus cancel(Machine machine, Context context, Path lampPath, Optional<String> run) {
        Control.Request request = Control.Request.of("cancel");
        if (run.isPresent()) request = request.with("run", run.get());
        return askTheSession(machine, context, lampPath, "cancel", request, reply -> context.sink().accept(
                new LampEvent.Ok("run",
                "cancelled " + reply.values().get("run").orElse("the run")
              + "; what the agent did until now is saved as the run ends")));
    }

    /// `oillamp model <dir> <id>`: the running session's agent answers with `model` from its next
    /// run on.
    private static LampExitStatus model(Machine machine, Context context, Path lampPath, String model) {
        return askTheSession(machine, context, lampPath, "model", Control.Request.of("model").with("model", model),
                reply -> context.sink().accept(new LampEvent.Ok("run",
                        "the agent answers with " + model + " from its next run on")));
    }

    /// `oillamp host-access <dir> [on|off] [--requirements-env <name>]`: what the lamp's file
    /// says about host access, after writing what was given into it.
    ///
    /// Only `enabled` and `requirements` under `[host_access]` change; see [ConfigFileEditUtil]
    /// and [#changeConfig].
    private static LampExitStatus hostAccess(Machine machine, Context context, Path lampPath,
                                         Optional<Boolean> enabled, Optional<String> requirementsEnv) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<LampConfig> before = loadConfig(machine, layout.root());
        if (before instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.HostAccess now = ((Result.Ok<LampConfig>) before).value().hostAccess();
        if (enabled.isEmpty() && requirementsEnv.isEmpty()) {
            context.sink().accept(new LampEvent.HostAccessSettings(now.enabled(), now.requirements()));
            return LampExitStatus.SUCCESS;
        }

        Optional<String> requirements = requirementsEnv.map(name -> machine.environmentVariable(name).orElse(""));
        if (requirements.isPresent() && requirements.get().getBytes(StandardCharsets.UTF_8).length > PiSetupUtil.LONGEST_INSTRUCTIONS) {
            Tuple<LampProblem> tooLong = Tuple.of(LampProblem.class, ProblemCatalogUtil.usage("--requirements-env "
                    + requirementsEnv.get() + ": more than " + PiSetupUtil.LONGEST_INSTRUCTIONS / 1024
                    + " KiB, far more than any useful rules", Command.usageOf("host-access")));
            context.sinkAcceptProblems(tooLong);
            return exitStatusFor(tooLong);
        }
        LampConfig.HostAccess wanted = new LampConfig.HostAccess(enabled.orElse(now.enabled()),
                requirements.orElse(now.requirements()), now.checkerModel(), now.roots(), now.maxReadBytes(),
                now.executables(), now.allowAdditive(), now.askTheUser());
        return changeConfig(machine, context, layout, "[host_access]",
                file -> ConfigFileEditUtil.withHostAccess(file, enabled, requirements),
                ((Result.Ok<LampConfig>) before).value().withHostAccess(wanted),
                () -> context.sink().accept(new LampEvent.HostAccessSettings(wanted.enabled(), wanted.requirements())));
    }

    /// `oillamp history <dir> autosave [<moment> on|off]...`: when the lamp's file says oillamp
    /// saves the lamp by itself, after writing what was given into it. Only those keys under
    /// `[autosave]` change, as with [#hostAccess].
    private static LampExitStatus autosave(Machine machine, Context context, Path lampPath,
                                           Command.History.Action.Autosave asked) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<LampConfig> before = loadConfig(machine, layout.root());
        if (before instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.Autosave now = ((Result.Ok<LampConfig>) before).value().autosave();
        if (asked.startup().isEmpty() && asked.shutdown().isEmpty()
                && asked.beforeRun().isEmpty() && asked.afterRun().isEmpty()) {
            context.sink().accept(autosaveSettings(now));
            return LampExitStatus.SUCCESS;
        }

        LampConfig.Autosave wanted = new LampConfig.Autosave(asked.startup().orElse(now.startup()),
                asked.shutdown().orElse(now.shutdown()), asked.beforeRun().orElse(now.beforeRun()),
                asked.afterRun().orElse(now.afterRun()));
        java.util.SequencedMap<String, String> values = new java.util.LinkedHashMap<>();
        asked.startup().ifPresent(on -> values.put("startup", on.toString()));
        asked.shutdown().ifPresent(on -> values.put("shutdown", on.toString()));
        asked.beforeRun().ifPresent(on -> values.put("before_run", on.toString()));
        asked.afterRun().ifPresent(on -> values.put("after_run", on.toString()));
        return changeConfig(machine, context, layout, "[autosave]",
                file -> ConfigFileEditUtil.withValues(file, "autosave", values),
                ((Result.Ok<LampConfig>) before).value().withAutosave(wanted),
                () -> context.sink().accept(autosaveSettings(wanted)));
    }

    /// `oillamp history <dir> max-growth [<gb>]`: how far the lamp's file lets the history grow
    /// beyond its first snapshot, after writing `gb` into it. Only `max_growth_gb` under
    /// `[history]` changes, as with [#autosave]. The next save prunes to the new size.
    private static LampExitStatus maxGrowth(Machine machine, Context context, Path lampPath, Optional<Double> gb) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<LampConfig> before = loadConfig(machine, layout.root());
        if (before instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.History now = ((Result.Ok<LampConfig>) before).value().history();
        if (gb.isEmpty()) {
            context.sink().accept(new LampEvent.HistoryGrowthLimit(now.maxGrowthGb()));
            return LampExitStatus.SUCCESS;
        }

        LampConfig.History wanted = new LampConfig.History(gb.get());
        java.util.SequencedMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put("max_growth_gb", java.math.BigDecimal.valueOf(gb.get()).stripTrailingZeros().toPlainString());
        return changeConfig(machine, context, layout, "[history]",
                file -> ConfigFileEditUtil.withValues(file, "history", values),
                ((Result.Ok<LampConfig>) before).value().withHistory(wanted),
                () -> context.sink().accept(new LampEvent.HistoryGrowthLimit(wanted.maxGrowthGb())));
    }

    private static LampEvent.AutosaveSettings autosaveSettings(LampConfig.Autosave autosave) {
        return new LampEvent.AutosaveSettings(autosave.startup(), autosave.shutdown(),
                                              autosave.beforeRun(), autosave.afterRun());
    }

    /// Writes `edit` of a lamp's `oillamp.toml`, while no session runs, and then reports.
    ///
    /// The changed file is read back as `at` would read it, and written only when it is valid
    /// and reads as `wanted`, so a slip of the line editor never reaches the file. Refused
    /// while a session runs, which read the file as it started and would not see the change.
    ///
    /// @param table    the table that changes, such as `[autosave]`, for the problem if it went wrong
    /// @param wanted   the whole configuration as the changed file must read
    /// @param reported reports what the file now says, once it is written
    private static LampExitStatus changeConfig(Machine machine, Context context, LampLayout layout, String table,
                                           java.util.function.UnaryOperator<String> edit, LampConfig wanted,
                                           Runnable reported) {
        Optional<LampLock> lock;
        try {
            lock = LampLock.tryAcquire(layout.lockFile());
        } catch (IOException e) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.lampNotWritable(layout.root(), ProblemCatalogUtil.reason(e))));
            return LampExitStatus.ERROR;
        }
        if (lock.isEmpty()) {
            Tuple<LampProblem> busy = Tuple.of(LampProblem.class, busyProblem(layout));
            context.sinkAcceptProblems(busy);
            return exitStatusFor(busy);
        }
        try {
            String file = FilesystemUtil.readString(layout.config()).orElse("");
            String changed = edit.apply(file);
            Tuple<ConfigSource> sources = LampPhase.configurationFiles(home(machine), layout.config())
                    .retainIf(source -> source.kind() == ConfigSource.Kind.USER_GLOBAL)
                    .add(ConfigSource.lamp(layout.config(), changed));
            Result<LampConfig> after = ConfigLoadingUtil.load(sources);
            if (after instanceof Result.Err<LampConfig> failure) {
                context.sinkAcceptProblems(failure.problems());
                return exitStatusFor(failure.problems());
            }
            if (!((Result.Ok<LampConfig>) after).value().equals(wanted)) {
                Tuple<LampProblem> wrong = Tuple.of(LampProblem.class, ProblemCatalogUtil.internal("writing " + table,
                        "the changed oillamp.toml did not read back as what was asked, so it was left as it was"));
                context.sinkAcceptProblems(wrong);
                return exitStatusFor(wrong);
            }
            FilesystemUtil.writeFile(layout.config(), changed, PosixMode.PRIVATE_FILE);
            reported.run();
            return LampExitStatus.SUCCESS;
        } catch (IOException e) {
            context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                    ProblemCatalogUtil.lampNotWritable(layout.root(), ProblemCatalogUtil.reason(e))));
            return LampExitStatus.ERROR;
        } finally {
            release(lock.get());
        }
    }

    /// `oillamp answer <dir> yes|no <question>`: the person's answer to a host-access question
    /// the running session asked, named by its text.
    private static LampExitStatus answer(Machine machine, Context context, Path lampPath, boolean yes, String question) {
        return askTheSession(machine, context, lampPath, "answer", Control.Request.of("host-access-answer")
                        .with("question", question).with("yes", String.valueOf(yes)),
                reply -> context.sink().accept(new LampEvent.Ok("host access", "answered " + (yes ? "yes" : "no"))));
    }

    /// `oillamp follow <dir>`: report everything a running session reports, as it happens,
    /// until it ends.
    ///
    /// It starts with what a latecomer needs: how to reach the sandbox, the session's state, the
    /// run in progress from its start, and the runs waiting. Following ends nothing: Ctrl-C here
    /// leaves the session running. Embedded, following ends when standard input closes, as the
    /// session itself would for the application that started it.
    private static LampExitStatus follow(Machine machine, Context context, Path lampPath) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<Control.Reply> followed = Control.follow(layout.controlSocket(), layout.root(),
                Control.Request.of("follow"), "follow",
                line -> LampEvent.fromJson(line).ifPresent(context.sink()),
                context.options().embedded() ? Optional.of(machine.standardInput()) : Optional.empty());
        if (followed instanceof Result.Err<Control.Reply> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        return LampExitStatus.SUCCESS;
    }

    private static void release(LampLock lock) {
        try {
            lock.close();
        } catch (IOException ignored) {
            // The kernel releases it when this process ends, moments from now.
        }
    }

    // ─── reaching the supervisor ───────────────────────────────────────────────────────────

    private static LampExitStatus askTheSession(Machine machine, Context context, Path lampPath, String command,
                                            Control.Request request, Consumer<Control.Reply> onSuccess) {
        Result<Control.Reply> reply = askTheSession(machine, lampPath, command, request);
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        onSuccess.accept(((Result.Ok<Control.Reply>) reply).value());
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp recordings <dir> [--open <session>] [--prune]`: list, play or prune the lamp's
    /// recordings.
    ///
    /// The recordings belong to the infra user, so this user can read them but not delete them
    /// directly; `--prune` deletes through `podman unshare`.
    ///
    /// Works while a session is running. The file being recorded is listed with its current size
    /// and duration.
    private static LampExitStatus recordings(Machine machine, Context context, Path lampPath, Optional<String> open, boolean prune) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Tuple<RecordingFile> existing = FilesystemUtil.listRecordings(layout.recordingsDir());

        if (open.isPresent()) return openRecording(machine, context, existing, open.get(), layout);

        context.sink().accept(new LampEvent.Answer(describeRecordings(existing, layout)));
        if (!prune) return LampExitStatus.SUCCESS;

        Result<LampConfig> loaded = loadConfig(machine, lampPath);
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.Recording policy = ((Result.Ok<LampConfig>) loaded).value().recording();
        Tuple<RecordingFile> doomed = RecordingRetentionUtil.select(existing, policy, machine.now());
        if (doomed.isEmpty()) {
            context.sink().accept(new LampEvent.Ok("recordings", "nothing is beyond the configured retention of "
                    + policy.maxAgeDays() + " days / " + policy.maxTotalGb() + " GB"));
            return LampExitStatus.SUCCESS;
        }
        Result<Plan> done = new StepRunner(machine, context)
                .run(LampPlanUtil.planPrune(doomed, policy));
        if (done instanceof Result.Err<Plan> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.sinkAcceptProblems(done.warnings());
        if (context.options().dryRun()) return LampExitStatus.SUCCESS;
        context.sink().accept(new LampEvent.Ok("recordings", doomed.size() + " recording(s) deleted"));
        return LampExitStatus.SUCCESS;
    }

    private static LampExitStatus openRecording(Machine machine, Context context, Tuple<RecordingFile> existing,
                                            String session, LampLayout layout) {
        for (RecordingFile file : existing)
            if (file.session().map(id -> id.value().equals(session)).orElse(false)) {
                // Not waited for: with some players xdg-open only returns when the video is closed.
                Machine.Window window = machine.launch(
                        Machine.Command.of("xdg-open", file.path().toString()).labelled("xdg-open"),
                        Machine.Window.Stdio.DETACHED);
                if (window.failure().isPresent()) {
                    context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                            ProblemCatalogUtil.recordingNotOpened(file.path(), window.failure().get())));
                    return LampExitStatus.ERROR;
                }
                context.sink().accept(new LampEvent.Ok("recordings", "opened " + file.path()));
                return LampExitStatus.SUCCESS;
            }
        context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                ProblemCatalogUtil.noSuchRecording(session, layout.root(), layout.recordingsDir(), existing)));
        return LampExitStatus.USAGE;
    }

    private static String describeRecordings(Tuple<RecordingFile> existing, LampLayout layout) {
        if (existing.isEmpty())
            return "no recordings in " + layout.recordingsDir()
                 + "\nrecording is off unless `recording.enabled = true` is set in oillamp.toml";
        StringBuilder out = new StringBuilder(layout.recordingsDir() + "\n\n");
        long total = 0;
        for (RecordingFile file : existing) {
            total += file.sizeBytes();
            Path name = file.path().getFileName();
            out.append("  ").append(name == null ? file.path().toString() : name.toString())
               .append("  ").append(pad(describeDuration(file), 9))
               .append("  ").append(pad(describeSize(file.sizeBytes()), 8))
               .append("  ").append(file.recordedAt().truncatedTo(ChronoUnit.SECONDS))
               .append('\n');
        }
        return out.append("\n  ").append(existing.size()).append(" recording(s), ")
                  .append(describeSize(total)).append(" in total").toString();
    }

    /// `text` followed by spaces up to `width`, for the columns of `status`, `list` and
    /// `recordings`.
    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /// "-" for a file whose duration is unknown.
    private static String describeDuration(RecordingFile file) {
        return file.duration()
                .map(gap -> gap.toHours() > 0
                        ? "%dh%02dm".formatted(gap.toHours(), gap.toMinutesPart())
                        : "%dm%02ds".formatted(gap.toMinutes(), gap.toSecondsPart()))
                .orElse("-");
    }

    private static String describeSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return "%.1f GB".formatted(bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)        return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        return "%d kB".formatted(bytes / 1024);
    }

    private static Result<Control.Reply> askTheSession(Machine machine, Path lampPath, String command,
                                                       Control.Request request) {
        Result<LampLayout> layout = layoutOf(machine, lampPath);
        if (layout instanceof Result.Err<LampLayout> failure) return Result.err(failure.problems());
        LampLayout found = ((Result.Ok<LampLayout>) layout).value();
        return Control.ask(found.controlSocket(), found.root(), request, command);
    }

    /// Finds a lamp's paths without creating or changing anything. Used by `view`,
    /// `shell`, `stop`, `status` and `recordings`.
    private static Result<LampLayout> layoutOf(Machine machine, Path lampPath) {
        Path root = LampPhase.resolve(machine, lampPath);
        LampState state = LampDirectoryUtil.classify(root, FilesystemUtil.list(root),
                FilesystemUtil.readString(root.resolve(".oillamp").resolve("lamp.json")));
        if (!(state instanceof LampState.Existing existing))
            return Result.err(ProblemCatalogUtil.lampNotWritable(root,
                    "this is not an oillamp lamp — run `oillamp at " + lampPath + "` to make one"));
        return Result.ok(new LampLayout(root, existing.meta().agentId(), HostProbeUtil.runtimeDirectory(machine)));
    }

    /// No supervisor answered. Either nothing is running, or a supervisor was killed without
    /// cleaning up. In that case its container, its control socket and `session.json` are left
    /// behind; this removes them. Without removing the socket, every later `status` would say
    /// "the session did not answer, run `oillamp stop`", and `stop` would say the same.
    ///
    /// A supervisor that answered at all, even to say no, is alive, and nothing is removed.
    private static LampExitStatus cleanUpAfterACrashedSession(Machine machine, Context context, Path lampPath,
                                                          Tuple<LampProblem> why) {
        Result<LampLayout> found = layoutOf(machine, lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.sinkAcceptProblems(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        if (Relay.answers(layout.controlSocket())) {
            context.sinkAcceptProblems(why);
            return exitStatusFor(why);
        }
        ContainerName container = layout.containerName();
        boolean containerLeft = machine.run(Machine.Command
                .of("podman", "container", "exists", container.value())
                .labelled("podman container exists")).succeeded();
        boolean filesLeft = FilesystemUtil.exists(layout.controlSocket())
                || FilesystemUtil.exists(layout.sessionMeta());
        if (!containerLeft && !filesLeft) {
            context.sinkAcceptProblems(why);
            return exitStatusFor(why);
        }
        context.sink().accept(new LampEvent.Info("stop",
                "no supervisor is running, but its session left things behind — cleaning up"));
        if (containerLeft) {
            Machine.Outcome removed = machine.run(Machine.Command
                    .of("podman", "rm", "-f", container.value())
                    .withTimeout(Duration.ofSeconds(30)).labelled("podman rm"));
            if (!removed.succeeded()) {
                context.sinkAcceptProblems(Tuple.of(LampProblem.class, ProblemCatalogUtil.podmanFailed(
                        "podman rm", removed.exitCode(), removed.errorOutput().strip())));
                return LampExitStatus.ERROR;
            }
        }
        for (Path leftover : Tuple.of(Path.class, layout.controlSocket(), layout.primarySshSocket(),
                                                layout.extraSshSocket(), layout.sessionMeta())) {
            try {
                FilesystemUtil.deleteIfPresent(leftover);
            } catch (IOException e) {
                context.sinkAcceptProblems(Tuple.of(LampProblem.class,
                        ProblemCatalogUtil.internal("cleanup", ProblemCatalogUtil.reason(e))));
            }
        }
        context.sink().accept(new LampEvent.Ok("stop", containerLeft
                ? "the sandbox left by the previous session has been removed"
                : "removed what the previous session left behind; its sandbox was already gone"));
        return LampExitStatus.SUCCESS;
    }

    /// `oillamp config <dir> check`: validate the lamp's configuration without changing anything.
    private static LampExitStatus checkConfig(Machine machine, Context context, Path lampPath) {
        HostPhase.Outcome host = new HostPhase(machine, new Context(context.sink(),
                context.options().withDryRun(true).withAutoInstall(false), context.version()))
                .prepare(lampPath, false, Installing.NEVER);
        context.sinkAcceptProblems(host.result().warnings());
        return checkConfiguration(machine, context, lampPath, true);
    }

    /// `oillamp config <dir> show-effective`: print a summary of the validated configuration,
    /// the global file and the lamp's merged, as `at` would use it.
    private static LampExitStatus showEffectiveConfig(Machine machine, Context context, Path lampPath) {
        Result<LampConfig> loaded = loadConfig(machine, lampPath);
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return LampExitStatus.USAGE;
        }
        LampConfig config = ((Result.Ok<LampConfig>) loaded).value();
        context.sink().accept(new LampEvent.Answer(describe(config)));
        return LampExitStatus.SUCCESS;
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private static LampExitStatus checkConfiguration(Machine machine, Context context, Path lampPath, boolean quiet) {
        Result<LampConfig> loaded = loadConfig(machine, lampPath);
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.sinkAcceptProblems(failure.problems());
            return LampExitStatus.USAGE;
        }
        context.sinkAcceptProblems(loaded.warnings());
        if (!quiet) context.sink().accept(new LampEvent.Ok("lamp", "configuration is valid"));
        else context.sink().accept(new LampEvent.Ok("config", "valid"));
        return LampExitStatus.SUCCESS;
    }

    /// The lamp's configuration merged over the global one, exactly as `at` reads it.
    private static Result<LampConfig> loadConfig(Machine machine, Path lampPath) {
        Path lampConfig = lampPath.resolve("oillamp.toml");
        if (!FilesystemUtil.exists(lampConfig))
            return Result.err(ProblemCatalogUtil.lampNotWritable(lampPath,
                    "there is no oillamp.toml here — run `oillamp at " + lampPath + "` to create one"));
        return ConfigLoadingUtil.load(LampPhase.configurationFiles(home(machine), lampConfig));
    }

    private static String describe(LampConfig config) {
        return "display      " + config.display().size() + " scale " + config.display().scale()
                + " gpu " + config.display().gpu().configName()
                + ", " + config.display().windows().configName() + " windows\n"
             + "viewer       clipboard " + config.viewer().clipboard().configName()
                + ", " + config.viewer().maxFps() + " fps"
                + (config.viewer().viewOnly() ? ", view only" : "") + "\n"
             + "recording    " + (config.recording().enabled()
                    ? config.recording().codec() + " crf " + config.recording().crf()
                      + ", keep " + config.recording().maxAgeDays() + " days / "
                      + config.recording().maxTotalGb() + " GB"
                    : "off") + "\n"
             + "limits       memory " + config.limits().memory()
                + ", cpus " + (config.limits().cpus() == 0 ? "all but one" : config.limits().cpus())
                + ", pids " + config.limits().pids() + "\n"
             + "network      default " + config.network().defaultDecision().configName()
                + ", " + config.network().rules().size() + " rule(s), "
                + config.forwards().size() + " forward(s)\n"
             + "image        " + config.image().base() + ", " + config.image().jdkPackage()
                + ", node " + config.image().nodeVersion()
             + "\nhost access  " + describeHostAccess(config.hostAccess());
    }

    /// What the sandbox may reach on the host, and what it is judged by. The requirements are the
    /// user's own text and are printed as written: this is the command they use to check it.
    private static String describeHostAccess(LampConfig.HostAccess access) {
        if (!access.enabled()) return "off";
        String roots = access.roots().isEmpty() ? "your home directory" : String.join(", ", access.roots());
        return (access.requirements().isBlank() ? "(no requirements!)" : "on") + "\n"
             + "               checker " + (access.checkerModel().isBlank() ? "the agent's model" : access.checkerModel())
             + ", roots " + roots
             + ", " + (access.allowAdditive() ? "writing and opening pages allowed" : "read only")
             + (access.askTheUser() ? ", asks you past the roots" : "")
             + ", " + (access.executables().isEmpty() ? "no programs" : "programs: " + String.join(", ", access.executables()))
             + "\n"
             + "               requirements, as the checker sees them:\n"
             + access.requirements().lines().map(line -> "                 | " + line).reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b);
    }

    /// Says who holds the lamp, from the `session.json` the running session wrote.
    private static LampProblem busyProblem(LampPhase.Prepared prepared) {
        return busyProblem(prepared.layout());
    }

    private static LampProblem busyProblem(LampLayout layout) {
        Optional<com.fasterxml.jackson.databind.JsonNode> session =
                FilesystemUtil.readString(layout.sessionMeta()).flatMap(JsonUtil::parse);
        return ProblemCatalogUtil.lockBusy(layout.root(),
                session.map(json -> json.path("supervisorPid").asLong(0)).orElse(0L),
                session.map(json -> JsonUtil.text(json, "startedAt")).filter(when -> !when.isEmpty())
                       .orElse("an earlier time"));
    }

    /// The exit code for a list of problems: 4 for a busy lamp, 2 for configuration errors and for a
    /// snapshot or branch that cannot be the one meant, otherwise 1.
    private static LampExitStatus exitStatusFor(Tuple<LampProblem> problems) {
        for (LampProblem problem : problems) {
            String code = problem.code().value();
            if (code.equals("OIL-LOCK-001") || code.equals("OIL-HISTORY-005")) return LampExitStatus.LAMP_BUSY;
            if (code.equals("OIL-HISTORY-001") || code.equals("OIL-HISTORY-008") || code.equals("OIL-HISTORY-009")
                    || code.equals("OIL-HISTORY-010") || code.equals("OIL-HISTORY-011")) return LampExitStatus.USAGE;
            if (code.startsWith("OIL-CONFIG-")) return LampExitStatus.USAGE;
        }
        for (LampProblem problem : problems)
            if (problem.isError()) return LampExitStatus.ERROR;
        return LampExitStatus.SUCCESS;
    }
}
