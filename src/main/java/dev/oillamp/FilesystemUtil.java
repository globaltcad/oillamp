package dev.oillamp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import sprouts.Tuple;

/// File operations on the lamp directory.
///
/// These use the real filesystem even in tests, unlike the rest of oillamp's effects, which go
/// through [Machine]. The lamp's isolation depends on real permission bits, ownership and
/// symlinks, and a simulated filesystem could hide bugs in exactly those.
///
/// Files are written atomically: to a temporary file in the same directory, then renamed. An
/// interrupted run cannot leave a half-written `lamp.json` or `runtime.env` behind.
///
/// Files inside the agent's home are a case of their own, read and written with
/// [#readInAgentHome] and [#writeInAgentHome]. The agent can change anything in its home,
/// while the sandbox runs and between sessions, and it runs as the same user as oillamp on the
/// host. A directory it replaced by a symbolic link would otherwise lead oillamp's writes to
/// wherever the link points, such as the user's own `~/.bashrc`.
final class FilesystemUtil {

    private FilesystemUtil() {}

    /// The largest file [#readInAgentHome] reads. The files oillamp reads there are a few
    /// kilobytes; anything much larger was not written by pi.
    static final int LARGEST_AGENT_FILE = 1024 * 1024;

    /// A symbolic link where oillamp was about to read or write a file in the agent's home: at
    /// the file itself, or at a directory on the way to it. Nothing was read or written.
    static final class LinkInAgentHome extends IOException {

        private static final long serialVersionUID = 1L;

        private final transient Path link;

        LinkInAgentHome(Path link) {
            super(link + " is a symbolic link, and oillamp does not follow links in the agent's home");
            this.link = link;
        }

        /// The link, as a path on the host.
        Path link() { return link; }
    }

