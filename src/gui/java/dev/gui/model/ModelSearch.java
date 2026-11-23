package dev.gui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import sprouts.Tuple;

/// Which models match what the user types into a field for a model's name, best first: the
/// suggestions the settings show under the field as the user types.
///
/// A model matches when its name holds every word typed, whatever their case: `qwen 8` matches
/// `qwen3:8b`. The model named exactly comes first, then those whose name begins with what was
/// typed, then those of the model family typed, then the rest, each in the order offered.
public final class ModelSearch {

    private ModelSearch() {}

    /// The most models suggested at once: typing more finds the others.
    public static final int MOST = 60;

    /// The models of `offered`, and of `variants`, that match `query`, best first, at most
    /// [#MOST]. A blank query matches every offered model. The `variants`, such as other
    /// quantisations of an offered model, are only suggested once the query names a tag, after
    /// a colon, as in `qwen3:8b-q8`: until then they would crowd out the usual ones.
    public static Tuple<String> matching(String query, Tuple<String> offered, Tuple<String> variants) {
        String typed = query.strip().toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>(offered.toList());
        if (typed.contains(":"))
            for (String variant : variants)
                if (candidates.stream().noneMatch(variant::equalsIgnoreCase)) candidates.add(variant);
        String[] words = typed.isEmpty() ? new String[0] : typed.split("\\s+");
        String family = OllamaLibrary.nameOf(words.length == 0 ? "" : words[0]);
        List<String> found = new ArrayList<>();
        for (String model : candidates) {
            String name = model.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) all &= name.contains(word);
            if (all && found.stream().noneMatch(model::equalsIgnoreCase)) found.add(model);
        }
        found.sort(Comparator.comparingInt(model -> rank(model.toLowerCase(Locale.ROOT), typed, family)));
        return Tuple.of(String.class, found.subList(0, Math.min(MOST, found.size())));
    }

    /// How well `name` matches: 0 for the model named exactly, 1 for one whose name begins with
    /// what was typed, 2 for one of the family typed, 3 for any other.
    private static int rank(String name, String typed, String family) {
        if (typed.isEmpty()) return 3;
        if (name.equals(typed)) return 0;
        if (name.startsWith(typed)) return 1;
        if (!family.isEmpty() && OllamaLibrary.nameOf(name).equals(family)) return 2;
        return 3;
    }
}
