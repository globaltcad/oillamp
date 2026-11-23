package dev.gui.genie;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import dev.gui.model.ModelFacts;

/// What a model is made of, read from the header of its GGUF file, the format Ollama and
/// llama.cpp keep models in. The header comes first in the file: its metadata, such as how many
/// layers the model has and how many experts, then a line for each tensor, with its name and
/// shape. The weights follow, and are not read.
///
/// From the tensors follow how many parameters the model has, how many of them are experts and
/// how many are n-gram tables, by the names llama.cpp gives them: `ffn_up_exps` for experts,
/// anything with `engram` or `ngram` for n-gram tables. From the metadata follows what its
/// attention keeps for each token of context.
///
/// A header that is not as the format describes, or larger than [#MOST] bytes, is refused with
/// an [IOException], however it is broken, so that Genies falls back on rougher facts.
public final class GgufHeader {

    private GgufHeader() {}

    /// The most bytes of a header Genies reads: a model with a vocabulary of a few hundred
    /// thousand tokens has a header of some ten megabytes.
    public static final int MOST = 64 << 20;

    /// "GGUF", as the first four bytes of the file, read as a little-endian number.
    private static final int MAGIC = 0x46554747;

    /// The most entries of metadata, and the most tensors, Genies believes a header has.
    private static final long MOST_ENTRIES = 1_000_000;

    /// The bytes each of the attention's keys and values takes in Ollama's cache, as 16-bit numbers.
    private static final int CACHE_BYTES = 2;

    /// What the GGUF file whose header `in` begins with says `model` is made of. `download` is
    /// what Ollama downloads for the model, from which the bits of each parameter follow; 0 if not
    /// known. Reads no further than the header, nor more than [#MOST] bytes.
    public static ModelFacts read(String model, InputStream in, long download) throws IOException {
        Reader read = new Reader(new BufferedInputStream(in, 1 << 16));
        if (read.u32() != MAGIC) throw new IOException("not a GGUF file");
        long version = read.u32();
        if (version < 2 || version > 3) throw new IOException("GGUF version " + version + " is not one Genies knows");
        long tensors = read.count();
        long entries = read.count();
        Map<String, Object> metadata = new HashMap<>();
        for (long i = 0; i < entries; i++) {
            String key = read.string();
            Object value = read.value((int) read.u32(), key);
            if (value != SKIPPED) metadata.put(key, value);
        }
        double all = 0;
        double experts = 0;
        double tables = 0;
        double embedding = 0;
        boolean output = false;
        for (long i = 0; i < tensors; i++) {
            String name = read.string().toLowerCase(Locale.ROOT);
            long dimensions = read.u32();
            if (dimensions > 8) throw new IOException("a tensor of " + dimensions + " dimensions");
            double elements = 1;
            for (long d = 0; d < dimensions; d++) elements *= read.count(Long.MAX_VALUE);
            read.u32();
            read.u64();
            all += elements;
            if (name.contains("engram") || name.contains("ngram")) tables += elements;
            else if (name.contains("_exps")) experts += elements;
            else if (name.startsWith("token_embd.")) embedding += elements;
            else if (name.startsWith("output.")) output = true;
        }
        if (all <= 0) throw new IOException("a GGUF header without tensors");
        String architecture = text(metadata, "general.architecture").orElse("");
        long expertCount = number(metadata, architecture + ".expert_count").orElse(0L);
        long expertsUsed = number(metadata, architecture + ".expert_used_count").orElse(0L);
        // Each token reads only the experts picked for it, and of its embeddings only its own
        // row, unless the same tensor also turns the last layer into tokens.
        double unused = expertCount > 0 && expertsUsed > 0 && expertsUsed < expertCount ? experts * (expertCount - expertsUsed) / (double) expertCount : 0;
        double active = all - tables - unused - (output ? embedding : 0);
        return new ModelFacts(
            model.strip(),
            all / 1e9,
            active / 1e9,
            tables / 1e9,
            0,
            download,
            cachePerToken(metadata, architecture),
            ModelFacts.Source.FILE_HEADER
        );
    }

    /// The bytes each token of context takes in the cache of a model of `architecture`: the keys
    /// and values each layer's attention keeps, or, with attention that compresses them, as
    /// DeepSeek's does, what it keeps of them. 0 when the metadata does not say.
    static long cachePerToken(Map<String, Object> metadata, String architecture) {
        long layers = number(metadata, architecture + ".block_count").orElse(0L);
        if (layers <= 0) return 0;
        long compressed = number(metadata, architecture + ".attention.kv_lora_rank").orElse(0L);
        if (compressed > 0) {
            long rope = number(metadata, architecture + ".rope.dimension_count").orElse(0L);
            return layers * (compressed + rope) * CACHE_BYTES;
        }
        long width = number(metadata, architecture + ".embedding_length").orElse(0L);
        long heads = firstOf(metadata.get(architecture + ".attention.head_count")).orElse(0L);
        long keys = number(metadata, architecture + ".attention.key_length").orElse(heads > 0 ? width / heads : 0);
        long values = number(metadata, architecture + ".attention.value_length").orElse(keys);
        if (keys <= 0) return 0;
        Object kv = metadata.getOrDefault(architecture + ".attention.head_count_kv", metadata.get(architecture + ".attention.head_count"));
        // A model may keep a different number of heads in each layer, and none in a layer
        // without attention: then the metadata lists one for each.
        long heldHeads = kv instanceof List<?> each
            ? each.stream().mapToLong(it -> it instanceof Number n ? n.longValue() : 0).sum()
            : firstOf(kv).orElse(0L) * layers;
        return heldHeads * (keys + values) * CACHE_BYTES;
    }

