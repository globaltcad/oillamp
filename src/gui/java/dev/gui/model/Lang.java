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
/// The texts are in a record for each part of the window, as whoever words a part finds its
/// texts together. A part knows the language it speaks, and each of its texts is a method named
/// after what it says in English, such as `YOUR_GENIES()`, which says it in that language. A text
/// assembled from something else, such as a count, takes that as its parameters, as languages
/// differ in how they assemble one.
///
/// A language is added as a constant here. Every text then switches over it without saying it in
/// that language yet, and the compiler lists each of them, until all are.
public enum Lang {
    ENGLISH;

    /// The words of the window around the genies: the list of them, and what went wrong.
    public Window WINDOW() {
        return new Window(this);
    }

    /// The words of the header above a genie's pages, and what its lamp asks the user to allow.
    public Header HEADER() {
        return new Header(this);
    }

    /// The words of a genie's desktop, beside its chat.
    public Desktop DESKTOP() {
        return new Desktop(this);
    }

    /// The words of a genie's chat: the conversation, and the composer under it.
    public Chat CHAT() {
        return new Chat(this);
    }

    /// The words of the dialogs the window asks and tells the user things in.
    public Dialogs DIALOGS() {
        return new Dialogs(this);
    }

    /// The words of what can be done with a genie as a whole.
    public ActionsPage ACTIONS_PAGE() {
        return new ActionsPage(this);
    }

    /// The words of a genie's home, and linking its folders into the user's.
    public HomeFolders HOME_FOLDERS() {
        return new HomeFolders(this);
    }

    /// The words of whether a genie may reach this computer, and its rules.
    public HostAccessPage HOST_ACCESS_PAGE() {
        return new HostAccessPage(this);
    }

    /// The words of the settings every genie shares, and the welcome above them.
    public SettingsPage SETTINGS_PAGE() {
        return new SettingsPage(this);
    }

    /// The words of when jobs wake a genie, and what they did.
    public SchedulePage SCHEDULE_PAGE() {
        return new SchedulePage(this);
    }

    /// The words of the moments a genie's home was saved at, and going back to one.
    public HistoryPage HISTORY_PAGE() {
        return new HistoryPage(this);
    }

    /// The words of the parts of the window that several pages share.
    public Parts PARTS() {
        return new Parts(this);
    }

    /// The words of days and spans of time, as the pages say them.
    public Dates DATES() {
        return new Dates(this);
    }

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

    /// The words of the window around the genies: the list of them, and what went wrong.
    public record Window(Lang lang) {
        public String NEW_GENIE() {
            return switch (lang) {
                case ENGLISH -> "New genie";
            };
        }

