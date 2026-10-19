package dev.oillamp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// Changes `enabled` and `requirements` under `[host_access]` in the text of a lamp's
/// `oillamp.toml`, and nothing else: every other line, the user's comments among them, stays as
/// it was. What `oillamp host-access <dir> on|off` writes, for an application's settings page.
///
/// This edits lines, it does not parse TOML. So the caller reads the result back with
/// [ConfigLoadingUtil] and keeps it only when it says exactly what was asked; see
/// `CommandExecutionUtil.hostAccess`.
final class HostAccessFileUtil {

    private HostAccessFileUtil() {}

    /// A table's header, such as `[host_access]` or `[[network.rules]]`, with an optional comment.
    private static final Pattern TABLE = Pattern.compile("\\s*\\[\\[?[^\\]\\[]+\\]\\]?\\s*(#.*)?");
    private static final Pattern HOST_ACCESS = Pattern.compile("\\s*\\[\\s*host_access\\s*\\]\\s*(#.*)?");
    private static final Pattern ENABLED = Pattern.compile("\\s*enabled\\s*=.*");
    private static final Pattern REQUIREMENTS = Pattern.compile("\\s*requirements\\s*=.*");

    /// `file` with `enabled` and `requirements` under `[host_access]` set to what is given. A key
    /// that is there is replaced where it is; one that is not goes right under the table's
    /// header; a file without the table gets one at its end.
    static String withHostAccess(String file, Optional<Boolean> enabled, Optional<String> requirements) {
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
                if (HOST_ACCESS.matcher(lines.get(i)).matches()) header = i;
            }
            open = stillOpen(lines.get(i), open);
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.getLast().isBlank()) lines.add("");
            lines.add("[host_access]");
            header = lines.size() - 1;
            end = lines.size();
        }

        // Each key is replaced where it is, the lines of a multi-line value with it.
        List<String> table = new ArrayList<>();
        boolean enabledFound = false;
        boolean requirementsFound = false;
        open = "";
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i);
            if (open.isEmpty() && enabled.isPresent() && ENABLED.matcher(line).matches()) {
                table.add("enabled = " + enabled.get());
                enabledFound = true;
                continue;
            }
            if (open.isEmpty() && requirements.isPresent() && REQUIREMENTS.matcher(line).matches()) {
                for (open = stillOpen(line, ""); !open.isEmpty() && i + 1 < end; ) open = stillOpen(lines.get(++i), open);
                table.add("requirements = " + text(requirements.get()));
                requirementsFound = true;
                continue;
            }
            table.add(line);
            open = stillOpen(line, open);
        }
        if (requirements.isPresent() && !requirementsFound) table.addFirst("requirements = " + text(requirements.get()));
        if (enabled.isPresent() && !enabledFound) table.addFirst("enabled = " + enabled.get());

        List<String> out = new ArrayList<>(lines.subList(0, header + 1));
        out.addAll(table);
        out.addAll(lines.subList(end, lines.size()));
        return String.join("\n", out) + "\n";
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