    private static Optional<String> text(Map<String, Object> metadata, String key) {
        return metadata.get(key) instanceof String it ? Optional.of(it) : Optional.empty();
    }

    private static Optional<Long> number(Map<String, Object> metadata, String key) {
        return metadata.get(key) instanceof Number it ? Optional.of(it.longValue()) : Optional.empty();
    }

    private static Optional<Long> firstOf(@Nullable Object value) {
        if (value instanceof Number it) return Optional.of(it.longValue());
        if (value instanceof List<?> each) return each.stream().filter(Number.class::isInstance).map(it -> ((Number) it).longValue()).max(Long::compare);
        return Optional.empty();
    }

    /// A value that is not kept, as an array of strings: the vocabulary of the model.
    private static final Object SKIPPED = new Object();

    /// Reads the parts of a header, little-endian, as the format describes them, and counts what
    /// it read, to read no more than [#MOST].
    private static final class Reader {
        private final InputStream in;
        private long read = 0;

        Reader(InputStream in) { this.in = in; }

        private int u8() throws IOException {
            int it = in.read();
            if (it < 0) throw new EOFException("the GGUF header ends early");
            if (++read > MOST) throw new IOException("a GGUF header larger than " + MOST + " bytes");
            return it;
        }

        long u32() throws IOException {
            return u8() | (long) u8() << 8 | (long) u8() << 16 | (long) u8() << 24;
        }

        long u64() throws IOException {
            return u32() | u32() << 32;
        }

        /// A count, which no header Genies believes in exceeds.
        long count() throws IOException {
            return count(MOST_ENTRIES);
        }

        long count(long most) throws IOException {
            long it = u64();
            if (it < 0 || it > most) throw new IOException("a count of " + Long.toUnsignedString(it) + " in a GGUF header");
            return it;
        }

        String string() throws IOException {
            long length = count(MOST);
            // Refused before it takes any memory: a broken header could name a string of
            // gigabytes, or many of 64 MB.
            if (length > MOST - read) throw new IOException("a string of " + length + " bytes, beyond the end of a GGUF header");
            byte[] bytes = new byte[(int) length];
            for (int i = 0; i < length; i++) bytes[i] = (byte) u8();
            return new String(bytes, StandardCharsets.UTF_8);
        }

        void skip(long bytes) throws IOException {
            for (long i = 0; i < bytes; i++) u8();
        }

        /// A value of the GGUF type `type`. Arrays of numbers are kept as lists, as a model that
        /// keeps a different number of heads in each layer lists them; other arrays are skipped.
        Object value(int type, String key) throws IOException {
            return switch (type) {
                case 0, 1 -> (long) (type == 1 ? (byte) u8() : u8());
                case 2, 3 -> { long it = u8() | (long) u8() << 8; yield type == 3 ? (long) (short) it : it; }
                case 4 -> u32();
                case 5 -> (long) (int) u32();
                case 6 -> (double) Float.intBitsToFloat((int) u32());
                case 7 -> u8() != 0;
                case 8 -> string();
                case 9 -> array((int) u32(), count(Long.MAX_VALUE), key);
                case 10, 11 -> u64();
                case 12 -> Double.longBitsToDouble(u64());
                default -> throw new IOException("a value of the unknown GGUF type " + type + " for " + key);
            };
        }

        private Object array(int type, long length, String key) throws IOException {
            if (type == 9) throw new IOException("an array of arrays for " + key);
            int width = switch (type) {
                case 0, 1, 7 -> 1;
                case 2, 3 -> 2;
                case 4, 5, 6 -> 4;
                case 10, 11, 12 -> 8;
                default -> 0;
            };
            if (width == 0 || length > 4096) {
                // The vocabulary and its merges: long, and of no use here.
                for (long i = 0; i < length; i++) {
                    if (width > 0) skip(width);
                    else if (type == 8) skip(count(MOST));
                    else throw new IOException("an array of the unknown GGUF type " + type + " for " + key);
                }
                return SKIPPED;
            }
            java.util.List<Object> values = new java.util.ArrayList<>();
            for (long i = 0; i < length; i++) values.add(value(type, key));
            return values;
        }
    }
}
