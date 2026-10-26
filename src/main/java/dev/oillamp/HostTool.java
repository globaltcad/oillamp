package dev.oillamp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import dev.lamp.LampHostRisk;
import dev.lamp.LampHostVerdict;

import com.fasterxml.jackson.databind.JsonNode;
import sprouts.Association;
import sprouts.Tuple;

/// The requests the agent can make of the host, one record for each tool, with its arguments
/// read.
///
/// Every request has a fixed shape, read from the call's JSON arguments by [#read] before
/// anything else looks at it. The one tool that takes a command string, `host_bash`, is the
/// executing tier, which the lamp keeps closed unless its `host_access.commands` opens it.
///
/// Everything here decides and nothing does. [HostAccess] carries the decisions out, after the
/// checks in [HostPaths] and the checker's [Verdict][dev.lamp.LampHostVerdict]. What each tool is
/// called and described as is [HostToolKind].
///
/// Adding a tool means adding a case to every `switch` over it, which the compiler insists on.
sealed interface HostTool {

    // ─── the tools ────────────────────────────────────────────────────────────────────────

    record ListDir(String path) implements HostTool {}
    record Stat(String path) implements HostTool {}
    record ReadFile(String path, long offset, int maxBytes) implements HostTool {}
    record ClipboardRead() implements HostTool {}
    record OpenUrl(URI url) implements HostTool {}
    record Show(String path) implements HostTool {}
    record WriteFile(String path, String text) implements HostTool {}
    /// One exact piece of a text file replaced by another.
    ///
    /// @param oldText what is replaced; it must occur in the file exactly once
    record EditFile(String path, String oldText, String newText) implements HostTool {}
    record ClipboardWrite(String text) implements HostTool {}
    record Notify(String title, String text) implements HostTool {}
    /// A file copied out of the sandbox's home onto the host.
    ///
    /// @param from where the file is in the sandbox: under `/home/agent`, or a path starting `~/`
    /// @param path where it goes on the host
    record CopyOut(String from, String path) implements HostTool {}
    /// A file copied from the host into the sandbox's home.
    ///
    /// @param path where the file is on the host
    /// @param to   where it goes in the sandbox: under `/home/agent`, or a path starting `~/`
    record CopyIn(String path, String to) implements HostTool {}
    /// A bash command, run on the host.
    ///
    /// @param command what bash runs, as one argument to `bash -c`, never pasted into another
    ///                command line
    /// @param cwd     the host directory it runs in, as the agent wrote it
    /// @param timeout how long it may take before it is stopped
    record Bash(String command, String cwd, Duration timeout) implements HostTool {}

    /// How long a command may take when the agent does not say.
    Duration USUAL_COMMAND = Duration.ofMinutes(2);
    /// The longest a command may be given. A tool call that runs for longer than this belongs in
    /// a program the agent starts some other way.
    Duration LONGEST_COMMAND = Duration.ofMinutes(10);
    /// The longest command `host_bash` takes, in bytes. The checker is shown the whole command,
    /// so a longer one is refused instead of cut: a command whose end the checker never saw is not
    /// a command it judged.
    int LONGEST_COMMAND_TEXT = 16 * 1024;

    /// The tool a request is a call of.
    static HostToolKind kindOf(HostTool tool) {
        return switch (tool) {
            case ListDir _ -> HostToolKind.LIST_DIR;
            case Stat _ -> HostToolKind.STAT;
            case ReadFile _ -> HostToolKind.READ_FILE;
            case ClipboardRead _ -> HostToolKind.CLIPBOARD_READ;
            case OpenUrl _ -> HostToolKind.OPEN_URL;
            case Show _ -> HostToolKind.SHOW;
            case WriteFile _ -> HostToolKind.WRITE_FILE;
            case EditFile _ -> HostToolKind.EDIT_FILE;
            case ClipboardWrite _ -> HostToolKind.CLIPBOARD_WRITE;
            case Notify _ -> HostToolKind.NOTIFY;
            case CopyOut _ -> HostToolKind.COPY_OUT;
            case CopyIn _ -> HostToolKind.COPY_IN;
            case Bash _ -> HostToolKind.BASH;
        };
    }

    /// The tier a request belongs to, which is its tool's.
    static LampHostRisk tierOf(HostTool tool) { return kindOf(tool).tier; }

    /// The tool's name, such as `host_read_file`.
    static String nameOf(HostTool tool) { return kindOf(tool).toolName; }

