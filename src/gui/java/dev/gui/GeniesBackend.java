package dev.gui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.swing.SwingUtilities;

import dev.gui.desktop.Desktop;
import dev.gui.genie.GenieHomeUtil;
import dev.gui.genie.GenieRunner;
import dev.gui.genie.LampLighter;
import dev.gui.genie.OllamaKeeper;
import dev.gui.genie.Shelf;
import dev.gui.genie.SystemLook;
import dev.gui.model.Conversation;
import dev.gui.model.Conversations;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.History;
import dev.gui.model.Home;
import dev.gui.model.OllamaSetup;
import dev.gui.model.Palette;
import dev.gui.model.Settings;
import dev.lamp.LampExitStatus;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;

/// Genies: a chat app whose every conversation partner is an AI agent with a sandboxed Linux
/// desktop of its own, a "genie" living in an oillamp lamp.
///
/// It is also what oillamp's embedding API, [Lamp], was made for, used the way any application
/// would use it: this package imports `dev.lamp` and never `dev.oillamp`.
///
/// This class holds the app together, without a window; [Main] gives it one. The window draws
/// one [GeniesState] and asks for everything else through [Genies]; each genie's [GenieRunner]
/// reports what happens as changes to its genie, which are applied here, on Swing's event
/// thread, one at a time.
public final class GeniesBackend implements Genies {

    /// How long closing or deleting waits for a genie's lamp to go out or be let go.
    private static final long SECONDS_TO_LET_GO_OF_A_LAMP = 180;

    /// What setting up Ollama says once the user stopped it.
    private static final String STOPPED = "Stopped. What was downloaded of a model is kept, and getting it again goes on from there.";

    private final Var<GeniesState> state;
    private final Shelf shelf;
    private final LampLighter lighter;
    private final OllamaKeeper ollama;
    private final Map<UUID, GenieRunner> runners = new ConcurrentHashMap<>();
    /// The thread setting up Ollama, while one does; only touched on Swing's event thread.
    private Optional<Thread> settingUp = Optional.empty();
    /// Whether the user stopped setting up Ollama. A download that is cut short may fail rather
    /// than notice that it was interrupted, and is no failure then.
    private volatile boolean stopping = false;
    /// Each genie's id and name. Kept as a field: when it changes, the genies are put on the
    /// shelf, and a view nobody holds would be forgotten.
    private final Val<Tuple<String>> names;
    /// The window's palette. Kept as a field for the same reason: another one is kept on the shelf.
    private final Val<Palette> palette;
    /// The page on show. Kept as a field for the same reason: leaving the settings keeps them.
    private final Val<GeniesState.Page> page;
    /// The place the settings on show name, or nothing while they are not on show. Kept as a
    /// field for the same reason: opening the settings, or choosing another place, asks that
    /// place for its models.
    private final Val<String> placeShown;
    /// The genie whose schedule is on show, or [GeniesState#NONE]. Kept as a field for the same
    /// reason: a schedule is read from its lamp when it comes on show.
    private final Val<UUID> scheduleShown;
    /// The genie whose history is on show, or [GeniesState#NONE]. Kept as a field for the same
    /// reason: a history is read from its lamp when it comes on show.
    private final Val<UUID> historyShown;
    /// The genie whose host access is on show, or [GeniesState#NONE]. Kept as a field for the
    /// same reason: the host access is read from its lamp when it comes on show.
    private final Val<UUID> hostAccessShown;
    /// The genie whose Actions page is on show and its phase, or nothing. Kept as a field for the
    /// same reason: its home is looked for when the page comes on show, and again as the genie
    /// wakes, which makes it the first time.
    private final Val<String> actionsShown;
    /// Where the user's documents are, where a genie's folders are linked unless they pick another.
    private final Path documents;
    /// The lamps from elsewhere the user removed, which Genies does not take in again when it
    /// finds them lit. Only touched on Swing's event thread.
    private Tuple<Path> removedLamps;
    /// How often Genies looks for lamps lit elsewhere. Each look starts oillamp's engine once,
    /// which asks podman, so not more often than this.
    private static final Duration LOOKING_FOR_LAMPS_EVERY = Duration.ofMinutes(1);
    /// The thread that looks for lamps now and then, until Genies quits.
    private volatile Optional<Thread> lookingForLamps = Optional.empty();

