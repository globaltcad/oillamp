package gui

import dev.gui.genie.GgufHeader
import dev.gui.model.Hardware
import dev.gui.model.ModelFacts
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@Title('Judging a model by what it is made of')
@Narrative('''
Before a user downloads a model of many gigabytes, the settings say how well it would run on
their computer. Genies judges that by what the model is made of, as a `ModelFacts`: how many
parameters it has, how many of them it reads for each token it writes, how many are n-gram
tables, and what each token of its context keeps in memory.

Genies learns these facts from several sources, each a `ModelFacts.Source`, the most exact first:
the header of the model's GGUF file, read from Ollama's registry; what the registry says of the
model; its page on ollama.com; Genies' own list; and the model's name. The best source that
answered gives the facts, and each worse one fills in what it left out, with `ModelFacts.or`.
The name always answers, so no failure of a better source leaves a model without facts, only
with rougher ones.

`Hardware.fit` then judges the facts against the computer's memory and graphics card.
''')
@Subject([ModelFacts, GgufHeader, Hardware])
class Judging_A_Model_Spec extends Specification {

    static final long GIGABYTE = 1L << 30

    /// The header of a GGUF file, version 3, with `metadata` and `tensors`, each tensor by its
    /// name and its number of elements, as one dimension.
    static byte[] gguf(Map<String, Object> metadata, Map<String, Long> tensors) {
        var out = new ByteArrayOutputStream()
        def u32 = { long it -> out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) it).array()) }
        def u64 = { long it -> out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(it).array()) }
        def string = { String it -> byte[] bytes = it.getBytes(StandardCharsets.UTF_8); u64(bytes.length); out.write(bytes) }
        u32(0x46554747)
        u32(3)
        u64(tensors.size())
        u64(metadata.size())
        metadata.each { key, value ->
            string(key)
            if (value instanceof String) { u32(8); string(value) }
            else { u32(4); u32((long) value) }
        }
        tensors.each { name, elements ->
            string(name)
            u32(1)
            u64(elements)
            u32(12)
            u64(0)
        }
        return out.toByteArray()
    }

    def 'The header of a mixture of experts says how many of its parameters each token reads, and what its context keeps'() {
        reportInfo """
            Let's consider the GGUF file of a small mixture of experts, of the architecture
            `qwen3moe`, with 10 million parameters: 1 million in its attention, 8 million in eight
            experts, of which it picks 2 for each token, and two tensors of 500 thousand each that
            turn tokens into numbers and back: `token_embd.weight` and `output.weight`. Its
            metadata says it has 2 layers, and that each layer's attention keeps 2 heads of
            keys and values, 16 numbers each.

            `GgufHeader.read` reads the parameters from the tensors: all 10 million of them. For
            each token, the model reads its attention, the 2 experts it picked, a quarter of the
            8 million, and `output.weight`; of `token_embd.weight` it reads only the row of the
            token, so that does not count: 3.5 million. Each token of context keeps, in each of
            the 2 layers, 2 heads of 16 keys and 16 values, each a 16-bit number: 256 bytes.
        """
        given: 'the header of the file'
            var header = gguf(
                [
                    'general.architecture'                : 'qwen3moe',
                    'qwen3moe.block_count'                : 2L,
                    'qwen3moe.attention.head_count'       : 4L,
                    'qwen3moe.attention.head_count_kv'    : 2L,
                    'qwen3moe.attention.key_length'       : 16L,
                    'qwen3moe.attention.value_length'     : 16L,
                    'qwen3moe.expert_count'               : 8L,
                    'qwen3moe.expert_used_count'          : 2L,
                ],
                [
                    'token_embd.weight'        : 500_000L,
                    'blk.0.attn_q.weight'      : 1_000_000L,
                    'blk.0.ffn_up_exps.weight' : 8_000_000L,
                    'output.weight'            : 500_000L,
                ]
            )

        when: 'Genies reads it, knowing the model downloads 6 million bytes'
            var facts = GgufHeader.read('tiny-moe:10m', new ByteArrayInputStream(header), 6_000_000)

        then: 'the model has 10 million parameters, and reads 3.5 million for each token'
            Math.round(facts.billions() * 1e9) == 10_000_000
            Math.round(facts.active() * 1e9) == 3_500_000
            facts.tables() == 0

        and: 'each token of its context takes 256 bytes'
            facts.cachePerToken() == 256

        and: 'it weighs the bytes it downloads, and the facts come from its file'
            facts.download() == 6_000_000
            facts.source() == ModelFacts.Source.FILE_HEADER
    }

    def 'A header that is not a GGUF file, or ends early, is refused'() {
        reportInfo """
            Ollama's registry may answer with something else than a model's file, such as an
            error page, or the download may break off within the header. `GgufHeader.read`
            refuses either with an `IOException`, rather than make up facts from it. Genies then
            goes by what the registry said of the model, and by its name.
        """
        given: 'a header that ends in the middle of its first tensor'
            var whole = gguf(['general.architecture': 'qwen3'], ['blk.0.attn_q.weight': 1_000L])
            var cut = Arrays.copyOf(whole, whole.length - 10)

        when: 'Genies reads an HTML page as a header'
            GgufHeader.read('qwen3:8b', new ByteArrayInputStream('<html>Not found</html>'.getBytes(StandardCharsets.UTF_8)), 0)
        then:
            thrown(IOException)

        when: 'Genies reads the header that ends early'
            GgufHeader.read('qwen3:8b', new ByteArrayInputStream(cut), 0)
        then:
            thrown(IOException)
    }

    def 'Facts from a worse source fill in what a better one left out'() {
        reportInfo """
            Let's consider Genies could not read the header of the file of qwen3:30b-a3b, but
            Ollama's registry answered: the model downloads 18.6 billion bytes, and has 30.5
            billion parameters. The registry does not say how many of them each token reads.
            Genies' own list does: 3 billion, of the experts the model picks. `ModelFacts.or`
            takes the download and the parameters from the registry, and the 3 billion from
            the list, so the settings judge the model as the mixture of experts it is. The facts
            count as the registry's, the best source among them.
        """
        given: 'what the registry said of the model'
            var registry = new ModelFacts('qwen3:30b-a3b', 30.5, 0, 0, 0, 18_556_698_820L, 0, ModelFacts.Source.REGISTRY)

        when: 'what Genies knows without asking anyone fills it in'
            var facts = registry.or(ModelFacts.of('qwen3:30b-a3b'))

        then:
            facts.billions() == 30.5
            facts.active() == 3
            facts.download() == 18_556_698_820L
            facts.source() == ModelFacts.Source.REGISTRY
    }

    def 'A model\'s n-gram tables need no room on the graphics card nor in memory, and it keeps a fair pace with them on the disk'() {
        reportInfo """
            Some models keep a large share of their parameters in n-gram tables, as in
            DeepSeek's Engram: memory a model looks up a few rows in for each token, rather than
            reading it whole. Such tables can stay in the computer's memory, or even on its disk,
            while the rest of the model runs, and cost little speed there.

            Let's consider a model of 40 billion parameters, 28 billion of them in n-gram tables,
            that reads 4 billion for each token, in Ollama's usual 5 bits each. On a computer
            with 32 GB of memory and no graphics card, the 12 billion parameters outside its
            tables fit in memory with a genie's context, and the processor runs them at a fair
            pace. Its tables of 16 GB do not fit in the memory that is left, so `Hardware.fit`
            says they stay on the disk. With 64 GB of memory, they fit too. A model of the same
            40 billion parameters without tables would not run on the 32 GB at all.
        """
        given:
            var tables = new ModelFacts('engram-model:40b', 40, 4, 28, 5, 0, 0, ModelFacts.Source.FILE_HEADER)
            var dense = new ModelFacts('dense-model:40b', 40, 40, 0, 5, 0, 0, ModelFacts.Source.FILE_HEADER)
            var smaller = new Hardware(32 * GIGABYTE, Optional.empty())
            var larger = new Hardware(64 * GIGABYTE, Optional.empty())

        expect: 'on 32 GB, the model with tables runs on the processor at a fair pace, its tables on the disk'
            smaller.fit(tables).runs() == Hardware.Runs.ON_PROCESSOR
            smaller.fit(tables).pace()
            smaller.fit(tables).tablesOnDisk()

        and: 'on 64 GB, its tables are in memory'
            larger.fit(tables).runs() == Hardware.Runs.ON_PROCESSOR
            !larger.fit(tables).tablesOnDisk()

        and: 'the model without tables is too large for 32 GB'
            smaller.fit(dense).runs() == Hardware.Runs.TOO_LARGE
    }

    def 'A name whose numbers Genies cannot read says nothing of the model\'s size'() {
        reportInfo """
            The settings judge whatever the user types into the field for a model's name, as
            they type it. Let's consider a name with a number of experts too long to count, such
            as `mixtral:99999999999x7b`. `ModelFacts.of` takes it for a name that says nothing
            of the model's size, `ModelFacts.Source.NOTHING`, rather than fail, and
            `Hardware.fit` says the size is not known.
        """
        when:
            var facts = ModelFacts.of('mixtral:99999999999x7b')
        then:
            facts.source() == ModelFacts.Source.NOTHING
            new Hardware(16 * GIGABYTE, Optional.empty()).fit(facts).runs() == Hardware.Runs.UNKNOWN
    }

    def 'Genies knows how many bits the parameters of each quantisation take'() {
        reportInfo """
            Ollama's registry names a model's quantisation as llama.cpp does, such as `Q4_K_M`,
            and so does the tag of a model, such as `qwen3:8b-q8_0`. `ModelFacts.bitsOf` gives
            the bits a parameter takes on average in each, with the scales its blocks keep; 0 for
            one Genies does not know, which then counts as Ollama's usual `Q4_K_M`.
        """
        expect:
            ModelFacts.bitsOf(quantisation) == bits
        where:
            quantisation | bits
            'Q4_K_M'     | 5.0
            'q4_0'       | 4.5
            'Q8_0'       | 8.5
            'F16'        | 16
            'bf16'       | 16
            'MXFP4'      | 4.25
            'turbo'      | 0
    }
}