    /// One line for the user and the log. It never includes a file's contents or a clipboard
    /// text: those can be large, and they are not what the user is being asked to judge. A
    /// command is included whole, because it is exactly what the person is asked to judge.
    static String describe(HostTool tool) {
        return switch (tool) {
            case ListDir dir -> "list " + dir.path();
            case Stat stat -> "stat " + stat.path();
            case ReadFile read -> "read " + read.path()
                    + (read.offset() > 0 ? " from byte " + read.offset() : "")
                    + (read.maxBytes() > 0 ? ", at most " + read.maxBytes() + " bytes" : "");
            case ClipboardRead _ -> "read the clipboard";
            case OpenUrl url -> "open " + url.url();
            case Show show -> "show " + show.path();
            case WriteFile write -> "write " + write.text().getBytes(StandardCharsets.UTF_8).length
                    + " bytes to " + write.path();
            case EditFile edit -> "edit " + edit.path() + ": replace " + edit.oldText().length()
                    + " characters with " + edit.newText().length();
            case ClipboardWrite write -> "put " + write.text().length() + " characters on the clipboard";
            case Notify notify -> "notify: " + notify.title();
            case CopyOut copy -> "copy " + copy.from() + " from the sandbox to " + copy.path() + ", without running it";
            case CopyIn copy -> "copy " + copy.path() + " from the host into the sandbox as " + copy.to();
            case Bash bash -> "run in " + bash.cwd() + ": " + bash.command();
        };
    }

    /// The request's arguments as text, by name, as the checker and the log are shown them.
    static Association<String, String> argumentsOf(HostTool tool) {
        Association<String, String> none = Association.between(String.class, String.class);
        return switch (tool) {
            case ListDir dir -> none.put("path", dir.path());
            case Stat stat -> none.put("path", stat.path());
            case ReadFile read -> none.put("path", read.path()).put("offset", String.valueOf(read.offset()))
                                      .put("max_bytes", String.valueOf(read.maxBytes()));
            case ClipboardRead _ -> none;
            case OpenUrl url -> none.put("url", url.url().toString());
            case Show show -> none.put("path", show.path());
            case WriteFile write -> none.put("path", write.path()).put("text", write.text());
            case EditFile edit -> none.put("path", edit.path()).put("old_text", edit.oldText())
                                      .put("new_text", edit.newText());
            case ClipboardWrite write -> none.put("text", write.text());
            case Notify notify -> none.put("title", notify.title()).put("text", notify.text());
            case CopyOut copy -> none.put("from", copy.from()).put("path", copy.path());
            case CopyIn copy -> none.put("path", copy.path()).put("to", copy.to());
            case Bash bash -> none.put("command", bash.command()).put("cwd", bash.cwd())
                                  .put("timeout_seconds", String.valueOf(bash.timeout().toSeconds()));
        };
    }

    /// Why a tool cannot be offered by this lamp, or empty when it can.
    ///
    /// Kept apart from the checker on purpose: whether a whole tier is open is the user's
    /// setting, and must not depend on a model's opinion.
    static Optional<String> closedBecause(HostToolKind kind, LampConfig.HostAccess access) {
        return switch (kind) {
            // Not built yet: offering them would only teach the agent a tool that always refuses.
            case CLIPBOARD_READ, CLIPBOARD_WRITE -> Optional.of(kind.toolName + " is not built yet");
            case OPEN_URL, WRITE_FILE, EDIT_FILE, NOTIFY, COPY_OUT ->
                    access.allowAdditive() ? Optional.empty()
                    : Optional.of("this lamp's host_access.allow_additive is false, so nothing on the host may be changed");
            case BASH -> switch (access.commands()) {
                case OFF -> Optional.of("this lamp does not let the agent run commands on the host "
                                      + "(host_access.commands is \"off\")");
                case ASK, CHECKER -> Optional.empty();
            };
            case LIST_DIR, STAT, READ_FILE, COPY_IN, SHOW -> Optional.empty();
        };
    }

    /// The tools this lamp offers the agent, in the order they are listed. A closed tier is
    /// absent, so the agent does not try and does not read a refusal it could have avoided.
    static Tuple<HostToolKind> offeredBy(LampConfig.HostAccess access) {
        Tuple<HostToolKind> offered = Tuple.of(HostToolKind.class);
        for (HostToolKind kind : HostToolKind.values())
            if (closedBecause(kind, access).isEmpty()) offered = offered.add(kind);
        return offered;
    }

    /// Whether the person must allow a request too, after the checker did. A command does in a
    /// lamp whose `host_access.commands` is `ask`; a path past the roots does separately, in
    /// [HostAccess].
    static boolean needsThePerson(HostTool tool, LampConfig.HostAccess access) {
        return tool instanceof Bash && access.commands() == LampConfig.HostAccess.Commands.ASK;
    }