    GeniesBackend(Var<GeniesState> state, Shelf shelf, LampLighter lighter, OllamaKeeper ollama) {
        this(state, shelf, lighter, ollama, GenieHomeUtil.documents(name -> Optional.ofNullable(System.getenv(name))));
    }

    GeniesBackend(Var<GeniesState> state, Shelf shelf, LampLighter lighter, OllamaKeeper ollama, Path documents) {
        this.state = state;
        this.shelf = shelf;
        this.lighter = lighter;
        this.ollama = ollama;
        this.documents = documents;
        this.removedLamps = shelf.removedLamps();
        this.names = state.viewAs(Tuple.classTyped(String.class),
                it -> it.genies().mapTo(String.class, genie -> genie.id() + " " + genie.name()));
        Viewable.cast(names).onChange(From.ALL, _ -> keepGenies());
        // The palette is kept as soon as the user picks another.
        this.palette = state.viewAs(Palette.class, GeniesState::palette);
        Viewable.cast(palette).onChange(From.ALL, _ -> keepPalette());
        // The settings are kept however the user leaves them: with Done, or by clicking a genie.
        this.page = state.viewAs(GeniesState.Page.class, GeniesState::page);
        // Shown, they look again whether Ollama is there, and what it has.
        Viewable.cast(page).onChange(From.ALL, it -> {
            if (it.currentValue().orElseNull() != GeniesState.Page.SETTINGS) {
                keepSettings();
                useTheSettingsModel();
            }
            else lookForOllama();
        });
        this.placeShown = state.viewAsString(it -> it.page() == GeniesState.Page.SETTINGS ? it.settings().place().name() : "");
        Viewable.cast(placeShown).onChange(From.ALL, it -> {
            if (!it.currentValue().orElse("").isEmpty()) lookUpModels();
        });
        this.scheduleShown = state.viewAs(UUID.class, it -> it.page() == GeniesState.Page.SCHEDULE ? it.selected() : GeniesState.NONE);
        Viewable.cast(scheduleShown).onChange(From.ALL, it -> {
            UUID shown = it.currentValue().orElse(GeniesState.NONE);
            if (!shown.equals(GeniesState.NONE)) runner(shown).schedule().read();
        });
        this.historyShown = state.viewAs(UUID.class, it -> it.page() == GeniesState.Page.HISTORY ? it.selected() : GeniesState.NONE);
        Viewable.cast(historyShown).onChange(From.ALL, it -> {
            UUID shown = it.currentValue().orElse(GeniesState.NONE);
            // The schedule too, whose jobs give their runs' moments their titles.
            if (!shown.equals(GeniesState.NONE)) {
                // When it saves first: until the moments are read, the page says it is reading.
                runner(shown).history().readSettings();
                runner(shown).history().read();
                runner(shown).schedule().read();
            }
        });
        this.hostAccessShown = state.viewAs(UUID.class, it -> it.page() == GeniesState.Page.HOST_ACCESS ? it.selected() : GeniesState.NONE);
        Viewable.cast(hostAccessShown).onChange(From.ALL, it -> {
            UUID shown = it.currentValue().orElse(GeniesState.NONE);
            if (!shown.equals(GeniesState.NONE)) runner(shown).hostAccess().read();
        });
        this.actionsShown = state.viewAsString(it -> it.page() != GeniesState.Page.ACTIONS ? ""
                : it.find(it.selected()).map(genie -> genie.id() + " " + genie.phase()).orElse(""));
        Viewable.cast(actionsShown).onChange(From.ALL, _ -> {
            GeniesState now = state.get();
            if (now.page() == GeniesState.Page.ACTIONS && now.find(now.selected()).isPresent())
                readHome(now.selected(), Tuple.of(String.class));
        });
    }

