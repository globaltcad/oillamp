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
        String LAMPS_SOMEONE_LIT_WITH_OILLAMP_AT_OUTSIDE_GENIES
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
            count -> count == 1 ? "⚠  Something went wrong" : "⚠  " + count + " things went wrong",
            "Genies carried on. Click to see what happened",
            "LAMPS FROM ELSEWHERE",
            "Lamps someone lit with `oillamp at`, outside Genies. Genies leaves them as they are"
        );
    }
}
