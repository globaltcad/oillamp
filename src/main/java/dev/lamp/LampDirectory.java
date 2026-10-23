package dev.lamp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/// A lamp's directory, whether or not a session runs it: what can be done to the lamp at rest,
/// and how to start or join its session. [Lamp#at(Path)] returns one.
///
/// ```
/// LampDirectory north = Lamp.at(Path.of("/home/me/campaigns/north")).onEvent(ui::show);
/// List<LampSave> saves = north.history();      // works whether or not a session runs
/// try (Lamp lamp = north.model("azure/gpt-6.1-sol").start()) { … }
/// ```
///
/// It is immutable: each method that configures it returns a new one and leaves this one as it
/// was. Those methods are of two kinds:
///
/// - [#onEvent] and [#launchedBy] apply to everything this directory does: the session that
///   [#start] or [#join] opens, and every command below.
/// - [#modelService], [#modelKey], [#model], [#instructions], [#security] and
///   [#enableScheduling] only say how [#start] starts a session. Nothing is written to the lamp
///   before then, and [#join] ignores them: the session it joins keeps the settings it was
///   started with.
///
/// The other methods act on the lamp itself, such as [#save], [#history], [#restore],
/// [#schedule] and [#remove]. Each one runs a short-lived engine process, waits for it, and
/// passes what it reports to the listeners; each says whether it works while a session runs.
/// The conversation methods read the agent's home directly, and [#ask] needs a running session.
public final class LampDirectory {

    private final Path directory;
    private final List<Consumer<LampEvent>> listeners;
    private final LampLauncher launcher;
    private final Optional<URI> modelService;
    private final Optional<String> modelKey;
    private final Optional<String> model;
    private final Optional<String> instructions;
    private final boolean scheduling;
    private final Optional<Security> security;

    /// What the agent may do on the host, and what it is judged against.
    ///
    /// @param requirements the user's own words: what the agent may not see, change or run.
    ///                     Handed to the checking model with every request, and to no one else
    public record Security(String requirements) {
        public Security {
            if (requirements == null || requirements.isBlank())
                throw new IllegalArgumentException("the host-access requirements cannot be blank");
            if (requirements.getBytes(StandardCharsets.UTF_8).length > Lamp.LONGEST_INSTRUCTIONS)
                throw new IllegalArgumentException("the host-access requirements cannot be longer than "
                        + Lamp.LONGEST_INSTRUCTIONS / 1024 + " KiB");
        }
    }

    LampDirectory(
            final Path directory,
            final List<Consumer<LampEvent>> listeners,
            final LampLauncher launcher,
            final Optional<URI> modelService,
            final Optional<String> modelKey,
            final Optional<String> model,
            final Optional<String> instructions,
            final boolean scheduling,
            final Optional<Security> security
    ) {
        this.directory = directory;
        this.listeners = listeners;
        this.launcher = launcher;
        this.modelService = modelService;
        this.modelKey = modelKey;
        this.model = model;
        this.instructions = instructions;
        this.scheduling = scheduling;
        this.security = security;
    }

    /// Receives every event the engine reports, in order, from the moment it starts.
    ///
    /// Called on one thread that reads the engine's output. A listener that takes long holds
    /// up the ones after it, so a Swing application hands the event over to its event thread.
    ///
    /// A listener that throws misses nothing else: the exception goes to the reading thread's
    /// uncaught exception handler, and the other listeners, and the lamp itself, still get
    /// this event and every one after it.
    public LampDirectory onEvent(Consumer<LampEvent> listener) {
        List<Consumer<LampEvent>> more = new ArrayList<>(listeners);
        more.add(listener);
        return new LampDirectory(directory, List.copyOf(more), launcher, modelService, modelKey, model, instructions, scheduling, security);
    }

    /// Starts the engine with `launcher` instead of the default, which runs `java` from this
    /// JVM with this application's classpath, and runs [Lamp#exec]'s commands with it. For
    /// tests, or for an application that runs the engine some other way.
    public LampDirectory launchedBy(LampLauncher launcher) {
        return new LampDirectory(directory, listeners, launcher, modelService, modelKey, model, instructions, scheduling, security);
    }