    /// The app as it is on the shelf, without a window. See [Genies#open].
    ///
    /// @param lighter     lights the genies' lamps
    /// @param environment the environment Genies was started from, by variable
    /// @param desktop     whether the desktop is dark or light, for the palette until the user
    ///                    picks one
    static GeniesBackend open(
        final Shelf shelf,
        final ErrorLog errors,
        final LampLighter lighter,
        final Function<String, Optional<String>> environment,
        final SystemLook desktop
    ) {
        Optional<String> environmentKey = environment.apply(Settings.KEY_VARIABLE).filter(key -> !key.isBlank());
        OllamaKeeper ollama = new OllamaKeeper(shelf.ollama());
        // The first time, the genies' model is where this computer has one: Ollama, if it is
        // installed; otherwise Eden AI, if a key for it was found; otherwise Ollama, which
        // Genies installs then.
        Settings settings = shelf.hasSettings() ? shelf.settings()
                : shelf.settings().withPlace(ollama.program().isEmpty() && environmentKey.isPresent()
                                             ? Settings.Place.EDEN_AI : Settings.Place.THIS_MACHINE);
        // The palette the user picked; before they ever did, the one that fits the desktop, so
        // that Genies comes light on a light desktop; and the lamp at night when nothing says.
        Palette palette = shelf.palette().or(desktop::palette).orElse(Palette.DARK);
        GeniesBackend app = new GeniesBackend(Var.of(GeniesState.of(shelf.genies(), settings, environmentKey).withPalette(palette)),
                                shelf, lighter, ollama, GenieHomeUtil.documents(environment));
        errors.showIn(trouble -> SwingUtilities.invokeLater(() -> app.state.update(it -> it.withTrouble(trouble))));
        // The tree of conversations under each genie is there before any genie wakes, and so is
        // whether it may reach this computer, which its card says. A genie left running when
        // Genies last closed is awake again.
        app.state.get().genies().forEach(genie -> {
            app.runner(genie.id()).reloadConversations();
            app.runner(genie.id()).hostAccess().read();
            app.rejoin(genie.id());
        });
        app.lookForOllama();
        // Lamps someone lit elsewhere, now and then, from the first moment on.
        app.lookingForLamps = Optional.of(Thread.ofVirtual().name("look for lamps now and then").start(() -> {
            try {
                while (true) {
                    SwingUtilities.invokeLater(app::lookForLamps);
                    Thread.sleep(LOOKING_FOR_LAMPS_EVERY);
                }
            } catch (InterruptedException quitting) {
                // Genies quits.
            }
        }));
        return app;
    }

    // ─── actions ───────────────────────────────────────────────────────────────────────────

    @Override public Var<GeniesState> state() { return state; }

    @Override public Path errorLog() { return shelf.errorLog(); }

    /// A new genie wakes at once, as a new chat is ready at once elsewhere.
    @Override public void newGenie() {
        Genie genie = Genie.named(state.get().freshName());
        state.update(From.VIEW, it -> it.add(genie));
        wake(genie.id());
    }

    @Override public void wake(UUID id) {
        GeniesState now = state.get();
        Optional<String> problem = now.settingsProblem();
        Optional<String> key = now.settings().keyFrom(now.environmentKey());
        if (problem.isPresent() || key.isEmpty()) {
            String why = problem.orElse("There is no model key.");
            state.update(From.VIEW, it -> it.update(id, genie -> genie.withTranscript(genie.transcript().problem(why)))
                                             .withPage(GeniesState.Page.SETTINGS));
            return;
        }
        // Ollama may have ended since, as when the computer restarted. It starts in less time
        // than a lamp does.
        if (now.settings().usesOllama()) Thread.ofVirtual().name("start ollama").start(() -> {
            try {
                ollama.start();
            } catch (IOException failed) {
                report(new IOException("could not start Ollama for the genies", failed));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        now.find(id).ifPresent(genie -> runner(id).wake(genie.name(), now.settings(), key.get(),
                conversationShown(genie), leafShown(genie)));
    }

    @Override public void goTo(UUID id, String conversation, String leaf) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id));
        switch (genie.get().phase()) {
            // Conversations are read from the lamp, so a sleeping genie's can be read too, and a
            // working genie's answer is kept aside while the user reads another.
            case READY, WORKING, ASLEEP, BROKEN -> {
                state.update(From.VIEW, it -> it.update(id, g -> g.withConversations(g.conversations().withOverview(false))));
                runner(id).goToConversation(conversation, leaf);
            }
            case WAKING -> { }   // The tree does not move while the genie wakes.
        }
    }

