package dev.promo;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import dev.gui.model.Conversation;
import dev.gui.model.Conversations;
import dev.gui.model.Fold;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Hardware;
import dev.gui.model.History;
import dev.gui.model.HostAccess;
import dev.gui.model.OllamaSetup;
import dev.gui.model.Recurrence;
import dev.gui.model.Schedule;
import dev.gui.model.Settings;
import dev.gui.model.Transcript;
import dev.gui.pi.PiEvent;
import sprouts.Tuple;

/// Who is in the film, and what they have been up to before it begins: the genies, their
/// conversations, their jobs and their histories, as values the window shows. Nothing here is
/// read from a lamp; it is what a lamp would have said.
final class Story {

    private Story() {}

    /// Where the film is set, which decides what its clocks say.
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /// When the film is set: a Thursday morning.
    static final Instant NOW = Instant.parse("2026-11-12T09:40:00Z");

    /// The model the genies answer with, as Genies names one it prepared for them with Ollama.
    static final String MODEL = "genies/qwen3:14b";

    // A genie's colour and what it wears follow from its id, so these are chosen for their looks.
    static final UUID JUNIPER = UUID.fromString("e5597446-27cc-3b9d-8657-9d548b57bc01");
    static final UUID SAMI = UUID.fromString("8c8d357b-5e87-3bba-8d45-197626bd5759");
    static final UUID REX = UUID.fromString("04d8b0d7-2264-3c23-a02f-dafcc5fb364e");
    /// Two more, who are only in the picture of what one might ask for.
    static final UUID FOURTH = UUID.fromString("89e3eb66-497b-398d-bd22-50f0093903c6"), FIFTH = UUID.fromString("62ad1c2a-46c5-398f-be2c-95d3babf8d0c");

    /// Juniper, who builds things: asleep, with the conversations she had.
    static Genie juniper() {
        return Genie.asleep(JUNIPER, "Juniper").withConversations(conversations(
            talk("recipes", "Turn my recipe notes into a little cookbook site", "2026-11-10T18:02:00Z"),
            talk("backup", "Why does my backup script fail on Sundays?", "2026-11-08T10:40:00Z"),
            talk("budget", "A budget sheet that reads my bank's CSV export", "2026-11-03T20:15:00Z")
        ).withChatsFold(Fold.CLOSED.toggled())).withSchedule(Schedule.unread(ZONE));
    }

    /// Sami, who does jobs on a schedule.
    static Genie sami() {
        return Genie.asleep(SAMI, "Sami").withConversations(conversations(
            talk("feeds", "Read my feeds each morning and brief me", "2026-11-09T07:30:00Z")
        )).withSchedule(schedule());
    }

    /// Rex, who may reach a few folders of this computer.
    static Genie rex() {
        return Genie.asleep(REX, "Rex").withConversations(conversations(
            talk("invoices", "Sort this year's invoices by month", "2026-11-05T16:20:00Z"),
            talk("photos", "Rename my holiday photos by date and place", "2026-10-28T21:05:00Z")
        )).withHostAccess(access());
    }

    /// Juniper once she has built the plant app: awake, in that conversation.
    static Genie juniperAfterTheApp() {
        Genie juniper = juniper();
        Conversation plants = talk("plants", "Build me a little app that reminds me when to water my plants.", "2026-11-12T09:31:00Z");
        return juniper.waking().awake()
            .withConversations(juniper.conversations()
                .withAll(juniper.conversations().all().addAt(0, plants))
                .withHere(new Conversations.Here(plants.file(), "")))
            .withTranscript(Transcript.empty()
                .hear(new PiEvent.History(Tuple.of(PiEvent.History.Line.class,
                    new PiEvent.History.Line(true, "Build me a little app that reminds me when to water my plants.", "q1", false, false, ""),
                    new PiEvent.History.Line(false, "On it. I'll build it right here in my lamp: a small web app that knows your plants and when each one is thirsty.", "a1", true, false, MODEL),
                    new PiEvent.History.Line(false, "Here it is. **Six plants** to start with, and the fern is due today. Tell me what to change.", "a2", false, false, MODEL)
                ), "a2")))
            .withHistory(history())
            .withSchedule(Schedule.unread(ZONE).readAs(false, ZONE, Tuple.of(Schedule.Job.class), Tuple.of(Schedule.Run.class)));
    }

