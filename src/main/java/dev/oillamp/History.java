package dev.oillamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import dev.lamp.LampBranch;
import dev.lamp.LampSaveKind;
import dev.lamp.LampProblem;
import dev.lamp.LampSave;
import dev.lamp.LampSaveOnBranches;
import dev.lamp.LampEvent;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// A lamp's history: snapshots of its agent directory, its `oillamp.toml` and its schedule, kept
/// as a git repository in `.oillamp/history`.
///
/// Each snapshot is one commit. Its tree mirrors the lamp: the agent directory under its own name,
/// `oillamp.toml`, `.oillamp/schedule.json`, and `.oillamp-modes`, a list of the exact
/// permissions git cannot hold (git knows only "executable or not", and ssh refuses a private key
/// that others may read). Each commit names the one before it as its parent.
///
/// A **branch** is a file under `refs/heads/` that names the newest commit of one line of them,
/// as git keeps it; git may also have moved it into `packed-refs`. The first branch is `main`.
/// `HEAD` says which branch the lamp is on, as `ref: refs/heads/<name>`, and every save goes to
/// that one. A restore never removes a commit: it adds one whose tree is the older snapshot's,
/// with the schedule the lamp kept when that snapshot is older than snapshots that hold one. A
/// switch brings the lamp to another branch's newest snapshot and points `HEAD` at that branch. A
/// reset moves the current branch back to one of its older snapshots, and a deleted branch's file
/// is removed: the commits that then lie on no branch stay where they are.
///
/// Everything here is written in git's own format by this class, not by running `git`; why is
/// explained in [GitObjectUtil]. Objects are only ever added, each written to a temporary file and
/// then renamed, and a branch is moved last, the same way. A save that is interrupted, even by a
/// power cut, leaves the history as it was, plus some objects nothing refers to.
///
/// The history is outside the agent directory and never mounted into the container, so the agent
/// cannot rewrite its own past.
final class History {

    /// The name of the list of exact permissions, at the top of every snapshot's tree.
    static final String MODES_FILE = ".oillamp-modes";
    static final String CONFIG_FILE = "oillamp.toml";
    /// Where the schedule is in a snapshot's tree: `.oillamp/schedule.json`, as in the lamp. The
    /// rest of `.oillamp/` is never saved. A snapshot made before the schedule was saved with it
    /// has no `.oillamp` at all.
    static final String STATE_DIR = ".oillamp";
    static final String SCHEDULE_FILE = "schedule.json";
    /// The branch a history starts on, and the only one of a lamp saved before there were others.
    static final String FIRST_BRANCH = "main";
    /// Where git keeps the branches, each a file named after its branch.
    private static final String HEADS = "refs/heads/";
    /// What the last save found of each file, in the history: see [FileStats].
    private static final String FILE_STATS = "file-stats";
    /// The longest name a branch can be given.
    static final int LONGEST_BRANCH_NAME = 64;
    /// How long a save waits for another save or restore of the same lamp to finish.
    private static final Duration LOCK_WAIT = Duration.ofMinutes(10);
    /// Files up to this size are read into memory once. Larger ones are read twice, once to name
    /// them and once to store them, and never held in memory whole.
    private static final long SMALL_FILE = 4L * 1024 * 1024;

    private final LampLayout layout;
    private final Path repository;
    private final LampConfig.History policy;
    private final java.util.function.Consumer<LampEvent> sink;
    private final boolean stopped;

    History(LampLayout layout) {
        this(layout, ConfigDefaultsUtil.lampConfig().history(), _ -> { });
    }

    History(LampLayout layout, LampConfig.History policy, java.util.function.Consumer<LampEvent> sink) {
        this(layout, policy, sink, false);
    }

    History(LampLayout layout, LampConfig.History policy, java.util.function.Consumer<LampEvent> sink, boolean stopped) {
        this.layout = layout;
        this.repository = layout.historyDir();
        this.policy = policy;
        this.sink = sink;
        this.stopped = stopped;
    }

    /// What a save did.
    ///
    /// @param made    the new snapshot, or empty when nothing had changed since the last one
    /// @param latest  the newest snapshot now: the one made, or the unchanged one before it.
    ///                Empty only for a lamp that has never been saved and holds nothing
    /// @param files   how many files the snapshot holds
    /// @param skipped paths left out, because they could not be read or kept changing
    record Saving(Optional<LampSave> made, Optional<LampSave> latest, int files, Tuple<String> skipped) {}

    /// What a restore did.
    ///
    /// @param safety the save made just before, so the restore can be undone; empty when the lamp
    ///               had not changed since its last snapshot, which is then the way back
    /// @param result the snapshot that records the restore; empty when the lamp already was as
    ///               `target` holds it
    record Restoring(LampSave target, Optional<LampSave> safety, Optional<LampSave> result,
                     Tuple<String> skipped) {}

    /// What a switch to another branch did.
    ///
    /// @param from   the branch the lamp was on
    /// @param to     the branch the lamp is on now; the same as `from` when it already was on it,
    ///               and then nothing changed
    /// @param safety the save made on `from` just before, so that nothing on it is lost; empty
    ///               when the lamp had not changed since that branch's newest snapshot
    record Switching(String from, LampBranch to, Optional<LampSave> safety, Tuple<String> skipped) {}

    /// What a reset did, or would do when it was not confirmed.
    ///
    /// @param branch  the branch that goes back, the current one
    /// @param dropped how many snapshots made after `target` are no longer on the branch
    /// @param unsaved whether the lamp had changes that no snapshot held
    record Resetting(String branch, LampSave target, int dropped, boolean unsaved, Tuple<String> skipped) {}

    // ─── the three operations ──────────────────────────────────────────────────────────────

    /// Saves the lamp as it is now, unless nothing changed since the last snapshot.
    Result<Saving> save(LampSaveKind kind, String message, Optional<SessionId> session, Instant now) {
        return save(kind, message, session, Association.between(String.class, String.class), false, now);
    }

    /// Saves the lamp as it is now.
    ///
    /// @param trailers more trailers for the commit's message, such as the run it belongs to
    /// @param always   whether to make a snapshot even when nothing changed. A run's last
    ///                 snapshot is made either way, because it records what the agent said
    Result<Saving> save(LampSaveKind kind, String message, Optional<SessionId> session,
                        Association<String, String> trailers, boolean always, Instant now) {
        return lockedThenReport(() -> {
            try {
                Saving saving = saveUnlocked(kind, message, session, trailers, always, now);
                return new Done<>(Result.ok(saving),
                        saving.made().isPresent() ? maintain(now, ValueSet.of(String.class)) : Tuple.of(LampEvent.class));
            } catch (IOException | RuntimeException e) {
                return new Done<>(Result.err(ProblemCatalogUtil.saveFailed(layout.root(), ProblemCatalogUtil.reason(e))),
                        Tuple.of(LampEvent.class));
            }
        });
    }

    /// Every commit on the current branch, newest first, with the trailers that say what made each
    /// one.
    Result<Tuple<GitObjectUtil.Commit>> commitList() {
        return reading(() -> Result.ok(commits()));
    }

    /// One path that differs between two snapshots.
    ///
    /// @param kind `added`, `changed` or `removed`
    /// @param path as the agent sees it, such as `~/workspace/app.py`; a directory that is new or
    ///             gone as a whole ends in `/` and is listed once, not file by file
    record Change(String kind, String path) {}

    /// What differs between two snapshots' trees, as far as `limit` paths.
    ///
    /// @param more whether there were more than `limit`
    record Changes(Tuple<Change> shown, boolean more) {}

    /// Lists what differs from the tree `before` (or from nothing) to the tree `after`.
    Result<Changes> changes(Optional<String> before, String after, int limit) {
        return reading(() -> {
            List<Change> found = new ArrayList<>();
            boolean more = compare(before, Optional.of(after), "", found, limit, true);
            return Result.ok(new Changes(Tuple.of(Change.class, found), more));
        });
    }

    /// Adds what differs between two trees to `found`. True once there were more than `limit`.
    private boolean compare(Optional<String> before, Optional<String> after, String prefix,
                            List<Change> found, int limit, boolean top) throws IOException {
        Association<String, GitObjectUtil.Entry> old = entriesOf(before);
        Association<String, GitObjectUtil.Entry> now = entriesOf(after);
        SortedSet<String> names = new TreeSet<>();
        for (GitObjectUtil.Entry entry : old.values()) names.add(entry.name());
        for (GitObjectUtil.Entry entry : now.values()) names.add(entry.name());
        for (String name : names) {
            // Neither is a file of the agent's: one is oillamp's list of permissions, and the
            // other the schedule, which changes with every run of a job.
            if (top && (name.equals(MODES_FILE) || name.equals(STATE_DIR))) continue;
            // The agent's home, whatever its name in the lamp, is `~` to the agent.
            String path = top && name.startsWith(LampLayout.AGENT_DIR_PREFIX) ? "~"
                        : top ? name : prefix + "/" + name;
            Optional<GitObjectUtil.Entry> was = old.get(name);
            Optional<GitObjectUtil.Entry> is = now.get(name);
            if (was.equals(is)) continue;
            boolean wasDirectory = was.filter(GitObjectUtil.Entry::isDirectory).isPresent();
            boolean isDirectory = is.filter(GitObjectUtil.Entry::isDirectory).isPresent();
            if (wasDirectory && isDirectory) {
                if (compare(was.map(GitObjectUtil.Entry::id), is.map(GitObjectUtil.Entry::id), path, found, limit, false))
                    return true;
                continue;
            }
            if (found.size() >= limit) return true;
            if (was.isEmpty()) found.add(new Change("added", path + (isDirectory ? "/" : "")));
            else if (is.isEmpty()) found.add(new Change("removed", path + (wasDirectory ? "/" : "")));
            else found.add(new Change("changed", path + (isDirectory ? "/" : "")));
        }
        return false;
    }

