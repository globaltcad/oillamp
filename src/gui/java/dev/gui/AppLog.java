package dev.gui;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

/// The last lines Genies logged, in memory only, for the user to peek into: what SwingTree and
/// Sprouts log through slf4j ([LoggedErrors]), what went wrong unexpectedly ([ErrorLog]), and
/// what the genies' lamps report.
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

    private static final AppLog SHARED = new AppLog();

    public static AppLog shared() { return SHARED; }

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private long added;
    /// The finest level kept. Finer ones are not even put into words: SwingTree says a lot at
    /// those levels, and the user asks for them only while looking.
    private volatile Level keeping = Level.INFO;

    AppLog() {}

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
    }

    /// The lines kept, oldest first.
    public synchronized List<Line> lines() { return List.copyOf(lines); }

    /// How many lines were ever added, so that a reader knows whether there are new ones.
    public synchronized long added() { return added; }
}
