package dev.gui;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import dev.gui.desktop.Desktop;
import dev.gui.genie.LampLighterImpl;
import dev.gui.genie.Shelf;
import dev.gui.model.GeniesState;
import dev.gui.model.History;
import dev.lamp.LampLauncher;
import sprouts.Var;

/// The app as the window sees it: its state, which the window draws and changes, and what the
/// window asks it to do beyond that: everything that starts, stops or moves something outside the
/// window. The app, [GeniesBackend], does it.
public interface Genies {

    /// The app with the genies and the settings `shelf` keeps, without a window: each genie's
    /// tree of conversations and host access read, those left running when Genies last closed
    /// joined again, and Ollama looked for. What goes wrong from then on, which `errors` is told,
    /// is in its state too. [Main] gives it its window.
    static Genies open(Shelf shelf, ErrorLog errors) {
        return GeniesBackend.open(shelf, errors, new LampLighterImpl(), name -> Optional.ofNullable(System.getenv(name)));
    }

    /// The app as [#open(Shelf, ErrorLog)] opens it, but with the genies' lamps started by
    /// `launcher`, such as oillamp's engine in this JVM on a simulated machine, and as if Genies
    /// was started from `environment`, by variable.
    static Genies open(Shelf shelf, ErrorLog errors, LampLauncher launcher, Function<String, Optional<String>> environment) {
        return GeniesBackend.open(shelf, errors, new LampLighterImpl(launcher), environment);
    }

    /// Everything the window shows. The window changes it through lenses, such as a draft, the
    /// settings or the selected genie; the app changes it for everything else.
    Var<GeniesState> state();

    void newGenie();

    void wake(UUID genie);

    void sleep(UUID genie);

    /// Sends the selected genie's draft.
    void send();

    /// Stops what the genie is doing.
    void stop(UUID genie);

    /// Deletes the genie and its lamp, for good. The window has asked the user already.
    void delete(UUID genie);

    /// Goes to a conversation of the genie's, and within it to the entry `leaf`, waking the
    /// genie first if it sleeps.
    ///
    /// @param conversation its session file, relative to the genie's home
    /// @param leaf         the entry to continue after, or nothing for where pi left it
    void goTo(UUID genie, String conversation, String leaf);

    /// Starts a new conversation with the genie, waking it first if it sleeps.
    void startAfresh(UUID genie);

    /// Deletes a conversation of the genie's for good. The window has asked the user already.
    void forget(UUID genie, String conversation);

    /// Asks `text` instead of the selected genie's question `id`; the old question stays in the
    /// conversation as a branch of its own.
    void askInstead(String id, String text);

    /// Into the genie's inbox.
    void giveFile(UUID genie, Path file);

    /// To where the user chose.
    void saveOutboxFile(UUID genie, String name, Path target);

    /// Adds the job written in the genie's schedule editor, or changes the job it replaces.
    void saveJob(UUID genie);

    /// Takes a job off the genie's schedule. The window has asked the user already.
    void removeJob(UUID genie, String job);

    void switchJob(UUID genie, String job, boolean on);

    /// Pauses the genie's whole schedule, or lets it run again.
    void pauseSchedule(UUID genie, boolean paused);

    /// Stops a run the genie has going, such as a job's.
    void stopRun(UUID genie, String run);

    /// Saves the genie's home as a moment of its history, awake or asleep.
    ///
    /// @param message what to remember it by; may be empty
    void save(UUID genie, String message);

    /// Brings the genie's home back to its history's moment `moment`. An awake genie sleeps
    /// meanwhile, and wakes again after. The window has asked the user already.
    void goBack(UUID genie, String moment);

    /// Writes when oillamp saves the genie's home by itself into its lamp. Only while it sleeps.
    void autosave(UUID genie, History.Autosave wanted);

    /// Writes the rules the genie's host access page shows into its lamp. Only while it sleeps.
    void saveHostAccess(UUID genie);

    /// The user allows or refuses a request the genie's lamp asked them about, named by its words.
    void decidePermission(UUID genie, String request, boolean allowed);

    /// Shows the genie's chat at the conversation pi knows by `conversation`, such as the one a
    /// job's run had.
    void openConversation(UUID genie, String conversation);

    /// Asks the model service the settings name which models it offers.
    void lookUpModels();

    /// Sets up Ollama on this computer with the model the settings want, and has the genies use
    /// it: whatever is left of installing Ollama, starting it, and getting the model ready.
    void setUpOllama();

    /// Stops setting up Ollama.
    void stopSettingUp();

    /// The settings page closes. Leaving it, however, keeps the settings.
    void settingsDone();

    /// Keeps the genies and the settings at once. Then, once `first` has ended, such as the
    /// window giving a desktop its own size back, puts every genie to sleep, or leaves it running,
    /// and calls `whenDone` on Swing's event thread once each lamp has gone out or been let go.
    /// (If Genies is killed instead, the lamps end by themselves: each notices its application
    /// is gone.)
    void quit(boolean leaveRunning, Optional<Thread> first, Runnable whenDone);

    /// Where what went wrong that Genies did not expect is written.
    Path errorLog();

    /// The genie's desktop, while it is awake.
    Optional<Desktop> desktopOf(UUID genie);
}
