package dev.oillamp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// What the last save found of each file it stored: its size, when its content and its inode
/// last changed, which inode it is, and the blob it became. The next save takes the blob of a file
/// whose stat is still the same, rather than reading the file again, as git's index does. Saving a
/// large agent directory then reads only what changed, and lists the rest.
///
/// [History] keeps it as `file-stats` in the history, which the agent cannot reach, and does the
/// reading and writing; this class only decides. It is only ever a shortcut: stats that are
/// missing, damaged or of another version count as none, and the save reads everything, as it did
/// before there were any. A blob they name is in the history: objects are only ever added, except
/// by the sweep of a prune, which has them [forget][#without] those first.
///
/// A file changed within the same tick of the filesystem's clock as the save read it has the same
/// stat as before, though not the same content. So a file is remembered only when it last changed
/// [#SETTLED] before the save began, by the filesystem's own clock: any later change gives it
/// another time. Whatever changed later is read again next time.
final class FileStats {

    /// How long before a save began a file must have last changed to be remembered. Longer than
    /// the coarsest clock of a filesystem a lamp is likely to be on, FAT's two seconds.
    static final Duration SETTLED = Duration.ofSeconds(3);

    private static final String HEADER = "oillamp file stats 1";
    private static final Pattern BLOB = Pattern.compile("[0-9a-f]{40}");

    /// A file's stat, as far as it tells whether its content may have changed.
    ///
    /// @param modified when its content last changed, in nanoseconds since 1970
    /// @param changed  when its inode last changed, in nanoseconds since 1970: also when its
    ///                 content was replaced and its time of modification set back, as `cp -p`,
    ///                 `rsync -t` and `tar` do
    record Stat(long size, long modified, long changed, long inode, long device) {}

    private record Known(Stat stat, String blob) {}

    private final Map<String, Known> before;
    private final Map<String, Known> now = new HashMap<>();
    private final long settledBefore;

    private FileStats(Map<String, Known> before, long settledBefore) {
        this.before = before;
        this.settledBefore = settledBefore;
    }

    /// None: every file is read, and none is remembered.
    static FileStats none() {
        return new FileStats(new HashMap<>(), Long.MIN_VALUE);
    }

    /// The stats the last save [wrote][#written], for a save that began at `began`, in
    /// nanoseconds since 1970 on the clock of the filesystem the files are on.
    static FileStats of(byte[] written, long began) {
        return new FileStats(known(written), began - SETTLED.toNanos());
    }

    /// The blob the file at `path` was stored as, while its stat is `stat` as it was then.
    Optional<String> blob(String path, Stat stat) {
        Known known = before.get(path);
        return known != null && known.stat().equals(stat) ? Optional.of(known.blob()) : Optional.empty();
    }

    /// Remembers that the file at `path`, with `stat`, was stored as `blob`, unless it changed
    /// too recently to tell a later change from it.
    void stored(String path, Stat stat, String blob) {
        if (stat.modified() < settledBefore && stat.changed() < settledBefore) now.put(path, new Known(stat, blob));
    }

    /// What this save remembered, for the next one. The files it did not find are forgotten.
    byte[] written() {
        return written(now);
    }

    /// The stats `written` without the files stored as one of `blobs`, which are about to be
    /// deleted, so that no save takes a blob that is gone. Empty when none was stored so.
    static Optional<byte[]> without(byte[] written, Set<String> blobs) {
        Map<String, Known> known = known(written);
        return known.values().removeIf(it -> blobs.contains(it.blob())) ? Optional.of(written(known)) : Optional.empty();
    }

    private static byte[] written(Map<String, Known> known) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(known.size() * 160);
        field(out, HEADER);
        for (Map.Entry<String, Known> entry : known.entrySet()) {
            Stat stat = entry.getValue().stat();
            field(out, entry.getKey());
            field(out, Long.toString(stat.size()));
            field(out, Long.toString(stat.modified()));
            field(out, Long.toString(stat.changed()));
            field(out, Long.toString(stat.inode()));
            field(out, Long.toString(stat.device()));
            field(out, entry.getValue().blob());
        }
        return out.toByteArray();
    }

    /// Each field ends with a NUL byte, which no path holds, since a name may hold a newline.
    private static void field(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static Map<String, Known> known(byte[] written) {
        Map<String, Known> known = new HashMap<>();
        String[] fields = new String(written, StandardCharsets.UTF_8).split("\0", -1);
        // The last field is what follows the last NUL: nothing, in stats written whole.
        if (fields.length < 2 || !fields[0].equals(HEADER) || (fields.length - 2) % 7 != 0 || !fields[fields.length - 1].isEmpty())
            return known;
        try {
            for (int i = 1; i + 7 <= fields.length - 1; i += 7) {
                Stat stat = new Stat(Long.parseLong(fields[i + 1]), Long.parseLong(fields[i + 2]),
                        Long.parseLong(fields[i + 3]), Long.parseLong(fields[i + 4]), Long.parseLong(fields[i + 5]));
                if (!BLOB.matcher(fields[i + 6]).matches()) return new HashMap<>();
                known.put(fields[i], new Known(stat, fields[i + 6]));
            }
        } catch (NumberFormatException damaged) {
            return new HashMap<>();
        }
        return known;
    }
}
