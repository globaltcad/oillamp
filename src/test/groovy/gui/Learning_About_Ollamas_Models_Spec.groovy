package gui

import dev.gui.genie.OllamaCatalogue
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Hardware
import dev.gui.model.ModelFacts
import dev.gui.model.ModelSearch
import dev.gui.model.OllamaLibrary
import dev.gui.model.OllamaSetup
import dev.gui.model.Settings
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title
import sprouts.Tuple

import java.nio.file.Files
import java.nio.file.Path

@Title('Learning about Ollama\'s models')
@Narrative('''
The settings offer the models Ollama has on ollama.com, not only those Genies knew when it was
built, and say when a newer Ollama is out. Genies keeps what it learned in an `OllamaLibrary`.
`OllamaCatalogue` asks for it: ollama.com for its models, Ollama's registry for what a model is
made of, GitHub for the newest Ollama.

None of these promises to answer as it does today. So every question is asked in tiers, and
whatever goes wrong is logged, and the next tier tried: what ollama.com said before, which Genies
keeps on its shelf in `ollama-library.json`, and at the last what Genies knows itself,
`OllamaLibrary.BUILT_IN`. A page Genies does not recognise is no answer. The user loses at the
worst the newer models, never the settings.
''')
@Subject([OllamaLibrary, OllamaCatalogue, ModelSearch, OllamaSetup])
class Learning_About_Ollamas_Models_Spec extends Specification {

    @TempDir Path shelf

    /// A model as ollama.com/library lists it: a link to its page, its labels, and how often
    /// it was pulled.
    static String listed(String name, List<String> labels, String pulls) {
        """
        <li class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/${name}" class="group w-full space-y-5">
            <h2><span class="group-hover:underline truncate">${name}</span></h2>
            ${labels.collect { "<span class=\"inline-flex items-center rounded-md\">${it}</span>" }.join('\n')}
            <p><span class="flex items-center"><span >${pulls}</span><span class="hidden sm:flex">&nbsp;Pulls</span></span></p>
          </a>
        </li>
        """
    }

    /// A page of ollama.com/library with five models a genie could use besides those of the
    /// scenario, as a page that Genies recognises lists many.
    static String library(String... models) {
        var others = (1..5).collect { listed("other-${it}", ['tools', "${it}b"], '1K') }
        return "<html><body><ul>${models.join('\n')}${others.join('\n')}</ul></body></html>"
    }

    def 'Genies offers the models on ollama.com that can use tools and run on a computer of their own'() {
        reportInfo """
            Let's consider ollama.com/library lists, among others, qwen3 in four sizes, with the
            label `tools`, pulled 17.6 million times; nomic-embed-text, which cannot use tools;
            and kimi-k3, which can, but only in Ollama's cloud, with no size to download.
            `OllamaLibrary.offersIn` reads the page: a genie must use tools, and Genies runs
            models on the user's computer, so it offers qwen3, in each of its sizes, and
            neither of the other two. `OllamaLibrary.models` names qwen3 in each size with its
            usual tag, such as `qwen3:8b`, those of the models pulled most first.
        """
        given: 'the page of ollama.com/library'
            var page = library(
                listed('qwen3', ['tools', 'thinking', '0.6b', '8b', '30b-a3b', '235b'], '17.6M'),
                listed('nomic-embed-text', ['embedding', '137m'], '40.1M'),
                listed('kimi-k3', ['tools', 'cloud'], '9.3K')
            )

        when: 'Genies reads it'
            var offers = OllamaLibrary.offersIn(page)

        then: 'qwen3 is offered in its four sizes, pulled 17.6 million times'
            var qwen = offers.toList().find { it.name() == 'qwen3' }
            qwen.sizes().toList() == ['0.6b', '8b', '30b-a3b', '235b']
            qwen.pulls() == 17_600_000

        and: 'neither the model without tools, nor the one only in the cloud'
            !offers.any { it.name() == 'nomic-embed-text' || it.name() == 'kimi-k3' }

        and: 'the models offered begin with the variants of qwen3, the model pulled most'
            OllamaLibrary.BUILT_IN.withOffers(offers, OllamaLibrary.Source.OLLAMA_COM, Optional.empty()).models().toList().take(4) ==
                ['qwen3:0.6b', 'qwen3:8b', 'qwen3:30b-a3b', 'qwen3:235b']
    }

    def 'A page of ollama.com that Genies does not recognise is no answer'() {
        reportInfo """
            ollama.com may change its pages, or answer with a page that says it is down for
            maintenance. Then Genies would find a few models, or none, on it. With fewer than
            `OllamaLibrary.FEWEST_OFFERS` models that can use tools, `OllamaLibrary.offersIn`
            refuses the page with an `IllegalArgumentException`, and `OllamaCatalogue` offers
            what it knew before, rather than all but nothing.
        """
        when:
            OllamaLibrary.offersIn('<html><body><h1>Down for maintenance</h1><a href="/library/qwen3">qwen3</a></body></html>')
        then:
            thrown(IllegalArgumentException)
    }

