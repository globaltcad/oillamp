package dev.oillamp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.lamp.LampHostRisk;
import dev.lamp.LampHostVerdict;

import com.fasterxml.jackson.databind.JsonNode;
import sprouts.Association;
import sprouts.Tuple;

/// The tools the agent can call to reach the host, and what each one is allowed to be.
///
/// A tool is a record with a fixed shape of arguments. There is no shell string anywhere: the
/// agent names a program and its arguments separately, or a path, and the host decides what to do
/// with them.
///
/// Everything here decides and nothing does. [HostAccess] carries the decisions out, after the
/// checks in [HostPaths] and the checker's [Verdict][dev.lamp.LampHostVerdict].
///
/// Adding a tool means adding a case to every `switch` over it, which the compiler insists on.
sealed interface HostTool {

    /// What the agent asked for, in the tool's own arguments.
    record Call(HostTool tool, Association<String, String> arguments) {}

    // ─── the tools ────────────────────────────────────────────────────────────────────────

    record ListDir(String path) implements HostTool {}
    record Stat(String path) implements HostTool {}
    record ReadFile(String path, long offset, int maxBytes) implements HostTool {}
    record ClipboardRead() implements HostTool {}
    record OpenUrl(URI url) implements HostTool {}
    record Show(String path) implements HostTool {}
    record WriteFile(String path, String text) implements HostTool {}
    record ClipboardWrite(String text) implements HostTool {}
    record Notify(String title, String text) implements HostTool {}
    /// A file copied out of the sandbox's home onto the host.
    ///
    /// @param from where the file is in the sandbox: under `/home/agent`, or a path starting `~/`
    /// @param path where it goes on the host
    record CopyOut(String from, String path) implements HostTool {}
    record Run(String executable, Tuple<String> argv) implements HostTool {}

    /// The tier a tool belongs to. It is the ceiling: the checker may put a request lower, never
    /// higher, and [HostAccess] refuses a request in a tier that is closed.
    static LampHostRisk tierOf(HostTool tool) {
        return switch (tool) {
            case ListDir _, Stat _, ReadFile _, ClipboardRead _, Show _ -> LampHostRisk.PASSIVE;
            // Opening a page sends whatever the address holds to whoever serves it, so it is a way
            // out for anything the agent has read, not a read.
            case OpenUrl _, WriteFile _, ClipboardWrite _, Notify _, CopyOut _ -> LampHostRisk.ADDITIVE;
            case Run _ -> LampHostRisk.EXECUTING;
        };
    }

    /// The tool's name in the sandbox, which is also what `pi` shows the model.
    static String nameOf(HostTool tool) {
        return switch (tool) {
            case ListDir _ -> "host_list_dir";
            case Stat _ -> "host_stat";
            case ReadFile _ -> "host_read_file";
            case ClipboardRead _ -> "host_clipboard_read";
            case OpenUrl _ -> "host_open_url";
            case Show _ -> "host_show";
            case WriteFile _ -> "host_write_file";
            case ClipboardWrite _ -> "host_clipboard_write";
            case Notify _ -> "host_notify";
            case CopyOut _ -> "host_copy_out";
            case Run _ -> "host_run";
        };
    }

    /// One line for the user and the log. It never includes a file's contents, a clipboard text,
    /// or a program's standard input: those can be large, and they are not what the user is
    /// being asked to judge.
    static String describe(HostTool tool) {
        return switch (tool) {
            case ListDir dir -> "list " + dir.path();
            case Stat stat -> "stat " + stat.path();
            case ReadFile read -> "read " + read.path()
                    + (read.offset() > 0 ? " from byte " + read.offset() : "")
                    + (read.maxBytes() > 0 ? ", at most " + read.maxBytes() + " bytes" : "");
            case ClipboardRead _ -> "read the clipboard";
            case OpenUrl url -> "open " + url;
            case Show show -> "show " + show.path();
            case WriteFile write -> "write " + write.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + " bytes to " + write.path();
            case ClipboardWrite write -> "put " + write.text().length() + " characters on the clipboard";
            case Notify notify -> "notify: " + notify.title();
            case CopyOut copy -> "copy " + copy.from() + " from the sandbox to " + copy.path() + ", without running it";
            case Run run -> "run " + run.executable()
                    + (run.argv().isEmpty() ? "" : " " + String.join(" ", run.argv()));
        };
    }

