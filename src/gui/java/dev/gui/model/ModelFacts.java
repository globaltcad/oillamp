package dev.gui.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// What Genies knows about one Ollama model, as Ollama names it, such as `qwen3:8b`: what it is
/// made of, which decides how well it runs on a computer ([Hardware#fit(ModelFacts)]).
///
/// Genies learns these facts from several sources, each a [Source], the best first. The best one
/// that answered gives a model its facts, and each worse one fills in what the better ones left
/// out ([#or]). The worst, the model's name, always answers, so a model is never without facts,
/// only with rougher ones. A fact no source gave is 0.
///
/// @param model         the model, as Ollama names it
/// @param billions      all of its parameters, in billions
/// @param active        the billions of parameters it reads for each token it writes. Fewer than
///                      [#billions] in a mixture of experts, which reads only the experts it picks
///                      for a token, and in a model with n-gram tables, which reads only the rows
///                      it looks up; 0 when that is not known, and then all of them but its tables
/// @param tables        the billions of parameters in its n-gram tables, as in DeepSeek's Engram:
///                      memory it looks a few rows up in for each token, rather than reading it
///                      whole. They can stay in the computer's memory, or even on its disk, while
///                      the rest of the model runs on the graphics card
/// @param bits          the bits each parameter is stored in, on average, such as 5 for Ollama's
///                      usual `Q4_K_M`
/// @param download      the bytes Ollama downloads for it
/// @param cachePerToken the bytes each token of its context takes in memory while it runs: the
///                      keys and values its attention keeps
/// @param source        where the best of these facts came from
public record ModelFacts(
    String model,
    double billions,
    double active,
    double tables,
    double bits,
    long download,
    long cachePerToken,
    Source source
) {

    /// Where Genies learned what a model is made of, the most exact first.
    public enum Source {
        /// The header of the model's file, read from Ollama's registry: every tensor, the
        /// experts and the n-gram tables among them, and what its attention keeps.
        FILE_HEADER,
        /// Ollama's registry: what it downloads, and the size and quantisation Ollama gives it.
        REGISTRY,
        /// The page of the model's variants on ollama.com: what each downloads.
        LIBRARY,
        /// Genies' own list of models it knows.
        KNOWN,
        /// The model's name, such as `30b-a3b` for 30 billion parameters, 3 billion active.
        NAME,
        /// Nothing: its name does not say how large it is.
        NOTHING;

        /// Whether the facts come from the model itself, rather than from what Genies guesses.
        public boolean isExact() { return this == FILE_HEADER || this == REGISTRY; }
    }

    /// A model Genies knows, which it also suggests from.
    private record Known(String model, double billions, double active) {}

    /// Models Ollama has that can use tools, as a genie must, best first. Checked against
    /// ollama.com/library in October 2026. Genies suggests from them, and falls back on them when
    /// it cannot reach ollama.com.
    private static final Tuple<Known> KNOWN = Tuple.of(Known.class,
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
    /// The numbers are a few digits at most: a name such as `99999999999x7b` says no size.
    private static final Pattern SIZE = Pattern.compile("(?:(\\d{1,4})x)?(\\d{1,5}(?:\\.\\d{1,3})?)([bm])");

    /// The parameters that work on each token, in a name such as `qwen3:30b-a3b`, or the
    /// "effective" ones of a name such as `gemma3n:e4b`.
    private static final Pattern ACTIVE = Pattern.compile("[ae](\\d{1,5}(?:\\.\\d{1,3})?)b");

    /// The quantisation in a name, such as `q4_K_M`, `q8_0`, `iq4_xs`, `fp16` or `mxfp4`.
    private static final Pattern QUANTISATION = Pattern.compile("i?q\\d.*|fp16|bf16|f16|fp32|f32|mxfp4");

    /// The bits a parameter takes on average in Ollama's usual quantisation, `Q4_K_M`, with
    /// the few tensors it keeps more exactly.
    public static final double USUAL_BITS = 5.0;

    /// In a mixture of experts whose name says only how many experts of what size, such as
    /// `mixtral:8x7b`, the experts it picks for each token.
    private static final int USUAL_EXPERTS_USED = 2;

    /// What `model` is made of, as far as Genies knows without asking anyone: from its own list,
    /// and otherwise from the model's name.
    public static ModelFacts of(String model) {
        String name = model.strip();
        ModelFacts named = fromName(name);
        for (Known known : KNOWN)
            if (known.model().equals(normal(name)))
                return new ModelFacts(name, known.billions(), known.active(), 0, 0, 0, 0, Source.KNOWN).or(named);
        return named;
    }

    /// The models Genies knows, best first.
    public static Tuple<String> known() {
        return KNOWN.mapTo(String.class, Known::model);
    }

    /// What the name of `model` says it is made of: the first size in it, the parameters it says
    /// are active, and the quantisation it names. With no size in it, nothing is known.
    static ModelFacts fromName(String model) {
        try {
            return sizedByName(model.strip());
        } catch (NumberFormatException unreadable) {
            // A name is anything the user typed: one Genies cannot read says nothing.
            return new ModelFacts(model.strip(), 0, 0, 0, 0, 0, 0, Source.NOTHING);
        }
    }

    private static ModelFacts sizedByName(String name) {
        double billions = 0;
        double active = 0;
        double bits = 0;
        for (String part : PARTS.splitAsStream(normal(name)).toList()) {
            Matcher size = SIZE.matcher(part);
            Matcher few = ACTIVE.matcher(part);
            if (billions == 0 && size.matches()) {
                double each = Double.parseDouble(size.group(2)) / (size.group(3).equals("m") ? 1000 : 1);
                if (size.group(1) == null) billions = each;
                else {
                    int experts = Integer.parseInt(size.group(1));
                    billions = experts * each;
                    active = Math.min(experts, USUAL_EXPERTS_USED) * each;
                }
            }
            else if (few.matches()) active = Double.parseDouble(few.group(1));
            else if (QUANTISATION.matcher(part).matches()) bits = bitsOf(part);
        }
        // A name such as `gemma3n:e4b` says only the parameters that work on each token: as many
        // as it takes at the least.
        if (billions == 0) billions = active;
        return billions == 0
            ? new ModelFacts(name, 0, 0, 0, bits, 0, 0, Source.NOTHING)
            : new ModelFacts(name, billions, active, 0, bits, 0, 0, Source.NAME);
    }

    /// The bits a parameter takes on average in the quantisation `quantisation`, as Ollama and
    /// llama.cpp name them, such as `Q4_K_M`, `q8_0` or `F16`; 0 for one Genies does not know.
    public static double bitsOf(String quantisation) {
        String it = quantisation.strip().toLowerCase(Locale.ROOT);
        if (it.equals("mxfp4")) return 4.25;
        if (it.matches("f(p)?16|bf16")) return 16;
        if (it.matches("f(p)?32")) return 32;
        Matcher q = Pattern.compile("i?q(\\d)(?:_(.*))?").matcher(it);
        if (!q.matches()) return 0;
        int bits = Integer.parseInt(q.group(1));
        String rest = Optional.ofNullable(q.group(2)).orElse("");
        // A block of 32 parameters keeps a scale of its own, and some an offset; the K
        // quantisations keep a few tensors more exactly than their name says.
        return switch (bits) {
            case 2 -> 3.0;
            case 3 -> rest.contains("s") || rest.contains("xxs") ? 3.5 : 3.9;
            case 4 -> rest.startsWith("0") ? 4.5 : rest.startsWith("1") ? 5.0 : rest.contains("xs") || rest.contains("nl") ? 4.4 : USUAL_BITS;
            case 5 -> rest.startsWith("0") ? 5.5 : 5.7;
            case 6 -> 6.6;
            case 8 -> 8.5;
            default -> bits + 0.5;
        };
    }

    /// These facts, with each one they do not know taken from `lesser`, the facts of a worse source.
    public ModelFacts or(ModelFacts lesser) {
        return new ModelFacts(
            model,
            billions > 0 ? billions : lesser.billions(),
            active > 0 ? active : lesser.active(),
            tables > 0 ? tables : lesser.tables(),
            bits > 0 ? bits : lesser.bits(),
            download > 0 ? download : lesser.download(),
            cachePerToken > 0 ? cachePerToken : lesser.cachePerToken(),
            source.compareTo(lesser.source()) <= 0 ? source : lesser.source()
        );
    }

    /// Whether Genies knows how large the model is.
    public boolean isSized() { return billions > 0 || download > 0; }

    /// The bits each parameter takes, as far as known: from what it downloads, from the
    /// quantisation it names, or Ollama's usual one.
    public double bitsEach() {
        if (download > 0 && billions > 0) return download * 8.0 / (billions * 1e9);
        return bits > 0 ? bits : USUAL_BITS;
    }

    /// The gigabytes it takes, to download and on the disk.
    public double gigabytes() {
        return download > 0 ? download / GIGABYTE : billions * 1e9 * bitsEach() / 8 / GIGABYTE;
    }

    /// The gigabytes of its n-gram tables, which need not be on the graphics card, nor even in
    /// the computer's memory.
    public double tableGigabytes() {
        return tables * 1e9 * bitsEach() / 8 / GIGABYTE;
    }

    /// The gigabytes it reads for each token it writes. Writing is about as fast as the memory
    /// it is in can be read, so this decides how quickly it answers.
    public double gigabytesEachToken() {
        double working = active > 0 ? active : billions - tables;
        return Math.max(0, working) * 1e9 * bitsEach() / 8 / GIGABYTE;
    }

    /// The gigabytes its context of `tokens` tokens takes in memory, or empty when not known.
    public Optional<Double> cacheGigabytes(int tokens) {
        return cachePerToken > 0 ? Optional.of(cachePerToken * (double) tokens / GIGABYTE) : Optional.empty();
    }

    private static final double GIGABYTE = 1L << 30;

    private static String normal(String model) {
        return model.strip().toLowerCase(Locale.ROOT);
    }
}
