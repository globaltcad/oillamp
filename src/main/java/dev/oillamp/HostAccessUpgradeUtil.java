package dev.oillamp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.lamp.LampHostAccess;
import dev.lamp.LampHostAccessGuards;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import sprouts.Tuple;

/// Turns a `[host_access]` table written before `may`, `guards` and the two allowlists existed
/// into those keys, so that it does what it did before.
///
/// The old table said the same things with other keys: `enabled`, `requirements`, `roots`,
/// `commands`, `allow_additive` and `ask_the_user`, and in the oldest lamps `executables`. Every
/// lamp made by an older template, Genies' among them, has them in its `oillamp.toml`, and the
/// loader refuses a key it does not know. So [ConfigLoadingUtil] converts every file it reads with
/// [#upgraded], and the lamp's own file is rewritten once with [#upgradedText]; see
/// `LampPhase.upgradeLampFile`. The user's global file is only ever converted as it is read.
///
/// The old keys map onto the new ones like this:
///
/// - `may`: `nothing` unless `enabled` is true. Then `read_change_run` when `commands` is `ask` or
///   `checker`, else `read` when `allow_additive` is false, else `read_change`. A lamp that ran
///   commands but did not allow the additive tier cannot be said exactly; it gets
///   `read_change_run`, because taking away the commands would break what it was set up for.
/// - `guards`: `allowlists_checker_user` when `commands` is `ask` or `ask_the_user` is true, else
///   `allowlists_checker`. The old lamp checked every request with the checker either way.
/// - `read_allowlist` and `change_allowlist`: both what `roots` listed, or `["~"]` for none,
///   since no roots meant the home directory.
/// - `checker_instructions`: the `requirements`, as they were written, byte for byte.
///
/// `executables` is dropped: it could only be empty in a lamp that started, and empty meant no
/// commands, which `commands` already says. A key of the new kind that the table has already
/// wins over the one worked out from the old keys.
final class HostAccessUpgradeUtil {

    private HostAccessUpgradeUtil() {}

    private static final TomlMapper TOML = TomlMapper.builder().build();

    /// The keys of `[host_access]` that no longer exist.
    static final Tuple<String> OLD_KEYS = Tuple.of(String.class,
            "enabled", "requirements", "roots", "commands", "allow_additive", "ask_the_user", "executables");

    /// The new keys of an old table.
    ///
    /// @param allowlist           both allowlists
    /// @param checkerInstructions the old `requirements`, when the table had them
    record NewKeys(LampHostAccess may, LampHostAccessGuards guards, Tuple<String> allowlist,
                   Optional<String> checkerInstructions) {}

    /// The new keys a `[host_access]` table stands for, or empty when it has no old key, or an
    /// old key with a value that was never valid. Such a table is left alone, and the loader
    /// reports its keys.
    static Optional<NewKeys> newKeysOf(JsonNode table) {
        if (!(table instanceof ObjectNode) || !OLD_KEYS.any(table::has)) return Optional.empty();
        JsonNode enabled = table.path("enabled");
        JsonNode requirements = table.path("requirements");
        JsonNode roots = table.path("roots");
        JsonNode commands = table.path("commands");
        JsonNode additive = table.path("allow_additive");
        JsonNode askTheUser = table.path("ask_the_user");
        boolean fits = (enabled.isMissingNode() || enabled.isBoolean())
                && (requirements.isMissingNode() || requirements.isTextual())
                && (roots.isMissingNode() || (roots.isArray() && allText(roots)))
                && (commands.isMissingNode() || (commands.isTextual()
                        && Tuple.of(String.class, "off", "ask", "checker").contains(commands.asText())))
                && (additive.isMissingNode() || additive.isBoolean())
                && (askTheUser.isMissingNode() || askTheUser.isBoolean());
        if (!fits) return Optional.empty();

        boolean runs = commands.asText("off").equals("ask") || commands.asText("off").equals("checker");
        LampHostAccess may = !enabled.asBoolean(false) ? LampHostAccess.NOTHING
                : runs ? LampHostAccess.MAY_READ_CHANGE_RUN
                : !additive.asBoolean(true) ? LampHostAccess.MAY_READ
                : LampHostAccess.MAY_READ_CHANGE;
        LampHostAccessGuards guards = commands.asText("off").equals("ask") || askTheUser.asBoolean(false)
                ? LampHostAccessGuards.ALLOWLISTS_CHECKER_USER : LampHostAccessGuards.ALLOWLISTS_CHECKER;
        Tuple<String> allowlist = Tuple.of(String.class);
        for (JsonNode root : roots) allowlist = allowlist.add(root.asText());
        if (allowlist.isEmpty()) allowlist = Tuple.of(String.class, "~");
        return Optional.of(new NewKeys(may, guards, allowlist,
                requirements.isTextual() ? Optional.of(requirements.asText()) : Optional.empty()));
    }

    private static boolean allText(JsonNode array) {
        for (JsonNode element : array) if (!element.isTextual()) return false;
        return true;
    }

