package dev.gui.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// What Genies learned from ollama.com about the models Ollama offers, and about the newest
/// Ollama: the models a genie can use, their variants, and what some of them are made of.
///
/// Nothing on ollama.com is promised to stay as it is, so all of it is a bonus. Without it,
/// Genies offers the models it knows itself ([#BUILT_IN]); with an older answer it kept, that
/// one ([Source#KEPT]). The pages of ollama.com are read by [#offersIn] and [#variantsIn], which
/// take what they recognise and leave the rest.
///
/// @param offers   the models a genie can use that run on a computer of its own, most pulled first
/// @param variants the variants of some models, such as `qwen3:8b-q8_0`, and what each downloads,
///                 as asked for one model at a time
/// @param facts    what some models are made of, as their files and Ollama's registry say
/// @param newest   the newest Ollama's version, such as `0.40.17`; empty while not known
/// @param source   where [#offers] came from
/// @param fetched  when ollama.com gave the offers, if it did
/// @param asking   whether Genies asks ollama.com for its newest models just now
public record OllamaLibrary(
    Tuple<Offer> offers,
    Tuple<Variant> variants,
    Tuple<ModelFacts> facts,
    String newest,
    Source source,
    Optional<Instant> fetched,
    boolean asking
) {

    /// A model on ollama.com that can use tools, as a genie must, and runs on a computer of its own.
    ///
    /// @param name  as Ollama names it without a tag, such as `qwen3`
    /// @param sizes the sizes it comes in, as tags, such as `8b` or `30b-a3b`; empty for one with
    ///              only its `latest`
    /// @param pulls how often it was downloaded, as far as ollama.com says; 0 if it does not
    public record Offer(String name, Tuple<String> sizes, long pulls) {
        /// Its variants in Ollama's usual quantisation: one for each size, or its name alone.
        public Tuple<String> models() {
            return sizes.isEmpty() ? Tuple.of(String.class, name) : sizes.mapTo(String.class, size -> name + ":" + size);
        }
    }

    /// A variant of a model, as ollama.com lists them, such as `qwen3:8b-q8_0`, and the bytes
    /// Ollama downloads for it, or 0 if the page does not say.
    public record Variant(String model, long download) {}

    /// Where the offers came from.
    public enum Source {
        /// ollama.com, just now.
        OLLAMA_COM,
        /// An answer of ollama.com that Genies kept from before.
        KEPT,
        /// Genies' own list of the models it knows ([ModelFacts#known]).
        BUILT_IN
    }

    /// The models Genies knows itself, before it has heard from ollama.com.
    public static final OllamaLibrary BUILT_IN = new OllamaLibrary(
        ModelFacts.known().mapTo(Offer.class, model -> {
            String[] parts = model.split(":", 2);
            return new Offer(parts[0], parts.length > 1 ? Tuple.of(String.class, parts[1]) : Tuple.of(String.class), 0);
        }),
        Tuple.of(Variant.class),
        Tuple.of(ModelFacts.class),
        "",
        Source.BUILT_IN,
        Optional.empty(),
        false
    );

    /// With fewer models than this on its page, ollama.com is taken to have changed its page
    /// beyond what [#offersIn] recognises, rather than to offer so few.
    public static final int FEWEST_OFFERS = 5;

    /// These, with the offers `offers` from `source`, given at `fetched`.
    public OllamaLibrary withOffers(Tuple<Offer> offers, Source source, Optional<Instant> fetched) {
        return new OllamaLibrary(offers, variants, facts, newest, source, fetched, asking);
    }

    /// These, while Genies asks ollama.com for its newest models, or once it has, as `asking` says.
    public OllamaLibrary asking(boolean asking) {
        return new OllamaLibrary(offers, variants, facts, newest, source, fetched, asking);
    }

    /// These, with the variants `found` of a model in place of those known before.
    public OllamaLibrary withVariants(String model, Tuple<Variant> found) {
        String name = nameOf(model);
        return new OllamaLibrary(offers, variants.removeIf(it -> nameOf(it.model()).equals(name)).addAll(found), facts, newest, source, fetched, asking);
    }

    /// These, with what `learned` says a model is made of in place of what was known before.
    public OllamaLibrary withFacts(ModelFacts learned) {
        return new OllamaLibrary(offers, variants, facts.removeIf(it -> it.model().equals(learned.model())).add(learned), newest, source, fetched, asking);
    }

    /// These, knowing that the newest Ollama is `version`.
    public OllamaLibrary withNewest(String version) {
        return new OllamaLibrary(offers, variants, facts, version, source, fetched, asking);
    }

    /// The models offered in Ollama's usual quantisation, most pulled first.
    public Tuple<String> models() {
        Tuple<String> all = Tuple.of(String.class);
        for (Offer offer : offers.sort((a, b) -> Long.compare(b.pulls(), a.pulls()))) all = all.addAll(offer.models());
        return all;
    }

    /// The variants known of the model `model`, whatever its tag.
    public Tuple<String> variantsOf(String model) {
        String name = nameOf(model);
        return variants.retainIf(it -> nameOf(it.model()).equals(name)).mapTo(String.class, Variant::model);
    }

    /// Whether the variants of the model `model` were asked for.
    public boolean knowsVariantsOf(String model) {
        String name = nameOf(model);
        return variants.any(it -> nameOf(it.model()).equals(name));
    }

    /// What Genies knows `model` is made of: what its file or Ollama's registry said, with
    /// what its download on ollama.com's page, Genies' own list and its name add.
    public ModelFacts factsOf(String model) {
        String name = model.strip();
        ModelFacts guessed = ModelFacts.of(name);
        for (Variant variant : variants)
            if (variant.model().equalsIgnoreCase(name) && variant.download() > 0)
                guessed = new ModelFacts(name, 0, 0, 0, 0, variant.download(), 0, ModelFacts.Source.LIBRARY).or(guessed);
        for (ModelFacts known : facts)
            if (known.model().equalsIgnoreCase(name)) return known.or(guessed);
        return guessed;
    }

    /// Whether Genies learned from the model's file or the registry what `model` is made of.
    public boolean learned(String model) {
        return facts.any(it -> it.model().equalsIgnoreCase(model.strip()));
    }

    // ─── reading ollama.com ────────────────────────────────────────────────────────────────

    /// Where each model begins on ollama.com/library: a link to its page.
    private static final Pattern MODEL_LINK = Pattern.compile("href=\"/library/([a-zA-Z0-9._-]+)\"");

    /// A label of a model on ollama.com/library, such as its capability `tools` or its size `8b`.
    private static final Pattern LABEL = Pattern.compile("<span[^>]*>\\s*([^<>]{1,40}?)\\s*</span>");

    /// How often a model was pulled, on ollama.com/library, such as `70.4K`, just before "Pulls".
    private static final Pattern PULLS = Pattern.compile("([\\d.,]+)\\s*([KMB]?)\\s*</span>\\s*<span[^>]*>(?:&nbsp;|\\s)*Pulls");

    /// A size among a model's labels: `8b`, `0.6b`, `270m`, `30b-a3b`, `e4b`, `8x7b`. Always in
    /// small letters, unlike how often a model was pulled, such as `121.3M`.
    private static final Pattern SIZE_LABEL = Pattern.compile("(?:\\d+x)?e?\\d+(?:\\.\\d+)?[bm](?:-a\\d+(?:\\.\\d+)?b)?");

    /// The models on a page of ollama.com/library, as its HTML `html` lists them, that can use
    /// tools and run on a computer of their own. A model that is offered only in Ollama's cloud,
    /// with no size to download, is left out. What is not recognised of a model is left out too.
    ///
    /// @throws IllegalArgumentException if fewer than [#FEWEST_OFFERS] models were recognised:
    ///         then the page is not what Genies expects, and it keeps what it had
    public static Tuple<Offer> offersIn(String html) {
        Map<String, Offer> found = new LinkedHashMap<>();
        Matcher link = MODEL_LINK.matcher(html);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (link.find()) {
            starts.add(new int[] { link.start() });
            names.add(link.group(1));
        }
        int tools = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int from = starts.get(i)[0];
            int to = i + 1 < names.size() ? starts.get(i + 1)[0] : Math.min(html.length(), from + 20_000);
            String about = html.substring(from, to);
            List<String> sizes = new ArrayList<>();
            boolean canUseTools = false;
            boolean cloud = false;
            Matcher label = LABEL.matcher(about);
            while (label.find()) {
                String text = label.group(1).strip();
                if (text.equalsIgnoreCase("tools")) canUseTools = true;
                else if (text.equalsIgnoreCase("cloud")) cloud = true;
                else if (SIZE_LABEL.matcher(text).matches() && !sizes.contains(text)) sizes.add(text);
            }
            if (canUseTools) tools++;
            if (!canUseTools || found.containsKey(name) || (cloud && sizes.isEmpty())) continue;
            found.put(name, new Offer(name, Tuple.of(String.class, sizes), pullsIn(about)));
        }
        if (tools < FEWEST_OFFERS)
            throw new IllegalArgumentException("ollama.com/library listed " + names.size() + " models, " + tools + " of them with tools, which is not what Genies expects");
        return Tuple.of(Offer.class, new ArrayList<>(found.values()));
    }

    /// A variant on the page of a model's tags on ollama.com, such as `/library/qwen3:8b-q8_0`.
    private static final Pattern VARIANT_LINK = Pattern.compile("href=\"/library/([a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+)\"");

    /// What a variant downloads, as its tags page says: `5.2GB`, `640MB`.
    private static final Pattern DOWNLOAD = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(TB|GB|MB|KB)\\b");

    /// The variants of the model `model` on its tags page on ollama.com, as its HTML `html`
    /// lists them, each with what it downloads if the page says. Variants of other models, and
    /// what is not recognised, are left out.
    ///
    /// @throws IllegalArgumentException if no variant of `model` was recognised
    public static Tuple<Variant> variantsIn(String html, String model) {
        String name = nameOf(model);
        Map<String, Long> found = new LinkedHashMap<>();
        Matcher link = VARIANT_LINK.matcher(html);
        while (link.find()) {
            String variant = link.group(1);
            if (!nameOf(variant).equals(name) || found.containsKey(variant)) continue;
            Matcher download = DOWNLOAD.matcher(html.substring(link.end(), Math.min(html.length(), link.end() + 2_000)));
            found.put(variant, download.find() ? bytes(download.group(1), download.group(2)) : 0L);
        }
        if (found.isEmpty()) throw new IllegalArgumentException("ollama.com listed no variants of " + name + " that Genies recognised");
        List<Variant> variants = new ArrayList<>();
        found.forEach((variant, download) -> variants.add(new Variant(variant, download)));
        return Tuple.of(Variant.class, variants);
    }

    /// The model's name without its tag: `qwen3` of `qwen3:8b`.
    public static String nameOf(String model) {
        String name = model.strip().toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(0, colon);
    }

    private static long pullsIn(String about) {
        Matcher pulls = PULLS.matcher(about);
        if (!pulls.find()) return 0;
        try {
            double count = Double.parseDouble(pulls.group(1).replace(",", ""));
            return Math.round(count * switch (pulls.group(2)) {
                case "K" -> 1e3;
                case "M" -> 1e6;
                case "B" -> 1e9;
                default -> 1;
            });
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    private static long bytes(String amount, String unit) {
        double count = Double.parseDouble(amount);
        // ollama.com counts in thousands, not in powers of two.
        return Math.round(count * switch (unit) {
            case "TB" -> 1e12;
            case "GB" -> 1e9;
            case "MB" -> 1e6;
            default -> 1e3;
        });
    }
}
