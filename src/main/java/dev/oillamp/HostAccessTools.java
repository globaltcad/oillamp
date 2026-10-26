package dev.oillamp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import sprouts.Association;
import sprouts.Tuple;

/// Carries out an allowed host request. Every effect the feature can have on this machine is in
/// this class, and nowhere else.
///
/// Only [HostAccess] calls it, and only after the tier, the roots and the checker allowed it.
/// Everything here is bounded: a file read is cut at the lamp's `max_read_bytes`, a command is
/// given a timeout and stopped with everything it started, and a copy out never runs what it
/// copies.
///
/// The desktop tools go through the user's own desktop programs, found on `PATH`; a missing one
/// is a refusal with its name in it, not a silent nothing.
final class HostAccessTools {

    /// The largest file `host_copy_out` copies. The whole file is held in memory on its way out.
    static final int LARGEST_COPY = 64 * 1024 * 1024;

    /// The agent's home inside the sandbox, which [#inAgentHome] maps onto the lamp.
    static final String SANDBOX_HOME = "/home/agent";

    /// Names a desktop opens by running something rather than showing it. `xdg-open` hands a
    /// `.desktop` file to the launcher, and an installer to whatever installs it.
    private static final Set<String> LAUNCHED = Set.of(
            "desktop", "sh", "bash", "zsh", "fish", "csh", "ksh", "py", "pl", "rb", "jar", "appimage",
            "run", "bin", "exe", "msi", "bat", "cmd", "com", "command", "deb", "rpm", "snap",
            "flatpak", "flatpakref", "flatpakrepo");

    private final Machine machine;
    private final LampConfig.HostAccess access;
    private final Path agentHome;
    private final Tuple<String> withheld;

    /// @param agentHome the agent's home as it is on the host, the lamp's agent directory
    /// @param withheld  the variables of oillamp's environment a command does not get
    HostAccessTools(Machine machine, LampConfig.HostAccess access, Path agentHome, Tuple<String> withheld) {
        this.machine = machine;
        this.access = access;
        this.agentHome = agentHome;
        this.withheld = withheld;
    }

    /// Does the work, and returns what the agent is told.
    ///
    /// @param resolved  the paths, in the order the tool named them
    /// @param withdrawn completes when the sandbox stops waiting; a command still running then
    ///                  is stopped
    Result<String> perform(HostTool tool, Tuple<HostPaths.ResolvedPath> resolved, CompletableFuture<Void> withdrawn)
            throws IOException, InterruptedException {
        return switch (tool) {
            case HostTool.ListDir _ -> Result.ok(list(resolved.first()));
            case HostTool.Stat _ -> Result.ok(stat(resolved.first()));
            case HostTool.ReadFile read -> Result.ok(read(resolved.first(), read.offset(), effectiveLimit(read.maxBytes())));
            case HostTool.ClipboardRead _ -> clipboardRead();
            case HostTool.OpenUrl url -> open("host_open_url", url.url().toString());
            case HostTool.Show _ -> {
                // Asked again at the moment of the effect: the file may have changed since.
                Optional<String> launched = whyNotShown(resolved.first().hostPath());
                yield launched.isPresent() ? Result.err(HostAccessProblems.failed("host_show", launched.get()))
                                           : open("host_show", resolved.first().hostPath().toString());
            }
            case HostTool.WriteFile write -> write("host_write_file", resolved.first(),
                    write.text().getBytes(StandardCharsets.UTF_8));
            case HostTool.EditFile edit -> edit(resolved.first(), edit.oldText(), edit.newText());
            case HostTool.ClipboardWrite _ -> clipboardWrite();
            case HostTool.Notify notify -> notifyUser(notify.title(), notify.text());
            case HostTool.CopyOut copy -> copyOut(copy.from(), resolved.first());
            case HostTool.CopyIn copy -> copyIn(resolved.first(), copy.to());
            case HostTool.Bash bash -> bash(bash, resolved.first(), withdrawn);
        };
    }

    /// How much of a file one request may return: what the tool asked for, and never more than
    /// the lamp allows. A tool asking for more is cut, not refused: the agent can read on.
    private int effectiveLimit(int asked) {
        int most = access.maxReadBytes();
        return asked <= 0 ? most : Math.min(asked, most);
    }