    /// Sends the sandbox's model requests to `service`, in place of the lamp's `model.service`:
    /// `https://api.eu.edenai.run`, say, or a model server on this machine with the path of
    /// its API, such as `http://127.0.0.1:11434/v1` for Ollama. It must be `https`, unless it
    /// is on this machine's loopback; the engine refuses anything else, and the events say why.
    ///
    /// Only Eden AI's models are filtered to those served in the EU. Any other service's
    /// models are all offered, since its model list names no regions.
    public LampDirectory modelService(URI service) {
        return new LampDirectory(directory, listeners, launcher, Optional.of(service), modelKey, model, instructions, scheduling, security);
    }

    /// Uses `key` for the sandbox's model requests, in place of the one in the variable the
    /// lamp's `model.key_env` names. The sandbox never sees it.
    ///
    /// @throws IllegalArgumentException when `key` is blank, which would only fail later
    public LampDirectory modelKey(String key) {
        if (key.isBlank()) throw new IllegalArgumentException("a model key cannot be blank");
        return new LampDirectory(directory, listeners, launcher, modelService, Optional.of(key.strip()), model, instructions, scheduling, security);
    }

    /// Lets jobs on the schedule wake the agent in the session [#start] starts, as
    /// `enabled = true` under `[schedule]` in the lamp's oillamp.toml would. The file is not
    /// changed.
    public LampDirectory enableScheduling() {
        return new LampDirectory(directory, listeners, launcher, modelService, modelKey, model, instructions, true, security);
    }

    /// Has the agent answer every question with `id`, a model as the service names it, such
    /// as `azure/gpt-6.1-sol` from [Lamp#models]. It applies to new conversations and to old
    /// ones alike: a question in a conversation that was on another model is answered by this
    /// one, and the earlier answers keep the model that gave them.
    ///
    /// The engine also writes it as pi's default model into pi's settings in the agent's home,
    /// keeping the rest of them, so that pi starts a new conversation on it by itself.
    ///
    /// Without it, a new conversation gets whatever default model pi's settings name, and an
    /// old one stays on the model it was last on.
    ///
    /// A model the service does not offer fails each run that needs it, and the run's answer
    /// names the model.
    ///
    /// @throws IllegalArgumentException when `id` is empty or has spaces, which no service's
    ///                                  model has
    public LampDirectory model(String id) {
        if (!id.matches(Lamp.MODEL_ID))
            throw new IllegalArgumentException("not a model as a service names it: \"" + id + "\"");
        return new LampDirectory(directory, listeners, launcher, modelService, modelKey, Optional.of(id), instructions, scheduling, security);
    }

    /// Tells the agent `text` in addition to pi's own instructions, for the whole session:
    /// what it is, how the user reaches it, where it hands over files. pi adds it to its
    /// system prompt, in every conversation, old and new.
    ///
    /// The engine writes it into the agent's home, where pi reads it, once the sandbox is up
    /// and before pi starts; that is `~/.pi/agent/APPEND_SYSTEM.md`, replacing what was
    /// there. Without this, that file stays as it is, with whatever an earlier session, the
    /// agent or a person put there.
    ///
    /// The text goes to the engine in its environment, like the key: it can be long and run
    /// over many lines, which a command line is poor at, and it is nobody else's business.
    ///
    /// @throws IllegalArgumentException when `text` is blank, or longer than 64 KiB, far more
    ///                                  than any useful instructions
    public LampDirectory instructions(String text) {
        if (text.isBlank()) throw new IllegalArgumentException("instructions cannot be blank");
        if (text.getBytes(StandardCharsets.UTF_8).length > Lamp.LONGEST_INSTRUCTIONS)
            throw new IllegalArgumentException("instructions cannot be longer than " + Lamp.LONGEST_INSTRUCTIONS / 1024 + " KiB");
        return new LampDirectory(directory, listeners, launcher, modelService, modelKey, model, Optional.of(text), scheduling, security);
    }

