package dev.promo;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import dev.gui.Genies;
import dev.gui.desktop.Desktop;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.History;
import dev.gui.model.Home;
import dev.gui.model.LampWindow;
import sprouts.Tuple;
import sprouts.Var;

/// Genies as the window sees it, with nothing behind it: no lamp is lit and no model is asked.
/// The storyboard plays the app's part, by changing [#state] as the app would, so that the window
/// that is filmed is the real one, drawing a story that is told the same way each time.
///
/// What the window asks for is done only where a scene needs it: sending the draft, and the
/// desktop of a genie, which [#desktop] stands in for.
final class StagedGenies implements Genies {

    private final Var<GeniesState> state;
    /// The desktop every awake genie shows, once a scene has one.
    Optional<Desktop> desktop = Optional.empty();

    StagedGenies(GeniesState state) { this.state = Var.of(state); }

    @Override public Var<GeniesState> state() { return state; }

    @Override public void send() { state.update(it -> it.withGenie(it.genie().send())); }

    @Override public Optional<Desktop> desktopOf(UUID genie) { return desktop; }

    @Override public void settingsDone() { state.update(it -> it.withPage(GeniesState.Page.CHAT)); }

    @Override public Path documents() { return Path.of(System.getProperty("user.home"), "Documents"); }
    @Override public Path errorLog() { return Path.of("errors.log"); }
    @Override public Path lampOf(UUID genie) { return Path.of("lamps", genie.toString()); }
    @Override public Optional<Path> folderOf(UUID genie, Tuple<String> folder) { return Optional.empty(); }
    @Override public Home.Link link(UUID genie, Tuple<String> folder, Path into, String name) throws IOException {
        throw new IOException("Nothing is linked while the promo is filmed.");
    }

    @Override public void newGenie() {}
    @Override public void wake(UUID genie) { state.update(it -> it.update(genie, Genie::waking)); }
    @Override public void sleep(UUID genie) {}
    @Override public void stop(UUID genie) {}
    @Override public void delete(UUID genie) {}
    @Override public void goTo(UUID genie, String conversation, String leaf) {}
    @Override public void startAfresh(UUID genie) {}
    @Override public void forget(UUID genie, String conversation) {}
    @Override public void askInstead(String id, String text) {}
    @Override public void lookForLamps() {}
    @Override public void addLamp(Path directory) {}
    @Override public void giveFile(UUID genie, Path file) {}
    @Override public void saveOutboxFile(UUID genie, String name, Path target) {}
    @Override public void saveJob(UUID genie) {}
    @Override public void removeJob(UUID genie, String job) {}
    @Override public void switchJob(UUID genie, String job, boolean on) {}
    @Override public void pauseSchedule(UUID genie, boolean paused) {}
    @Override public void stopRun(UUID genie, String run) {}
    @Override public void save(UUID genie, String message) {}
    @Override public void goBack(UUID genie, String moment) {}
    @Override public void autosave(UUID genie, History.Autosave wanted) {}
    @Override public void maxGrowth(UUID genie, double gb) {}
    @Override public void planPruning(UUID genie) {}
    @Override public void prune(UUID genie) {}
    @Override public void startBranch(UUID genie, String name, String moment) {}
    @Override public void switchBranch(UUID genie, String branch) {}
    @Override public void planDeletingBranch(UUID genie, String branch) {}
    @Override public void deleteBranch(UUID genie, String branch) {}
    @Override public void planReset(UUID genie, String moment) {}
    @Override public void reset(UUID genie, String moment) {}
    @Override public void saveHostAccess(UUID genie) {}
    @Override public void decidePermission(UUID genie, String request, boolean allowed) {}
    @Override public void readHome(UUID genie, Tuple<String> folder) {}
    @Override public void showInFileManager(UUID genie, Tuple<String> folder) {}
    @Override public void openInBrowser(URI link) {}
    @Override public void open(UUID genie, LampWindow window, Consumer<String> whenNot) {}
    @Override public void openConversation(UUID genie, String conversation) {}
    @Override public void lookUpModels() {}
    @Override public void setUpOllama() {}
    @Override public void stopSettingUp() {}
    @Override public void quit(boolean leaveRunning, Optional<Thread> first, Runnable whenDone) { whenDone.run(); }
}