    def 'Without ollama.com, Genies offers what ollama.com said before, and otherwise the models it knows itself'() {
        reportInfo """
            `OllamaCatalogue` keeps each answer of ollama.com in a file on the shelf,
            `ollama-library.json`. Let's consider Genies cannot reach ollama.com. With a kept
            answer, `OllamaCatalogue.kept` offers its models, from `OllamaLibrary.Source.KEPT`.
            With none, or one that is broken, such as a file cut off when the computer lost
            power, it offers the models Genies knows itself, from
            `OllamaLibrary.Source.BUILT_IN`.
        """
        given: 'a kept answer with five models'
            var kept = shelf.resolve('ollama-library.json')
            Files.writeString(kept, '''{
                "fetched": "2026-11-20T10:00:00Z",
                "offers": [
                    {"name": "qwen3.6", "sizes": ["27b", "35b"], "pulls": 900000},
                    {"name": "gemma4", "sizes": ["e4b", "12b"], "pulls": 800000},
                    {"name": "gpt-oss", "sizes": ["20b"], "pulls": 700000},
                    {"name": "mistral", "sizes": ["7b"], "pulls": 600000},
                    {"name": "lfm3", "sizes": ["1.7b"], "pulls": 500000}
                ]
            }''')
            var broken = shelf.resolve('broken.json')
            Files.writeString(broken, '{"fetched": "2026-11-20T10:00:00Z", "offers": [{"name": "qwen3.6", "si')

        expect: 'the kept answer is offered'
            var offered = new OllamaCatalogue(kept, shelf.resolve('headers.json')).kept(OllamaLibrary.BUILT_IN)
            offered.source() == OllamaLibrary.Source.KEPT
            offered.models().first() == 'qwen3.6:27b'

        and: 'without a file, and with a broken one, the models Genies knows itself are'
            new OllamaCatalogue(shelf.resolve('none.json'), shelf.resolve('headers.json')).kept(OllamaLibrary.BUILT_IN).source() == OllamaLibrary.Source.BUILT_IN
            new OllamaCatalogue(broken, shelf.resolve('headers.json')).kept(OllamaLibrary.BUILT_IN).models() == OllamaLibrary.BUILT_IN.models()
    }

    def 'The page of a model\'s tags on ollama.com gives its other quantisations, and what each downloads'() {
        reportInfo """
            Besides a model in each size, Ollama offers each size in other quantisations, such as
            `qwen3:8b-q8_0`, which the settings suggest once the user types a tag. They are on
            the page of the model's tags on ollama.com, each with what it downloads.
            `OllamaLibrary.variantsIn` reads them, and leaves out links to other models. Once
            known, the download of a variant tells how large it is, more exactly than its name:
            `OllamaLibrary.factsOf` takes it, from `ModelFacts.Source.LIBRARY`.
        """
        given: 'the page of the tags of qwen3'
            var page = '''
                <a href="/library/qwen3:8b" class="md:hidden"><span>qwen3:8b</span>
                  <span class="font-mono">500a1f067a9f</span> • 5.2GB • 40K context window</a>
                <a href="/library/qwen3:8b-q8_0" class="md:hidden"><span>qwen3:8b-q8_0</span>
                  <span class="font-mono">9b7f2a8d01c3</span> • 8.9GB • 40K context window</a>
                <a href="/library/qwen3.6:27b">qwen3.6, another model</a>
            '''

        when:
            var variants = OllamaLibrary.variantsIn(page, 'qwen3')

        then: 'both variants of qwen3, and nothing of the other model'
            variants.toList() == [new OllamaLibrary.Variant('qwen3:8b', 5_200_000_000L), new OllamaLibrary.Variant('qwen3:8b-q8_0', 8_900_000_000L)]

        and: 'the download of qwen3:8b-q8_0 is what Genies judges it by'
            var facts = OllamaLibrary.BUILT_IN.withVariants('qwen3', variants).factsOf('qwen3:8b-q8_0')
            facts.download() == 8_900_000_000L
            facts.source() == ModelFacts.Source.LIBRARY
    }

