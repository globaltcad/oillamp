package dev.gui.genie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gui.model.Conversations;
import dev.gui.model.Genie;
import dev.gui.model.Palette;
import dev.gui.model.Settings;

import sprouts.Tuple;

/// Where Genies keeps what outlives it, in one directory:
///
/// - `genies.json`: each genie's id and name, in order, and the directory of each lamp Genies
///   did not make, but found lit elsewhere or was given;
/// - `removed-lamps.json`: the lamps from elsewhere the user removed, not to be taken in again;
/// - `settings.json`: the model settings, readable by this user only, because it can hold a key;
/// - `look.json`: how the window looks: the palette the user picked, dark or light; until they
///   pick one, there is none, and the window follows the desktop ([SystemLook]);
/// - `lamps/<id>/`: each genie's lamp, with its home and its conversation in it;
/// - `ollama/`: Ollama, when Genies installed it, and what Ollama says when Genies starts it.
///
/// The directory is `$XDG_DATA_HOME/genies`, which is usually `~/.local/share/genies`.
public final class Shelf {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public Shelf(Path root) { this.root = root; }

    /// The shelf in the user's data directory.
    public static Shelf standard(Function<String, Optional<String>> environment) {
        Path data = environment.apply("XDG_DATA_HOME").filter(value -> !value.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".local", "share"));
        return new Shelf(data.resolve("genies"));
    }

    public Path root() { return root; }

    /// Where Genies writes what went wrong that it did not expect.
    public Path errorLog() { return root.resolve("errors.log"); }

    /// Where Genies installs Ollama.
    public Path ollama() { return root.resolve("ollama"); }

    /// Whether settings were kept before; not the first time Genies starts.
    public boolean hasSettings() { return Files.exists(root.resolve("settings.json")); }

    /// The lamp directory of the genie `id`.
    public Path lampOf(UUID id) { return root.resolve("lamps").resolve(id.toString()); }

    /// The genies there were when Genies last closed, all asleep, with their cards folded. None,
    /// the first time.
    public Tuple<Genie> genies() {
        Tuple<Genie> genies = Tuple.of(Genie.class);
        for (JsonNode entry : read("genies.json").path("genies")) {
            try {
                Optional<Path> elsewhere = entry.hasNonNull("lamp") ? Optional.of(Path.of(entry.path("lamp").asText())) : Optional.empty();
                genies = genies.add(Genie.asleep(UUID.fromString(entry.path("id").asText()), entry.path("name").asText())
                                         .withElsewhere(elsewhere)
                                         .withConversations(Conversations.NONE.withFolded(true)));
            } catch (IllegalArgumentException notAnIdOrAPath) {
                // An entry someone edited by hand into something else is skipped.
            }
        }
        return genies;
    }

    /// Keeps the genies, and the lamp's directory of each whose lamp Genies did not make.
    public void keep(Tuple<Genie> genies) throws IOException {
        ArrayNode list = JSON.createArrayNode();
        for (Genie genie : genies) {
            ObjectNode entry = list.addObject().put("id", genie.id().toString()).put("name", genie.name());
            genie.elsewhere().ifPresent(lamp -> entry.put("lamp", lamp.toString()));
        }
        ObjectNode file = JSON.createObjectNode();
        file.set("genies", list);
        write("genies.json", file);
    }

    /// The lamps from elsewhere the user removed from Genies, by directory: Genies does not take
    /// them in again when it finds them lit, unless the user adds one by hand.
    public Tuple<Path> removedLamps() {
        Tuple<Path> removed = Tuple.of(Path.class);
        for (JsonNode lamp : read("removed-lamps.json").path("lamps")) removed = removed.add(Path.of(lamp.asText()));
        return removed;
    }

    public void keepRemovedLamps(Tuple<Path> removed) throws IOException {
        ArrayNode list = JSON.createArrayNode();
        for (Path lamp : removed) list.add(lamp.toString());
        ObjectNode file = JSON.createObjectNode();
        file.set("lamps", list);
        write("removed-lamps.json", file);
    }

    /// The palette the user last picked, or none, before they ever did.
    public Optional<Palette> palette() {
        return switch (read("look.json").path("palette").asText()) {
            case "LIGHT" -> Optional.of(Palette.LIGHT);
            case "DARK" -> Optional.of(Palette.DARK);
            default -> Optional.empty();
        };
    }

    public void keep(Palette palette) throws IOException {
        write("look.json", JSON.createObjectNode().put("palette", palette.isDark() ? "DARK" : "LIGHT"));
    }

    /// The settings as last kept, or the defaults.
    ///
    /// A file from before a server elsewhere could be chosen still reads. Its "hosted service" is
    /// the top-level `service`, `keySource`, `key` and `model`: Eden AI's settings when the
    /// service is Eden AI, and otherwise those of a server elsewhere, which is what it was.
    public Settings settings() {
        JsonNode file = read("settings.json");
        Settings defaults = Settings.defaults();
        JsonNode local = file.path("local");
        Settings.OnThisMachine onThisMachine = new Settings.OnThisMachine(
                local.path("address").asText(defaults.local().address()), local.path("model").asText(defaults.local().model()));
        if (file.has("edenAi")) {
            JsonNode eden = file.path("edenAi");
            JsonNode elsewhere = file.path("elsewhere");
            return new Settings(placeIn(file.path("place").asText(), defaults.place()),
                    new Settings.EdenAi(keySourceIn(eden), eden.path("key").asText(""),
                                        eden.path("model").asText(defaults.edenAi().model())),
                    new Settings.Elsewhere(elsewhere.path("address").asText(""), elsewhere.path("key").asText(""),
                                           elsewhere.path("model").asText("")),
                    onThisMachine);
        }
        String service = file.path("service").asText(Settings.EDEN_AI_SERVICE);
        String model = file.path("model").asText(defaults.edenAi().model());
        boolean edenAi = Settings.isEdenAi(service);
        boolean chosenHere = file.path("place").asText().equals("THIS_MACHINE");
        return new Settings(chosenHere ? Settings.Place.THIS_MACHINE : edenAi ? Settings.Place.EDEN_AI : Settings.Place.ELSEWHERE,
                edenAi ? new Settings.EdenAi(keySourceIn(file), file.path("key").asText(""), model) : defaults.edenAi(),
                edenAi ? defaults.elsewhere() : new Settings.Elsewhere(service, file.path("key").asText(""), model),
                onThisMachine);
    }

    private static Settings.Place placeIn(String text, Settings.Place otherwise) {
        for (Settings.Place place : Settings.Place.values()) if (place.name().equals(text)) return place;
        return otherwise;
    }

    private static Settings.KeySource keySourceIn(JsonNode settings) {
        return settings.path("keySource").asText().equals("ENTERED") ? Settings.KeySource.ENTERED : Settings.KeySource.ENVIRONMENT;
    }

    /// Keeps the settings, in a file only this user can read. An entered Eden AI key is kept only
    /// while the settings say to use it; a server elsewhere's key is kept while it is entered.
    public void keep(Settings settings) throws IOException {
        Settings.EdenAi eden = settings.edenAi();
        ObjectNode file = JSON.createObjectNode().put("place", settings.place().name());
        ObjectNode edenAi = file.putObject("edenAi")
                .put("keySource", eden.keySource().name())
                .put("model", eden.model().strip());
        if (eden.keySource() == Settings.KeySource.ENTERED) edenAi.put("key", eden.key().strip());
        file.putObject("elsewhere")
                .put("address", settings.elsewhere().address().strip())
                .put("key", settings.elsewhere().key().strip())
                .put("model", settings.elsewhere().model().strip());
        file.putObject("local")
                .put("address", settings.local().address().strip())
                .put("model", settings.local().model().strip());
        write("settings.json", file);
    }

    private JsonNode read(String name) {
        try {
            return JSON.readTree(Files.readString(root.resolve(name), StandardCharsets.UTF_8));
        } catch (IOException missingOrBroken) {   // JacksonException is one too
            return JSON.createObjectNode();
        }
    }

    /// Writes a file whole or not at all, readable by this user only.
    private void write(String name, JsonNode content) throws IOException {
        Files.createDirectories(root);
        Path partial = root.resolve("." + name + ".part");
        Files.deleteIfExists(partial);
        Files.createFile(partial, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(partial, content.toPrettyString() + "\n", StandardCharsets.UTF_8);
        Files.move(partial, root.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
