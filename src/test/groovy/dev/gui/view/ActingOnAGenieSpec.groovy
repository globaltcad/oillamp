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
conversation, saving its home, sleeping or waking it, and deleting it. In a narrow window it is
the only way to them besides a right-click, so it must work by touch: the name is edited where it
stands rather than in a dialog, and an action that leads elsewhere takes the user there.

The scenarios open the real Genies window against oillamp's simulated machine, and press its
buttons as the user would.
''')
@Subject([ActionsPage, GeniesView])
class ActingOnAGenieSpec extends Specification {

    @TempDir Path tmp
    Genies app
    GeniesView view

    def setup() {
        var sandbox = new ScenarioHost(tmp)
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