    /// Reads the text file `relative` in the agent's `home`, without following a symbolic link
    /// anywhere below `home`.
    ///
    /// @return the file's text; empty when the file, or a directory on the way to it, is not there
    /// @throws LinkInAgentHome when the file or a directory on the way is a link
    /// @throws IOException     when it is not a regular file, is larger than [#LARGEST_AGENT_FILE],
    ///                         or could not be read
    static Optional<String> readInAgentHome(Path home, Path relative) throws IOException {
        Optional<Optional<String>> read = inDirectoryOf(home, relative, false, (directory, name, where) -> {
            Optional<BasicFileAttributes> found = attributesOf(directory, name);
            if (found.isEmpty()) return Optional.<String>empty();
            if (found.get().isSymbolicLink()) throw new LinkInAgentHome(where);
            if (!found.get().isRegularFile()) throw new IOException(where + " is not a regular file");
            try (SeekableByteChannel channel = directory.newByteChannel(name,
                         Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream in = Channels.newInputStream(channel)) {
                byte[] bytes = in.readNBytes(LARGEST_AGENT_FILE + 1);
                if (bytes.length > LARGEST_AGENT_FILE)
                    throw new IOException(where + " is larger than " + LARGEST_AGENT_FILE / 1024 + " KiB");
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        });
        return read.flatMap(text -> text);
    }

    /// Reads the file `relative` in the agent's `home` as bytes, without following a symbolic
    /// link anywhere below `home`, as [#readInAgentHome] reads text.
    ///
    /// @param largest the most bytes the file may have
    /// @return the file's bytes; empty when the file, or a directory on the way to it, is not there
    /// @throws LinkInAgentHome when the file or a directory on the way is a link
    /// @throws IOException     when it is not a regular file, is larger than `largest`, or could
    ///                         not be read
    static Optional<byte[]> readBytesInAgentHome(Path home, Path relative, int largest) throws IOException {
        Optional<Optional<byte[]>> read = inDirectoryOf(home, relative, false, (directory, name, where) -> {
            Optional<BasicFileAttributes> found = attributesOf(directory, name);
            if (found.isEmpty()) return Optional.<byte[]>empty();
            if (found.get().isSymbolicLink()) throw new LinkInAgentHome(where);
            if (!found.get().isRegularFile()) throw new IOException(where + " is not a regular file");
            try (SeekableByteChannel channel = directory.newByteChannel(name,
                         Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream in = Channels.newInputStream(channel)) {
                byte[] bytes = in.readNBytes(largest + 1);
                if (bytes.length > largest)
                    throw new IOException(where + " is larger than " + largest / (1024 * 1024) + " MiB");
                return Optional.of(bytes);
            }
        });
        return read.flatMap(bytes -> bytes);
    }

    /// Writes `content` to the file `relative` in the agent's `home`, without following a
    /// symbolic link anywhere below `home`, and makes the directories on the way that are not
    /// there yet.
    ///
    /// The file is written atomically, as [#writeFile] writes, but each step goes through an open
    /// handle on the directory that refuses links: the directories are opened one by one, the
    /// temporary file is created in the last one, and renamed there. A link the agent plants
    /// while this runs makes the write fail; it never moves the write elsewhere. The one thing
    /// done by path is making a directory that is missing, which oillamp does before the first
    /// sandbox of a lamp starts. Should the agent swap a directory on the way for a link at that
    /// very moment, an empty directory could appear where the link points, and nothing more:
    /// the file is still written only through the handles.
    ///
    /// @throws LinkInAgentHome when the file or a directory on the way is a link
    /// @throws IOException     when the file could not be written
    static void writeInAgentHome(Path home, Path relative, String content, PosixMode mode) throws IOException {
        writeInAgentHome(home, relative, content.getBytes(StandardCharsets.UTF_8), mode, false);
    }

    /// Writes `content` as the file `relative` in the agent's `home`, as [#writeInAgentHome]
    /// does, except that a link at the file itself is replaced by the file rather than refused:
    /// for a file the agent asked for, at a name of its choosing. A link at a directory on the
    /// way is still refused, and nothing is ever written through one.
    ///
    /// @throws LinkInAgentHome when a directory on the way is a link
    /// @throws IOException     when the file could not be written
    static void replaceInAgentHome(Path home, Path relative, byte[] content, PosixMode mode) throws IOException {
        writeInAgentHome(home, relative, content, mode, true);
    }

    private static void writeInAgentHome(Path home, Path relative, byte[] bytes, PosixMode mode, boolean replaceLink)
            throws IOException {
        inDirectoryOf(home, relative, true, (directory, name, where) -> {
            Optional<BasicFileAttributes> found = attributesOf(directory, name);
            if (found.isPresent() && found.get().isSymbolicLink() && !replaceLink) throw new LinkInAgentHome(where);
            if (found.isPresent() && found.get().isDirectory()) throw new IOException(where + " is a directory");
            Path temporary = Path.of(".oillamp-" + UUID.randomUUID() + ".tmp");
            try {
                try (SeekableByteChannel out = directory.newByteChannel(temporary,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(mode.permissions()))) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) out.write(buffer);
                }
                // The creation applies the user's umask, which can take bits away from the mode.
                directory.getFileAttributeView(temporary, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                         .setPermissions(mode.permissions());
                // A rename replaces whatever has the name, a link too, and never follows it.
                directory.move(temporary, directory, name);
            } finally {
                try {
                    directory.deleteFile(temporary);
                } catch (NoSuchFileException renamed) {
                    // The usual case: the temporary file has its final name now.
                }
            }
            return true;
        });
    }

    /// Deletes the file `relative` in the agent's `home`, if it is there, without following a
    /// symbolic link to a directory on the way. A link at the file itself is deleted, not what it
    /// points to.
    ///
    /// @throws LinkInAgentHome when a directory on the way is a link
    /// @throws IOException     when the file could not be deleted
    static void deleteInAgentHome(Path home, Path relative) throws IOException {
        inDirectoryOf(home, relative, false, (directory, name, where) -> {
            try {
                directory.deleteFile(name);
            } catch (NoSuchFileException absent) {
                // Nothing to delete.
            }
            return true;
        });
    }

    /// What [#inDirectoryOf] does in the directory it opened.
    @FunctionalInterface
    private interface InDirectory<T> {
        /// @param directory the file's directory, opened without following links
        /// @param name      the file's name in it
        /// @param where     the file's path on the host, for messages
        T apply(SecureDirectoryStream<Path> directory, Path name, Path where) throws IOException;
    }

    /// Opens the directory of `relative` in `home`, one directory at a time and never through a
    /// link, and does `action` there.
    ///
    /// @param create whether to make directories that are missing; otherwise a missing one ends
    ///               the walk
    /// @return what `action` returned; empty when a directory was missing and not made
    private static <T> Optional<T> inDirectoryOf(Path home, Path relative, boolean create, InDirectory<T> action)
            throws IOException {
        Path name = relative.getFileName();
        if (relative.isAbsolute() || name == null || name.toString().isEmpty())
            throw new IllegalArgumentException("not a plain path inside the agent's home: " + relative);
        for (Path part : relative)
            if (part.toString().equals("..") || part.toString().equals("."))
                throw new IllegalArgumentException("not a plain path inside the agent's home: " + relative);
        // The home itself is the agent directory in the lamp, which the agent cannot replace: its
        // parent, the lamp directory, is not in the sandbox.
        if (Files.isSymbolicLink(home)) throw new LinkInAgentHome(home);
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(home)) {
            if (!(opened instanceof SecureDirectoryStream<Path> secure))
                throw new IOException("this system cannot open " + home + " without following links");
            return walk(secure, home, relative, 0, create, action, home.resolve(relative));
        }
    }

    /// [#inDirectoryOf], from the directory `directory`, at `where`, which is `depth` names into
    /// `relative`.
    private static <T> Optional<T> walk(SecureDirectoryStream<Path> directory, Path where, Path relative, int depth,
                                        boolean create, InDirectory<T> action, Path file) throws IOException {
        Path part = relative.getName(depth);
        if (depth == relative.getNameCount() - 1) return Optional.of(action.apply(directory, part, file));
        Path inner = where.resolve(part);
        Optional<SecureDirectoryStream<Path>> opened = directoryIn(directory, part, inner);
        if (opened.isEmpty()) {
            if (!create) return Optional.empty();
            Files.createDirectory(inner, PosixFilePermissions.asFileAttribute(PosixMode.PUBLIC_DIR.permissions()));
            // The creation applies the user's umask, which can take bits away from the mode.
            directory.getFileAttributeView(part, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                     .setPermissions(PosixMode.PUBLIC_DIR.permissions());
            opened = directoryIn(directory, part, inner);
            if (opened.isEmpty()) throw new IOException(inner + " went away as it was made");
        }
        try (SecureDirectoryStream<Path> next = opened.get()) {
            return walk(next, inner, relative, depth + 1, create, action, file);
        }
    }

    /// The directory `name` in `parent`, opened without following a link; empty when it is not
    /// there.
    ///
    /// @throws LinkInAgentHome when it is a link
    private static Optional<SecureDirectoryStream<Path>> directoryIn(SecureDirectoryStream<Path> parent, Path name,
                                                                     Path where) throws IOException {
        Optional<BasicFileAttributes> found = attributesOf(parent, name);
        if (found.isEmpty()) return Optional.empty();
        if (found.get().isSymbolicLink()) throw new LinkInAgentHome(where);
        if (!found.get().isDirectory()) throw new IOException(where + " is not a directory");
        // Opened with O_NOFOLLOW: had it become a link since the look above, this fails.
        return Optional.of(parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS));
    }

    /// The attributes of `name` in `directory` itself, a link's own if it is one; empty when
    /// nothing has that name.
    private static Optional<BasicFileAttributes> attributesOf(SecureDirectoryStream<Path> directory, Path name)
            throws IOException {
        try {
            return Optional.of(directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                                        .readAttributes());
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
    }

    public static void createDirectory(Path path, PosixMode mode) throws IOException {
        if (Files.isDirectory(path)) {
            setMode(path, mode);
            return;
        }
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(mode.permissions()));
        // The creation call applies the user's umask, which can take bits away from the mode.
        setMode(path, mode);
    }

    /// Writes atomically, then applies the mode — never leaves a partially written file.
    public static void writeFile(Path path, String content, PosixMode mode) throws IOException {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8), mode);
    }

    /// The same for binary content, such as the wallpaper PNG in the image files. Writing it as text
    /// would corrupt it.
    public static void writeBytes(Path path, byte[] content, PosixMode mode) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent == null ? path : parent, ".oillamp-", ".tmp");
        try {
            Files.write(temporary, content);
            setMode(temporary, mode);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Creates a directory and every missing parent, applying the mode to those it creates.
    public static void createDirectories(Path path, PosixMode mode) throws IOException {
        if (Files.isDirectory(path)) return;
        Path parent = path.getParent();
        if (parent != null) createDirectories(parent, mode);
        if (Files.isDirectory(path)) return;
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(mode.permissions()));
        // The creation call applies the user's umask, which can take bits away from the mode.
        setMode(path, mode);
    }

    /// Copies atomically, like [#writeBytes]: a private key is never there with a wider mode.
    public static void copyFile(Path from, Path to, PosixMode mode) throws IOException {
        writeBytes(to, Files.readAllBytes(from), mode);
    }

    /// Points `link` at `target`, replacing anything else at that path, such as a link
    /// left by a crashed session that points somewhere else.
    public static void createSymlink(Path link, Path target) throws IOException {
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(target)) return;
            Files.delete(link);
        } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(link);
        }
        Path parent = link.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createSymbolicLink(link, target);
    }

    public static void setMode(Path path, PosixMode mode) throws IOException {
        Files.setPosixFilePermissions(path, mode.permissions());
    }

    public static boolean exists(Path path) { return Files.exists(path); }

    public static Optional<String> readString(Path path) {
        try {
            return Files.isReadable(path) ? Optional.of(Files.readString(path)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /// A file's bytes exactly as they are; empty when there is no file.
    public static Optional<byte[]> readBytes(Path path) throws IOException {
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    public static void deleteIfPresent(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /// Deletes a tree as far as this user is allowed to, and reports what survived.
    ///
    /// It does not stop at the first refusal, so the caller can tell the user which paths could
    /// not be deleted (usually files owned by the infra user).
    ///
    /// Symlinks are deleted, never followed. The runtime directory holds a link into the lamp, and
    /// following it would delete the lamp's contents.
    public static Tuple<Path> deleteTree(Path root) {
        Tuple<Path> survivors = Tuple.of(Path.class);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return survivors;
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> children = Files.list(root)) {
                for (Path child : children.toList()) survivors = survivors.addAll(deleteTree(child));
            } catch (IOException unreadable) {
                return survivors.add(root);
            }
        }
        try {
            Files.deleteIfExists(root);
        } catch (IOException refused) {
            survivors = survivors.add(root);
        }
        return survivors;
    }

    /// Every `.mkv` file in a recordings directory, sorted by name (which is by time).
    ///
    /// The file is created when recording starts and written until it stops, so its creation and
    /// modification times give its duration without opening it or needing `ffprobe`.
    ///
    /// @return the recordings; empty when the directory does not exist or cannot be read
    public static Tuple<RecordingFile> listRecordings(Path directory) {
        Tuple<RecordingFile> found = Tuple.of(RecordingFile.class);
        if (!Files.isDirectory(directory)) return found;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                if (!entry.toString().endsWith(".mkv")) continue;
                BasicFileAttributes attributes =
                        Files.readAttributes(entry, BasicFileAttributes.class);
                found = found.add(new RecordingFile(entry, startOf(entry, attributes),
                        attributes.lastModifiedTime().toInstant(), attributes.size()));
            }
        } catch (IOException e) {
            return found;
        }
        return found;
    }

    /// When the recording began: the file's creation time where the filesystem keeps one. Where it
    /// does not, Java returns the modification time instead, which would make every recording zero
    /// seconds long, so the session start in the file name is used. That is a few seconds early,
    /// because the sandbox starts before the recorder.
    private static Instant startOf(Path file, BasicFileAttributes attributes) {
        Instant created = attributes.creationTime().toInstant();
        if (created.isBefore(attributes.lastModifiedTime().toInstant())) return created;
        Path name = file.getFileName();
        return name == null ? created
                : SessionId.parse(name.toString().replaceFirst("\\.mkv$", ""))
                           .map(SessionId::startedAt)
                           .orElse(created);
    }

    /// Lists a directory for [LampDirectoryUtil].
    public static DirListing list(Path path) {
        if (!Files.exists(path)) return DirListing.missing();
        if (!Files.isDirectory(path) || !Files.isReadable(path))
            return new DirListing(true, false, Tuple.of(String.class));
        try (Stream<Path> entries = Files.list(path)) {
            Tuple<String> names = Tuple.of(String.class);
            for (Path entry : entries.sorted().toList()) {
                Path fileName = entry.getFileName();
                if (fileName != null) names = names.add(fileName.toString());
            }
            return new DirListing(true, true, names);
        } catch (IOException e) {
            return new DirListing(true, false, Tuple.of(String.class));
        }
    }
}
