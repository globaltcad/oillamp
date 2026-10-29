package dev.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import dev.gui.desktop.Desktop;
import dev.gui.genie.LampLighterImpl;
import dev.gui.genie.Shelf;
import dev.gui.genie.SystemLook;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.History;
import dev.gui.model.Home;
import dev.gui.model.Settings;
import dev.lamp.LampLauncher;
import sprouts.Tuple;
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
        Function<String, Optional<String>> environment = name -> Optional.ofNullable(System.getenv(name));
        return GeniesBackend.open(shelf, errors, new LampLighterImpl(), environment, SystemLook.ofThisComputer(environment));
    }

    /// The app as [#open(Shelf, ErrorLog)] opens it, from the shelf in `directory` as if Genies had
    /// left `genies` and `settings` on it when it last closed, its errors logged there too. The
    /// genies' lamps are started by `launcher`, such as oillamp's engine in this JVM on a
    /// simulated machine, and Genies is as if started from `environment`, by variable. Whether
    /// the desktop is dark, it learns from that environment alone, not from this computer.
    ///
    /// @throws IOException when the genies or the settings could not be put on the shelf
    static Genies open(
        final Path directory,
        final Tuple<Genie> genies,
        final Settings settings,
        final LampLauncher launcher,
        final Function<String, Optional<String>> environment
    ) throws IOException {
        Shelf shelf = new Shelf(directory);
        shelf.keep(genies);
        shelf.keep(settings);
        return GeniesBackend.open(shelf, new ErrorLog(shelf.errorLog()), new LampLighterImpl(launcher), environment, SystemLook.ofEnvironment(environment));
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

    /// Deletes the genie and its lamp, for good. The window has asked the user already. A genie
    /// whose lamp is from elsewhere only leaves Genies: its lamp stays as it is, running if it runs.
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

    /// Looks for lamps someone lit elsewhere, with `oillamp at`, and takes in each Genies does
    /// not know yet, after its own genies, joining its session. The user's selection stays.
    /// Genies does this by itself, now and then; this looks now. Off Swing's event thread.
    void lookForLamps();

    /// Takes in the lamp in `directory`, which the user picked, lit or not, and shows it: one
    /// someone made elsewhere with `oillamp at`. Removing it from Genies later leaves it as it is.
    ///
    /// @throws IOException when there is no lamp in `directory`, or it is one of Genies' own; the
    ///                     message says so, to the user
    void addLamp(Path directory) throws IOException;

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

    /// Writes how far the genie's history may grow beyond its first moment, in GB, into its lamp;
    /// 0 never deletes a moment. Only while it sleeps.
    void maxGrowth(UUID genie, double gb);

    /// Asks the genie's lamp which old moments keeping its history within its size would delete
    /// now; the history page then asks the user. Only while it sleeps.
    void planPruning(UUID genie);

    /// Deletes those moments. The user confirmed. Only while it sleeps.
    void prune(UUID genie);

    /// Starts the branch `name` of the genie's history at the moment `moment`, or at the newest
    /// moment of the branch it lives on when `moment` is empty. The genie stays where it lives.
    /// Awake or asleep. The window checked the name with [History#branchNameProblem].
    void startBranch(UUID genie, String name, String moment);

    /// Moves the genie to the branch `branch` of its history: its home becomes that branch's.
    /// Only while it sleeps. The window has asked the user already.
    void switchBranch(UUID genie, String branch);

    /// Asks the genie's lamp how many moments only the branch `branch` holds; the history page
    /// then asks the user.
    void planDeletingBranch(UUID genie, String branch);

    /// Deletes the branch `branch`, which the genie does not live on. The user confirmed.
    void deleteBranch(UUID genie, String branch);

    /// Asks the genie's lamp what resetting the branch it lives on back to `moment` would throw
    /// away; the history page then asks the user. Only while it sleeps.
    void planReset(UUID genie, String moment);

    /// Resets the branch the genie lives on back to `moment`, for good: what came after it leaves
    /// the branch. The user confirmed. Only while it sleeps.
    void reset(UUID genie, String moment);

    /// Writes the rules the genie's host access page shows into its lamp. Only while it sleeps.
    void saveHostAccess(UUID genie);

    /// The user allows or refuses a request the genie's lamp asked them about, named by its words.
    void decidePermission(UUID genie, String request, boolean allowed);

    /// Reads the genie's home folder `folder`, by names below its home, into its [Genie#home]:
    /// the folders in it, and theirs. With an empty `folder`, the home itself, which the app
    /// first looks for. Off Swing's event thread.
    void readHome(UUID genie, Tuple<String> folder);

    /// The genie's home folder `folder`, by names below its home, on this computer: there if
    /// every step to it is a folder, none of them a link the genie made.
    Optional<Path> folderOf(UUID genie, Tuple<String> folder);

    /// Puts a link to the genie's home folder `folder` into the user's folder `into`, called
    /// `name`; or `name 2`, and so on, when that is taken by something else. A link of that name
    /// to the same folder is kept as it was. Quick: a link is one small file.
    ///
    /// @throws IOException when there is no such folder, `name` is no plain name, or the link
    ///                     could not be made; its message says which, to the user
    Home.Link link(UUID genie, Tuple<String> folder, Path into, String name) throws IOException;

    /// Opens the genie's home folder `folder` in this computer's file manager.
    void showInFileManager(UUID genie, Tuple<String> folder);

    /// The user's documents folder, such as `~/Documents`, where a genie's folders are linked
    /// unless the user picks another.
    Path documents();

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

    /// The genie's lamp: the directory oillamp keeps it in, whether or not it was ever lit.
    Path lampOf(UUID genie);
}