    /// Juniper's history: what she did this week, and a branch on which she tried something else.
    static History history() {
        Tuple<String> both = Tuple.of(String.class, "main", "try-svelte"), main = Tuple.of(String.class, "main"), other = Tuple.of(String.class, "try-svelte");
        Tuple<History.Moment> moments = Tuple.of(History.Moment.class,
            moment("a1", "2026-11-12T09:31:00Z", History.Kind.CHATTED, "", "plants", main),
            moment("a0", "2026-11-12T09:22:00Z", History.Kind.WOKE, "", "", main),
            moment("b2", "2026-11-11T20:44:00Z", History.Kind.CHATTED, "", "recipes", other),
            moment("b1", "2026-11-11T19:58:00Z", History.Kind.SAVED, "the cookbook, rewritten in Svelte", "", other),
            moment("9f", "2026-11-10T18:40:00Z", History.Kind.SAVED, "before the big redesign", "", both),
            moment("8e", "2026-11-10T18:02:00Z", History.Kind.CHATTED, "", "recipes", both),
            moment("7d", "2026-11-08T10:40:00Z", History.Kind.CHATTED, "", "backup", both),
            moment("6c", "2026-11-03T20:15:00Z", History.Kind.CHATTED, "", "budget", both)
        );
        return History.UNREAD
            .readAs(1, Tuple.of(History.Branch.class, new History.Branch("main", true, id("a1")), new History.Branch("try-svelte", false, id("b2"))), moments)
            .withAutosave(Optional.of(new History.Autosave(true, true, true, true, 10)))
            .withMaxGrowthGb(Optional.of(2.0));
    }

    private static History.Moment moment(String id, String at, History.Kind kind, String message, String conversation, Tuple<String> branches) {
        return new History.Moment(id(id), Instant.parse(at), kind, message, "", "", Optional.empty(), conversation, branches);
    }

    /// oillamp's id for a moment, forty characters, beginning with `start`.
    static String id(String start) { return (start + "0".repeat(40)).substring(0, 40); }

    /// Sami's week: a briefing each weekday morning, a backup on Sundays, and a reminder she set herself.
    static Schedule schedule() {
        String briefing = "Read my feeds and write me a morning briefing: three things worth my time, and why.";
        Tuple<Schedule.Job> jobs = Tuple.of(Schedule.Job.class,
            new Schedule.Job("job-1", Optional.of(new Recurrence.Weekdays(LocalTime.of(8, 0))), Optional.empty(), Optional.of(local("2026-11-13T08:00")),
                Tuple.of(Instant.class, local("2026-11-13T08:00"), local("2026-11-16T08:00"), local("2026-11-17T08:00"), local("2026-11-18T08:00"), local("2026-11-19T08:00")),
                briefing, false, Optional.empty(), true),
            new Schedule.Job("job-2", Optional.of(new Recurrence.Weekly(Tuple.of(DayOfWeek.class, DayOfWeek.SUNDAY), LocalTime.of(20, 0))), Optional.empty(), Optional.of(local("2026-11-15T20:00")),
                Tuple.of(Instant.class, local("2026-11-15T20:00")),
                "Back up my notes to the outbox, and tell me what changed since last week.", false, Optional.empty(), true),
            new Schedule.Job("job-3", Optional.empty(), Optional.of(local("2026-11-14T10:00")), Optional.of(local("2026-11-14T10:00")),
                Tuple.of(Instant.class, local("2026-11-14T10:00")),
                "Check whether the conference tickets went on sale, and tell me the price.", true, Optional.of(local("2026-11-26T10:00")), true)
        );
        Tuple<Schedule.Run> runs = Tuple.of(Schedule.Run.class,
            new Schedule.Run("run-41", "job-1", local("2026-11-12T08:03"), Schedule.Outcome.FINISHED, "Your briefing is in the outbox. The Fediverse piece is the one to read first.", Optional.empty()),
            new Schedule.Run("run-40", "job-1", local("2026-11-11T08:02"), Schedule.Outcome.FINISHED, "Three picks today, and a note on the kernel release.", Optional.empty()),
            new Schedule.Run("run-39", "job-1", local("2026-11-10T08:03"), Schedule.Outcome.FINISHED, "A quiet morning: two picks, both short.", Optional.empty()),
            new Schedule.Run("run-38", "job-1", local("2026-11-09T08:02"), Schedule.Outcome.FINISHED, "Your briefing is in the outbox.", Optional.empty()),
            new Schedule.Run("run-37", "job-2", local("2026-11-08T20:04"), Schedule.Outcome.FINISHED, "Backed up 212 notes. Nine changed, two are new.", Optional.empty())
        );
        return Schedule.unread(ZONE).readAs(false, ZONE, jobs, runs);
    }

