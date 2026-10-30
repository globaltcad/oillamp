package dev.gui.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

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
/// @param HEADER the header above a genie's pages, and what its lamp asks the user to allow
/// @param DESKTOP a genie's desktop, beside its chat
/// @param CHAT    a genie's chat: the conversation, and the composer under it
/// @param DIALOGS the dialogs the window asks and tells the user things in
/// @param ACTIONS_PAGE what can be done with a genie as a whole
/// @param HOME_FOLDERS a genie's home, and linking its folders into the user's
/// @param HOST_ACCESS_PAGE whether a genie may reach this computer, and its rules
/// @param SETTINGS_PAGE the settings every genie shares, and the welcome above them
/// @param SCHEDULE_PAGE when jobs wake a genie, and what they did
/// @param HISTORY_PAGE the moments a genie's home was saved at, and going back to one
/// @param PARTS   the parts of the window that several pages share
/// @param DATES   days and spans of time, as the pages say them
public record Lang(
    Window WINDOW,
    Header HEADER,
    Desktop DESKTOP,
    Chat CHAT,
    Dialogs DIALOGS,
    ActionsPage ACTIONS_PAGE,
    HomeFolders HOME_FOLDERS,
    HostAccessPage HOST_ACCESS_PAGE,
    SettingsPage SETTINGS_PAGE,
    SchedulePage SCHEDULE_PAGE,
    HistoryPage HISTORY_PAGE,
    Parts PARTS,
    Dates DATES
) {

    public static final Lang ENGLISH = new Lang(
        Window.ENGLISH,
        Header.ENGLISH,
        Desktop.ENGLISH,
        Chat.ENGLISH,
        Dialogs.ENGLISH,
        ActionsPage.ENGLISH,
        HomeFolders.ENGLISH,
        HostAccessPage.ENGLISH,
        SettingsPage.ENGLISH,
        SchedulePage.ENGLISH,
        HistoryPage.ENGLISH,
        Parts.ENGLISH,
        Dates.ENGLISH
    );

    /// Names as an English sentence lists them: "main", "main and docs", "a, b and c".
    private static String listedInEnglish(List<String> names) {
        return names.size() < 2
            ? String.join("", names)
            : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /// The same, as choices: "a, b or c".
    private static String eitherInEnglish(List<String> names) {
        return names.size() < 2
            ? String.join("", names)
            : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
    }

    /// A text that says how many there are of something.
    public interface Counted { String of(int count); }

    /// Something that happens every so many `hours`, at a `minute` past the hour.
    public interface EveryHours { String of(int hours, String minute); }

    /// A text that is longer than it may be: at `most` so many characters, where it `has` more.
    public interface TooLong { String of(int most, int has); }

    /// A job that runs once: on a `day`, at a `time`, which is `fromNow` so far off.
    public interface OnceAt { String of(String day, String time, String fromNow); }

    /// A computer's `memory`, and its graphics `card`, which has `cardMemory` of its own.
    public interface Computer { String of(String memory, String card, String cardMemory); }

    /// How many `times` a job runs on a day, from the `first` to the `last`.
    public interface TimesFromTo { String of(int times, String first, String last); }

    /// A text about something named, such as a genie or a window it shows.
    public interface About { String of(String what); }

    /// What a genie's card says of it when the pointer rests on it: its name, its status, and the
    /// window it shows, which is empty while it shows none.
    public interface GenieTip { String of(String name, String status, String showing); }

    /// How long a conversation, or a branch of one, is: how many `questions` were asked in it,
    /// and how many times one of them was then asked `differently`.
    public interface Questions { String of(int questions, int differently); }

    /// A text about something a genie does: `who` is the genie's name, and `what` what it does
    /// it with, such as a request it asks the user to allow, or a window it shows.
    public interface WhoAndWhat { String of(String who, String what); }

    /// Where a genie's folder was linked: the genie's name, whether the link was made `now` or
    /// was there `already`, the folder it is in, and the link's own name there.
    public interface Linked { String of(String genie, boolean now, String folder, String link); }

    /// A moment, by its `day`, such as "tomorrow", and its `time` on the clock.
    public interface DayAndTime { String of(String day, String time); }

    /// A month of a year, by name.
    public interface OfMonth { String of(YearMonth month); }

    /// A day of the week, by name.
    public interface OfWeekday { String of(DayOfWeek day); }

    /// A day of a month, written out.
    public interface OfDate { String of(LocalDate day); }

    /// Names as a sentence lists them, such as "a, b and c".
    public interface Listed { String of(List<String> names); }

    /// A text about a `branch` of a genie's history, and `what` it says of it, such as a time.
    public interface OnBranch { String of(String branch, String what); }

    /// A text about how many `moments` of a genie's history are on a `branch`.
    public interface CountedOnBranch { String of(int moments, String branch); }

    /// Why the oldest `moments` of a history would be deleted: the `branches` they are on, as a
    /// list, or empty when that goes without saying, and how much deleting them `frees`.
    public interface OldestMoments { String of(int moments, String branches, String frees); }

    /// What the user looks at in a genie's history: the `shown` branch, while the `genie` lives
    /// on another, its `home`.
    public interface Looking { String of(String shown, String genie, String home); }

    /// What oillamp deleted the last time a history grew too large: how many `moments`, how much
    /// that `freed`, and how much the history has `taken` now of the `most` it may.
    public interface Pruned { String of(int moments, String freed, String taken, String most); }

    /// Moving a `genie` to the branch `to`, from the branch `from` it lives on.
    public interface Moving { String of(String genie, String to, String from); }

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
        String DRAG_TO_MAKE_THIS_LIST_TALLER_OR_SHORTER,
        String HIDE_YOUR_GENIES,
        String SHOW_YOUR_GENIES,
        String RENAME_ELLIPSIS,
        String NEW_CONVERSATION,
        String SAVE_NOW_ELLIPSIS,
        About LINK_ITS_WORKSPACE_INTO,
        String SLEEP,
        String WAKE,
        String TRY_WAKING_AGAIN,
        String REMOVE_FROM_GENIES_ELLIPSIS,
        About DELETE_GENIE_ELLIPSIS,
        String DOING_A_SCHEDULED_JOB,
        String A_CONVERSATION_IN_WHICH_NOTHING_WAS_ASKED_YET
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
            "Drag to make this list taller or shorter",
            "Hide your genies",
            "Show your genies",
            "Rename…",
            "New conversation",
            "Save now…",
            folder -> "Link its workspace into " + folder,
            "Sleep",
            "Wake",
            "Try waking again",
            "Remove from Genies…",
            name -> "Delete " + name + "…",
            "doing a scheduled job",
            "New conversation"
        );
    }

    /// The words of the header above a genie's pages, and of what its lamp asks the user to allow
    /// under it.
    public record Header(
        String SOMETHING_WENT_WRONG_IN_GENIES_NOT_ONLY_IN_THIS_GENIE,
        String DOUBLE_CLICK_TO_RENAME,
        Counted TOKENS,
        String TOKENS_THE_MODEL_COUNTED_FOR_THIS_GENIE_SINCE_GENIES_STARTED,
        String DESKTOP,
        String DESKTOP_WATCH_THE_GENIES_DESKTOP_AND_USE_IT,
        String STOP,
        String STOP_WHAT_THE_GENIE_IS_DOING,
        String SLEEP,
        String SLEEP_END_THE_GENIES_SANDBOX,
        WhoAndWhat ASKS_YOU_TO_ALLOW,
        String A_MODEL_ALLOWED_IT_ALREADY_THIS_ONE_NEEDS_YOU_TOO,
        String REFUSE,
        String ALLOW,
        String MANAGE_THE_GENIES_ACTIONS_SCHEDULE_HISTORY_AND_HOST_ACCESS,
        String CHAT,
        String ACTIONS,
        String SCHEDULE,
        String HISTORY,
        String ACCESS,
        String CHAT_TALK_WITH_THE_GENIE,
        String ACTIONS_RENAME_SAVE_WAKE_OR_SLEEP_LINK_ITS_FOLDERS_OR_DELETE_THE_GENIE,
        String SCHEDULE_WHEN_JOBS_WAKE_THE_GENIE_AND_WHAT_THEY_DID,
        String HISTORY_THE_MOMENTS_THE_GENIES_HOME_WAS_SAVED_AT,
        String HOST_ACCESS_WHETHER_THE_GENIE_MAY_REACH_THIS_COMPUTER
    ) {
        public static final Header ENGLISH = new Header(
            "Something went wrong in Genies, not only in this genie. Click to see what happened",
            "Double-click to rename",
            count -> count == 0 ? "" : String.format("%,d tokens", count),
            "Tokens the model counted for this genie since Genies started",
            "Desktop",
            "Desktop: watch the genie's desktop, and use it",
            "Stop",
            "Stop what the genie is doing",
            "Sleep",
            "Sleep: end the genie's sandbox. Its home and this conversation are kept.",
            (who, what) -> who + " asks you to allow: " + what,
            "A model allowed it already; this one needs you too. "
            + "Nothing happens until you decide, and after a minute it is refused.",
            "Refuse",
            "Allow",
            "Manage: the genie's actions, schedule, history and host access",
            "Chat",
            "Actions",
            "Schedule",
            "History",
            "Access",
            "Chat: talk with the genie",
            "Actions: rename, save, wake or sleep, link its folders, or delete the genie",
            "Schedule: when jobs wake the genie, and what they did",
            "History: the moments the genie's home was saved at, and going back to one",
            "Host access: whether the genie may reach this computer, and its rules"
        );
    }

    /// The words of a genie's desktop, beside its chat, and of how it is shown.
    public record Desktop(
        String DRAG_TO_SHARE_THE_ROOM_BETWEEN_THE_CHAT_AND_THE_DESKTOP,
        String FITTED,
        String RECORDED_SO_IT_KEEPS_ITS_OWN_SIZE,
        String CLICK_THE_DESKTOP_TO_USE_IT,
        WhoAndWhat SHOWS_YOU,
        String FIT,
        String GIVE_THE_DESKTOP_THE_SIZE_OF_THIS_PANEL,
        String FIXED,
        String SHOW_THE_WHOLE_DESKTOP_AT_ITS_OWN_SIZE,
        String SMALLER_OR_CTRL_AND_THE_MOUSE_WHEEL,
        String LARGER_OR_CTRL_AND_THE_MOUSE_WHEEL,
        String KEYS,
        String THE_KEYS_THAT_MAKE_A_WINDOW_ON_THE_DESKTOP_FULLSCREEN,
        String KEYS_FOR_THE_DESKTOP,
        String CLICK_A_WINDOW_FIRST_SO_THAT_IT_HAS_THE_KEYBOARD,
        String FULLSCREEN_AND_BACK,
        String CLOSE_THE_WINDOW,
        String MINIMIZE_THE_WINDOW,
        String BRING_EVERY_MINIMIZED_WINDOW_BACK,
        String SUPER_IS_THE_KEY_WITH_THE_WINDOWS_LOGO,
        String CTRL,
        String SUPER,
        String THE_DESKTOP_SHOWS_HERE_WHILE_THE_GENIE_IS_AWAKE,
        String CONNECTING_TO_THE_DESKTOP,
        About THE_DESKTOP_WENT_AWAY,
        About COULD_NOT_SHOW_THE_DESKTOP
    ) {
        public static final Desktop ENGLISH = new Desktop(
            "Drag to share the room between the chat and the desktop, or all the way to close the desktop."
            + " Double-click to let Genies share it",
            "fitted",
            "Recorded, so it keeps its own size",
            "Click the desktop to use it",
            (who, what) -> who + " shows you: " + what,
            "Fit",
            "Give the desktop the size of this panel, so what the genie shows fills it."
            + " It gets its own size back when you close it.",
            "Fixed",
            "Show the whole desktop at its own size, shrunk into this panel",
            "Smaller (or Ctrl and the mouse wheel on the desktop)",
            "Larger (or Ctrl and the mouse wheel on the desktop)",
            "Keys",
            "The keys that make a window on the desktop fullscreen, close it or minimize it",
            "Keys for the desktop",
            "Click a window first, so that it has the keyboard.",
            "Fullscreen, and back",
            "Close the window",
            "Minimize the window",
            "Bring every minimized window back",
            "Super is the key with the Windows logo.",
            "Ctrl",
            "Super",
            "The desktop shows here while the genie is awake.",
            "Connecting to the desktop…",
            why -> "The desktop went away: " + why,
            why -> "Could not show the desktop: " + why
        );
    }

    /// The words of a genie's chat: the conversation, the files the genie hands out, and the
    /// composer under it.
    public record Chat(
        About IS_LISTENING,
        About IS_WAKING,
        About IS_IN_THE_LAMP,
        About COULD_NOT_WAKE,
        String ASK_FOR_ANYTHING_IT_HAS_A_DESKTOP_AND_A_SHELL_OF_ITS_OWN,
        String ITS_SANDBOX_IS_STARTING_THE_VERY_FIRST_TIME_IT_IS_MADE,
        String WAKE_IT_TO_TALK_IT_KEEPS_ITS_OWN_DESKTOP,
        String WHAT_WENT_WRONG_IS_SAID_BELOW_TRY_AGAIN_ONCE_THAT_IS_SORTED_OUT,
        String OUTBOX,
        About SAVE_SOMEWHERE_ON_THIS_COMPUTER,
        About IS_ANSWERING_IN_ANOTHER_CONVERSATION,
        String GIVE_THE_GENIE_A_FILE_IT_LANDS_IN_INBOX,
        String RETURN_SENDS_SHIFT_AND_RETURN_STARTS_A_NEW_LINE,
        String SEND,
        String NEW_CONVERSATION,
        String START_A_NEW_CONVERSATION_OR_SELECT_ONE_IN_THE_TREE_TO_RETURN_TO_IT,
        WhoAndWhat WAKING,
        About IS_ASLEEP_ITS_SANDBOX_IS_OFF,
        WhoAndWhat COULD_NOT_WAKE_BECAUSE,
        String TRY_AGAIN,
        String WAKE,
        String EDIT,
        String ASK_THIS_DIFFERENTLY_WHAT_FOLLOWED_IS_KEPT_AS_A_BRANCH,
        String PREVIOUS_CONVERSATION_VERSION,
        String NEXT_CONVERSATION_VERSION,
        String COPY,
        String COPY_THE_ANSWER_AS_THE_GENIE_WROTE_IT,
        String THINKING,
        String THOUGHT,
        String CLICK_TO_FOLD_THE_THOUGHTS_AWAY,
        String CLICK_TO_SEE_WHAT_THE_GENIE_THINKS,
        About YOUR_MESSAGE_WAITS_UNTIL_THIS_JOB_IS_DONE,
        String RUNNING,
        String FAILED,
        String CLICK_TO_SEE_WHAT_IT_PRINTED,
        String SAVE_ELLIPSIS,
        WhoAndWhat IS_DOING_A_SCHEDULED_JOB_FIRST,
        About IS_THINKING,
        About WRITTEN_BY
    ) {
        public static final Chat ENGLISH = new Chat(
            name -> name + " is listening.",
            name -> name + " is waking.",
            name -> name + " is in the lamp.",
            name -> name + " could not wake.",
            "Ask for anything. It has a desktop and a shell of its own, and hands you files it makes.",
            "Its sandbox is starting. The very first time, the sandbox is made, which takes several minutes.",
            "Wake it to talk. It keeps its own desktop, safely away from yours.",
            "What went wrong is said below. Try again once that is sorted out.",
            "OUTBOX",
            file -> "Save " + file + " somewhere on this computer",
            name -> name + " is answering in another conversation. Write here once it is done.",
            "Give the genie a file; it lands in ~/inbox",
            "Return sends; Shift and Return starts a new line",
            "Send",
            "New conversation",
            "Start a new conversation, or select a conversation in the tree to return to it",
            (name, doing) -> "Waking " + name + ": " + doing + "…",
            name -> name + " is asleep. Its sandbox is off, its home and conversation are kept.",
            (name, why) -> name + " could not wake: " + why,
            "Try again",
            "Wake",
            "Edit",
            "Ask this differently. What followed is kept, as a branch in the genie's conversations.",
            "Previous conversation version",
            "Next conversation version",
            "Copy",
            "Copy the answer, as the genie wrote it",
            "thinking",
            "thought",
            "Click to fold the thoughts away",
            "Click to see what the genie thinks",
            job -> job + ". The genie does one thing at a time, so your message waits until this job is done.",
            "running…",
            "failed",
            "Click to see what it printed",
            "Save…",
            (name, job) -> name + " is doing a scheduled job first: " + job,
            name -> name + " is thinking",
            model -> "Written by " + model
        );
    }

    /// The words of the dialogs the window asks and tells the user things in.
    public record Dialogs(
        About DELETE_THE_CONVERSATION_FOR_GOOD_WITH_ALL_ITS_BRANCHES,
        String DELETE_A_CONVERSATION,
        String DELETE,
        String KEEP,
        About A_NEW_NAME_FOR,
        String RENAME_A_GENIE,
        Linked WORKSPACE_IS_IN_YOUR_FOLDER_NOW_IT_IS_A_LINK_NOT_A_COPY,
        String LINKED,
        String COULD_NOT_LINK,
        Counted THINGS_WENT_WRONG_THAT_GENIES_DID_NOT_EXPECT,
        String WHAT_WENT_WRONG,
        About GENIES_CARRIED_ON_ALL_OF_IT_IS_KEPT_IN,
        String CLOSE,
        WhoAndWhat REMOVE_FROM_GENIES_ITS_LAMP_STAYS_WHERE_IT_IS,
        String REMOVE_A_LAMP_FROM_GENIES,
        String REMOVE,
        About DELETE_FOR_GOOD_ITS_HOME_AND_YOUR_CONVERSATIONS_WITH_IT,
        String DELETE_A_GENIE,
        String ADD_A_LAMP_MADE_WITH_OILLAMP_AT,
        String ADD,
        String NOT_A_LAMP,
        About ASK_DIFFERENTLY,
        About GIVE_A_FILE,
        About SAVE
    ) {
        public static final Dialogs ENGLISH = new Dialogs(
            title -> "Delete the conversation \"" + title + "\" for good, with all its branches?",
            "Delete a conversation",
            "Delete",
            "Keep",
            name -> "A new name for " + name + ":",
            "Rename a genie",
            (genie, now, folder, link) ->
                genie + "'s workspace " + (now ? "is" : "was") + " in your " + folder + (now ? " now" : " already")
                + ", as \"" + link + "\".\nIt is a link, not a copy: " + genie + " sees what you change there.",
            "Linked",
            "Could not link",
            count -> count == 1
                ? "Something went wrong that Genies did not expect."
                : count + " things went wrong that Genies did not expect.",
            "What went wrong",
            log -> "Genies carried on. All of it is kept in " + log + ", which helps whoever fixes it.",
            "Close",
            (name, where) ->
                "Remove " + name + " from Genies? Its lamp stays where it is, at "
                + where + ", as it is, running if it runs.\nAdd it again with \"Add a lamp from elsewhere…\".",
            "Remove a lamp from Genies",
            "Remove",
            name -> "Delete " + name + " for good? Its home, everything it made and your conversations with it are deleted.",
            "Delete a genie",
            "Add a lamp made with `oillamp at`",
            "Add",
            "Not a lamp",
            name -> "Ask " + name + " differently",
            name -> "Give " + name + " a file",
            file -> "Save " + file
        );
    }

    /// The words of a genie's Actions page: what can be done with the genie as a whole.
    public record ActionsPage(
        String ACTIONS,
        About WHAT_CAN_BE_DONE_WITH_IT_AS_A_WHOLE,
        String NAME,
        String ENTER_KEEPS_THE_NEW_NAME_ESCAPE_THE_ONE_IT_HAD,
        String WHAT_IT_IS_CALLED_IN_YOUR_LIST_AND_ABOVE_ITS_CHAT,
        String RENAME,
        String CANCEL,
        String SAVE,
        String NEW_CONVERSATION,
        About START_AFRESH_WITH_IT_THIS_CONVERSATION_IS_KEPT,
        String START_A_NEW_CONVERSATION,
        String SAVE_NOW,
        About SAVE_ITS_HOME_AS_IT_IS_NOW,
        String SAVE_NOW_ELLIPSIS,
        String SLEEP,
        String WAKE,
        About IS_IN_THE_LAMP_WAKING_IT_STARTS_ITS_SANDBOX,
        About IS_WAKING,
        About DID_NOT_WAKE_ITS_HOME_IS_KEPT,
        About END_ITS_SANDBOX_FOR_WHOEVER_LIT_IT_TOO,
        About END_ITS_SANDBOX,
        String WAKING,
        String TRY_WAKING_AGAIN,
        String FOLDERS,
        String REMOVE,
        String DELETE,
        About REMOVE_FROM_GENIES_ITS_LAMP_WAS_MADE_ELSEWHERE,
        About DELETE_FOR_GOOD_YOU_ARE_ASKED_FIRST,
        String REMOVE_FROM_GENIES_ELLIPSIS,
        About DELETE_GENIE_ELLIPSIS
    ) {
        public static final ActionsPage ENGLISH = new ActionsPage(
            "Actions",
            genie -> "What can be done with " + genie + " as a whole. A right-click on its card in your list offers the same.",
            "Name",
            "Enter keeps the new name, Escape the one it had.",
            "What it is called in your list and above its chat.",
            "Rename",
            "Cancel",
            "Save",
            "New conversation",
            genie -> "Start afresh with " + genie + ", waking it first if it sleeps. This conversation is kept, to go back to.",
            "Start a new conversation",
            "Save now",
            genie -> "Save " + genie + "'s home as it is now: a moment in its history to go back to.",
            "Save now…",
            "Sleep",
            "Wake",
            genie -> genie + " is in the lamp. Waking it starts its sandbox, with its home as it left it.",
            genie -> genie + " is waking…",
            genie -> genie + " did not wake. Its home is kept, so it can try again.",
            genie -> "End " + genie + "'s sandbox, for whoever lit it with `oillamp at` too. Its home and this conversation are kept.",
            genie -> "End " + genie + "'s sandbox. Its home and this conversation are kept.",
            "Waking…",
            "Try waking again",
            "Folders",
            "Remove",
            "Delete",
            genie ->
                "Remove " + genie + " from Genies. Its lamp was made elsewhere, with `oillamp at`, and stays "
                + "where it is, as it is, running if it runs. You are asked first.",
            genie ->
                "Delete " + genie + " for good: its home, everything it made and your "
                + "conversations with it. You are asked first.",
            "Remove from Genies…",
            genie -> "Delete " + genie + "…"
        );
    }

    /// The words of a genie's home on its Actions page, and of linking its folders into the
    /// user's.
    public record HomeFolders(
        About HAS_NO_HOME_YET,
        About ITS_HOME_IS_A_FOLDER_ON_THIS_COMPUTER_LINK_ONE_OF_ITS_FOLDERS_INTO_YOURS,
        String ON_THIS_COMPUTER,
        String OPEN_IN_YOUR_FILE_MANAGER,
        String THE_LINKS_NAME,
        About LINK_INTO,
        String LINK_INTO_ELLIPSIS,
        About OR_DRAG_A_FOLDER_FROM_THE_TREE_INTO_YOUR_FILE_MANAGER,
        About WHERE_TO_PUT_THE_LINK,
        String LINK_HERE,
        About LINKED,
        About IT_WAS_LINKED_ALREADY
    ) {
        public static final HomeFolders ENGLISH = new HomeFolders(
            genie -> genie + " has no home yet. It gets one as it first wakes.",
            genie ->
                genie + "'s home is a folder on this computer. Link one of its folders into yours, to find what "
                + genie + " made in your file manager. A link is no copy: a change on either side is one on both.",
            "ON THIS COMPUTER",
            "Open in your file manager",
            "THE LINK'S NAME",
            folder -> "Link into " + folder,
            "Link into…",
            genie ->
                "Or drag a folder from the tree into your file manager. "
                + "Deleting " + genie + " leaves its links behind, leading nowhere.",
            link -> "Where to put the link \"" + link + "\"",
            "Link here",
            at -> "Linked: " + at,
            at -> "It was linked already: " + at
        );
    }

    /// The words of a genie's Host access page: whether it may reach this computer, and the
    /// rules a model judges its requests by.
    public record HostAccessPage(
        String HOST_ACCESS,
        About MAY_ASK_TO_REACH_THIS_COMPUTER_A_MODEL_JUDGES_EACH_REQUEST,
        About CANNOT_REACH_THIS_COMPUTER,
        String READING_THE_HOST_ACCESS,
        About WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP,
        About SEND_IT_TO_SLEEP_TO_CHANGE_THESE,
        String MAY_IT_REACH_THIS_COMPUTER,
        String EXPERIMENTAL_NEVER_TURN_THIS_ON_ON_A_COMPUTER_WITH_SENSITIVE_DATA,
        String FOLDERS_IT_MAY_READ,
        String ONE_PER_LINE_SUCH_AS_DOCUMENTS,
        String FOLDERS_IT_MAY_READ_AND_CHANGE,
        String IT_MAY_WRITE_FILES_THERE_AND_COPY_WHAT_IT_MADE_THERE,
        String ANYTHING_ELSE_IN_YOUR_OWN_WORDS,
        String SAY_WHAT_MUST_NOT_HAPPEN,
        String UNDO_CHANGES,
        String SAVE,
        String SAVING,
        String WRITE_THESE_RULES_INTO_THE_GENIES_LAMP,
        About IS_NOT_A_PATH_THE_MODEL_THAT_JUDGES_READS_IT_AS_WORDS,
        String NOTHING_IS_LISTED_SO_THE_MODEL_THAT_JUDGES_WILL_REFUSE_EVERY_REQUEST
    ) {
        public static final HostAccessPage ENGLISH = new HostAccessPage(
            "Host access",
            genie -> genie + " may ask to reach this computer. A model judges each request against these rules before anything happens.",
            genie -> genie + " cannot reach this computer.",
            "Reading the host access…",
            genie -> "Wake " + genie + " once first. Its settings are kept in its lamp, which it gets as it first wakes.",
            genie -> "Send " + genie + " to sleep to change these. While it is awake, it keeps the settings it woke with.",
            "MAY IT REACH THIS COMPUTER?",
            "Experimental. Never turn this on on a computer with sensitive data. "
            + "The model that judges reads these rules with every request, and nothing else.",
            "FOLDERS IT MAY READ",
            "One per line, such as ~/Documents.",
            "FOLDERS IT MAY READ AND CHANGE",
            "It may write files there, and copy what it made there.",
            "ANYTHING ELSE, IN YOUR OWN WORDS",
            "Say what must not happen, such as \"never open my tax returns\".",
            "Undo changes",
            "Save",
            "Saving…",
            "Write these rules into the genie's lamp",
            folder -> "\"" + folder + "\" is not a path. The model that judges reads it as words, so make sure they say what you mean.",
            "Nothing is listed, so the model that judges will refuse every request."
        );
    }

    /// The words of the settings every genie shares, and of the welcome above them while there
    /// are no genies.
    public record SettingsPage(
        String YOUR_GENIES_MODEL,
        String SETTINGS,
        String WHERE_YOUR_GENIES_MODEL_RUNS_AND_HOW_THEY_REACH_IT,
        String GENIES_SETS_UP_A_MODEL_ON_THIS_COMPUTER_FOR_YOU_WITH_OLLAMA,
        String ADVANCED_EDEN_AI_OR_A_MODEL_SERVER_OF_YOUR_OWN,
        String FOR_A_MODEL_ELSEWHERE_OR_A_MODEL_SERVER_OTHER_THAN_OLLAMA,
        String BACK_TO_THE_SIMPLE_WAY_OLLAMA_SET_UP_BY_GENIES,
        String THIS_COMPUTER,
        String GENIES_IS_LOOKING_WHAT_THIS_COMPUTER_HAS,
        String OLLAMA,
        String MODEL,
        String GENIES_SUGGESTS_THE_BEST_MODEL_THIS_COMPUTER_RUNS_WELL,
        About YOUR_GENIES_USE,
        String STOP,
        String STOP_SETTING_UP_A_MODELS_DOWNLOAD_GOES_ON_THE_NEXT_TIME,
        String UNTIL_THEN_YOUR_GENIES_USE_EDEN_AI,
        About UNTIL_THEN_YOUR_GENIES_USE_THE_MODEL_SERVER_AT,
        String THE_MODEL_RUNS,
        String AT_EDEN_AI,
        String IN_THE_EU_WITH_YOUR_EDEN_AI_KEY,
        String ON_A_SERVER_ELSEWHERE,
        String A_MODEL_SERVER_OF_YOUR_OWN_ON_ANOTHER_MACHINE,
        String ON_THIS_COMPUTER,
        String A_MODEL_SERVER_SUCH_AS_OLLAMA_LM_STUDIO_OR_LLAMA_CPP,
        String KEY,
        About USE,
        String FOUND_WHERE_GENIES_WAS_STARTED,
        String NOT_SET_WHERE_GENIES_WAS_STARTED,
        String USE_THIS_KEY,
        String THE_KEY_STAYS_ON_THIS_COMPUTER_GENIES_NEVER_SEE_IT,
        String ADDRESS,
        String WHERE_THE_SERVERS_API_IS_IT_MUST_BE_HTTPS,
        String LEAVE_IT_EMPTY_IF_THE_SERVER_ASKS_FOR_NONE,
        String WHERE_THE_SERVERS_API_IS_ON_THIS_COMPUTER_NO_KEY_IS_NEEDED,
        String LOOK_UP,
        String ASK_THE_SERVICE_WHICH_MODELS_IT_OFFERS,
        String CHANGES_TAKE_EFFECT_WHEN_A_GENIE_WAKES,
        String YOUR_GENIES_CAN_REACH_THEIR_MODEL,
        String DONE,
        String YOUR_FIRST_GENIE,
        String A_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN_THAT_WAKES_AT_ONCE,
        String APPEARANCE,
        String THE_LAMP_AT_NIGHT_OR_BY_DAY,
        String DARK,
        String LIGHT,
        String WELCOME_TO_GENIES,
        String A_GENIE_IS_AN_AI_HELPER_WITH_A_COMPUTER_OF_ITS_OWN,
        String GENIES_FOUND_AN_EDEN_AI_KEY_WHERE_IT_WAS_STARTED,
        String TO_THINK_GENIES_NEED_A_MODEL_GENIES_CAN_SET_ONE_UP_FOR_YOU,
        String GENIES_THAT_ARE_AWAKE_ANSWER_WITH_A_NEW_MODEL_FROM_THEIR_NEXT_MESSAGE_ON,
        String A_MODELS_NAME_HAS_NO_SPACES_AND_ONLY_PLAIN_LETTERS_DIGITS_AND_SIGNS,
        String NAME_THE_MODEL_THE_GENIES_SHOULD_USE_LOOK_UP_SHOWS_EDEN_AIS_LIST,
        About ENTER_A_KEY_OR_USE_THE_ONE_IN,
        About IS_NOT_SET_WHERE_GENIES_WAS_STARTED_ENTER_A_KEY_IN_THE_SETTINGS,
        String ENTER_THE_SERVERS_ADDRESS,
        String NAME_THE_MODEL_AS_THE_SERVER_LISTS_IT,
        String A_MODEL_SERVER_ON_THIS_COMPUTER_IS_REACHED_AT_127_0_0_1_OR_LOCALHOST,
        String NAME_THE_MODEL_AS_THE_MODEL_SERVER_LISTS_IT,
        String THE_ADDRESS_SHOULD_LOOK_LIKE_AN_ADDRESS_OF_A_MODEL_SERVER,
        String THE_ADDRESS_SHOULD_BE_A_SCHEME_A_HOST_AND_PERHAPS_A_PORT_AND_A_PATH,
        String THE_KEY_IS_SENT_TO_THE_SERVER_SO_IT_MUST_USE_HTTPS,
        About INSTALL_OLLAMA_AND_GET,
        About GET,
        About USE_MODEL,
        String GENIES_IS_LOOKING_WHETHER_OLLAMA_IS_ON_THIS_COMPUTER,
        About OLLAMA_RUNS_ON_THIS_COMPUTER,
        String OLLAMA_IS_INSTALLED_GENIES_STARTS_IT_WHEN_THE_GENIES_NEED_IT,
        String OLLAMA_IS_NOT_INSTALLED_YET_GENIES_INSTALLS_IT_FOR_YOU,
        String GENIES_COULD_NOT_READ_HOW_MUCH_MEMORY_THIS_COMPUTER_HAS,
        About MEMORY_AND_NO_GRAPHICS_CARD_A_MODEL_CAN_RUN_ON,
        Computer MEMORY_AND_THE_GRAPHICS_CARD,
        About ABOUT_SO_MUCH_TO_DOWNLOAD,
        String ITS_NAME_DOES_NOT_SAY_HOW_LARGE_IT_IS,
        String IT_RUNS_ON_THE_GRAPHICS_CARD_SO_IT_ANSWERS_QUICKLY,
        String IT_RUNS_ON_THE_PROCESSOR_AT_A_FAIR_PACE,
        String IT_RUNS_ON_THE_PROCESSOR_SO_IT_ANSWERS_SLOWLY,
        About RUNNING_IT_NEEDS_MORE_MEMORY_THAN_THIS_COMPUTER_CAN_SPARE
    ) {
        public static final SettingsPage ENGLISH = new SettingsPage(
            "Your genies' model",
            "Settings",
            "Where your genies' model runs, and how they reach it.",
            "Genies sets up a model on this computer for you, with Ollama: free, and your conversations stay here.",
            "Advanced: Eden AI, or a model server of your own",
            "For a model elsewhere, or a model server on this computer other than Ollama",
            "Back to the simple way: Ollama, set up by Genies",
            "This computer",
            "Genies is looking what this computer has…",
            "Ollama",
            "Model",
            "Genies suggests the best model this computer runs well. Any other "
            + "Ollama model that can use tools works too: ollama.com/search?c=tools lists them.",
            wanted -> "Your genies use " + wanted + ".",
            "Stop",
            "Stop setting up. A model's download goes on from where it was the next time",
            "Until then, your genies use Eden AI.",
            at -> "Until then, your genies use the model server at " + at + ".",
            "The model runs",
            "at Eden AI",
            "In the EU, with your Eden AI key.",
            "on a server elsewhere",
            "A model server of your own on another machine, such as Ollama behind a proxy that asks for a key.",
            "on this computer",
            "A model server such as Ollama, LM Studio or llama.cpp.",
            "Key",
            variable -> "Use " + variable,
            "Found where Genies was started.",
            "Not set where Genies was started.",
            "Use this key:",
            "The key stays on this computer. Genies never see it: oillamp adds it to "
            + "their requests as they leave the sandbox. An entered key is kept in a file only you can read.",
            "Address",
            "Where the server's OpenAI-style API is, such as "
            + "https://ollama.example.com/v1. It must be https://, because the key goes with every request.",
            "Leave it empty if the server asks for none.",
            "Where the server's OpenAI-style API is: http://127.0.0.1:11434/v1 for Ollama, "
            + "http://127.0.0.1:1234/v1 for LM Studio, http://127.0.0.1:8080/v1 for llama.cpp. No key is needed.",
            "Look up",
            "Ask the service which models it offers",
            "Changes take effect when a genie wakes.",
            "Your genies can reach their model.",
            "Done",
            "Your first genie",
            "A genie, with a sandboxed desktop of its own, that wakes at once",
            "Appearance",
            "The lamp at night, or by day.",
            "Dark",
            "Light",
            "Welcome to Genies",
            "A genie is an AI helper with a computer of its own: a Linux desktop in a sandbox, "
            + "where it can browse the web, run programs and make files, without ever touching yours.",
            "Genies found an Eden AI key where it was started, so your genies can think at Eden AI right away. "
            + "Or have Genies set up a model on this computer instead: the simple way, below.",
            "To think, genies need a model. Genies can set one up for you, right here on this computer.",
            "Genies that are awake answer with a new model from their next message on. "
            + "A new place, address or key reaches them when they next wake, and so does "
            + "everything for a genie that kept running while Genies was closed.",
            "A model's name has no spaces and only plain letters, digits and signs, such as azure/gpt-6.1-sol.",
            "Name the model the genies should use; Look up shows Eden AI's list.",
            variable -> "Enter a key, or use the one in " + variable + ".",
            variable -> variable + " is not set where Genies was started. Enter a key in the settings, or set it and start Genies again.",
            "Enter the server's address, such as https://ollama.example.com/v1.",
            "Name the model, as the server lists it; Look up shows its list.",
            "A model server on this computer is reached at 127.0.0.1 or localhost, such as http://127.0.0.1:11434/v1.",
            "Name the model, as the model server lists it; Look up shows its list.",
            "The address should look like https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.",
            "The address should be a scheme, a host, and perhaps a port and a path, such as https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.",
            "The key is sent to the server, so it must use https:// (http:// only on this computer).",
            wanted -> "Install Ollama and get " + wanted,
            wanted -> "Get " + wanted,
            wanted -> "Use " + wanted,
            "Genies is looking whether Ollama is on this computer…",
            version -> "Ollama " + version + " runs on this computer.",
            "Ollama is installed; Genies starts it when the genies need it.",
            "Ollama is not installed yet. Genies installs it for you, for you alone: no password, "
            + "nothing changes outside its own folder. It is a download of about 1.4 GB.",
            "Genies could not read how much memory this computer has.",
            memory -> memory + " of memory, and no graphics card a model can run on.",
            (memory, card, cardMemory) -> memory + " of memory, and the graphics card " + card + " with " + cardMemory + ".",
            size -> "About " + size + " to download.",
            "Its name does not say how large it is, so Genies cannot tell how well it runs here.",
            "It runs on the graphics card, so it answers quickly.",
            "It runs on the processor, at a fair pace.",
            "It runs on the processor, so it answers slowly.",
            need -> "Running, it needs about " + need + " of memory, more than this computer can spare: it may not load, or be very slow."
        );
    }

    /// The words of a genie's Schedule page: when jobs wake the genie, and what they did.
    public record SchedulePage(
        String PAUSED_NO_JOB_RUNS_UNTIL_YOU_RESUME_THE_SCHEDULE,
        About ON_JOBS_WAKE_IT_AT_THEIR_TIMES,
        About IS_WAKING_JOBS_RUN_ONCE_IT_IS_AWAKE,
        About IS_ASLEEP_JOBS_RUN_ONLY_WHILE_IT_IS_AWAKE,
        String SCHEDULE,
        String WAKE,
        String JOBS_RUN_ONLY_WHILE_THE_GENIE_IS_AWAKE,
        String RESUME,
        String PAUSE,
        String LET_THE_JOBS_RUN_AGAIN,
        String HOLD_EVERY_JOB_UNTIL_YOU_RESUME_NOTHING_IS_LOST,
        String NEW_JOB,
        String THE_WEEK,
        String SHOW_ONLY_THE_LAST_RUNS,
        Counted SHOW_EARLIER_RUNS,
        String READING_THE_SCHEDULE,
        String NOTHING_RAN_THIS_WEEK_AND_NOTHING_IS_PLANNED,
        String CLICK_AGAIN_TO_SEE_EVERY_JOB,
        String CLICK_TO_BRING_THIS_JOBS_TIMES_FORWARD,
        About ADDED_BY,
        String OPEN_THE_CONVERSATION,
        String STOP,
        String STOP_THIS_RUN_WHAT_THE_GENIE_DID_SO_FAR_IS_KEPT,
        Counted JOBS,
        String NOTHING_ON_THE_SCHEDULE_YET,
        About GIVE_IT_A_TASK_AND_A_TIME,
        String CHANGE_SWITCH_OFF_OR_REMOVE_THIS_JOB,
        About UNTIL,
        String SWITCHED_OFF,
        String WILL_NOT_RUN_AGAIN,
        String DUE_NOW,
        String TODAY,
        String TOMORROW,
        DayAndTime NEXT,
        String CHANGE_ELLIPSIS,
        String SWITCH_OFF,
        String SWITCH_ON,
        String REMOVE_ELLIPSIS,
        About TAKE_IT_OFF_THE_SCHEDULE_WHAT_ITS_RUNS_DID_STAYS_IN_THE_HISTORY,
        String REMOVE_A_JOB,
        String REMOVE,
        String KEEP,
        String ON_CLICK_TO_SWITCH_IT_OFF_KEEPING_IT_ON_THE_SCHEDULE,
        String OFF_CLICK_TO_SWITCH_IT_ON,
        String CHANGE_A_JOB,
        About A_NEW_JOB_FOR,
        String IT_GETS_THE_TASK_AT_THE_TIME_YOU_CHOOSE_IN_A_CONVERSATION_OF_ITS_OWN,
        String CLOSE_WITHOUT_SAVING,
        String CANCEL,
        String SAVING,
        String SAVE_THE_JOB,
        String ADD_TO_THE_SCHEDULE,
        String CTRL_AND_RETURN_SAVE_TOO,
        String THE_TASK,
        String BRIEF_IT_AS_YOU_WOULD_A_COLLEAGUE,
        String WHEN,
        String ONCE,
        String AGAIN_AND_AGAIN,
        String IN_AN_HOUR,
        String THIS_EVENING,
        String TOMORROW_MORNING,
        String MONDAY_MORNING,
        String AT,
        String A_NUMBER_FROM_0_TO_59,
        String MINUTES_PAST_EACH_HOUR,
        String FIVE_FIELDS_AS_CRON_HAS_THEM,
        String SUCH_AS_09_00_OR_9_30,
        String ENDS,
        String NEVER,
        String ON_A_DAY,
        String THE_MONTH_BEFORE,
        String THE_MONTH_AFTER,
        OfMonth MONTH_AND_YEAR,
        OfWeekday WEEKDAY_IN_TWO_LETTERS,
        OfWeekday WEEKDAY,
        Listed DAYS_LISTED,
        String EVERY_HOUR_ON_THE_HOUR,
        About EVERY_HOUR_AT_PAST,
        About EVERY_DAY_AT,
        About EVERY_WEEKDAY_AT,
        DayAndTime EVERY_DAYS_AT,
        Counted EVERY_MINUTES,
        Counted EVERY_HOURS_ON_THE_HOUR,
        EveryHours EVERY_HOURS_AT_PAST,
        About ON_THE_CRON_SCHEDULE,
        String EVERY_HOUR,
        String EVERY_DAY,
        String WEEKDAYS,
        String SOME_DAYS,
        String CUSTOM,
        String WRITE_WHAT_THE_GENIE_SHOULD_DO,
        TooLong A_TASK_CAN_BE_AT_MOST,
        String THAT_TIME_HAS_PASSED_PICK_A_LATER_ONE,
        String THE_MINUTE_PAST_THE_HOUR_IS_A_NUMBER_FROM_0_TO_59,
        String PICK_AT_LEAST_ONE_DAY,
        String WRITE_A_CRON_EXPRESSION,
        String THE_LAST_DAY_HAS_PASSED,
        String WRITE_THE_TIME_AS_HOURS_AND_MINUTES,
        OnceAt ONCE_AT_FROM_NOW,
        DayAndTime FIRST_AT,
        String BUT_NOT_BEFORE_ITS_LAST_DAY,
        String OILLAMP_READS_THE_EXPRESSION_WHEN_YOU_SAVE_THE_JOB,
        DayAndTime ONCE_AT,
        String ONCE_WITHOUT_A_TIME,
        String A_JOB_THAT_HAS_RUN,
        String A_JOB,
        About WORKING_ON_IT_SINCE,
        String NOW_ON_THE_TIMELINE,
        String ITS_TIME_CAME_IT_RUNS_AS_SOON_AS_THE_GENIE_IS_AWAKE_AND_FREE,
        TimesFromTo TIMES_FROM_TO,
        String DONE,
        String FAILED,
        String RAN_OUT_OF_TIME,
        String STOPPED
    ) {
        public static final SchedulePage ENGLISH = new SchedulePage(
            "Paused. No job runs until you resume the schedule.",
            genie -> "On. Jobs wake " + genie + " at their times, one after the other.",
            genie -> genie + " is waking. Jobs run once it is awake.",
            genie -> genie + " is asleep. Jobs run only while it is awake; one whose time came meanwhile runs as soon as it wakes.",
            "Schedule",
            "Wake",
            "Jobs run only while the genie is awake",
            "Resume",
            "Pause",
            "Let the jobs run again",
            "Hold every job until you resume; nothing is lost",
            "New job",
            "THE WEEK",
            "Show only the last runs",
            count -> "Show " + count + " earlier " + (count == 1 ? "run" : "runs"),
            "Reading the schedule…",
            "Nothing ran this week, and nothing is planned.",
            "Click again to see every job",
            "Click to bring this job's times forward",
            genie -> "added by " + genie,
            "Open the conversation",
            "Stop",
            "Stop this run; what the genie did so far is kept, and saved",
            count -> count == 0 ? "JOBS" : "JOBS  ·  " + count,
            "Nothing on the schedule yet",
            genie ->
                "Give " + genie + " a task and a time, "
                + "once or again and again: a morning check of the build, a weekly report, a reminder "
                + "to tidy up. It works on it in a conversation of its own, and you can read it here afterwards.",
            "Change, switch off or remove this job",
            day -> "until " + day,
            "switched off",
            "will not run again",
            "due now",
            "today",
            "tomorrow",
            (day, time) -> "next: " + day + ", " + time,
            "Change…",
            "Switch off",
            "Switch on",
            "Remove…",
            job -> "Take \"" + job + "\" off the schedule? What its runs did stays in the history.",
            "Remove a job",
            "Remove",
            "Keep",
            "On: click to switch it off, keeping it on the schedule",
            "Off: click to switch it on",
            "Change a job",
            genie -> "A new job for " + genie,
            "It gets the task at the time you choose, in a conversation of its own, "
            + "and while it is awake: a job whose time came while it slept runs as soon as it wakes.",
            "Close without saving",
            "Cancel",
            "Saving…",
            "Save the job",
            "Add to the schedule",
            "Ctrl and Return save too",
            "THE TASK",
            "Brief it as you would a colleague: what to do, where things are, and "
            + "what done looks like. Each run starts a fresh conversation; between runs, the genie "
            + "keeps notes in ~/workspace/NOTES.md.",
            "WHEN",
            "Once",
            "Again and again",
            "In an hour",
            "This evening",
            "Tomorrow morning",
            "Monday morning",
            "At",
            "A number from 0 to 59",
            "minutes past each hour",
            "Five fields, as cron has them: minute, hour, day of the month, month, "
            + "day of the week. 0 9 1 * * is nine on the first of each month; */30 * * * * every half hour.",
            "Such as 09:00, or 9.30",
            "Ends",
            "Never",
            "On a day",
            "The month before",
            "The month after",
            month -> month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear(),
            day -> day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2),
            day -> day.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            Lang::listedInEnglish,
            "Every hour, on the hour",
            minute -> "Every hour at " + minute + " past",
            time -> "Every day at " + time,
            time -> "Every weekday at " + time,
            (days, time) -> "Every " + days + " at " + time,
            count -> "Every " + count + " minutes",
            count -> "Every " + count + " hours, on the hour",
            (count, minute) -> "Every " + count + " hours, at " + minute + " past",
            cron -> "On the cron schedule “" + cron + "”",
            "Every hour",
            "Every day",
            "Weekdays",
            "Some days",
            "Custom",
            "Write what the genie should do.",
            (most, has) -> "A task can be at most " + String.format("%,d", most) + " characters; this one has " + String.format("%,d", has) + ".",
            "That time has passed. Pick a later one.",
            "The minute past the hour is a number from 0 to 59.",
            "Pick at least one day.",
            "Write a cron expression, such as 0 9 * * 1-5.",
            "The last day has passed.",
            "Write the time as hours and minutes, such as 09:00.",
            (day, time, fromNow) -> "Once, " + day + " at " + time + " — " + fromNow + ".",
            (day, time) -> "first " + day + " at " + time,
            "but not before its last day",
            "oillamp reads the expression when you save the job.",
            (day, time) -> "Once, " + day + " at " + time,
            "Once",
            "A job that has run",
            "A job",
            time -> "working on it, since " + time,
            "Now",
            "its time came; it runs as soon as the genie is awake and free",
            (times, first, last) -> times + " times, " + first + " to " + last,
            "Done",
            "Failed",
            "Ran out of time",
            "Stopped"
        );
    }

    /// The words of a genie's History page: the moments its home was saved at, its branches, and
    /// going back to a moment.
    public record HistoryPage(
        About IS_WAKING_GO_BACK_ONCE_IT_IS_AWAKE,
        About IS_WORKING_GO_BACK_ONCE_IT_IS_DONE,
        String A_JOB_IS_RUNNING_GO_BACK_ONCE_IT_IS_DONE,
        About SEND_IT_TO_SLEEP_FIRST_ITS_HOME_CHANGES_ONLY_WHILE_IT_SLEEPS,
        About IS_SAVED_ONLY_WHEN_YOU_SAVE_IT_BY_HAND,
        WhoAndWhat SAVED_WHENEVER,
        String GOING_BACK_KEEPS_HOW_IT_IS_NOW_SO_YOU_CAN_ALWAYS_RETURN,
        About IS_WORKING_YOU_CAN_GO_BACK_ONCE_IT_IS_DONE,
        About IS_WAKING_YOU_CAN_GO_BACK_ONCE_IT_IS_AWAKE,
        String HISTORY,
        String NEW_BRANCH_ELLIPSIS,
        About KEEP_HOW_IT_WAS_LAST_SAVED_AS_A_BRANCH_OF_ITS_OWN,
        String SAVE_NOW_ELLIPSIS,
        String KEEP_HOW_THE_GENIE_IS_NOW_AS_A_MOMENT_TO_COME_BACK_TO,
        String HOW_IT_WAS_BEFORE_IS_KEPT_SO_YOU_CAN_RETURN_TO_IT,
        String UNDO,
        String GO_BACK_TO_HOW_IT_WAS_BEFORE,
        Listed NAMES_LISTED,
        String THEN,
        About WHAT_CHANGED_SINCE_THE_NEWEST_MOMENT_WAS_NEVER_SAVED_AND_IS_LOST,
        OnBranch RESET_TO_HOW_IT_WAS,
        CountedOnBranch MOMENTS_AFTER_IT_LEAVE,
        String A_RESET_KEEPS_NO_COPY_SO_IT_CANNOT_BE_UNDONE,
        About RESET,
        About DELETE_THE_BRANCH,
        CountedOnBranch MOMENTS_ON_IT_ALONE_GO_WITH_IT,
        About DELETE,
        Counted DELETE_THE_OLDEST_MOMENTS,
        OldestMoments THEY_DO_NOT_FIT_WITHIN_HOW_FAR_THE_HISTORY_MAY_GROW,
        Counted DELETE_THEM,
        String CANCEL,
        String GO_BACK_INSTEAD,
        String BRING_IT_BACK_TO_THAT_MOMENT_AND_KEEP_HOW_IT_IS_NOW,
        String SHOW_ONLY_THE_NEWEST_MOMENTS,
        Counted SHOW_EARLIER_MOMENTS,
        String MOMENTS,
        String READING_THE_HISTORY,
        String NOTHING_SAVED_YET,
        About ITS_HOME_IS_SAVED_THE_FIRST_TIME_IT_WAKES,
        WhoAndWhat HOW_IT_IS_NOW_ON_BRANCH,
        String NOW,
        About LIVES_HERE,
        Counted AND_MORE,
        String CLICK_A_LINE_TO_SHOW_IT_HERE,
        Listed STAND_HERE,
        String CLICK_AGAIN_TO_CLOSE,
        String CLICK_TO_GO_BACK_TO_THIS_MOMENT_START_A_BRANCH_FROM_IT_OR_OPEN_ITS_CONVERSATION,
        String GO_BACK_TO_THIS_MOMENT_ELLIPSIS,
        String BRING_ITS_FILES_AND_CONVERSATIONS_BACK_TO_HOW_THEY_WERE_THEN,
        String START_A_BRANCH_HERE_ELLIPSIS,
        About KEEP_THIS_MOMENT_AS_A_BRANCH_OF_ITS_OWN,
        String RESET_TO_HERE_ELLIPSIS,
        String TAKE_EVERY_MOMENT_AFTER_THIS_ONE_OFF_THE_BRANCH_FOR_GOOD,
        String OPEN_THE_CONVERSATION,
        WhoAndWhat LIVES_ON_IT_AND_EVERY_SAVE_GOES_THERE,
        Looking YOU_ARE_LOOKING_AT,
        WhoAndWhat MOVE_TO_ELLIPSIS,
        String MAKE_THIS_BRANCH_THE_GENIES_HOME,
        About DELETE_ELLIPSIS,
        String DELETE_THIS_BRANCH_YOU_ARE_TOLD_FIRST_HOW_MANY_MOMENTS_ONLY_IT_HOLDS,
        About LIVES_ON_THIS_BRANCH,
        String ITS_LINE_IS_THE_ONE_WITH_WORDS,
        String CLICK_TO_LOOK_AT_ITS_LINE,
        About WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP,
        String READING_ITS_SETTINGS,
        About SEND_IT_TO_SLEEP_TO_CHANGE_THESE,
        String WHEN_IT_SAVES_BY_ITSELF,
        String AS_IT_WAKES,
        String AFTER_IT_WENT_TO_SLEEP,
        String AS_IT_BEGINS_ON_A_QUESTION_OR_A_JOB,
        String AS_IT_ANSWERS_OR_ENDS_A_JOB,
        String A_JOB_IS_TOLD_WHAT_ITS_LAST_RUNS_SAID_AND_CHANGED,
        String HOW_LARGE_IT_MAY_GROW,
        String WHAT_A_MOMENT_HOLDS,
        About ITS_HOME_THE_FILES_IT_MADE_ITS_NOTES_AND_ITS_CONVERSATIONS,
        String ITS_SCHEDULE_STAYS_AS_IT_IS,
        About A_BRANCH_IS_A_LINE_OF_MOMENTS_WITH_A_HOME_OF_ITS_OWN,
        String SAVE_BY_HAND_BEFORE_SOMETHING_YOU_MIGHT_WANT_TO_UNDO,
        About NOTHING_IS_EVER_DELETED_ITS_HISTORY_MAY_GROW_WITHOUT_END,
        WhoAndWhat ITS_HISTORY_MAY_GROW_BEYOND_ITS_FIRST_MOMENT,
        Pruned THE_LAST_TIME_OILLAMP_DELETED_OLD_MOMENTS,
        String TIDY_UP_NOW_ELLIPSIS,
        String DELETE_WHAT_DOES_NOT_FIT_NOW_RATHER_THAN_AT_THE_NEXT_SAVE,
        String NEVER_DELETE,
        String WHAT_SHOULD_THIS_MOMENT_BE_REMEMBERED_BY,
        About SAVE_AS_IT_IS_NOW,
        WhoAndWhat WHAT_SHOULD_THE_NEW_BRANCH_BE_CALLED,
        String START_A_BRANCH_FROM_THE_NEWEST_MOMENT,
        String START_A_BRANCH_FROM_THIS_MOMENT,
        WhoAndWhat BRING_IT_BACK_TO_HOW_IT_WAS,
        About SLEEPS_WHILE_IT_GOES_BACK_AND_WAKES_AGAIN_AFTER,
        String GO_BACK_IN_TIME,
        String GO_BACK,
        String STAY,
        Moving MOVE_IT_TO_ANOTHER_BRANCH,
        String MOVE_TO_ANOTHER_BRANCH,
        String MOVE,
        String WOKE_UP,
        String WENT_TO_SLEEP,
        String SAVED_BY_HAND,
        String BEFORE_IT_WENT_TO_WORK,
        About DID_THE_JOB,
        String DID_A_SCHEDULED_JOB,
        About ANSWERED,
        String ANSWERED_A_QUESTION,
        About TALKED,
        String TALKED_WITH_YOU,
        String BEFORE_GOING_BACK,
        About UNDID_GOING_BACK_TO_HOW_IT_WAS,
        About WENT_BACK_TO_HOW_IT_WAS,
        String WENT_BACK_TO_AN_EARLIER_MOMENT,
        String BEFORE_IT_MOVED_TO_ANOTHER_BRANCH,
        String SAVED_AS_ITS_SANDBOX_STARTED,
        String SAVED_AFTER_ITS_SANDBOX_STOPPED,
        String SAVED_AS_IT_BEGAN_ON_A_QUESTION_OR_A_JOB,
        String SAVED_ONCE_THE_CHAT_HAD_GONE_QUIET,
        About STOPPED_AND_SAID,
        About FAILED_AND_SAID,
        About RAN_OUT_OF_TIME_AND_SAID,
        String KEPT_SO_THAT_GOING_BACK_CAN_BE_UNDONE,
        String KEPT_AS_IT_WAS_WHEN_IT_MOVED_TO_ANOTHER_BRANCH,
        String WAKES,
        String ANSWERS,
        String BEGINS_TO_ANSWER,
        String SLEEPS,
        Listed ONE_OR_ANOTHER,
        String A_BRANCH_NEEDS_A_NAME,
        Counted A_BRANCHS_NAME_IS_AT_MOST_SO_MANY_CHARACTERS_LONG,
        String A_BRANCHS_NAME_IS_MADE_OF_LOWERCASE_LETTERS_DIGITS_AND_SIGNS,
        String A_BRANCHS_NAME_CANNOT_HOLD_TWO_DOTS_OR_END_IN_A_DOT_OR_LOCK,
        About THERE_IS_A_BRANCH_CALLED_IT_ALREADY
    ) {
        public static final HistoryPage ENGLISH = new HistoryPage(
            genie -> genie + " is waking; go back once it is awake",
            genie -> genie + " is working; go back once it is done",
            "A job is running; go back once it is done",
            genie -> "Send " + genie + " to sleep first: its home changes only while it sleeps.",
            genie -> genie + " is saved only when you save it by hand.",
            (genie, when) -> "Saved whenever " + genie + " " + when + ".",
            "Going back keeps how it is now, so you can always return.",
            genie -> genie + " is working. You can go back once it is done.",
            genie -> genie + " is waking. You can go back once it is awake.",
            "History",
            "New branch…",
            genie ->
                "Keep how " + genie + " was last saved as a branch of "
                + "its own, to try something else from there. " + genie + " stays where it lives",
            "Save now…",
            "Keep how the genie is now as a moment to come back to, such as before a big change",
            "How it was before is kept, so you can return to it.",
            "Undo",
            "Go back to how it was before",
            Lang::listedInEnglish,
            "then",
            genie -> "What changed in " + genie + "'s home since the newest moment was never saved, and is lost.",
            (branch, when) -> "Reset " + branch + " to how it was " + when + "?",
            (count, branch) ->
                count == 0 ? "No moment comes after it on " + branch + "."
                : count == 1 ? "The moment after it leaves " + branch + "."
                : "The " + count + " moments after it leave " + branch + ".",
            "A reset keeps no copy, so it cannot be undone; moments also on another branch stay there. "
            + "Going back instead keeps how it is now as a moment of its own.",
            branch -> "Reset " + branch,
            branch -> "Delete the branch " + branch + "?",
            (count, branch) ->
                count == 0 ? "Every moment on it is on another branch too, so no moment is lost."
                : count == 1 ? "One moment is on " + branch + " alone, and goes with it."
                : count + " moments are on " + branch + " alone, and go with it.",
            branch -> "Delete " + branch,
            count -> "Delete the " + (count == 1 ? "oldest moment" : count + " oldest moments") + "?",
            (count, branches, frees) ->
                (count == 1 ? "It does" : "They do") + " not fit within how far the history may grow"
                + (branches.isEmpty() ? "" : ", on " + branches) + ". Deleting "
                + (count == 1 ? "it" : "them") + " frees " + frees
                + ". Each branch keeps its newest moment, and the next save would delete them anyway.",
            count -> "Delete " + (count == 1 ? "it" : "them"),
            "Cancel",
            "Go back instead",
            "Bring it back to that moment, and keep how it is now as a moment you can return to",
            "Show only the newest moments",
            count -> "Show " + count + " earlier " + (count == 1 ? "moment" : "moments"),
            "MOMENTS",
            "Reading the history…",
            "Nothing saved yet",
            genie ->
                genie + "'s home is saved the first time it wakes, and from then on "
                + "whenever something changed. Each save is a moment you can bring it back to.",
            (genie, branch) -> "How " + genie + " is now" + (branch.isEmpty() ? "" : ", on " + branch),
            "now",
            genie -> "(" + genie + " lives here)",
            count -> "and " + count + " more",
            "Click a line to show it here",
            branches -> listedInEnglish(branches) + (branches.size() == 1 ? " stands here" : " stand here"),
            "Click again to close",
            "Click to go back to this moment, start a branch from it, or open its conversation",
            "Go back to this moment…",
            "Bring its files and conversations back to how they were then",
            "Start a branch here…",
            genie -> "Keep this moment as a branch of its own, to try something else from here. " + genie + " stays where it lives",
            "Reset to here…",
            "Take every moment after this one off the branch, for good. You are asked first",
            "Open the conversation",
            (genie, branch) -> genie + " lives on " + branch + ", and every save goes there. Click another branch, or its line, to look at it.",
            (shown, genie, home) -> "You are looking at " + shown + ". " + genie + " lives on " + home + ", and stays there until you move it.",
            (genie, branch) -> "Move " + genie + " to " + branch + "…",
            "Make this branch the genie's home: its files and conversations become this branch's newest moment",
            branch -> "Delete " + branch + "…",
            "Delete this branch. You are told first how many moments only it holds",
            genie -> genie + " lives on this branch.",
            "Its line is the one with words",
            "Click to look at its line",
            genie -> "Wake " + genie + " once first. Its settings are kept in its lamp, which it gets as it first wakes.",
            "Reading its settings…",
            genie -> "Send " + genie + " to sleep to change these. While it is awake, it keeps what it woke with.",
            "WHEN IT SAVES BY ITSELF",
            "As it wakes",
            "After it went to sleep",
            "As it begins on a question or a job",
            "As it answers, or ends a job",
            "A job is told what its last runs said and changed from "
            + "what was saved as they ended. Saving by hand works whatever these say.",
            "HOW LARGE IT MAY GROW",
            "WHAT A MOMENT HOLDS",
            genie ->
                genie + "'s home: the files it made, its notes and its conversations, "
                + "and the settings of its lamp. Going back brings all of these back together.",
            "Its schedule stays as it is, and so does everything on this computer outside the genie's lamp.",
            genie ->
                "A branch is a line of moments with a home of its own, schedule and settings "
                + "included, to try something without losing the rest. " + genie + " lives on one at a time.",
            "Save by hand before something you might want to undo, such as a big change to a project.",
            genie -> "Nothing is ever deleted: " + genie + "'s history may grow without end.",
            (genie, size) ->
                genie + "'s history may grow " + size + " beyond its first moment, on all branches "
                + "together. Past that, each save deletes the moments saved longest ago; each branch keeps its newest.",
            (count, freed, taken, most) ->
                "The last time, oillamp deleted " + (count == 1 ? "one old moment" : count + " old moments")
                + " to stay within it, which freed " + freed + ". The history now takes "
                + taken + " of the " + most + " it may, its first moment included.",
            "Tidy up now…",
            "Delete what does not fit now, rather than at the next save. You are told first what that is",
            "Never delete",
            "What should this moment be remembered by? You may leave it empty.",
            genie -> "Save " + genie + " as it is now",
            (genie, home) ->
                "What should the new branch be called? Lowercase letters, digits, -, _ and ., such as rust-rewrite.\n"
                + genie + " stays on " + home + "; you can move it later, while it sleeps.",
            "Start a branch from the newest moment",
            "Start a branch from this moment",
            (genie, when) ->
                "Bring " + genie + " back to how it was " + when + "?\n"
                + "Its files and conversations become what they were then. How it is now is kept, so you can return.",
            genie -> genie + " sleeps while it goes back, and wakes again after.",
            "Go back in time",
            "Go back",
            "Stay",
            (genie, to, from) ->
                "Move " + genie + " to " + to + "?\n"
                + "Its files and conversations become those of the newest moment on " + to
                + ", and every save goes to " + to + " from now on.\n"
                + "What changed since the newest moment on " + from + " is saved there first, so nothing is lost.",
            "Move to another branch",
            "Move",
            "Woke up",
            "Went to sleep",
            "Saved by hand",
            "Before it went to work",
            job -> "Did the job: " + job,
            "Did a scheduled job",
            conversation -> "Answered: " + conversation,
            "Answered a question",
            conversation -> "Talked: " + conversation,
            "Talked with you",
            "Before going back",
            when -> "Undid going back to how it was " + when,
            when -> "Went back to how it was " + when,
            "Went back to an earlier moment",
            "Before it moved to another branch",
            "Saved as its sandbox started",
            "Saved after its sandbox stopped",
            "Saved as it began on a question or a job",
            "Saved once the chat had gone quiet",
            said -> "Stopped. " + said,
            said -> "Failed. " + said,
            said -> "Ran out of time. " + said,
            "Kept so that going back can be undone",
            "Kept as it was when it moved to another branch",
            "wakes",
            "answers",
            "begins to answer",
            "sleeps",
            Lang::eitherInEnglish,
            "A branch needs a name, such as rust-rewrite.",
            most -> "A branch's name is at most " + most + " characters long.",
            "A branch's name is made of lowercase letters, digits, -, _ and ., and begins with a letter or a digit, such as rust-rewrite.",
            "A branch's name cannot hold .., or end in . or .lock.",
            branch -> "There is a branch called " + branch + " already."
        );
    }

    /// Days and spans of time, as the pages say them; [DateWordingUtil] says which of them fits.
    public record Dates(
        String TODAY,
        String TOMORROW,
        String YESTERDAY,
        OfDate DAY,
        String TODAY_IN_A_SENTENCE,
        String TOMORROW_IN_A_SENTENCE,
        String YESTERDAY_IN_A_SENTENCE,
        About ON_DAY,
        OfDate SHORT_DAY,
        String NOW,
        Counted IN_MINUTES,
        Counted IN_HOURS,
        Counted IN_DAYS,
        String JUST_NOW,
        Counted MINUTES_AGO,
        Counted HOURS_AGO,
        Counted DAYS_AGO,
        DayAndTime AT
    ) {
        public static final Dates ENGLISH = new Dates(
            "Today",
            "Tomorrow",
            "Yesterday",
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)::format,
            "today",
            "tomorrow",
            "yesterday",
            date -> "on " + date,
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)::format,
            "now",
            count -> "in " + count + (count == 1 ? " minute" : " minutes"),
            count -> "in " + count + (count == 1 ? " hour" : " hours"),
            count -> "in " + count + (count == 1 ? " day" : " days"),
            "just now",
            count -> count + (count == 1 ? " minute ago" : " minutes ago"),
            count -> count + (count == 1 ? " hour ago" : " hours ago"),
            count -> count + (count == 1 ? " day ago" : " days ago"),
            (date, time) -> date + " at " + time
        );
    }

    /// The words of the parts of the window that several pages share.
    public record Parts(
        String NO,
        String YES
    ) {
        public static final Parts ENGLISH = new Parts(
            "No",
            "Yes"
        );
    }
}
