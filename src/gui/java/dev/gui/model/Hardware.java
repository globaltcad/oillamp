package dev.gui.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// What this computer has for running a model: its memory and, if it has one a model can run on,
/// its graphics card. From these follow how well a model would run here, and which model Genies
/// suggests.
///
/// Both are rough estimates. A model's size is read from its name, as Ollama names its models,
/// such as `qwen3:8b` for eight billion parameters, and `q8_0` or `fp16` for more bits for each.
///
/// @param memory   the computer's memory, in bytes; 0 when it could not be read
/// @param graphics the graphics card a model can run on: an NVIDIA card, or an AMD card with
///                 memory of its own. A card that shares the computer's memory counts as none
public record Hardware(long memory, Optional<Graphics> graphics) {

    /// A graphics card.
    ///
    /// @param name   what the card is called, such as `NVIDIA GeForce RTX 4070`
    /// @param memory its own memory, in bytes
    public record Graphics(String name, long memory) {}

    /// Where a model runs on this computer.
    public enum Runs {
        /// All of it fits on the graphics card.
        ON_GRAPHICS,
        /// It fits in the computer's memory, and the processor runs it.
        ON_PROCESSOR,
        /// It needs more memory than the computer can spare.
        TOO_LARGE,
        /// Its name does not say how large it is.
        UNKNOWN
    }

    /// How a model would run here.
    ///
    /// @param size the gigabytes it takes, to download and in memory; 0 when unknown
    /// @param need the gigabytes of memory it needs while it runs, with its context
    /// @param runs where it runs
    /// @param pace whether it answers at a fair pace on the processor: it works with few enough
    ///             parameters for each word
    public record Fit(double size, double need, Runs runs, boolean pace) {}

    /// A model the suggestion picks from.
    ///
    /// @param billions its parameters, in billions
    /// @param active   how many of them work on each word, in billions: fewer than all for a
    ///                 model made of experts, which then runs faster than its size
    private record Known(String model, double billions, double active) {}

    /// Models Ollama has that can use tools, as a genie must, best first. Checked against
    /// ollama.com/library in October 2026. A model that does not fit the computer is passed over,
    /// and so is one that would be slow on the processor.
    private static final Tuple<Known> SUGGESTIONS = Tuple.of(Known.class,
            new Known("qwen3:32b", 32, 32),
            new Known("qwen3:30b-a3b", 30, 3),
            new Known("gpt-oss:20b", 20, 3.6),
            new Known("qwen3:14b", 14, 14),
            new Known("qwen3:8b", 8, 8),
            new Known("qwen3:4b", 4, 4),
            new Known("qwen3:1.7b", 1.7, 1.7));

    /// What a model's name is split at into its parts: `qwen3:30b-a3b` into `qwen3`, `30b`, `a3b`.
    private static final Pattern PARTS = Pattern.compile("[:/-]");

    /// A size in a model's name: `8b`, `1.7b`, `270m`, or `8x7b` for eight experts of seven.
    private static final Pattern SIZE = Pattern.compile("(?:(\\d+)x)?(\\d+(?:\\.\\d+)?)([bm])");

    /// The parameters that work on each word, in a name such as `qwen3:30b-a3b`.
    private static final Pattern ACTIVE = Pattern.compile("a(\\d+(?:\\.\\d+)?)b");

    /// The bits each parameter is stored in: `q4_K_M`, `q8_0`, `fp16`, `bf16`.
    private static final Pattern BITS = Pattern.compile("q(\\d)(?:_.*)?|(?:fp|bf|f)(16|32)");

    /// The bits Ollama stores a parameter in unless the name says otherwise: its default
    /// quantisation, `q4_K_M`.
    private static final int USUAL_BITS = 4;

    /// The share of the computer's memory a model may take. The rest is for the system, Genies,
    /// and the genies' sandboxes.
    private static final double MEMORY_SHARE = 0.65;

    /// The share of a graphics card's memory a model may take.
    private static final double GRAPHICS_SHARE = 0.9;

    /// A card with less memory of its own than this shares the computer's, and runs nothing faster.
    public static final long LEAST_GRAPHICS_MEMORY = 4L << 30;

    /// On the processor, a model answers at a fair pace when at most this many billions of its
    /// parameters work on each word.
    private static final double FAIR_PACE = 8;

    public static final Hardware UNKNOWN = new Hardware(0, Optional.empty());

