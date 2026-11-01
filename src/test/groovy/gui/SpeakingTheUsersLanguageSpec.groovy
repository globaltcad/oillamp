package gui

import dev.gui.Genies
import dev.gui.genie.Shelf
import dev.gui.genie.SystemLook
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.History
import dev.gui.model.HostAccess
import dev.gui.model.OllamaSetup
import dev.gui.model.Schedule
import dev.gui.model.Lang
import dev.gui.model.Settings
import dev.gui.view.GeniesView
import oillamp.ScenarioHost
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title
import sprouts.Tuple

import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import java.awt.Container
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Genies speaks the language the user picks, and until then the desktop\'s')
@Narrative('''
Genies says every text of its window in English, German, Spanish, Japanese or Chinese. Until the
user picks one beside the palette on the settings page, it speaks the language of the desktop it
starts on, if it is one of these, and English otherwise. The desktop's language is the one of the
locale its programs are started with, which `LC_ALL`, `LC_MESSAGES` and `LANG` name, the first set
deciding, as for any other program.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SpeakingTheUsersLanguageSpec extends Specification {

    @TempDir Path tmp

    def 'SystemLook takes the language from LC_ALL, LC_MESSAGES and LANG, the first set deciding'() {
        reportInfo """
            Let's consider a desktop whose programs are started with the locale variables below.
            `SystemLook.lang()` reads the first of `LC_ALL`, `LC_MESSAGES` and `LANG` that is set,
            and takes its language from the part before the territory, such as `de` in
            `de_DE.UTF-8`. A language Genies does not speak, or the plain `C` locale, says nothing.
        """
        expect:
            SystemLook.ofEnvironment({ String name -> Optional.ofNullable(variables[name]) }).lang() == Optional.ofNullable(lang)

        where:
            variables                                            || lang
            [LANG: 'de_DE.UTF-8']                                || Lang.GERMAN
            [LANG: 'es_ES.UTF-8']                                || Lang.SPANISH
            [LANG: 'ja_JP.UTF-8']                                || Lang.JAPANESE
            [LANG: 'zh_CN.UTF-8']                                || Lang.CHINESE
            [LANG: 'en_GB.UTF-8']                                || Lang.ENGLISH
            [LANG: 'de_DE.UTF-8', LC_MESSAGES: 'ja_JP.UTF-8']    || Lang.JAPANESE
            [LC_ALL: 'es_ES.UTF-8', LC_MESSAGES: 'ja_JP.UTF-8']  || Lang.SPANISH
            [LANG: 'fr_FR.UTF-8']                                || null
            [LANG: 'C']                                          || null
            [:]                                                  || null
    }

    def 'Genies speaks the language of the desktop until the user picks one, and theirs from then on'() {
        reportInfo """
            Let's consider a user who starts Genies for the first time on a desktop whose `LANG` is
            `de_DE.UTF-8`: its window speaks German, and its shelf keeps no language yet. Then the
            settings page's language box sets the state's language to Japanese, which Genies keeps
            in `look.json` on its shelf, without keeping the palette, which the user did not pick.
            The next time Genies starts, from the same German desktop, it speaks Japanese.
        """
        given: 'a desktop that speaks German'
            var sandbox = new ScenarioHost(tmp)
            sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)) }
            var shelf = sandbox.home.resolve('genies')
            var german = { String name -> name == 'LANG' ? Optional.of('de_DE.UTF-8') : Optional.<String>empty() }

        when: 'Genies starts for the first time'
            Genies first = Genies.open(shelf, Tuple.of(Genie), Settings.defaults(), sandbox.launcher, german)

        then:
            first.state().get().lang() == Lang.GERMAN
            new Shelf(shelf).lang() == Optional.empty()

        when: 'the state\'s language becomes Japanese, and Genies quits and starts again'
            SwingUtilities.invokeAndWait { first.state().update { it.withLang(Lang.JAPANESE) } }
            var quitted = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { first.quit(false, Optional.empty(), { quitted.countDown() }) }
            quitted.await(60, TimeUnit.SECONDS)

        then: 'the shelf keeps the language alone'
            new Shelf(shelf).lang() == Optional.of(Lang.JAPANESE)
            new Shelf(shelf).palette() == Optional.empty()

        when:
            Genies again = Genies.open(shelf, Tuple.of(Genie), Settings.defaults(), sandbox.launcher, german)

        then:
            again.state().get().lang() == Lang.JAPANESE

        cleanup:
            var done = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { again?.quit(false, Optional.empty(), { done.countDown() }) }
            done.await(60, TimeUnit.SECONDS)
    }

    def 'Picking a language on the settings page speaks it at once'() {
        reportInfo """
            Let's consider a user on the settings page of Genies, which speaks English, as it does
            on a desktop that names no language. Beside the switch between the dark and the light
            palette is a box that lists each language by its own name: English, Deutsch, Español,
            日本語 and 中文. Choosing Deutsch there turns the card's title from "Personalization"
            into "Personalisierung" at once, and every other text of the window with it.
        """
        given: 'Genies on its settings page, in English'
            var sandbox = new ScenarioHost(tmp)
            sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)) }
            Genies app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie), Settings.defaults(), sandbox.launcher, { Optional.empty() })
            GeniesView view
            SwingUtilities.invokeAndWait {
                view = new GeniesView(app)
                app.state().update { it.withPage(GeniesState.Page.SETTINGS) }
            }
            JComboBox languages
            SwingUtilities.invokeAndWait { languages = descendants(view).find { it instanceof JComboBox && it.getItemAt(0) instanceof Lang } as JComboBox }

        expect: 'the box names each language in itself, and the card speaks English'
            (0..<languages.itemCount).collect { (languages.getItemAt(it) as Lang).NAME_IN_ITSELF() } == ['English', 'Deutsch', 'Español', '日本語', '中文']
            labelled(view, 'Personalization')

        when: 'the user chooses Deutsch'
            SwingUtilities.invokeAndWait { languages.selectedItem = Lang.GERMAN }

        then:
            app.state().get().lang() == Lang.GERMAN
            labelled(view, 'Personalisierung')
            !labelled(view, 'Personalization')

        cleanup:
            var done = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
            done.await(60, TimeUnit.SECONDS)
    }

    def 'What the history page is busy with is said in the language Genies speaks, even after it began'() {
        reportInfo """
            Let's consider a genie moving to its branch "try". While oillamp moves it, the history
            page says so: "Moving to try…". Genies keeps what is on its way as a value without
            words, `History.Busy.MovingTo`, and only the page says it, in the language Genies
            speaks. So a user who switches to German while the genie is still moving reads
            "Wechsle zu try…", and not the English the move began in.
        """
        given: 'a history moving to the branch try'
            def history = History.UNREAD.withBusy(new History.Busy.MovingTo('try'))

        expect: 'it is said in each language, the branch named in it'
            history.busy().get().words(Lang.ENGLISH) == 'Moving to try…'
            history.busy().get().words(Lang.GERMAN) == 'Wechsle zu try…'
            history.busy().get().words(Lang.JAPANESE) == 'try に移動中…'

        and: 'once it is done, nothing is said'
            history.notBusy().busy().isEmpty()
    }

    def 'What failed on the history page is said in the language Genies speaks, and why in oillamp\'s own words'() {
        reportInfo """
            Let's consider oillamp refusing to delete the branch "try" because the disk is full.
            The history page says what failed in the language Genies speaks, and then why, as
            oillamp said it: oillamp speaks English, and its words are quoted, not translated.
            A note, such as that a save saved nothing as nothing changed, is said in that
            language too.
        """
        given: 'a history whose branch try could not be deleted'
            def history = History.UNREAD.withProblem(new History.Problem.CouldNotDeleteBranch('try', 'no space left on device'))

        expect: 'what failed is said in each language, and why as oillamp said it'
            history.problem().get().words(Lang.ENGLISH) == 'oillamp could not delete the branch try: no space left on device'
            history.problem().get().words(Lang.GERMAN) == 'oillamp konnte den Zweig try nicht löschen: no space left on device'
            history.problem().get().words(Lang.CHINESE) == 'oillamp 无法删除分支 try：no space left on device'

        and: 'a note is said in each language too'
            History.Note.NOTHING_TO_DELETE.words(Lang.SPANISH) == 'El historial cabe en su tamaño, así que no hay nada que eliminar.'

        and: 'once the next change begins, neither is said'
            history.withNote(History.Note.NOTHING_CHANGED).noProblem().noNote().problem().isEmpty()
    }

    def 'What failed on the schedule and host access pages is said in the language Genies speaks'() {
        reportInfo """
            Let's consider a job oillamp could not switch off, and host access it could not
            save. As on the history page, each page says what failed in the language Genies
            speaks, and why as oillamp said it.
        """
        given: 'a job that could not be switched off, and host access that could not be saved'
            def job = new Schedule.Problem.CouldNotSwitchOff('the lamp is busy')
            def access = new HostAccess.Problem.CouldNotSave('permission denied')

        expect: 'each is said in each language, and why as oillamp said it'
            job.words(Lang.ENGLISH) == 'The job could not be switched off: the lamp is busy'
            job.words(Lang.GERMAN) == 'Der Auftrag ließ sich nicht ausschalten: the lamp is busy'
            access.words(Lang.ENGLISH) == 'oillamp could not save it: permission denied'
            access.words(Lang.SPANISH) == 'oillamp no pudo guardarlo: permission denied'
    }

    def 'How far setting up Ollama is, is said in the language Genies speaks'() {
        reportInfo """
            Let's consider Genies downloading the model qwen3:8b for Ollama. The settings say how
            far the download is, and the steps before and after it. Genies keeps each as a value,
            such as how many bytes of how many arrived, and the settings say it in the language
            Genies speaks, the amounts in MB and GB.
        """
        given: 'a download of qwen3:8b, 512 MB of 2 GB along'
            def downloading = new OllamaSetup.Says.DownloadingModel('qwen3:8b', 512L << 20, 2L << 30)

        expect: 'it is said in each language'
            downloading.words(Lang.ENGLISH) == 'Downloading qwen3:8b: 512 MB of 2.0 GB.'
            downloading.words(Lang.GERMAN) == 'Lade qwen3:8b herunter: 512 MB von 2.0 GB.'
            downloading.words(Lang.JAPANESE) == 'qwen3:8b をダウンロード中：512 MB / 2.0 GB'

        and: 'before anything arrived, it says only what it downloads'
            new OllamaSetup.Says.DownloadingModel('qwen3:8b', 0, 0).words(Lang.SPANISH) == 'Descargando qwen3:8b…'

        and: 'so are the other steps'
            new OllamaSetup.Says.StartingOllama().words(Lang.CHINESE) == '正在启动 Ollama…'
    }

    def 'Why setting up Ollama failed is said in the language Genies speaks, and what Ollama said as it said it'() {
        reportInfo """
            Let's consider a user who asked Genies for a model Ollama does not know, and another
            whose download Ollama broke off. The settings say why in the language Genies speaks;
            what Ollama itself said, as why it broke off, stays as Ollama put it.
        """
        given: 'a model Ollama does not know, and a download Ollama broke off'
            def unknown = new OllamaSetup.Says.Failed(new OllamaSetup.Failure.NoSuchModel('qwen9'))
            def brokeOff = new OllamaSetup.Says.Failed(new OllamaSetup.Failure.CouldNotDownload('qwen3:8b', 'connection reset'))

        expect: 'why is said in each language, and what Ollama said as it said it'
            unknown.words(Lang.ENGLISH) == 'Ollama has no model called qwen9. Check its name on ollama.com/library.'
            unknown.words(Lang.GERMAN) == 'Ollama kennt kein Modell namens qwen9. Prüfe den Namen auf ollama.com/library.'
            brokeOff.words(Lang.SPANISH) == 'Ollama no pudo descargar qwen3:8b: connection reset'
    }

    def 'What looking up the models found is said in the language Genies speaks'() {
        reportInfo """
            Let's consider a user who looked up the models of the model server on their computer,
            which offers two. The note under the list says so in the language Genies speaks, as
            it says that it is still asking. Genies keeps only the answer, and words the note when
            it shows it.
        """
        given: 'settings that name this computer, whose model server offers two models'
            def state = GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.empty())
            def local = state.withSettings(state.settings().withPlace(Settings.Place.THIS_MACHINE))
            def found = local.modelsFound(Settings.Place.THIS_MACHINE, 'http://127.0.0.1:11434/v1', Tuple.of(String, 'a', 'b'))

        expect: 'the note says so in each language'
            found.lookUp().note(Lang.ENGLISH) == 'The model server offers 2 models.'
            found.lookUp().note(Lang.GERMAN) == 'Der Modellserver bietet 2 Modelle an.'
            found.lookUp().note(Lang.CHINESE) == '模型服务器提供 2 个模型。'

        and: 'while it asks, it says so'
            local.askingForModels().lookUp().note(Lang.SPANISH) == 'Consultando los modelos…'
    }

    def 'What a genie is doing is said in the language Genies speaks, and what its lamp reports as oillamp put it'() {
        reportInfo """
            Let's consider a genie that uses the tool "read", and another whose lamp makes its
            sandbox. Under each genie's name, Genies says what it is doing in the language it
            speaks. A step its lamp reports in oillamp's own words, such as "Pulling the base
            image", is said as oillamp put it.
        """
        given: 'a genie using the tool read, and its lamp\'s steps'
            def jafar = Genie.named('Jafar').withPhase(Genie.Phase.WORKING).withActivity(new Genie.Activity.Using('read'))

        expect: 'what it does is said in each language'
            jafar.status(Lang.ENGLISH) == 'using read'
            jafar.status(Lang.GERMAN) == 'benutzt read'
            jafar.status(Lang.JAPANESE) == 'read を使っています'

        and: 'so are the steps of waking, and a step its lamp reports stays as oillamp put it'
            new Genie.Activity.MakingTheSandbox().words(Lang.SPANISH) == 'creando el sandbox; la primera vez tarda varios minutos'
            new Genie.Activity.LampSays('Pulling the base image').words(Lang.GERMAN) == 'Pulling the base image'
    }

    /// Whether a label in `view` says `text`.
    private static boolean labelled(Container view, String text) {
        boolean found = false
        SwingUtilities.invokeAndWait { found = descendants(view).any { it instanceof JLabel && it.text == text } }
        found
    }

    private static List descendants(Container parent) {
        parent.components.collectMany { it instanceof Container ? [it] + descendants(it) : [it] }
    }
}
