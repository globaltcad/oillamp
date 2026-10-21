package dev.lamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// A running lamp, held by the application that started it.
///
/// ```
/// try (Lamp lamp = Lamp.at(Path.of("/home/me/campaigns/north")).onEvent(ui::show).start()) {
///     if (lamp.awaitRunning(Duration.ofMinutes(15))) { … }
/// }   // closing ends the session and waits until the sandbox is gone
/// ```
///
/// The sandbox itself is run by oillamp's engine, in a separate Java process started from the
/// same classpath as the application: `oillamp at <dir> --embedded`. This object holds that
/// process:
///
/// - its **standard output** carries every [LampEvent] as a line of JSON, which is read on a
///   thread of its own and passed to the listeners;
/// - its **standard input** stays open for as long as the application wants the session. Closing
///   it, in [#close()], ends the session. If the application dies, the operating system closes it,
///   so the sandbox never outlives the application.
///
/// Model requests from the sandbox go through the engine, which adds the key. By default the
/// engine uses the lamp's `[model]` settings and reads the key from its environment, which it
/// inherits from the application. An application with its own settings screen gives both instead:
///
/// ```
/// Lamp.at(dir).modelService(URI.create("https://llm.example.com")).modelKey(userKey).start()
/// ```
///
/// The key goes to the engine in its environment, which only this user can read, and never onto
/// its command line or into the lamp.
///
/// An application that lets its user choose the model also gives that, with [LampStartupConf#model(String)]. The
/// agent then answers every question with it, in old conversations too. And an application that
/// has something to tell the agent about the world it lives in, such as how the user talks to it,
/// gives that with [LampStartupConf#instructions(String)]. The engine writes both into pi's own files in the
/// agent's home, so the application never needs to know where pi keeps them, or what they look
/// like.
///
/// Public because this is what an application uses oillamp through.
public final class Lamp implements AutoCloseable {

    /// The engine's main class. Named as text, because this package must not depend on the engine.
    static final String ENGINE = "dev.oillamp.OilLamp";
    /// What [#leaveRunning()] writes on the engine's standard input. Named as text for the same
    /// reason as [#ENGINE].
    static final String LEAVE_RUNNING = "leave-running";
    /// The variable in the engine's environment that holds a key given with [LampStartupConf#modelKey(String)].
    static final String MODEL_KEY_VARIABLE = "OILLAMP_MODEL_KEY";
    /// The variable in the engine's environment that holds instructions given with
    /// [LampStartupConf#instructions(String)].
    static final String INSTRUCTIONS_VARIABLE = "OILLAMP_INSTRUCTIONS";
    /// The variable in the engine's environment that holds the host-access requirements given
    /// with [LampStartupConf#security(String)].
    static final String SECURITY_REQUIREMENTS_VARIABLE = "OILLAMP_SECURITY_REQUIREMENTS";
    /// The longest instructions, in bytes of UTF-8. The engine checks the same.
    static final int LONGEST_INSTRUCTIONS = 64 * 1024;
    /// What a model's id may be: printable ASCII without spaces, as every service names its
    /// models. The engine checks the same, for the command line.
    static final String MODEL_ID = "[\\x21-\\x7E]{1,200}";
    /// Where pi keeps conversations, relative to the agent's home.
    static final String SESSIONS = ".pi/agent/sessions";
    /// Session files larger than this are left out. A long conversation with much tool output is
    /// a few megabytes.
    static final long LARGEST_CONVERSATION = 64L * 1024 * 1024;
    /// How the engine names a job run's conversation. Written out, because this package must
    /// not depend on the engine.
    private static final Pattern JOB_RUN_NAME = Pattern.compile("run-\\d+ \\((job-\\d+)\\)");
    /// The prefix of the agent directory's name in a lamp. Written out, because this package must
    /// not depend on the engine, whose layout says the same.
    static final String AGENT_DIR_PREFIX = "agent-lamp-";


