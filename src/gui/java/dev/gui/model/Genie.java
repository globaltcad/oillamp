package dev.gui.model;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import dev.gui.pi.PiEvent;

import sprouts.HasId;
import sprouts.Tuple;

/// One genie: an agent that lives in a lamp of its own, and the conversation with it.
///
/// @param id        stable for the genie's life; its lamp directory is named after it
/// @param name      what the user calls it
/// @param phase     whether it is asleep, waking, ready, working or broken
/// @param activity  what is happening right now, such as the lamp's last report
/// @param transcript the conversation
/// @param draft     what the user is typing to it
/// @param tokens    how many tokens its model calls have counted since the app started
/// @param handouts  the files in its outbox, which the user can save
/// @param desktopShown whether its desktop is shown next to the chat
/// @param showing   what the genie said it shows on its desktop, such as "the chart you asked
///                  for", until the user closes the desktop; empty when it said nothing
/// @param conversations every conversation with it, and which one the chat shows
/// @param schedule  the jobs that wake it, as its schedule page shows them
/// @param history   the moments its home was saved at, as its history page shows them
/// @param hostAccess whether it may reach this computer, and its rules, as its host access page shows them
/// @param notifications what its lamp asked the user, or told them, and what became of it
/// @param home      where its home is on this computer, and its folders, as its Actions page shows them
/// @param elsewhere its lamp's directory, when Genies did not make it: a lamp someone lit with
///                  `oillamp at`, which Genies found running or was given. Nothing for a lamp on
///                  Genies' own shelf
public record Genie(
    UUID id,
    String name,
    Phase phase,
    Activity activity,
    Transcript transcript,
    String draft,
    int tokens,
    Tuple<Handout> handouts,
    boolean desktopShown,
    String showing,
    Conversations conversations,
    Schedule schedule,
    History history,
    HostAccess hostAccess,
    Notifications notifications,
    Home home,
    Optional<Path> elsewhere
) implements HasId<UUID> {

    /// What a genie is doing right now, as its card says under its name, and the window while it
    /// wakes. Said in the language Genies speaks with [#words(Lang)]; what its lamp reports, and
    /// why it broke, stay as they were put.
    public sealed interface Activity {
        /// Its lamp is being lit.
        record LightingTheLamp() implements Activity {}
        /// Genies joins its lamp, which someone lit with `oillamp at`.
        record JoiningLitElsewhere() implements Activity {}
        /// Genies joins its lamp, which was left running when Genies closed.
        record JoiningLeftRunning() implements Activity {}
        /// Its lamp checks this computer.
        record CheckingThisComputer() implements Activity {}
        /// Its lamp prepares itself.
        record PreparingTheLamp() implements Activity {}
        /// Its lamp makes the sandbox image, which takes minutes the first time.
        record MakingTheSandbox() implements Activity {}
        /// Its lamp starts the sandbox.
        record StartingTheSandbox() implements Activity {}
        /// Its lamp reported a step, or a warning, in oillamp's words, `said`.
        record LampSays(String said) implements Activity {}
        /// Its sandbox runs, and the genie in it is being woken.
        record WakingTheGenie() implements Activity {}
        /// It waits for the user.
        record Ready() implements Activity {}
        /// Its lamp is being put out: its sandbox stops, and its home may be saved.
        record PuttingOutTheLamp() implements Activity {}
        /// Its lamp is not running.
        record Asleep() implements Activity {}
        /// Its lamp, which Genies did not make, is not where it was: its directory is gone, so
        /// there is nothing to wake until it is back.
        record LampGone() implements Activity {}
        /// It thinks.
        record Thinking() implements Activity {}
        /// It uses the tool `tool`.
        record Using(String tool) implements Activity {}
        /// It writes its answer.
        record Writing() implements Activity {}
        /// It broke, for `why`.
        record Broken(Broke why) implements Activity {}

        /// What it is doing, in a few words, in `lang`.
        default String words(Lang lang) {
            Lang.Window words = lang.WINDOW();
            return switch (this) {
                case LightingTheLamp _ -> words.LIGHTING_THE_LAMP();
                case JoiningLitElsewhere _ -> words.JOINING_ITS_LAMP_LIT_ELSEWHERE();
                case JoiningLeftRunning _ -> words.JOINING_ITS_LAMP_WHICH_WAS_LEFT_RUNNING();
                case CheckingThisComputer _ -> words.CHECKING_THIS_COMPUTER();
                case PreparingTheLamp _ -> words.PREPARING_THE_LAMP();
                case MakingTheSandbox _ -> words.MAKING_THE_SANDBOX_THE_FIRST_TIME_TAKES_SEVERAL_MINUTES();
                case StartingTheSandbox _ -> words.STARTING_THE_SANDBOX();
                case LampSays it -> it.said();
                case WakingTheGenie _ -> words.WAKING_THE_GENIE();
                case Ready _ -> words.READY();
                case PuttingOutTheLamp _ -> words.PUTTING_OUT_THE_LAMP();
                case Asleep _ -> words.ASLEEP();
                case LampGone _ -> words.ITS_FOLDER_IS_GONE();
                case Thinking _ -> words.THINKING();
                case Using it -> words.USING(it.tool());
                case Writing _ -> words.WRITING();
                case Broken it -> it.why().words(lang);
            };
        }
    }

    /// Why a genie broke: its lamp did not start, could not be joined, or its sandbox stopped.
    /// Said in the language Genies speaks with [#words(Lang)]; what oillamp or the computer said
    /// stays as they put it.
    public sealed interface Broke {
        /// `model` cannot be a model's name, as it has spaces.
        record NotAModelsName(String model) implements Broke {}
        /// Its lamp did not start, and ended with `status`; empty when it did not end in time.
        record LampDidNotStart(Optional<String> status) implements Broke {}
        /// Its lamp is lit, but Genies could not join it.
        record CouldNotJoin() implements Broke {}
        /// Joining its lamp failed, for `why`; the lamp goes on as it is.
        record JoinFailed(Broke why) implements Broke {}
        /// Its sandbox stopped by itself, and oillamp said `detail`.
        record SandboxStopped(String detail) implements Broke {}
        /// Anything else, as oillamp or the computer `said` it.
        record Failed(String said) implements Broke {}

        /// Why it broke, in `lang`.
        default String words(Lang lang) {
            Lang.Window words = lang.WINDOW();
            return switch (this) {
                case NotAModelsName it -> words.CANNOT_BE_A_MODELS_NAME(it.model());
                case LampDidNotStart it -> it.status().map(words::THE_GENIES_LAMP_DID_NOT_START)
                                                      .orElse(words.THE_GENIES_LAMP_DID_NOT_START_IN_TIME());
                case CouldNotJoin _ -> words.ITS_LAMP_IS_LIT_BUT_GENIES_COULD_NOT_JOIN_IT();
                case JoinFailed it -> words.ITS_LAMP_GOES_ON_AS_IT_IS(it.why().words(lang).replaceAll("[.。\\s]+$", ""));
                case SandboxStopped it -> words.THE_GENIES_SANDBOX_STOPPED(it.detail());
                case Failed it -> it.said();
            };
        }
    }

    public enum Phase {
        /// Its lamp is not running. Its home and its conversation are kept on disk.
        ASLEEP,
        /// Its lamp is starting; the first time, the sandbox image is built, which takes minutes.
        WAKING,
        /// It waits for the user.
        READY,
        /// It is answering.
        WORKING,
        /// Its lamp or its harness failed; [Genie#activity()] says why.
        BROKEN,
        /// Its lamp is being put out: the sandbox stops, and its home may be saved, which can take
        /// a while. It ends asleep.
        FALLING_ASLEEP;

        public boolean isAwake() { return this == READY || this == WORKING; }

        /// Whether its lamp is being lit or put out: it is neither awake nor asleep, and what
        /// needs either waits until it is one or the other.
        public boolean isBetween() { return this == WAKING || this == FALLING_ASLEEP; }
    }

    public static Genie named(String name) {
        return asleep(UUID.randomUUID(), name);
    }

    public static Genie asleep(UUID id, String name) {
        return new Genie(
            id,
            name,
            Phase.ASLEEP,
            new Activity.Asleep(),
            Transcript.empty(),
            "",
            0,
            Tuple.of(Handout.class),
            false,
            "",
            Conversations.NONE,
            Schedule.unread(ZoneId.systemDefault()),
            History.UNREAD,
            HostAccess.UNREAD,
            Notifications.NONE,
            Home.UNREAD,
            Optional.empty()
        );
    }

    public Genie withName(String name)             { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withPhase(Phase phase)            { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withActivity(Activity activity)   { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withTranscript(Transcript transcript) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withDraft(String draft)           { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withTokens(int tokens)            { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withHandouts(Tuple<Handout> handouts) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    /// Closing the desktop also forgets what the genie said it shows there.
    public Genie withDesktopShown(boolean shown)   { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, shown, shown ? showing : "", conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withShowing(String showing)       { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withConversations(Conversations conversations) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withSchedule(Schedule schedule)   { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withHistory(History history)      { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withHostAccess(HostAccess hostAccess) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withNotifications(Notifications notifications) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withHome(Home home)               { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }
    public Genie withElsewhere(Optional<Path> elsewhere) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, showing, conversations, schedule, history, hostAccess, notifications, home, elsewhere); }

    /// A genie whose lamp someone lit with `oillamp at`, in `directory`, not Genies: named as its
    /// directory is, asleep until Genies finds its lamp lit and joins it.
    public static Genie elsewhere(UUID id, Path directory) {
        Path name = directory.getFileName();
        return asleep(id, name == null ? directory.toString() : name.toString()).withElsewhere(Optional.of(directory));
    }

    /// Whether Genies did not make its lamp, but found it, or was given it.
    public boolean fromElsewhere() { return elsewhere.isPresent(); }

    /// Whether its lamp's directory was found gone, so that it cannot be woken.
    public boolean lampGone() { return activity instanceof Activity.LampGone; }

    /// The genie once Genies has looked for its lamp's directory, and found it `gone` or there.
    /// Only a sleeping genie whose lamp is from elsewhere says that it is gone, and is asleep as
    /// before once it is back; any other is as it was.
    public Genie withLampGone(boolean gone) {
        if (phase != Phase.ASLEEP || !fromElsewhere() || gone == lampGone()) return this;
        return withActivity(gone ? new Activity.LampGone() : new Activity.Asleep());
    }

    // ─── its lamp ──────────────────────────────────────────────────────────────────────────

    public Genie waking() {
        return withPhase(Phase.WAKING).withActivity(new Activity.LightingTheLamp());
    }

    /// Its lamp reported progress while waking, such as building the image, or while it is put
    /// out, such as stopping the sandbox.
    public Genie lampSays(Activity what) {
        return phase.isBetween() ? withActivity(what) : this;
    }

    /// Its lamp is being put out. The desktop goes with it at once: it is no longer there to watch.
    public Genie fallingAsleep() {
        return
            withPhase(Phase.FALLING_ASLEEP)
            .withActivity(new Activity.PuttingOutTheLamp())
            .withDesktopShown(false);
    }

    /// Waking is done. A genie that broke while waking, because its harness died say, stays
    /// broken: the news of that may arrive before the news that waking finished.
    public Genie awake() {
        return phase == Phase.WAKING ? withPhase(Phase.READY).withActivity(new Activity.Ready()) : this;
    }

    /// What was put aside is on disk by now, and is read from there when the chat goes back.
    public Genie asleep() {
        return
            withPhase(Phase.ASLEEP)
            .withActivity(new Activity.Asleep())
            .withConversations(conversations.withAside(Optional.empty()))
            .withTranscript(transcript.settled())
            .withDesktopShown(false);
    }

    /// The genie asks the user to look at its desktop, where it shows `what`: the desktop opens
    /// next to the chat, and says what it shows.
    public Genie shows(String what) {
        return withDesktopShown(true).withShowing(what);
    }

    public Genie broken(Broke why) {
        return
            withPhase(Phase.BROKEN)
            .withActivity(new Activity.Broken(why))
            .withDesktopShown(false)
            .withConversations(conversations.withAside(Optional.empty()))
            .withTranscript(transcript.settled().problem(new Notice.Broke(why)));
    }

    // ─── what it is busy with ──────────────────────────────────────────────────────────────

    /// What the genie does, in a few words, as shown under its name: a job of its schedule while
    /// it does one, or else [#activity].
    /// Whether its card shows its conversations: always while it is awake, and otherwise unless
    /// the user folded them away.
    public boolean showsConversations() {
        return phase.isAwake() || !conversations.folded();
    }

    /// What it does, in a few words, such as "thinking".
    public String status(Lang lang) {
        return phase.isAwake() && schedule.running().isPresent() ? lang.WINDOW().DOING_A_SCHEDULED_JOB() : activity.words(lang);
    }

    /// What the user waits for, as the chat's bar says it.
    ///
    /// @param what what the genie does, such as "Sami is doing a scheduled job first: Paint the sunset"
    /// @param time how long the job has run, such as `1:12`; empty while the genie thinks
    public record Waiting(String what, String time) {}

    /// What the user waits for while the genie works on their message and nothing of the answer
    /// has come yet. The genie does one thing at a time: while it does a job of its schedule, the
    /// message waits until the job is done, and this says which job, and how long it has run by
    /// `now`. Otherwise the genie works on the message itself, and thinks.
    public Waiting waitingOn(Instant now, Lang lang) {
        return schedule.running().map(job -> {
            long seconds = Math.max(0, Duration.between(job.since(), now).toSeconds());
            return new Waiting(
                lang.CHAT().IS_DOING_A_SCHEDULED_JOB_FIRST(name, job.title()),
                String.format("%d:%02d", seconds / 60, seconds % 60)
            );
        }).orElse(new Waiting(lang.CHAT().IS_THINKING(name), ""));
    }

    // ─── the conversation ──────────────────────────────────────────────────────────────────

    /// While the genie answers in another conversation than the chat shows, nothing is sent:
    /// the question would wait for that answer, and then go somewhere the user no longer looks.
    public boolean canSend() {
        return
            phase.isAwake() &&
            !draft.isBlank() &&
            conversations.aside().isEmpty() &&
            !conversations.overview();
    }

    /// The draft becomes the user's next message, and the genie is working on it. Whoever calls
    /// this sends the draft, as it was before, to the genie's harness.
    public Genie send() {
        if (!canSend()) return this;
        return
            withTranscript(transcript.you(draft.strip()))
            .withDraft("")
            .withPhase(Phase.WORKING)
            .withActivity(new Activity.Thinking());
    }

    /// Whether the user may ask one of their questions differently now: while the genie waits,
    /// and only a question pi has given an id.
    public boolean canAskInstead(Entry question) {
        return phase == Phase.READY && question.kind() == Entry.Kind.YOU && !question.ref().isEmpty();
    }

    /// The user asks `text` instead of their question `id`. The chat forgets what followed it,
    /// which pi keeps as a branch of its own, and the genie works on the new question. Whoever
    /// calls this tells the genie's harness.
    public Genie askInstead(String id, String text) {
        if (phase != Phase.READY || text.isBlank()) return this;
        return
            withTranscript(transcript.askedInstead(id, text.strip()))
            .withPhase(Phase.WORKING)
            .withActivity(new Activity.Thinking());
    }

    /// What the genie's run for the chat did. While the chat shows another conversation, it goes
    /// to the one put aside, and the chat stays as it is.
    public Genie hear(PiEvent event) {
        Optional<Conversations.Aside> aside = conversations.aside();
        if (aside.isEmpty()) return heard(event);
        Genie there =
            withTranscript(aside.get().transcript())
            .withConversations(conversations.withHere(aside.get().here()))
            .heard(event);
        return
            there.withTranscript(transcript)
            .withConversations(
                there.conversations()
                .withHere(conversations.here())
                .withAside(Optional.of(new Conversations.Aside(there.conversations().here(), there.transcript())))
            );
    }

    /// The chat shows the conversation in `file`, from its start to where `history` ends.
    ///
    /// While the genie answers, the user may look elsewhere: the conversation it answers in is
    /// put aside, and taken back, answer so far and all, when the chat goes there again.
    ///
    /// @param file    relative to the genie's home, or empty for a new conversation; while one is
    ///                put aside, empty is the new one the genie answers in, as pi writes a new
    ///                conversation to disk only once there is an answer in it
    /// @param history what is on disk of it
    public Genie shows(String file, PiEvent.History history) {
        Optional<Conversations.Aside> aside = conversations.aside();
        if (aside.isPresent() && aside.get().here().file().equals(file))
            return
                withTranscript(aside.get().transcript())
                .withConversations(conversations.withHere(aside.get().here()).withAside(Optional.empty()));
        // Another branch of the conversation it answers in: the chat stays on the answer.
        if (phase == Phase.WORKING && aside.isEmpty() && file.equals(conversations.here().file())) return this;
        Genie leaving = phase == Phase.WORKING && aside.isEmpty()
            ? withConversations(conversations.withAside(Optional.of(new Conversations.Aside(conversations.here(), transcript))))
            : this;
        return
            leaving.heard(new PiEvent.Opened(Conversations.HOME + file))
            .heard(history)
            .withConversations(leaving.conversations().withHere(new Conversations.Here(file, history.leaf())));
    }

    /// The chat shows the conversation in `file` as it stood when the genie began to answer
    /// `question`, which it is still answering: asked before the app last closed, and left running.
    /// What the genie has done since comes as its run is heard.
    ///
    /// @param file    relative to the genie's home, or empty for a new conversation
    /// @param history what is on disk of it, up to the question
    public Genie answering(String file, PiEvent.History history, String question) {
        Genie shown = shows(file, history);
        return
            shown.withTranscript(shown.transcript().you(question.strip()))
            .withPhase(Phase.WORKING)
            .withActivity(new Activity.Thinking());
    }

    /// The run for the chat ended in the conversation in `file`, standing at `history`'s end.
    /// The chat learns pi's ids for what was said there, unless it shows another conversation;
    /// then the one put aside is let go, since it is on disk now.
    public Genie finishedIn(String file, PiEvent.History history) {
        if (conversations.aside().isPresent()) return withConversations(conversations.withAside(Optional.empty()));
        return heard(new PiEvent.Opened(Conversations.HOME + file)).learn(history);
    }

    private Genie heard(PiEvent event) {
        Genie heard = withTranscript(transcript.hear(event));
        Genie after = switch (event) {
            case PiEvent.Answered answered -> heard.withTokens(tokens + answered.tokens());
            case PiEvent.ToolStarted tool  -> heard.withActivity(new Activity.Using(tool.tool()));
            case PiEvent.Said _            -> heard.withActivity(new Activity.Writing());
            case PiEvent.Thinking _        -> heard.withActivity(new Activity.Thinking());
            case PiEvent.Settled _         -> phase.isAwake() ? heard.withPhase(Phase.READY).withActivity(new Activity.Ready()) : heard;
            case PiEvent.History history   -> heard.withConversations(
                conversations.withHere(new Conversations.Here(conversations.here().file(), history.leaf()))
            );
            case PiEvent.Opened opened     -> heard.withConversations(
                conversations.withHere(new Conversations.Here(Conversations.inHome(opened.file()), conversations.here().leaf()))
            );
            // A question pi could not take is not being worked on, and nothing else will say so.
            case PiEvent.Refused refused when
                phase == Phase.WORKING &&
                (refused.command().equals("prompt") || refused.command().equals("send_user_message"))
                -> heard.withPhase(Phase.READY).withActivity(new Activity.Ready());
            default                        -> heard;
        };
        // While its lamp is put out, what the run did last is still heard, but the genie stays
        // falling asleep, and its status keeps saying how the lamp goes out.
        return phase == Phase.FALLING_ASLEEP ? after.withPhase(phase).withActivity(activity) : after;
    }

    /// What pi sent after an answer: the chat learns pi's ids for the questions in it, and the
    /// genie where it now is. Unlike [#hear], the chat is not replaced.
    public Genie learn(PiEvent.History history) {
        return
            withTranscript(transcript.learn(history))
            .withConversations(conversations.withHere(new Conversations.Here(conversations.here().file(), history.leaf())));
    }

    /// What is in the outbox now. A file that was not there before is announced in the chat;
    /// [#withHandouts] takes a listing without announcing anything, as when the genie wakes.
    public Genie outbox(Tuple<Handout> now) {
        Transcript told = transcript;
        for (Handout file : now)
            if (!handouts.contains(file)) told = told.handedOver(file);
        return withHandouts(now).withTranscript(told);
    }

    public Genie gave(String fileName) {
        return withTranscript(transcript.gave(fileName));
    }
}
