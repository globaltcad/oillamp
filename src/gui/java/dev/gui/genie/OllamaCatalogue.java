package dev.gui.genie;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gui.model.ModelFacts;
import dev.gui.model.OllamaLibrary;

import sprouts.Tuple;

/// Asks the internet about Ollama's models, and about the newest Ollama: ollama.com, Ollama's
/// registry, and GitHub, where Ollama is released.
///
/// None of them promises to answer as it does today, so each question is asked in tiers, the best
/// way first. When a way fails, for whatever reason, what went wrong is logged, and the next way
/// is tried. The last way never fails: what Genies knows itself, or nothing. So every method here
/// answers, unless its thread is interrupted, and a broken page or a changed API costs the user
/// only the newer models, never the settings.
///
/// Every method blocks; Genies calls them off Swing's event thread.
public final class OllamaCatalogue {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaCatalogue.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /// Every model ollama.com offers, most pulled first.
    private static final URI LIBRARY = URI.create("https://ollama.com/library");

    /// Where the variants of a model are listed, after its name.
    private static final String TAGS = "https://ollama.com/library/";

    /// Ollama's registry, which Ollama downloads models from.
    private static final String REGISTRY = "https://registry.ollama.ai/v2/";

    /// The newest release of Ollama on GitHub, as JSON.
    private static final URI RELEASES = URI.create("https://api.github.com/repos/ollama/ollama/releases/latest");

    /// Ollama's download page, which names the newest version.
    private static final URI DOWNLOAD_PAGE = URI.create("https://ollama.com/download");

    /// How long an answer may take to begin, once connected; and how long a whole answer may
    /// take, a page, a manifest or a header: one that stalls midway is given up then, rather
    /// than waited for forever.
    private static final Duration ANSWERING = Duration.ofSeconds(20);
    private static final Duration WHOLE_ANSWER = Duration.ofSeconds(60);
    private static final Duration WHOLE_HEADER = Duration.ofMinutes(2);

    /// A model as Ollama names it: an optional namespace, a name and an optional tag.
    private static final Pattern MODEL = Pattern.compile("(?:([a-z0-9._-]+)/)?([a-z0-9._-]+)(?::([a-zA-Z0-9._-]+))?");

    /// A version on Ollama's download page, such as `v0.40.17`.
    private static final Pattern VERSION = Pattern.compile("\\bv(\\d+\\.\\d+\\.\\d+)\\b");

    private final Path kept;
    /// The file Genies keeps what it read from the headers of models' files in.
    private final Path headers;
    /// What Genies read from the header of each model's file, by the file's digest, as kept in
    /// [#headers]; read from there the first time it is needed.
    private final Map<String, ModelFacts> read = new ConcurrentHashMap<>();
    private volatile boolean readKept = false;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    /// @param kept    the file Genies keeps ollama.com's last answer in, for when it cannot reach it
    /// @param headers the file Genies keeps what it read from the headers of models' files in,
    ///                so as to read each only once
    public OllamaCatalogue(Path kept, Path headers) {
        this.kept = kept;
        this.headers = headers;
    }

    /// `known`, with the models offered as best Genies can tell: from ollama.com, which is then
    /// kept for the next time; otherwise from what ollama.com said before; otherwise what Genies
    /// knows itself.
    public OllamaLibrary offers(OllamaLibrary known) throws InterruptedException {
        try {
            Tuple<OllamaLibrary.Offer> offers = OllamaLibrary.offersIn(text(get(LIBRARY)));
            Instant now = Instant.now();
            keep(offers, now);
            return known.withOffers(offers, OllamaLibrary.Source.OLLAMA_COM, Optional.of(now));
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not read the models ollama.com offers, so Genies offers those it knew before", failed);
        }
        return kept(known);
    }

    /// `known`, with the models ollama.com offered when Genies last asked, if it kept them;
    /// otherwise with what Genies knows itself.
    public OllamaLibrary kept(OllamaLibrary known) {
        try {
            if (Files.exists(kept)) {
                JsonNode file = JSON.readTree(kept.toFile());
                List<OllamaLibrary.Offer> offers = new ArrayList<>();
                for (JsonNode offer : file.path("offers")) {
                    String name = offer.path("name").asText("");
                    if (name.isBlank()) continue;
                    List<String> sizes = new ArrayList<>();
                    for (JsonNode size : offer.path("sizes")) sizes.add(size.asText());
                    offers.add(new OllamaLibrary.Offer(name, Tuple.of(String.class, sizes), offer.path("pulls").asLong(0)));
                }
                if (offers.size() >= OllamaLibrary.FEWEST_OFFERS)
                    return known.withOffers(Tuple.of(OllamaLibrary.Offer.class, offers), OllamaLibrary.Source.KEPT, fetchedIn(file));
            }
        } catch (IOException | RuntimeException unreadable) {
            LOG.warn("Could not read the models ollama.com offered before, from {}, so Genies offers those it knows itself", kept, unreadable);
        }
        return known.withOffers(OllamaLibrary.BUILT_IN.offers(), OllamaLibrary.Source.BUILT_IN, Optional.empty());
    }

