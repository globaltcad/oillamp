package dev.gui.model;

import java.nio.file.Path;
import java.util.Optional;

import sprouts.HasId;
import sprouts.Tuple;

/// A genie's home as its Actions page shows it: where it is on this computer, and the folders in
/// it, as far as the user opened them.
///
/// The home is a folder in the genie's lamp, which its sandbox sees as `/home/agent`. The user
/// can link any folder of it into a folder of their own, such as their documents, to find what
/// the genie made from their file manager. A link is all it is: Genies keeps no list of them, and
/// one whose genie was deleted points nowhere.
///
/// Only folders are shown, since only they are linked, and none whose name starts with a dot,
/// such as the tools' `.pi` and `.sdkman`. Nor a link the genie made: it could point anywhere on
/// this computer, and the tree would show that as the genie's.
///
/// @param read  whether Genies looked for the home yet
/// @param where the home on this computer; nothing before the genie first woke, as its lamp
///              makes it then
/// @param root  the home's folders, each with the folders in it as far as they were read
public record Home(boolean read, Optional<Path> where, Folder root) {

    /// What the root of the tree is called, the home itself.
    public static final String HOME = "Home";

    public static final Home UNREAD = new Home(false, Optional.empty(), Folder.empty(HOME));

    /// The folder the genie works in, which the Actions page offers first.
    public static final Tuple<String> WORKSPACE = Tuple.of(String.class, "workspace");

    /// A folder, and the folders in it as far as they were read: a folder whose folders were not
    /// read yet has none.
    public record Folder(String name, Tuple<Folder> folders) implements HasId<String> {

        public static Folder empty(String name) { return new Folder(name, Tuple.of(Folder.class)); }

        @Override public String id() { return name; }

        public Optional<Folder> find(String name) {
            return folders.stream().filter(it -> it.name().equals(name)).findFirst();
        }

        /// The folder at `path`, by names from this one down; this one for an empty path.
        public Optional<Folder> at(Tuple<String> path) {
            Optional<Folder> at = Optional.of(this);
            for (String name : path) at = at.flatMap(it -> it.find(name));
            return at;
        }

        /// This folder as `fresh` read it, two levels deep, keeping what was read below that
        /// before: folders the user opened stay open.
        Folder readAgain(Folder fresh) {
            return new Folder(name, fresh.folders().map(child -> find(child.name())
                    .map(before -> new Folder(child.name(), child.folders().map(grandchild ->
                            before.find(grandchild.name()).orElse(grandchild))))
                    .orElse(child)));
        }

        /// With the folder at `path` read again as `fresh`. Folders on the way to it that were
        /// not read yet are added, after the others, until their parent is read.
        Folder withReadAt(Tuple<String> path, Folder fresh) {
            if (path.isEmpty()) return readAgain(fresh);
            Optional<Folder> child = find(path.first());
            Folder read = child.orElse(empty(path.first())).withReadAt(path.removeFirst(), fresh);
            return new Folder(name, child.isPresent() ? folders.setAt(folders.firstIndexOf(child.get()), read) : folders.add(read));
        }
    }

    /// A link Genies put into a folder of the user's.
    ///
    /// @param at      where the link is
    /// @param madeNow whether Genies made it now; otherwise, the same link was there already
    public record Link(Path at, boolean madeNow) {}

    /// The home found at `where`, before any of its folders were read.
    public static Home at(Optional<Path> where) {
        return new Home(true, where, Folder.empty(HOME));
    }

    /// With the folder at `path`, by names below the home, read again as `fresh`: its folders
    /// and theirs.
    public Home withRead(Optional<Path> where, Tuple<String> path, Folder fresh) {
        Folder root = where.equals(this.where) ? this.root : Folder.empty(HOME);
        return new Home(true, where, root.withReadAt(path, fresh));
    }

    /// What a link to the folder at `path` is called unless the user names it: the genie's name
    /// and the folder's, such as "Juniper - workspace". A slash, which no name may hold, becomes
    /// a dash.
    public static String linkName(String genie, Tuple<String> path) {
        String folder = path.isEmpty() ? HOME : path.last();
        return (genie.strip() + " - " + folder).replace('/', '-');
    }
}
