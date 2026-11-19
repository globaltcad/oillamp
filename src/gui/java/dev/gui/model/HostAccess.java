package dev.gui.model;

import java.util.Optional;

import sprouts.Tuple;

/// A genie's host access as its page shows it: what its agent may do on this computer, what
/// guards each of its requests, the folders it may read and change, and the instructions for
/// the checker.
///
/// All live in the genie's lamp, as `may`, `guards`, `read_allowlist`, `change_allowlist` and
/// `checker_instructions` under `[host_access]` in its `oillamp.toml`. [Rules] has one value for
/// each, with the same names, as the Lamp API's `Lamp.HostAccess` has them. There is no
/// permission system here: oillamp checks the allowlists itself, and the checker, another AI
/// model, reads the instructions as the user wrote them.
///
/// The lamp's file can only be changed while the genie sleeps, since a session reads it as it
/// starts; the page says so while it is awake.
///
/// @param read      whether the settings were read from the lamp yet
/// @param lamp      whether the genie has a lamp yet. One that never woke has none, so there is
///                  no file to keep the settings in
/// @param saved     the rules as the lamp's file has them
/// @param draft     the rules as the page shows them, which the user changes until they save
/// @param busy      whether a save is on its way to the lamp
/// @param problem   what failed last, the reading or the save, with why in the lamp's words; empty
///                  when it did not
public record HostAccess(
    boolean read,
    boolean lamp,
    Rules saved,
    Rules draft,
    boolean busy,
    Optional<Problem> problem
) {

    /// What failed last, which the page says until the next save. Each says why in `reason`, in
    /// the words of the lamp, which stay as oillamp put them; what failed is said in the language
    /// Genies speaks with [#words(Lang)].
    public sealed interface Problem {
        /// Why it failed, as oillamp said.
        String reason();

        /// Reading the settings from the lamp's file.
        record CouldNotRead(String reason) implements Problem {}
        /// Saving them to it.
        record CouldNotSave(String reason) implements Problem {}

        /// What failed and why, in `lang`.
        default String words(Lang lang) {
            Lang.HostAccessPage words = lang.HOST_ACCESS_PAGE();
            return switch (this) {
                case CouldNotRead it -> words.THE_HOST_ACCESS_COULD_NOT_BE_READ(it.reason());
                case CouldNotSave it -> words.OILLAMP_COULD_NOT_SAVE_IT(it.reason());
            };
        }
    }

    public static final HostAccess UNREAD = new HostAccess(false, false, Rules.DEFAULTS, Rules.DEFAULTS, false, Optional.empty());

    /// What the agent may do on this computer: `may` in the lamp's file. A ladder: each step
    /// allows everything the one before it does, and more.
    public enum May {
        /// Nothing: host access is off, and the agent has no tools to reach this computer.
        NOTHING,
        /// List folders, read files, copy a file into the sandbox, show a document.
        READ,
        /// Also write and edit files, copy a file out of the sandbox, open a web page, show a
        /// notification.
        READ_CHANGE,
        /// Also run commands, which can do anything the user can.
        READ_CHANGE_RUN
    }

    /// What every request passes before it happens: `guards` in the lamp's file. A chain, always
    /// in this order, each value adding one guard to the one before it: the allowlists, which
    /// oillamp checks itself; the checker, another AI model, which judges the request against
    /// the checker instructions; the user, asked about every command, and about a path outside
    /// the allowlists.
    public enum Guards {
        /// The allowlists alone. No model judges anything, and nobody is asked.
        ALLOWLISTS,
        /// The allowlists, then the checker.
        ALLOWLISTS_CHECKER,
        /// The allowlists, then the checker, then the user.
        ALLOWLISTS_CHECKER_USER;

        /// Whether the checker judges each request.
        public boolean checks() { return this != ALLOWLISTS; }
    }

    /// The user's settings for the genie, as the page edits them: one value for each a person
    /// decides on in the lamp's file. The allowlists are text, one folder per line, as the page
    /// shows them; [#readFolders()] and [#changeFolders()] are the folders the file lists.
    ///
    /// @param may                 what the agent may do on this computer
    /// @param guards              what each request passes before it happens
    /// @param readAllowlist       the folders it may read, one per line, such as `~/Documents`
    /// @param changeAllowlist     the folders it may read and change, and start commands in, one
    ///                            per line
    /// @param checkerInstructions what the checker judges each request against, in the user's
    ///                            own words, as they wrote them; unused with [Guards#ALLOWLISTS]
    public record Rules(May may, Guards guards, String readAllowlist, String changeAllowlist, String checkerInstructions) {

        /// What oillamp's file says when nobody changed it: off, with the checker among the
        /// guards should it be turned on, and the whole home folder in both allowlists.
        public static final Rules DEFAULTS = new Rules(May.NOTHING, Guards.ALLOWLISTS_CHECKER, "~", "~", "");

        public Rules withMay(May may)                                 { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions); }
        public Rules withGuards(Guards guards)                        { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions); }
        public Rules withReadAllowlist(String readAllowlist)          { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions); }
        public Rules withChangeAllowlist(String changeAllowlist)      { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions); }
        public Rules withCheckerInstructions(String checkerInstructions) { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions); }

        /// The rules as the lamp's file lists them: each allowlist one folder per line.
        public static Rules of(May may, Guards guards, Tuple<String> readFolders, Tuple<String> changeFolders, String checkerInstructions) {
            return new Rules(may, guards, String.join("\n", readFolders), String.join("\n", changeFolders), checkerInstructions);
        }

        /// The folders of the read allowlist: its lines, without blank ones and the spaces around each.
        public Tuple<String> readFolders() { return folders(readAllowlist); }

        /// The folders of the change allowlist, as [#readFolders()].
        public Tuple<String> changeFolders() { return folders(changeAllowlist); }

        /// Whether the agent may do anything on this computer at all.
        public boolean on() { return may != May.NOTHING; }

        /// Whether commands would run with nothing but the allowlists in their way: no checker,
        /// and nobody asked. Allowed, since it is the user's computer, but the page warns about it
        /// loudly, as oillamp does.
        public boolean runsCommandsUnchecked() {
            return may == May.READ_CHANGE_RUN && !guards.checks();
        }

        /// What keeps these rules from being saved, as oillamp would refuse them; empty when
        /// nothing does. The first one found: a folder of the read allowlist, then of the change
        /// allowlist, then the checker instructions.
        public Optional<Mistake> mistake() {
            for (String folder : readFolders().addAll(changeFolders())) {
                if (folder.contains(":")) return Optional.of(new Mistake.HoldsAColon(folder));
                if (!folder.equals("~") && !folder.startsWith("~/") && !folder.startsWith("/"))
                    return Optional.of(new Mistake.NotAFolder(folder));
            }
            if (on() && guards.checks() && checkerInstructions.isBlank())
                return Optional.of(new Mistake.NoCheckerInstructions());
            return Optional.empty();
        }

        private static Tuple<String> folders(String lines) {
            Tuple<String> folders = Tuple.of(String.class);
            for (String line : lines.split("\n", -1))
                if (!line.isBlank()) folders = folders.add(line.strip());
            return folders;
        }
    }

    /// What keeps rules from being saved, said in the language Genies speaks with
    /// [#words(Lang)].
    public sealed interface Mistake {
        /// A line of an allowlist that is not `~`, does not start with `~/`, and is not absolute.
        record NotAFolder(String line) implements Mistake {}
        /// A line of an allowlist with a colon in it, which oillamp keeps between folders.
        record HoldsAColon(String line) implements Mistake {}
        /// No checker instructions, while the checker is among the guards of a genie that may do
        /// something: the checker would have nothing to judge against.
        record NoCheckerInstructions() implements Mistake {}

        /// What is wrong, in `lang`.
        default String words(Lang lang) {
            Lang.HostAccessPage words = lang.HOST_ACCESS_PAGE();
            return switch (this) {
                case NotAFolder it -> words.IS_NOT_A_FOLDER_WRITE_ONE_STARTING_WITH_A_TILDE_OR_A_SLASH(it.line());
                case HoldsAColon it -> words.HOLDS_A_COLON_WHICH_A_FOLDER_HERE_CANNOT(it.line());
                case NoCheckerInstructions _ -> words.THE_CHECKER_NEEDS_INSTRUCTIONS_WRITE_THEM_OR_CHOOSE_ALLOWLISTS_ALONE();
            };
        }
    }

    public HostAccess withDraft(Rules draft)       { return new HostAccess(read, lamp, saved, draft, busy, problem); }
    public HostAccess withBusy(boolean busy)       { return new HostAccess(read, lamp, saved, draft, busy, problem); }
    public HostAccess withProblem(Problem problem) { return new HostAccess(read, lamp, saved, draft, busy, Optional.of(problem)); }
    public HostAccess noProblem()                  { return new HostAccess(read, lamp, saved, draft, busy, Optional.empty()); }

    /// Read from the lamp, or saved to it: what the page shows is what the file has.
    public HostAccess readAs(boolean lamp, Rules rules) {
        return new HostAccess(true, lamp, rules, rules, false, Optional.empty());
    }

    /// Whether the page shows rules the lamp's file does not have yet.
    public boolean changed() { return !draft.equals(saved); }
}
