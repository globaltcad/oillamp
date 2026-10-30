package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Genie;
import dev.gui.model.History;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;
import dev.lamp.LampDirectory;
import dev.lamp.LampFailedException;
import dev.lamp.LampSave;

import sprouts.Tuple;

/// Reads a genie's history from its lamp, with every branch, and saves its home there as a new
/// moment, awake or asleep. Also starts and deletes branches, which works awake or asleep too, and
/// asks the lamp first what deleting a branch, resetting one or keeping the history within its
/// size would throw away. Going back to a moment, moving to another branch and resetting one are
/// [GenieRunner#goBackTo], [GenieRunner#switchTo] and [GenieRunner#reset], which change the
/// genie's home and so need its lamp out.
///
/// Also reads and writes when oillamp saves by itself, with [LampDirectory#autosave()] and
/// [LampDirectory#autosave(Lamp.Autosave)], and how far the history may grow, with
/// [LampDirectory#historyMaxGrowthGb()] and [LampDirectory#historyMaxGrowthGb(double)]; writing
/// needs the genie asleep, and the page only offers it then.
///
/// Each call starts an engine process that ends by itself, which takes a moment, so the work is
/// done one piece after the other on a thread of its own, and the calls return at once. Each
/// reading is numbered as it starts, and [History#readAs] shows only one newer than what it shows.
public final class HistoryKeeper {