    /// Lets the agent reach the host machine through oillamp, as `[host_access]` in the lamp's
    /// file would, and says what it is judged against.
    ///
    /// Every request from the sandbox to the host is first judged by a model, in a
    /// conversation of its own that never sees the agent's messages: it sees the requirements,
    /// the request, and what the run was asked to do and did just before. A refusal is final
    /// for that request. The gate judges by your text and nothing else, so it is the text that
    /// matters: say what must not happen.
    ///
    /// The feature must be on in the lamp's `oillamp.toml` already: an application gives the
    /// rules, it does not open a door the user never asked for. The roots, the executable
    /// allowlist and the rest stay as the file has them.
    ///
    /// The model that judges is the one the agent answers with, from [#model] and later
    /// [Lamp#useModel], unless the lamp's file names a `checker_model` of its own. A lamp with
    /// neither does not start: there is no model to judge with.
    ///
    /// The requirements go to the engine in its environment, like [LampDirectory#instructions], so
    /// that they are not on a command line any user of the machine can read.
    ///
    /// @throws IllegalArgumentException when the requirements are blank or longer than 64 KiB
    public LampDirectory security(String requirements) {
        return new LampDirectory(directory, listeners, launcher, modelService, modelKey, model,
                instructions, scheduling, Optional.of(new Security(requirements)));
    }

    /// Starts the engine, and returns at once. The sandbox is running when
    /// [Lamp#awaitRunning] says so; building its image the first time takes several minutes.
    ///
    /// @throws IOException when the engine's process could not be started at all
    public Lamp start() throws IOException {
        List<String> arguments = new ArrayList<>(List.of("at", directory.toString(), "--embedded"));
        modelService.ifPresent(service -> arguments.addAll(List.of("--model-service", service.toString())));
        modelKey.ifPresent(_ -> arguments.addAll(List.of("--model-key-env", Lamp.MODEL_KEY_VARIABLE)));
        model.ifPresent(id -> arguments.addAll(List.of("--model", id)));
        instructions.ifPresent(_ -> arguments.addAll(List.of("--instructions-env", Lamp.INSTRUCTIONS_VARIABLE)));
        security.ifPresent(_ -> arguments.addAll(List.of("--security-requirements-env",
                Lamp.SECURITY_REQUIREMENTS_VARIABLE)));
        if (scheduling) arguments.add("--enable-scheduling");
        Map<String, String> environment = new HashMap<>();
        modelKey.ifPresent(key -> environment.put(Lamp.MODEL_KEY_VARIABLE, key));
        instructions.ifPresent(text -> environment.put(Lamp.INSTRUCTIONS_VARIABLE, text));
        security.ifPresent(rules -> environment.put(Lamp.SECURITY_REQUIREMENTS_VARIABLE, rules.requirements()));
        Process engine = launcher.launch(List.copyOf(arguments), Map.copyOf(environment));
        Lamp lamp = new Lamp(directory, launcher, engine, listeners, true);
        Thread.ofVirtual().name("lamp-events-" + directory.getFileName()).start(lamp::readEvents);
        return lamp;
    }

    /// Joins the session already running this lamp, whoever started it: this application
    /// before it was closed and [left it running][Lamp#leaveRunning()], another application,
    /// or a person at a terminal. Returns at once.
    ///
    /// The lamp returned is used as one this application started. Its listeners first hear
    /// what the session is doing now: [LampEvent.SessionOpened], after which
    /// [Lamp#awaitRunning] is true; the session's state; the run in progress, from its
    /// [LampEvent.RunStarted], with what the agent wrote so far; a [LampEvent.RunQueued]
    /// for each run waiting; and last a [LampEvent.AgentStatus], which marks the end of the
    /// catching up. Then they hear every event as it happens. The model settings are
    /// those the session was started with.
    ///
    /// Closing it only stops following; the session goes on. [Lamp#stop()] ends it.
    ///
    /// Where no session runs, [Lamp#awaitRunning] is false, and a [LampEvent.Failure]
    /// with problem `OIL-SESSION-001` says so.
    ///
    /// @throws IOException when the engine's process could not be started at all
    public Lamp join() throws IOException {
        Process follower = launcher.launch(List.of("follow", directory.toString(), "--embedded"), Map.of());
        Lamp lamp = new Lamp(directory, launcher, follower, listeners, false);
        Thread.ofVirtual().name("lamp-events-" + directory.getFileName()).start(lamp::readEvents);
        return lamp;
    }

