package dev.gui.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Tuple;

/// A genie's host access as its page shows it: whether its agent may reach this computer, and
/// the rules the checker model judges each of its requests against.
///
/// Both live in the genie's lamp, as `enabled` and `requirements` under `[host_access]` in its
/// `oillamp.toml`. Genies writes the requirements as JSON built from [PermissionRules], and reads them back
/// into it. There is no permission system here: the checker reads the JSON as it would any words.
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
/// @param problem   why the last reading or save failed, in the lamp's words; empty when it did not
/// @param requestsForPermission the requests the genie's lamp asks the user to allow, oldest first
public record HostAccess(boolean read, boolean lamp, PermissionRules saved, PermissionRules draft, boolean busy, String problem,
                         Tuple<RequestForPermission> requestsForPermission) {

    public static final HostAccess UNREAD = new HostAccess(false, false, PermissionRules.OFF, PermissionRules.OFF, false, "",
                                                           Tuple.of(RequestForPermission.class));

    /// How long the lamp waits for the user to allow a request. It refuses the request after that.
    public static final Duration PATIENCE = Duration.ofSeconds(60);

    /// The user's rules for the genie, as the page edits them.
    ///
    /// @param on        whether the agent may ask to reach this computer at all
    /// @param mayRead   folders it may read, one per line, such as `~/Documents`
    /// @param mayChange folders it may read and change, one per line
    /// @param words     anything else, in the user's own words
    public record PermissionRules(boolean on, String mayRead, String mayChange, String words) {

        public static final PermissionRules OFF = new PermissionRules(false, "", "", "");

        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String WRITTEN_BY = "Genies, from the genie's host access page";

        public PermissionRules withOn(boolean on)              { return new PermissionRules(on, mayRead, mayChange, words); }
        public PermissionRules withMayRead(String mayRead)     { return new PermissionRules(on, mayRead, mayChange, words); }
        public PermissionRules withMayChange(String mayChange) { return new PermissionRules(on, mayRead, mayChange, words); }
        public PermissionRules withWords(String words)         { return new PermissionRules(on, mayRead, mayChange, words); }

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
            return json;
        }

        /// Permission rules read back from the lamp's file. Requirements that are not JSON Genies wrote,
        /// such as rules a person wrote into `oillamp.toml` by hand, become the user's own words,
        /// so that nothing of them is lost.
        public static PermissionRules of(boolean enabled, String requirements) {
            Optional<JsonNode> json;
            try {
                json = Optional.of(JSON.readTree(requirements)).filter(it -> it.path("written_by").asText().equals(WRITTEN_BY));
            } catch (Exception notJson) {
                json = Optional.empty();
            }
            if (json.isEmpty()) return new PermissionRules(enabled, "", "", requirements.strip());
            JsonNode rule = json.get();
            return new PermissionRules(enabled, lines(rule.path("folders_it_may_read")), lines(rule.path("folders_it_may_read_and_change")),
                             rule.path("in_the_users_own_words").asText(""));
        }

        /// What the user may want to look at before saving; empty when nothing stands out. Only a
        /// warning: the model that judges reads words too, so "everything" is a rule it
        /// understands, and these rules save whatever they say.
        public Optional<String> warning() {
            for (String folder : folders(mayRead + "\n" + mayChange))
                if (!folder.startsWith("/") && !folder.startsWith("~"))
                    return Optional.of("\"" + folder + "\" is not a path. The model that judges reads it as words, "
                            + "so make sure they say what you mean.");
            if (on && empty())
                return Optional.of("Nothing is listed, so the model that judges will refuse every request.");
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

    /// A request the genie's lamp asks the user to allow, such as running a program. The checker
    /// model allowed it already; some requests need the user too.
    ///
    /// @param tool such as `host_run`
    /// @param what what is about to happen, in the lamp's words; the user's decision names the
    ///             request by this
    /// @param at   when the lamp asked
    public record RequestForPermission(String tool, String what, Instant at) {}

    public HostAccess withDraft(PermissionRules draft)     { return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission); }
    public HostAccess withBusy(boolean busy)     { return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission); }
    public HostAccess withProblem(String problem) { return new HostAccess(read, lamp, saved, draft, busy, problem, requestsForPermission); }

    /// Read from the lamp, or saved to it: what the page shows is what the file has.
    public HostAccess readAs(boolean lamp, PermissionRules rules) {
        return new HostAccess(true, lamp, rules, rules, false, "", requestsForPermission);
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
