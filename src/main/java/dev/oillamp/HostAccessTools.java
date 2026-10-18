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

import sprouts.Association;
import sprouts.Tuple;

/// Carries out an allowed host request. Every effect the feature can have on this machine is in
/// this class, and nowhere else.
///
/// Only [HostAccess] calls it, and only after the tier, the roots and the checker allowed it.
/// Everything here is bounded: a file read is cut at the lamp's `max_read_bytes`, a program is
/// given a timeout and no shell, and a copy out never runs what it copies.
///
/// The desktop tools go through the user's own desktop programs, found on `PATH`; a missing one
/// is a refusal with its name in it, not a silent nothing.
final class HostAccessTools {

    /// How long a program the agent runs on the host may take. A tool call that hangs forever
    /// would hold the agent's run until its own timeout, with no sign why.
    static final java.time.Duration PROGRAM_PATIENCE = java.time.Duration.ofMinutes(5);

    /// The largest file `host_copy_out` copies. The whole file is held in memory on its way out.
    static final int LARGEST_COPY = 64 * 1024 * 1024;

    /// The agent's home inside the sandbox, which [#sourceInAgentHome] maps onto the lamp.
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

    /// @param agentHome the agent's home as it is on the host, the lamp's agent directory
    HostAccessTools(Machine machine, LampConfig.HostAccess access, Path agentHome) {
        this.machine = machine;
        this.access = access;
        this.agentHome = agentHome;
    }

    /// Does the work, and returns what the agent is told.
    ///
    /// @param resolved the paths, in the order the tool named them
    Result<String> perform(HostTool.Call call, Tuple<HostPaths.ResolvedPath> resolved)
            throws IOException, InterruptedException {
        return switch (call.tool()) {
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
            case HostTool.ClipboardWrite _ -> clipboardWrite();
            case HostTool.Notify notify -> notifyUser(notify.title(), notify.text());
            case HostTool.CopyOut copy -> copyOut(copy.from(), resolved.first());
            case HostTool.Run run -> run(run.executable(), run.argv());
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

    /// The file a `host_copy_out` copies, as a path in the agent's home: `~/x` or `/home/agent/x`.
    /// Anything else is not in the sandbox's home, and a `..` would leave it.
    static Result<Path> sourceInAgentHome(String from) {
        String text = from.strip();
        String inside = text.startsWith("~/") ? text.substring(2)
                      : text.startsWith(SANDBOX_HOME + "/") ? text.substring(SANDBOX_HOME.length() + 1)
                      : "";
        if (inside.isEmpty())
            return Result.err(HostAccessProblems.badArguments("host_copy_out",
                    "from: a file in the sandbox's home, such as ~/workspace/report.pdf"));
        Path relative = Path.of(inside).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()
                || !relative.equals(Path.of(inside)))
            return Result.err(HostAccessProblems.badArguments("host_copy_out",
                    "from: a plain path in the sandbox's home, without . or .."));
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

    /// Copies a file out of the agent's home, read without following a link the agent may have
    /// put there, and writes it as data: never executable.
    private Result<String> copyOut(String from, HostPaths.ResolvedPath to) throws IOException {
        Result<Path> source = sourceInAgentHome(from);
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

    // ─── running a program ───────────────────────────────────────────────────────────────

    /// Runs one of the programs the lamp names, with an argument list and never a shell. The
    /// executable is found on `PATH`, and its real path is checked, so a `PATH` the agent could
    /// not change still cannot be fooled by a directory it could.
    private Result<String> run(String executable, Tuple<String> argv) throws IOException, InterruptedException {
        if (!access.executables().contains(executable))
            return Result.err(HostAccessProblems.failed("host_run", executable + " is not in host_access.executables"));
        Optional<Path> found = machine.locateExecutable(executable);
        if (found.isEmpty()) return Result.err(HostAccessProblems.failed("host_run", executable + " is not on PATH"));
        Path real = found.get().toRealPath();
        Machine.Outcome outcome = machine.run(new Machine.Command(
                Tuple.of(String.class).add(real.toString()).addAll(argv),
                Association.between(String.class, String.class), Optional.empty(),
                PROGRAM_PATIENCE, executable, false));
        StringBuilder out = new StringBuilder();
        out.append("exit status ").append(outcome.exitCode()).append('\n');
        if (!outcome.output().isBlank()) out.append("stdout:\n").append(outcome.output()).append('\n');
        if (!outcome.errorOutput().isBlank()) out.append("stderr:\n").append(outcome.errorOutput());
        return Result.ok(out.toString());
    }
}