    /// Every snapshot on the current branch, newest first. Empty for a lamp that has never been
    /// saved.
    Result<Tuple<LampSave>> snapshots() {
        return reading(() -> {
            Tuple<LampSave> found = Tuple.of(LampSave.class);
            for (GitObjectUtil.Commit commit : commits()) found = found.add(commit.snapshot());
            return Result.ok(found);
        });
    }

    /// Brings the lamp back to the snapshot whose id starts with `prefix`, which must be on the
    /// current branch: a restore does not put another branch's state on top of this one's.
    ///
    /// Saves the lamp first, so the restore itself can be undone. Only call it while no session
    /// runs: the caller holds the lamp's lock.
    Result<Restoring> restore(String prefix, Instant now) {
        return lockedThenReport(() -> {
            Result<Restoring> restored = restoreUnlocked(prefix, now);
            // The target is kept, and with it every snapshot from it to the branch's newest, the
            // save just before among them, so that the restore can still be undone.
            if (restored instanceof Result.Ok<Restoring> ok
                    && (ok.value().safety().isPresent() || ok.value().result().isPresent()))
                return new Done<>(restored, maintain(now, ValueSet.of(ok.value().target().id())));
            return new Done<>(restored, Tuple.of(LampEvent.class));
        });
    }

    private Result<Restoring> restoreUnlocked(String prefix, Instant now) {
        Tuple<GitObjectUtil.Commit> commits;
        Result<LampSave> found;
        try {
            commits = commits();
            found = onThisBranch(commits, prefix, "restore");
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
        if (!(found instanceof Result.Ok<LampSave> ok)) return Result.err(found.problems());
        LampSave target = ok.value();
        GitObjectUtil.Commit wanted = commits.stream().filter(c -> c.id().equals(target.id())).findFirst().orElseThrow();

        Saving safety;
        try {
            safety = saveUnlocked(LampSaveKind.BEFORE_RESTORE, "before restoring " + target.shortId(),
                                  Optional.empty(), now);
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.saveFailed(layout.root(),
                    "the save before the restore failed, so nothing was restored: " + ProblemCatalogUtil.reason(e)));
        }
        // The newest snapshot now holds exactly what is on disk, apart from any skipped files.
        GitObjectUtil.Commit current;
        try {
            current = commits().first();
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
        Tuple<LampProblem> warnings = safety.skipped().isEmpty() ? Tuple.of(LampProblem.class)
                : Tuple.of(LampProblem.class, ProblemCatalogUtil.filesNotSaved(layout.root(), safety.skipped()));
        String restored;
        try {
            restored = withScheduleOf(wanted.tree(), current.tree());
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
        if (current.tree().equals(restored))
            return Result.ok(new Restoring(target, safety.made(), Optional.empty(), safety.skipped()), warnings);
        try {
            bringBack(restored, current.tree());
            String message = GitObjectUtil.message(LampSaveKind.RESTORE, "back to " + target.shortId()
                    + " (" + target.kind().label() + ", " + target.at() + ")", Optional.empty());
            LampSave result = commit(restored, Optional.of(current.id()), now, message);
            return Result.ok(new Restoring(target, safety.made(), Optional.of(result), safety.skipped()), warnings);
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.restoreFailed(layout.root(), ProblemCatalogUtil.reason(e),
                    current.snapshot().shortId()));
        }
    }

    /// The snapshot on the current branch, whose commits are `line`, that `prefix` names. One that
    /// only other branches hold is refused, with how to reach it: by starting a branch at it and
    /// switching to that.
    ///
    /// @param action `restore` or `reset`, for the problem
    private Result<LampSave> onThisBranch(Tuple<GitObjectUtil.Commit> line, String prefix, String action)
            throws IOException {
        Result<LampSave> here = GitObjectUtil.find(snapshotsOf(line), prefix, layout.root());
        if (here.isOk()) return here;
        Result<LampSave> anywhere = GitObjectUtil.find(snapshotsOf(allCommits()), prefix, layout.root());
        if (!(anywhere instanceof Result.Ok<LampSave> ok)) return anywhere;
        Tuple<String> holders = Tuple.of(String.class);
        for (String name : branchNames())
            if (line(ref(name)).stream().anyMatch(commit -> commit.id().equals(ok.value().id()))) holders = holders.add(name);
        return Result.err(ProblemCatalogUtil.notOnThisBranch(ok.value(), headBranch(), holders, layout.root(), action));
    }

    private static Tuple<LampSave> snapshotsOf(Tuple<GitObjectUtil.Commit> commits) {
        Tuple<LampSave> snapshots = Tuple.of(LampSave.class);
        for (GitObjectUtil.Commit commit : commits) snapshots = snapshots.add(commit.snapshot());
        return snapshots;
    }

    // ─── branches ──────────────────────────────────────────────────────────────────────────

    /// Why `name` cannot be a new branch's name, or empty when it can. A name is made of lowercase
    /// letters, digits, `-`, `_` and `.`, begins with a letter or a digit, is at most 64 characters
    /// long, and neither holds `..` nor ends in `.` or `.lock`, which git refuses. Lowercase only,
    /// so that no two branches differ by case alone, which some file systems cannot tell apart.
    static Optional<String> branchNameProblem(String name) {
        if (name.length() > LONGEST_BRANCH_NAME)
            return Optional.of("a branch's name is at most " + LONGEST_BRANCH_NAME + " characters long");
        if (!name.matches("[a-z0-9][a-z0-9._-]*"))
            return Optional.of("a branch's name is made of lowercase letters, digits, '-', '_' and '.', "
                             + "and begins with a letter or a digit");
        if (name.contains("..") || name.endsWith(".") || name.endsWith(".lock"))
            return Optional.of("a branch's name cannot hold '..', or end in '.' or '.lock', which git refuses");
        return Optional.empty();
    }

    /// The branch the lamp is on: the one every save goes to. `main` for a lamp never saved.
    Result<String> currentBranch() {
        return reading(() -> Result.ok(headBranch()));
    }

    /// Every branch, in alphabetical order, with its newest snapshot. The current branch is among
    /// them even before its first snapshot.
    Result<Tuple<LampBranch>> branches() {
        return reading(() -> {
            String current = headBranch();
            SortedSet<String> names = branchNames();
            names.add(current);
            Tuple<LampBranch> found = Tuple.of(LampBranch.class);
            for (String name : names) {
                Optional<String> tip = ref(name);
                found = found.add(new LampBranch(name, name.equals(current),
                        tip.isEmpty() ? Optional.empty() : Optional.of(commitAt(tip.get()).snapshot())));
            }
            return Result.ok(found);
        });
    }

    /// Every commit on any branch, newest first, each once. Runs are numbered and counted from
    /// these, because every run on every branch was a run of this lamp's agent.
    Result<Tuple<GitObjectUtil.Commit>> commitsOfAllBranches() {
        return reading(() -> Result.ok(allCommits()));
    }

    /// Every snapshot on any branch, newest first, each once, with the branches it is on.
    Result<Tuple<LampSaveOnBranches>> snapshotsOfAllBranches() {
        return reading(() -> {
            Tuple<LampSaveOnBranches> found = Tuple.of(LampSaveOnBranches.class);
            for (OnBranches listed : everyCommit())
                found = found.add(new LampSaveOnBranches(listed.commit().snapshot(), Tuple.of(String.class, listed.branches())));
            return Result.ok(found);
        });
    }

    /// Starts the branch `name` at the snapshot whose id starts with `prefix`, which may be on any
    /// branch, or without one at the current branch's newest snapshot. The lamp stays on the branch
    /// it is on. Only the new branch's file is written, so this works while a session runs.
    Result<LampBranch> createBranch(String name, Optional<String> prefix) {
        Optional<String> wrong = branchNameProblem(name);
        if (wrong.isPresent())
            return Result.err(ProblemCatalogUtil.usage("the branch name '" + name + "': " + wrong.get(),
                    Command.usageOf("history <dir> branch")));
        return locked(() -> {
            try {
                if (branchNames().contains(name) || headBranch().equals(name))
                    return Result.err(ProblemCatalogUtil.branchExists(name, layout.root()));
                LampSave at;
                if (prefix.isPresent()) {
                    Result<LampSave> found = GitObjectUtil.find(snapshotsOf(allCommits()), prefix.get(), layout.root());
                    if (!(found instanceof Result.Ok<LampSave> ok)) return Result.err(found.problems());
                    at = ok.value();
                } else {
                    Optional<GitObjectUtil.Commit> head = headCommit();
                    if (head.isEmpty())
                        return Result.err(ProblemCatalogUtil.nothingToBranchFrom(name, headBranch(), layout.root()));
                    at = head.get().snapshot();
                }
                writeRef(name, at.id());
                return Result.ok(new LampBranch(name, false, Optional.of(at)));
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
            }
        });
    }

    /// Deletes the branch `name`, or with `confirmed` false only counts what it alone holds. The
    /// current branch cannot be deleted. Only the branch's file goes; its snapshots stay in the
    /// history's files, and those on no other branch are then on none.
    ///
    /// @return how many snapshots are on `name` and on no other branch
    Result<Integer> deleteBranch(String name, boolean confirmed) {
        return locked(() -> {
            try {
                SortedSet<String> names = branchNames();
                if (!names.contains(name))
                    return Result.err(ProblemCatalogUtil.noSuchBranch(name, layout.root(), Tuple.of(String.class, names)));
                if (name.equals(headBranch()))
                    return Result.err(ProblemCatalogUtil.branchIsCurrent(name, layout.root()));
                Set<String> elsewhere = new HashSet<>();
                for (String other : names)
                    if (!other.equals(name))
                        for (GitObjectUtil.Commit commit : line(ref(other))) elsewhere.add(commit.id());
                int only = (int) line(ref(name)).stream().filter(commit -> !elsewhere.contains(commit.id())).count();
                if (confirmed) deleteRef(name);
                return Result.ok(only);
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
            }
        });
    }

    /// Switches the lamp to the branch `name`. Saves the lamp on the branch it is on first, if
    /// anything changed since that branch's newest snapshot, so that nothing is lost. Then brings
    /// the agent directory, `oillamp.toml` and the schedule to `name`'s newest snapshot, the way a
    /// restore does, skipping every directory that is the same in both, and points `HEAD` at
    /// `name`. No snapshot is added to `name`.
    ///
    /// Only call it while no session runs: the caller holds the lamp's lock.
    Result<Switching> switchTo(String name, Instant now) {
        return lockedThenReport(() -> {
            Result<Switching> switched = switchUnlocked(name, now);
            // Every branch's newest snapshot is kept anyway, so the switch needs nothing more kept.
            if (switched instanceof Result.Ok<Switching> ok && ok.value().safety().isPresent())
                return new Done<>(switched, maintain(now, ValueSet.of(String.class)));
            return new Done<>(switched, Tuple.of(LampEvent.class));
        });
    }

    private Result<Switching> switchUnlocked(String name, Instant now) {
        String from;
        SortedSet<String> names;
        try {
            from = headBranch();
            names = branchNames();
            if (name.equals(from))
                return Result.ok(new Switching(from, new LampBranch(from, true,
                        headCommit().map(GitObjectUtil.Commit::snapshot)), Optional.empty(), Tuple.of(String.class)));
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
        if (!names.contains(name))
            return Result.err(ProblemCatalogUtil.noSuchBranch(name, layout.root(), Tuple.of(String.class, names)));

        Saving safety;
        try {
            safety = saveUnlocked(LampSaveKind.BEFORE_SWITCH, "before switching to " + name, Optional.empty(), now);
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), "the save before the switch failed, "
                    + "so the lamp is still on " + from + ": " + ProblemCatalogUtil.reason(e)));
        }
        Tuple<LampProblem> warnings = safety.skipped().isEmpty() ? Tuple.of(LampProblem.class)
                : Tuple.of(LampProblem.class, ProblemCatalogUtil.filesNotSaved(layout.root(), safety.skipped()));
        // The newest snapshot of `from` now holds exactly what is on disk, apart from any skipped files.
        GitObjectUtil.Commit current;
        GitObjectUtil.Commit wanted;
        String switched;
        try {
            current = headCommit().orElseThrow();
            wanted = commitAt(ref(name).orElseThrow());
            switched = withScheduleOf(wanted.tree(), current.tree());
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
        try {
            if (!switched.equals(current.tree())) bringBack(switched, current.tree());
            writeHead(name);
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.switchFailed(layout.root(), ProblemCatalogUtil.reason(e),
                    from, current.snapshot().shortId()));
        }
        return Result.ok(new Switching(from, new LampBranch(name, true, Optional.of(wanted.snapshot())),
                safety.made(), safety.skipped()), warnings);
    }

    /// Brings the lamp and its current branch back to the snapshot whose id starts with `prefix`,
    /// which must be on that branch, as `git reset --hard` does: the snapshots made after it are no
    /// longer on the branch, and changes that no snapshot holds are thrown away. Nothing is saved
    /// first. With `confirmed` false nothing changes, and the result says what would be lost.
    ///
    /// Only call it while no session runs: the caller holds the lamp's lock.
    Result<Resetting> reset(String prefix, boolean confirmed) {
        return locked(() -> {
            String branch;
            Tuple<GitObjectUtil.Commit> line;
            Result<LampSave> found;
            try {
                branch = headBranch();
                line = commits();
                found = onThisBranch(line, prefix, "reset");
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
            }
            if (!(found instanceof Result.Ok<LampSave> ok)) return Result.err(found.problems());
            LampSave target = ok.value();
            int dropped = 0;
            while (!line.get(dropped).id().equals(target.id())) dropped++;
            GitObjectUtil.Commit newest = line.first();
            GitObjectUtil.Commit wanted = line.get(dropped);

            // Only to compare it with the newest snapshot; without `confirmed`, nothing is stored.
            Tally tally = new Tally();
            tally.write = confirmed;
            String now;
            try {
                now = storeLamp(tally);
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), "the lamp could not be read, "
                        + "so nothing was reset: " + ProblemCatalogUtil.reason(e)));
            }
            // A skipped directory is absent from `now`, not empty on disk. Using that incomplete
            // tree to restore would leave extra files inside it and falsely report them discarded.
            if (!tally.skipped.isEmpty())
                return Result.err(ProblemCatalogUtil.saveFailed(layout.root(),
                        "the lamp could not be read completely, so nothing was reset: " + tally.skipped.join(", "))
                    );
            Resetting resetting = new Resetting(branch, target, dropped, !now.equals(newest.tree()), tally.skipped);
            if (!confirmed || (dropped == 0 && !resetting.unsaved())) return Result.ok(resetting);
            try {
                bringBack(withScheduleOf(wanted.tree(), now), now);
                moveBranch(Optional.of(newest.id()), wanted.id());
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.resetFailed(layout.root(), ProblemCatalogUtil.reason(e),
                        branch, newest.snapshot().shortId()));
            }
            return Result.ok(resetting);
        });
    }

    // ─── saving ────────────────────────────────────────────────────────────────────────────

    /// Counts what a save found, as it walks the agent directory.
    private static final class Tally {
        int files;
        Tuple<String> skipped = Tuple.of(String.class);
        /// Whether objects are stored. Off, they are only named, so that the lamp can be compared
        /// with a snapshot without adding anything to the history.
        boolean write = true;
        /// One line per path whose permissions are not what git restores by default, as
        /// `0600 agent-lamp-x/.ssh/id_ed25519` and a NUL byte, since a name may hold a newline.
        final StringBuilder modes = new StringBuilder();
        /// What the last save found of each file, so that an unchanged one is not read again.
        FileStats stats = FileStats.none();
    }

    private Saving saveUnlocked(LampSaveKind kind, String message, Optional<SessionId> session, Instant now)
            throws IOException {
        return saveUnlocked(kind, message, session, Association.between(String.class, String.class), false, now);
    }

    private Saving saveUnlocked(LampSaveKind kind, String message, Optional<SessionId> session,
                                Association<String, String> trailers, boolean always, Instant now)
            throws IOException {
        ensureRepository();
        Tally tally = new Tally();
        String tree = storeLamp(tally);

        Optional<GitObjectUtil.Commit> head = headCommit();
        if (!always && head.isPresent() && head.get().tree().equals(tree)) {
            if (kind == LampSaveKind.BEFORE_RUN) pinBaseUnlocked(head.get().id());
            return new Saving(Optional.empty(), head.map(GitObjectUtil.Commit::snapshot), tally.files, tally.skipped);
        }
        LampSave made = commit(tree, head.map(GitObjectUtil.Commit::id), now,
                GitObjectUtil.message(kind, message, session, trailers));
        return new Saving(Optional.of(made), Optional.of(made), tally.files, tally.skipped);
    }

    /// Stores the lamp as it is now: the agent directory, `oillamp.toml`, the schedule and the list
    /// of exact permissions. Returns the name of the top tree, which is a snapshot's tree.
    private String storeLamp(Tally tally) throws IOException {
        Tuple<GitObjectUtil.Entry> top = Tuple.of(GitObjectUtil.Entry.class);
        Path agent = layout.agentDir();
        String agentName = fileName(agent);
        tally.stats = readFileStats(tally.write);
        if (Files.isDirectory(agent, LinkOption.NOFOLLOW_LINKS))
            top = top.add(new GitObjectUtil.Entry(agentName, GitObjectUtil.EntryMode.DIRECTORY,
                    storeDirectory(agent, agentName, tally)));
        // Only a save keeps them: without one, the blobs they name may not be stored.
        if (tally.write) {
            try {
                FilesystemUtil.writeBytes(repository.resolve(FILE_STATS), tally.stats.written(), PosixMode.PRIVATE_FILE);
            } catch (IOException notKept) {
                // The next save reads every file again, as without them.
            }
        }
        if (Files.isRegularFile(layout.config(), LinkOption.NOFOLLOW_LINKS))
            top = top.add(new GitObjectUtil.Entry(CONFIG_FILE, GitObjectUtil.EntryMode.FILE,
                    storeSmall("blob", Files.readAllBytes(layout.config()), tally.write)));
        String schedule = storeSmall("blob", new ScheduleBook(layout).stored(), tally.write);
        top = top.add(new GitObjectUtil.Entry(STATE_DIR, GitObjectUtil.EntryMode.DIRECTORY, storeSmall("tree",
                GitObjectUtil.tree(Tuple.of(GitObjectUtil.Entry.class,
                        new GitObjectUtil.Entry(SCHEDULE_FILE, GitObjectUtil.EntryMode.FILE, schedule))), tally.write)));
        top = top.add(new GitObjectUtil.Entry(MODES_FILE, GitObjectUtil.EntryMode.FILE,
                storeSmall("blob", tally.modes.toString().getBytes(StandardCharsets.UTF_8), tally.write)));
        return storeSmall("tree", GitObjectUtil.tree(top), tally.write);
    }

    /// Stores a directory and everything in it, and returns the name of its tree.
    ///
    /// Links are stored as links and never followed. Sockets, pipes and devices are not files
    /// anyone restores, so they are passed over without a word.
    private String storeDirectory(Path directory, String relative, Tally tally) throws IOException {
        Tuple<GitObjectUtil.Entry> entries = Tuple.of(GitObjectUtil.Entry.class);
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            for (Path child : listing) children.add(child);
        }
        for (Path child : children) {
            String name = fileName(child);
            String path = relative + "/" + name;
            PosixFileAttributes attributes;
            try {
                attributes = Files.readAttributes(child, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException gone) {
                continue;   // deleted while the save ran
            }
            int permissions = bitsOf(attributes.permissions());
            try {
                if (attributes.isSymbolicLink()) {
                    byte[] target = Files.readSymbolicLink(child).toString().getBytes(StandardCharsets.UTF_8);
                    entries = entries.add(new GitObjectUtil.Entry(name, GitObjectUtil.EntryMode.SYMLINK,
                            storeSmall("blob", target, tally.write)));
                } else if (attributes.isDirectory()) {
                    if (!Files.isReadable(child) || !Files.isExecutable(child)) {
                        tally.skipped = tally.skipped.add(path + "/ (cannot be read)");
                        continue;
                    }
                    entries = entries.add(new GitObjectUtil.Entry(name, GitObjectUtil.EntryMode.DIRECTORY,
                            storeDirectory(child, path, tally)));
                    if (permissions != 0755) tally.modes.append(octal(permissions)).append(' ').append(path).append('\0');
                } else if (attributes.isRegularFile()) {
                    Optional<String> blob = storeFile(child, path, attributes, tally);
                    if (blob.isEmpty()) {
                        tally.skipped = tally.skipped.add(path + " (kept changing while it was read)");
                        continue;
                    }
                    boolean executable = (permissions & 0100) != 0;
                    entries = entries.add(new GitObjectUtil.Entry(name,
                            executable ? GitObjectUtil.EntryMode.EXECUTABLE : GitObjectUtil.EntryMode.FILE, blob.get()));
                    tally.files++;
                    if (permissions != (executable ? 0755 : 0644))
                        tally.modes.append(octal(permissions)).append(' ').append(path).append('\0');
                }
            } catch (AccessDeniedException unreadable) {
                tally.skipped = tally.skipped.add(path + " (cannot be read)");
            } catch (NoSuchFileException gone) {
                // deleted while the save ran
            }
        }
        return storeSmall("tree", GitObjectUtil.tree(entries), tally.write);
    }

    /// Stores the file at `path`, and returns the name of its blob, without reading it when its
    /// stat is as the last save found it. Empty when the file kept changing while it was read,
    /// three times in a row.
    private Optional<String> storeFile(Path file, String path, PosixFileAttributes attributes, Tally tally) throws IOException {
        Optional<FileStats.Stat> stat = statOf(file, attributes);
        Optional<String> known = stat.flatMap(it -> tally.stats.blob(path, it));
        Optional<String> blob = known.isPresent() ? known : storeContent(file, attributes.size(), tally.write);
        if (blob.isPresent() && stat.isPresent()) tally.stats.stored(path, stat.get(), blob.get());
        return blob;
    }

    /// What the last save found of each file, for a save that begins now. None, when the last
    /// save left none that can be read.
    ///
    /// When a save that stores began is read from the clock of the filesystem the history is on,
    /// which is where the files' times come from: a file is made in it, and removed again. It is
    /// named as a write in progress, which a prune sweeps away should oillamp be killed before it
    /// is removed. A save that only compares stores nothing, remembers nothing, and so writes
    /// nothing at all, which a history it can only read allows.
    ///
    /// @param write whether the save stores what it finds
    private FileStats readFileStats(boolean write) throws IOException {
        byte[] written;
        try {
            written = Files.readAllBytes(repository.resolve(FILE_STATS));
        } catch (IOException missingOrUnreadable) {
            written = new byte[0];
        }
        if (!write) return FileStats.toCompare(written);
        Path marker = Files.createTempFile(repository.resolve("objects"), "incoming-", ".tmp");
        long began;
        try {
            began = Files.getLastModifiedTime(marker).to(TimeUnit.NANOSECONDS);
        } finally {
            Files.deleteIfExists(marker);
        }
        return FileStats.of(written, began);
    }

    /// The stat of `file`, whose `attributes` were just read. Empty when the filesystem does not
    /// say when its inode changed, or which one it is.
    private static Optional<FileStats.Stat> statOf(Path file, PosixFileAttributes attributes) throws IOException {
        Map<String, Object> unix;
        try {
            unix = Files.readAttributes(file, "unix:ctime,ino,dev", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException noUnixView) {
            return Optional.empty();
        }
        if (!(unix.get("ctime") instanceof FileTime ctime) || !(unix.get("ino") instanceof Long inode)
                || !(unix.get("dev") instanceof Long device))
            return Optional.empty();
        return Optional.of(new FileStats.Stat(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                ctime.to(TimeUnit.NANOSECONDS), inode, device));
    }

    /// Stores one file's content, and returns the name of its blob. Empty when the file kept
    /// changing while it was read, three times in a row.
    ///
    /// @param write whether to store it; without, it is only named
    private Optional<String> storeContent(Path file, long size, boolean write) throws IOException {
        if (size <= SMALL_FILE) {
            byte[] content = Files.readAllBytes(file);
            return Optional.of(storeSmall("blob", content, write));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            long expected = Files.size(file);
            Optional<String> id = hashFile(file, expected);
            if (id.isEmpty()) continue;
            if (!write || Files.exists(objectPath(id.get()))) return id;
            if (writeLarge(file, expected, id.get())) return id;
        }
        return Optional.empty();
    }

    /// The name a file of `size` bytes would have as a blob. Empty when it is no longer that size.
    private static Optional<String> hashFile(Path file, long size) throws IOException {
        MessageDigest sha = GitObjectUtil.sha1();
        sha.update(GitObjectUtil.header("blob", size));
        long read = 0;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0; read += n) sha.update(buffer, 0, n);
        }
        return read == size ? Optional.of(HexFormat.of().formatHex(sha.digest())) : Optional.empty();
    }

    /// Writes a large file as the object `id`, naming it again while it is written. False when
    /// what was written is not what was named, because the file changed in between.
    private boolean writeLarge(Path file, long size, String id) throws IOException {
        Path temporary = Files.createTempFile(repository.resolve("objects"), "incoming-", ".tmp");
        try {
            MessageDigest sha = GitObjectUtil.sha1();
            long read = 0;
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = new DeflaterOutputStream(Files.newOutputStream(temporary), deflater, 1 << 16)) {
                byte[] header = GitObjectUtil.header("blob", size);
                sha.update(header);
                out.write(header);
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0 && read + n <= size; read += n) {
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            } finally {
                deflater.end();
            }
            if (read != size || !HexFormat.of().formatHex(sha.digest()).equals(id)) return false;
            place(temporary, id);
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Stores an object held in memory, unless it is there already, and returns its name.
    private String storeSmall(String type, byte[] content) throws IOException {
        return storeSmall(type, content, true);
    }

    /// The same, or with `write` false only its name.
    private String storeSmall(String type, byte[] content, boolean write) throws IOException {
        String id = GitObjectUtil.idOf(type, content);
        if (!write || Files.exists(objectPath(id))) return id;
        Path temporary = Files.createTempFile(repository.resolve("objects"), "incoming-", ".tmp");
        // A stream given its own Deflater does not release it, so it is released here, rather
        // than whenever the garbage collector gets to thousands of them.
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            try (OutputStream out = new DeflaterOutputStream(Files.newOutputStream(temporary), deflater)) {
                out.write(GitObjectUtil.header(type, content.length));
                out.write(content);
            }
            place(temporary, id);
        } finally {
            deflater.end();
            Files.deleteIfExists(temporary);
        }
        return id;
    }

    /// Moves a finished object into place. git keeps objects read-only, and so does this.
    private void place(Path temporary, String id) throws IOException {
        Path target = objectPath(id);
        Files.createDirectories(target.getParent());
        FilesystemUtil.setMode(temporary, PosixMode.of(0444));
        force(temporary);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        force(java.util.Objects.requireNonNull(target.getParent()));
        force(repository.resolve("objects"));
    }

    private LampSave commit(String tree, Optional<String> parent, Instant now, String message) throws IOException {
        String id = storeSmall("commit", GitObjectUtil.commit(tree, parent, now, message));
        moveBranch(parent, id);
        LampSave snapshot = GitObjectUtil.parseCommit(id, readObject(id, "commit")).snapshot();
        if (snapshot.kind() == LampSaveKind.BEFORE_RUN) pinBaseUnlocked(id);
        return snapshot;
    }

    /// Points the current branch at `id`, the last step of every save and of a reset.
    ///
    /// The history lock is held, so nothing else moves it at the same time. Checking it still
    /// points at `parent` catches someone committing to this repository by hand meanwhile.
    private void moveBranch(Optional<String> parent, String id) throws IOException {
        String branch = headBranch();
        Optional<String> now = ref(branch);
        if (!now.equals(parent))
            throw new IOException("the history changed while this ran; " + branch + " now ends at "
                    + now.orElse("nothing") + ", not " + parent.orElse("nothing"));
        writeRef(branch, id);
    }

    /// Writes a branch's file, which names its newest commit, as git writes it.
    private void writeRef(String branch, String id) throws IOException {
        durableWrite(repository.resolve(HEADS + branch), id + "\n");
    }

    /// Points `HEAD` at a branch: from now on, saves go to it.
    private void writeHead(String branch) throws IOException {
        durableWrite(repository.resolve("HEAD"), "ref: " + HEADS + branch + "\n");
    }

    /// Removes a branch: its line in `packed-refs`, if git packed it, and then its own file. Either
    /// is replaced by renaming a new file over it, so that an interruption leaves the branch as it
    /// was or gone.
    private void deleteRef(String branch) throws IOException {
        Path packed = repository.resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            List<String> lines = Files.readAllLines(packed);
            List<String> kept = new ArrayList<>();
            boolean dropping = false;
            for (String line : lines) {
                // A `^` line belongs to the ref above it.
                if (!line.startsWith("^")) dropping = line.endsWith(" " + HEADS + branch);
                if (!dropping) kept.add(line);
            }
            if (kept.size() != lines.size())
                durableWrite(packed, String.join("\n", kept) + "\n");
        }
        Files.deleteIfExists(repository.resolve(HEADS + branch));
        Path parent = java.util.Objects.requireNonNull(repository.resolve(HEADS + branch).getParent());
        if (Files.isDirectory(parent)) force(parent);
    }

    /// Creates an empty repository, the first time a lamp is saved.
    private void ensureRepository() throws IOException {
        if (Files.isRegularFile(repository.resolve("HEAD"))) return;
        FilesystemUtil.createDirectories(repository.resolve("objects"), PosixMode.PRIVATE_DIR);
        FilesystemUtil.createDirectories(repository.resolve("refs").resolve("heads"), PosixMode.PRIVATE_DIR);
        FilesystemUtil.createDirectories(repository.resolve("refs").resolve("tags"), PosixMode.PRIVATE_DIR);
        // `gc.auto = 0` keeps git itself, run here by hand, from packing the objects into a
        // form oillamp does not read.
        FilesystemUtil.writeFile(repository.resolve("config"), """
                [core]
                \trepositoryformatversion = 0
                \tbare = true
                [gc]
                \tauto = 0
                """, PosixMode.PRIVATE_FILE);
        FilesystemUtil.writeFile(repository.resolve("description"),
                "The history of the oillamp lamp at " + layout.root() + "\n", PosixMode.PRIVATE_FILE);
        writeHead(FIRST_BRANCH);
    }

    // ─── reading ───────────────────────────────────────────────────────────────────────────

    /// The branch `HEAD` names, which every save goes to: `main` before the first save.
    private String headBranch() throws IOException {
        Path head = repository.resolve("HEAD");
        if (!Files.isRegularFile(head)) return FIRST_BRANCH;
        String text = Files.readString(head).strip();
        String name = text.startsWith("ref: " + HEADS) ? text.substring(("ref: " + HEADS).length()) : "";
        if (!isRefName(name))
            throw new IOException("HEAD says '" + text + "', not the branch the lamp is on, such as 'ref: "
                    + HEADS + FIRST_BRANCH + "'");
        return name;
    }

    /// A branch's name as git may have written it: parts joined by `/`, each a plain name. Checked
    /// for every name read from the history, so that none leads outside `refs/heads`. Git forbids
    /// dot-prefixed components; in particular, oillamp's `.oillamp-*.tmp` writes are not branches.
    private static boolean isRefName(String name) {
        for (String part : name.split("/", -1))
            if (!GitObjectUtil.isPlainName(part) || part.startsWith(".")) return false;
        return true;
    }

    /// Where a branch points: in its own file, or in `packed-refs` if git packed it.
    private Optional<String> ref(String branch) throws IOException {
        Path loose = repository.resolve(HEADS + branch);
        if (Files.isRegularFile(loose, LinkOption.NOFOLLOW_LINKS))
            return Optional.of(checkedId(Files.readString(loose).strip(), branch));
        for (String line : packedRefs())
            if (line.substring(41).equals(HEADS + branch)) return Optional.of(checkedId(line.substring(0, 40), branch));
        return Optional.empty();
    }

    /// The lines of `packed-refs` that name a ref, each an id, a space and the ref; without the
    /// comment git starts it with and the `^` lines it adds below a tag.
    private List<String> packedRefs() throws IOException {
        Path packed = repository.resolve("packed-refs");
        if (!Files.isRegularFile(packed)) return List.of();
        return Files.readAllLines(packed).stream()
                .filter(line -> line.length() > 41 && line.charAt(40) == ' ' && !line.startsWith("#") && !line.startsWith("^"))
                .toList();
    }

    /// The name of every branch, in alphabetical order: those in their own files under
    /// `refs/heads`, and those git packed into `packed-refs`. A file git leaves while it changes a
    /// branch, which ends in `.lock`, and oillamp's dot-prefixed temporary files are not branches.
    private SortedSet<String> branchNames() throws IOException {
        SortedSet<String> names = new TreeSet<>();
        Path heads = repository.resolve(HEADS);
        if (Files.isDirectory(heads, LinkOption.NOFOLLOW_LINKS))
            try (Stream<Path> files = Files.walk(heads)) {
                files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                     .forEach(file -> names.add(heads.relativize(file).toString()));
            }
        for (String line : packedRefs())
            if (line.startsWith(HEADS, 41)) names.add(line.substring(41 + HEADS.length()));
        names.removeIf(name -> !isRefName(name) || name.endsWith(".lock"));
        return names;
    }

    private static String checkedId(String id, String branch) throws IOException {
        if (!GitObjectUtil.isObjectId(id))
            throw new IOException("the branch " + branch + " names '" + id + "', not a commit");
        return id;
    }

    private GitObjectUtil.Commit commitAt(String id) throws IOException {
        return GitObjectUtil.parseCommit(id, readObject(id, "commit"));
    }

    /// The newest commit of the current branch.
    private Optional<GitObjectUtil.Commit> headCommit() throws IOException {
        Optional<String> id = ref(headBranch());
        return id.isEmpty() ? Optional.empty() : Optional.of(commitAt(id.get()));
    }

    /// Every commit on the current branch, newest first.
    private Tuple<GitObjectUtil.Commit> commits() throws IOException {
        if (!Files.isRegularFile(repository.resolve("HEAD"))) return Tuple.of(GitObjectUtil.Commit.class);
        return line(ref(headBranch()));
    }

    /// The commit `tip` names and every one before it, newest first, following each one's parent.
    private Tuple<GitObjectUtil.Commit> line(Optional<String> tip) throws IOException {
        Tuple<GitObjectUtil.Commit> found = Tuple.of(GitObjectUtil.Commit.class);
        Optional<String> next = tip;
        Set<String> seen = new HashSet<>();
        Set<String> boundaries = shallow();
        while (next.isPresent() && seen.add(next.get())) {
            GitObjectUtil.Commit commit = commitAt(next.get());
            found = found.add(commit);
            next = boundaries.contains(commit.id()) ? Optional.empty() : commit.parent();
        }
        return found;
    }

    /// Every commit on any branch, newest first, each once.
    private Tuple<GitObjectUtil.Commit> allCommits() throws IOException {
        Tuple<GitObjectUtil.Commit> found = Tuple.of(GitObjectUtil.Commit.class);
        for (OnBranches listed : everyCommit()) found = found.add(listed.commit());
        return found;
    }

    /// A commit, with the branches it is on.
    ///
    /// @param depth    how many commits come before it, following each one's parent
    /// @param branches the names of those branches, in alphabetical order
    private record OnBranches(GitObjectUtil.Commit commit, int depth, List<String> branches) {}

    /// Every commit on any branch, each once: newest first, and of two saved in the same second,
    /// the one with more commits before it first, since a commit always comes after its parent.
    private List<OnBranches> everyCommit() throws IOException {
        if (!Files.isRegularFile(repository.resolve("HEAD"))) return List.of();
        Map<String, OnBranches> found = new LinkedHashMap<>();
        for (String name : branchNames()) {
            Tuple<GitObjectUtil.Commit> line = line(ref(name));
            for (int at = 0; at < line.size(); at++) {
                GitObjectUtil.Commit commit = line.get(at);
                int depth = line.size() - 1 - at;
                found.computeIfAbsent(commit.id(), _ -> new OnBranches(commit, depth, new ArrayList<>())).branches().add(name);
            }
        }
        List<OnBranches> newestFirst = new ArrayList<>(found.values());
        newestFirst.sort(Comparator.comparing((OnBranches listed) -> listed.commit().snapshot().at())
                                   .thenComparingInt(OnBranches::depth).reversed());
        return newestFirst;
    }

    private Path objectPath(String id) {
        return repository.resolve("objects").resolve(id.substring(0, 2)).resolve(id.substring(2));
    }

    /// An object's content, for the small ones: commits, trees and the list of permissions.
    private byte[] readObject(String id, String type) throws IOException {
        try (InputStream in = openObject(id, type)) {
            return in.readAllBytes();
        }
    }

    /// An object's content as a stream, after checking its header says `type`.
    private InputStream openObject(String id, String type) throws IOException {
        Path file = objectPath(id);
        if (!Files.isRegularFile(file))
            throw new IOException("the object " + id + " is missing from " + repository.resolve("objects"));
        // The stream's own Inflater, which it releases when it is closed.
        InputStream in = new InflaterInputStream(Files.newInputStream(file));
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        for (int b; (b = in.read()) > 0; ) header.write(b);
        String text = header.toString(StandardCharsets.US_ASCII);
        if (!text.startsWith(type + " ")) {
            in.close();
            throw new IOException("the object " + id + " is not a " + type + " but '" + text + "'");
        }
        return in;
    }

    private Association<String, GitObjectUtil.Entry> entriesOf(Optional<String> tree) throws IOException {
        Association<String, GitObjectUtil.Entry> byName = Association.between(String.class, GitObjectUtil.Entry.class);
        if (tree.isEmpty()) return byName;
        for (GitObjectUtil.Entry entry : GitObjectUtil.parseTree(readObject(tree.get(), "tree")))
            byName = byName.put(entry.name(), entry);
        return byName;
    }

    // ─── restoring ─────────────────────────────────────────────────────────────────────────

    /// Makes the lamp hold what the tree `target` holds, knowing it now holds what `current`
    /// holds. Only what differs is touched: a directory whose tree has not changed is skipped
    /// whole, which is what makes restoring a large home quick.
    private void bringBack(String target, String current) throws IOException {
        Association<String, GitObjectUtil.Entry> wanted = entriesOf(Optional.of(target));
        Association<String, GitObjectUtil.Entry> now = entriesOf(Optional.of(current));
        String agentName = fileName(layout.agentDir());
        // A snapshot holds the agent directory under the name it had when it was saved. That is
        // the same name unless the lamp was made again with a new agent id; then take its one
        // agent directory, whatever it was called.
        Optional<GitObjectUtil.Entry> agentThen = wanted.get(agentName).or(() -> {
            for (GitObjectUtil.Entry entry : wanted.values())
                if (entry.name().startsWith(LampLayout.AGENT_DIR_PREFIX) && entry.isDirectory())
                    return Optional.of(entry);
            return Optional.empty();
        });
        Optional<GitObjectUtil.Entry> agentNow = now.get(agentName);
        if (agentThen.isPresent()) {
            if (!agentNow.equals(agentThen))
                restoreDirectory(layout.agentDir(), agentThen.get().id(),
                        agentNow.filter(GitObjectUtil.Entry::isDirectory).map(GitObjectUtil.Entry::id));
        } else {
            deleteAnything(layout.agentDir());
        }

        Optional<GitObjectUtil.Entry> configThen = wanted.get(CONFIG_FILE);
        if (configThen.isPresent() && !configThen.equals(now.get(CONFIG_FILE)))
            FilesystemUtil.writeBytes(layout.config(), readObject(configThen.get().id(), "blob"), PosixMode.PRIVATE_FILE);

        Optional<GitObjectUtil.Entry> scheduleThen = scheduleIn(wanted.get(STATE_DIR));
        if (scheduleThen.isPresent() && !scheduleThen.equals(scheduleIn(now.get(STATE_DIR))))
            new ScheduleBook(layout).restore(readObject(scheduleThen.get().id(), "blob"));

        applyModes(modesIn(now.get(MODES_FILE)), modesIn(wanted.get(MODES_FILE)),
                   agentThen.map(GitObjectUtil.Entry::name).orElse(agentName));
    }

    /// The tree a restore to `target` brings the lamp to. That is `target` itself, unless it was
    /// saved before snapshots held the schedule: then the lamp keeps the schedule it has, which
    /// the tree `current` holds, because an older snapshot says nothing about which jobs the lamp
    /// had, and an empty schedule would be a guess.
    private String withScheduleOf(String target, String current) throws IOException {
        Association<String, GitObjectUtil.Entry> wanted = entriesOf(Optional.of(target));
        Optional<GitObjectUtil.Entry> kept = entriesOf(Optional.of(current)).get(STATE_DIR);
        if (wanted.containsKey(STATE_DIR) || kept.isEmpty()) return target;
        Tuple<GitObjectUtil.Entry> entries = Tuple.of(GitObjectUtil.Entry.class, kept.get());
        for (GitObjectUtil.Entry entry : wanted.values()) entries = entries.add(entry);
        return storeSmall("tree", GitObjectUtil.tree(entries));
    }

    /// The schedule file in a snapshot's `.oillamp` directory, if it has one.
    private Optional<GitObjectUtil.Entry> scheduleIn(Optional<GitObjectUtil.Entry> state) throws IOException {
        if (state.isEmpty() || !state.get().isDirectory()) return Optional.empty();
        return entriesOf(state.map(GitObjectUtil.Entry::id)).get(SCHEDULE_FILE)
                .filter(entry -> entry.mode() == GitObjectUtil.EntryMode.FILE);
    }

    private void restoreDirectory(Path directory, String target, Optional<String> current) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            deleteAnything(directory);
            Files.createDirectory(directory);
            current = Optional.empty();
        }
        makeWritable(directory);
        Association<String, GitObjectUtil.Entry> wanted = entriesOf(Optional.of(target));
        Association<String, GitObjectUtil.Entry> now = entriesOf(current);
        for (GitObjectUtil.Entry old : now.values())
            if (!wanted.containsKey(old.name())) deleteAnything(directory.resolve(old.name()));
        for (GitObjectUtil.Entry entry : wanted.values()) {
            Optional<GitObjectUtil.Entry> was = now.get(entry.name());
            if (was.isPresent() && was.get().equals(entry)) continue;
            Path path = directory.resolve(entry.name());
            switch (entry.mode()) {
                case DIRECTORY -> restoreDirectory(path, entry.id(),
                        was.filter(GitObjectUtil.Entry::isDirectory).map(GitObjectUtil.Entry::id));
                case SYMLINK -> {
                    deleteAnything(path);
                    Files.createSymbolicLink(path, Path.of(new String(readObject(entry.id(), "blob"), StandardCharsets.UTF_8)));
                }
                case FILE, EXECUTABLE -> {
                    deleteAnything(path);
                    writeBlob(entry.id(), path, entry.mode() == GitObjectUtil.EntryMode.EXECUTABLE ? 0755 : 0644);
                }
            }
        }
    }

    /// Writes a blob to `path`, streaming it, through a temporary file beside it.
    private void writeBlob(String id, Path path, int permissions) throws IOException {
        Path parent = path.getParent();
        Path temporary = Files.createTempFile(parent == null ? path : parent, ".oillamp-", ".tmp");
        try {
            try (InputStream in = openObject(id, "blob")) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setPosixFilePermissions(temporary, permissionsOf(permissions));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Deletes a file, link or whole directory, never following a link. The agent may have made
    /// directories read-only, as Go does with its module cache, so each is made writable first.
    private static void deleteAnything(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            makeWritable(path);
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(path)) {
                for (Path child : listing) children.add(child);
            }
            for (Path child : children) deleteAnything(child);
        }
        Files.delete(path);
    }

    private static void makeWritable(Path directory) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
        if (permissions.containsAll(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                            PosixFilePermission.OWNER_EXECUTE))) return;
        Set<PosixFilePermission> more = EnumSet.copyOf(permissions);
        more.addAll(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(directory, more);
    }

    // ─── exact permissions ─────────────────────────────────────────────────────────────────

    private Association<String, Integer> modesIn(Optional<GitObjectUtil.Entry> file) throws IOException {
        Association<String, Integer> modes = Association.between(String.class, Integer.class);
        if (file.isEmpty()) return modes;
        String text = new String(readObject(file.get().id(), "blob"), StandardCharsets.UTF_8);
        for (String line : text.split("\0", -1)) {
            int space = line.indexOf(' ');
            if (space < 0) continue;
            try {
                modes = modes.put(line.substring(space + 1), Integer.parseInt(line.substring(0, space), 8) & 07777);
            } catch (NumberFormatException ignored) {
                // A damaged line costs one path its exact permissions, nothing more.
            }
        }
        return modes;
    }

    /// Gives every path the permissions the target snapshot recorded, and puts back git's
    /// default on paths that had other permissions only in the snapshot being left.
    ///
    /// Directories come last and deepest first, because a directory made read-only first would
    /// stop the permissions of what is inside it from being changed.
    ///
    /// @param savedAs the agent directory's name in the target snapshot, which paths in its list
    ///                start with; they are applied to the agent directory as it is named now
    private void applyModes(Association<String, Integer> before, Association<String, Integer> after,
                            String savedAs) throws IOException {
        String agentName = fileName(layout.agentDir());
        List<Path> directories = new ArrayList<>();
        Map<Path, Integer> wanted = new HashMap<>();
        for (var pair : after)
            resolveInLamp(pair.first(), savedAs, agentName).ifPresent(path -> wanted.put(path, pair.second()));
        // -1: the path had its own permissions before, and should get git's default back.
        for (var pair : before)
            resolveInLamp(pair.first(), agentName, agentName).ifPresent(path -> wanted.putIfAbsent(path, -1));
        for (var mode : wanted.entrySet()) {
            Path path = mode.getKey();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { directories.add(path); continue; }
            int bits = mode.getValue() >= 0 ? mode.getValue()
                    : Files.isExecutable(path) ? 0755 : 0644;
            Files.setPosixFilePermissions(path, permissionsOf(bits));
        }
        directories.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        for (Path directory : directories) {
            int bits = wanted.getOrDefault(directory, -1);
            Files.setPosixFilePermissions(directory, permissionsOf(bits >= 0 ? bits : 0755));
        }
    }

    /// A path from the list of permissions, in the lamp. Empty for one that does not lie inside
    /// the agent directory, which a list oillamp wrote never holds.
    private Optional<Path> resolveInLamp(String listed, String savedAs, String agentName) {
        if (!listed.startsWith(savedAs + "/")) return Optional.empty();
        String rest = listed.substring(savedAs.length() + 1);
        for (String part : rest.split("/", -1)) if (!GitObjectUtil.isPlainName(part)) return Optional.empty();
        return Optional.of(layout.root().resolve(agentName).resolve(rest));
    }

    // ─── small helpers ─────────────────────────────────────────────────────────────────────

    // ─── bounded history ────────────────────────────────────────────────────────────────────

    private Set<String> shallow() throws IOException {
        Path file = repository.resolve("shallow");
        Set<String> ids = new HashSet<>();
        if (Files.exists(file))
            for (String id : Files.readAllLines(file)) {
                if (!GitObjectUtil.isObjectId(id)) throw new IOException("invalid shallow boundary: " + id);
                ids.add(id);
            }
        return ids;
    }

    Result<Optional<LampEvent.HistoryPruned>> prune(boolean confirmed, Instant now) {
        return lockedThenReport(() -> {
            try {
                Pruning pruning = pruneUnlocked(confirmed, now, ValueSet.of(String.class));
                return new Done<>(Result.ok(pruning.event()), pruning.warnings());
            } catch (IOException | RuntimeException e) {
                return new Done<>(Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e))),
                        Tuple.of(LampEvent.class));
            }
        });
    }

    /// One fixed allowance, measured from the oldest initial snapshot when adopting an existing
    /// repository. It is a byte count, not a protected snapshot; its objects may eventually go.
    private long initialBytes(List<OnBranches> commits, Map<String, Long> sizes, boolean write) throws IOException {
        Path file = repository.resolve("initial-bytes");
        if (Files.exists(file)) {
            long bytes = Long.parseLong(Files.readString(file).strip());
            if (bytes < 0) throw new IOException("negative initial history size");
            return bytes;
        }
        long bytes = 0;
        if (!commits.isEmpty()) {
            GitObjectUtil.Commit first = commits.stream().min(Comparator.comparingInt(OnBranches::depth)
                    .thenComparing(c -> c.commit().snapshot().at())).orElseThrow().commit();
            for (String id : snapshotObjects(first)) bytes += sizes.getOrDefault(id, 0L);
        }
        if (write) durableWrite(file, bytes + "\n");
        return bytes;
    }

    private Set<String> snapshotObjects(GitObjectUtil.Commit commit) throws IOException {
        Set<String> objects = new HashSet<>();
        objects.add(commit.id());
        collectTree(commit.tree(), objects);
        return objects;
    }

    /// Count incoming edges in the object DAG, not a full descendant set per snapshot. Identical
    /// trees are traversed once, so thousands of snapshots of one large home need linear memory.
    private void retainTree(String tree, Map<String, Integer> counts, Map<String, Tuple<String>> children) throws IOException {
        counts.merge(tree, 1, Integer::sum);
        if (children.containsKey(tree)) return;
        Tuple<String> edges = Tuple.of(String.class);
        for (GitObjectUtil.Entry entry : GitObjectUtil.parseTree(readObject(tree, "tree"))) {
            edges = edges.add(entry.id());
            if (entry.isDirectory()) retainTree(entry.id(), counts, children);
            else {
                if (!Files.isRegularFile(objectPath(entry.id()), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("missing snapshot object " + entry.id());
                counts.merge(entry.id(), 1, Integer::sum);
            }
        }
        children.put(tree, edges);
    }

    private long releaseObject(String id, Map<String, Integer> counts, Map<String, Tuple<String>> children,
                               Map<String, Long> sizes) {
        int count = java.util.Objects.requireNonNull(counts.get(id)) - 1;
        if (count > 0) { counts.put(id, count); return 0; }
        counts.remove(id);
        long freed = sizes.getOrDefault(id, 0L);
        for (String child : children.getOrDefault(id, Tuple.of(String.class)))
            freed += releaseObject(child, counts, children, sizes);
        return freed;
    }

    Result<Tuple<String>> boundaries() {
        return reading(() -> Result.ok(Tuple.of(String.class, new TreeSet<>(shallow()))));
    }

    private void collectTree(String tree, Set<String> objects) throws IOException {
        if (!objects.add(tree)) return;
        for (GitObjectUtil.Entry entry : GitObjectUtil.parseTree(readObject(tree, "tree"))) {
            if (entry.isDirectory()) collectTree(entry.id(), objects);
            else {
                if (!Files.isRegularFile(objectPath(entry.id()), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("missing snapshot object " + entry.id());
                objects.add(entry.id());
            }
        }
    }

    /// Publish boundaries before sweeping. After interruption, traversal is already valid and a
    /// later sweep can reclaim what remains. No commit or branch ref is rewritten.
    /// What a prune did, or would do when it was not confirmed.
    ///
    /// @param event    what it removed; empty when it removed nothing and the history fits
    /// @param warnings about what it could not finish
    private record Pruning(Optional<LampEvent.HistoryPruned> event, Tuple<LampEvent> warnings) {}

    /// Prunes after a save, a restore or a switch added a snapshot. The snapshot is already
    /// saved, so a prune that fails is a warning, not a failed save.
    ///
    /// @param keep the snapshots the operation still uses, which are kept with every snapshot
    ///             from them to their branch's newest
    /// @return the events that tell what the prune did, to report once the lock is released
    private Tuple<LampEvent> maintain(Instant now, ValueSet<String> keep) {
        try {
            Pruning pruning = pruneUnlocked(true, now, keep);
            return pruning.event().<Tuple<LampEvent>>map(pruning.warnings()::add).orElse(pruning.warnings());
        } catch (IOException | RuntimeException e) {
            return Tuple.of(LampEvent.class, new LampEvent.Warning(ProblemCatalogUtil.historyPruningFailed(layout.root(),
                    "the snapshot was saved, but history pruning failed: " + ProblemCatalogUtil.reason(e))));
        }
    }

    private Pruning pruneUnlocked(boolean confirmed, Instant now, ValueSet<String> keep) throws IOException {
        Tuple<LampEvent> warnings = Tuple.of(LampEvent.class);
        if (!Files.isRegularFile(repository.resolve("HEAD"))) return new Pruning(Optional.empty(), warnings);
        if (policy.maxGrowthGb() == 0) {
            if (confirmed && !Files.exists(repository.resolve("initial-bytes"))) {
                List<OnBranches> all = everyCommit();
                Map<String, Long> sizes = new HashMap<>();
                if (!all.isEmpty()) {
                    GitObjectUtil.Commit first = all.stream().min(Comparator.comparingInt(OnBranches::depth)
                            .thenComparing(c -> c.commit().snapshot().at())).orElseThrow().commit();
                    for (String id : snapshotObjects(first)) sizes.put(id, Files.size(objectPath(id)));
                }
                initialBytes(all, sizes, true);
            }
            return new Pruning(Optional.empty(), warnings);
        }
        Map<String, Long> sizes = new HashMap<>();
        Map<Path, Long> abandoned = new HashMap<>();
        try (Stream<Path> files = Files.walk(repository.resolve("objects"))) {
            for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = repository.resolve("objects").relativize(file).toString();
                String id = relative.replace("/", "");
                if (relative.matches("[0-9a-f]{2}/[0-9a-f]{38}")) sizes.put(id, Files.size(file));
                else if (relative.matches("incoming-[^/]+\\.tmp")) abandoned.put(file, Files.size(file));
                else if (!relative.startsWith("info/"))
                    throw new IOException("history pruning does not support object file " + relative);
            }
        }
        List<OnBranches> all = everyCommit();
        long initial = initialBytes(all, sizes, confirmed);
        long limit = initial + policy.allowanceBytes();
        // Tags, reflogs and replacement refs can retain history outside our branch graph. Refuse
        // that layout instead of silently deleting objects a person protected with git.
        try (Stream<Path> refs = Files.walk(repository.resolve("refs"))) {
            if (refs.anyMatch(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && !p.startsWith(repository.resolve("refs/heads"))))
                throw new IOException("remove additional git refs before pruning this lamp's history");
        }
        if (packedRefs().stream().anyMatch(ref -> !ref.startsWith(HEADS, 41))
                || Files.isDirectory(repository.resolve("logs")))
            throw new IOException("additional git refs or reflogs are not supported by history pruning");
        Set<String> protectedIds = new HashSet<>();
        for (String id : keep) protectedIds.add(id);
        Path pin = repository.resolve("active-base");
        if (stopped && confirmed) Files.deleteIfExists(pin);
        if (!stopped && Files.exists(pin)) {
            String id = Files.readString(pin).strip();
            if (!GitObjectUtil.isObjectId(id)) throw new IOException("invalid active run base");
            protectedIds.add(id);
        }
        // A pin must remain reachable as well as stored. Preserve the path from its branch tip
        // until the run or restore releases it; otherwise a later sweep could forget the pin.
        Set<String> pins = new HashSet<>(protectedIds);
        for (String name : branchNames()) {
            Tuple<GitObjectUtil.Commit> line = line(ref(name));
            int through = -1;
            for (int at = 0; at < line.size(); at++)
                if (pins.contains(line.get(at).id())) through = at;
            for (int at = 0; at <= through; at++) protectedIds.add(line.get(at).id());
        }
        for (String name : branchNames()) ref(name).ifPresent(protectedIds::add);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Tuple<String>> children = new HashMap<>();
        for (OnBranches listed : all) {
            counts.put(listed.commit().id(), 1);
            retainTree(listed.commit().tree(), counts, children);
        }
        long kept = 0;
        for (String id : counts.keySet()) kept += sizes.getOrDefault(id, 0L);
        Set<String> retained = new HashSet<>();
        for (OnBranches listed : all) retained.add(listed.commit().id());
        Tuple<LampSaveOnBranches> removed = Tuple.of(LampSaveOnBranches.class);
        // Oldest first by when each was saved, not by how many come before it: a branch started at
        // an old snapshot holds new work at a small depth. A snapshot counts as no older than the
        // one before it, even if the clock was set back, so that a line is only ever cut from its
        // start and no retained snapshot is left that no branch leads to.
        Map<String, Instant> age = new HashMap<>();
        List<OnBranches> byDepth = new ArrayList<>(all);
        byDepth.sort(Comparator.comparingInt(OnBranches::depth));
        for (OnBranches listed : byDepth) {
            Instant at = listed.commit().snapshot().at();
            Instant before = listed.commit().parent().map(age::get).orElse(at);
            age.put(listed.commit().id(), at.isBefore(before) ? before : at);
        }
        List<OnBranches> oldest = new ArrayList<>(all);
        oldest.sort(Comparator.comparing((OnBranches listed) -> age.get(listed.commit().id()))
                              .thenComparingInt(OnBranches::depth));
        for (OnBranches candidate : oldest) {
            if (kept <= limit) break;
            if (protectedIds.contains(candidate.commit().id())) continue;
            retained.remove(candidate.commit().id());
            removed = removed.add(new LampSaveOnBranches(candidate.commit().snapshot(), Tuple.of(String.class, candidate.branches())));
            kept -= releaseObject(candidate.commit().id(), counts, children, sizes);
            kept -= releaseObject(candidate.commit().tree(), counts, children, sizes);
        }
        SortedSet<String> boundaries = new TreeSet<>();
        for (OnBranches listed : all)
            if (retained.contains(listed.commit().id()) && listed.commit().parent().filter(p -> !retained.contains(p)).isPresent())
                boundaries.add(listed.commit().id());
        long total = sizes.values().stream().mapToLong(Long::longValue).sum()
                + abandoned.values().stream().mapToLong(Long::longValue).sum();
        long freed = total - kept;
        if (confirmed) {
            // Histories created by older builds did not force objects to disk. Establish their
            // durability once before the first destructive sweep; new writes already do so.
            if (!Files.exists(repository.resolve("durable-objects"))) {
                for (String id : counts.keySet()) force(objectPath(id));
                try (Stream<Path> dirs = Files.walk(repository.resolve("objects"))) {
                    for (Path dir : dirs.filter(Files::isDirectory).toList()) force(dir);
                }
                try (Stream<Path> refs = Files.walk(repository.resolve("refs"))) {
                    for (Path ref : refs.toList()) force(ref);
                }
                for (String name : List.of("HEAD", "packed-refs", "shallow"))
                    if (Files.exists(repository.resolve(name))) force(repository.resolve(name));
                force(repository);
                durableWrite(repository.resolve("durable-objects"), "1\n");
            }
            rememberPrunedRuns(removed, now);
            long actualFreed = 0;
            List<String> failures = new ArrayList<>();
            Map<Path, Long> doomed = new HashMap<>(abandoned);
            for (String id : sizes.keySet()) if (!counts.containsKey(id)) doomed.put(objectPath(id), sizes.getOrDefault(id, 0L));
            // Reads wait from here on, and this waits for the reads already going, which may
            // still follow the snapshots about to be cut off.
            Optional<LampLock> alone = LampLock.acquireWithin(layout.historyReadLock(), false, LOCK_WAIT);
            if (alone.isEmpty())
                throw new IOException("reads of the history kept going for " + LOCK_WAIT.toMinutes()
                        + " minutes, so nothing was pruned");
            try {
                if (!boundaries.equals(shallow()))
                    durableWrite(repository.resolve("shallow"), boundaries.isEmpty() ? "" : String.join("\n", boundaries) + "\n");
                // A save takes the blob of an unchanged file without looking for it, so the file
                // stats forget the blobs about to go first. Should that fail, none goes.
                Set<String> blobs = new HashSet<>();
                for (Path object : doomed.keySet())
                    blobs.add(repository.resolve("objects").relativize(object).toString().replace("/", ""));
                Path stats = repository.resolve(FILE_STATS);
                if (Files.exists(stats)) {
                    Optional<byte[]> remaining = FileStats.without(Files.readAllBytes(stats), blobs);
                    if (remaining.isPresent()) FilesystemUtil.writeBytes(stats, remaining.get(), PosixMode.PRIVATE_FILE);
                }
                for (var object : doomed.entrySet()) {
                    try {
                        if (Files.deleteIfExists(object.getKey())) actualFreed += object.getValue();
                    } catch (IOException e) { failures.add(ProblemCatalogUtil.reason(e)); }
                }
            } finally {
                alone.get().close();
            }
            freed = actualFreed;
            kept = total - actualFreed;
            if (!failures.isEmpty()) warnings = warnings.add(new LampEvent.Warning(ProblemCatalogUtil.historyPruningFailed(layout.root(),
                    "history boundaries were updated, but some unused objects could not be deleted: " + failures.getFirst())));
        }
        return new Pruning(Optional.of(new LampEvent.HistoryPruned(removed, Tuple.of(String.class, boundaries), freed, kept, limit))
                .filter(event -> !event.removed().isEmpty() || event.bytesFreed() > 0 || event.remainingBytes() > event.limitBytes()),
                warnings);
    }

    /// Accounting for runs whose commit was removed automatically. Manual reset keeps its existing
    /// semantics; automatic maintenance must not reuse numbers or bypass the daily token limit.
    private void rememberPrunedRuns(Tuple<LampSaveOnBranches> removed, Instant now) throws IOException {
        Path file = repository.resolve("pruned-runs");
        int high = prunedHigh();
        Map<String, Instant> recent = prunedAgentRuns(now);
        for (LampSaveOnBranches listed : removed) {
            LampSave snapshot = listed.snapshot();
            if (snapshot.run().isPresent() && snapshot.run().get().matches("run-[0-9]{1,9}"))
                high = Math.max(high, Integer.parseInt(snapshot.run().get().substring(4)));
            if (snapshot.kind() == LampSaveKind.RUN && snapshot.run().isPresent()) {
                GitObjectUtil.Commit commit = commitAt(snapshot.id());
                if (commit.trailers().get(GitObjectUtil.AUTHOR_TRAILER).filter("agent"::equals).isPresent()
                        && !snapshot.at().isBefore(now.minus(Duration.ofDays(1)))) recent.put(snapshot.run().get(), snapshot.at());
            }
        }
        StringBuilder text = new StringBuilder(high + "\n");
        for (var run : recent.entrySet()) text.append(run.getKey()).append(' ').append(run.getValue().getEpochSecond()).append('\n');
        durableWrite(file, text.toString());
    }

    private int prunedHigh() throws IOException {
        Path file = repository.resolve("pruned-runs");
        return Files.exists(file) ? Integer.parseInt(Files.readAllLines(file).getFirst()) : 0;
    }

    private Map<String, Instant> prunedAgentRuns(Instant now) throws IOException {
        Map<String, Instant> runs = new HashMap<>();
        Path file = repository.resolve("pruned-runs");
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines.subList(1, lines.size())) {
                String[] parts = line.split(" ", -1);
                Instant at = Instant.ofEpochSecond(Long.parseLong(parts[1]));
                if (!at.isBefore(now.minus(Duration.ofDays(1)))) runs.put(parts[0], at);
            }
        }
        return runs;
    }

    Result<Integer> highestRun() {
        return reading(() -> Result.ok(Math.max(prunedHigh(), GitObjectUtil.highestRun(allCommits()))));
    }

    Result<Integer> agentRunsInADay(Instant now) {
        return reading(() -> {
            Set<String> runs = new HashSet<>(prunedAgentRuns(now).keySet());
            for (GitObjectUtil.Commit commit : allCommits())
                if (!commit.snapshot().at().isBefore(now.minus(Duration.ofDays(1)))
                        && commit.trailers().get(GitObjectUtil.AUTHOR_TRAILER).filter("agent"::equals).isPresent()
                        && commit.snapshot().kind() == LampSaveKind.RUN) commit.snapshot().run().ifPresent(runs::add);
            return Result.ok(runs.size());
        });
    }

    private void pinBaseUnlocked(String id) throws IOException {
        FilesystemUtil.writeFile(repository.resolve("active-base"), id + "\n", PosixMode.PRIVATE_FILE);
    }

    Result<Optional<String>> pinLatest() {
        return writing(() -> {
            Optional<String> id = headCommit().map(GitObjectUtil.Commit::id);
            if (id.isPresent()) pinBaseUnlocked(id.get());
            return Result.ok(id);
        });
    }

    Result<Boolean> releaseBase() {
        return writing(() -> Result.ok(Files.deleteIfExists(repository.resolve("active-base"))));
    }

    Result<Optional<Changes>> runChanges(Optional<String> base, String tree, int limit) {
        return reading(() -> {
            Optional<String> before = Optional.empty();
            if (base.isPresent()) {
                before = allCommits().stream().filter(c -> c.id().equals(base.get())).findFirst().map(GitObjectUtil.Commit::tree);
                if (before.isEmpty()) return Result.ok(Optional.empty());
            }
            List<Change> found = new ArrayList<>();
            boolean more = compare(before, Optional.of(tree), "", found, limit, true);
            return Result.ok(Optional.of(new Changes(Tuple.of(Change.class, found), more)));
        });
    }

    @FunctionalInterface
    private interface Reading<T> { Result<T> get() throws IOException; }

    /// Force contents before publishing, then force the directory entry before deleting history.
    private static void durableWrite(Path file, String text) throws IOException {
        Path parent = java.util.Objects.requireNonNull(file.getParent());
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".oillamp-", ".tmp");
        try {
            Files.writeString(temporary, text);
            FilesystemUtil.setMode(temporary, PosixMode.PRIVATE_FILE);
            force(temporary);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            force(parent);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }

    /// Runs `work`, which only reads, while holding a share of the read lock.
    ///
    /// Not the history lock: a save holds that for as long as it takes to read a large home, and
    /// a read need not wait for it, because a save only adds files and moves a branch last. Only
    /// the sweep of a prune removes files, and it waits for every read to finish first, so no
    /// read finds a file gone halfway through.
    private <T> Result<T> reading(Reading<T> work) {
        try {
            Optional<LampLock> lock = LampLock.acquireWithin(layout.historyReadLock(), true, LOCK_WAIT);
            if (lock.isEmpty()) return Result.err(ProblemCatalogUtil.historyBusy(layout.root(), LOCK_WAIT));
            try {
                return work.get();
            } finally {
                lock.get().close();
            }
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
    }

    /// Runs `work`, which changes the history, while holding the history lock.
    private <T> Result<T> writing(Reading<T> work) {
        return locked(() -> {
            try { return work.get(); }
            catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
            }
        });
    }

    /// What a locked operation did, and the events that tell the rest, such as what a prune
    /// after a save removed. The events are reported once the lock is released, so that a
    /// listener can read the history straight away.
    private record Done<T>(Result<T> result, Tuple<LampEvent> events) {}

    /// Runs `work` while holding the history lock, waiting for another save, restore, switch,
    /// reset, or start or deletion of a branch of this lamp to finish first.
    private <T> Result<T> locked(Supplier<Result<T>> work) {
        return lockedThenReport(() -> new Done<>(work.get(), Tuple.of(LampEvent.class)));
    }

    /// The same, and then reports the events `work` gave.
    private <T> Result<T> lockedThenReport(Supplier<Done<T>> work) {
        Duration pause = Duration.ofMillis(200);
        try {
            for (long waited = 0; ; waited++) {
                Optional<LampLock> lock = LampLock.tryAcquire(layout.historyLock());
                if (lock.isPresent()) {
                    Done<T> done;
                    try {
                        done = work.get();
                    } finally {
                        lock.get().close();
                    }
                    for (LampEvent event : done.events()) sink.accept(event);
                    return done.result();
                }
                if (pause.multipliedBy(waited).compareTo(LOCK_WAIT) > 0)
                    return Result.err(ProblemCatalogUtil.historyBusy(layout.root(), LOCK_WAIT));
                Thread.sleep(pause);
            }
        } catch (IOException e) {
            return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), ProblemCatalogUtil.reason(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), "interrupted while waiting for another save"));
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static int bitOf(PosixFilePermission permission) {
        return switch (permission) {
            case OWNER_READ     -> 0400;
            case OWNER_WRITE    -> 0200;
            case OWNER_EXECUTE  -> 0100;
            case GROUP_READ     -> 040;
            case GROUP_WRITE    -> 020;
            case GROUP_EXECUTE  -> 010;
            case OTHERS_READ    -> 04;
            case OTHERS_WRITE   -> 02;
            case OTHERS_EXECUTE -> 01;
        };
    }

    private static int bitsOf(Set<PosixFilePermission> permissions) {
        int bits = 0;
        for (PosixFilePermission permission : permissions) bits |= bitOf(permission);
        return bits;
    }

    private static Set<PosixFilePermission> permissionsOf(int bits) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission permission : PosixFilePermission.values())
            if ((bits & bitOf(permission)) != 0) permissions.add(permission);
        return permissions;
    }

    private static String octal(int bits) {
        return String.format("%04o", bits);
    }
}
