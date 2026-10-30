package gui

import dev.gui.Genies
import dev.gui.genie.Shelf
import dev.gui.genie.SystemLook
import dev.gui.model.Genie
import dev.gui.model.Palette
import dev.gui.model.Settings
import oillamp.ScenarioHost
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title
import sprouts.Tuple

import javax.swing.SwingUtilities
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Genies starts in the palette of the desktop it is on')
@Narrative('''
Until the user picks a palette on the settings page, Genies looks at the desktop it starts on:
on a light desktop it comes in its light palette, the lamp by day, and on a dark one in its dark
palette, the lamp at night. `SystemLook` gathers a handful of clues for that: the environment,
what GNOME and Ubuntu say, KDE's settings file, and macOS and Windows. Where nothing says, Genies
is the lamp at night, as it always was.

The clues come from three places, each of which a scenario hands `SystemLook` itself: the
environment, by variable; what a command prints; and the user's home, with the files desktops
keep their settings in.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class FittingInWithTheDesktopSpec extends Specification {

    @TempDir Path tmp

    def 'Ubuntu says whether it is dark or light, in its style or in its theme'() {
        reportInfo """
            Let's consider Ubuntu, whose Appearance settings have a Dark and a Light style. GNOME
            offers programs the style as a colour scheme, `prefer-dark` for the dark one. For the
            light one, some versions say `prefer-light` and others `default`, which says nothing
            by itself; then the theme's name says it: `Yaru` is light, and `Yaru-dark` dark.
        """
        expect:
            new SystemLook({ Optional.empty() }, { List<String> command -> gnome(scheme, theme, command) }, Optional.empty())
                .palette() == Optional.of(palette)

        where:
            scheme           | theme          || palette
            "'prefer-dark'"  | "'Yaru'"       || Palette.DARK
            "'prefer-light'" | "'Yaru-dark'"  || Palette.LIGHT
            "'default'"      | "'Yaru'"       || Palette.LIGHT
            "'default'"      | "'Yaru-dark'"  || Palette.DARK
    }

    def 'A GTK theme in the environment comes before what the desktop says'() {
        reportInfo """
            Let's consider a user who starts Genies with `GTK_THEME=Adwaita:dark` on an otherwise
            light desktop, as one does to make a program dark. That is the clue Genies follows.
        """
        given:
            var environment = { String name -> name == 'GTK_THEME' ? Optional.of('Adwaita:dark') : Optional.<String>empty() }

        expect:
            new SystemLook(environment, { List<String> command -> gnome("'prefer-light'", "'Yaru'", command) }, Optional.empty())
                .palette() == Optional.of(Palette.DARK)
    }

    def 'KDE says it in its settings file: by the name of its colour scheme, or by the colour of its windows'() {
        reportInfo """
            Let's consider KDE Plasma, which keeps its colours in `~/.config/kdeglobals`. The
            colour scheme it names, such as `BreezeDark`, says whether it is dark; a file
            without a name still has the background colour of its windows, which says it too.
        """
        given:
            Files.createDirectories(tmp.resolve('.config'))
            Files.writeString(tmp.resolve('.config/kdeglobals'), file)

        expect:
            new SystemLook({ Optional.empty() }, { '' }, Optional.of(tmp)).palette() == Optional.of(palette)

        where:
            file                                                                   || palette
            '[General]\nColorScheme=BreezeDark\n'                                  || Palette.DARK
            '[General]\nColorScheme=BreezeLight\n'                                 || Palette.LIGHT
            '[Colors:Window]\nBackgroundNormal=32,35,38\n'                         || Palette.DARK
            '[Colors:Window]\nBackgroundNormal=239,240,241\n[General]\nName=Mine\n' || Palette.LIGHT
    }

    def 'Where nothing says, there is no clue'() {
        reportInfo """
            Let's consider a computer without a desktop of these kinds, such as a bare window
            manager: no command answers, and there is no settings file. `SystemLook` then says
            nothing, rather than guess.
        """
        expect:
            new SystemLook({ Optional.empty() }, { '' }, Optional.of(tmp)).palette() == Optional.empty()
    }

    def 'Genies starts in the palette of the desktop until the user picks one, and in theirs from then on'() {
        reportInfo """
            Let's consider a user who starts Genies for the first time from a light desktop: it
            comes in the light palette, and keeps nothing on its shelf yet. Then they pick the
            dark palette on the settings page, which Genies keeps there. The next time Genies
            starts, from the same light desktop, it comes in the dark palette, the user's.
        """
        given: 'a desktop whose GTK theme is light'
            var sandbox = new ScenarioHost(tmp)
            sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)) }
            var shelf = sandbox.home.resolve('genies')
            var light = { String name -> name == 'GTK_THEME' ? Optional.of('Adwaita') : Optional.<String>empty() }

        when: 'Genies starts for the first time'
            Genies first = Genies.open(shelf, Tuple.of(Genie), Settings.defaults(), sandbox.launcher, light)

        then:
            first.state().get().palette() == Palette.LIGHT
            new Shelf(shelf).palette() == Optional.empty()

        when: 'the user picks the dark palette, quits, and starts Genies again'
            SwingUtilities.invokeAndWait { first.state().update { it.withPalette(Palette.DARK) } }
            var quitted = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { first.quit(false, Optional.empty(), { quitted.countDown() }) }
            quitted.await(60, TimeUnit.SECONDS)
            Genies again = Genies.open(shelf, Tuple.of(Genie), Settings.defaults(), sandbox.launcher, light)

        then:
            again.state().get().palette() == Palette.DARK

        cleanup:
            var done = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { again?.quit(false, Optional.empty(), { done.countDown() }) }
            done.await(60, TimeUnit.SECONDS)
    }

    /// What `gsettings` prints for the colour scheme and the theme of GNOME, or nothing for any
    /// other command.
    private static String gnome(String scheme, String theme, List<String> command) {
        if (command == ['gsettings', 'get', 'org.gnome.desktop.interface', 'color-scheme']) return scheme + '\n'
        if (command == ['gsettings', 'get', 'org.gnome.desktop.interface', 'gtk-theme']) return theme + '\n'
        return ''
    }
}