    /// When ollama.com gave the offers kept in `file`, if the file says, and says it readably.
    private static Optional<Instant> fetchedIn(JsonNode file) {
        try {
            return Optional.of(Instant.parse(file.path("fetched").asText("")));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /// The newest Ollama's version, such as `0.40.17`: as GitHub releases it, or as Ollama's
    /// download page names it. Empty when neither says.
    public String newestOllama() throws InterruptedException {
        try {
            String tag = JSON.readTree(text(get(RELEASES))).path("tag_name").asText("").strip();
            if (tag.matches("v?\\d+(\\.\\d+)+")) return tag.startsWith("v") ? tag.substring(1) : tag;
            LOG.warn("GitHub named the newest Ollama '{}', which is not a version", tag);
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not ask GitHub for the newest Ollama", failed);
        }
        try {
            Matcher version = VERSION.matcher(text(get(DOWNLOAD_PAGE)));
            if (version.find()) return version.group(1);
            LOG.warn("Ollama's download page names no version");
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not read Ollama's download page for its newest version", failed);
        }
        return "";
    }

    /// The variants of `model` that ollama.com lists, such as its other quantisations; none
    /// when it cannot be asked, or lists none Genies recognises.
    public Tuple<OllamaLibrary.Variant> variants(String model) throws InterruptedException {
        try {
            Matcher name = parse(model);
            String path = (name.group(1) == null ? "" : name.group(1) + "/") + name.group(2);
            return OllamaLibrary.variantsIn(text(get(URI.create(TAGS + path + "/tags"))), model);
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not read the variants of {} on ollama.com", model, failed);
            return Tuple.of(OllamaLibrary.Variant.class);
        }
    }

    /// What `model` is made of, as exactly as Ollama's registry tells: from the header of its
    /// file, with what the registry says of it filling in; or from the registry alone; or
    /// nothing, when the registry does not know the model, or cannot be asked.
    public Optional<ModelFacts> facts(String model) throws InterruptedException {
        String name = model.strip();
        JsonNode manifest;
        String repository;
        try {
            Matcher parts = parse(name);
            repository = (parts.group(1) == null ? "library" : parts.group(1)) + "/" + parts.group(2);
            String tag = parts.group(3) == null ? "latest" : parts.group(3);
            manifest = JSON.readTree(text(send(HttpRequest.newBuilder(URI.create(REGISTRY + repository + "/manifests/" + tag))
                    .header("Accept", "application/vnd.docker.distribution.manifest.v2+json"))));
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not ask Ollama's registry about {}", name, failed);
            return Optional.empty();
        }
        long download = 0;
        String weights = "";
        for (JsonNode layer : manifest.path("layers")) {
            download += Math.max(0, layer.path("size").asLong(0));
            if (layer.path("mediaType").asText().equals("application/vnd.ollama.image.model")) weights = layer.path("digest").asText("");
        }
        ModelFacts registry = new ModelFacts(name, 0, 0, 0, 0, download, 0, ModelFacts.Source.REGISTRY);
        try {
            String config = manifest.path("config").path("digest").asText("");
            if (!config.isEmpty()) {
                JsonNode said = JSON.readTree(text(get(URI.create(REGISTRY + repository + "/blobs/" + config))));
                registry = new ModelFacts(name, billionsIn(said.path("model_type").asText("")), 0, 0,
                        ModelFacts.bitsOf(said.path("file_type").asText("")), download, 0, ModelFacts.Source.REGISTRY);
            }
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not read what Ollama's registry says of {}", name, failed);
        }
        if (weights.isEmpty()) return Optional.of(registry);
        ModelFacts known = keptHeaders().get(weights);
        if (known != null) return Optional.of(new ModelFacts(name, known.billions(), known.active(), known.tables(), known.bits(),
                                                             known.download(), known.cachePerToken(), known.source()).or(registry));
        try {
            HttpResponse<InputStream> file = http.send(
                HttpRequest.newBuilder(URI.create(REGISTRY + repository + "/blobs/" + weights)).timeout(ANSWERING)
                    .header("Range", "bytes=0-" + (GgufHeader.MOST - 1)).build(),
                HttpResponse.BodyHandlers.ofInputStream()
            );
            // Closed once the header is read, which ends the download there, even from a server
            // that ignored the range and sends the whole file.
            try (InputStream body = file.body()) {
                if (file.statusCode() / 100 != 2) throw new IOException("Ollama's registry answered " + file.statusCode());
                // A download that stalls is closed from here, which ends the read waiting on it.
                Thread watchdog = Thread.ofVirtual().name("header deadline").start(() -> {
                    try {
                        Thread.sleep(WHOLE_HEADER);
                        body.close();
                    } catch (InterruptedException | IOException done) {
                        // Read in time, or closed already.
                    }
                });
                try {
                    ModelFacts header = GgufHeader.read(name, body, download);
                    keepHeader(weights, header);
                    return Optional.of(header.or(registry));
                } finally {
                    watchdog.interrupt();
                }
            }
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not read the header of the file of {}, so Genies goes by what the registry says of it", name, failed);
            return Optional.of(registry);
        }
    }

    /// The billions of parameters in a size as Ollama's registry gives it, such as `8.2B` or
    /// `567M`; 0 for anything else.
    static double billionsIn(String size) {
        Matcher it = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([BMK])", Pattern.CASE_INSENSITIVE).matcher(size.strip());
        if (!it.matches()) return 0;
        double count = Double.parseDouble(it.group(1));
        return switch (it.group(2).toUpperCase(java.util.Locale.ROOT)) {
            case "B" -> count;
            case "M" -> count / 1e3;
            default -> count / 1e6;
        };
    }

    private void keep(Tuple<OllamaLibrary.Offer> offers, Instant fetched) {
        try {
            ObjectNode file = JSON.createObjectNode().put("fetched", fetched.toString());
            ArrayNode list = file.putArray("offers");
            for (OllamaLibrary.Offer offer : offers) {
                ObjectNode it = list.addObject().put("name", offer.name()).put("pulls", offer.pulls());
                ArrayNode sizes = it.putArray("sizes");
                for (String size : offer.sizes()) sizes.add(size);
            }
            write(kept, file);
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not keep the models ollama.com offers in {}", kept, failed);
        }
    }

    /// What was read from the headers of models' files before, by digest.
    private Map<String, ModelFacts> keptHeaders() {
        if (readKept) return read;
        synchronized (read) {
            if (readKept) return read;
            try {
                if (Files.exists(headers))
                    for (JsonNode it : JSON.readTree(headers.toFile()).path("headers")) {
                        String digest = it.path("digest").asText("");
                        ModelFacts facts = new ModelFacts(it.path("model").asText(""), it.path("billions").asDouble(0), it.path("active").asDouble(0),
                                it.path("tables").asDouble(0), 0, it.path("download").asLong(0), it.path("cachePerToken").asLong(0), ModelFacts.Source.FILE_HEADER);
                        if (!digest.isEmpty() && facts.billions() > 0) read.put(digest, facts);
                    }
            } catch (IOException | RuntimeException unreadable) {
                LOG.warn("Could not read what Genies kept of the headers of models' files, from {}; it reads them again", headers, unreadable);
            }
            // Only once all of it is in: until then, others wait above rather than miss some.
            readKept = true;
            return read;
        }
    }

    private void keepHeader(String digest, ModelFacts facts) {
        keptHeaders().put(digest, facts);
        synchronized (read) {
            try {
                ObjectNode file = JSON.createObjectNode();
                ArrayNode list = file.putArray("headers");
                read.forEach((key, it) -> list.addObject().put("digest", key).put("model", it.model()).put("billions", it.billions())
                        .put("active", it.active()).put("tables", it.tables()).put("download", it.download()).put("cachePerToken", it.cachePerToken()));
                write(headers, file);
            } catch (IOException | RuntimeException failed) {
                LOG.warn("Could not keep what Genies read of the header of {} in {}", facts.model(), headers, failed);
            }
        }
    }

    /// Writes `json` to `file` whole, or not at all.
    private static void write(Path file, ObjectNode json) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(partial, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json), StandardCharsets.UTF_8);
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Matcher parse(String model) {
        Matcher parts = MODEL.matcher(model.strip());
        if (!parts.matches()) throw new IllegalArgumentException("'" + model + "' is not a model's name as Ollama names it");
        return parts;
    }

    private HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri));
    }

    /// The whole answer to `request`, within [#WHOLE_ANSWER].
    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpRequest sent = request.timeout(ANSWERING).header("User-Agent", "Genies").build();
        CompletableFuture<HttpResponse<String>> answer = http.sendAsync(sent, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return answer.get(WHOLE_ANSWER.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException tooSlow) {
            answer.cancel(true);
            throw new IOException(sent.uri() + " did not answer whole within " + WHOLE_ANSWER.toSeconds() + " seconds", tooSlow);
        } catch (InterruptedException stopped) {
            answer.cancel(true);
            throw stopped;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException it) throw it;
            throw new IOException("asking " + sent.uri() + " failed", failed.getCause());
        }
    }

    private static String text(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) throw new IOException(response.uri() + " answered " + response.statusCode());
        return response.body();
    }
}