    def 'The models suggested as the user types hold every word typed, the best matches first'() {
        reportInfo """
            The settings' field for a model's name suggests the models that match what the user
            types. `ModelSearch.matching` takes those whose name holds every word typed, whatever
            their case: `QWEN 8` finds `qwen3:8b`. The model named exactly comes first, then those
            whose name begins with what was typed, then those of the model family named by the
            first word, then the rest: for `qwen3 8b`, `qwen3:8b` comes before `my-qwen3:8b`.
            Other quantisations crowd out the usual ones, so they are suggested only once the user
            types a tag, after a colon.
        """
        given:
            var offered = Tuple.of(String, 'gpt-oss:20b', 'qwen3.6:27b', 'qwen3:8b', 'qwen3:30b-a3b')
            var variants = Tuple.of(String, 'qwen3:8b-q8_0', 'qwen3:8b-fp16')

        expect: 'every word must be in the name'
            ModelSearch.matching('QWEN 8', offered, variants).toList() == ['qwen3:8b']

        and: 'the names beginning with what was typed come first'
            ModelSearch.matching('qwen3', offered, variants).toList() == ['qwen3.6:27b', 'qwen3:8b', 'qwen3:30b-a3b']
            ModelSearch.matching('qwen3:8b', offered, variants).toList() == ['qwen3:8b', 'qwen3:8b-q8_0', 'qwen3:8b-fp16']

        and: 'the other quantisations only once a tag is typed'
            !ModelSearch.matching('8b', offered, variants).contains('qwen3:8b-q8_0')
            ModelSearch.matching('qwen3:8b-q', offered, variants).toList() == ['qwen3:8b-q8_0']

        and: 'the model family named first comes before other models that hold the words'
            ModelSearch.matching('qwen3 8b', Tuple.of(String, 'my-qwen3:8b', 'qwen3:8b'), variants).toList() == ['qwen3:8b', 'my-qwen3:8b']

        and: 'nothing typed suggests every model offered'
            ModelSearch.matching('', offered, variants) == offered
    }

    def 'Genies updates only the Ollama it installed itself, and asks first only when awake genies use it'() {
        reportInfo """
            Let's consider GitHub says Ollama 0.40.17 is the newest, and Ollama 0.40.12 runs on
            the computer. `OllamaSetup.newerOllama` says 0.40.17 is out. If Genies installed that
            Ollama, `OllamaSetup.canUpdate` says it can update it; one installed otherwise, such
            as with the computer's package manager, is updated the way it was installed.

            To put the new Ollama in place, Genies stops the old one for a moment, which would
            break off what genies that use it are writing. `GeniesState.updatingOllamaInterrupts`
            says whether anything would be broken off: only when a genie is awake or waking, and
            the genies use Ollama. Only then does the user confirm the update.
        """
        given:
            var computer = new Hardware(32L << 30, Optional.empty())
            var library = OllamaLibrary.BUILT_IN.withNewest('0.40.17')
            def running = { String version, boolean ours ->
                OllamaSetup.NOT_YET.withLibrary(library).found(
                    new OllamaSetup.Found('/home/you/.local/share/genies/ollama/bin/ollama', version, Tuple.of(String), computer, ours), Settings.defaults())
            }
            var withOllama = Settings.defaults().withPlace(Settings.Place.THIS_MACHINE).withModel('genies/qwen3:8b')
            var awake = Genie.named('Iago').waking().awake()
            var asleep = Genie.named('Jafar')

        expect: 'an older Ollama Genies installed can be updated'
            running('0.40.12', true).newerOllama() == Optional.of('0.40.17')
            running('0.40.12', true).canUpdate()

        and: 'one installed otherwise is not updated by Genies, nor the newest'
            !running('0.40.12', false).canUpdate()
            running('0.40.17', true).newerOllama().isEmpty()

        and: 'the update is confirmed only while awake genies use Ollama'
            GeniesState.of(Tuple.of(Genie, awake), withOllama, Optional.empty()).updatingOllamaInterrupts()
            !GeniesState.of(Tuple.of(Genie, asleep), withOllama, Optional.empty()).updatingOllamaInterrupts()
            !GeniesState.of(Tuple.of(Genie, awake), Settings.defaults(), Optional.empty()).updatingOllamaInterrupts()
    }

    def 'A version is newer by its numbers, a prerelease is older than its release, and one that is not numbers is never newer'() {
        reportInfo """
            GitHub names a release by a tag such as `v0.40.17`, and Ollama says its version
            without the `v`. `OllamaSetup.isNewer` compares their numbers one by one, so that
            0.40.17 is newer than 0.9.3. A newest version that is not numbers between dots, such
            as a prerelease, is never taken for newer, so Genies offers no update to it. The
            Ollama installed may be a prerelease, such as 0.41.0-rc1: the release 0.41.0, and
            any later one, is newer than it.
        """
        expect:
            OllamaSetup.isNewer(candidate, than) == newer
        where:
            candidate    | than         | newer
            'v0.40.17'   | '0.40.12'    | true
            '0.40.17'    | '0.9.3'      | true
            '0.40.17'    | '0.40.17'    | false
            '0.40'       | '0.40.1'     | false
            '0.41.0'     | '0.41.0-rc1' | true
            '0.42.0'     | '0.41.0-rc1' | true
            '0.40.17'    | '0.41.0-rc1' | false
            '0.41.0-rc2' | '0.41.0-rc1' | false
            ''           | '0.40.12'    | false
    }
}