    @Override public void startAfresh(UUID id) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        // From any of the genie's pages, such as its actions, to the new conversation.
        state.update(From.VIEW, it -> it.select(id).withPage(GeniesState.Page.CHAT));
        if (genie.get().phase() != Genie.Phase.WORKING) {
            state.update(From.VIEW, it -> it.update(id, g -> g.withConversations(g.conversations().withOverview(false))));
            runner(id).startAfresh();
        }
    }

    @Override public void forget(UUID id, String conversation) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        // A sleeping genie that was in it wakes in its newest conversation instead; an awake one
        // is moved to a new conversation by its runner first.
        if (!genie.get().phase().isAwake() && genie.get().conversations().here().file().equals(conversation))
            state.update(From.VIEW, it -> it.update(id, sleeping -> sleeping.withConversations(
                    sleeping.conversations().withHere(Conversations.Here.UNKNOWN))));
        runner(id).forgetConversation(conversation);
    }

    @Override public void askInstead(String question, String text) {
        Genie genie = state.get().genie();
        if (text.isBlank() || genie.phase() != Genie.Phase.READY) return;
        state.update(From.VIEW, it -> it.update(genie.id(), g -> g.askInstead(question, text)));
        runner(genie.id()).askInstead(question, text.strip());
    }

    @Override public void sleep(UUID id) {
        Optional.ofNullable(runners.get(id)).ifPresent(GenieRunner::sleep);
    }

    /// Waits one turn of Swing's event thread first: a text field hands its latest text to the
    /// draft on the next turn, so a message sent in the same turn as the last keystroke or a
    /// paste would lose it.
    @Override public void send() {
        SwingUtilities.invokeLater(this::sendDraft);
    }

    private void sendDraft() {
        Genie genie = state.get().genie();
        if (!genie.canSend()) return;
        String text = genie.draft().strip();
        state.update(From.VIEW, it -> it.update(genie.id(), Genie::send));
        runner(genie.id()).sendMessage(text);
    }

    @Override public void stop(UUID id) {
        Optional.ofNullable(runners.get(id)).ifPresent(GenieRunner::stopAnswering);
    }

    /// The genie leaves the window at once; its lamp is put out and removed in the background.
    ///
    /// A lamp from elsewhere is not Genies' to remove: Genies lets go of it, leaving it running
    /// if it runs, and it stays where it is, as it is. Genies then does not take it in again when
    /// it finds it lit, unless the user adds it by hand.
    @Override public void delete(UUID id) {
        Path lamp = lampOf(id);
        boolean fromElsewhere = state.get().find(id).map(Genie::fromElsewhere).orElse(false);
        GenieRunner runner = runners.remove(id);
        state.update(From.VIEW, it -> it.remove(id));
        if (fromElsewhere) {
            removedLamps = removedLamps.remove(lamp).add(lamp);
            keepRemovedLamps();
            if (runner != null) Thread.ofVirtual().name("let go of a lamp").start(() -> {
                try {
                    runner.leaveRunningAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            return;
        }
        Thread.ofVirtual().name("delete genie").start(() -> {
            try {
                if (runner != null) runner.sleepAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
                if (!Files.exists(lamp)) return;
                StringBuilder why = new StringBuilder();
                LampExitStatus removed = Lamp.at(lamp).onEvent(event -> {
                    if (event instanceof LampEvent.Failure failure) why.append(failure.problem().whatHappened());
                }).remove();
                if (removed != LampExitStatus.SUCCESS)
                    report(new IOException("could not remove the lamp at " + lamp + ": " + why));
            } catch (IOException | InterruptedException failed) {
                report(new IOException("could not remove the lamp at " + lamp, failed));
            }
        });
    }

    // ─── lamps from elsewhere ──────────────────────────────────────────────────────────────

    /// Each lamp found lit that Genies does not know yet joins the list, after Genies' own, and
    /// Genies joins its session. Only the lamps the user removed are left out, and Genies' own
    /// lamps on the shelf. A genie asleep in the list whose lamp someone lit since, with
    /// `oillamp at`, is joined. Off Swing's event thread. When it cannot be found out, as without
    /// podman, nothing changes: Genies says so when a genie wakes, and this is asked again.
    @Override public void lookForLamps() {
        Thread.ofVirtual().name("look for lamps").start(() -> {
            try {
                List<Path> lit = lighter.running();
                SwingUtilities.invokeLater(() -> {
                    for (Path lamp : lit) {
                        Path found = lamp.toAbsolutePath().normalize();
                        Optional<Genie> asleep = listedAt(found).filter(it -> it.phase() == Genie.Phase.ASLEEP);
                        if (asleep.isPresent()) rejoin(asleep.get().id());
                        if (known(found) || removedLamps.contains(found)) continue;
                        take(found, false);
                    }
                });
            } catch (IOException unknown) {
                // Asked again next time.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// A lamp the user picked joins the list, and is shown; Genies joins its session if it is lit.
    /// One already in the list is shown. A removed one is no longer.
    @Override public void addLamp(Path directory) throws IOException {
        Path lamp = directory.toAbsolutePath().normalize();
        if (lamp.startsWith(shelf.root().toAbsolutePath().normalize()))
            throw new IOException(lamp + " is a genie's lamp already, kept by Genies itself.");
        if (!Files.isDirectory(lamp.resolve(".oillamp")) && !Files.isRegularFile(lamp.resolve("oillamp.toml")))
            throw new IOException(lamp + " is no lamp: it has no oillamp.toml, nor a .oillamp folder. "
                    + "`oillamp at " + lamp + "` makes one there.");
        removedLamps = removedLamps.remove(lamp);
        keepRemovedLamps();
        Optional<Genie> listed = state.get().genies().stream().filter(it -> lampOf(it.id()).equals(lamp)).findFirst();
        if (listed.isPresent()) state.update(From.VIEW, it -> it.select(listed.get().id()));
        else take(lamp, true);
    }

    /// Whether `lamp` is a genie's lamp already, or one on the shelf, whose genie was deleted
    /// while its lamp went on running.
    private boolean known(Path lamp) {
        if (lamp.startsWith(shelf.root().toAbsolutePath().normalize())) return true;
        return listedAt(lamp).isPresent();
    }

    /// The genie in the list whose lamp is `lamp`, an absolute and normal path.
    private Optional<Genie> listedAt(Path lamp) {
        return state.get().genies().stream().filter(it -> lampOf(it.id()).toAbsolutePath().normalize().equals(lamp)).findFirst();
    }

    /// Takes the lamp in `directory` in as a genie, and reads what a sleeping genie shows; joins
    /// its session if it is lit.
    ///
    /// @param shown whether the window shows it then, as when the user picked it
    private void take(Path directory, boolean shown) {
        Genie genie = Genie.elsewhere(UUID.randomUUID(), directory).withConversations(Conversations.NONE.withFolded(true));
        state.update(From.VIEW, it -> shown ? it.add(genie) : it.found(genie));
        runner(genie.id()).reloadConversations();
        runner(genie.id()).hostAccess().read();
        rejoin(genie.id());
    }

    private void keepRemovedLamps() {
        try {
            shelf.keepRemovedLamps(removedLamps);
        } catch (IOException failed) {
            report(new IOException("could not keep the removed lamps in " + shelf.root(), failed));
        }
    }

    @Override public void giveFile(UUID id, Path file) { runner(id).giveFile(file); }

    @Override public void saveOutboxFile(UUID id, String name, Path target) { runner(id).saveOutboxFile(name, target); }

    // ─── the home ──────────────────────────────────────────────────────────────────────────

    /// A folder deleted since it was shown leaves the tree as it was, until its parent is read again.
    @Override public void readHome(UUID id, Tuple<String> folder) {
        Path lamp = lampOf(id);
        Thread.ofVirtual().name("read home").start(() -> {
            Optional<Path> where = GenieHomeUtil.home(lamp);
            Optional<Home.Folder> read = Optional.empty();
            try {
                if (where.isPresent()) read = Optional.of(GenieHomeUtil.read(where.get(), folder));
            } catch (IOException gone) {
                // Deleted, or made a link, since it was shown.
            }
            Optional<Home.Folder> fresh = read;
            SwingUtilities.invokeLater(() -> state.update(it -> it.update(id, genie -> genie.withHome(
                    fresh.map(found -> genie.home().withRead(where, folder, found))
                         .orElseGet(() -> genie.home().read() && genie.home().where().equals(where) ? genie.home() : Home.at(where))))));
        });
    }

    @Override public Optional<Path> folderOf(UUID id, Tuple<String> folder) {
        return GenieHomeUtil.home(lampOf(id)).flatMap(home -> GenieHomeUtil.folder(home, folder));
    }

    @Override public Home.Link link(UUID id, Tuple<String> folder, Path into, String name) throws IOException {
        String genie = state.get().find(id).map(Genie::name).orElse("The genie");
        Path home = GenieHomeUtil.home(lampOf(id))
                .orElseThrow(() -> new IOException(genie + " has no home yet. It gets one as it first wakes."));
        Path target = GenieHomeUtil.folder(home, folder)
                .orElseThrow(() -> new IOException(genie + "'s home has no folder " + String.join("/", folder) + "."));
        return GenieHomeUtil.link(target, into, name);
    }

    @Override public void showInFileManager(UUID id, Tuple<String> folder) {
        folderOf(id, folder).ifPresent(shown -> Thread.ofVirtual().name("show in file manager").start(() -> {
            try {
                GenieHomeUtil.showInFileManager(shown);
            } catch (IOException failed) {
                report(new IOException("could not open " + shown + " in the file manager", failed));
            }
        }));
    }

    @Override public Path documents() { return documents; }

    // ─── the schedule ──────────────────────────────────────────────────────────────────────

    @Override public void saveJob(UUID id) {
        state.get().find(id).ifPresent(genie -> genie.schedule().draft().ifPresent(draft -> {
            ZoneId zone = genie.schedule().zone();
            runner(id).schedule().save(draft, LocalDateTime.now(zone), zone);
        }));
    }

    @Override public void removeJob(UUID id, String job) { runner(id).schedule().remove(job); }

    @Override public void switchJob(UUID id, String job, boolean on) { runner(id).schedule().switchJob(job, on); }

    @Override public void pauseSchedule(UUID id, boolean paused) { runner(id).schedule().pause(paused); }

    @Override public void stopRun(UUID id, String run) {
        Optional.ofNullable(runners.get(id)).ifPresent(runner -> runner.stopRun(run));
    }

    // ─── the history ───────────────────────────────────────────────────────────────────────

    @Override public void save(UUID id, String message) { runner(id).history().save(message); }

    /// Not while the genie wakes or works, which going back would cut short. Waking it again
    /// afterwards waits until it went back: the runner does one thing after the other.
    @Override public void goBack(UUID id, String moment) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty() || genie.get().phase() == Genie.Phase.WAKING || genie.get().phase() == Genie.Phase.WORKING) return;
        Optional<History.Moment> target = genie.get().history().find(moment);
        if (target.isEmpty()) return;
        runner(id).goBackTo(target.get());
        if (genie.get().phase().isAwake()) wake(id);
    }

    /// Not while the genie is awake or waking: its lamp read the file as it woke.
    @Override public void autosave(UUID id, History.Autosave wanted) {
        if (asleep(id)) runner(id).history().autosave(wanted);
    }

    /// Not while the genie is awake or waking, as [#autosave].
    @Override public void maxGrowth(UUID id, double gb) {
        if (asleep(id)) runner(id).history().maxGrowth(gb);
    }

    /// Not while the genie is awake or waking: oillamp deletes moments only from a lamp that is out.
    @Override public void planPruning(UUID id) {
        if (asleep(id)) runner(id).history().planPruning();
    }

    @Override public void prune(UUID id) {
        if (asleep(id)) runner(id).history().prune();
    }

    @Override public void startBranch(UUID id, String name, String moment) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty() || genie.get().history().branchNameProblem(name, state.get().lang()).isPresent()) return;
        runner(id).history().startBranch(name, moment);
    }

    /// Not while the genie is awake or waking: moving it changes its home, which oillamp refuses
    /// under a running sandbox. Unlike going back, the app does not put the genie to sleep for
    /// it: the user moves a genie that sleeps, and wakes it on its new branch.
    @Override public void switchBranch(UUID id, String branch) {
        if (asleep(id)) runner(id).switchTo(branch);
    }

    @Override public void planDeletingBranch(UUID id, String branch) { runner(id).history().planDeletingBranch(branch); }

    @Override public void deleteBranch(UUID id, String branch) { runner(id).history().deleteBranch(branch); }

    /// Not while the genie is awake or waking, as [#switchBranch].
    @Override public void planReset(UUID id, String moment) {
        if (asleep(id)) runner(id).history().planReset(moment);
    }

    @Override public void reset(UUID id, String moment) {
        if (asleep(id)) runner(id).reset(moment);
    }

    /// Whether the genie sleeps, or broke: its lamp is out, and its file and home can change.
    private boolean asleep(UUID id) {
        Optional<Genie> genie = state.get().find(id);
        return genie.isPresent() && !genie.get().phase().isAwake() && genie.get().phase() != Genie.Phase.WAKING;
    }

    // ─── the host access ───────────────────────────────────────────────────────────────────

    /// Not while the genie is awake or waking: its lamp read the file as it woke.
    @Override public void saveHostAccess(UUID id) {
        if (asleep(id)) runner(id).hostAccess().save(state.get().find(id).orElseThrow().hostAccess().draft());
    }

    @Override public void decidePermission(UUID id, String request, boolean allowed) { runner(id).decidePermission(request, allowed); }

    /// While the genie wakes, the chat stays where it is, as the tree does.
    @Override public void openConversation(UUID id, String conversation) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id).withPage(GeniesState.Page.CHAT));
        if (genie.get().phase() != Genie.Phase.WAKING) runner(id).openConversation(conversation);
    }

    /// Leaving the page keeps the settings.
    @Override public void settingsDone() {
        state.update(From.VIEW, it -> it.withPage(GeniesState.Page.CHAT));
    }

    /// Asks the service the settings name for its models, off Swing's event thread, the way the
    /// genies' lamps will ask it, with the same key.
    @Override public void lookUpModels() {
        Settings settings = state.get().settings();
        Settings.Place place = settings.place();
        String service = settings.service();
        Optional<String> key = settings.listingKeyFrom(state.get().environmentKey());
        state.update(From.VIEW, GeniesState::askingForModels);
        Thread.ofVirtual().name("look up models").start(() -> {
            try {
                List<String> found = key.isPresent() ? Lamp.models(URI.create(service), key.get())
                                                     : Lamp.models(URI.create(service));
                SwingUtilities.invokeLater(() -> state.update(it -> it.modelsFound(place, service, Tuple.of(String.class, found))));
            } catch (IOException | IllegalArgumentException failed) {
                String why = Optional.ofNullable(failed.getMessage()).orElse(failed.toString());
                SwingUtilities.invokeLater(() -> state.update(it -> it.modelsNotFound(place, service, why)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Sets up Ollama with the model wanted, off Swing's event thread: whatever is left of
    /// installing Ollama, starting it, downloading the model, preparing it for genies and trying
    /// it. Then the genies use it, and the settings are kept.
    @Override public void setUpOllama() {
        String model = state.get().ollama().wanted().strip();
        if (model.isEmpty() || state.get().ollama().step().isBusy()) return;
        stopping = false;
        state.update(From.VIEW, it -> it.withOllama(it.ollama().at(OllamaSetup.Step.STARTING, 0, "Looking at what is left to do…")));
        settingUp = Optional.of(Thread.ofVirtual().name("set up ollama").start(() -> {
            try {
                OllamaSetup.Found found = ollama.look();
                if (!found.isInstalled() && !found.isRunning()) {
                    step(OllamaSetup.Step.INSTALLING, 0, "Downloading Ollama…");
                    ollama.install((done, says) -> step(OllamaSetup.Step.INSTALLING, done, says));
                }
                if (!found.isRunning()) {
                    step(OllamaSetup.Step.STARTING, 0, "Starting Ollama…");
                    ollama.start();
                }
                if (!found.has(model)) {
                    step(OllamaSetup.Step.DOWNLOADING, 0, "Downloading " + model + "…");
                    ollama.pull(model, (done, says) -> step(OllamaSetup.Step.DOWNLOADING, done, says));
                }
                step(OllamaSetup.Step.PREPARING, 0, "Preparing " + model + " for genies…");
                ollama.prepare(model);
                step(OllamaSetup.Step.TRYING, 0, "Trying " + model + ". Its first answer loads it, which can take a few minutes…");
                ollama.tryOut(OllamaSetup.PREFIX + model);
                SwingUtilities.invokeLater(() -> {
                    state.update(it -> it.usingOllama(model));
                    keepSettings();
                });
            } catch (IOException failed) {
                if (stopping) step(OllamaSetup.Step.IDLE, 0, STOPPED);
                else step(OllamaSetup.Step.FAILED, 0, Optional.ofNullable(failed.getMessage()).orElse(failed.toString()));
            } catch (InterruptedException stopped) {
                step(OllamaSetup.Step.IDLE, 0, STOPPED);
            }
            SwingUtilities.invokeLater(() -> {
                settingUp = Optional.empty();
                lookForOllama();
            });
        }));
    }

    @Override public void stopSettingUp() {
        stopping = true;
        settingUp.ifPresent(Thread::interrupt);
    }

    @Override public Optional<Desktop> desktopOf(UUID id) {
        return Optional.ofNullable(runners.get(id)).flatMap(GenieRunner::desktop);
    }

    /// A lamp from elsewhere is where it was found; any other is on the shelf.
    @Override public Path lampOf(UUID id) {
        return state.get().find(id).flatMap(Genie::elsewhere).orElseGet(() -> shelf.lampOf(id));
    }

    // ─── the rest ──────────────────────────────────────────────────────────────────────────

    /// Looks, off Swing's event thread, whether Ollama is installed and runs, and what it and the
    /// computer have.
    private void lookForOllama() {
        Thread.ofVirtual().name("look for ollama").start(() -> {
            try {
                OllamaSetup.Found found = ollama.look();
                SwingUtilities.invokeLater(() -> state.update(it -> it.withOllama(it.ollama().found(found, it.settings()))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Shows how far setting up Ollama is.
    private void step(OllamaSetup.Step step, double done, String says) {
        SwingUtilities.invokeLater(() -> state.update(it -> it.withOllama(it.ollama().at(step, done, says))));
    }

    private GenieRunner runner(UUID id) {
        return runners.computeIfAbsent(id, genie -> new GenieRunner(lampOf(genie), state.get().find(genie).map(Genie::fromElsewhere).orElse(false), lighter,
                change -> SwingUtilities.invokeLater(() -> state.update(it -> it.update(genie, change)))));
    }

    /// Hands `failed` to the error log, which is this thread's uncaught exception handler, so the
    /// user sees it, and goes on.
    private static void report(Exception failed) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, failed);
    }

    private void keepSettings() {
        try {
            shelf.keep(state.get().settings());
        } catch (IOException failed) {
            report(new IOException("could not keep the settings in " + shelf.root(), failed));
        }
    }

    private void keepPalette() {
        try {
            shelf.keep(state.get().palette());
        } catch (IOException failed) {
            report(new IOException("could not keep the palette in " + shelf.root(), failed));
        }
    }

    private void keepGenies() {
        try {
            shelf.keep(state.get().genies());
        } catch (IOException failed) {
            report(new IOException("could not keep the genies in " + shelf.root(), failed));
        }
    }

    /// Joins the genie's lamp if it was left running, and shows it where it was. The lamp keeps
    /// the model it was lit with until the genie next wakes: Genies cannot know which service
    /// and key it was lit with, and a model from another place would fail there.
    private void rejoin(UUID id) {
        state.get().find(id).ifPresent(genie -> runner(id).rejoin(conversationShown(genie), leafShown(genie)));
    }

    /// Every awake genie whose lamp reaches the settings' service with their key answers with
    /// the settings' model from its next message on, in whatever conversation, without its lamp
    /// going out. Any other awake genie gets all the new settings when it next wakes; the
    /// settings say so.
    private void useTheSettingsModel() {
        GeniesState now = state.get();
        String key = now.settings().keyFrom(now.environmentKey()).orElse("");
        for (GenieRunner runner : runners.values()) runner.useModel(now.settings(), key);
    }

    /// The file of the conversation the genie was last in, or empty for its most recent one.
    private static String conversationShown(Genie genie) {
        return genie.conversations().current().map(Conversation::file).orElse("");
    }

    /// The entry the genie's chat was last at, or empty for where its conversation stands.
    private static String leafShown(Genie genie) {
        return genie.conversations().current().isPresent() ? genie.conversations().here().leaf() : "";
    }

    /// A lamp from elsewhere is left running whatever the user chose: whoever lit it decides
    /// when it goes out.
    @Override public void quit(boolean leaveRunning, Optional<Thread> first, Runnable whenDone) {
        keepGenies();
        keepSettings();
        lookingForLamps.ifPresent(Thread::interrupt);
        Map<UUID, Boolean> fromElsewhere = new ConcurrentHashMap<>();
        for (Genie genie : state.get().genies()) fromElsewhere.put(genie.id(), genie.fromElsewhere());
        Thread.ofVirtual().name("quit").start(() -> {
            try {
                if (first.isPresent()) first.get().join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            for (Map.Entry<UUID, GenieRunner> each : runners.entrySet()) {
                GenieRunner runner = each.getValue();
                try {
                    if (leaveRunning || fromElsewhere.getOrDefault(each.getKey(), false))
                        runner.leaveRunningAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
                    else runner.sleepAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            SwingUtilities.invokeLater(whenDone);
        });
    }
}