    /// Whether a session is running this lamp, so that [#join] finds it. Looks only at the
    /// record a session keeps while it runs; a session whose engine was killed leaves that
    /// record behind, and [#join] then says it cannot reach it.
    public boolean isRunning() {
        return Files.exists(directory.resolve(".oillamp").resolve("session.json"));
    }

    /// Deletes this lamp for good: the agent's home with everything the agent made in it,
    /// the lamp's state and its configuration. Files in the directory that oillamp did not
    /// make are left, and so is the directory then.
    ///
    /// Part of a lamp belongs to the sandbox's own users, so neither the application nor
    /// `rm -rf` can delete it; the engine can, as `oillamp remove <dir> --yes`. A lamp whose
    /// sandbox is still running is refused: close it first.
    ///
    /// Blocks until the engine is done. What it reports goes to the listeners.
    ///
    /// @return [LampExitStatus#SUCCESS] once the lamp is gone; otherwise the events said why
    /// @throws IOException when the engine's process could not be started at all
    public LampExitStatus remove() throws IOException, InterruptedException {
        return Lamp.runToEnd(launcher, listeners, "remove", directory.toString(), "--yes").status();
    }

    /// Takes a snapshot of this lamp: the agent's home, its `oillamp.toml` and its schedule, as
    /// they are now. [#restore] brings the lamp back to it later. Works whether or not the lamp's
    /// sandbox is running; see [Lamp#save(LampLauncher, List, Path, String)] for one this application holds.
    ///
    /// Blocks until the engine is done. What it reports goes to the listeners.
    ///
    /// @param message what to remember the snapshot by; may be empty
    /// @return the snapshot, or empty when nothing changed since the last one
    /// @throws LampFailedException when the lamp could not be saved; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public Optional<LampSave> save(String message) throws IOException, InterruptedException, LampFailedException {
        return Lamp.save(launcher, listeners, directory, message);
    }

    /// Every snapshot of this lamp, newest first.
    ///
    /// @throws LampFailedException when the lamp's history could not be read; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public List<LampSave> history() throws IOException, InterruptedException, LampFailedException {
        Lamp.Ran ran = Lamp.runToEnd(launcher, listeners, "history", directory.toString());
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.History(sprouts.Tuple<LampSave> snapshots1)) {
                List<LampSave> snapshots = new ArrayList<>();
                for (LampSave snapshot : snapshots1) snapshots.add(snapshot);
                return List.copyOf(snapshots);
            }
        return List.of();
    }

    /// Brings this lamp back to `snapshot`: the agent's home, `oillamp.toml` and the schedule
    /// become what they were when it was saved; a snapshot from before oillamp saved the schedule
    /// leaves the schedule as it is. The lamp is saved first, so a restore can be undone by
    /// restoring that save. A lamp whose sandbox is running is refused: close it first.
    ///
    /// @param snapshot a snapshot's [id][LampSave#id()], or a unique beginning of
    ///                 it of at least four characters
    /// @return the snapshot that records the restore, or the one restored when the lamp
    ///         already was in that state
    /// @throws LampFailedException when there is no such snapshot, the sandbox is running, or the
    ///                     restore did not finish; the problem says which
    /// @throws IOException when the engine's process could not be started at all
    public LampSave restore(String snapshot) throws IOException, InterruptedException, LampFailedException {
        Lamp.Ran ran = Lamp.runToEnd(launcher, listeners, "history", directory.toString(), "restore", snapshot);
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.Restored restored) return restored.result();
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no restore"));
    }

    /// Reads the values of `enabled` and `requirements` in the `[host_access]` table of this
    /// lamp's `oillamp.toml`, merged over `~/.config/oillamp/config.toml` as a session reads
    /// them.
    ///
    /// Runs `oillamp host-access <dir>` as a short-lived engine process. It only reads the
    /// file, so it works whether or not a session is running.
    ///
    /// @throws LampFailedException when the directory is not a lamp, or its configuration is not
    ///                     valid; the problem says which
    /// @throws IOException when the engine's process could not be started at all
    public Lamp.HostAccess hostAccess() throws IOException, InterruptedException, LampFailedException {
        return Lamp.hostAccessOf(InternalLampUtil.runToEnd(launcher, listeners, Map.of(), "host-access", directory.toString()));
    }

    /// Sets the values of `enabled` and `requirements` in the `[host_access]` table of this
    /// lamp's `oillamp.toml`, and returns them as the file now has them.
    ///
    /// Runs `oillamp host-access <dir> on|off --requirements-env OILLAMP_SECURITY_REQUIREMENTS`
    /// as a short-lived engine process. The requirements are passed in that environment
    /// variable, so that they do not appear in the process list. The engine replaces only
    /// those two keys and keeps every other line of the file, comments included. It writes
    /// the file only when the result is a valid configuration.
    ///
    /// Refused with [LampExitStatus#LAMP_BUSY] while a session runs this lamp, because a session
    /// reads the file only when it starts.
    ///
    /// @param enabled      the new value of `enabled`: whether the next session gives the
    ///                     agent the host tools
    /// @param requirements the new value of `requirements`: the text the checker model judges
    ///                     every host request against. May be empty only when `enabled` is
    ///                     false. Plain text in the user's own words, or anything else the
    ///                     checker model can read, such as JSON from a settings form
    /// @return the values as the file now has them
    /// @throws LampFailedException when the sandbox is running, or the settings are not valid, such
    ///                     as on with no requirements; the problem says which
    /// @throws IOException when the engine's process could not be started at all
    public Lamp.HostAccess hostAccess(boolean enabled, String requirements)
            throws IOException, InterruptedException, LampFailedException {
        return Lamp.hostAccessOf(InternalLampUtil.runToEnd(launcher, listeners, Map.of(Lamp.SECURITY_REQUIREMENTS_VARIABLE, requirements),
                "host-access", directory.toString(), enabled ? "on" : "off",
                "--requirements-env", Lamp.SECURITY_REQUIREMENTS_VARIABLE));
    }

    /// Reads when oillamp saves this lamp by itself: the values in the `[autosave]` table of
    /// its `oillamp.toml`, merged over `~/.config/oillamp/config.toml` as a session reads them.
    ///
    /// Runs `oillamp history <dir> autosave` as a short-lived engine process. It only reads the
    /// file, so it works whether or not a session is running.
    ///
    /// @throws LampFailedException when the directory is not a lamp, or its configuration is not
    ///                     valid; the problem says which
    /// @throws IOException when the engine's process could not be started at all
    public Lamp.Autosave autosave() throws IOException, InterruptedException, LampFailedException {
        return Lamp.autosaveOf(Lamp.runToEnd(launcher, listeners, "history", directory.toString(), "autosave"));
    }

    /// Sets when oillamp saves this lamp by itself: all four values in the `[autosave]` table
    /// of its `oillamp.toml`. Returns them as the file now has them.
    ///
    /// Runs `oillamp history <dir> autosave startup on|off shutdown on|off before-run on|off
    /// after-run on|off` as a short-lived engine process. The engine replaces only those
    /// keys and keeps every other line of the file, comments included.
    ///
    /// Refused with [LampExitStatus#LAMP_BUSY] while a session runs this lamp, because a session
    /// reads the file only when it starts.
    ///
    /// @throws LampFailedException when the sandbox is running, or the lamp's file is not valid; the
    ///                     problem says which
    /// @throws IOException when the engine's process could not be started at all
    public Lamp.Autosave autosave(Lamp.Autosave wanted) throws IOException, InterruptedException, LampFailedException {
        return Lamp.autosaveOf(Lamp.runToEnd(launcher, listeners, "history", directory.toString(), "autosave",
                "startup", Lamp.onOff(wanted.startup()), "shutdown", Lamp.onOff(wanted.shutdown()),
                "before-run", Lamp.onOff(wanted.beforeRun()), "after-run", Lamp.onOff(wanted.afterRun())));
    }

    /// This lamp's schedule: the jobs that wake the agent while a session runs.
    ///
    /// @throws LampFailedException when the schedule could not be read; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public LampEvent.Schedule schedule() throws IOException, InterruptedException, LampFailedException {
        return Lamp.schedule(launcher, listeners, directory);
    }

    /// Adds a job that wakes the agent again and again, as `cron` says.
    ///
    /// @param cron   five fields, as cron has them, on this machine's clock, such as
    ///               `0 9 * * 1-5` for nine on every weekday
    /// @param prompt what the agent is asked each time
    /// @throws LampFailedException when the expression cannot be read; the problem says why
    public LampJob repeat(String cron, String prompt) throws IOException, InterruptedException, LampFailedException {
        return Lamp.addJob(launcher, listeners, directory, "--cron", cron, prompt);
    }

    /// The same, taken off the schedule at `until`.
    public LampJob repeat(String cron, String prompt, Instant until) throws IOException, InterruptedException, LampFailedException {
        return Lamp.addJob(launcher, listeners, directory, "--cron", cron, prompt, "--expires", until.toString());
    }

    /// Adds a job that wakes the agent once.
    ///
    /// @param at when: `2026-10-01 09:00` on this machine's clock, `2026-10-01T07:00Z`, or
    ///           `in 2h`
    /// @throws LampFailedException when the time cannot be read or has passed; the problem says why
    public LampJob once(String at, String prompt) throws IOException, InterruptedException, LampFailedException {
        return Lamp.addJob(launcher, listeners, directory, "--at", at, prompt);
    }

    /// Adds a job that wakes the agent once, at `at`.
    ///
    /// @throws LampFailedException when `at` has passed
    public LampJob once(Instant at, String prompt) throws IOException, InterruptedException, LampFailedException {
        return once(at.toString(), prompt);
    }

    /// Switches a job back on. A repeating job then runs at its next time from now, not once
    /// for each time it was off.
    ///
    /// @throws LampFailedException when there is no such job
    public void enable(String job) throws IOException, InterruptedException, LampFailedException {
        Lamp.runToEnd(launcher, listeners, "schedule", directory.toString(), "enable", job).orThrow();
    }

    /// Switches a job off. It stays on the schedule, and does not run until it is switched on.
    ///
    /// @throws LampFailedException when there is no such job
    public void disable(String job) throws IOException, InterruptedException, LampFailedException {
        Lamp.runToEnd(launcher, listeners, "schedule", directory.toString(), "disable", job).orThrow();
    }

    /// Pauses the whole schedule: no job runs until [#resume].
    public void pause() throws IOException, InterruptedException, LampFailedException {
        Lamp.runToEnd(launcher, listeners, "schedule", directory.toString(), "pause").orThrow();
    }

    /// Lets the schedule run again after [#pause]. Each repeating job counts as if it had just
    /// run, and runs at its next time from now; the times the pause covered are not missed.
    public void resume() throws IOException, InterruptedException, LampFailedException {
        Lamp.runToEnd(launcher, listeners, "schedule", directory.toString(), "resume").orThrow();
    }

    /// Every conversation the agent had in this lamp, the most recent first. See
    /// [Lamp#conversations(Path)].
    public List<LampConversation> conversations() {
        return Lamp.conversations(directory);
    }

    /// The conversation named by `id` or a unique beginning of it.
    public Optional<LampConversation> conversation(String id) {
        return Lamp.conversation(directory, id);
    }

    /// Deletes a conversation for good. See [Lamp#forget(Path, String)].
    public void forget(String conversation) throws IOException {
        Lamp.forget(directory, conversation);
    }

    /// Asks the agent `question` in the session that is running this lamp, whoever started it,
    /// and waits for the answer. See [Lamp#ask(LampPrompt)].
    ///
    /// @throws LampFailedException when no session is running, or the question has no place to go
    public LampEvent.RunFinished ask(LampPrompt question) throws IOException, InterruptedException, LampFailedException {
        return Lamp.ask(launcher, listeners, directory, question);
    }

    /// Takes a job off the schedule, whoever added it.
    ///
    /// @param job such as `job-3`
    /// @throws LampFailedException when there is no such job
    public void unschedule(String job) throws IOException, InterruptedException, LampFailedException {
        Lamp.runToEnd(launcher, listeners, "schedule", directory.toString(), "remove", job).orThrow();
    }
}
