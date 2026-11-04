package dev.gui.model;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Tuple;

/// A genie's host access as its page shows it: whether its agent may reach this computer, the
/// rules the checker model judges each of its requests against, and when the user decides too.
///
/// All live in the genie's lamp, as `enabled`, `requirements`, `commands` and `ask_the_user`
/// under `[host_access]` in its `oillamp.toml`. Genies writes the requirements as JSON built from
/// [PermissionRules], and reads them back into it. There is no permission system here: the
/// checker reads the JSON as it would any words.
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
/// @param requestsForPermission the requests the genie's lamp asks the user to allow, oldest first
public record HostAccess(
    boolean read,
    boolean lamp,
    PermissionRules saved,
    PermissionRules draft,
    boolean busy,
    Optional<Problem> problem,
    Tuple<RequestForPermission> requestsForPermission
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

    public static final HostAccess UNREAD = new HostAccess(false, false, PermissionRules.OFF, PermissionRules.OFF, false, Optional.empty(),
                                                           Tuple.of(RequestForPermission.class));

    /// How long the lamp waits for the user to allow a request. It refuses the request after that.
    public static final Duration PATIENCE = Duration.ofSeconds(60);

    /// Who lets the agent run a command on this computer: `commands` in the lamp's file. A
    /// command names no folder the lamp could check, and can do anything the user can.
    public enum Commands {
        /// Nobody: the agent cannot run commands at all.
        NEVER,
        /// The user, each time: the checker model refuses only a command that clearly breaks the
        /// rules, and the user is asked before any other runs.
        YOU_EACH_TIME,
        /// The checker model alone: what it allows runs straight away.
        THE_MODEL_ALONE;

        /// Whether the user is asked before a command runs.
        public boolean asksYou() { return this == YOU_EACH_TIME; }
    }

    /// The user's rules for the genie, as the page edits them.
    ///
    /// @param on             whether the agent may ask to reach this computer at all
    /// @param mayRead        folders it may read, one per line, such as `~/Documents`
    /// @param mayChange      folders it may read and change, one per line
    /// @param words          anything else, in the user's own words
    /// @param commands       who lets it run a command
    /// @param askOutsideHome whether a request for a folder outside the user's home folder,
    ///                       which the checker model allowed, goes to the user rather than being
    ///                       refused: `ask_the_user`. The lamp's roots, which Genies leaves empty,
    ///                       are the home folder
    public record PermissionRules(boolean on, String mayRead, String mayChange, String words, Commands commands, boolean askOutsideHome) {

        public static final PermissionRules OFF = new PermissionRules(false, "", "", "", Commands.NEVER, false);

        /// What the requirements tell the checker model about commands, which lifts "refuse
        /// everything else" for them; with [Commands#NEVER] they say nothing, as before there
        /// were commands. Written in English, for the model. While the user is asked, the
        /// model turns away only what clearly breaks the rules, and lets every other command
        /// through to them. While it decides alone,
        /// which commands are fine is the user's to say, in their own words: Genies adds no rule
        /// of its own.
        public static final String THE_PERSON_APPROVES_EVERY_COMMAND =
            "The person approves every command themselves. Refuse only a command that clearly breaks "
            + "these settings or the user's own words; allow every other one, so that it reaches the person.";
        public static final String COMMANDS_ARE_SWITCHED_ON =
            "Commands are switched on. Judge each one against these settings and the user's own words.";

        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String WRITTEN_BY = "Genies, from the genie's host access page";

        public PermissionRules withOn(boolean on)                         { return new PermissionRules(on, mayRead, mayChange, words, commands, askOutsideHome); }
        public PermissionRules withMayRead(String mayRead)                { return new PermissionRules(on, mayRead, mayChange, words, commands, askOutsideHome); }
        public PermissionRules withMayChange(String mayChange)            { return new PermissionRules(on, mayRead, mayChange, words, commands, askOutsideHome); }
        public PermissionRules withWords(String words)                    { return new PermissionRules(on, mayRead, mayChange, words, commands, askOutsideHome); }
        public PermissionRules withCommands(Commands commands)            { return new PermissionRules(on, mayRead, mayChange, words, commands, askOutsideHome); }
        public PermissionRules withAskOutsideHome(boolean askOutsideHome) { return new PermissionRules(on, mayRead, mayChange, words, commands, askOutsideHome); }

        /// Whether the lamp may ask the user about a request, which then waits for them.
        public boolean asksYou() { return commands.asksYou() || askOutsideHome; }

        /// The requirements Genies writes into the lamp's file: these rules as JSON, for the
        /// checker model to read. Empty while host access is off and there are no rules at all.
        public String requirements() {
            return !on && empty() ? "" : rule().toPrettyString();
        }

        /// Whether no folder is listed and nothing is said.
        public boolean empty() {
            return folders(mayRead).isEmpty() && folders(mayChange).isEmpty() && words.isBlank();
        }

        private ObjectNode rule() {
            ObjectNode json = JSON.createObjectNode();
            json.put("written_by", WRITTEN_BY);
            json.put("rule", "Allow a request only when these settings allow it. Refuse everything else.");
            ArrayNode read = json.putArray("folders_it_may_read");
            folders(mayRead).forEach(read::add);
            ArrayNode change = json.putArray("folders_it_may_read_and_change");
            folders(mayChange).forEach(change::add);
            if (!words.isBlank()) json.put("in_the_users_own_words", words.strip());
            switch (commands) {
                case NEVER -> {}
                case YOU_EACH_TIME -> json.put("commands", THE_PERSON_APPROVES_EVERY_COMMAND);
                case THE_MODEL_ALONE -> json.put("commands", COMMANDS_ARE_SWITCHED_ON);
            }
            return json;
        }

        /// Permission rules read back from the lamp's file. Requirements that are not JSON Genies wrote,
        /// such as rules a person wrote into `oillamp.toml` by hand, become the user's own words,
        /// so that nothing of them is lost. What the requirements say about commands is not read
        /// back: `commands` says it, and the requirements follow from it.
        public static PermissionRules of(boolean enabled, String requirements, Commands commands, boolean askOutsideHome) {
            Optional<JsonNode> json;
            try {
                json = Optional.of(JSON.readTree(requirements)).filter(it -> it.path("written_by").asText().equals(WRITTEN_BY));
            } catch (Exception notJson) {
                json = Optional.empty();
            }
            if (json.isEmpty()) return new PermissionRules(enabled, "", "", requirements.strip(), commands, askOutsideHome);
            JsonNode rule = json.get();
            return new PermissionRules(enabled, lines(rule.path("folders_it_may_read")), lines(rule.path("folders_it_may_read_and_change")),
                             rule.path("in_the_users_own_words").asText(""), commands, askOutsideHome);
        }

        /// What the user may want to look at before saving; empty when nothing stands out. Only a
        /// warning: the model that judges reads words too, so "everything" is a rule it
        /// understands, and these rules save whatever they say.
        ///
        /// @param home the user's home folder, which the lamp reaches without asking the user
        public Optional<String> warning(Lang lang, Path home) {
            Lang.HostAccessPage words = lang.HOST_ACCESS_PAGE();
            for (String folder : folders(mayRead + "\n" + mayChange))
                if (!folder.startsWith("/") && !folder.startsWith("~"))
                    return Optional.of(words.IS_NOT_A_PATH_THE_MODEL_THAT_JUDGES_READS_IT_AS_WORDS(folder));
            if (!askOutsideHome)
                for (String folder : folders(mayRead + "\n" + mayChange))
                    if (folder.startsWith("/") && !Path.of(folder).normalize().startsWith(home))
                        return Optional.of(words.IS_OUTSIDE_YOUR_HOME_FOLDER_SO_THE_LAMP_REFUSES_IT_UNLESS_YOU_ARE_ASKED(folder));
            if (on && empty() && !commands.asksYou())
                return Optional.of(words.NOTHING_IS_LISTED_SO_THE_MODEL_THAT_JUDGES_WILL_REFUSE_EVERY_REQUEST());
            return Optional.empty();
        }

        private static java.util.List<String> folders(String lines) {
            return Arrays.stream(lines.split("\n")).map(String::strip).filter(it -> !it.isEmpty()).toList();
        }

        private static String lines(JsonNode array) {
            StringBuilder text = new StringBuilder();
            array.forEach(it -> text.append(text.isEmpty() ? "" : "\n").append(it.asText()));
            return text.toString();
        }
    }

    /// A request the genie's lamp asks the user to allow, such as running a command. The checker
    /// model allowed it already; some requests need the user too.
    ///
    /// @param tool such as `host_bash`
    /// @param what what is about to happen, in the lamp's words; the user's decision names the
    ///             request by this
    /// @param at   when the lamp asked
    public record RequestForPermission(String tool, String what, Instant at) {}

    public HostAccess withDraft(PermissionRules draft)     { return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission); }
    public HostAccess withBusy(boolean busy)     { return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission); }
    public HostAccess withProblem(Problem problem) { return new HostAccess(read, lamp, saved, draft, busy, Optional.of(problem), requestsForPermission); }
    public HostAccess noProblem()                 { return new HostAccess(read, lamp, saved, draft, busy, Optional.empty(), requestsForPermission); }

    /// Read from the lamp, or saved to it: what the page shows is what the file has.
    public HostAccess readAs(boolean lamp, PermissionRules rules) {
        return new HostAccess(true, lamp, rules, rules, false, Optional.empty(), requestsForPermission);
    }

    /// Whether the page shows rules the lamp's file does not have yet.
    public boolean changed() { return !draft.equals(saved); }

    public HostAccess permissionRequested(RequestForPermission request) {
        return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission.add(request));
    }

    /// The user allowed or refused the request the lamp described as `what`.
    public HostAccess permissionDecided(String what) {
        return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission.removeIf(it -> it.what().equals(what)));
    }

    /// The requests the lamp still waits on the user's decision about at `now`.
    public Tuple<RequestForPermission> waitingAt(Instant now) {
        return requestsForPermission.retainIf(it -> it.at().plus(PATIENCE).isAfter(now));
    }

    /// The genie's lamp went out, and with it every request it waited on the user's decision about.
    public HostAccess wentOut() {
        return new HostAccess(read, lamp, saved, draft, busy, problem, Tuple.of(RequestForPermission.class));
    }
}
