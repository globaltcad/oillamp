package gui

import dev.gui.Genies
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

@Title('A genie\'s desktop in the palette the user picked')
@Narrative('''
oillamp has three wallpapers for a sandbox's desktop: the lamp on its panel, its own default, and
the inside of the lamp in waves, light or dark. Genies offers no choice of its own. A genie's
lamp gets the waves in the palette the user picked under Personalization, dark or light, as it
is made, when the genie first wakes. The lamp keeps them, as its `oillamp.toml` says, whatever
the user picks later. The lamp on its panel is never chosen.

Here the app is driven as its window drives it, through `Genies`; its lamps are oillamp's real
engine, in this JVM, on a simulated machine, with a stand-in pi.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ChoosingAGeniesWallpaperSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    UUID juniper
    UUID sage

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80))
                            .agent { String prompt -> 'You said: ' + prompt } }
        // Genies opens with Juniper and Sage on its shelf, neither ever woken, and a key in its environment.
        var first = Genie.named('Juniper')
        var second = Genie.named('Sage')
        juniper = first.id()
        sage = second.id()
        app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, first, second), Settings.defaults(), sandbox.launcher,
                          { name -> name == Settings.KEY_VARIABLE ? Optional.of('sk-key') : Optional.<String>empty() })
    }

    def cleanup() {
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        done.await(3, TimeUnit.MINUTES)
    }

    def 'A new genie\'s lamp gets the waves of the palette the user picked, and keeps them'() {
        reportInfo '''
            Let's consider a user who works in the dark palette and wakes Juniper for the first
            time. Juniper's lamp is made then, with the dark waves. Then the user switches to the
            light palette and wakes Sage, whose lamp is made with the light ones. Juniper, put to
            sleep and woken again in the light, keeps the dark waves it was made with: the lamp's
            own file decides, and Genies never overwrites it.
        '''
        when: 'the user, in the dark palette, wakes Juniper for the first time'
            SwingUtilities.invokeAndWait { app.state().update { it.withPalette(Palette.DARK) } }
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil(juniper) { it.phase() == Genie.Phase.READY }

        then: 'its lamp was made with the dark waves, and its desktop shows them'
            wallpaperIn(juniper) == 'waves-dark'
            desktopOf(juniper) == 'waves-dark'

        when: 'the user switches to the light palette and wakes Sage for the first time'
            SwingUtilities.invokeAndWait { app.state().update { it.withPalette(Palette.LIGHT) } }
            SwingUtilities.invokeAndWait { app.wake(sage) }
            waitUntil(sage) { it.phase() == Genie.Phase.READY }

        then: 'its lamp was made with the light waves'
            wallpaperIn(sage) == 'waves-light'
            desktopOf(sage) == 'waves-light'

        when: 'Juniper is put to sleep, and woken again in the light palette'
            SwingUtilities.invokeAndWait { app.sleep(juniper) }
            waitUntil(juniper) { it.phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil(juniper) { it.phase() == Genie.Phase.READY }

        then: 'it keeps the dark waves it was made with'
            wallpaperIn(juniper) == 'waves-dark'
            desktopOf(juniper) == 'waves-dark'
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /// The wallpaper the genie's lamp file names, as `display.wallpaper`.
    private String wallpaperIn(UUID genie) {
        Files.readString(app.lampOf(genie).resolve('oillamp.toml')).find(/(?m)^wallpaper\s*=\s*"([^"]+)"/) { it[1] }
    }

    /// The wallpaper the genie's sandbox desktop was told to draw, this session.
    private String desktopOf(UUID genie) {
        Files.readString(app.lampOf(genie).resolve('.oillamp/session/runtime.env')).find(/OILLAMP_WALLPAPER='([^']+)'/) { it[1] }
    }

    private void waitUntil(UUID id, Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition(app.state().get().find(id).orElseThrow())) return
            Thread.sleep(20)
        }
        var shown = app.state().get().find(id).orElseThrow()
        throw new AssertionError("never happened; the genie is ${shown.phase()} (${shown.activity()})" as Object)
    }
}