    /// Why `host_show` must not open this file, or empty when it may.
    ///
    /// Showing is passive only while the default application shows the file. A file the desktop
    /// would run — a launcher, a script, anything marked executable — would make `host_show` a
    /// way to run a program without the executing tier, which is closed and always asks the
    /// person. So those are refused before any model is asked, and again just before opening.
    static Optional<String> whyNotShown(Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.of(file + " does not exist");
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.of(file + " is not a regular file");
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && LAUNCHED.contains(name.substring(dot + 1)))
            return Optional.of(file + " is a kind of file the desktop runs rather than shows");
        try {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (mode.contains(PosixFilePermission.OWNER_EXECUTE) || mode.contains(PosixFilePermission.GROUP_EXECUTE)
                    || mode.contains(PosixFilePermission.OTHERS_EXECUTE))
                return Optional.of(file + " is executable, so opening it could run it");
        } catch (IOException | UnsupportedOperationException e) {
            return Optional.of("the permissions of " + file + " cannot be read");
        }
        return Optional.empty();
    }

    /// A path in the sandbox, `~/x` or `/home/agent/x`, as a path inside the agent's home: where
    /// `host_copy_out` reads from, and where `host_copy_in` writes to. Anything else is not in the
    /// sandbox's home, and a `..` would leave it.
    ///
    /// @param tool     the tool, for the refusal
    /// @param argument the argument that names it, such as `from`
    static Result<Path> inAgentHome(String tool, String argument, String asked) {
        String text = asked.strip();
        String inside = text.startsWith("~/") ? text.substring(2)
                      : text.startsWith(SANDBOX_HOME + "/") ? text.substring(SANDBOX_HOME.length() + 1)
                      : "";
        if (inside.isEmpty())
            return Result.err(HostAccessProblems.badArguments(tool,
                    argument + ": a file in the sandbox's home, such as ~/workspace/report.pdf"));
        Path relative = Path.of(inside).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()
                || !relative.equals(Path.of(inside)))
            return Result.err(HostAccessProblems.badArguments(tool,
                    argument + ": a plain path in the sandbox's home, without . or .."));
        return Result.ok(relative);
    }

    // ─── reading ──────────────────────────────────────────────────────────────────────────

    /// The most entries one listing returns.
    private static final int LISTED = 500;

    private static String list(HostPaths.ResolvedPath dir) throws IOException {
        Path path = dir.hostPath();
        if (!Files.isDirectory(path)) return dir.asked() + " is not a directory.";
        StringBuilder out = new StringBuilder(path + "\n");
        try (var entries = Files.list(path)) {
            List<Path> sorted = entries.sorted().limit(LISTED).toList();
            for (Path entry : sorted) {
                // A link is described as itself: its target may be outside the roots, and a
                // broken one must not fail the whole listing.
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException gone) {
                    continue;   // removed while it was listed
                }
                String kind = attributes.isDirectory() ? "dir " : attributes.isSymbolicLink() ? "link" : "file";
                String size = attributes.isRegularFile() ? String.valueOf(attributes.size()) : "";
                out.append(String.format("%-4s %12s  %s%n", kind, size, entry.getFileName()));
            }
        }
        return out.toString();
    }

    private static String stat(HostPaths.ResolvedPath path) throws IOException {
        Path file = path.hostPath();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return path.asked() + " does not exist.";
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return path.hostPath() + "\n"
             + "kind        " + (attributes.isDirectory() ? "dir" : attributes.isSymbolicLink() ? "link" : "file") + "\n"
             + "size        " + attributes.size() + " bytes\n"
             + "modified    " + attributes.lastModifiedTime() + "\n"
             + "permissions " + PosixFilePermissions.toString(
                     Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
    }

    /// How far into a file the binary check looks.
    private static final int SNIFFED = 4096;

    /// Reads at most `limit` bytes from `offset`, and never more of the file than that: a file of
    /// gigabytes costs what the agent is handed, not what the file holds.
    private static String read(HostPaths.ResolvedPath path, long offset, int limit) throws IOException {
        Path file = path.hostPath();
        if (!Files.isRegularFile(file)) return path.asked() + " is not a file.";
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (isBinary(readAt(channel, 0, (int) Math.min(size, SNIFFED))))
                return path.asked() + " is not text, so it is not returned. Use it through a program instead.";
            long from = Math.min(Math.max(0, offset), size);
            byte[] bytes = readAt(channel, from, (int) Math.min(limit, size - from));
            // Cut where a character ends, so the agent never gets half of one; the next read
            // starts where this one stopped.
            int kept = bytes.length;
            if (from + kept < size) kept = characterBoundary(bytes);
            String text = new String(bytes, 0, kept, StandardCharsets.UTF_8);
            long rest = size - from - kept;
            return "from " + path.hostPath() + ", byte " + from + " of " + size + ":\n\n" + text
                 + (rest > 0 ? "\n\n[… " + rest + " bytes more; read on with offset=" + (from + kept) + "]" : "");
        }
    }

    private static byte[] readAt(SeekableByteChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(0, length));
        channel.position(position);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }

    /// The length of `bytes` without a UTF-8 character that was cut at the end.
    private static int characterBoundary(byte[] bytes) {
        int end = bytes.length;
        int back = 0;
        // Continuation bytes are 10xxxxxx; a character is at most four bytes.
        while (back < 4 && end - back - 1 >= 0 && (bytes[end - back - 1] & 0xC0) == 0x80) back++;
        int lead = end - back - 1;
        if (lead < 0) return end;
        int first = bytes[lead] & 0xFF;
        int needed = first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : first >= 0xC0 ? 2 : 1;
        return back + 1 >= needed ? end : lead;
    }

    /// A file with a NUL byte in its first kilobytes is treated as binary. Reading it as text
    /// would hand the agent a garbled copy of an image or an archive, which no one can use.
    private static boolean isBinary(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return true;
        return false;
    }

    // ─── writing ──────────────────────────────────────────────────────────────────────────

    private static Result<String> write(String tool, HostPaths.ResolvedPath path, byte[] content) throws IOException {
        Path file = path.hostPath();
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
            return Result.err(HostAccessProblems.failed(tool, path.asked() + " is a directory"));
        // A file that is a link is replaced, not written through: the target was never part of
        // what the checker was shown, so there is no verdict about it. What is written is never
        // executable, whatever was there before.
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".oillamp-write-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, content);
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Result.ok("wrote " + content.length + " bytes to " + file);
    }

    /// The largest file `host_edit_file` edits. The whole file is held in memory while it is.
    static final int LARGEST_EDIT = 8 * 1024 * 1024;

    /// Replaces the one place `oldText` occurs in a text file with `newText`, and writes the file
    /// as [#write] does. No match, or more than one, changes nothing: a replacement in the wrong
    /// one of two places is worse than none, and the agent can give more of the text around it.
    private static Result<String> edit(HostPaths.ResolvedPath path, String oldText, String newText) throws IOException {
        Path file = path.hostPath();
        if (!Files.isRegularFile(file))
            return Result.err(HostAccessProblems.failed("host_edit_file", path.asked() + " is not a file"));
        if (Files.size(file) > LARGEST_EDIT)
            return Result.err(HostAccessProblems.failed("host_edit_file", path.asked() + " is larger than "
                    + LARGEST_EDIT / (1024 * 1024) + " MiB"));
        byte[] bytes = Files.readAllBytes(file);
        if (isBinary(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, SNIFFED))))
            return Result.err(HostAccessProblems.failed("host_edit_file", path.asked() + " is not text"));
        String text = new String(bytes, StandardCharsets.UTF_8);
        int first = text.indexOf(oldText);
        if (first < 0)
            return Result.err(HostAccessProblems.failed("host_edit_file",
                    "old_text does not occur in " + path.asked() + "; read the file again and copy it exactly"));
        if (text.indexOf(oldText, first + oldText.length()) >= 0)
            return Result.err(HostAccessProblems.failed("host_edit_file",
                    "old_text occurs more than once in " + path.asked() + "; include more of the lines around it"));
        String edited = text.substring(0, first) + newText + text.substring(first + oldText.length());
        Result<String> written = write("host_edit_file", path, edited.getBytes(StandardCharsets.UTF_8));
        return written instanceof Result.Ok<String>
                ? Result.ok("replaced " + oldText.length() + " characters with " + newText.length()
                            + " in " + file + ", at character " + first)
                : written;
    }

    /// Copies a file out of the agent's home, read without following a link the agent may have
    /// put there, and writes it as data: never executable.
    private Result<String> copyOut(String from, HostPaths.ResolvedPath to) throws IOException {
        Result<Path> source = inAgentHome("host_copy_out", "from", from);
        if (!(source instanceof Result.Ok<Path>(Path relative, var ignored)))
            return Result.err(HostAccessProblems.failed("host_copy_out", from + " is not a file in the sandbox's home"));
        Optional<byte[]> bytes;
        try {
            bytes = FilesystemUtil.readBytesInAgentHome(agentHome, relative, LARGEST_COPY);
        } catch (FilesystemUtil.LinkInAgentHome link) {
            return Result.err(HostAccessProblems.failed("host_copy_out", from + " is or passes through a link"));
        }
        if (bytes.isEmpty())
            return Result.err(HostAccessProblems.failed("host_copy_out", from + " does not exist in the sandbox"));
        return write("host_copy_out", to, bytes.get());
    }

    /// Copies a file from the host into the agent's home, as data: `rw-r--r--`, never
    /// executable.
    ///
    /// The host file is read where it was resolved and checked. The agent's home is the agent's
    /// to change, so the copy is written as [FilesystemUtil#replaceInAgentHome] writes: one
    /// directory at a time, never through a link. A link the agent planted at the destination is
    /// replaced by the copy; a link at a directory on the way refuses the copy. Either way nothing
    /// is written outside the agent's home, which would otherwise be a write anywhere on the host,
    /// past the checker.
    private Result<String> copyIn(HostPaths.ResolvedPath from, String to) throws IOException {
        Result<Path> destination = inAgentHome("host_copy_in", "to", to);
        if (!(destination instanceof Result.Ok<Path>(Path relative, var ignored)))
            return Result.err(HostAccessProblems.failed("host_copy_in", to + " is not a file in the sandbox's home"));
        Path file = from.hostPath();
        if (!Files.isRegularFile(file))
            return Result.err(HostAccessProblems.failed("host_copy_in", from.asked() + " is not a file"));
        if (Files.size(file) > LARGEST_COPY)
            return Result.err(HostAccessProblems.failed("host_copy_in", from.asked() + " is larger than "
                    + LARGEST_COPY / (1024 * 1024) + " MiB"));
        byte[] bytes = Files.readAllBytes(file);
        try {
            FilesystemUtil.replaceInAgentHome(agentHome, relative, bytes, PosixMode.PUBLIC_FILE);
        } catch (FilesystemUtil.LinkInAgentHome link) {
            return Result.err(HostAccessProblems.failed("host_copy_in", to + " passes through a link in the sandbox"));
        }
        return Result.ok("copied " + bytes.length + " bytes from " + file + " to " + to + " in the sandbox");
    }

    // ─── the desktop ──────────────────────────────────────────────────────────────────────

    private Result<String> clipboardRead() {
        // Reading another process's selection needs a tool that speaks Wayland or X11. oillamp
        // does not carry one yet, and guessing is worse than saying so.
        return Result.err(HostAccessProblems.failed("host_clipboard_read",
                "reading the clipboard on the host is not built yet; copy the text into a file and read that"));
    }

    private Result<String> clipboardWrite() {
        return Result.err(HostAccessProblems.failed("host_clipboard_write",
                "writing the clipboard on the host is not built yet"));
    }

    private Result<String> notifyUser(String title, String text) {
        Optional<Path> notifier = machine.locateExecutable("notify-send");
        if (notifier.isEmpty())
            return Result.err(HostAccessProblems.failed("host_notify", "notify-send is not on PATH"));
        Machine.Outcome outcome = machine.run(new Machine.Command(
                Tuple.of(String.class, notifier.get().toString(), "--app-name=oillamp", "--", title, text),
                Association.between(String.class, String.class), Optional.empty(),
                java.time.Duration.ofSeconds(10), "notify-send", false));
        return outcome.exitCode() == 0 ? Result.ok("the user was notified: " + title)
                : Result.err(HostAccessProblems.failed("host_notify", outcome.errorOutput().strip()));
    }

    private Result<String> open(String tool, String what) {
        Optional<Path> opener = machine.locateExecutable("xdg-open");
        if (opener.isEmpty()) return Result.err(HostAccessProblems.failed(tool, "xdg-open is not on PATH"));
        // The opener is detached and its exit status is not waited for: a browser that is
        // already running hands the URL to itself and exits, and that is not a failure.
        Machine.Window window = machine.launch(new Machine.Command(Tuple.of(String.class, opener.get().toString(), what),
                Association.between(String.class, String.class), Optional.empty(),
                java.time.Duration.ofMinutes(1), "xdg-open", false), Machine.Window.Stdio.DETACHED);
        if (window.failure().isPresent())
            return Result.err(HostAccessProblems.failed(tool, window.failure().get()));
        return Result.ok("opened " + what + " on the user's desktop");
    }

    // ─── running a command ───────────────────────────────────────────────────────────────

    /// The most of each of a command's outputs the agent is handed. The end is kept: that is
    /// where a build or a test run says how it went.
    static final int OUTPUT_KEPT = 32 * 1024;

    /// Runs a command with bash, in the directory the agent named, for at most the time it gave.
    ///
    /// The command is one argument to `bash -c`, never pasted into another command line, so
    /// nothing in it is read by anything but bash. It inherits oillamp's environment, as a
    /// command typed in the user's terminal would, except for the variables that hold oillamp's
    /// secrets, which `env -u` removes before bash starts. Its standard input is empty. When its
    /// time is up, or the request is withdrawn, it is stopped with everything it started.
    ///
    /// The command runs on a thread of its own, which does nothing but wait for it, so that a
    /// withdrawal can interrupt that thread: [Machine#run] stops the command when its thread is
    /// interrupted. The request's own thread is never interrupted; it goes on to write the log
    /// and the session's events.
    ///
    /// A command that exits with a status other than 0 has run: that is an answer, not a failure.
    private Result<String> bash(HostTool.Bash bash, HostPaths.ResolvedPath cwd, CompletableFuture<Void> withdrawn)
            throws InterruptedException {
        if (!Files.isDirectory(cwd.hostPath()))
            return Result.err(HostAccessProblems.failed("host_bash", cwd.asked() + " is not a directory"));
        if (machine.locateExecutable("bash").isEmpty())
            return Result.err(HostAccessProblems.failed("host_bash", "bash is not on PATH"));
        Tuple<String> argv = Tuple.of(String.class, "env");
        for (String variable : withheld) argv = argv.add("-u").add(variable);
        argv = argv.addAll(Tuple.of(String.class, "bash", "-c", bash.command()));
        Machine.Command command = new Machine.Command(argv, Association.between(String.class, String.class),
                Optional.of(cwd.hostPath()), bash.timeout(), "host_bash", false);
        CompletableFuture<Machine.Outcome> ran = new CompletableFuture<>();
        Thread running = Thread.ofVirtual().name("oillamp-host-bash").start(() -> {
            try {
                ran.complete(machine.run(command));
            } catch (RuntimeException failed) {
                ran.completeExceptionally(failed);
            }
        });
        var unused = withdrawn.thenRun(running::interrupt);
        running.join();
        return answerOf(ran.join(), bash.timeout(), withdrawn.isDone());
    }

    /// What the agent is told about a command, by how it ended. A command stopped before its end
    /// while the request was withdrawn was stopped for that, not for its time. One that finished
    /// is reported as finished, also when the withdrawal came a moment after.
    static Result<String> answerOf(Machine.Outcome outcome, java.time.Duration timeout, boolean withdrawn) {
        return switch (outcome) {
            case Machine.Outcome.Finished finished -> Result.ok("exit status " + finished.exitCode() + "\n"
                    + section("stdout", finished.standardOutput()) + section("stderr", finished.standardError()));
            case Machine.Outcome.TimedOut _ when withdrawn -> Result.err(HostAccessProblems.failed("host_bash",
                    "the sandbox withdrew the request while the command ran, and it was stopped with everything it started"));
            case Machine.Outcome.TimedOut timedOut -> Result.ok("timed out: it was given " + timeout.toSeconds()
                    + " s, and was stopped with everything it started\n"
                    + section("stdout so far", timedOut.standardOutputSoFar()));
            case Machine.Outcome.NotFound notFound -> Result.err(HostAccessProblems.failed("host_bash",
                    notFound.executable() + " is not on PATH"));
        };
    }

    /// One of a command's outputs, under its name, or nothing when it printed nothing there.
    private static String section(String name, String output) {
        if (output.isBlank()) return "";
        String kept = output.length() <= OUTPUT_KEPT ? output
                : "[… " + (output.length() - OUTPUT_KEPT) + " characters before this were cut]\n"
                  + output.substring(output.length() - OUTPUT_KEPT);
        return name + ":\n" + kept + (kept.endsWith("\n") ? "" : "\n");
    }
}