    /// Why a tool cannot be offered by this lamp, or empty when it can.
    ///
    /// Kept apart from the checker on purpose: whether a whole tier is open, and whether a program
    /// is named at all, are the user's settings, and must not depend on a model's opinion.
    static Optional<String> closedBecause(HostTool tool, LampConfig.HostAccess access) {
        return switch (tool) {
            case OpenUrl _, WriteFile _, ClipboardWrite _, Notify _, CopyOut _ ->
                    access.allowAdditive() ? Optional.empty()
                    : Optional.of("this lamp's host_access.allow_additive is false, so nothing on the host may be changed");
            case Run run ->
                    access.executables().contains(run.executable()) ? Optional.empty()
                    : Optional.of("this lamp does not let the agent run programs on the host"
                          + (access.executables().isEmpty() ? " (host_access.executables is empty)" : " by that name"));
            case ListDir _, Stat _, ReadFile _, ClipboardRead _, Show _ -> Optional.empty();
        };
    }

    /// The tools this lamp offers the agent, in the order the extension lists them. A closed tier
    /// is absent, so the agent does not try and does not read a refusal it could have avoided.
    static Tuple<String> offeredBy(LampConfig.HostAccess access) {
        Tuple<String> names = Tuple.of(String.class);
        for (String name : ALL)
            if (offered(name, access)) names = names.add(name);
        return names;
    }

    /// The names of every tool there is, whether or not this lamp offers it.
    Tuple<String> ALL = Tuple.of(String.class,
            "host_list_dir", "host_stat", "host_read_file", "host_clipboard_read", "host_open_url", "host_show",
            "host_write_file", "host_clipboard_write", "host_notify", "host_copy_out", "host_run");

    private static boolean offered(String name, LampConfig.HostAccess access) {
        return switch (name) {
            // Not built yet: offering them would only teach the agent a tool that always refuses.
            case "host_clipboard_read", "host_clipboard_write" -> false;
            case "host_open_url", "host_write_file", "host_notify", "host_copy_out" -> access.allowAdditive();
            case "host_run" -> !access.executables().isEmpty();
            default -> true;
        };
    }

    // ─── reading a request ────────────────────────────────────────────────────────────────

    /// Reads one request from the sandbox. The line's shape is fixed, and anything that is not
    /// exactly it is refused here, before a path is touched or a model is asked.
    static Result<Call> read(String line) {
        Optional<JsonNode> parsed = JsonUtil.parse(line);
        if (parsed.isEmpty()) return Result.err(HostAccessProblems.notARequest());
        JsonNode node = parsed.get();
        if (!node.isObject()) return Result.err(HostAccessProblems.notARequest());
        String tool = node.path("tool").asText("");
        JsonNode args = node.path("args");
        if (tool.isEmpty() || !args.isObject()) return Result.err(HostAccessProblems.notARequest());
        Association<String, String> arguments = Association.between(String.class, String.class);
        for (var field : args.properties()) {
            JsonNode value = field.getValue();
            if (!value.isTextual()) return Result.err(HostAccessProblems.notARequest());
            arguments = arguments.put(field.getKey(), value.asText());
        }
        return of(tool, arguments);
    }