    /// How `model`, as Ollama names it, would run here.
    public Fit fit(String model) {
        String name = model.strip().toLowerCase(Locale.ROOT);
        double billions = 0;
        double active = 0;
        int bits = USUAL_BITS;
        for (String part : PARTS.splitAsStream(name).toList()) {
            Matcher size = SIZE.matcher(part);
            Matcher few = ACTIVE.matcher(part);
            Matcher stored = BITS.matcher(part);
            if (billions == 0 && size.matches()) {
                double each = Double.parseDouble(size.group(2)) / (size.group(3).equals("m") ? 1000 : 1);
                billions = size.group(1) == null ? each : Integer.parseInt(size.group(1)) * each;
            } else if (few.matches()) active = Double.parseDouble(few.group(1));
            else if (stored.matches()) bits = Integer.parseInt(stored.group(1) != null ? stored.group(1) : stored.group(2));
        }
        for (Known known : SUGGESTIONS)
            if (known.model().equals(name)) {
                billions = known.billions();
                active = known.active();
            }
        if (billions == 0) return new Fit(0, 0, Runs.UNKNOWN, false);
        // About what Ollama's files weigh: 5.2 GB for qwen3:8b, 9.3 GB for qwen3:14b. Running,
        // a model needs more, for its context and Ollama itself.
        double size = billions * (0.1 + bits * 0.13);
        double need = size * 1.25 + 3;
        double gigabyte = 1L << 30;
        Runs runs = graphics.isPresent() && need <= graphics.get().memory() / gigabyte * GRAPHICS_SHARE ? Runs.ON_GRAPHICS
                  : need <= memory / gigabyte * MEMORY_SHARE ? Runs.ON_PROCESSOR
                  : Runs.TOO_LARGE;
        return new Fit(size, need, runs, (active > 0 ? active : billions) <= FAIR_PACE);
    }

    /// The model Genies suggests for this computer: the best of [#SUGGESTIONS] that runs on its
    /// graphics card, which answers far faster; otherwise the best that runs at a fair pace on
    /// its processor; the smallest, if none does.
    public String suggested() {
        for (Known known : SUGGESTIONS)
            if (fit(known.model()).runs() == Runs.ON_GRAPHICS) return known.model();
        for (Known known : SUGGESTIONS) {
            Fit fit = fit(known.model());
            if (fit.runs() == Runs.ON_PROCESSOR && fit.pace()) return known.model();
        }
        return SUGGESTIONS.last().model();
    }

    /// The models Genies suggests from, best first.
    public static Tuple<String> suggestions() {
        return SUGGESTIONS.mapTo(String.class, Known::model);
    }

    /// The computer, in a few words: "64 GB of memory, and no graphics card a model can run on."
    public String words(Lang lang) {
        Lang.SettingsPage words = lang.SETTINGS_PAGE();
        if (memory == 0) return words.GENIES_COULD_NOT_READ_HOW_MUCH_MEMORY_THIS_COMPUTER_HAS();
        String ours = gigabytes(memory / (double) (1L << 30));
        return graphics
            .map(card -> words.MEMORY_AND_THE_GRAPHICS_CARD().of(ours, card.name(), gigabytes(card.memory() / (double) (1L << 30))))
            .orElse(words.MEMORY_AND_NO_GRAPHICS_CARD_A_MODEL_CAN_RUN_ON().of(ours));
    }

    /// How `model` would run here, in a sentence or two.
    public String words(String model, Lang lang) {
        Lang.SettingsPage words = lang.SETTINGS_PAGE();
        Fit fit = fit(model);
        String size = words.ABOUT_SO_MUCH_TO_DOWNLOAD().of(gigabytes(fit.size())) + " ";
        return switch (fit.runs()) {
            case UNKNOWN -> words.ITS_NAME_DOES_NOT_SAY_HOW_LARGE_IT_IS();
            case ON_GRAPHICS -> size + words.IT_RUNS_ON_THE_GRAPHICS_CARD_SO_IT_ANSWERS_QUICKLY();
            case ON_PROCESSOR -> size + (fit.pace() ? words.IT_RUNS_ON_THE_PROCESSOR_AT_A_FAIR_PACE()
                                                    : words.IT_RUNS_ON_THE_PROCESSOR_SO_IT_ANSWERS_SLOWLY());
            case TOO_LARGE -> size + words.RUNNING_IT_NEEDS_MORE_MEMORY_THAN_THIS_COMPUTER_CAN_SPARE().of(gigabytes(fit.need()));
        };
    }

    /// "5.2 GB", or "19 GB" from ten on.
    private static String gigabytes(double amount) {
        return (amount < 10 ? String.format(Locale.ROOT, "%.1f", amount) : String.valueOf(Math.round(amount))) + " GB";
    }
}
