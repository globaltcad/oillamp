package dev.gui.model;

/// The words of Genies: every text the window shows, in one language. A value, as [Palette] is,
/// so that Genies can be shown in another language as it can in other colours.
///
/// A text is a component named after what it says in English, such as `YOUR_GENIES`. A text that
/// is assembled from something else, such as a count, is a function from that to the words: an
/// interface declared here, as languages differ in how they assemble one. Each language is a
/// single constant, such as [#ENGLISH], so that its functions are the same objects whenever the
/// window compares two of them, and a view follows the words only when the language changes.
///
/// The texts are in a record for each part of the window, as a record has room for no more than
/// 255 components, and as whoever words a part finds its texts together.
///
/// @param WINDOW the window around the genies: the list of them, and what went wrong
public record Lang(
    Window WINDOW
) {

    public static final Lang ENGLISH = new Lang(
        Window.ENGLISH
    );

    /// A text that says how many there are of something.
    public interface Counted { String of(int count); }

    /// A text about something named, such as a genie or a window it shows.
    public interface About { String of(String what); }

    /// What a genie's card says of it when the pointer rests on it: its name, its status, and the
    /// window it shows, which is empty while it shows none.
    public interface GenieTip { String of(String name, String status, String showing); }

    /// How long a conversation, or a branch of one, is: how many `questions` were asked in it,
    /// and how many times one of them was then asked `differently`.
    public interface Questions { String of(int questions, int differently); }

    /// The words of the window around the genies: the list of them, and what went wrong.
    public record Window(
        String NEW_GENIE,
        String A_NEW_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN,
        String SETTINGS,
        String SETTINGS_THE_MODEL_EVERY_GENIE_USES,
        String YOUR_GENIES,
        String DRAG_TO_SEE_MORE_OF_YOUR_GENIES,
        String ADD_A_LAMP_FROM_ELSEWHERE,
        String SHOW_A_LAMP_YOU_MADE_WITH_OILLAMP_AT_HERE_TOO,
        Counted THINGS_WENT_WRONG,
        String GENIES_CARRIED_ON_CLICK_TO_SEE_WHAT_HAPPENED,
        String LAMPS_FROM_ELSEWHERE,
        String LAMPS_SOMEONE_LIT_WITH_OILLAMP_AT_OUTSIDE_GENIES,
        GenieTip GENIE_STATUS_AND_WHAT_IT_SHOWS_RIGHT_CLICK_FOR_MORE,
        String ASKS_YOU_TO_ALLOW_SOMETHING,
        String MAY_REACH_THIS_COMPUTER,
        About SHOWS_YOU,
        String SHOW_THIS_GENIES_CONVERSATIONS,
        String FOLD_THIS_GENIES_CONVERSATIONS_AWAY,
        Counted SCHEDULED_RUNS,
        String SHOW_OR_HIDE_THE_CONVERSATIONS_OF_SCHEDULED_JOBS,
        Counted CONVERSATIONS,
        String SHOW_OR_HIDE_YOUR_CONVERSATIONS_WITH_THIS_GENIE,
        String START_A_NEW_CONVERSATION_WITH_THIS_GENIE,
        String DELETE_ELLIPSIS,
        String DELETE_THE_CONVERSATION_THIS_GENIE_IS_IN,
        String ITS_FIRST_QUESTION_WAS_ASKED_DIFFERENTLY,
        Questions QUESTIONS_THEN_ASKED_DIFFERENTLY,
        String DRAG_TO_MAKE_THIS_LIST_TALLER_OR_SHORTER
    ) {
        public static final Window ENGLISH = new Window(
            "New genie",
            "A new genie, with a sandboxed desktop of its own",
            "Settings",
            "Settings: the model every genie uses. Pressed again, back to the genie",
            "YOUR GENIES",
            "Drag to see more of your genies, or up to fold them away",
            "Add a lamp from elsewhere…",
            "Show a lamp you made with `oillamp at` here too. Genies finds those that are lit by itself",
            count -> count == 1 ? "Something went wrong" : count + " things went wrong",
            "Genies carried on. Click to see what happened",
            "LAMPS FROM ELSEWHERE",
            "Lamps someone lit with `oillamp at`, outside Genies. Genies leaves them as they are",
            (name, status, showing) -> name + " — " + status + (showing.isEmpty() ? "" : ". Shows you: " + showing) + ". Right-click for more.",
            "asks you to allow something",
            "may reach this computer",
            showing -> "shows you: " + showing,
            "Show this genie's conversations",
            "Fold this genie's conversations away",
            count -> count == 0 ? "" : count == 1 ? "1 scheduled run" : count + " scheduled runs",
            "Show or hide the conversations the runs of this genie's scheduled jobs had",
            count -> count == 0 ? "no conversations yet" : count == 1 ? "1 conversation" : count + " conversations",
            "Show or hide your conversations with this genie",
            "Start a new conversation with this genie; the others are kept",
            "Delete…",
            "Delete the conversation this genie is in, with all its branches",
            "its first question was asked differently",
            (questions, differently) ->
                (questions == 1 ? "one question" : questions + " questions")
                + (differently == 0 ? "" : ", then asked differently " + (differently == 1 ? "once" : differently + " times")),
            "Drag to make this list taller or shorter"
        );
    }
}