    private final Path directory;
    private final LampDirectory lamp;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("history").factory());
    /// Whether a reading waits its turn already; one reading then covers every change before it.
    private final AtomicBoolean readingQueued = new AtomicBoolean();
    /// The number of the newest reading started.
    private final AtomicLong readings = new AtomicLong();
    /// Whether the history was ever asked for. Until then, nothing shows it, and news from the
    /// lamp does not make the keeper read it.
    private volatile boolean watched;

    /// @param lamp the genie's lamp, unlit: [LampLighter#unlit]. What the engine reports while it
    ///             works for the keeper, such as a save that deleted old moments, is heard too
    public HistoryKeeper(Path directory, LampDirectory lamp, Consumer<UnaryOperator<Genie>> changes) {
        this.directory = directory;
        this.lamp = lamp.onEvent(this::onLampEvent);
        this.changes = changes;
    }

    /// The genie's lamp, unlit, for the runner's changes to the history: what the engine reports
    /// while it makes them reaches the keeper.
    LampDirectory lamp() { return lamp; }

    /// Reads the history, on the keeper's thread, after whatever waits there already. A reading
    /// that waits its turn already covers this one too. A change the keeper makes reads the
    /// history itself, right after it, so a reading that waited before the change cannot be
    /// the last one.
    public void read() {
        watched = true;
        if (!readingQueued.compareAndSet(false, true)) return;
        work.execute(() -> {
            readingQueued.set(false);
            readNow();
        });
    }

    /// Reads the history on the keeper's thread, now.
    private void readNow() {
        long reading = readings.incrementAndGet();
        // A genie that never woke has no lamp yet, and so nothing saved.
        if (Lamp.agentHome(directory).isEmpty()) {
            change(it -> it.readAs(reading, Tuple.of(History.Branch.class), Tuple.of(History.Moment.class)));
            return;
        }
        try {
            var branches = LampApiConversionUtil.branches(lamp.branches());
            var moments = LampApiConversionUtil.moments(lamp.historyOfAllBranches());
            change(it -> it.readAs(reading, branches, moments));
        } catch (IOException | LampFailedException failed) {
            change(it -> it.withProblem("The history could not be read: " + reason(failed)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Reads the lamp's file: when oillamp saves by itself, and how far the history may grow. A
    /// genie that never woke has no lamp yet, and so nothing to read. Apart from [#read], which
    /// follows every save: these change only when the user changes them, or when the genie goes
    /// back or moves to another branch, which brings another file.
    public void readSettings() {
        work.execute(() -> {
            if (Lamp.agentHome(directory).isEmpty()) {
                change(it -> it.withAutosave(Optional.empty()).withMaxGrowthGb(Optional.empty()));
                return;
            }
            try {
                Lamp.Autosave read = lamp.autosave();
                double gb = lamp.historyMaxGrowthGb();
                change(it -> it.withAutosave(Optional.of(asShown(read))).withMaxGrowthGb(Optional.of(gb)));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withProblem("The history's settings could not be read: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Writes `wanted` into the lamp's file. The page shows it at once, then what the file has,
    /// or, when it could not be written, what it had and why.
    public void autosave(History.Autosave wanted) {
        change(it -> it.withAutosave(Optional.of(wanted)).withProblem(""));
        work.execute(() -> {
            try {
                Lamp.Autosave written = lamp.autosave(new Lamp.Autosave(wanted.waking(), wanted.sleeping(),
                                                                         wanted.beforeRun(), wanted.afterRun(),
                                                                         wanted.chatQuietMinutes()));
                change(it -> it.withAutosave(Optional.of(asShown(written))));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withProblem("oillamp could not change when it saves: " + reason(failed)));
                readSettings();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Writes how far the history may grow beyond its first moment into the lamp's file, as
    /// [#autosave] does. Nothing is deleted until the next save, or [#prune].
    ///
    /// @param gb from 0 to 10000; 0 never deletes a moment
    public void maxGrowth(double gb) {
        change(it -> it.withMaxGrowthGb(Optional.of(gb)).withProblem(""));
        work.execute(() -> {
            try {
                double written = lamp.historyMaxGrowthGb(gb);
                change(it -> it.withMaxGrowthGb(Optional.of(written)));
            } catch (IOException | LampFailedException | IllegalArgumentException failed) {
                change(it -> it.withProblem("oillamp could not change how far the history may grow: " + reason(failed)));
                readSettings();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static History.Autosave asShown(Lamp.Autosave lamps) {
        return new History.Autosave(lamps.startup(), lamps.shutdown(), lamps.beforeRun(), lamps.afterRun(),
                                    lamps.chatQuietMinutes());
    }

    /// Saves the genie's home as a new moment, which is then picked. When nothing changed since
    /// the newest moment, oillamp saves nothing; the newest moment is picked, and the note says why.
    /// The chat says what came of it too, since the user may have saved from there.
    ///
    /// @param message what to remember the moment by; may be empty
    public void save(String message) {
        watched = true;
        change(it -> it.withBusy("Saving…").withNote("").withProblem(""));
        work.execute(() -> {
            try {
                Optional<LampSave> saved = lamp.save(message.strip());
                String nothingChanged = "Nothing changed since the newest moment, so it holds how the genie is now already.";
                change(it -> saved.isPresent() ? it.withBusy("").withPicked(saved.get().id())
                        : it.withBusy("").withPicked(it.moments().isEmpty() ? "" : it.moments().first().id()).withNote(nothingChanged));
                String told = saved.isEmpty() ? nothingChanged
                        : "Saved as a moment of the history" + (message.isBlank() ? "" : ": " + message.strip())
                          + ". The history page brings the genie back to it.";
                changes.accept(genie -> genie.withTranscript(genie.transcript().notice(told)));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy("").withProblem("oillamp could not save: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            readNow();
        });
    }

    // ─── branches ──────────────────────────────────────────────────────────────────────────

    /// Starts the branch `name` at the moment `moment`, or, when that is empty, at the newest
    /// moment of the branch the genie lives on. The genie stays on its branch, awake or asleep.
    public void startBranch(String name, String moment) {
        watched = true;
        change(it -> it.withBusy("Starting the branch " + name + "…").withNote("").withProblem("").withPlan(Optional.empty()));
        work.execute(() -> {
            try {
                if (moment.isEmpty()) lamp.branch(name);
                else lamp.branch(name, moment);
                change(it -> it.withBusy(""));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy("").withProblem("oillamp could not start the branch " + name + ": " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            readNow();
        });
    }

    /// Asks the lamp how many moments only the branch `name` holds, which deleting it would lose,
    /// for the page to ask the user.
    public void planDeletingBranch(String name) {
        plan("Looking at what the branch " + name + " holds…", "deleting the branch " + name, () -> {
            LampEvent.BranchDeletePlanned planned = lamp.wouldDeleteBranch(name);
            return Optional.of(new History.Plan.DeleteBranch(planned.branch(), planned.snapshots()));
        });
    }

    /// Deletes the branch `name`, which the genie does not live on. The user confirmed.
    public void deleteBranch(String name) {
        watched = true;
        change(it -> it.withBusy("Deleting the branch " + name + "…").withProblem("").withPlan(Optional.empty()));
        work.execute(() -> {
            try {
                lamp.deleteBranch(name);
                change(it -> it.withBusy(""));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy("").withProblem("oillamp could not delete the branch " + name + ": " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            readNow();
        });
    }

    /// Asks the lamp what resetting the branch the genie lives on back to `moment` would throw
    /// away, for the page to ask the user. Only while the genie sleeps.
    public void planReset(String moment) {
        plan("Looking at what a reset would throw away…", "resetting", () -> {
            LampEvent.ResetPlanned planned = lamp.wouldReset(moment);
            return Optional.of(new History.Plan.Reset(planned.target().id(), planned.snapshots(), planned.unsaved()));
        });
    }

    // ─── its size ──────────────────────────────────────────────────────────────────────────

    /// Asks the lamp which moments keeping the history within its size would delete now, for the
    /// page to ask the user; when none, the page says so instead. Only while the genie sleeps.
    public void planPruning() {
        plan("Looking at what does not fit…", "tidying up", () -> {
            Optional<LampEvent.HistoryPrunePlanned> planned = lamp.wouldPruneHistory();
            Optional<History.Plan> pruning = planned.filter(it -> !it.removed().isEmpty()).map(LampApiConversionUtil::pruning);
            if (pruning.isEmpty())
                change(it -> it.withNote("The history fits within its size, so there is nothing to delete."));
            return pruning;
        });
    }

    /// Deletes the moments saved longest ago, to keep the history within its size. The user
    /// confirmed. Only while the genie sleeps.
    public void prune() {
        watched = true;
        change(it -> it.withBusy("Deleting old moments…").withProblem("").withPlan(Optional.empty()));
        work.execute(() -> {
            try {
                lamp.pruneHistory();
                change(it -> it.withBusy(""));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy("").withProblem("oillamp could not delete old moments: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            readNow();
        });
    }

    /// What a change would throw away, as the lamp says.
    @FunctionalInterface
    private interface Asking {
        Optional<History.Plan> ask() throws IOException, InterruptedException, LampFailedException;
    }

    /// Asks the lamp what a change would throw away, saying `busy` meanwhile, and shows it as the
    /// plan the user confirms or drops; `what` names the change if the lamp refuses.
    private void plan(String busy, String what, Asking asking) {
        change(it -> it.withBusy(busy).withNote("").withProblem("").withPlan(Optional.empty()));
        work.execute(() -> {
            try {
                Optional<History.Plan> plan = asking.ask();
                change(it -> it.withBusy("").withPlan(plan));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy("").withProblem("oillamp refused " + what + ": " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ─── news ──────────────────────────────────────────────────────────────────────────────

    /// What the lamp reports, while the genie is awake and while the keeper works: a save, or a
    /// run's end, which saves too, adds a moment; a save past the history's size deletes old ones.
    void onLampEvent(LampEvent event) {
        switch (event) {
            case LampEvent.Saved _ -> { if (watched) read(); }
            case LampEvent.RunFinished _ -> { if (watched) read(); }
            case LampEvent.HistoryPruned pruned -> {
                change(it -> it.withPruned(Optional.of(LampApiConversionUtil.pruned(pruned))));
                if (watched) read();
            }
            default -> { }
        }
    }

    /// The genie's lamp went out, and saved its home as it did. A genie that woke for the first
    /// time has a lamp now, and so a file with the history's settings.
    void wentOut() {
        if (!watched) return;
        read();
        readSettings();
    }

    private void change(UnaryOperator<History> change) {
        changes.accept(genie -> genie.withHistory(change.apply(genie.history())));
    }

    static String reason(Exception failed) {
        if (failed instanceof LampFailedException refused) return refused.problem().whatHappened();
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
