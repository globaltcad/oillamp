package dev.gui.view

import dev.gui.Genies
import dev.gui.model.*
import oillamp.ScenarioHost
import spock.lang.*
import sprouts.Tuple

import javax.swing.*
import java.awt.Container
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Acting on a genie from its Actions page')
@Narrative('''
A genie's Actions page gathers what can be done with it as a whole: renaming it, starting a new
conversation, saving its home, sleeping or waking it, looking into its sandbox in windows of
their own, and deleting it. In a narrow window it is
the only way to them besides a right-click, so it must work by touch: the name is edited where it
stands rather than in a dialog, and an action that leads elsewhere takes the user there.

The scenarios open the real Genies window against oillamp's simulated machine, and press its
buttons as the user would.
''')
@Subject([ActionsPage, GeniesView])
class ActingOnAGenieSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    GeniesView view

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)) }
        var genie = Genie.named('Juniper')
        app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, Genie.asleep(genie.id(), genie.name())),
                Settings.defaults(), sandbox.launcher, { Optional.of('sk-key') })
        SwingUtilities.invokeAndWait {
            view = new GeniesView(app)
            app.state().update { it.withPage(GeniesState.Page.ACTIONS) }
        }
    }

    def cleanup() {
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        if (app != null) assert done.await(30, TimeUnit.SECONDS)
    }

    def 'Starting a new conversation from the Actions page shows the chat'() {
        reportInfo '''
            The new conversation is where the user wants to be next, so the page gives way to the
            chat, as choosing a conversation in the genie's list does.
        '''
        when: 'the user presses "Start a new conversation"'
            SwingUtilities.invokeAndWait { button('Start a new conversation').doClick() }

        then:
            app.state().get().page() == GeniesState.Page.CHAT
    }

    def 'The name is renamed where it stands, and Enter keeps it'() {
        reportInfo '''
            The tile shows the genie's name in a field that cannot be typed in. Rename makes it
            editable, with the name selected to type over; Enter keeps the new name. Until then,
            the genie keeps the one it had.
        '''
        given:
            JTextField field
            SwingUtilities.invokeAndWait { field = nameField() }

        expect: 'the name, not to be typed in'
            field.text == 'Juniper'
            !field.editable

        when: 'the user presses Rename and types a new name'
            SwingUtilities.invokeAndWait { besideTheName(field, 'Rename').doClick() }
            SwingUtilities.invokeAndWait { field.text = 'Juniper the Wise' }

        then: 'the field can be typed in, and the genie is not renamed yet'
            field.editable
            app.state().get().genie().name() == 'Juniper'

        when: 'the user presses Enter'
            SwingUtilities.invokeAndWait { field.postActionEvent() }
            SwingUtilities.invokeAndWait { }

        then: 'the genie has its new name, and the field is the name again'
            app.state().get().genie().name() == 'Juniper the Wise'
            !field.editable
    }

    def 'Escape gives up renaming, and the genie keeps its name'() {
        given:
            JTextField field
            SwingUtilities.invokeAndWait { field = nameField() }

        when: 'the user starts renaming, types, and presses Escape'
            SwingUtilities.invokeAndWait { besideTheName(field, 'Rename').doClick() }
            SwingUtilities.invokeAndWait { field.text = 'Somebody else' }
            SwingUtilities.invokeAndWait { press(field, KeyEvent.VK_ESCAPE) }
            SwingUtilities.invokeAndWait { }

        then:
            app.state().get().genie().name() == 'Juniper'
            field.text == 'Juniper'
            !field.editable
    }

    def 'Cancel gives up renaming too'() {
        given:
            JTextField field
            SwingUtilities.invokeAndWait { field = nameField() }

        when:
            SwingUtilities.invokeAndWait { besideTheName(field, 'Rename').doClick() }
            SwingUtilities.invokeAndWait { field.text = 'Somebody else' }
            SwingUtilities.invokeAndWait { besideTheName(field, 'Cancel').doClick() }
            SwingUtilities.invokeAndWait { }

        then:
            app.state().get().genie().name() == 'Juniper'
            field.text == 'Juniper'
            !field.editable
    }

    def 'A blank name is not kept'() {
        given:
            JTextField field
            SwingUtilities.invokeAndWait { field = nameField() }

        when:
            SwingUtilities.invokeAndWait { besideTheName(field, 'Rename').doClick() }
            SwingUtilities.invokeAndWait { field.text = '   ' }
            SwingUtilities.invokeAndWait { field.postActionEvent() }
            SwingUtilities.invokeAndWait { }

        then: 'the genie keeps its name, and the user can go on typing'
            app.state().get().genie().name() == 'Juniper'
            field.editable
    }

    def 'The "Behind the scenes" tile opens no window onto the sandbox of a genie that sleeps'() {
        reportInfo '''
            The "Behind the scenes" tile has three buttons: "Shell", "Desktop" and "Follow". Each
            opens a window onto the genie's sandbox, and a genie that sleeps has no sandbox
            running. So the three buttons cannot be pressed until the genie is awake.
        '''
        expect: 'Juniper sleeps, and none of the three buttons can be pressed'
            app.state().get().genie().phase() == Genie.Phase.ASLEEP
            !button('Shell').enabled
            !button('Desktop').enabled
            !button('Follow').enabled
    }

    def 'Pressing "#pressed" has the awake genie\'s lamp run `oillamp #command` for a window of its own'() {
        reportInfo '''
            The three buttons of the "Behind the scenes" tile do what three `oillamp` commands do
            at a terminal, for the user who has none open. "Shell" opens a terminal with a shell
            in the genie's sandbox, "Desktop" the sandbox's desktop in a viewer, and "Follow" a
            terminal that prints what the genie's lamp reports. Genies opens none of these
            windows itself: it starts oillamp's engine with the command, on the genie's lamp
            directory, and the session of the lamp opens the window. The scenario checks the
            command line the engine was started with.
        '''
        given: 'Juniper is awake'
            var juniper = app.state().get().genie().id()
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil { app.state().get().genie().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { }

        when: 'the user presses the button'
            SwingUtilities.invokeAndWait { button(pressed).doClick() }

        then: 'an engine was started with the command, on the lamp directory of Juniper'
            var lamp = app.lampOf(juniper).toString()
            waitUntil { sandbox.engines.any { it.arguments == [command.split(' ')[0], lamp, *command.split(' ').drop(1), '--embedded'] } }

        where:
            pressed   | command
            'Shell'   | 'shell --window'
            'Desktop' | 'view'
            'Follow'  | 'follow --window'
    }

    def 'The last two tiles stand side by side in a window of medium width, and one under the other in a narrow and in a wide one'() {
        reportInfo '''
            The page ends with two tiles apart from the rest: "Behind the scenes", then "Delete",
            which is always the last. In a narrow window every tile takes the whole width, so
            the two stand one under the other. In a window of medium width they stand side by
            side. In a wide window the two share one column beside the "Folders" tile, where
            they stand one under the other again. The scenario finds each tile by a button in
            it: "Shell", and "Delete Juniper…".
        '''
        given: 'the Genies window, laid out without being shown'
            JFrame frame
            SwingUtilities.invokeAndWait {
                frame = new JFrame()
                frame.contentPane.add(view)
                frame.pack()
            }

        expect: 'at each width, where the "Delete Juniper…" button stands, seen from the "Shell" button'
            whereDeleteStands(frame, 460) == 'under'
            whereDeleteStands(frame, 1000) == 'beside'
            whereDeleteStands(frame, 1750) == 'under'

        and: 'in the wide window, both stand to the right of the "Folders" tile\'s "Link into…" button'
            SwingUtilities.invokeAndWait { }
            at(button('Shell')).x > at(button('Link into…')).x + 400

        cleanup:
            SwingUtilities.invokeAndWait { frame?.dispose() }
    }

    /// Gives the window `width`, and says whether the "Delete Juniper…" button then stands
    /// 'under' the "Shell" button, at the same left edge, or 'beside' it, further right at
    /// about the same height.
    private String whereDeleteStands(JFrame frame, int width) {
        SwingUtilities.invokeAndWait { frame.setSize(width, 1200); frame.validate() }
        // The page learns its new width after the resize, and lays its tiles out once more.
        3.times { Thread.sleep(200); SwingUtilities.invokeAndWait { frame.validate() } }
        var shell = at(button('Shell'))
        var delete = at(button('Delete Juniper…'))
        if (delete.x == shell.x && delete.y > shell.y + 40) return 'under'
        if (delete.x > shell.x + 100 && Math.abs(delete.y - shell.y) < 40) return 'beside'
        "neither: Shell at ${shell}, Delete at ${delete}"
    }

    /// Where `component` stands in the window.
    private java.awt.Point at(JComponent component) {
        java.awt.Point found = null
        SwingUtilities.invokeAndWait { found = SwingUtilities.convertPoint(component.parent, component.location, view) }
        found
    }

    private static void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("never got there: ${condition}" as Object)
    }

    private JButton button(String text) {
        descendants(view).find { it instanceof JButton && it.text == text } as JButton
    }

    /// A button in the row of the name's field, such as its Rename, Cancel or Save.
    private static JButton besideTheName(JTextField field, String text) {
        descendants(field.parent).find { it instanceof JButton && it.text == text } as JButton
    }

    /// A key pressed in `field`, given to its listeners directly: a window that is not on screen
    /// has no keyboard focus, to which Swing would send it otherwise.
    private static void press(JTextField field, int key) {
        var event = new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, key, KeyEvent.CHAR_UNDEFINED)
        field.keyListeners.each { it.keyPressed(event) }
    }

    /// The only text field on the Actions page: the composer and the settings' fields are text
    /// areas, or on other pages.
    private JTextField nameField() {
        descendants(view).find { it instanceof JTextField && it.text == 'Juniper' } as JTextField
    }

    private static List descendants(Container parent) {
        parent.components.collectMany { it instanceof Container ? [it] + descendants(it) : [it] }
    }
}