    private static Instant local(String time) { return LocalDateTime.parse(time).atZone(ZONE).toInstant(); }

    /// What Rex may reach of this computer.
    static HostAccess access() {
        return HostAccess.UNREAD.readAs(true, new HostAccess.PermissionRules(
            true,
            "~/Documents/Invoices\n~/Pictures/Holidays",
            "~/Documents/Invoices/sorted",
            "Never open my tax returns. Ask me before you delete anything.",
            HostAccess.Commands.NEVER,
            false
        ));
    }

    /// Ollama on this computer, with the model the genies use: found, running, nothing left to do.
    static OllamaSetup ollama() {
        long gb = 1L << 30;
        Hardware hardware = new Hardware(32 * gb, Optional.of(new Hardware.Graphics("AMD Radeon RX 7800 XT", 16 * gb)));
        return OllamaSetup.NOT_YET.found(
            new OllamaSetup.Found("/home/you/.local/share/genies/ollama/bin/ollama", "0.12.6", Tuple.of(String.class, "qwen3:14b", MODEL), hardware),
            settings()
        );
    }

    /// The window with the three of them, Juniper's chat shown, all asleep.
    static GeniesState genies() {
        return GeniesState.of(Tuple.of(Genie.class, juniper(), sami(), rex()), settings(), Optional.empty())
            .withNow(NOW)
            .withOllama(ollama())
            .withArea(900, 600);
    }

    /// The window once Juniper has built the plant app and shows it on her desktop: where every
    /// scene after that one begins, on whichever genie and page it then goes to. Sami and Rex
    /// sleep, and what their pages show has been read from their lamps.
    static GeniesState afterTheApp() {
        History quiet = History.UNREAD.readAs(1, Tuple.of(History.Branch.class, new History.Branch("main", true, id("c1"))), Tuple.of(History.Moment.class,
            moment("c1", "2026-11-12T07:03:00Z", History.Kind.SLEPT, "", "", Tuple.of(String.class, "main")),
            moment("c0", "2026-11-12T06:58:00Z", History.Kind.WOKE, "", "", Tuple.of(String.class, "main"))
        )).withAutosave(Optional.of(new History.Autosave(true, true, true, true, 10))).withMaxGrowthGb(Optional.of(2.0));
        Schedule none = Schedule.unread(ZONE).readAs(false, ZONE, Tuple.of(Schedule.Job.class), Tuple.of(Schedule.Run.class));
        return genies()
            .update(JUNIPER, _ -> juniperAfterTheApp().shows("your plant app"))
            .update(SAMI, it -> it.withHistory(quiet))
            .update(REX, it -> it.withHistory(quiet).withSchedule(none));
    }

    /// The genies use Ollama on this computer, as Genies sets it up.
    static Settings settings() {
        return Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
            .withLocal(new Settings.OnThisMachine(Settings.OLLAMA, MODEL));
    }

    private static Conversations conversations(Conversation... talks) {
        return new Conversations(Tuple.of(Conversation.class, talks), Conversations.Here.UNKNOWN);
    }

    /// A conversation the user had, titled by its first question.
    private static Conversation talk(String id, String asked, String modified) {
        return new Conversation(id, ".pi/agent/sessions/--home-agent--/" + id + ".jsonl", "", modified,
            Tuple.of(Conversation.Step.class, new Conversation.Step(id + "-1", "", true, asked)), "");
    }
}
