package dev.gui.model;

import java.util.Locale;
import java.util.Optional;

import sprouts.Tuple;

/// What this computer has for running a model: its memory and, if it has one a model can run on,
/// its graphics card. From these follow how well a model would run here, and which model Genies
/// suggests.
///
/// Both are estimates, as good as the [ModelFacts] they start from. A model runs where all of
/// it but its n-gram tables fits, with the keys and values of its context and what Ollama itself
/// needs: on the graphics card, on the graphics card and the processor together, or on the
/// processor alone. Its n-gram tables fit wherever there is room left, on the disk at the worst,
/// for it looks only a few rows up in them for each token. Writing a token reads every active
/// parameter once, so how quickly a model answers follows from how many gigabytes that is, and
/// from where they are: a graphics card reads its own memory many times faster than a processor.
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
        /// It is too large for the graphics card alone: Ollama puts as much of it there as fits,
        /// and runs the rest on the processor.
        PARTLY_ON_GRAPHICS,
        /// It fits in the computer's memory, and the processor runs it.
        ON_PROCESSOR,
        /// It needs more memory than the computer can spare.
        TOO_LARGE,
        /// Nothing says how large it is.
        UNKNOWN
    }

    /// How a model would run here.
    ///
    /// @param size         the gigabytes it takes, to download and on the disk; 0 when unknown
    /// @param need         the gigabytes of memory it needs while it runs, with its context, but
    ///                     without its n-gram tables
    /// @param runs         where it runs
    /// @param pace         whether it answers at a fair pace: what it reads for each token is
    ///                     on the graphics card, or few enough gigabytes for the processor
    /// @param tablesOnDisk whether its n-gram tables find no room in memory, and stay on the disk
    /// @param source       where the facts it was judged by came from
    public record Fit(double size, double need, Runs runs, boolean pace, boolean tablesOnDisk, ModelFacts.Source source) {}

    /// The share of the computer's memory a model may take. The rest is for the system, Genies,
    /// and the genies' sandboxes.
    private static final double MEMORY_SHARE = 0.65;

    /// The share of a graphics card's memory a model may take.
    private static final double GRAPHICS_SHARE = 0.9;

    /// A card with less memory of its own than this shares the computer's, and runs nothing faster.
    public static final long LEAST_GRAPHICS_MEMORY = 4L << 30;

    /// On the processor, a model answers at a fair pace when it reads at most this many
    /// gigabytes for each token: about eight billion parameters in Ollama's usual quantisation.
    private static final double FAIR_PACE = 5.0;

    /// The gigabytes Ollama needs beside a model's weights and context, for its own work.
    private static final double OLLAMA_ITSELF = 1.0;

    public static final Hardware UNKNOWN = new Hardware(0, Optional.empty());

    private static final double GIGABYTE = 1L << 30;

    /// How `model`, as Ollama names it, would run here, judged by what Genies knows of it
    /// without asking anyone ([ModelFacts#of]).
    public Fit fit(String model) {
        return fit(ModelFacts.of(model));
    }

    /// How a model made as `facts` say would run here, with Genies' context of
    /// [OllamaSetup#CONTEXT] tokens.
    public Fit fit(ModelFacts facts) {
        if (!facts.isSized()) return new Fit(0, 0, Runs.UNKNOWN, false, false, facts.source());
        double size = facts.gigabytes();
        double working = Math.max(0, size - facts.tableGigabytes());
        // Without the model's own numbers, what its context takes grows with its size, as it
        // does for most models: about 4 GB for qwen3:8b.
        double context = facts.cacheGigabytes(OllamaSetup.CONTEXT).orElse(working * 0.25 + 2);
        double need = working + context + OLLAMA_ITSELF;
        double card = graphics.map(it -> it.memory() / GIGABYTE * GRAPHICS_SHARE).orElse(0.0);
        double ours = memory / GIGABYTE * MEMORY_SHARE;
        Runs runs = card > 0 && need <= card ? Runs.ON_GRAPHICS
                  : card > 0 && need <= card + ours ? Runs.PARTLY_ON_GRAPHICS
                  : need <= ours ? Runs.ON_PROCESSOR
                  : Runs.TOO_LARGE;
        // What the processor reads for each token: none of it on the graphics card, all of it on
        // the processor, and as large a share as is left off the card in between.
        double onProcessor = switch (runs) {
            case ON_GRAPHICS -> 0;
            case PARTLY_ON_GRAPHICS -> facts.gigabytesEachToken() * (need - card) / need;
            default -> facts.gigabytesEachToken();
        };
        double room = switch (runs) {
            case ON_GRAPHICS -> ours;
            case PARTLY_ON_GRAPHICS -> ours - (need - card);
            default -> ours - need;
        };
        boolean tablesOnDisk = facts.tableGigabytes() > 0 && facts.tableGigabytes() > Math.max(0, room);
        return new Fit(size, need, runs, onProcessor <= FAIR_PACE, tablesOnDisk, facts.source());
    }

    /// The model Genies suggests for this computer: the best it knows ([ModelFacts#known]) that
    /// runs on its graphics card, which answers far faster; otherwise the best that runs at a
    /// fair pace, partly on the graphics card or on the processor; the smallest, if none does.
    public String suggested() {
        Tuple<String> known = ModelFacts.known();
        for (String model : known)
            if (fit(model).runs() == Runs.ON_GRAPHICS) return model;
        for (String model : known) {
            Fit fit = fit(model);
            if ((fit.runs() == Runs.PARTLY_ON_GRAPHICS || fit.runs() == Runs.ON_PROCESSOR) && fit.pace()) return model;
        }
        return known.last();
    }

    /// The computer, in a few words: "64 GB of memory, and no graphics card a model can run on."
    public String words(Lang lang) {
        Lang.SettingsPage words = lang.SETTINGS_PAGE();
        if (memory == 0) return words.GENIES_COULD_NOT_READ_HOW_MUCH_MEMORY_THIS_COMPUTER_HAS();
        String ours = gigabytes(memory / (double) (1L << 30));
        return graphics
            .map(card -> words.MEMORY_AND_THE_GRAPHICS_CARD(ours, card.name(), gigabytes(card.memory() / (double) (1L << 30))))
            .orElse(words.MEMORY_AND_NO_GRAPHICS_CARD_A_MODEL_CAN_RUN_ON(ours));
    }

    /// How `model` would run here, in a sentence or two, judged by what Genies knows of it
    /// without asking anyone.
    public String words(String model, Lang lang) {
        return words(ModelFacts.of(model), lang);
    }

    /// How a model made as `facts` say would run here, in a sentence or two, and whether that is
    /// only guessed from its name.
    public String words(ModelFacts facts, Lang lang) {
        Lang.SettingsPage words = lang.SETTINGS_PAGE();
        Fit fit = fit(facts);
        String size = words.ABOUT_SO_MUCH_TO_DOWNLOAD(gigabytes(fit.size())) + " ";
        String runs = switch (fit.runs()) {
            case UNKNOWN -> words.ITS_NAME_DOES_NOT_SAY_HOW_LARGE_IT_IS();
            case ON_GRAPHICS -> size + words.IT_RUNS_ON_THE_GRAPHICS_CARD_SO_IT_ANSWERS_QUICKLY();
            case PARTLY_ON_GRAPHICS -> size + (fit.pace() ? words.IT_RUNS_PARTLY_ON_THE_GRAPHICS_CARD_AT_A_FAIR_PACE()
                                                          : words.IT_RUNS_PARTLY_ON_THE_GRAPHICS_CARD_SO_IT_ANSWERS_SLOWLY());
            case ON_PROCESSOR -> size + (fit.pace() ? words.IT_RUNS_ON_THE_PROCESSOR_AT_A_FAIR_PACE()
                                                    : words.IT_RUNS_ON_THE_PROCESSOR_SO_IT_ANSWERS_SLOWLY());
            case TOO_LARGE -> size + words.RUNNING_IT_NEEDS_MORE_MEMORY_THAN_THIS_COMPUTER_CAN_SPARE(gigabytes(fit.need()));
        };
        if (fit.tablesOnDisk() && fit.runs() != Runs.TOO_LARGE) runs += " " + words.ITS_N_GRAM_TABLES_STAY_ON_THE_DISK();
        if (fit.source() == ModelFacts.Source.NAME) runs += " " + words.GUESSED_FROM_ITS_NAME();
        return runs;
    }

    /// "5.2 GB", or "19 GB" from ten on.
    private static String gigabytes(double amount) {
        return (amount < 10 ? String.format(Locale.ROOT, "%.1f", amount) : String.valueOf(Math.round(amount))) + " GB";
    }
}
