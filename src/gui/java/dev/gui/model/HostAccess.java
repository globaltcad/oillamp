package dev.gui.model;

import java.net.URI;
import java.util.Optional;

import sprouts.Tuple;

/// A genie's host access as its page shows it: what its agent may do on this computer, what
/// guards each of its requests, the folders it may read and change, the instructions for the
/// checker, and which web servers in its sandbox this computer's browser may open.
///
/// All live in the genie's lamp, as `may`, `guards`, `read_allowlist`, `change_allowlist` and
/// `checker_instructions` under `[host_access]` in its `oillamp.toml`, and `publish` and `ports`
/// under `[web]`. [Rules] has one value for each, with the same names, as the Lamp API's
/// `Lamp.HostAccess` and `Lamp.Web` have them. There is no permission system here: oillamp
/// checks the allowlists itself, and the checker, another AI model, reads the instructions as
/// the user wrote them.
///
/// The lamp's file can only be changed while the genie sleeps, since a session reads it as it
/// starts; the page says so while it is awake. While it is awake, its lamp has web servers open
/// to this computer's browser: [#webPorts], the ones the file lists and the ones the agent
/// opened.
///
/// @param read      whether the settings were read from the lamp yet
/// @param lamp      whether the genie has a lamp yet. One that never woke has none, so there is
///                  no file to keep the settings in
/// @param saved     the rules as the lamp's file has them
/// @param draft     the rules as the page shows them, which the user changes until they save
/// @param busy      whether a save is on its way to the lamp
/// @param problem   what failed last, the reading or the save, with why in the lamp's words; empty
///                  when it did not
/// @param webPorts  the web servers in its sandbox that this computer's browser can open now, as
///                  the lamp's session said last; none while the lamp is out
public record HostAccess(
    boolean read,
    boolean lamp,
    Rules saved,
    Rules draft,
    boolean busy,
    Optional<Problem> problem,
    Tuple<WebPort> webPorts
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

    public static final HostAccess UNREAD = new HostAccess(false, false, Rules.DEFAULTS, Rules.DEFAULTS, false, Optional.empty(), Tuple.of(WebPort.class));

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
    /// @param web                 which web servers in its sandbox this computer's browser may open
    public record Rules(May may, Guards guards, String readAllowlist, String changeAllowlist, String checkerInstructions, Web web) {

        /// What oillamp's file says when nobody changed it: off, with the checker among the
        /// guards should it be turned on, and the whole home folder in both allowlists; and
        /// opencode's web server listed.
        public static final Rules DEFAULTS = new Rules(May.NOTHING, Guards.ALLOWLISTS_CHECKER, "~", "~", "", Web.DEFAULTS);

        public Rules withMay(May may)                                 { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions, web); }
        public Rules withGuards(Guards guards)                        { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions, web); }
        public Rules withReadAllowlist(String readAllowlist)          { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions, web); }
        public Rules withChangeAllowlist(String changeAllowlist)      { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions, web); }
        public Rules withCheckerInstructions(String checkerInstructions) { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions, web); }
        public Rules withWeb(Web web)                                 { return new Rules(may, guards, readAllowlist, changeAllowlist, checkerInstructions, web); }

        /// The rules as the lamp's file lists them: each allowlist one folder per line.
        public static Rules of(May may, Guards guards, Tuple<String> readFolders, Tuple<String> changeFolders, String checkerInstructions, Web web) {
            return new Rules(may, guards, String.join("\n", readFolders), String.join("\n", changeFolders), checkerInstructions, web);
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
            for (ListedPort port : web.ports())
                if (!port.name().matches(ListedPort.NAME))
                    return Optional.of(new Mistake.NotAPortName(port.name()));
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
        /// A listed port's name that is not [ListedPort#NAME]. A comma or an equals sign could not
        /// even be sent to oillamp, which keeps them between the ports and their names.
        record NotAPortName(String name) implements Mistake {}

        /// What is wrong, in `lang`.
        default String words(Lang lang) {
            Lang.HostAccessPage words = lang.HOST_ACCESS_PAGE();
            return switch (this) {
                case NotAFolder it -> words.IS_NOT_A_FOLDER_WRITE_ONE_STARTING_WITH_A_TILDE_OR_A_SLASH(it.line());
                case HoldsAColon it -> words.HOLDS_A_COLON_WHICH_A_FOLDER_HERE_CANNOT(it.line());
                case NoCheckerInstructions _ -> words.THE_CHECKER_NEEDS_INSTRUCTIONS_WRITE_THEM_OR_CHOOSE_ALLOWLISTS_ALONE();
                case NotAPortName it -> words.CANNOT_NAME_A_PORT_USE_LOWERCASE_LETTERS_DIGITS_AND_DASHES(it.name());
            };
        }
    }

    public HostAccess withDraft(Rules draft)       { return new HostAccess(read, lamp, saved, draft, busy, problem, webPorts); }
    /// With `saved` as what the lamp's file has, and the page still showing its draft.
    public HostAccess withSaved(Rules saved)       { return new HostAccess(read, lamp, saved, draft, busy, problem, webPorts); }
    public HostAccess withBusy(boolean busy)       { return new HostAccess(read, lamp, saved, draft, busy, problem, webPorts); }
    public HostAccess withProblem(Problem problem) { return new HostAccess(read, lamp, saved, draft, busy, Optional.of(problem), webPorts); }
    public HostAccess noProblem()                  { return new HostAccess(read, lamp, saved, draft, busy, Optional.empty(), webPorts); }
    public HostAccess withWebPorts(Tuple<WebPort> webPorts) { return new HostAccess(read, lamp, saved, draft, busy, problem, webPorts); }

    /// Read from the lamp, or saved to it: what the page shows is what the file has.
    public HostAccess readAs(boolean lamp, Rules rules) {
        return new HostAccess(true, lamp, rules, rules, false, Optional.empty(), webPorts);
    }

    /// Whether the page shows rules the lamp's file does not have yet.
    public boolean changed() { return !draft.equals(saved); }

    /// Where this computer's browser opens `listed`, a port the lamp's file lists, while its
    /// session published it; empty while it did not, such as with publishing off.
    public Optional<WebPort> published(ListedPort listed) {
        return webPorts.stream().filter(listed::names).findFirst();
    }

    /// The web servers the agent opened this session, which the lamp's file does not list: each
    /// open port that none of the listed ports names. The file as the lamp read it is [#saved()],
    /// since it cannot change while the lamp is lit.
    public Tuple<WebPort> openedThisSession() {
        return webPorts.removeIf(port -> saved.web().ports().any(listed -> listed.names(port)));
    }

    // ─── web servers ───────────────────────────────────────────────────────────────────────

    /// Which web servers in the genie's sandbox this computer's browser may open: `publish` and
    /// `ports` under `[web]` in the lamp's file.
    ///
    /// @param publish which ports the lamp publishes
    /// @param ports   the listed ports, which it publishes each time it wakes, unless `publish`
    ///                is [Publish#OFF]
    public record Web(Publish publish, Tuple<ListedPort> ports) {

        /// What oillamp's file says when nobody changed it: the listed ports, which are opencode's
        /// on 4096.
        public static final Web DEFAULTS = new Web(Publish.LISTED, Tuple.of(ListedPort.class, new ListedPort("opencode", 4096)));

        public Web withPublish(Publish publish) { return new Web(publish, ports); }

        /// With `port` listed after the others.
        public Web with(ListedPort port) { return new Web(publish, ports.add(port)); }

        /// Without `port` among the listed ones.
        public Web without(ListedPort port) { return new Web(publish, ports.remove(port)); }
    }

    /// Which web servers the lamp publishes to this computer's browser: `publish` in the lamp's
    /// file. A ladder: each step publishes what the one before it does, and more. A page opened
    /// from a published port runs in the user's browser, where it can reach their other local
    /// web servers; that is why [#OFF] exists.
    public enum Publish {
        /// Nothing, whatever the file lists.
        OFF,
        /// The listed ports, and no others.
        LISTED,
        /// The listed ports, and each port the agent opens while it is awake, once the user
        /// allowed it.
        ASK,
        /// The listed ports, and every port the agent opens while it is awake, unasked.
        ANY
    }

    /// A port the lamp's file lists under `[web]`: a name that says what it is for, and the
    /// port inside the sandbox it names.
    ///
    /// @param name        such as `opencode`
    /// @param sandboxPort such as `4096`
    public record ListedPort(String name, int sandboxPort) {

        /// What a name may be, as oillamp has it: up to 32 lowercase letters, digits and dashes.
        public static final String NAME = "[a-z0-9-]{1,32}";

        /// The port the user typed: `name`, without the spaces around it, and `port`, a whole
        /// number. Empty while either is missing, or the port is not a number a port can be.
        /// Whether oillamp accepts them, it says as the page is saved.
        public static Optional<ListedPort> typed(String name, String port) {
            String number = port.strip();
            if (name.isBlank() || !number.matches("[0-9]{1,5}")) return Optional.empty();
            return Optional.of(new ListedPort(name.strip(), Integer.parseInt(number)));
        }

        /// Whether this is the listed port that `open` is.
        boolean names(WebPort open) { return name.equals(open.name()) && sandboxPort == open.sandboxPort(); }
    }

    /// A web server in the genie's sandbox that this computer's browser can open while its lamp
    /// is lit: an opening into the sandbox, not a promise that anything answers there.
    ///
    /// @param name        what the lamp calls it: the name the file lists it by, such as
    ///                    `opencode`, or, for one the agent opened, such as `port-5173`
    /// @param sandboxPort the port inside the sandbox, such as `4096`
    /// @param link        what to open in a browser, such as `http://k3v7x2ab.localhost:41237/`
    /// @param program     the command line of the program that listened on a port the agent
    ///                    opened, as the lamp found it, such as `python3 -m http.server 5173`;
    ///                    empty for a listed port, whose name says what it is for, and when the
    ///                    lamp could not tell
    public record WebPort(String name, int sandboxPort, URI link, String program) {}
}