    /// The parsed configuration `root` with its `[host_access]` table in the new keys, or `root`
    /// itself when there is nothing to convert.
    static JsonNode upgraded(JsonNode root) {
        Optional<NewKeys> keys = newKeysOf(root.path("host_access"));
        if (keys.isEmpty() || !(root instanceof ObjectNode object)) return root;
        ObjectNode copy = object.deepCopy();
        ObjectNode table = (ObjectNode) copy.get("host_access");
        for (String old : OLD_KEYS) table.remove(old);
        if (!table.has("may")) table.put("may", keys.get().may().configName());
        if (!table.has("guards")) table.put("guards", keys.get().guards().configName());
        for (String list : Tuple.of(String.class, "read_allowlist", "change_allowlist")) {
            if (table.has(list)) continue;
            ArrayNode folders = table.putArray(list);
            for (String folder : keys.get().allowlist()) folders.add(folder);
        }
        if (!table.has("checker_instructions"))
            keys.get().checkerInstructions().ifPresent(text -> table.put("checker_instructions", text));
        return copy;
    }

    /// The text of a configuration file with its `[host_access]` table in the new keys, or empty
    /// when it has nothing to convert, or its table is written in a way this cannot rewrite line
    /// by line.
    ///
    /// The old keys are taken out, each with the lines of a value over several lines and the
    /// comment lines that continue its own comment in the same column. `may`, `guards` and the
    /// allowlists go where the first old key was, with the comments the lamp template gives them.
    /// `requirements` is renamed to `checker_instructions` where it is, and its value is left as
    /// it was written. Every other line, other tables and the user's own comments, stays as it was.
    ///
    /// The result is read back, and given only when it reads exactly as [#upgraded] converts the
    /// original: a file this cannot rewrite is then converted as it is read, every time.
    static Optional<String> upgradedText(String file) {
        JsonNode parsed;
        try {
            parsed = TOML.readTree(file);
        } catch (JacksonException unparseable) {
            return Optional.empty();
        }
        JsonNode table = parsed.path("host_access");
        Optional<NewKeys> keys = newKeysOf(table);
        if (keys.isEmpty()) return Optional.empty();

        Pattern header = Pattern.compile("\\s*\\[\\s*host_access\\s*\\]\\s*(#.*)?");
        List<String> lines = file.lines().toList();
        List<String> out = new ArrayList<>();
        int insertAt = -1;
        boolean inside = false;
        String open = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (open.isEmpty() && ConfigFileEditUtil.TABLE.matcher(line).matches())
                inside = header.matcher(line).matches();
            Optional<String> key = inside && open.isEmpty() ? ConfigFileEditUtil.keyOf(line) : Optional.empty();
            if (key.isEmpty() || !OLD_KEYS.contains(key.get())) {
                out.add(line);
                open = ConfigFileEditUtil.stillOpen(line, open);
                continue;
            }
            // The old key's whole value: the lines of a string or a list over several lines.
            List<String> value = new ArrayList<>(List.of(line));
            String stillOpen = ConfigFileEditUtil.stillOpen(line, "");
            int depth = depthAfter(line, 0);
            while ((!stillOpen.isEmpty() || depth > 0) && i + 1 < lines.size()) {
                String next = lines.get(++i);
                value.add(next);
                stillOpen = ConfigFileEditUtil.stillOpen(next, stillOpen);
                if (stillOpen.isEmpty()) depth = depthAfter(next, depth);
            }
            // Comment lines below a one-line value that carry on its comment, in its column.
            int column = value.size() == 1 ? commentAt(line) : -1;
            while (column > 0 && i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("#")
                    && lines.get(i + 1).indexOf('#') == column)
                i++;
            if (insertAt < 0) insertAt = out.size();
            if (key.get().equals("requirements") && !table.has("checker_instructions")) {
                int at = line.indexOf("requirements");
                out.add(line.substring(0, at) + "checker_instructions" + line.substring(at + "requirements".length()));
                out.addAll(value.subList(1, value.size()));
            }
        }
        if (insertAt < 0) return Optional.empty();
        List<String> switches = new ArrayList<>();
        if (!table.has("may"))
            switches.add(GeneratedFileTextUtil.hostAccessLine("may", "\"" + keys.get().may().configName() + "\""));
        if (!table.has("guards"))
            switches.add(GeneratedFileTextUtil.hostAccessLine("guards", "\"" + keys.get().guards().configName() + "\""));
        for (String list : Tuple.of(String.class, "read_allowlist", "change_allowlist"))
            if (!table.has(list))
                switches.add(GeneratedFileTextUtil.hostAccessLine(list, ConfigFileEditUtil.list(keys.get().allowlist())));
        out.addAll(insertAt, switches);

        String upgraded = String.join("\n", out) + "\n";
        try {
            return TOML.readTree(upgraded).equals(upgraded(parsed)) ? Optional.of(upgraded) : Optional.empty();
        } catch (JacksonException unparseable) {
            return Optional.empty();
        }
    }

    /// How many lists are still open at the end of `line`, given how many were open at its start.
    /// Brackets in strings and in the comment are not counted.
    private static int depthAfter(String line, int depth) {
        int end = commentAt(line) < 0 ? line.length() : commentAt(line);
        int start = depth > 0 ? 0 : Math.max(0, line.indexOf('=') + 1);
        char quote = 0;
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '[') depth++;
            else if (c == ']') depth--;
        }
        return depth;
    }

    /// Where the comment on `line` starts, or -1 when it has none. A `#` in a string is not one.
    private static int commentAt(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '#') return i;
        }
        return -1;
    }
}
