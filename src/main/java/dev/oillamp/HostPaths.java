package dev.oillamp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.lamp.LampProblem;

import sprouts.Tuple;

/// Resolves the paths a host request names, on this host, and decides whether the lamp's
/// allowlists contain them.
///
/// The agent writes the path, so nothing about it is trusted. A leading `~` is the user's home;
/// any other path must be absolute, and may not contain `.` or `..`, which would make the path
/// that is judged differ from the one the kernel walks. Every part of the way that exists is
/// resolved with its links followed. The first part that does not exist yet is appended as
/// written, so a file that is about to be created still gets a path that can be checked.
///
/// This is the one class of the feature that reads the filesystem, and it reads nothing but the
/// resolution of a path: no file's content, no directory's entries.
final class HostPaths {

    private HostPaths() {}

    /// A path the tool will touch, resolved on the host.
    ///
    /// @param asked    the path as the agent wrote it, before it was resolved
    /// @param hostPath the absolute path on the host it stands for, with every link followed
    record ResolvedPath(String asked, Path hostPath) {}

    /// `~` is the home directory it is given, not the shell's idea of it.
    static Path expand(String asked, Path home) {
        String text = asked.strip();
        if (text.equals("~")) return home;
        if (text.startsWith("~/")) return home.resolve(text.substring(2));
        return Path.of(text);
    }

    /// What a request does with a path, which decides the allowlist it must be in.
    enum Use {
        /// Reads it, which a folder of either allowlist allows: changing implies reading.
        READ,
        /// Changes it, or runs a command in it, which only a folder of `change_allowlist` allows.
        CHANGE;

        /// The folders that allow this use of a path, as the lamp's file writes them.
        Tuple<String> allowedBy(LampConfig.HostAccess access) {
            return switch (this) {
                case READ -> access.readAllowlist().addAll(access.changeAllowlist());
                case CHANGE -> access.changeAllowlist();
            };
        }
    }

    /// Resolves a path on the host, without asking whether the allowlists contain it.
    ///
    /// @return the resolved path, or why it is not one oillamp can judge at all
    static Result<ResolvedPath> locate(String asked, Path home) {
        Path expanded = expand(asked, home);
        if (!expanded.isAbsolute())
            return Result.err(HostAccessProblems.outsideTheAllowlists(asked, "it is not an absolute path"));
        for (Path part : expanded)
            if (part.toString().equals("..") || part.toString().equals("."))
                return Result.err(HostAccessProblems.outsideTheAllowlists(asked,
                        "it contains \"" + part + "\"; name the place itself"));
        Path existing = expanded;
        List<String> missing = new ArrayList<>();
        // Walk up to the first part that exists, remembering what was walked over. The names are
        // put back in the order they were read: `Documents/notes/today.md`, not today/notes.
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) missing.add(0, name.toString());
            existing = existing.getParent();
        }
        if (existing == null)
            return Result.err(HostAccessProblems.outsideTheAllowlists(asked, "nothing on the way exists"));
        Path real;
        try {
            real = existing.toRealPath();
        } catch (java.io.IOException e) {
            return Result.err(HostAccessProblems.outsideTheAllowlists(asked, ProblemCatalogUtil.reason(e)));
        }
        Path resolved = real;
        for (String name : missing) resolved = resolved.resolve(name);
        return Result.ok(new ResolvedPath(asked, resolved));
    }

    /// Whether a resolved path is one of `folders` or under one, compared name by name, so that
    /// `/a/workspace2` is not under `/a/workspace`. The folders have their links followed too, so
    /// that a folder given through a link and a path resolved through it compare equal.
    ///
    /// @param folders as the lamp's file writes them: `~`, under `~/`, or absolute
    static boolean within(Path resolved, Path home, Tuple<String> folders) {
        for (String folder : folders)
            if (resolved.startsWith(real(expand(folder, home)))) return true;
        return false;
    }

    /// `path` with its links followed, or as written when it does not exist.
    static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
