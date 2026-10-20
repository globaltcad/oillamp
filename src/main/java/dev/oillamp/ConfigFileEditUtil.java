package dev.oillamp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Changes a few keys of one table in the text of a lamp's `oillamp.toml`, and nothing else:
/// every other line, the user's comments among them, stays as it was. What
/// `oillamp host-access <dir> on|off` and `oillamp autosave <dir> ...` write, for an
/// application's settings page.
///
/// This edits lines, it does not parse TOML. So the caller reads the result back with
/// [ConfigLoadingUtil] and keeps it only when it says exactly what was asked; see
/// `CommandExecutionUtil.hostAccess`.
final class ConfigFileEditUtil {

    private ConfigFileEditUtil() {}

    /// A table's header, such as `[host_access]` or `[[network.rules]]`, with an optional comment.
    private static final Pattern TABLE = Pattern.compile("\\s*\\[\\[?[^\\]\\[]+\\]\\]?\\s*(#.*)?");
    /// A key set to a bare value, such as `startup    = false   # as a session starts`. Only its
    /// value is replaced, so the alignment and the comment stay.
    private static final Pattern BARE = Pattern.compile("(\\s*[A-Za-z0-9_-]+\\s*=\\s*)(true|false|[-+0-9_.]+)(\\s*(#.*)?)");

    /// `file` with `enabled` and `requirements` under `[host_access]` set to what is given.
    static String withHostAccess(String file, Optional<Boolean> enabled, Optional<String> requirements) {
        SequencedMap<String, String> values = new LinkedHashMap<>();
        enabled.ifPresent(on -> values.put("enabled", on.toString()));
        requirements.ifPresent(text -> values.put("requirements", text(text)));
        return withValues(file, "host_access", values);
    }

    /// `file` with each key of `values` under `[table]` set to its value, which is already
    /// written as TOML, such as `true` or a quoted string. A key that is there is replaced where
    /// it is, the lines of a multi-line value with it, and a bare value keeps its comment; one that is not goes right under the
    /// table's header, in the order given; a file without the table gets one at its end.
    static String withValues(String file, String table, SequencedMap<String, String> values) {
        Pattern wanted = Pattern.compile("\\s*\\[\\s*" + Pattern.quote(table) + "\\s*\\]\\s*(#.*)?");
        List<String> lines = new ArrayList<>(file.lines().toList());
        // Where the table is: its header, and the first line after it, which is the next
        // header or the end. A line inside a multi-line string is text, whatever it looks like.
        int header = -1;
        int end = lines.size();
        String open = "";
        for (int i = 0; i < lines.size(); i++) {
            if (open.isEmpty() && TABLE.matcher(lines.get(i)).matches()) {
                if (header >= 0) {
                    end = i;
                    break;
                }
                if (wanted.matcher(lines.get(i)).matches()) header = i;
            }
            open = stillOpen(lines.get(i), open);
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.getLast().isBlank()) lines.add("");
            lines.add("[" + table + "]");
            header = lines.size() - 1;
            end = lines.size();
        }

        List<String> rows = new ArrayList<>();
        Set<String> found = new HashSet<>();
        open = "";
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i);
            Optional<String> key = open.isEmpty() ? keyOf(line).filter(values::containsKey) : Optional.empty();
            if (key.isPresent()) {
                for (open = stillOpen(line, ""); !open.isEmpty() && i + 1 < end; ) open = stillOpen(lines.get(++i), open);
                Matcher bare = BARE.matcher(line);
                rows.add(bare.matches() ? bare.group(1) + values.get(key.get()) + bare.group(3)
                                        : key.get() + " = " + values.get(key.get()));
                found.add(key.get());
                continue;
            }
            rows.add(line);
            open = stillOpen(line, open);
        }
        for (String key : values.sequencedKeySet().reversed())
            if (!found.contains(key)) rows.addFirst(key + " = " + values.get(key));

        List<String> out = new ArrayList<>(lines.subList(0, header + 1));
        out.addAll(rows);
        out.addAll(lines.subList(end, lines.size()));
        return String.join("\n", out) + "\n";
    }

    /// The bare key a line sets, such as `enabled` in `enabled = true`.
    private static Optional<String> keyOf(String line) {
        int equals = line.indexOf('=');
        if (equals < 0) return Optional.empty();
        String key = line.substring(0, equals).strip();
        return key.matches("[A-Za-z0-9_-]+") ? Optional.of(key) : Optional.empty();
    }

    /// The delimiter of the multi-line string still open at the end of `line`, given the one open
    /// at its start; empty when none is.
    private static String stillOpen(String line, String open) {
        int at = 0;
        while (true) {
            if (!open.isEmpty()) {
                int close = line.indexOf(open, at);
                if (close < 0) return open;
                at = close + 3;
                open = "";
            } else {
                int literal = line.indexOf("'''", at);
                int basic = line.indexOf("\"\"\"", at);
                if (literal < 0 && basic < 0) return "";
                int first = literal < 0 ? basic : basic < 0 ? literal : Math.min(literal, basic);
                open = line.substring(first, first + 3);
                at = first + 3;
            }
        }
    }

    /// `text` as a TOML string that reads back exactly: between triple single quotes, so that a
    /// person reads it as written, unless it holds what those cannot, and then escaped.
    static String text(String text) {
        boolean literal = !text.contains("'''") && !text.endsWith("'")
                && text.chars().noneMatch(c -> (c < 0x20 && c != '\n' && c != '\t') || c == 0x7F);
        if (literal) return "'''\n" + text + "'''";
        StringBuilder escaped = new StringBuilder("\"");
        text.chars().forEach(c -> escaped.append(switch (c) {
            case '\\' -> "\\\\";
            case '"'  -> "\\\"";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            default   -> c < 0x20 || c == 0x7F ? String.format("\\u%04X", c) : String.valueOf((char) c);
        }));
        return escaped.append('"').toString();
    }
}