        public String A_NEW_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH -> "A new genie, with a sandboxed desktop of its own";
            };
        }

        public String SETTINGS() {
            return switch (lang) {
                case ENGLISH -> "Settings";
            };
        }

        public String SETTINGS_THE_MODEL_EVERY_GENIE_USES() {
            return switch (lang) {
                case ENGLISH -> "Settings: the model every genie uses. Pressed again, back to the genie";
            };
        }

        public String YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "YOUR GENIES";
            };
        }

        public String DRAG_TO_SEE_MORE_OF_YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Drag to see more of your genies, or up to fold them away";
            };
        }

        public String ADD_A_LAMP_FROM_ELSEWHERE() {
            return switch (lang) {
                case ENGLISH -> "Add a lamp from elsewhere…";
            };
        }

        public String SHOW_A_LAMP_YOU_MADE_WITH_OILLAMP_AT_HERE_TOO() {
            return switch (lang) {
                case ENGLISH -> "Show a lamp you made with `oillamp at` here too. Genies finds those that are lit by itself";
            };
        }

        public String THINGS_WENT_WRONG(int count) {
            return switch (lang) {
                case ENGLISH -> count == 1 ? "Something went wrong" : count + " things went wrong";
            };
        }

        public String GENIES_CARRIED_ON_CLICK_TO_SEE_WHAT_HAPPENED() {
            return switch (lang) {
                case ENGLISH -> "Genies carried on. Click to see what happened";
            };
        }

        public String LAMPS_FROM_ELSEWHERE() {
            return switch (lang) {
                case ENGLISH -> "LAMPS FROM ELSEWHERE";
            };
        }

        public String LAMPS_SOMEONE_LIT_WITH_OILLAMP_AT_OUTSIDE_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Lamps someone lit with `oillamp at`, outside Genies. Genies leaves them as they are";
            };
        }

        public String GENIE_STATUS_AND_WHAT_IT_SHOWS_RIGHT_CLICK_FOR_MORE(String name, String status, String showing) {
            return switch (lang) {
                case ENGLISH -> name + " — " + status + (showing.isEmpty() ? "" : ". Shows you: " + showing) + ". Right-click for more.";
            };
        }

        public String ASKS_YOU_TO_ALLOW_SOMETHING() {
            return switch (lang) {
                case ENGLISH -> "asks you to allow something";
            };
        }

        public String MAY_REACH_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "may reach this computer";
            };
        }

        public String SHOWS_YOU(String showing) {
            return switch (lang) {
                case ENGLISH -> "shows you: " + showing;
            };
        }

        public String SHOW_THIS_GENIES_CONVERSATIONS() {
            return switch (lang) {
                case ENGLISH -> "Show this genie's conversations";
            };
        }

        public String FOLD_THIS_GENIES_CONVERSATIONS_AWAY() {
            return switch (lang) {
                case ENGLISH -> "Fold this genie's conversations away";
            };
        }

        public String SCHEDULED_RUNS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "" : count == 1 ? "1 scheduled run" : count + " scheduled runs";
            };
        }

        public String SHOW_OR_HIDE_THE_CONVERSATIONS_OF_SCHEDULED_JOBS() {
            return switch (lang) {
                case ENGLISH -> "Show or hide the conversations the runs of this genie's scheduled jobs had";
            };
        }

        public String CONVERSATIONS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "no conversations yet" : count == 1 ? "1 conversation" : count + " conversations";
            };
        }

        public String SHOW_OR_HIDE_YOUR_CONVERSATIONS_WITH_THIS_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Show or hide your conversations with this genie";
            };
        }

        public String START_A_NEW_CONVERSATION_WITH_THIS_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Start a new conversation with this genie; the others are kept";
            };
        }

        public String DELETE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Delete…";
            };
        }

        public String DELETE_THE_CONVERSATION_THIS_GENIE_IS_IN() {
            return switch (lang) {
                case ENGLISH -> "Delete the conversation this genie is in, with all its branches";
            };
        }

        public String ITS_FIRST_QUESTION_WAS_ASKED_DIFFERENTLY() {
            return switch (lang) {
                case ENGLISH -> "its first question was asked differently";
            };
        }

        public String QUESTIONS_THEN_ASKED_DIFFERENTLY(int questions, int differently) {
            return switch (lang) {
                case ENGLISH ->
                    (questions == 1 ? "one question" : questions + " questions")
                    + (differently == 0 ? "" : ", then asked differently " + (differently == 1 ? "once" : differently + " times"));
            };
        }

        public String DRAG_TO_MAKE_THIS_LIST_TALLER_OR_SHORTER() {
            return switch (lang) {
                case ENGLISH -> "Drag to make this list taller or shorter";
            };
        }

        public String HIDE_YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Hide your genies";
            };
        }

        public String SHOW_YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Show your genies";
            };
        }

        public String RENAME_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Rename…";
            };
        }

        public String NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
            };
        }

        public String SAVE_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save now…";
            };
        }

        public String LINK_ITS_WORKSPACE_INTO(String folder) {
            return switch (lang) {
                case ENGLISH -> "Link its workspace into " + folder;
            };
        }

        public String SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Sleep";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
            };
        }

        public String TRY_WAKING_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Try waking again";
            };
        }

        public String REMOVE_FROM_GENIES_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Remove from Genies…";
            };
        }

        public String DELETE_GENIE_ELLIPSIS(String name) {
            return switch (lang) {
                case ENGLISH -> "Delete " + name + "…";
            };
        }

        public String DOING_A_SCHEDULED_JOB() {
            return switch (lang) {
                case ENGLISH -> "doing a scheduled job";
            };
        }

        public String A_CONVERSATION_IN_WHICH_NOTHING_WAS_ASKED_YET() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
            };
        }
    }

    /// The words of the header above a genie's pages, and of what its lamp asks the user to allow
    /// under it.
    public record Header(Lang lang) {
        public String SOMETHING_WENT_WRONG_IN_GENIES_NOT_ONLY_IN_THIS_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Something went wrong in Genies, not only in this genie. Click to see what happened";
            };
        }

        public String DOUBLE_CLICK_TO_RENAME() {
            return switch (lang) {
                case ENGLISH -> "Double-click to rename";
            };
        }

        public String TOKENS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "" : String.format("%,d tokens", count);
            };
        }

        public String TOKENS_THE_MODEL_COUNTED_FOR_THIS_GENIE_SINCE_GENIES_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Tokens the model counted for this genie since Genies started";
            };
        }

        public String DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Desktop";
            };
        }

        public String DESKTOP_WATCH_THE_GENIES_DESKTOP_AND_USE_IT() {
            return switch (lang) {
                case ENGLISH -> "Desktop: watch the genie's desktop, and use it";
            };
        }

        public String STOP() {
            return switch (lang) {
                case ENGLISH -> "Stop";
            };
        }

        public String STOP_WHAT_THE_GENIE_IS_DOING() {
            return switch (lang) {
                case ENGLISH -> "Stop what the genie is doing";
            };
        }

        public String SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Sleep";
            };
        }

        public String SLEEP_END_THE_GENIES_SANDBOX() {
            return switch (lang) {
                case ENGLISH -> "Sleep: end the genie's sandbox. Its home and this conversation are kept.";
            };
        }

        public String ASKS_YOU_TO_ALLOW(String who, String what) {
            return switch (lang) {
                case ENGLISH -> who + " asks you to allow: " + what;
            };
        }

        public String A_MODEL_ALLOWED_IT_ALREADY_THIS_ONE_NEEDS_YOU_TOO() {
            return switch (lang) {
                case ENGLISH ->
                    "A model allowed it already; this one needs you too. "
                    + "Nothing happens until you decide, and after a minute it is refused.";
            };
        }

        public String REFUSE() {
            return switch (lang) {
                case ENGLISH -> "Refuse";
            };
        }

        public String ALLOW() {
            return switch (lang) {
                case ENGLISH -> "Allow";
            };
        }

        public String MANAGE_THE_GENIES_ACTIONS_SCHEDULE_HISTORY_AND_HOST_ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Manage: the genie's actions, schedule, history and host access";
            };
        }

        public String CHAT() {
            return switch (lang) {
                case ENGLISH -> "Chat";
            };
        }

        public String ACTIONS() {
            return switch (lang) {
                case ENGLISH -> "Actions";
            };
        }

        public String SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Schedule";
            };
        }

        public String HISTORY() {
            return switch (lang) {
                case ENGLISH -> "History";
            };
        }

        public String ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Access";
            };
        }

        public String CHAT_TALK_WITH_THE_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Chat: talk with the genie";
            };
        }

        public String ACTIONS_RENAME_SAVE_WAKE_OR_SLEEP_LINK_ITS_FOLDERS_OR_DELETE_THE_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Actions: rename, save, wake or sleep, link its folders, or delete the genie";
            };
        }

        public String SCHEDULE_WHEN_JOBS_WAKE_THE_GENIE_AND_WHAT_THEY_DID() {
            return switch (lang) {
                case ENGLISH -> "Schedule: when jobs wake the genie, and what they did";
            };
        }

        public String HISTORY_THE_MOMENTS_THE_GENIES_HOME_WAS_SAVED_AT() {
            return switch (lang) {
                case ENGLISH -> "History: the moments the genie's home was saved at, and going back to one";
            };
        }

        public String HOST_ACCESS_WHETHER_THE_GENIE_MAY_REACH_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "Host access: whether the genie may reach this computer, and its rules";
            };
        }
    }

    /// The words of a genie's desktop, beside its chat, and of how it is shown.
    public record Desktop(Lang lang) {
        public String DRAG_TO_SHARE_THE_ROOM_BETWEEN_THE_CHAT_AND_THE_DESKTOP() {
            return switch (lang) {
                case ENGLISH ->
                    "Drag to share the room between the chat and the desktop, or all the way to close the desktop."
                    + " Double-click to let Genies share it";
            };
        }

        public String FITTED() {
            return switch (lang) {
                case ENGLISH -> "fitted";
            };
        }

        public String RECORDED_SO_IT_KEEPS_ITS_OWN_SIZE() {
            return switch (lang) {
                case ENGLISH -> "Recorded, so it keeps its own size";
            };
        }

        public String CLICK_THE_DESKTOP_TO_USE_IT() {
            return switch (lang) {
                case ENGLISH -> "Click the desktop to use it";
            };
        }

        public String SHOWS_YOU(String who, String what) {
            return switch (lang) {
                case ENGLISH -> who + " shows you: " + what;
            };
        }

        public String FIT() {
            return switch (lang) {
                case ENGLISH -> "Fit";
            };
        }

        public String GIVE_THE_DESKTOP_THE_SIZE_OF_THIS_PANEL() {
            return switch (lang) {
                case ENGLISH ->
                    "Give the desktop the size of this panel, so what the genie shows fills it."
                    + " It gets its own size back when you close it.";
            };
        }

        public String FIXED() {
            return switch (lang) {
                case ENGLISH -> "Fixed";
            };
        }

        public String SHOW_THE_WHOLE_DESKTOP_AT_ITS_OWN_SIZE() {
            return switch (lang) {
                case ENGLISH -> "Show the whole desktop at its own size, shrunk into this panel";
            };
        }

        public String SMALLER_OR_CTRL_AND_THE_MOUSE_WHEEL() {
            return switch (lang) {
                case ENGLISH -> "Smaller (or Ctrl and the mouse wheel on the desktop)";
            };
        }

        public String LARGER_OR_CTRL_AND_THE_MOUSE_WHEEL() {
            return switch (lang) {
                case ENGLISH -> "Larger (or Ctrl and the mouse wheel on the desktop)";
            };
        }

        public String KEYS() {
            return switch (lang) {
                case ENGLISH -> "Keys";
            };
        }

        public String THE_KEYS_THAT_MAKE_A_WINDOW_ON_THE_DESKTOP_FULLSCREEN() {
            return switch (lang) {
                case ENGLISH -> "The keys that make a window on the desktop fullscreen, close it or minimize it";
            };
        }

        public String KEYS_FOR_THE_DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Keys for the desktop";
            };
        }

        public String CLICK_A_WINDOW_FIRST_SO_THAT_IT_HAS_THE_KEYBOARD() {
            return switch (lang) {
                case ENGLISH -> "Click a window first, so that it has the keyboard.";
            };
        }

        public String FULLSCREEN_AND_BACK() {
            return switch (lang) {
                case ENGLISH -> "Fullscreen, and back";
            };
        }

        public String CLOSE_THE_WINDOW() {
            return switch (lang) {
                case ENGLISH -> "Close the window";
            };
        }

        public String MINIMIZE_THE_WINDOW() {
            return switch (lang) {
                case ENGLISH -> "Minimize the window";
            };
        }

        public String BRING_EVERY_MINIMIZED_WINDOW_BACK() {
            return switch (lang) {
                case ENGLISH -> "Bring every minimized window back";
            };
        }

        public String SUPER_IS_THE_KEY_WITH_THE_WINDOWS_LOGO() {
            return switch (lang) {
                case ENGLISH -> "Super is the key with the Windows logo.";
            };
        }

        public String CTRL() {
            return switch (lang) {
                case ENGLISH -> "Ctrl";
            };
        }

        public String SUPER() {
            return switch (lang) {
                case ENGLISH -> "Super";
            };
        }

        public String THE_DESKTOP_SHOWS_HERE_WHILE_THE_GENIE_IS_AWAKE() {
            return switch (lang) {
                case ENGLISH -> "The desktop shows here while the genie is awake.";
            };
        }

        public String CONNECTING_TO_THE_DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Connecting to the desktop…";
            };
        }

        public String THE_DESKTOP_WENT_AWAY(String why) {
            return switch (lang) {
                case ENGLISH -> "The desktop went away: " + why;
            };
        }

        public String COULD_NOT_SHOW_THE_DESKTOP(String why) {
            return switch (lang) {
                case ENGLISH -> "Could not show the desktop: " + why;
            };
        }
    }

    /// The words of a genie's chat: the conversation, the files the genie hands out, and the
    /// composer under it.
    public record Chat(Lang lang) {
        public String IS_LISTENING(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is listening.";
            };
        }

        public String IS_WAKING(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is waking.";
            };
        }

        public String IS_IN_THE_LAMP(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is in the lamp.";
            };
        }

        public String COULD_NOT_WAKE(String name) {
            return switch (lang) {
                case ENGLISH -> name + " could not wake.";
            };
        }

        public String ASK_FOR_ANYTHING_IT_HAS_A_DESKTOP_AND_A_SHELL_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH -> "Ask for anything. It has a desktop and a shell of its own, and hands you files it makes.";
            };
        }

        public String ITS_SANDBOX_IS_STARTING_THE_VERY_FIRST_TIME_IT_IS_MADE() {
            return switch (lang) {
                case ENGLISH -> "Its sandbox is starting. The very first time, the sandbox is made, which takes several minutes.";
            };
        }

        public String WAKE_IT_TO_TALK_IT_KEEPS_ITS_OWN_DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Wake it to talk. It keeps its own desktop, safely away from yours.";
            };
        }

        public String WHAT_WENT_WRONG_IS_SAID_BELOW_TRY_AGAIN_ONCE_THAT_IS_SORTED_OUT() {
            return switch (lang) {
                case ENGLISH -> "What went wrong is said below. Try again once that is sorted out.";
            };
        }

        public String OUTBOX() {
            return switch (lang) {
                case ENGLISH -> "OUTBOX";
            };
        }

        public String SAVE_SOMEWHERE_ON_THIS_COMPUTER(String file) {
            return switch (lang) {
                case ENGLISH -> "Save " + file + " somewhere on this computer";
            };
        }

        public String IS_ANSWERING_IN_ANOTHER_CONVERSATION(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is answering in another conversation. Write here once it is done.";
            };
        }

        public String GIVE_THE_GENIE_A_FILE_IT_LANDS_IN_INBOX() {
            return switch (lang) {
                case ENGLISH -> "Give the genie a file; it lands in ~/inbox";
            };
        }

        public String RETURN_SENDS_SHIFT_AND_RETURN_STARTS_A_NEW_LINE() {
            return switch (lang) {
                case ENGLISH -> "Return sends; Shift and Return starts a new line";
            };
        }

        public String SEND() {
            return switch (lang) {
                case ENGLISH -> "Send";
            };
        }

        public String NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
            };
        }

        public String START_A_NEW_CONVERSATION_OR_SELECT_ONE_IN_THE_TREE_TO_RETURN_TO_IT() {
            return switch (lang) {
                case ENGLISH -> "Start a new conversation, or select a conversation in the tree to return to it";
            };
        }

        public String WAKING(String name, String doing) {
            return switch (lang) {
                case ENGLISH -> "Waking " + name + ": " + doing + "…";
            };
        }

        public String IS_ASLEEP_ITS_SANDBOX_IS_OFF(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is asleep. Its sandbox is off, its home and conversation are kept.";
            };
        }

        public String COULD_NOT_WAKE_BECAUSE(String name, String why) {
            return switch (lang) {
                case ENGLISH -> name + " could not wake: " + why;
            };
        }

        public String TRY_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Try again";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
            };
        }

        public String EDIT() {
            return switch (lang) {
                case ENGLISH -> "Edit";
            };
        }

        public String ASK_THIS_DIFFERENTLY_WHAT_FOLLOWED_IS_KEPT_AS_A_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Ask this differently. What followed is kept, as a branch in the genie's conversations.";
            };
        }

        public String PREVIOUS_CONVERSATION_VERSION() {
            return switch (lang) {
                case ENGLISH -> "Previous conversation version";
            };
        }

        public String NEXT_CONVERSATION_VERSION() {
            return switch (lang) {
                case ENGLISH -> "Next conversation version";
            };
        }

        public String COPY() {
            return switch (lang) {
                case ENGLISH -> "Copy";
            };
        }

        public String COPY_THE_ANSWER_AS_THE_GENIE_WROTE_IT() {
            return switch (lang) {
                case ENGLISH -> "Copy the answer, as the genie wrote it";
            };
        }

        public String THINKING() {
            return switch (lang) {
                case ENGLISH -> "thinking";
            };
        }

        public String THOUGHT() {
            return switch (lang) {
                case ENGLISH -> "thought";
            };
        }

        public String CLICK_TO_FOLD_THE_THOUGHTS_AWAY() {
            return switch (lang) {
                case ENGLISH -> "Click to fold the thoughts away";
            };
        }

        public String CLICK_TO_SEE_WHAT_THE_GENIE_THINKS() {
            return switch (lang) {
                case ENGLISH -> "Click to see what the genie thinks";
            };
        }

        public String YOUR_MESSAGE_WAITS_UNTIL_THIS_JOB_IS_DONE(String job) {
            return switch (lang) {
                case ENGLISH -> job + ". The genie does one thing at a time, so your message waits until this job is done.";
            };
        }

        public String RUNNING() {
            return switch (lang) {
                case ENGLISH -> "running…";
            };
        }

        public String FAILED() {
            return switch (lang) {
                case ENGLISH -> "failed";
            };
        }

        public String CLICK_TO_SEE_WHAT_IT_PRINTED() {
            return switch (lang) {
                case ENGLISH -> "Click to see what it printed";
            };
        }

        public String SAVE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save…";
            };
        }

        public String IS_DOING_A_SCHEDULED_JOB_FIRST(String name, String job) {
            return switch (lang) {
                case ENGLISH -> name + " is doing a scheduled job first: " + job;
            };
        }

        public String IS_THINKING(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is thinking";
            };
        }

        public String WRITTEN_BY(String model) {
            return switch (lang) {
                case ENGLISH -> "Written by " + model;
            };
        }
    }

    /// The words of the dialogs the window asks and tells the user things in.
    public record Dialogs(Lang lang) {
        public String DELETE_THE_CONVERSATION_FOR_GOOD_WITH_ALL_ITS_BRANCHES(String title) {
            return switch (lang) {
                case ENGLISH -> "Delete the conversation \"" + title + "\" for good, with all its branches?";
            };
        }

        public String DELETE_A_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Delete a conversation";
            };
        }

        public String DELETE() {
            return switch (lang) {
                case ENGLISH -> "Delete";
            };
        }

        public String KEEP() {
            return switch (lang) {
                case ENGLISH -> "Keep";
            };
        }

        public String A_NEW_NAME_FOR(String name) {
            return switch (lang) {
                case ENGLISH -> "A new name for " + name + ":";
            };
        }

        public String RENAME_A_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Rename a genie";
            };
        }

        public String WORKSPACE_IS_IN_YOUR_FOLDER_NOW_IT_IS_A_LINK_NOT_A_COPY(String genie, boolean now, String folder, String link) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s workspace " + (now ? "is" : "was") + " in your " + folder + (now ? " now" : " already")
                    + ", as \"" + link + "\".\nIt is a link, not a copy: " + genie + " sees what you change there.";
            };
        }

        public String LINKED() {
            return switch (lang) {
                case ENGLISH -> "Linked";
            };
        }

        public String COULD_NOT_LINK() {
            return switch (lang) {
                case ENGLISH -> "Could not link";
            };
        }

        public String THINGS_WENT_WRONG_THAT_GENIES_DID_NOT_EXPECT(int count) {
            return switch (lang) {
                case ENGLISH ->
                    count == 1
                    ? "Something went wrong that Genies did not expect."
                    : count + " things went wrong that Genies did not expect.";
            };
        }

        public String WHAT_WENT_WRONG() {
            return switch (lang) {
                case ENGLISH -> "What went wrong";
            };
        }

        public String GENIES_CARRIED_ON_ALL_OF_IT_IS_KEPT_IN(String log) {
            return switch (lang) {
                case ENGLISH -> "Genies carried on. All of it is kept in " + log + ", which helps whoever fixes it.";
            };
        }

        public String CLOSE() {
            return switch (lang) {
                case ENGLISH -> "Close";
            };
        }

        public String REMOVE_FROM_GENIES_ITS_LAMP_STAYS_WHERE_IT_IS(String name, String where) {
            return switch (lang) {
                case ENGLISH ->
                    "Remove " + name + " from Genies? Its lamp stays where it is, at "
                    + where + ", as it is, running if it runs.\nAdd it again with \"Add a lamp from elsewhere…\".";
            };
        }

        public String REMOVE_A_LAMP_FROM_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Remove a lamp from Genies";
            };
        }

        public String REMOVE() {
            return switch (lang) {
                case ENGLISH -> "Remove";
            };
        }

        public String DELETE_FOR_GOOD_ITS_HOME_AND_YOUR_CONVERSATIONS_WITH_IT(String name) {
            return switch (lang) {
                case ENGLISH -> "Delete " + name + " for good? Its home, everything it made and your conversations with it are deleted.";
            };
        }

        public String DELETE_A_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Delete a genie";
            };
        }

        public String ADD_A_LAMP_MADE_WITH_OILLAMP_AT() {
            return switch (lang) {
                case ENGLISH -> "Add a lamp made with `oillamp at`";
            };
        }

        public String ADD() {
            return switch (lang) {
                case ENGLISH -> "Add";
            };
        }

        public String NOT_A_LAMP() {
            return switch (lang) {
                case ENGLISH -> "Not a lamp";
            };
        }

        public String ASK_DIFFERENTLY(String name) {
            return switch (lang) {
                case ENGLISH -> "Ask " + name + " differently";
            };
        }

        public String GIVE_A_FILE(String name) {
            return switch (lang) {
                case ENGLISH -> "Give " + name + " a file";
            };
        }

        public String SAVE(String file) {
            return switch (lang) {
                case ENGLISH -> "Save " + file;
            };
        }
    }

    /// The words of a genie's Actions page: what can be done with the genie as a whole.
    public record ActionsPage(Lang lang) {
        public String ACTIONS() {
            return switch (lang) {
                case ENGLISH -> "Actions";
            };
        }

        public String WHAT_CAN_BE_DONE_WITH_IT_AS_A_WHOLE(String genie) {
            return switch (lang) {
                case ENGLISH -> "What can be done with " + genie + " as a whole. A right-click on its card in your list offers the same.";
            };
        }

        public String NAME() {
            return switch (lang) {
                case ENGLISH -> "Name";
            };
        }

        public String ENTER_KEEPS_THE_NEW_NAME_ESCAPE_THE_ONE_IT_HAD() {
            return switch (lang) {
                case ENGLISH -> "Enter keeps the new name, Escape the one it had.";
            };
        }

        public String WHAT_IT_IS_CALLED_IN_YOUR_LIST_AND_ABOVE_ITS_CHAT() {
            return switch (lang) {
                case ENGLISH -> "What it is called in your list and above its chat.";
            };
        }

        public String RENAME() {
            return switch (lang) {
                case ENGLISH -> "Rename";
            };
        }

        public String CANCEL() {
            return switch (lang) {
                case ENGLISH -> "Cancel";
            };
        }

        public String SAVE() {
            return switch (lang) {
                case ENGLISH -> "Save";
            };
        }

        public String NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
            };
        }

        public String START_AFRESH_WITH_IT_THIS_CONVERSATION_IS_KEPT(String genie) {
            return switch (lang) {
                case ENGLISH -> "Start afresh with " + genie + ", waking it first if it sleeps. This conversation is kept, to go back to.";
            };
        }

        public String START_A_NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Start a new conversation";
            };
        }

        public String SAVE_NOW() {
            return switch (lang) {
                case ENGLISH -> "Save now";
            };
        }

        public String SAVE_ITS_HOME_AS_IT_IS_NOW(String genie) {
            return switch (lang) {
                case ENGLISH -> "Save " + genie + "'s home as it is now: a moment in its history to go back to.";
            };
        }

        public String SAVE_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save now…";
            };
        }

        public String SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Sleep";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
            };
        }

        public String IS_IN_THE_LAMP_WAKING_IT_STARTS_ITS_SANDBOX(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is in the lamp. Waking it starts its sandbox, with its home as it left it.";
            };
        }

        public String IS_WAKING(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking…";
            };
        }

        public String DID_NOT_WAKE_ITS_HOME_IS_KEPT(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " did not wake. Its home is kept, so it can try again.";
            };
        }

        public String END_ITS_SANDBOX_FOR_WHOEVER_LIT_IT_TOO(String genie) {
            return switch (lang) {
                case ENGLISH -> "End " + genie + "'s sandbox, for whoever lit it with `oillamp at` too. Its home and this conversation are kept.";
            };
        }

        public String END_ITS_SANDBOX(String genie) {
            return switch (lang) {
                case ENGLISH -> "End " + genie + "'s sandbox. Its home and this conversation are kept.";
            };
        }

        public String WAKING() {
            return switch (lang) {
                case ENGLISH -> "Waking…";
            };
        }

        public String TRY_WAKING_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Try waking again";
            };
        }

        public String FOLDERS() {
            return switch (lang) {
                case ENGLISH -> "Folders";
            };
        }

        public String REMOVE() {
            return switch (lang) {
                case ENGLISH -> "Remove";
            };
        }

        public String DELETE() {
            return switch (lang) {
                case ENGLISH -> "Delete";
            };
        }

        public String REMOVE_FROM_GENIES_ITS_LAMP_WAS_MADE_ELSEWHERE(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Remove " + genie + " from Genies. Its lamp was made elsewhere, with `oillamp at`, and stays "
                    + "where it is, as it is, running if it runs. You are asked first.";
            };
        }

        public String DELETE_FOR_GOOD_YOU_ARE_ASKED_FIRST(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Delete " + genie + " for good: its home, everything it made and your "
                    + "conversations with it. You are asked first.";
            };
        }

        public String REMOVE_FROM_GENIES_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Remove from Genies…";
            };
        }

        public String DELETE_GENIE_ELLIPSIS(String genie) {
            return switch (lang) {
                case ENGLISH -> "Delete " + genie + "…";
            };
        }
    }

    /// The words of a genie's home on its Actions page, and of linking its folders into the
    /// user's.
    public record HomeFolders(Lang lang) {
        public String HAS_NO_HOME_YET(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " has no home yet. It gets one as it first wakes.";
            };
        }

        public String ITS_HOME_IS_A_FOLDER_ON_THIS_COMPUTER_LINK_ONE_OF_ITS_FOLDERS_INTO_YOURS(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s home is a folder on this computer. Link one of its folders into yours, to find what "
                    + genie + " made in your file manager. A link is no copy: a change on either side is one on both.";
            };
        }

        public String ON_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "ON THIS COMPUTER";
            };
        }

        public String OPEN_IN_YOUR_FILE_MANAGER() {
            return switch (lang) {
                case ENGLISH -> "Open in your file manager";
            };
        }

        public String THE_LINKS_NAME() {
            return switch (lang) {
                case ENGLISH -> "THE LINK'S NAME";
            };
        }

        public String LINK_INTO(String folder) {
            return switch (lang) {
                case ENGLISH -> "Link into " + folder;
            };
        }

        public String LINK_INTO_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Link into…";
            };
        }

        public String OR_DRAG_A_FOLDER_FROM_THE_TREE_INTO_YOUR_FILE_MANAGER(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Or drag a folder from the tree into your file manager. "
                    + "Deleting " + genie + " leaves its links behind, leading nowhere.";
            };
        }

        public String WHERE_TO_PUT_THE_LINK(String link) {
            return switch (lang) {
                case ENGLISH -> "Where to put the link \"" + link + "\"";
            };
        }

        public String LINK_HERE() {
            return switch (lang) {
                case ENGLISH -> "Link here";
            };
        }

        public String LINKED(String at) {
            return switch (lang) {
                case ENGLISH -> "Linked: " + at;
            };
        }

        public String IT_WAS_LINKED_ALREADY(String at) {
            return switch (lang) {
                case ENGLISH -> "It was linked already: " + at;
            };
        }
    }

    /// The words of a genie's Host access page: whether it may reach this computer, and the
    /// rules a model judges its requests by.
    public record HostAccessPage(Lang lang) {
        public String HOST_ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Host access";
            };
        }

        public String MAY_ASK_TO_REACH_THIS_COMPUTER_A_MODEL_JUDGES_EACH_REQUEST(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " may ask to reach this computer. A model judges each request against these rules before anything happens.";
            };
        }

        public String CANNOT_REACH_THIS_COMPUTER(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " cannot reach this computer.";
            };
        }

        public String READING_THE_HOST_ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Reading the host access…";
            };
        }

        public String WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP(String genie) {
            return switch (lang) {
                case ENGLISH -> "Wake " + genie + " once first. Its settings are kept in its lamp, which it gets as it first wakes.";
            };
        }

        public String SEND_IT_TO_SLEEP_TO_CHANGE_THESE(String genie) {
            return switch (lang) {
                case ENGLISH -> "Send " + genie + " to sleep to change these. While it is awake, it keeps the settings it woke with.";
            };
        }

        public String MAY_IT_REACH_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "MAY IT REACH THIS COMPUTER?";
            };
        }

        public String EXPERIMENTAL_NEVER_TURN_THIS_ON_ON_A_COMPUTER_WITH_SENSITIVE_DATA() {
            return switch (lang) {
                case ENGLISH ->
                    "Experimental. Never turn this on on a computer with sensitive data. "
                    + "The model that judges reads these rules with every request, and nothing else.";
            };
        }

        public String FOLDERS_IT_MAY_READ() {
            return switch (lang) {
                case ENGLISH -> "FOLDERS IT MAY READ";
            };
        }

        public String ONE_PER_LINE_SUCH_AS_DOCUMENTS() {
            return switch (lang) {
                case ENGLISH -> "One per line, such as ~/Documents.";
            };
        }

        public String FOLDERS_IT_MAY_READ_AND_CHANGE() {
            return switch (lang) {
                case ENGLISH -> "FOLDERS IT MAY READ AND CHANGE";
            };
        }

        public String IT_MAY_WRITE_FILES_THERE_AND_COPY_WHAT_IT_MADE_THERE() {
            return switch (lang) {
                case ENGLISH -> "It may write files there, and copy what it made there.";
            };
        }

        public String ANYTHING_ELSE_IN_YOUR_OWN_WORDS() {
            return switch (lang) {
                case ENGLISH -> "ANYTHING ELSE, IN YOUR OWN WORDS";
            };
        }

        public String SAY_WHAT_MUST_NOT_HAPPEN() {
            return switch (lang) {
                case ENGLISH -> "Say what must not happen, such as \"never open my tax returns\".";
            };
        }

        public String UNDO_CHANGES() {
            return switch (lang) {
                case ENGLISH -> "Undo changes";
            };
        }

        public String SAVE() {
            return switch (lang) {
                case ENGLISH -> "Save";
            };
        }

        public String SAVING() {
            return switch (lang) {
                case ENGLISH -> "Saving…";
            };
        }

        public String WRITE_THESE_RULES_INTO_THE_GENIES_LAMP() {
            return switch (lang) {
                case ENGLISH -> "Write these rules into the genie's lamp";
            };
        }

        public String IS_NOT_A_PATH_THE_MODEL_THAT_JUDGES_READS_IT_AS_WORDS(String folder) {
            return switch (lang) {
                case ENGLISH -> "\"" + folder + "\" is not a path. The model that judges reads it as words, so make sure they say what you mean.";
            };
        }

        public String NOTHING_IS_LISTED_SO_THE_MODEL_THAT_JUDGES_WILL_REFUSE_EVERY_REQUEST() {
            return switch (lang) {
                case ENGLISH -> "Nothing is listed, so the model that judges will refuse every request.";
            };
        }
    }

    /// The words of the settings every genie shares, and of the welcome above them while there
    /// are no genies.
    public record SettingsPage(Lang lang) {
        public String YOUR_GENIES_MODEL() {
            return switch (lang) {
                case ENGLISH -> "Your genies' model";
            };
        }

        public String SETTINGS() {
            return switch (lang) {
                case ENGLISH -> "Settings";
            };
        }

        public String WHERE_YOUR_GENIES_MODEL_RUNS_AND_HOW_THEY_REACH_IT() {
            return switch (lang) {
                case ENGLISH -> "Where your genies' model runs, and how they reach it.";
            };
        }

        public String GENIES_SETS_UP_A_MODEL_ON_THIS_COMPUTER_FOR_YOU_WITH_OLLAMA() {
            return switch (lang) {
                case ENGLISH -> "Genies sets up a model on this computer for you, with Ollama: free, and your conversations stay here.";
            };
        }

        public String ADVANCED_EDEN_AI_OR_A_MODEL_SERVER_OF_YOUR_OWN() {
            return switch (lang) {
                case ENGLISH -> "Advanced: Eden AI, or a model server of your own";
            };
        }

        public String FOR_A_MODEL_ELSEWHERE_OR_A_MODEL_SERVER_OTHER_THAN_OLLAMA() {
            return switch (lang) {
                case ENGLISH -> "For a model elsewhere, or a model server on this computer other than Ollama";
            };
        }

        public String BACK_TO_THE_SIMPLE_WAY_OLLAMA_SET_UP_BY_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Back to the simple way: Ollama, set up by Genies";
            };
        }

        public String THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "This computer";
            };
        }

        public String GENIES_IS_LOOKING_WHAT_THIS_COMPUTER_HAS() {
            return switch (lang) {
                case ENGLISH -> "Genies is looking what this computer has…";
            };
        }

        public String OLLAMA() {
            return switch (lang) {
                case ENGLISH -> "Ollama";
            };
        }

        public String MODEL() {
            return switch (lang) {
                case ENGLISH -> "Model";
            };
        }

        public String GENIES_SUGGESTS_THE_BEST_MODEL_THIS_COMPUTER_RUNS_WELL() {
            return switch (lang) {
                case ENGLISH ->
                    "Genies suggests the best model this computer runs well. Any other "
                    + "Ollama model that can use tools works too: ollama.com/search?c=tools lists them.";
            };
        }

        public String YOUR_GENIES_USE(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Your genies use " + wanted + ".";
            };
        }

        public String STOP() {
            return switch (lang) {
                case ENGLISH -> "Stop";
            };
        }

        public String STOP_SETTING_UP_A_MODELS_DOWNLOAD_GOES_ON_THE_NEXT_TIME() {
            return switch (lang) {
                case ENGLISH -> "Stop setting up. A model's download goes on from where it was the next time";
            };
        }

        public String UNTIL_THEN_YOUR_GENIES_USE_EDEN_AI() {
            return switch (lang) {
                case ENGLISH -> "Until then, your genies use Eden AI.";
            };
        }

        public String UNTIL_THEN_YOUR_GENIES_USE_THE_MODEL_SERVER_AT(String at) {
            return switch (lang) {
                case ENGLISH -> "Until then, your genies use the model server at " + at + ".";
            };
        }

        public String THE_MODEL_RUNS() {
            return switch (lang) {
                case ENGLISH -> "The model runs";
            };
        }

        public String AT_EDEN_AI() {
            return switch (lang) {
                case ENGLISH -> "at Eden AI";
            };
        }

        public String IN_THE_EU_WITH_YOUR_EDEN_AI_KEY() {
            return switch (lang) {
                case ENGLISH -> "In the EU, with your Eden AI key.";
            };
        }

        public String ON_A_SERVER_ELSEWHERE() {
            return switch (lang) {
                case ENGLISH -> "on a server elsewhere";
            };
        }

        public String A_MODEL_SERVER_OF_YOUR_OWN_ON_ANOTHER_MACHINE() {
            return switch (lang) {
                case ENGLISH -> "A model server of your own on another machine, such as Ollama behind a proxy that asks for a key.";
            };
        }

        public String ON_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "on this computer";
            };
        }

        public String A_MODEL_SERVER_SUCH_AS_OLLAMA_LM_STUDIO_OR_LLAMA_CPP() {
            return switch (lang) {
                case ENGLISH -> "A model server such as Ollama, LM Studio or llama.cpp.";
            };
        }

        public String KEY() {
            return switch (lang) {
                case ENGLISH -> "Key";
            };
        }

        public String USE(String variable) {
            return switch (lang) {
                case ENGLISH -> "Use " + variable;
            };
        }

        public String FOUND_WHERE_GENIES_WAS_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Found where Genies was started.";
            };
        }

        public String NOT_SET_WHERE_GENIES_WAS_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Not set where Genies was started.";
            };
        }

        public String USE_THIS_KEY() {
            return switch (lang) {
                case ENGLISH -> "Use this key:";
            };
        }

        public String THE_KEY_STAYS_ON_THIS_COMPUTER_GENIES_NEVER_SEE_IT() {
            return switch (lang) {
                case ENGLISH ->
                    "The key stays on this computer. Genies never see it: oillamp adds it to "
                    + "their requests as they leave the sandbox. An entered key is kept in a file only you can read.";
            };
        }

        public String ADDRESS() {
            return switch (lang) {
                case ENGLISH -> "Address";
            };
        }

        public String WHERE_THE_SERVERS_API_IS_IT_MUST_BE_HTTPS() {
            return switch (lang) {
                case ENGLISH ->
                    "Where the server's OpenAI-style API is, such as "
                    + "https://ollama.example.com/v1. It must be https://, because the key goes with every request.";
            };
        }

        public String LEAVE_IT_EMPTY_IF_THE_SERVER_ASKS_FOR_NONE() {
            return switch (lang) {
                case ENGLISH -> "Leave it empty if the server asks for none.";
            };
        }

        public String WHERE_THE_SERVERS_API_IS_ON_THIS_COMPUTER_NO_KEY_IS_NEEDED() {
            return switch (lang) {
                case ENGLISH ->
                    "Where the server's OpenAI-style API is: http://127.0.0.1:11434/v1 for Ollama, "
                    + "http://127.0.0.1:1234/v1 for LM Studio, http://127.0.0.1:8080/v1 for llama.cpp. No key is needed.";
            };
        }

        public String LOOK_UP() {
            return switch (lang) {
                case ENGLISH -> "Look up";
            };
        }

        public String ASK_THE_SERVICE_WHICH_MODELS_IT_OFFERS() {
            return switch (lang) {
                case ENGLISH -> "Ask the service which models it offers";
            };
        }

        public String CHANGES_TAKE_EFFECT_WHEN_A_GENIE_WAKES() {
            return switch (lang) {
                case ENGLISH -> "Changes take effect when a genie wakes.";
            };
        }

        public String YOUR_GENIES_CAN_REACH_THEIR_MODEL() {
            return switch (lang) {
                case ENGLISH -> "Your genies can reach their model.";
            };
        }

        public String DONE() {
            return switch (lang) {
                case ENGLISH -> "Done";
            };
        }

        public String YOUR_FIRST_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Your first genie";
            };
        }

        public String A_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN_THAT_WAKES_AT_ONCE() {
            return switch (lang) {
                case ENGLISH -> "A genie, with a sandboxed desktop of its own, that wakes at once";
            };
        }

        public String APPEARANCE() {
            return switch (lang) {
                case ENGLISH -> "Appearance";
            };
        }

        public String THE_LAMP_AT_NIGHT_OR_BY_DAY() {
            return switch (lang) {
                case ENGLISH -> "The lamp at night, or by day.";
            };
        }

        public String DARK() {
            return switch (lang) {
                case ENGLISH -> "Dark";
            };
        }

        public String LIGHT() {
            return switch (lang) {
                case ENGLISH -> "Light";
            };
        }

        public String WELCOME_TO_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Welcome to Genies";
            };
        }

        public String A_GENIE_IS_AN_AI_HELPER_WITH_A_COMPUTER_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH ->
                    "A genie is an AI helper with a computer of its own: a Linux desktop in a sandbox, "
                    + "where it can browse the web, run programs and make files, without ever touching yours.";
            };
        }

        public String GENIES_FOUND_AN_EDEN_AI_KEY_WHERE_IT_WAS_STARTED() {
            return switch (lang) {
                case ENGLISH ->
                    "Genies found an Eden AI key where it was started, so your genies can think at Eden AI right away. "
                    + "Or have Genies set up a model on this computer instead: the simple way, below.";
            };
        }

        public String TO_THINK_GENIES_NEED_A_MODEL_GENIES_CAN_SET_ONE_UP_FOR_YOU() {
            return switch (lang) {
                case ENGLISH -> "To think, genies need a model. Genies can set one up for you, right here on this computer.";
            };
        }

        public String GENIES_THAT_ARE_AWAKE_ANSWER_WITH_A_NEW_MODEL_FROM_THEIR_NEXT_MESSAGE_ON() {
            return switch (lang) {
                case ENGLISH ->
                    "Genies that are awake answer with a new model from their next message on. "
                    + "A new place, address or key reaches them when they next wake, and so does "
                    + "everything for a genie that kept running while Genies was closed.";
            };
        }

        public String A_MODELS_NAME_HAS_NO_SPACES_AND_ONLY_PLAIN_LETTERS_DIGITS_AND_SIGNS() {
            return switch (lang) {
                case ENGLISH -> "A model's name has no spaces and only plain letters, digits and signs, such as azure/gpt-6.1-sol.";
            };
        }

        public String NAME_THE_MODEL_THE_GENIES_SHOULD_USE_LOOK_UP_SHOWS_EDEN_AIS_LIST() {
            return switch (lang) {
                case ENGLISH -> "Name the model the genies should use; Look up shows Eden AI's list.";
            };
        }

        public String ENTER_A_KEY_OR_USE_THE_ONE_IN(String variable) {
            return switch (lang) {
                case ENGLISH -> "Enter a key, or use the one in " + variable + ".";
            };
        }

        public String IS_NOT_SET_WHERE_GENIES_WAS_STARTED_ENTER_A_KEY_IN_THE_SETTINGS(String variable) {
            return switch (lang) {
                case ENGLISH -> variable + " is not set where Genies was started. Enter a key in the settings, or set it and start Genies again.";
            };
        }

        public String ENTER_THE_SERVERS_ADDRESS() {
            return switch (lang) {
                case ENGLISH -> "Enter the server's address, such as https://ollama.example.com/v1.";
            };
        }

        public String NAME_THE_MODEL_AS_THE_SERVER_LISTS_IT() {
            return switch (lang) {
                case ENGLISH -> "Name the model, as the server lists it; Look up shows its list.";
            };
        }

        public String A_MODEL_SERVER_ON_THIS_COMPUTER_IS_REACHED_AT_127_0_0_1_OR_LOCALHOST() {
            return switch (lang) {
                case ENGLISH -> "A model server on this computer is reached at 127.0.0.1 or localhost, such as http://127.0.0.1:11434/v1.";
            };
        }

        public String NAME_THE_MODEL_AS_THE_MODEL_SERVER_LISTS_IT() {
            return switch (lang) {
                case ENGLISH -> "Name the model, as the model server lists it; Look up shows its list.";
            };
        }

        public String THE_ADDRESS_SHOULD_LOOK_LIKE_AN_ADDRESS_OF_A_MODEL_SERVER() {
            return switch (lang) {
                case ENGLISH -> "The address should look like https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.";
            };
        }

        public String THE_ADDRESS_SHOULD_BE_A_SCHEME_A_HOST_AND_PERHAPS_A_PORT_AND_A_PATH() {
            return switch (lang) {
                case ENGLISH -> "The address should be a scheme, a host, and perhaps a port and a path, such as https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.";
            };
        }

        public String THE_KEY_IS_SENT_TO_THE_SERVER_SO_IT_MUST_USE_HTTPS() {
            return switch (lang) {
                case ENGLISH -> "The key is sent to the server, so it must use https:// (http:// only on this computer).";
            };
        }

        public String INSTALL_OLLAMA_AND_GET(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Install Ollama and get " + wanted;
            };
        }

        public String GET(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Get " + wanted;
            };
        }

        public String USE_MODEL(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Use " + wanted;
            };
        }

        public String GENIES_IS_LOOKING_WHETHER_OLLAMA_IS_ON_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "Genies is looking whether Ollama is on this computer…";
            };
        }

        public String OLLAMA_RUNS_ON_THIS_COMPUTER(String version) {
            return switch (lang) {
                case ENGLISH -> "Ollama " + version + " runs on this computer.";
            };
        }

        public String OLLAMA_IS_INSTALLED_GENIES_STARTS_IT_WHEN_THE_GENIES_NEED_IT() {
            return switch (lang) {
                case ENGLISH -> "Ollama is installed; Genies starts it when the genies need it.";
            };
        }

        public String OLLAMA_IS_NOT_INSTALLED_YET_GENIES_INSTALLS_IT_FOR_YOU() {
            return switch (lang) {
                case ENGLISH ->
                    "Ollama is not installed yet. Genies installs it for you, for you alone: no password, "
                    + "nothing changes outside its own folder. It is a download of about 1.4 GB.";
            };
        }

        public String GENIES_COULD_NOT_READ_HOW_MUCH_MEMORY_THIS_COMPUTER_HAS() {
            return switch (lang) {
                case ENGLISH -> "Genies could not read how much memory this computer has.";
            };
        }

        public String MEMORY_AND_NO_GRAPHICS_CARD_A_MODEL_CAN_RUN_ON(String memory) {
            return switch (lang) {
                case ENGLISH -> memory + " of memory, and no graphics card a model can run on.";
            };
        }

        public String MEMORY_AND_THE_GRAPHICS_CARD(String memory, String card, String cardMemory) {
            return switch (lang) {
                case ENGLISH -> memory + " of memory, and the graphics card " + card + " with " + cardMemory + ".";
            };
        }

        public String ABOUT_SO_MUCH_TO_DOWNLOAD(String size) {
            return switch (lang) {
                case ENGLISH -> "About " + size + " to download.";
            };
        }

        public String ITS_NAME_DOES_NOT_SAY_HOW_LARGE_IT_IS() {
            return switch (lang) {
                case ENGLISH -> "Its name does not say how large it is, so Genies cannot tell how well it runs here.";
            };
        }

        public String IT_RUNS_ON_THE_GRAPHICS_CARD_SO_IT_ANSWERS_QUICKLY() {
            return switch (lang) {
                case ENGLISH -> "It runs on the graphics card, so it answers quickly.";
            };
        }

        public String IT_RUNS_ON_THE_PROCESSOR_AT_A_FAIR_PACE() {
            return switch (lang) {
                case ENGLISH -> "It runs on the processor, at a fair pace.";
            };
        }

        public String IT_RUNS_ON_THE_PROCESSOR_SO_IT_ANSWERS_SLOWLY() {
            return switch (lang) {
                case ENGLISH -> "It runs on the processor, so it answers slowly.";
            };
        }

        public String RUNNING_IT_NEEDS_MORE_MEMORY_THAN_THIS_COMPUTER_CAN_SPARE(String need) {
            return switch (lang) {
                case ENGLISH -> "Running, it needs about " + need + " of memory, more than this computer can spare: it may not load, or be very slow.";
            };
        }
    }

    /// The words of a genie's Schedule page: when jobs wake the genie, and what they did.
    public record SchedulePage(Lang lang) {
        public String PAUSED_NO_JOB_RUNS_UNTIL_YOU_RESUME_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Paused. No job runs until you resume the schedule.";
            };
        }

        public String ON_JOBS_WAKE_IT_AT_THEIR_TIMES(String genie) {
            return switch (lang) {
                case ENGLISH -> "On. Jobs wake " + genie + " at their times, one after the other.";
            };
        }

        public String IS_WAKING_JOBS_RUN_ONCE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking. Jobs run once it is awake.";
            };
        }

        public String IS_ASLEEP_JOBS_RUN_ONLY_WHILE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is asleep. Jobs run only while it is awake; one whose time came meanwhile runs as soon as it wakes.";
            };
        }

        public String SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Schedule";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
            };
        }

        public String JOBS_RUN_ONLY_WHILE_THE_GENIE_IS_AWAKE() {
            return switch (lang) {
                case ENGLISH -> "Jobs run only while the genie is awake";
            };
        }

        public String RESUME() {
            return switch (lang) {
                case ENGLISH -> "Resume";
            };
        }

        public String PAUSE() {
            return switch (lang) {
                case ENGLISH -> "Pause";
            };
        }

        public String LET_THE_JOBS_RUN_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Let the jobs run again";
            };
        }

        public String HOLD_EVERY_JOB_UNTIL_YOU_RESUME_NOTHING_IS_LOST() {
            return switch (lang) {
                case ENGLISH -> "Hold every job until you resume; nothing is lost";
            };
        }

        public String NEW_JOB() {
            return switch (lang) {
                case ENGLISH -> "New job";
            };
        }

        public String THE_WEEK() {
            return switch (lang) {
                case ENGLISH -> "THE WEEK";
            };
        }

        public String SHOW_ONLY_THE_LAST_RUNS() {
            return switch (lang) {
                case ENGLISH -> "Show only the last runs";
            };
        }

        public String SHOW_EARLIER_RUNS(int count) {
            return switch (lang) {
                case ENGLISH -> "Show " + count + " earlier " + (count == 1 ? "run" : "runs");
            };
        }

        public String READING_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Reading the schedule…";
            };
        }

        public String NOTHING_RAN_THIS_WEEK_AND_NOTHING_IS_PLANNED() {
            return switch (lang) {
                case ENGLISH -> "Nothing ran this week, and nothing is planned.";
            };
        }

        public String CLICK_AGAIN_TO_SEE_EVERY_JOB() {
            return switch (lang) {
                case ENGLISH -> "Click again to see every job";
            };
        }

        public String CLICK_TO_BRING_THIS_JOBS_TIMES_FORWARD() {
            return switch (lang) {
                case ENGLISH -> "Click to bring this job's times forward";
            };
        }

        public String ADDED_BY(String genie) {
            return switch (lang) {
                case ENGLISH -> "added by " + genie;
            };
        }

        public String OPEN_THE_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Open the conversation";
            };
        }

        public String STOP() {
            return switch (lang) {
                case ENGLISH -> "Stop";
            };
        }

        public String STOP_THIS_RUN_WHAT_THE_GENIE_DID_SO_FAR_IS_KEPT() {
            return switch (lang) {
                case ENGLISH -> "Stop this run; what the genie did so far is kept, and saved";
            };
        }

        public String JOBS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "JOBS" : "JOBS  ·  " + count;
            };
        }

        public String NOTHING_ON_THE_SCHEDULE_YET() {
            return switch (lang) {
                case ENGLISH -> "Nothing on the schedule yet";
            };
        }

        public String GIVE_IT_A_TASK_AND_A_TIME(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Give " + genie + " a task and a time, "
                    + "once or again and again: a morning check of the build, a weekly report, a reminder "
                    + "to tidy up. It works on it in a conversation of its own, and you can read it here afterwards.";
            };
        }

        public String CHANGE_SWITCH_OFF_OR_REMOVE_THIS_JOB() {
            return switch (lang) {
                case ENGLISH -> "Change, switch off or remove this job";
            };
        }

        public String UNTIL(String day) {
            return switch (lang) {
                case ENGLISH -> "until " + day;
            };
        }

        public String SWITCHED_OFF() {
            return switch (lang) {
                case ENGLISH -> "switched off";
            };
        }

        public String WILL_NOT_RUN_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "will not run again";
            };
        }

        public String DUE_NOW() {
            return switch (lang) {
                case ENGLISH -> "due now";
            };
        }

        public String TODAY() {
            return switch (lang) {
                case ENGLISH -> "today";
            };
        }

        public String TOMORROW() {
            return switch (lang) {
                case ENGLISH -> "tomorrow";
            };
        }

        public String NEXT(String day, String time) {
            return switch (lang) {
                case ENGLISH -> "next: " + day + ", " + time;
            };
        }

        public String CHANGE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Change…";
            };
        }

        public String SWITCH_OFF() {
            return switch (lang) {
                case ENGLISH -> "Switch off";
            };
        }

        public String SWITCH_ON() {
            return switch (lang) {
                case ENGLISH -> "Switch on";
            };
        }

        public String REMOVE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Remove…";
            };
        }

        public String TAKE_IT_OFF_THE_SCHEDULE_WHAT_ITS_RUNS_DID_STAYS_IN_THE_HISTORY(String job) {
            return switch (lang) {
                case ENGLISH -> "Take \"" + job + "\" off the schedule? What its runs did stays in the history.";
            };
        }

        public String REMOVE_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "Remove a job";
            };
        }

        public String REMOVE() {
            return switch (lang) {
                case ENGLISH -> "Remove";
            };
        }

        public String KEEP() {
            return switch (lang) {
                case ENGLISH -> "Keep";
            };
        }

        public String ON_CLICK_TO_SWITCH_IT_OFF_KEEPING_IT_ON_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "On: click to switch it off, keeping it on the schedule";
            };
        }

        public String OFF_CLICK_TO_SWITCH_IT_ON() {
            return switch (lang) {
                case ENGLISH -> "Off: click to switch it on";
            };
        }

        public String CHANGE_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "Change a job";
            };
        }

        public String A_NEW_JOB_FOR(String genie) {
            return switch (lang) {
                case ENGLISH -> "A new job for " + genie;
            };
        }

        public String IT_GETS_THE_TASK_AT_THE_TIME_YOU_CHOOSE_IN_A_CONVERSATION_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH ->
                    "It gets the task at the time you choose, in a conversation of its own, "
                    + "and while it is awake: a job whose time came while it slept runs as soon as it wakes.";
            };
        }

        public String CLOSE_WITHOUT_SAVING() {
            return switch (lang) {
                case ENGLISH -> "Close without saving";
            };
        }

        public String CANCEL() {
            return switch (lang) {
                case ENGLISH -> "Cancel";
            };
        }

        public String SAVING() {
            return switch (lang) {
                case ENGLISH -> "Saving…";
            };
        }

        public String SAVE_THE_JOB() {
            return switch (lang) {
                case ENGLISH -> "Save the job";
            };
        }

        public String ADD_TO_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Add to the schedule";
            };
        }

        public String CTRL_AND_RETURN_SAVE_TOO() {
            return switch (lang) {
                case ENGLISH -> "Ctrl and Return save too";
            };
        }

        public String THE_TASK() {
            return switch (lang) {
                case ENGLISH -> "THE TASK";
            };
        }

        public String BRIEF_IT_AS_YOU_WOULD_A_COLLEAGUE() {
            return switch (lang) {
                case ENGLISH ->
                    "Brief it as you would a colleague: what to do, where things are, and "
                    + "what done looks like. Each run starts a fresh conversation; between runs, the genie "
                    + "keeps notes in ~/workspace/NOTES.md.";
            };
        }

        public String WHEN() {
            return switch (lang) {
                case ENGLISH -> "WHEN";
            };
        }

        public String ONCE() {
            return switch (lang) {
                case ENGLISH -> "Once";
            };
        }

        public String AGAIN_AND_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Again and again";
            };
        }

        public String IN_AN_HOUR() {
            return switch (lang) {
                case ENGLISH -> "In an hour";
            };
        }

        public String THIS_EVENING() {
            return switch (lang) {
                case ENGLISH -> "This evening";
            };
        }

        public String TOMORROW_MORNING() {
            return switch (lang) {
                case ENGLISH -> "Tomorrow morning";
            };
        }

        public String MONDAY_MORNING() {
            return switch (lang) {
                case ENGLISH -> "Monday morning";
            };
        }

        public String AT() {
            return switch (lang) {
                case ENGLISH -> "At";
            };
        }

        public String A_NUMBER_FROM_0_TO_59() {
            return switch (lang) {
                case ENGLISH -> "A number from 0 to 59";
            };
        }

        public String MINUTES_PAST_EACH_HOUR() {
            return switch (lang) {
                case ENGLISH -> "minutes past each hour";
            };
        }

        public String FIVE_FIELDS_AS_CRON_HAS_THEM() {
            return switch (lang) {
                case ENGLISH ->
                    "Five fields, as cron has them: minute, hour, day of the month, month, "
                    + "day of the week. 0 9 1 * * is nine on the first of each month; */30 * * * * every half hour.";
            };
        }

        public String SUCH_AS_09_00_OR_9_30() {
            return switch (lang) {
                case ENGLISH -> "Such as 09:00, or 9.30";
            };
        }

        public String ENDS() {
            return switch (lang) {
                case ENGLISH -> "Ends";
            };
        }

        public String NEVER() {
            return switch (lang) {
                case ENGLISH -> "Never";
            };
        }

        public String ON_A_DAY() {
            return switch (lang) {
                case ENGLISH -> "On a day";
            };
        }

        public String THE_MONTH_BEFORE() {
            return switch (lang) {
                case ENGLISH -> "The month before";
            };
        }

        public String THE_MONTH_AFTER() {
            return switch (lang) {
                case ENGLISH -> "The month after";
            };
        }

        public String MONTH_AND_YEAR(YearMonth month) {
            return switch (lang) {
                case ENGLISH -> month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
            };
        }

        public String WEEKDAY_IN_TWO_LETTERS(DayOfWeek day) {
            return switch (lang) {
                case ENGLISH -> day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2);
            };
        }

        public String WEEKDAY(DayOfWeek day) {
            return switch (lang) {
                case ENGLISH -> day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            };
        }

        public String DAYS_LISTED(List<String> names) {
            return switch (lang) {
                case ENGLISH -> listedInEnglish(names);
            };
        }

        public String EVERY_HOUR_ON_THE_HOUR() {
            return switch (lang) {
                case ENGLISH -> "Every hour, on the hour";
            };
        }

        public String EVERY_HOUR_AT_PAST(String minute) {
            return switch (lang) {
                case ENGLISH -> "Every hour at " + minute + " past";
            };
        }

        public String EVERY_DAY_AT(String time) {
            return switch (lang) {
                case ENGLISH -> "Every day at " + time;
            };
        }

        public String EVERY_WEEKDAY_AT(String time) {
            return switch (lang) {
                case ENGLISH -> "Every weekday at " + time;
            };
        }

        public String EVERY_DAYS_AT(String days, String time) {
            return switch (lang) {
                case ENGLISH -> "Every " + days + " at " + time;
            };
        }

        public String EVERY_MINUTES(int count) {
            return switch (lang) {
                case ENGLISH -> "Every " + count + " minutes";
            };
        }

        public String EVERY_HOURS_ON_THE_HOUR(int count) {
            return switch (lang) {
                case ENGLISH -> "Every " + count + " hours, on the hour";
            };
        }

        public String EVERY_HOURS_AT_PAST(int count, String minute) {
            return switch (lang) {
                case ENGLISH -> "Every " + count + " hours, at " + minute + " past";
            };
        }

        public String ON_THE_CRON_SCHEDULE(String cron) {
            return switch (lang) {
                case ENGLISH -> "On the cron schedule “" + cron + "”";
            };
        }

        public String EVERY_HOUR() {
            return switch (lang) {
                case ENGLISH -> "Every hour";
            };
        }

        public String EVERY_DAY() {
            return switch (lang) {
                case ENGLISH -> "Every day";
            };
        }

        public String WEEKDAYS() {
            return switch (lang) {
                case ENGLISH -> "Weekdays";
            };
        }

        public String SOME_DAYS() {
            return switch (lang) {
                case ENGLISH -> "Some days";
            };
        }

        public String CUSTOM() {
            return switch (lang) {
                case ENGLISH -> "Custom";
            };
        }

        public String WRITE_WHAT_THE_GENIE_SHOULD_DO() {
            return switch (lang) {
                case ENGLISH -> "Write what the genie should do.";
            };
        }

        public String A_TASK_CAN_BE_AT_MOST(int most, int has) {
            return switch (lang) {
                case ENGLISH -> "A task can be at most " + String.format("%,d", most) + " characters; this one has " + String.format("%,d", has) + ".";
            };
        }

        public String THAT_TIME_HAS_PASSED_PICK_A_LATER_ONE() {
            return switch (lang) {
                case ENGLISH -> "That time has passed. Pick a later one.";
            };
        }

        public String THE_MINUTE_PAST_THE_HOUR_IS_A_NUMBER_FROM_0_TO_59() {
            return switch (lang) {
                case ENGLISH -> "The minute past the hour is a number from 0 to 59.";
            };
        }

        public String PICK_AT_LEAST_ONE_DAY() {
            return switch (lang) {
                case ENGLISH -> "Pick at least one day.";
            };
        }

        public String WRITE_A_CRON_EXPRESSION() {
            return switch (lang) {
                case ENGLISH -> "Write a cron expression, such as 0 9 * * 1-5.";
            };
        }

        public String THE_LAST_DAY_HAS_PASSED() {
            return switch (lang) {
                case ENGLISH -> "The last day has passed.";
            };
        }

        public String WRITE_THE_TIME_AS_HOURS_AND_MINUTES() {
            return switch (lang) {
                case ENGLISH -> "Write the time as hours and minutes, such as 09:00.";
            };
        }

        public String ONCE_AT_FROM_NOW(String day, String time, String fromNow) {
            return switch (lang) {
                case ENGLISH -> "Once, " + day + " at " + time + " — " + fromNow + ".";
            };
        }

        public String FIRST_AT(String day, String time) {
            return switch (lang) {
                case ENGLISH -> "first " + day + " at " + time;
            };
        }

        public String BUT_NOT_BEFORE_ITS_LAST_DAY() {
            return switch (lang) {
                case ENGLISH -> "but not before its last day";
            };
        }

        public String OILLAMP_READS_THE_EXPRESSION_WHEN_YOU_SAVE_THE_JOB() {
            return switch (lang) {
                case ENGLISH -> "oillamp reads the expression when you save the job.";
            };
        }

        public String ONCE_AT(String day, String time) {
            return switch (lang) {
                case ENGLISH -> "Once, " + day + " at " + time;
            };
        }

        public String ONCE_WITHOUT_A_TIME() {
            return switch (lang) {
                case ENGLISH -> "Once";
            };
        }

        public String A_JOB_THAT_HAS_RUN() {
            return switch (lang) {
                case ENGLISH -> "A job that has run";
            };
        }

        public String A_JOB() {
            return switch (lang) {
                case ENGLISH -> "A job";
            };
        }

        public String WORKING_ON_IT_SINCE(String time) {
            return switch (lang) {
                case ENGLISH -> "working on it, since " + time;
            };
        }

        public String NOW_ON_THE_TIMELINE() {
            return switch (lang) {
                case ENGLISH -> "Now";
            };
        }

        public String ITS_TIME_CAME_IT_RUNS_AS_SOON_AS_THE_GENIE_IS_AWAKE_AND_FREE() {
            return switch (lang) {
                case ENGLISH -> "its time came; it runs as soon as the genie is awake and free";
            };
        }

        public String TIMES_FROM_TO(int times, String first, String last) {
            return switch (lang) {
                case ENGLISH -> times + " times, " + first + " to " + last;
            };
        }

        public String DONE() {
            return switch (lang) {
                case ENGLISH -> "Done";
            };
        }

        public String FAILED() {
            return switch (lang) {
                case ENGLISH -> "Failed";
            };
        }

        public String RAN_OUT_OF_TIME() {
            return switch (lang) {
                case ENGLISH -> "Ran out of time";
            };
        }

        public String STOPPED() {
            return switch (lang) {
                case ENGLISH -> "Stopped";
            };
        }
    }

    /// The words of a genie's History page: the moments its home was saved at, its branches, and
    /// going back to a moment.
    public record HistoryPage(Lang lang) {
        public String IS_WAKING_GO_BACK_ONCE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking; go back once it is awake";
            };
        }

        public String IS_WORKING_GO_BACK_ONCE_IT_IS_DONE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is working; go back once it is done";
            };
        }

        public String A_JOB_IS_RUNNING_GO_BACK_ONCE_IT_IS_DONE() {
            return switch (lang) {
                case ENGLISH -> "A job is running; go back once it is done";
            };
        }

        public String SEND_IT_TO_SLEEP_FIRST_ITS_HOME_CHANGES_ONLY_WHILE_IT_SLEEPS(String genie) {
            return switch (lang) {
                case ENGLISH -> "Send " + genie + " to sleep first: its home changes only while it sleeps.";
            };
        }

        public String IS_SAVED_ONLY_WHEN_YOU_SAVE_IT_BY_HAND(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is saved only when you save it by hand.";
            };
        }

        public String SAVED_WHENEVER(String genie, String when) {
            return switch (lang) {
                case ENGLISH -> "Saved whenever " + genie + " " + when + ".";
            };
        }

        public String GOING_BACK_KEEPS_HOW_IT_IS_NOW_SO_YOU_CAN_ALWAYS_RETURN() {
            return switch (lang) {
                case ENGLISH -> "Going back keeps how it is now, so you can always return.";
            };
        }

        public String IS_WORKING_YOU_CAN_GO_BACK_ONCE_IT_IS_DONE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is working. You can go back once it is done.";
            };
        }

        public String IS_WAKING_YOU_CAN_GO_BACK_ONCE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking. You can go back once it is awake.";
            };
        }

        public String HISTORY() {
            return switch (lang) {
                case ENGLISH -> "History";
            };
        }

        public String NEW_BRANCH_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "New branch…";
            };
        }

        public String KEEP_HOW_IT_WAS_LAST_SAVED_AS_A_BRANCH_OF_ITS_OWN(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Keep how " + genie + " was last saved as a branch of "
                    + "its own, to try something else from there. " + genie + " stays where it lives";
            };
        }

        public String SAVE_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save now…";
            };
        }

        public String KEEP_HOW_THE_GENIE_IS_NOW_AS_A_MOMENT_TO_COME_BACK_TO() {
            return switch (lang) {
                case ENGLISH -> "Keep how the genie is now as a moment to come back to, such as before a big change";
            };
        }

        public String HOW_IT_WAS_BEFORE_IS_KEPT_SO_YOU_CAN_RETURN_TO_IT() {
            return switch (lang) {
                case ENGLISH -> "How it was before is kept, so you can return to it.";
            };
        }

        public String UNDO() {
            return switch (lang) {
                case ENGLISH -> "Undo";
            };
        }

        public String GO_BACK_TO_HOW_IT_WAS_BEFORE() {
            return switch (lang) {
                case ENGLISH -> "Go back to how it was before";
            };
        }

        public String NAMES_LISTED(List<String> names) {
            return switch (lang) {
                case ENGLISH -> listedInEnglish(names);
            };
        }

        public String THEN() {
            return switch (lang) {
                case ENGLISH -> "then";
            };
        }

        public String WHAT_CHANGED_SINCE_THE_NEWEST_MOMENT_WAS_NEVER_SAVED_AND_IS_LOST(String genie) {
            return switch (lang) {
                case ENGLISH -> "What changed in " + genie + "'s home since the newest moment was never saved, and is lost.";
            };
        }

        public String RESET_TO_HOW_IT_WAS(String branch, String when) {
            return switch (lang) {
                case ENGLISH -> "Reset " + branch + " to how it was " + when + "?";
            };
        }

        public String MOMENTS_AFTER_IT_LEAVE(int count, String branch) {
            return switch (lang) {
                case ENGLISH ->
                    count == 0 ? "No moment comes after it on " + branch + "."
                    : count == 1 ? "The moment after it leaves " + branch + "."
                    : "The " + count + " moments after it leave " + branch + ".";
            };
        }

        public String A_RESET_KEEPS_NO_COPY_SO_IT_CANNOT_BE_UNDONE() {
            return switch (lang) {
                case ENGLISH ->
                    "A reset keeps no copy, so it cannot be undone; moments also on another branch stay there. "
                    + "Going back instead keeps how it is now as a moment of its own.";
            };
        }

        public String RESET(String branch) {
            return switch (lang) {
                case ENGLISH -> "Reset " + branch;
            };
        }

        public String DELETE_THE_BRANCH(String branch) {
            return switch (lang) {
                case ENGLISH -> "Delete the branch " + branch + "?";
            };
        }

        public String MOMENTS_ON_IT_ALONE_GO_WITH_IT(int count, String branch) {
            return switch (lang) {
                case ENGLISH ->
                    count == 0 ? "Every moment on it is on another branch too, so no moment is lost."
                    : count == 1 ? "One moment is on " + branch + " alone, and goes with it."
                    : count + " moments are on " + branch + " alone, and go with it.";
            };
        }

        public String DELETE(String branch) {
            return switch (lang) {
                case ENGLISH -> "Delete " + branch;
            };
        }

        public String DELETE_THE_OLDEST_MOMENTS(int count) {
            return switch (lang) {
                case ENGLISH -> "Delete the " + (count == 1 ? "oldest moment" : count + " oldest moments") + "?";
            };
        }

        public String THEY_DO_NOT_FIT_WITHIN_HOW_FAR_THE_HISTORY_MAY_GROW(int count, String branches, String frees) {
            return switch (lang) {
                case ENGLISH ->
                    (count == 1 ? "It does" : "They do") + " not fit within how far the history may grow"
                    + (branches.isEmpty() ? "" : ", on " + branches) + ". Deleting "
                    + (count == 1 ? "it" : "them") + " frees " + frees
                    + ". Each branch keeps its newest moment, and the next save would delete them anyway.";
            };
        }

        public String DELETE_THEM(int count) {
            return switch (lang) {
                case ENGLISH -> "Delete " + (count == 1 ? "it" : "them");
            };
        }

        public String CANCEL() {
            return switch (lang) {
                case ENGLISH -> "Cancel";
            };
        }

        public String GO_BACK_INSTEAD() {
            return switch (lang) {
                case ENGLISH -> "Go back instead";
            };
        }

        public String BRING_IT_BACK_TO_THAT_MOMENT_AND_KEEP_HOW_IT_IS_NOW() {
            return switch (lang) {
                case ENGLISH -> "Bring it back to that moment, and keep how it is now as a moment you can return to";
            };
        }

        public String SHOW_ONLY_THE_NEWEST_MOMENTS() {
            return switch (lang) {
                case ENGLISH -> "Show only the newest moments";
            };
        }

        public String SHOW_EARLIER_MOMENTS(int count) {
            return switch (lang) {
                case ENGLISH -> "Show " + count + " earlier " + (count == 1 ? "moment" : "moments");
            };
        }

        public String MOMENTS() {
            return switch (lang) {
                case ENGLISH -> "MOMENTS";
            };
        }

        public String READING_THE_HISTORY() {
            return switch (lang) {
                case ENGLISH -> "Reading the history…";
            };
        }

        public String NOTHING_SAVED_YET() {
            return switch (lang) {
                case ENGLISH -> "Nothing saved yet";
            };
        }

        public String ITS_HOME_IS_SAVED_THE_FIRST_TIME_IT_WAKES(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s home is saved the first time it wakes, and from then on "
                    + "whenever something changed. Each save is a moment you can bring it back to.";
            };
        }

        public String HOW_IT_IS_NOW_ON_BRANCH(String genie, String branch) {
            return switch (lang) {
                case ENGLISH -> "How " + genie + " is now" + (branch.isEmpty() ? "" : ", on " + branch);
            };
        }

        public String NOW() {
            return switch (lang) {
                case ENGLISH -> "now";
            };
        }

        public String LIVES_HERE(String genie) {
            return switch (lang) {
                case ENGLISH -> "(" + genie + " lives here)";
            };
        }

        public String AND_MORE(int count) {
            return switch (lang) {
                case ENGLISH -> "and " + count + " more";
            };
        }

        public String CLICK_A_LINE_TO_SHOW_IT_HERE() {
            return switch (lang) {
                case ENGLISH -> "Click a line to show it here";
            };
        }

        public String STAND_HERE(List<String> branches) {
            return switch (lang) {
                case ENGLISH -> listedInEnglish(branches) + (branches.size() == 1 ? " stands here" : " stand here");
            };
        }

        public String CLICK_AGAIN_TO_CLOSE() {
            return switch (lang) {
                case ENGLISH -> "Click again to close";
            };
        }

        public String CLICK_TO_GO_BACK_TO_THIS_MOMENT_START_A_BRANCH_FROM_IT_OR_OPEN_ITS_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Click to go back to this moment, start a branch from it, or open its conversation";
            };
        }

        public String GO_BACK_TO_THIS_MOMENT_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Go back to this moment…";
            };
        }

        public String BRING_ITS_FILES_AND_CONVERSATIONS_BACK_TO_HOW_THEY_WERE_THEN() {
            return switch (lang) {
                case ENGLISH -> "Bring its files and conversations back to how they were then";
            };
        }

        public String START_A_BRANCH_HERE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Start a branch here…";
            };
        }

        public String KEEP_THIS_MOMENT_AS_A_BRANCH_OF_ITS_OWN(String genie) {
            return switch (lang) {
                case ENGLISH -> "Keep this moment as a branch of its own, to try something else from here. " + genie + " stays where it lives";
            };
        }

        public String RESET_TO_HERE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Reset to here…";
            };
        }

        public String TAKE_EVERY_MOMENT_AFTER_THIS_ONE_OFF_THE_BRANCH_FOR_GOOD() {
            return switch (lang) {
                case ENGLISH -> "Take every moment after this one off the branch, for good. You are asked first";
            };
        }

        public String OPEN_THE_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Open the conversation";
            };
        }

        public String LIVES_ON_IT_AND_EVERY_SAVE_GOES_THERE(String genie, String branch) {
            return switch (lang) {
                case ENGLISH -> genie + " lives on " + branch + ", and every save goes there. Click another branch, or its line, to look at it.";
            };
        }

        public String YOU_ARE_LOOKING_AT(String shown, String genie, String home) {
            return switch (lang) {
                case ENGLISH -> "You are looking at " + shown + ". " + genie + " lives on " + home + ", and stays there until you move it.";
            };
        }

        public String MOVE_TO_ELLIPSIS(String genie, String branch) {
            return switch (lang) {
                case ENGLISH -> "Move " + genie + " to " + branch + "…";
            };
        }

        public String MAKE_THIS_BRANCH_THE_GENIES_HOME() {
            return switch (lang) {
                case ENGLISH -> "Make this branch the genie's home: its files and conversations become this branch's newest moment";
            };
        }

        public String DELETE_ELLIPSIS(String branch) {
            return switch (lang) {
                case ENGLISH -> "Delete " + branch + "…";
            };
        }

        public String DELETE_THIS_BRANCH_YOU_ARE_TOLD_FIRST_HOW_MANY_MOMENTS_ONLY_IT_HOLDS() {
            return switch (lang) {
                case ENGLISH -> "Delete this branch. You are told first how many moments only it holds";
            };
        }

        public String LIVES_ON_THIS_BRANCH(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " lives on this branch.";
            };
        }

        public String ITS_LINE_IS_THE_ONE_WITH_WORDS() {
            return switch (lang) {
                case ENGLISH -> "Its line is the one with words";
            };
        }

        public String CLICK_TO_LOOK_AT_ITS_LINE() {
            return switch (lang) {
                case ENGLISH -> "Click to look at its line";
            };
        }

        public String WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP(String genie) {
            return switch (lang) {
                case ENGLISH -> "Wake " + genie + " once first. Its settings are kept in its lamp, which it gets as it first wakes.";
            };
        }

        public String READING_ITS_SETTINGS() {
            return switch (lang) {
                case ENGLISH -> "Reading its settings…";
            };
        }

        public String SEND_IT_TO_SLEEP_TO_CHANGE_THESE(String genie) {
            return switch (lang) {
                case ENGLISH -> "Send " + genie + " to sleep to change these. While it is awake, it keeps what it woke with.";
            };
        }

        public String WHEN_IT_SAVES_BY_ITSELF() {
            return switch (lang) {
                case ENGLISH -> "WHEN IT SAVES BY ITSELF";
            };
        }

        public String AS_IT_WAKES() {
            return switch (lang) {
                case ENGLISH -> "As it wakes";
            };
        }

        public String AFTER_IT_WENT_TO_SLEEP() {
            return switch (lang) {
                case ENGLISH -> "After it went to sleep";
            };
        }

        public String AS_IT_BEGINS_ON_A_QUESTION_OR_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "As it begins on a question or a job";
            };
        }

        public String AS_IT_ANSWERS_OR_ENDS_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "As it answers, or ends a job";
            };
        }

        public String A_JOB_IS_TOLD_WHAT_ITS_LAST_RUNS_SAID_AND_CHANGED() {
            return switch (lang) {
                case ENGLISH ->
                    "A job is told what its last runs said and changed from "
                    + "what was saved as they ended. Saving by hand works whatever these say.";
            };
        }

        public String HOW_LARGE_IT_MAY_GROW() {
            return switch (lang) {
                case ENGLISH -> "HOW LARGE IT MAY GROW";
            };
        }

        public String WHAT_A_MOMENT_HOLDS() {
            return switch (lang) {
                case ENGLISH -> "WHAT A MOMENT HOLDS";
            };
        }

        public String ITS_HOME_THE_FILES_IT_MADE_ITS_NOTES_AND_ITS_CONVERSATIONS(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s home: the files it made, its notes and its conversations, "
                    + "and the settings of its lamp. Going back brings all of these back together.";
            };
        }

        public String ITS_SCHEDULE_STAYS_AS_IT_IS() {
            return switch (lang) {
                case ENGLISH -> "Its schedule stays as it is, and so does everything on this computer outside the genie's lamp.";
            };
        }

        public String A_BRANCH_IS_A_LINE_OF_MOMENTS_WITH_A_HOME_OF_ITS_OWN(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "A branch is a line of moments with a home of its own, schedule and settings "
                    + "included, to try something without losing the rest. " + genie + " lives on one at a time.";
            };
        }

        public String SAVE_BY_HAND_BEFORE_SOMETHING_YOU_MIGHT_WANT_TO_UNDO() {
            return switch (lang) {
                case ENGLISH -> "Save by hand before something you might want to undo, such as a big change to a project.";
            };
        }

        public String NOTHING_IS_EVER_DELETED_ITS_HISTORY_MAY_GROW_WITHOUT_END(String genie) {
            return switch (lang) {
                case ENGLISH -> "Nothing is ever deleted: " + genie + "'s history may grow without end.";
            };
        }

        public String ITS_HISTORY_MAY_GROW_BEYOND_ITS_FIRST_MOMENT(String genie, String size) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s history may grow " + size + " beyond its first moment, on all branches "
                    + "together. Past that, each save deletes the moments saved longest ago; each branch keeps its newest.";
            };
        }

        public String THE_LAST_TIME_OILLAMP_DELETED_OLD_MOMENTS(int count, String freed, String taken, String most) {
            return switch (lang) {
                case ENGLISH ->
                    "The last time, oillamp deleted " + (count == 1 ? "one old moment" : count + " old moments")
                    + " to stay within it, which freed " + freed + ". The history now takes "
                    + taken + " of the " + most + " it may, its first moment included.";
            };
        }

        public String TIDY_UP_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Tidy up now…";
            };
        }

        public String DELETE_WHAT_DOES_NOT_FIT_NOW_RATHER_THAN_AT_THE_NEXT_SAVE() {
            return switch (lang) {
                case ENGLISH -> "Delete what does not fit now, rather than at the next save. You are told first what that is";
            };
        }

        public String NEVER_DELETE() {
            return switch (lang) {
                case ENGLISH -> "Never delete";
            };
        }

        public String WHAT_SHOULD_THIS_MOMENT_BE_REMEMBERED_BY() {
            return switch (lang) {
                case ENGLISH -> "What should this moment be remembered by? You may leave it empty.";
            };
        }

        public String SAVE_AS_IT_IS_NOW(String genie) {
            return switch (lang) {
                case ENGLISH -> "Save " + genie + " as it is now";
            };
        }

        public String WHAT_SHOULD_THE_NEW_BRANCH_BE_CALLED(String genie, String home) {
            return switch (lang) {
                case ENGLISH ->
                    "What should the new branch be called? Lowercase letters, digits, -, _ and ., such as rust-rewrite.\n"
                    + genie + " stays on " + home + "; you can move it later, while it sleeps.";
            };
        }

        public String START_A_BRANCH_FROM_THE_NEWEST_MOMENT() {
            return switch (lang) {
                case ENGLISH -> "Start a branch from the newest moment";
            };
        }

        public String START_A_BRANCH_FROM_THIS_MOMENT() {
            return switch (lang) {
                case ENGLISH -> "Start a branch from this moment";
            };
        }

        public String BRING_IT_BACK_TO_HOW_IT_WAS(String genie, String when) {
            return switch (lang) {
                case ENGLISH ->
                    "Bring " + genie + " back to how it was " + when + "?\n"
                    + "Its files and conversations become what they were then. How it is now is kept, so you can return.";
            };
        }

        public String SLEEPS_WHILE_IT_GOES_BACK_AND_WAKES_AGAIN_AFTER(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " sleeps while it goes back, and wakes again after.";
            };
        }

        public String GO_BACK_IN_TIME() {
            return switch (lang) {
                case ENGLISH -> "Go back in time";
            };
        }

        public String GO_BACK() {
            return switch (lang) {
                case ENGLISH -> "Go back";
            };
        }

        public String STAY() {
            return switch (lang) {
                case ENGLISH -> "Stay";
            };
        }

        public String MOVE_IT_TO_ANOTHER_BRANCH(String genie, String to, String from) {
            return switch (lang) {
                case ENGLISH ->
                    "Move " + genie + " to " + to + "?\n"
                    + "Its files and conversations become those of the newest moment on " + to
                    + ", and every save goes to " + to + " from now on.\n"
                    + "What changed since the newest moment on " + from + " is saved there first, so nothing is lost.";
            };
        }

        public String MOVE_TO_ANOTHER_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Move to another branch";
            };
        }

        public String MOVE() {
            return switch (lang) {
                case ENGLISH -> "Move";
            };
        }

        public String WOKE_UP() {
            return switch (lang) {
                case ENGLISH -> "Woke up";
            };
        }

        public String WENT_TO_SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Went to sleep";
            };
        }

        public String SAVED_BY_HAND() {
            return switch (lang) {
                case ENGLISH -> "Saved by hand";
            };
        }

        public String BEFORE_IT_WENT_TO_WORK() {
            return switch (lang) {
                case ENGLISH -> "Before it went to work";
            };
        }

        public String DID_THE_JOB(String job) {
            return switch (lang) {
                case ENGLISH -> "Did the job: " + job;
            };
        }

        public String DID_A_SCHEDULED_JOB() {
            return switch (lang) {
                case ENGLISH -> "Did a scheduled job";
            };
        }

        public String ANSWERED(String conversation) {
            return switch (lang) {
                case ENGLISH -> "Answered: " + conversation;
            };
        }

        public String ANSWERED_A_QUESTION() {
            return switch (lang) {
                case ENGLISH -> "Answered a question";
            };
        }

        public String TALKED(String conversation) {
            return switch (lang) {
                case ENGLISH -> "Talked: " + conversation;
            };
        }

        public String TALKED_WITH_YOU() {
            return switch (lang) {
                case ENGLISH -> "Talked with you";
            };
        }

        public String BEFORE_GOING_BACK() {
            return switch (lang) {
                case ENGLISH -> "Before going back";
            };
        }

        public String UNDID_GOING_BACK_TO_HOW_IT_WAS(String when) {
            return switch (lang) {
                case ENGLISH -> "Undid going back to how it was " + when;
            };
        }

        public String WENT_BACK_TO_HOW_IT_WAS(String when) {
            return switch (lang) {
                case ENGLISH -> "Went back to how it was " + when;
            };
        }

        public String WENT_BACK_TO_AN_EARLIER_MOMENT() {
            return switch (lang) {
                case ENGLISH -> "Went back to an earlier moment";
            };
        }

        public String BEFORE_IT_MOVED_TO_ANOTHER_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Before it moved to another branch";
            };
        }

        public String SAVED_AS_ITS_SANDBOX_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Saved as its sandbox started";
            };
        }

        public String SAVED_AFTER_ITS_SANDBOX_STOPPED() {
            return switch (lang) {
                case ENGLISH -> "Saved after its sandbox stopped";
            };
        }

        public String SAVED_AS_IT_BEGAN_ON_A_QUESTION_OR_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "Saved as it began on a question or a job";
            };
        }

        public String SAVED_ONCE_THE_CHAT_HAD_GONE_QUIET() {
            return switch (lang) {
                case ENGLISH -> "Saved once the chat had gone quiet";
            };
        }

        public String STOPPED_AND_SAID(String said) {
            return switch (lang) {
                case ENGLISH -> "Stopped. " + said;
            };
        }

        public String FAILED_AND_SAID(String said) {
            return switch (lang) {
                case ENGLISH -> "Failed. " + said;
            };
        }

        public String RAN_OUT_OF_TIME_AND_SAID(String said) {
            return switch (lang) {
                case ENGLISH -> "Ran out of time. " + said;
            };
        }

        public String KEPT_SO_THAT_GOING_BACK_CAN_BE_UNDONE() {
            return switch (lang) {
                case ENGLISH -> "Kept so that going back can be undone";
            };
        }

        public String KEPT_AS_IT_WAS_WHEN_IT_MOVED_TO_ANOTHER_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Kept as it was when it moved to another branch";
            };
        }

        public String WAKES() {
            return switch (lang) {
                case ENGLISH -> "wakes";
            };
        }

        public String ANSWERS() {
            return switch (lang) {
                case ENGLISH -> "answers";
            };
        }

        public String BEGINS_TO_ANSWER() {
            return switch (lang) {
                case ENGLISH -> "begins to answer";
            };
        }

        public String SLEEPS() {
            return switch (lang) {
                case ENGLISH -> "sleeps";
            };
        }

        public String ONE_OR_ANOTHER(List<String> names) {
            return switch (lang) {
                case ENGLISH -> eitherInEnglish(names);
            };
        }

        public String A_BRANCH_NEEDS_A_NAME() {
            return switch (lang) {
                case ENGLISH -> "A branch needs a name, such as rust-rewrite.";
            };
        }

        public String A_BRANCHS_NAME_IS_AT_MOST_SO_MANY_CHARACTERS_LONG(int most) {
            return switch (lang) {
                case ENGLISH -> "A branch's name is at most " + most + " characters long.";
            };
        }

        public String A_BRANCHS_NAME_IS_MADE_OF_LOWERCASE_LETTERS_DIGITS_AND_SIGNS() {
            return switch (lang) {
                case ENGLISH -> "A branch's name is made of lowercase letters, digits, -, _ and ., and begins with a letter or a digit, such as rust-rewrite.";
            };
        }

        public String A_BRANCHS_NAME_CANNOT_HOLD_TWO_DOTS_OR_END_IN_A_DOT_OR_LOCK() {
            return switch (lang) {
                case ENGLISH -> "A branch's name cannot hold .., or end in . or .lock.";
            };
        }

        public String THERE_IS_A_BRANCH_CALLED_IT_ALREADY(String branch) {
            return switch (lang) {
                case ENGLISH -> "There is a branch called " + branch + " already.";
            };
        }
    }

    /// Days and spans of time, as the pages say them; [DateWordingUtil] says which of them fits.
    public record Dates(Lang lang) {
        public String TODAY() {
            return switch (lang) {
                case ENGLISH -> "Today";
            };
        }

        public String TOMORROW() {
            return switch (lang) {
                case ENGLISH -> "Tomorrow";
            };
        }

        public String YESTERDAY() {
            return switch (lang) {
                case ENGLISH -> "Yesterday";
            };
        }

        public String DAY(LocalDate day) {
            return switch (lang) {
                case ENGLISH -> DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).format(day);
            };
        }

        public String TODAY_IN_A_SENTENCE() {
            return switch (lang) {
                case ENGLISH -> "today";
            };
        }

        public String TOMORROW_IN_A_SENTENCE() {
            return switch (lang) {
                case ENGLISH -> "tomorrow";
            };
        }

        public String YESTERDAY_IN_A_SENTENCE() {
            return switch (lang) {
                case ENGLISH -> "yesterday";
            };
        }

        public String ON_DAY(String date) {
            return switch (lang) {
                case ENGLISH -> "on " + date;
            };
        }

        public String SHORT_DAY(LocalDate day) {
            return switch (lang) {
                case ENGLISH -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).format(day);
            };
        }

        public String NOW() {
            return switch (lang) {
                case ENGLISH -> "now";
            };
        }

        public String IN_MINUTES(int count) {
            return switch (lang) {
                case ENGLISH -> "in " + count + (count == 1 ? " minute" : " minutes");
            };
        }

        public String IN_HOURS(int count) {
            return switch (lang) {
                case ENGLISH -> "in " + count + (count == 1 ? " hour" : " hours");
            };
        }

        public String IN_DAYS(int count) {
            return switch (lang) {
                case ENGLISH -> "in " + count + (count == 1 ? " day" : " days");
            };
        }

        public String JUST_NOW() {
            return switch (lang) {
                case ENGLISH -> "just now";
            };
        }

        public String MINUTES_AGO(int count) {
            return switch (lang) {
                case ENGLISH -> count + (count == 1 ? " minute ago" : " minutes ago");
            };
        }

        public String HOURS_AGO(int count) {
            return switch (lang) {
                case ENGLISH -> count + (count == 1 ? " hour ago" : " hours ago");
            };
        }

        public String DAYS_AGO(int count) {
            return switch (lang) {
                case ENGLISH -> count + (count == 1 ? " day ago" : " days ago");
            };
        }

        public String AT(String date, String time) {
            return switch (lang) {
                case ENGLISH -> date + " at " + time;
            };
        }
    }

    /// The words of the parts of the window that several pages share.
    public record Parts(Lang lang) {
        public String NO() {
            return switch (lang) {
                case ENGLISH -> "No";
            };
        }

        public String YES() {
            return switch (lang) {
                case ENGLISH -> "Yes";
            };
        }
    }
}