    // ─── reading a request ────────────────────────────────────────────────────────────────

    /// Reads one call of `kind` from its JSON arguments, an object such as
    /// `{"path": "~/Documents/q3.md", "offset": 10}`. Anything that is not exactly the tool's
    /// shape is refused here, before a path is touched or a model is asked: an argument it does
    /// not take, one of the wrong type, a required one missing, or a value the tool cannot use.
    static Result<HostTool> read(HostToolKind kind, JsonNode arguments) {
        Optional<String> misfit = misfitOf(kind, arguments);
        if (misfit.isPresent()) return Result.err(HostAccessProblems.badArguments(kind.toolName, misfit.get()));
        String name = kind.toolName;
        return switch (kind) {
            case LIST_DIR -> Result.ok(new ListDir(text(arguments, "path")));
            case STAT -> Result.ok(new Stat(text(arguments, "path")));
            case READ_FILE -> {
                long offset = number(arguments, "offset").orElse(0L);
                long most = number(arguments, "max_bytes").orElse(0L);
                yield offset < 0 || most < 0 || most > Integer.MAX_VALUE
                        ? Result.err(HostAccessProblems.badArguments(name, "offset and max_bytes: numbers of bytes, 0 or more"))
                        : Result.ok(new ReadFile(text(arguments, "path"), offset, (int) most));
            }
            case CLIPBOARD_READ -> Result.ok(new ClipboardRead());
            case OPEN_URL -> optionalUri(text(arguments, "url")).filter(HostTool::openable)
                    .<Result<HostTool>>map(uri -> Result.ok(new OpenUrl(uri)))
                    .orElse(Result.err(HostAccessProblems.badArguments(name, "url: an http:// or https:// address")));
            case SHOW -> Result.ok(new Show(text(arguments, "path")));
            case WRITE_FILE -> Result.ok(new WriteFile(text(arguments, "path"), text(arguments, "text")));
            case EDIT_FILE -> Result.ok(new EditFile(text(arguments, "path"), text(arguments, "old_text"),
                                                     text(arguments, "new_text")));
            case CLIPBOARD_WRITE -> Result.ok(new ClipboardWrite(text(arguments, "text")));
            case NOTIFY -> Result.ok(new Notify(text(arguments, "title"), text(arguments, "text")));
            case COPY_OUT -> Result.ok(new CopyOut(text(arguments, "from"), text(arguments, "path")));
            case COPY_IN -> Result.ok(new CopyIn(text(arguments, "path"), text(arguments, "to")));
            case BASH -> {
                long seconds = number(arguments, "timeout_seconds").orElse(USUAL_COMMAND.toSeconds());
                yield seconds < 1 || seconds > LONGEST_COMMAND.toSeconds()
                        ? Result.err(HostAccessProblems.badArguments(name,
                                "timeout_seconds: from 1 to " + LONGEST_COMMAND.toSeconds()))
                        : Result.ok(new Bash(text(arguments, "command"), text(arguments, "cwd"), Duration.ofSeconds(seconds)));
            }
        };
    }

    /// What is wrong with a call's arguments, judged by its tool's parameters alone, or empty
    /// when every argument is one the tool takes, of the type it takes.
    private static Optional<String> misfitOf(HostToolKind kind, JsonNode arguments) {
        if (!arguments.isObject()) return Optional.of("its arguments as one JSON object");
        for (var field : arguments.properties()) {
            Optional<HostToolKind.Parameter> parameter = parameterNamed(kind, field.getKey());
            if (parameter.isEmpty())
                return Optional.of(kind.parameters.isEmpty() ? "no arguments, not " + field.getKey()
                        : "only " + String.join(", ", kind.parameters.mapTo(String.class, HostToolKind.Parameter::name))
                          + ", not " + field.getKey());
            if (!fits(parameter.get().type(), field.getValue()))
                return Optional.of(field.getKey() + ": " + wanted(parameter.get().type()));
        }
        for (HostToolKind.Parameter parameter : kind.parameters)
            if (parameter.required() && !arguments.has(parameter.name()))
                return Optional.of(parameter.name() + ": " + wanted(parameter.type()));
        return Optional.empty();
    }

    private static Optional<HostToolKind.Parameter> parameterNamed(HostToolKind kind, String name) {
        for (HostToolKind.Parameter parameter : kind.parameters)
            if (parameter.name().equals(name)) return Optional.of(parameter);
        return Optional.empty();
    }

