package gui

import dev.gui.Genies
import dev.gui.genie.Shelf
import dev.gui.genie.SystemLook
import dev.gui.model.Genie
import dev.gui.model.GeniesState
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