    private final Path directory;
    private final Launcher launcher;
    private final Process engine;
    private final List<Consumer<LampEvent>> listeners;
    private final CountDownLatch runningOrEnded = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);
    /// How to run a command in the sandbox, once the engine has said so. Present means running.
    private volatile Optional<LampEvent.SessionOpened> opened = Optional.empty();
    private volatile Optional<ExitStatus> exit = Optional.empty();
    /// Whether the session ends when this lamp is closed: true for a lamp this application
    /// started, until it is left running; false for one it joined.
    private volatile boolean holding;

    Lamp(
            final Path directory,
            final Launcher launcher,
            final Process engine,
            final List<Consumer<LampEvent>> listeners,
            final boolean holding
    ) {
        this.directory = directory;
        this.launcher = launcher;
        this.engine = engine;
        this.listeners = listeners;
        this.holding = holding;
    }

    /// Starts describing the lamp in `directory`, which oillamp creates if it does not exist.
    public static LampStartupConf at(Path directory) {
        return new LampStartupConf(directory.toAbsolutePath(), List.of(), Lamp::sameJava,
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false,
                            Optional.empty());
    }

    /// Starts the engine as a separate process, with `arguments` after the engine's main class
    /// and `environment` added to the environment it inherits.
    ///
    /// Given a replacement with [LampStartupConf#launchedBy], for tests, or for an application that
    /// runs the engine some other way.
    @FunctionalInterface
    public interface Launcher {
        Process launch(List<String> arguments, Map<String, String> environment) throws IOException;
    }

    /// Everything an engine said, and how it ended.
    record Ran(ExitStatus status, List<LampEvent> events) {

        /// Throws the first error the engine reported, if it did not succeed.
        void orThrow() throws LampFailedException {
            if (status.isSuccess()) return;
            for (LampEvent event : events)
                if (event instanceof LampEvent.Failure(Problem problem)) throw new LampFailedException(status, problem);
            throw new LampFailedException(status, InternalLampUtil.internal("the engine exited with " + status + " without saying why"));
        }
    }

    /// Runs the engine for one command that ends by itself, passes each event to the listeners,
    /// and waits for it to exit.
    static Ran runToEnd(
        final Launcher launcher,
        final List<Consumer<LampEvent>> listeners,
        final String... command
    ) throws IOException, InterruptedException {
        return InternalLampUtil.runToEnd(launcher, listeners, Map.of(), command);
    }

    static Optional<LampSave> save(
        final Launcher launcher,
        final List<Consumer<LampEvent>> listeners,
        final Path directory,
        final String message
    ) throws IOException, InterruptedException, LampFailedException {
        Ran ran = message.isBlank()
                ? runToEnd(launcher, listeners, "save", directory.toString())
                : runToEnd(launcher, listeners, "save", directory.toString(), "--message", message);
        ran.orThrow();
        for ( LampEvent event : ran.events() )
            if (event instanceof LampEvent.Saved saved)
                return Optional.of(saved.snapshot());
        return Optional.empty();
    }

    /// The values the engine reported as a [LampEvent.HostAccessSettings], which is only how
    /// they travel from the engine to here.
    static HostAccess hostAccessOf(Ran ran) throws LampFailedException {
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.HostAccessSettings(boolean enabled, String requirements))
                return new HostAccess(enabled, requirements);
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no host access settings"));
    }

    /// The values the engine reported as a [LampEvent.AutosaveSettings].
    static Autosave autosaveOf(Ran ran) throws LampFailedException {
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.AutosaveSettings(
                    boolean startup, boolean shutdown, boolean beforeRun, boolean afterRun
            ))
                return new Autosave(startup, shutdown, beforeRun, afterRun);
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no autosave settings"));
    }

    static String onOff(boolean on) { return on ? "on" : "off"; }

    static LampEvent.Schedule schedule(
        final Launcher launcher,
        final List<Consumer<LampEvent>> listeners,
        final Path directory
    ) throws IOException, InterruptedException, LampFailedException {
        Ran ran = runToEnd(launcher, listeners, "schedule", directory.toString());
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.Schedule schedule) return schedule;
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no schedule"));
    }

    static LampJob addJob(
        final Launcher launcher,
        final List<Consumer<LampEvent>> listeners,
        final Path directory,
        final String option,
        final String when,
        final String prompt,
        final String... more
    ) throws IOException, InterruptedException, LampFailedException
    {
        List<String> command = new ArrayList<>(List.of("schedule", directory.toString(), "add", option, when));
        command.addAll(List.of(more));
        command.addAll(List.of("--", prompt));
        Ran ran = runToEnd(launcher, listeners, command.toArray(String[]::new));
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.JobAdded(LampJob job)) return job;
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no job"));
    }

    /// The values of `enabled` and `requirements` in the `[host_access]` table of a lamp's
    /// `oillamp.toml`, as [LampStartupConf#hostAccess()] reads them and [LampStartupConf#hostAccess(boolean, String)]
    /// writes them.
    ///
    /// @param enabled      whether a session gives the agent the host tools
    /// @param requirements the text the checker model judges every host request against
    public record HostAccess(boolean enabled, String requirements) {}

    /// When oillamp saves a lamp by itself, so that it can go back there: the `[autosave]` table
    /// of its `oillamp.toml`, as [LampStartupConf#autosave()] reads it and [LampStartupConf#autosave(Autosave)]
    /// writes it. A save someone asks for, with [LampStartupConf#save(String)], happens whatever these say.
    ///
    /// @param startup   as a session starts, before the sandbox runs. Off unless switched on
    /// @param shutdown  as a session ends, after the sandbox has stopped. Off unless switched on
    /// @param beforeRun just before a run wakes the agent, so that what the person changed and
    ///                  what the run changed are separate snapshots. On unless switched off
    /// @param afterRun  as a run ends, with the agent's last message. A job's prompt tells the
    ///                  agent what its last runs said and changed from these, so without them it
    ///                  is told nothing about them. On unless switched off
    public record Autosave(boolean startup, boolean shutdown, boolean beforeRun, boolean afterRun) {}

    // ─── conversations ─────────────────────────────────────────────────────────────────────

    /// Something to ask the agent, and where in its conversations it goes.
    ///
    /// @param conversation the conversation it continues, by its id or a unique start of it; empty
    ///                     for a new conversation
    /// @param after        an entry of that conversation to continue after, such as an earlier
    ///                     answer, which starts a branch there if the conversation went on since
    /// @param insteadOf    a question of that conversation to ask this instead of. The old question
    ///                     and what followed stay, as a branch of their own
    public record Question(
        String prompt,
        Optional<String> conversation,
        Optional<String> after,
        Optional<String> insteadOf
    ) {
        public Question {
            if (prompt.isBlank()) throw new IllegalArgumentException("a question needs something to ask");
            if ((after.isPresent() || insteadOf.isPresent()) && conversation.isEmpty())
                throw new IllegalArgumentException("an entry belongs to a conversation; name the conversation too");
            if (after.isPresent() && insteadOf.isPresent())
                throw new IllegalArgumentException("a question goes after an entry or instead of a question, not both");
        }

        /// In a new conversation.
        public static Question fresh(String prompt) {
            return new Question(prompt, Optional.empty(), Optional.empty(), Optional.empty());
        }

        /// In `conversation`, where it stands: after the entry written last.
        public static Question in(String conversation, String prompt) {
            return new Question(prompt, Optional.of(conversation), Optional.empty(), Optional.empty());
        }

        public static Question after(String conversation, String entry, String prompt) {
            return new Question(prompt, Optional.of(conversation), Optional.of(entry), Optional.empty());
        }

        public static Question insteadOf(String conversation, String entry, String prompt) {
            return new Question(prompt, Optional.of(conversation), Optional.empty(), Optional.of(entry));
        }
    }

    /// One conversation the agent had, as pi keeps it in the agent's home.
    ///
    /// A conversation is a tree of entries: every entry names the one before it. Most of the time
    /// that makes a line, a question, its answer and the next question. Asking something else
    /// instead of an earlier question starts a second line from the same entry, and both are kept.
    /// The conversation stands at its [leaf][#leaf()]: the entry written last. Asking in it
    /// continues from there, unless the question says where else it goes.
    ///
    /// @param id       pi's id for it, such as `01a0ec69-de1c-7234-914c-b970a862c13e`. Any unique
    ///                 beginning of it of at least four characters names it too
    /// @param name     the name it was given, such as `run-12 (job-3)`, or empty
    /// @param file     where pi keeps it, relative to the agent's home
    /// @param modified when its last entry was written
    /// @param entries  every entry, in the order pi wrote them
    public record Conversation(
        String id,
        String name,
        String file,
        Instant started,
        Instant modified,
        Tuple<Entry> entries
    ) {
        /// One line of pi's session file.
        ///
        /// @param id       unique within the conversation; what [Question#after] and
        ///                 [Question#insteadOf] take
        /// @param parent   the entry before this one; empty for the first. Entries with the same
        ///                 parent are where the conversation forks
        /// @param text     for a question, the message sent; for an answer, the agent's text, or the
        ///                 error when it failed; for a tool's output, the output; for a summary, the
        ///                 summary; otherwise empty
        /// @param thinking for an answer, the model's thinking; otherwise empty
        /// @param calls    for an answer, the tools it called; otherwise empty
        /// @param tool     for a tool's output, the tool's name; otherwise empty
        /// @param failed   for an answer, the model failed or was stopped; for a tool's output, the
        ///                 tool reported an error
        /// @param model    for an answer, the model that wrote it, as the service names it, such as
        ///                 `azure/gpt-6.1-sol`; otherwise empty. pi records it with every answer,
        ///                 so a conversation that changed model says which answer came from which
        public record Entry(String id, Optional<String> parent, Instant at, Kind kind, String text,
                            String thinking, Tuple<ToolCall> calls, Optional<String> tool, boolean failed,
                            Optional<String> model) {}

        public enum Kind {
            /// A message to the agent: a person's question, or a job's prompt.
            MESSAGE_TO_AGENT,
            /// A message from the agent.
            MESSAGE_FROM_AGENT,
            /// The output of one tool call.
            TOOL_OUTPUT,
            /// pi's summary of entries it took out of the model's context.
            SUMMARY,
            /// Anything else, such as pi's instructions to the model or a change of model.
            OTHER
        }

        /// A tool an answer called.
        ///
        /// @param summary the command for `bash`, the path for the file tools, otherwise the
        ///                arguments as JSON; one line, at most 160 characters
        public record ToolCall(String id, String name, String summary) {}

        /// How the conversation is shown: its name, or its first question.
        public String title() {
            if (!name.isBlank()) return oneLine(name);
            for (Entry entry : entries)
                if (entry.kind() == Kind.MESSAGE_TO_AGENT && !entry.text().isBlank()) return oneLine(entry.text());
            return "New conversation";
        }

        /// The job whose run had this conversation, such as `job-3`, or nothing for one someone
        /// asked. The engine names a job's conversation after the run and the job, `run-12 (job-3)`,
        /// and pi's file keeps nothing else that says so.
        public Optional<String> job() {
            Matcher named = JOB_RUN_NAME.matcher(name);
            return named.matches() ? Optional.of(named.group(1)) : Optional.empty();
        }

        /// The entry it stands at: the last one written. Empty for a conversation with none.
        public Optional<String> leaf() {
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.last().id());
        }

        public Optional<Entry> entry(String id) {
            return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
        }

        /// The line the conversation stands on: every entry from the first to the leaf.
        public Tuple<Entry> line() {
            return leaf().map(this::lineTo).orElse(Tuple.of(Entry.class));
        }

        /// Every entry from the first up to and including `id`. Empty when there is no such entry.
        public Tuple<Entry> lineTo(String id) {
            Map<String, Entry> byId = new HashMap<>();
            for (Entry entry : entries) byId.put(entry.id(), entry);
            List<Entry> line = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Entry at = byId.get(id); at != null && seen.add(at.id()); at = at.parent().map(byId::get).orElse(null))
                line.add(at);
            return Tuple.of(Entry.class, line.reversed());
        }

        /// The entries that follow `id` directly: one, or several where the conversation forks.
        public Tuple<Entry> children(String id) {
            return entries.retainIf(entry -> entry.parent().filter(id::equals).isPresent());
        }

        /// The first eighteen characters of [#id()], which is how oillamp shows a conversation.
        /// pi's ids begin with the time, so a shorter beginning is not always unique.
        public String shortId() { return id.substring(0, Math.min(18, id.length())); }

        private static String oneLine(String text) {
            String line = text.strip().replaceAll("\\s+", " ");
            return line.length() <= 60 ? line : line.substring(0, 59) + "…";
        }
    }

    /// Every conversation the agent had in the lamp at `directory`, the most recent first. Works
    /// whether or not the lamp is running, and reads the files directly, so it is quick enough
    /// to call whenever something may have changed.
    ///
    /// The agent writes these files, so they are read as the agent's work: no link is followed,
    /// not even a directory on the way, and a file that is not a conversation is left out.
    public static List<Conversation> conversations(Path directory) {
        Optional<Path> sessions = agentHome(directory).flatMap(home -> directoryWithoutLinks(home, SESSIONS));
        if (sessions.isEmpty()) return List.of();
        List<Conversation> found = new ArrayList<>();
        for (Path folder : listed(sessions.get())) {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
            for (Path file : listed(folder)) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    continue;
                try {
                    if (Files.size(file) > LARGEST_CONVERSATION) continue;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    PiSessionFile.parse(SESSIONS + "/" + folder.getFileName() + "/" + name, lines).ifPresent(found::add);
                } catch (IOException | UncheckedIOException unreadable) {
                    // Being written, or gone since it was listed: left out until the next look.
                }
            }
        }
        found.sort(Comparator.comparing(Conversation::modified).reversed());
        return List.copyOf(found);
    }

    /// The conversation named `id`, or by a unique beginning of it of at least four characters.
    public static Optional<Conversation> conversation(Path directory, String id) {
        String wanted = id.strip();
        if (wanted.length() < 4) return Optional.empty();
        List<Conversation> matching = conversations(directory).stream().filter(c -> c.id().startsWith(wanted)).toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }

    /// Deletes the conversation named `id` from the lamp at `directory`, for good.
    ///
    /// Deleting the conversation the agent is working in makes that run fail; the next one starts
    /// afresh.
    ///
    /// @throws IOException when there is no such conversation, or it could not be deleted
    public static void forget(Path directory, String id) throws IOException {
        Conversation doomed = conversation(directory, id)
                                .orElseThrow(() -> new IOException("the lamp has no conversation called '" + id + "'"));
        Path home = agentHome(directory).orElseThrow(() -> new IOException("the lamp has no agent home"));
        Path file = home.resolve(doomed.file());
        // Found by listing without following links, so it is a plain file where it should be.
        Files.delete(file);
    }

    private static Optional<Path> directoryWithoutLinks(Path from, String relative) {
        Path at = from;
        for (Path part : Path.of(relative)) {
            at = at.resolve(part);
            if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        }
        return Optional.of(at);
    }

    private static List<Path> listed(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /// The agent's home in the lamp at `directory`: the directory the sandbox sees as
    /// `/home/agent`. Nothing, before the lamp was first started.
    ///
    /// For an application that reads what the agent keeps there, such as a harness's saved
    /// conversations, while the sandbox runs or not. The agent writes it, so read it as the
    /// agent's work: never follow a link in it, and never trust a name in it to be a plain name.
    public static Optional<Path> agentHome(Path directory) {
        try (var entries = Files.list(directory)) {
            List<Path> homes = entries.filter(entry -> entry.getFileName().toString().startsWith(AGENT_DIR_PREFIX))
                                .filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                                .toList();
            return homes.size() == 1 ? Optional.of(homes.getFirst()) : Optional.empty();
        } catch (IOException notThere) {
            return Optional.empty();
        }
    }

    /// The models the agent of a lamp given `service` and `key` with [LampStartupConf#modelService(URI)] and
    /// [LampStartupConf#modelKey(String)] is offered, in the service's order. Asked on this machine, without the
    /// engine, so an application can offer them in its settings before any lamp runs.
    ///
    /// The service is asked where the engine's relay asks it: under its own path, such as
    /// `http://127.0.0.1:11434/v1/models` for Ollama, or under `/v3` without one, as for Eden AI.
    /// The key goes with the request as `Authorization: Bearer`, and nowhere else: no proxy, no
    /// redirect. For Eden AI only the models served in the EU are listed, as in the sandbox.
    ///
    /// @throws IllegalArgumentException when the engine would refuse `service`, such as plain
    ///                                  `http` anywhere but on this machine
    /// @throws IOException              when the service cannot be asked, or refuses; the message
    ///                                  says why, in words an application can show
    public static List<String> models(URI service, String key) throws IOException, InterruptedException {
        return ModelList.of(service, Optional.of(key));
    }

    /// The models a service offers without a key, such as a model server on this machine or
    /// Eden AI, whose list is public. See [#models(URI, String)].
    public static List<String> models(URI service) throws IOException, InterruptedException {
        return ModelList.of(service, Optional.empty());
    }

    /// The lamp directory.
    public Path directory() { return directory; }

    /// Takes a snapshot of this lamp while its sandbox runs, as [LampStartupConf#save] does. Programs in
    /// the sandbox may be writing at that moment, so the snapshot is marked as a running save.
    ///
    /// Blocks until it is done. What the engine reports goes to this lamp's listeners.
    ///
    /// @return the snapshot, or empty when nothing changed since the last one
    /// @throws LampFailedException      when the lamp could not be saved; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public Optional<LampSave> save(String message) throws IOException, InterruptedException, LampFailedException {
        return save(launcher, listeners, directory, message);
    }

    /// This lamp's schedule, as [LampStartupConf#schedule] reads it.
    public LampEvent.Schedule schedule() throws IOException, InterruptedException, LampFailedException {
        return schedule(launcher, listeners, directory);
    }

    /// Adds a repeating job, as [LampStartupConf#repeat] does. The running session sees it at once.
    public LampJob repeat(String cron, String prompt) throws IOException, InterruptedException, LampFailedException {
        return addJob(launcher, listeners, directory, "--cron", cron, prompt);
    }

    /// Adds a job that runs once, as [LampStartupConf#once] does. The running session sees it at once.
    public LampJob once(String at, String prompt) throws IOException, InterruptedException, LampFailedException {
        return addJob(launcher, listeners, directory, "--at", at, prompt);
    }

    /// Every conversation the agent had in this lamp, the most recent first.
    public List<Conversation> conversations() { return conversations(directory); }

    /// The conversation named by `id` or a unique beginning of it.
    public Optional<Conversation> conversation(String id) { return conversation(directory, id); }

    /// Deletes a conversation for good. See [Lamp#forget(Path, String)].
    public void forget(String conversation) throws IOException { forget(directory, conversation); }

    /// Takes a job off the schedule, as [LampStartupConf#unschedule] does.
    public void unschedule(String job) throws IOException, InterruptedException, LampFailedException {
        runToEnd(launcher, listeners, "schedule", directory.toString(), "remove", job).orThrow();
    }

    /// Allows or refuses a host request the session asked the person about, with a
    /// [LampEvent.HostAccessAsked]. Allowing lets that one request go on; it allows no other.
    /// A request nobody decides about is refused after a minute.
    ///
    /// Runs `oillamp answer <dir> yes|no -- <request>` as a short-lived engine process.
    ///
    /// @param request the event's [what][LampEvent.HostAccessAsked#what()], as it was given
    /// @throws LampFailedException when the session waits on no such request, or is not running
    public void decidePermission(String request, boolean allowed) throws IOException, InterruptedException, LampFailedException {
        runToEnd(launcher, listeners, "answer", directory.toString(), allowed ? "yes" : "no", "--", request).orThrow();
    }

    /// Wakes the agent with `prompt`, and waits until it has answered.
    ///
    /// The session holds one agent, which works on one thing at a time. When it is busy, with a
    /// job or with another question, this waits its turn. The lamp is saved just before and just
    /// after, so what the agent changed is one snapshot of its own, named in the result.
    ///
    /// Blocks until the agent is done, which can take minutes. What the engine reports goes to
    /// this lamp's listeners.
    ///
    /// @return how it ended, and what the agent said at the end
    /// @throws LampFailedException      when the session is not running or is ending; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public LampEvent.RunFinished ask(String prompt) throws IOException, InterruptedException, LampFailedException {
        return ask(Question.fresh(prompt));
    }

    /// Asks the agent `question`, where it says: in a new conversation, or in one of the agent's
    /// conversations, where it stands, after one of its entries, or instead of one of its
    /// questions. Otherwise as [#ask(String)].
    ///
    /// @throws LampFailedException when the conversation or the entry is not there, or the session is not
    ///                running; the problem says which
    public LampEvent.RunFinished ask(Question question) throws IOException, InterruptedException, LampFailedException {
        return ask(launcher, listeners, directory, question);
    }

    /// Hands the agent `question`, and returns at once with the run that will answer it.
    ///
    /// For an application that shows the answer as it is written: the run's events, from
    /// [LampEvent.RunStarted] through each [LampEvent.RunProgress] to [LampEvent.RunFinished],
    /// arrive at this lamp's listeners, with the returned run's id. When the agent is busy, the
    /// run waits its turn, and [LampEvent.RunQueued] says so.
    ///
    /// @throws LampFailedException when the conversation or the entry is not there, or the session is ending
    public LampRun send(Question question) throws IOException, InterruptedException, LampFailedException {
        var command = InternalLampUtil.askCommand(directory, question, "--no-wait");
        Ran ran = runToEnd(launcher, listeners, command);
        for ( LampEvent event : ran.events() )
            if (event instanceof LampEvent.RunAccepted(LampRun run))
                return run;
        ran.orThrow();
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no run"));
    }

    /// Has the agent answer with `id`, a model as the service names it, from its next run on, as
    /// [LampStartupConf#model(String)] would have had it from the start: in new conversations and in old ones,
    /// for questions and scheduled jobs alike, and as pi's default. A run in progress finishes on
    /// the model it started with. The session's service and key stay as they are; changing those
    /// needs a new session.
    ///
    /// @throws IllegalArgumentException when `id` is empty or has spaces, which no service's
    ///                                  model has
    /// @throws LampFailedException when the session is not running
    public void useModel(String id) throws IOException, InterruptedException, LampFailedException {
        if (!id.matches(MODEL_ID))
            throw new IllegalArgumentException("not a model as a service names it: \"" + id + "\"");
        runToEnd(launcher, listeners, "model", directory.toString(), id).orThrow();
    }

    /// Stops the run the agent is working on. What it did until then is saved, as for any run,
    /// and its [LampEvent.RunFinished] says it was [cancelled][LampRunOutcome#CANCELLED].
    ///
    /// @throws LampFailedException when the agent is not working on anything
    public void cancel() throws IOException, InterruptedException, LampFailedException {
        runToEnd(launcher, listeners, "cancel", directory.toString()).orThrow();
    }

    /// Stops `run`: the one in progress, or one still waiting, which then never starts.
    ///
    /// @throws LampFailedException when no such run is in progress or waiting
    public void cancel(String run) throws IOException, InterruptedException, LampFailedException {
        runToEnd(launcher, listeners, "cancel", directory.toString(), run).orThrow();
    }

    /// What the agent is doing: the run in progress, if any, and the runs waiting.
    ///
    /// @throws LampFailedException when the session is not running
    public LampEvent.AgentStatus agentStatus() throws IOException, InterruptedException, LampFailedException {
        Ran ran = runToEnd(launcher, listeners, "status", directory.toString());
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.AgentStatus status) return status;
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no status of the agent"));
    }

    /// Waits until the session is running, the engine has ended, or `limit` has passed.
    ///
    /// @return true once the session is running. False if it ended without getting there, in
    ///         which case the events said why, or if it is still starting after `limit`
    public boolean awaitRunning(Duration limit) throws InterruptedException {
        runningOrEnded.await(limit.toMillis(), TimeUnit.MILLISECONDS);
        return opened.isPresent() && exit.isEmpty();
    }

    /// Runs a command in the sandbox, as the agent user, with the agent's shell environment and
    /// no terminal. The returned process's standard input, output and error are the command's.
    ///
    /// For example `lamp.exec("opencode", "acp")` starts a harness whose standard input and output
    /// the application then speaks a protocol over.
    ///
    /// @throws IllegalStateException when the session is not running
    /// @throws IOException           when ssh could not be started at all
    public Process exec(String... command) throws IOException {
        return new ProcessBuilder(commandLine(command)).start();
    }

    /// The command line [#exec] runs: the engine's ssh command, followed by `command`, each
    /// argument quoted so the sandbox's shell receives it exactly as given.
    ///
    /// For an application that wants to start the process itself, for example with its own
    /// working directory or redirections.
    ///
    /// @throws IllegalStateException when the session is not running
    public List<String> commandLine(String... command) {
        LampEvent.SessionOpened session = opened.filter(ignored -> exit.isEmpty())
                .orElseThrow(() -> new IllegalStateException(
                        "the lamp at " + directory + " is not running"));
        List<String> line = new ArrayList<>();
        for (String part : session.command()) line.add(part);
        for (String argument : command) line.add(quoted(argument));
        return List.copyOf(line);
    }

    /// ssh joins its arguments with spaces and gives them to a shell in the sandbox. Quoting each
    /// one keeps spaces, quotes and `$` in an argument from being read by that shell.
    private static String quoted(String argument) {
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    /// The Unix socket of the sandbox's desktop, for an application that shows it in a window of
    /// its own. It speaks VNC (RFB 3.8) with no password: only this user can open the socket.
    ///
    /// @throws IllegalStateException when the session is not running
    public Path desktop() {
        return opened.filter(ignored -> exit.isEmpty()).map(LampEvent.SessionOpened::desktop)
                .orElseThrow(() -> new IllegalStateException("the lamp at " + directory + " is not running"));
    }

    /// What the session said when it opened: how to reach its sandbox, its desktop, and the
    /// desktop's own size.
    ///
    /// @throws IllegalStateException when the session is not running
    public LampEvent.SessionOpened session() {
        return opened.filter(ignored -> exit.isEmpty())
                .orElseThrow(() -> new IllegalStateException("the lamp at " + directory + " is not running"));
    }

    /// How the engine ended, once it has. For a joined lamp, how following ended:
    /// [ExitStatus#SUCCESS] when the session ended, or when this lamp was closed.
    public Optional<ExitStatus> exitStatus() { return exit; }

    /// Ends the session and waits until the engine has shut the sandbox down.
    ///
    /// Closing the engine's standard input is the signal. The engine then stops the container,
    /// which takes up to `timeouts.stop_seconds`, and exits.
    ///
    /// After [#leaveRunning()], this ends nothing and returns at once.
    ///
    /// Interrupting the waiting thread stops the wait, not the shutdown.
    @Override public void close() {
        try {
            engine.getOutputStream().close();
        } catch (IOException alreadyGone) {
            // The pipe could not be closed because the engine is no longer reading it.
        }
        if (!holding) return;
        try {
            ended.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Lets the session go on after this application is gone, until [#stop()] or
    /// `oillamp stop <dir>` ends it. [LampStartupConf#join] finds it again.
    ///
    /// For an application that closes while the agent should keep working: on its schedule, say.
    /// The engine is told on its standard input, which is closed after. Its events keep coming
    /// to the listeners until this application exits.
    ///
    /// Without this, the session ends when the application does, however it ends, so a crash
    /// never leaves a sandbox running. On a lamp this application joined, it is the same as
    /// [#close()].
    public void leaveRunning() {
        holding = false;
        try (var input = engine.getOutputStream()) {
            input.write((LEAVE_RUNNING + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException alreadyGone) {
            // The engine has ended, so there is nothing left to leave running.
        }
    }

    /// Ends the session, whoever started it, and waits until it has ended: the sandbox is
    /// shut down and the lamp saved.
    ///
    /// @throws LampFailedException when no session is running
    public void stop() throws IOException, InterruptedException, LampFailedException {
        runToEnd(launcher, listeners, "stop", directory.toString()).orThrow();
        ended.await();
    }

    /// Whether the session ends when this lamp is closed: true for a lamp this application
    /// started, until it was left running.
    public boolean holds() { return holding; }

    void readEvents() {
        try (
            var output = new BufferedReader(new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = output.readLine()) != null) {
                try {
                    LampEvent.fromJson(line).ifPresent(this::deliver);
                } catch (RuntimeException unexpected) {
                    InternalLampUtil.reportToThreadExceptionHandler(unexpected);
                }
            }
        } catch (IOException ended) {
            // The engine's output closed; it has exited, or is about to.
        } catch (Error broken) {
            // Reading broke off. No one may wait for the session on an event that will not come,
            // and the engine may go on until it is closed.
            runningOrEnded.countDown();
            throw broken;
        } finally {
            int code;
            try {
                code = engine.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            exit = Optional.of(ExitStatus.ofCode(code).orElse(ExitStatus.ERROR));
            runningOrEnded.countDown();
            ended.countDown();
        }
    }

    private void deliver(LampEvent event) {
        // The engine reports this once the session is running, with how to reach the sandbox.
        if (event instanceof LampEvent.SessionOpened session) {
            opened = Optional.of(session);
            runningOrEnded.countDown();
        }
        InternalLampUtil.passToListeners(listeners, event);
    }

    static LampEvent.RunFinished ask(
            final Launcher launcher,
            final List<Consumer<LampEvent>> listeners,
            final Path directory,
            final Question question
    ) throws IOException, InterruptedException, LampFailedException
    {
        Ran ran = runToEnd(launcher, listeners, InternalLampUtil.askCommand(directory, question));
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.RunFinished finished) return finished;
        ran.orThrow();
        throw new LampFailedException(ran.status(), InternalLampUtil.internal("the engine reported no answer"));
    }

    /// Starts the engine with this process's own Java runtime and classpath, so that the
    /// application and the engine are always the same version of oillamp. The engine's error
    /// output goes where the application's does.
    ///
    /// The engine runs in a session of its own (`setsid`), so a session left running is not hung
    /// up when the terminal the application was started from closes. One still held ends anyway,
    /// with the application.
    private static Process sameJava(List<String> arguments, Map<String, String> environment) throws IOException {
        List<String> command = new ArrayList<>(
                List.of(
                        "setsid",
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ENGINE
                )
        );
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environment);
        return builder.start();
    }
}