    private static boolean fits(HostToolKind.Type type, JsonNode value) {
        return switch (type) {
            case TEXT -> value.isTextual() && !value.asText().isBlank();
            case TEXT_OR_EMPTY -> value.isTextual();
            case WHOLE_NUMBER -> value.isIntegralNumber() && value.canConvertToLong();
        };
    }

    private static String wanted(HostToolKind.Type type) {
        return switch (type) {
            case TEXT -> "a string that is not empty";
            case TEXT_OR_EMPTY -> "a string";
            case WHOLE_NUMBER -> "a whole number";
        };
    }

    /// A string argument [#misfitOf] has already checked, or empty text for one left out.
    private static String text(JsonNode arguments, String name) {
        return arguments.path(name).asText("");
    }

    /// A number argument [#misfitOf] has already checked.
    private static Optional<Long> number(JsonNode arguments, String name) {
        return arguments.has(name) ? Optional.of(arguments.get(name).asLong()) : Optional.empty();
    }

    /// The host paths a tool will touch, as the agent wrote them, before they are resolved. The
    /// clipboard, a URL and a notification touch none; `host_copy_out` names its destination
    /// here, and its source is a path in the sandbox, which [HostAccessTools] reads in the
    /// agent's home; `host_copy_in` names its source, and writes in the agent's home. `host_bash` names the directory it starts in, and only that: what the
    /// command touches from there, the roots do not bound.
    static Tuple<String> pathsOf(HostTool tool) {
        return switch (tool) {
            case ListDir dir -> Tuple.of(String.class, dir.path());
            case Stat stat -> Tuple.of(String.class, stat.path());
            case ReadFile read -> Tuple.of(String.class, read.path());
            case Show show -> Tuple.of(String.class, show.path());
            case WriteFile write -> Tuple.of(String.class, write.path());
            case EditFile edit -> Tuple.of(String.class, edit.path());
            case CopyOut copy -> Tuple.of(String.class, copy.path());
            case CopyIn copy -> Tuple.of(String.class, copy.path());
            case Bash bash -> Tuple.of(String.class, bash.cwd());
            case ClipboardRead _, OpenUrl _, ClipboardWrite _, Notify _ -> Tuple.of(String.class);
        };
    }

    // ─── the checker's answer ────────────────────────────────────────────────────────────

    /// Reads the checker's reply. Anything that is not exactly a verdict is a refusal: an answer
    /// oillamp cannot read is not permission, and a model that rambles is not asked twice.
    static Result<LampHostVerdict> verdict(String answered) {
        // Models like to wrap JSON in a code fence now and then, and that is the one decoration
        // worth forgiving: the answer is still one object, and refusing it would deny everything.
        String text = answered.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) text = text.substring(firstLine + 1, lastFence).strip();
        }
        Optional<JsonNode> parsed = JsonUtil.parse(text);
        if (parsed.isEmpty() || !parsed.get().isObject()) return Result.err(HostAccessProblems.unreadableVerdict(answered));
        JsonNode node = parsed.get();
        String decision = node.path("decision").asText("");
        if (!decision.equals("allow") && !decision.equals("deny"))
            return Result.err(HostAccessProblems.unreadableVerdict(answered));
        String risk = node.path("risk").asText("").toUpperCase(Locale.ROOT);
        Optional<LampHostRisk> tier = switch (risk) {
            case "PASSIVE" -> Optional.of(LampHostRisk.PASSIVE);
            case "ADDITIVE" -> Optional.of(LampHostRisk.ADDITIVE);
            case "EXECUTING" -> Optional.of(LampHostRisk.EXECUTING);
            default -> Optional.empty();
        };
        if (tier.isEmpty()) return Result.err(HostAccessProblems.unreadableVerdict(answered));
        // A verdict without a reason is not usable: the agent is told the reason on a refusal,
        // and the user reads it in the terminal and the log.
        String reason = node.path("reason").isTextual() ? node.path("reason").asText().strip() : "";
        if (reason.isEmpty()) return Result.err(HostAccessProblems.unreadableVerdict(answered));
        return Result.ok(new LampHostVerdict(decision.equals("allow"), tier.get(), reason));
    }

    /// A URL the agent may ask to open: a web page, over http or https, and nothing else. A
    /// `file:` or `javascript:` URL would be a way around every path check above it.
    private static boolean openable(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) return false;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("http") || scheme.equals("https")) && uri.getUserInfo() == null;
    }

    private static Optional<URI> optionalUri(String text) {
        try {
            return Optional.of(URI.create(text.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
