package dev.gui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// The last lines Genies logged, for the user to peek into: what SwingTree and Sprouts log
/// through slf4j ([LoggedErrors]), what went wrong unexpectedly ([ErrorLog]), and what the
/// genies' lamps report.
///
/// They are kept in memory, and, once [#writeTo] names a file, lines of [#WRITTEN] and worse are
/// written there too, so that what happened can still be read after Genies closed, such as why a
/// genie's runs failed. The file is moved to `<name>.1` past [#MOST_BYTES], as [ErrorLog]'s is.
///
/// Kept apart from the window's state on purpose. Lines come many a second, on any thread; the
/// window reads them only while the user looks, and only when [#added] says there are new ones.
///
/// One for the whole application, as slf4j creates its loggers itself and cannot be handed one.
public final class AppLog {

    /// How bad, or how detailed, a line is; from the worst to the finest.
    public enum Level {
        ERROR, WARN, INFO, DEBUG, TRACE;

        /// Whether a line of this level is shown, or kept, when `least` is the finest wanted.
        public boolean atLeast(Level least) { return ordinal() <= least.ordinal(); }
    }

    /// One line of the log.
    ///
    /// @param when   when it was logged
    /// @param level  how bad, or how detailed
    /// @param source who logged it, such as a logger's name or a genie's lamp
    /// @param text   what it said, on one line
    public record Line(Instant when, Level level, String source, String text) {}

    /// How many lines are kept. Older ones go.
    public static final int MOST = 2000;
    /// How long a line may be; the rest is cut off. Some events of a lamp carry a lot.
    static final int LONGEST = 600;
    /// How long a line in the file may be: longer than in memory, as the file is read to find
    /// out what went wrong, and a failure's reason comes at the end of its event.
    static final int LONGEST_WRITTEN = 8_000;
    /// The finest level written to the file. Finer lines, such as each word of an answer as it
    /// comes, are many and say little afterwards.
    public static final Level WRITTEN = Level.INFO;
    /// Above this size the file is moved to `<name>.1`, replacing the one there, and begun again.
    public static final long MOST_BYTES = 2_000_000;

    private static final AppLog SHARED = new AppLog();

    public static AppLog shared() { return SHARED; }

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private long added;
    /// The finest level kept. Finer ones are not even put into words: SwingTree says a lot at
    /// those levels, and the user asks for them only while looking.
    private volatile Level keeping = Level.INFO;
    /// Where lines are written too; empty to keep them in memory only.
    private volatile Optional<Path> file = Optional.empty();
    /// Writes the lines to [#file], one after the other, on a thread of its own, so that a slow
    /// disk never holds up whoever logged, Swing's thread among them. A daemon, so that it never
    /// keeps Genies from ending.
    private final ExecutorService writer = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "genies log");
        thread.setDaemon(true);
        return thread;
    });
    /// The file open now, and how much is in it, for the writer alone.
    private Optional<SeekableByteChannel> open = Optional.empty();
    private long size;
    /// Whether the writer said it could not write, so that it says so once, not for every line.
    private boolean saidSo;

    AppLog() {}

    /// From now on, lines of [#WRITTEN] and worse are also written to `file`.
    public void writeTo(Path file) {
        this.file = Optional.of(file);
        writer.execute(this::close);
    }

    /// The file lines are written to, if any.
    public Optional<Path> file() { return file; }

    /// Waits until every line added so far is in the file, or `seconds` have passed.
    public void awaitWritten(long seconds) throws InterruptedException {
        try {
            writer.submit(() -> { }).get(seconds, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException notYet) {
            // What could be written is; the rest comes later, or never, as for any line.
        }
    }

    /// Whether lines of `level` are kept now.
    public boolean keeps(Level level) { return level.atLeast(keeping); }

    /// From now on, keeps lines of `least` and worse.
    public void keep(Level least) { keeping = least; }

    /// Keeps a line, if its level is kept, and lets the oldest go past [#MOST].
    public void add(Level level, String source, String text) {
        if (!keeps(level)) return;
        String oneLine = text.replaceAll("\\s*\\R\\s*", " ⏎ ");
        String cut = oneLine.length() > LONGEST ? oneLine.substring(0, LONGEST) + " …" : oneLine;
        Line line = new Line(Instant.now(), level, source, cut);
        synchronized (this) {
            lines.addLast(line);
            if (lines.size() > MOST) lines.removeFirst();
            added++;
        }
        if (level.atLeast(WRITTEN) && file.isPresent()) {
            String whole = oneLine.length() > LONGEST_WRITTEN ? oneLine.substring(0, LONGEST_WRITTEN) + " …" : oneLine;
            String entry = line.when() + " " + String.format("%-5s", level) + " " + source + ": " + whole + "\n";
            try {
                writer.execute(() -> write(entry));
            } catch (RejectedExecutionException ending) {
                // Genies is ending; the line is still in memory.
            }
        }
    }

    /// Appends `entry` to the file, on the writer's thread. Never throws: a log that cannot be
    /// written must not break what logged. When it cannot, it says so on the error output, once,
    /// and tries again with the next line, as a full disk may have room again by then.
    private void write(String entry) {
        Optional<Path> to = file;
        if (to.isEmpty()) return;
        try {
            if (open.isEmpty()) {
                Path folder = to.get().toAbsolutePath().getParent();
                if (folder != null) Files.createDirectories(folder);
                open = Optional.of(openPrivately(to.get()));
                size = open.get().size();
            }
            if (size > MOST_BYTES) {
                close();
                Files.move(to.get(), to.get().resolveSibling(to.get().getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                open = Optional.of(openPrivately(to.get()));
                size = open.get().size();
            }
            ByteBuffer bytes = ByteBuffer.wrap(entry.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) size += open.get().write(bytes);
            saidSo = false;
        } catch (IOException | RuntimeException unwritten) {
            close();
            if (!saidSo) System.err.println("genies: could not write the log to " + to.get() + ": " + unwritten);
            saidSo = true;
        }
    }

    private void close() {
        try {
            if (open.isPresent()) open.get().close();
        } catch (IOException ignored) {
            // Nothing more can be lost by it.
        }
        open = Optional.empty();
    }

    /// Opens `file` to append to it, creating it readable by its owner alone where the file system
    /// knows owners, in one step, so that two Genies starting at once cannot trip over each
    /// other: the log says what the genies were asked and what they did.
    private static SeekableByteChannel openPrivately(Path file) throws IOException {
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
            return Files.newByteChannel(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.newByteChannel(file, options);
    }

    /// The lines kept, oldest first.
    public synchronized List<Line> lines() { return List.copyOf(lines); }

    /// How many lines were ever added, so that a reader knows whether there are new ones.
    public synchronized long added() { return added; }
}