    /// Builds one tool from its name and arguments. Every argument that is missing, unknown or
    /// of the wrong shape is a refusal with the tool's name in it, not a guess.
    static Result<Call> of(String name, Association<String, String> args) {
        Result<Call> call = switch (name) {
            case "host_list_dir" -> {
                String path = args.get("path").orElse("");
                yield path.isEmpty() ? Result.err(HostAccessProblems.badArguments(name, "path"))
                                     : Result.ok(new Call(new ListDir(path), args));
            }
            case "host_stat" -> {
                String path = args.get("path").orElse("");
                yield path.isEmpty() ? Result.err(HostAccessProblems.badArguments(name, "path"))
                                     : Result.ok(new Call(new Stat(path), args));
            }
            case "host_read_file" -> {
                String path = args.get("path").orElse("");
                if (path.isEmpty()) yield Result.err(HostAccessProblems.badArguments(name, "path"));
                long offset = args.get("offset").flatMap(HostTool::number).orElse(0L);
                int max = args.get("max_bytes").flatMap(HostTool::number).map(Long::intValue).orElse(0);
                yield Result.ok(new Call(new ReadFile(path, Math.max(0, offset), max), args));
            }
            case "host_clipboard_read" -> Result.ok(new Call(new ClipboardRead(), args));
            case "host_open_url" -> {
                String url = args.get("url").orElse("");
                Optional<URI> parsed = url.isEmpty() ? Optional.empty() : optionalUri(url);
                yield parsed.filter(HostTool::openable).map(uri -> Result.ok(new Call(new OpenUrl(uri), args)))
                        .orElse(Result.err(HostAccessProblems.badArguments(name, "url")));
            }
            case "host_show" -> {
                String path = args.get("path").orElse("");
                yield path.isEmpty() ? Result.err(HostAccessProblems.badArguments(name, "path"))
                                     : Result.ok(new Call(new Show(path), args));
            }
            case "host_write_file" -> {
                Optional<String> path = args.get("path").filter(text -> !text.isEmpty());
                Optional<String> text = args.get("text");
                yield path.isEmpty() || text.isEmpty()
                        ? Result.err(HostAccessProblems.badArguments(name, "path and text"))
                        : Result.ok(new Call(new WriteFile(path.get(), text.get()), args));
            }
            case "host_clipboard_write" -> {
                Optional<String> text = args.get("text");
                yield text.isEmpty() ? Result.err(HostAccessProblems.badArguments(name, "text"))
                                     : Result.ok(new Call(new ClipboardWrite(text.get()), args));
            }
            case "host_notify" -> {
                Optional<String> title = args.get("title").filter(text -> !text.isBlank());
                Optional<String> text = args.get("text");
                yield title.isEmpty() || text.isEmpty()
                        ? Result.err(HostAccessProblems.badArguments(name, "title and text"))
                        : Result.ok(new Call(new Notify(title.get(), text.get()), args));
            }
            case "host_copy_out" -> {
                String from = args.get("from").orElse("");
                String path = args.get("path").orElse("");
                yield from.isEmpty() || path.isEmpty() ? Result.err(HostAccessProblems.badArguments(name, "from and path"))
                                                       : Result.ok(new Call(new CopyOut(from, path), args));
            }
            case "host_run" -> {
                Optional<String> executable = args.get("executable").filter(text -> !text.isEmpty());
                if (executable.isEmpty()) yield Result.err(HostAccessProblems.badArguments(name, "executable"));
                Optional<Tuple<String>> argv = args.get("args").map(HostTool::split);
                if (argv.isEmpty()) yield Result.err(HostAccessProblems.badArguments(name, "args"));
                yield Result.ok(new Call(new Run(executable.get(), argv.get()), args));
            }
            default -> Result.err(HostAccessProblems.noSuchTool(name));
        };
        return call;
    }

    /// The host paths a tool will touch, as the agent wrote them, before they are resolved. The
    /// clipboard, a URL and a notification touch none; `host_copy_out` names its destination
    /// here, and its source is a path in the sandbox, which [HostAccessTools] reads in the
    /// agent's home.
    static Tuple<String> pathsOf(HostTool tool) {
        return switch (tool) {
            case ListDir dir -> Tuple.of(String.class, dir.path());
            case Stat stat -> Tuple.of(String.class, stat.path());
            case ReadFile read -> Tuple.of(String.class, read.path());
            case Show show -> Tuple.of(String.class, show.path());
            case WriteFile write -> Tuple.of(String.class, write.path());
            case CopyOut copy -> Tuple.of(String.class, copy.path());
            case ClipboardRead _, OpenUrl _, ClipboardWrite _, Notify _, Run _ -> Tuple.of(String.class);
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

    private static Optional<Long> number(String text) {
        try {
            return Optional.of(Long.parseLong(text.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /// A program's arguments arrive as one string from pi's tool call. They are split the way a
    /// shell would split them, except that nothing is ever expanded or run: the result is a list
    /// handed to [ProcessBuilder], never to a shell.
    private static Tuple<String> split(String text) {
        Tuple<String> parts = Tuple.of(String.class);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == quote) quoted = false; else current.append(c);
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) { parts = parts.add(current.toString()); current.setLength(0); }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts = parts.add(current.toString());
        return parts;
    }
}
