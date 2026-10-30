package dev.gui.genie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import dev.gui.model.Home;
import dev.lamp.Lamp;

import sprouts.Tuple;

/// A genie's home on this computer, and links to its folders in the user's own.
///
/// The genie writes its home, so everything here reads it as the genie's work, as [Lamp#agentHome]
/// asks: no link in it is ever followed, and no name in it is trusted to be a plain name. A
/// folder is reached only through folders, so a link the genie made, to anywhere on this
/// computer, is neither shown nor linked to.
public final class GenieHomeUtil {

    private GenieHomeUtil() {}

    /// How many folders of one folder are read. A folder with more, such as one a build filled,
    /// shows the first of them, by name.
    static final int MOST_FOLDERS = 500;

    /// The genie's home in the lamp at `lamp`; nothing before it first woke.
    public static Optional<Path> home(Path lamp) {
        return Files.isDirectory(lamp, LinkOption.NOFOLLOW_LINKS) ? Lamp.agentHome(lamp) : Optional.empty();
    }

    /// The folder at `path`, by names below `home`, if every step to it is a folder and none is a link.
    public static Optional<Path> folder(Path home, Tuple<String> path) {
        Path at = home;
        for (String name : path) {
            if (!GenieFileTransferUtil.isPlainName(name)) return Optional.empty();
            at = at.resolve(name);
            if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        }
        return Optional.of(at);
    }

    /// The folder at `path` below `home` and the folders in it, each with the folders in it:
    /// two levels, so that each folder shown knows whether it can be opened.
    ///
    /// @throws IOException when there is no such folder
    public static Home.Folder read(Path home, Tuple<String> path) throws IOException {
        Path folder = folder(home, path).orElseThrow(() -> new IOException("there is no folder " + String.join("/", path) + " in " + home));
        Tuple<Home.Folder> folders = Tuple.of(Home.Folder.class);
        for (Path child : foldersIn(folder)) {
            Tuple<Home.Folder> inside = Tuple.of(Home.Folder.class);
            for (Path grandchild : foldersIn(child)) inside = inside.add(Home.Folder.empty(grandchild.getFileName().toString()));
            folders = folders.add(new Home.Folder(child.getFileName().toString(), inside));
        }
        return new Home.Folder(path.isEmpty() ? Home.HOME : path.last(), folders);
    }

    /// The folders in `folder`, by name, leaving out links, those whose name starts with a dot,
    /// and any past [#MOST_FOLDERS]. None, when it cannot be read.
    private static List<Path> foldersIn(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(it -> GenieFileTransferUtil.isPlainName(it.getFileName().toString()) && !it.getFileName().toString().startsWith("."))
                          .filter(it -> Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS))
                          .sorted(Comparator.comparing((Path it) -> it.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                          .limit(MOST_FOLDERS)
                          .toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /// Puts a link to `target` into the folder `into`, called `name`, making `into` if it is not
    /// there. A link of that name to `target` is kept as it is. When the name is taken by
    /// something else, the link is called `name 2`, or `name 3`, and so on.
    ///
    /// @throws IOException when `name` is no plain name, or the link could not be made
    public static Home.Link link(Path target, Path into, String name) throws IOException {
        String wanted = name.strip();
        if (!GenieFileTransferUtil.isPlainName(wanted))
            throw new IOException("A link needs a name without a slash, such as \"" + target.getFileName() + "\".");
        Files.createDirectories(into);
        for (int number = 1; ; number++) {
            Path link = into.resolve(number == 1 ? wanted : wanted + " " + number);
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).equals(target)) return new Home.Link(link, false);
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.createSymbolicLink(link, target);
            return new Home.Link(link, true);
        }
    }

    /// The user's documents folder: the one `~/.config/user-dirs.dirs` names, as the desktop's
    /// file manager knows it, or `~/Documents`.
    ///
    /// @param environment the environment Genies was started from, by variable
    public static Path documents(Function<String, Optional<String>> environment) {
        Path home = environment.apply("HOME").filter(it -> !it.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home")));
        Path config = environment.apply("XDG_CONFIG_HOME").filter(it -> !it.isBlank()).map(Path::of)
                .orElseGet(() -> home.resolve(".config"));
        try {
            for (String line : Files.readAllLines(config.resolve("user-dirs.dirs"), StandardCharsets.UTF_8)) {
                Optional<Path> named = documentsIn(line.strip(), home);
                if (named.isPresent()) return named.get();
            }
        } catch (IOException missing) {
            // The usual place, then.
        }
        return home.resolve("Documents");
    }

    /// The folder a line of `user-dirs.dirs` names, if it is the documents': such as
    /// `XDG_DOCUMENTS_DIR="$HOME/Dokumente"`. A line naming the home itself, which is how that
    /// file turns a folder off, names none.
    static Optional<Path> documentsIn(String line, Path home) {
        String prefix = "XDG_DOCUMENTS_DIR=\"";
        if (!line.startsWith(prefix) || !line.endsWith("\"")) return Optional.empty();
        String value = line.substring(prefix.length(), line.length() - 1);
        Path folder = value.startsWith("$HOME/") ? home.resolve(value.substring("$HOME/".length()))
                    : value.startsWith("/") ? Path.of(value) : home.resolve(value);
        return folder.normalize().equals(home.normalize()) ? Optional.empty() : Optional.of(folder);
    }

    /// Opens `folder` in this computer's file manager, with `xdg-open`, without waiting for it.
    public static void showInFileManager(Path folder) throws IOException {
        new ProcessBuilder("xdg-open", folder.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }
}
