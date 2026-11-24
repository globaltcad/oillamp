package dev.gui.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import sprouts.Tuple;

/// What the user looks for in the conversation the chat shows, every place it was found, and
/// which of those places the chat shows them now: the match in focus.
///
/// It searches the texts as the chat shows them, which the chat hands over one per entry, in the
/// order of the conversation: an answer without its Markdown signs, a thought only while it is
/// unfolded. Letters are compared ignoring their case. Matches do not overlap.
///
/// @param query   what the user typed; a blank one finds nothing
/// @param matches every place the query was found, in the order of the conversation
/// @param at      the index in `matches` of the match in focus, or -1 while there are none
public record ChatSearch(String query, Tuple<Match> matches, int at) {

    public static final ChatSearch NONE = new ChatSearch("", Tuple.of(Match.class), -1);

    /// The text of one entry, as the chat shows it; empty for an entry it does not search.
    public record Text(UUID entry, String text) {}

    /// A place where the query was found: from the character `start` to the one before `end`, in
    /// the text of `entry`.
    public record Match(UUID entry, int start, int end) {}

    /// A place in the conversation: the character `at` in the text of `entry`.
    public record Spot(UUID entry, int at) {}

    /// The first character of the conversation, wherever it is.
    public static final Spot START = new Spot(new UUID(0, 0), 0);

    /// `query` in `texts`. The match in focus is the first one at `from` or after it, or the very
    /// first one when none comes after `from`, as a search that went round.
    public static ChatSearch find(String query, Tuple<Text> texts, Spot from) {
        List<Match> found = new ArrayList<>();
        // For each match, the place of its entry in the conversation.
        List<Integer> entryOf = new ArrayList<>();
        Map<UUID, Integer> order = new HashMap<>();
        for (Text text : texts) {
            order.put(text.entry(), order.size());
            if (query.isBlank()) continue;
            String in = text.text();
            int length = query.length();
            int i = 0;
            while (i + length <= in.length()) {
                if (in.regionMatches(true, i, query, 0, length)) {
                    found.add(new Match(text.entry(), i, i + length));
                    entryOf.add(order.size() - 1);
                    i += length;
                } else {
                    i++;
                }
            }
        }
        int at = -1;
        if (!found.isEmpty()) {
            int fromEntry = order.getOrDefault(from.entry(), -1);
            at = 0;
            for (int i = 0; i < found.size(); i++) {
                Match match = found.get(i);
                int entry = entryOf.get(i);
                if (entry > fromEntry || (entry == fromEntry && match.start() >= from.at())) {
                    at = i;
                    break;
                }
            }
        }
        return new ChatSearch(query, Tuple.of(Match.class, found), at);
    }

    /// `query`, which the user typed instead of this one, in `texts`. The search goes on from
    /// where it is: from the match in focus, so that typing more letters stays at the place found
    /// while it still matches; or, while none is in focus, from `near`, such as the place the
    /// chat shows.
    public ChatSearch typed(String query, Tuple<Text> texts, Spot near) {
        return find(query, texts, focused().map(it -> new Spot(it.entry(), it.start())).orElse(near));
    }

    /// The same query in `texts`, which changed, as they do while an answer streams in or a thought
    /// is unfolded. The match in focus stays in focus while it is still there; otherwise the next
    /// match after it is.
    public ChatSearch in(Tuple<Text> texts) {
        return typed(query, texts, START);
    }

    /// The match after the one in focus put in focus; after the last, the first one.
    public ChatSearch next() {
        return matches.isEmpty() ? this : new ChatSearch(query, matches, (at + 1) % matches.size());
    }

    /// The match before the one in focus put in focus; before the first, the last one.
    public ChatSearch previous() {
        return matches.isEmpty() ? this : new ChatSearch(query, matches, (at - 1 + matches.size()) % matches.size());
    }

    /// The match the chat shows the user, unless nothing was found.
    public Optional<Match> focused() {
        return at < 0 ? Optional.empty() : Optional.of(matches.get(at));
    }

    /// Which of the matches is in focus, counted from 1, or 0 while nothing was found.
    public int position() {
        return at + 1;
    }

    /// The matches in the text of each entry with any, in their order: what each row of the chat
    /// marks, looked up rather than picked out of all the matches by every row.
    public Map<UUID, Tuple<Match>> byEntry() {
        Map<UUID, List<Match>> grouped = new HashMap<>();
        for (Match match : matches)
            grouped.computeIfAbsent(match.entry(), _ -> new ArrayList<>()).add(match);
        Map<UUID, Tuple<Match>> byEntry = new HashMap<>();
        grouped.forEach((entry, found) -> byEntry.put(entry, Tuple.of(Match.class, found)));
        return Map.copyOf(byEntry);
    }
}
