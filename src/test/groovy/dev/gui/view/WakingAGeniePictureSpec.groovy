package dev.gui.view

import dev.gui.model.Genie
import spock.lang.Specification
import spock.lang.Narrative
import spock.lang.Subject
import spock.lang.Title

import java.awt.image.BufferedImage

@Title('Waking a genie: a truthful start and a moment to say hello')
@Narrative('''

The wake picture should make a genie feel present without claiming that its sandbox is ready
before it is. While startup takes its time, the lamp burns and progress reports continue.
Once ready, that genie's own avatar takes form from the flame and waves before the conversation
returns. The greeting is presentation only: it must not delay the genie's ability to work.

Failure, sleeping, retrying and changing the selected genie must leave no stale greeting behind.
These scenarios advance immutable presentation values explicitly, without waiting for a real
sandbox or a wall clock. The rendering scenario checks that the shared welcome scene actually
draws the expected genie, not just that the presentation records its identity.

''')
@Subject([WakeScene, WelcomeScene])
class WakingAGeniePictureSpec extends Specification {

    def 'A long start keeps the lamp burning until the real genie is ready'() {
        reportInfo '''
            **Subject:** the wake picture while the sandbox is starting.

            The first startup can take minutes. An elapsed animation must not make the genie pop
            out and imply that it is ready while the sandbox is still being built. Its lamp
            should keep burning until the real lifecycle reports readiness; only then should
            the flame-to-genie sequence begin. Startup progress belongs to the real lifecycle,
            not to an invented percentage derived from the animation clock.
        '''
        given: 'Juniper has started waking'
            def genie = Genie.named('Juniper')
            def starting = WakeScene.of(genie).follows(genie.waking())

        when: 'two minutes pass without the sandbox becoming ready'
            def waiting = starting.after(120)

        then: 'the lamp still animates in the burning part of the scene, with no emerging genie'
            starting.moving()
            waiting.moving()
            !waiting.emerging()
            waiting.moment() >= 3.6 && waiting.moment() < 4.6

        when: 'the actual genie becomes ready'
            def ready = waiting.follows(genie.waking().awake())

        then: 'emergence starts from the beginning, however long startup took'
            ready.emerging()
            ready.moment() == 4.6
    }

    def 'After taking form, the genie has time to wave before its conversation returns'() {
        reportInfo '''
            **Subject:** the greeting between readiness and returning to the conversation.

            The emergence is a greeting, not merely a transition flash. The user should have
            time to recognise the genie and see it wave after it takes form. The 2.2-second
            emergence is followed by another 1.6 seconds of animation, then the conversation
            may return. Advancing the scene during that pause matters: holding a still image
            would give the user more time, but would lose the wave itself.
        '''
        given: 'Juniper has just become ready after waking'
            def genie = Genie.named('Juniper')
            def ready = WakeScene.of(genie.waking()).follows(genie.waking().awake())

        expect: 'the greeting continues after formation and the shared scene keeps advancing'
            ready.after(2.2).emerging()
            ready.after(3).moving()
            ready.after(3).moment() > WelcomeScene.FORMED
            ready.after(3.5).moment() > ready.after(2.2).moment()

        and: 'the presentation releases the conversation after the greeting ends'
            !ready.after(3.9).emerging()
            !ready.after(4).moving()
    }

    def 'A quick start still forms the right avatar and does not delay the real lifecycle'() {
        reportInfo '''
            **Subject:** avatar identity and readiness during a fast startup.

            Even when the sandbox starts almost immediately, the user should see their own
            genie emerge, with the colour and outfit used on its card. The greeting must not
            keep a ready genie waiting: it can already answer a message while the picture
            finishes. Moving from ready to working must preserve that greeting rather than
            restart it. Selecting a genie that was already awake is not a new wake and should
            not manufacture another emergence.
        '''
        given: 'Juniper becomes ready after only a tenth of a second'
            def genie = Genie.named('Juniper')
            def ready = WakeScene.of(genie.waking()).after(0.1).follows(genie.waking().awake())
            def working = ready.after(1).follows(genie.withPhase(Genie.Phase.WORKING))
        expect: 'the picture greets with Juniper’s avatar while readiness and work take effect immediately'
            ready.phase() == Genie.Phase.READY
            ready.emerging()
            working.emerging()
            working.released() == ready.released()
            working.play().pip() == GenieSvgUtil.appearanceOf(genie.id())
            !WakeScene.of(genie.withPhase(Genie.Phase.READY)).moving()
    }

    def 'A failed wake stops the animation, and retry lights the lamp afresh'() {
        reportInfo '''
            **Subject:** failure and a subsequent retry.

            A failure must stop the hopeful startup animation; it must not eventually show a
            successful emergence merely because time passed. When the user tries again, the
            new attempt starts with the lamp catching, without inheriting elapsed time or a
            pending greeting from the failed attempt.
        '''
        given: 'Juniper has spent ten seconds starting its sandbox'
            def genie = Genie.named('Juniper')
            def starting = WakeScene.of(genie.waking()).after(10)

        when: 'startup fails because no model is available'
            def broken = starting.follows(genie.broken('No model'))

        then: 'no startup or successful greeting animation continues'
            !broken.moving()
            broken.moment() == 1

        when: 'the user retries waking Juniper'
            def retry = broken.follows(genie.waking())

        then: 'the new attempt animates from the beginning, without a previously released genie'
            retry.moving()
            retry.seconds() == 0
            retry.released() == -1
    }

    def 'Putting the genie to sleep cancels its unfinished greeting'() {
        reportInfo '''
            **Subject:** sleeping while the wake greeting is still visible.

            The user can put a ready genie to sleep before its greeting finishes. The picture
            must follow that choice immediately: continuing to form or wave would contradict
            the sleeping state. No remaining greeting should outlive the lifecycle it depicts.
        '''
        given: 'Juniper is partway through its emergence'
            def genie = Genie.named('Juniper')
            def emerging = WakeScene.of(genie.waking()).follows(genie.waking().awake()).after(0.5)

        expect: 'sleeping cancels the greeting and returns the scene to the cold lamp'
            !emerging.follows(genie.asleep()).moving()
            emerging.follows(genie.asleep()).moment() == 1
    }

    def 'Selecting another genie never carries the previous genie’s greeting across'() {
        reportInfo '''
            **Subject:** the wake picture when the selected genie changes.

            A greeting belongs to one genie. Switching to Sami must not leave Juniper emerging
            above Sami's name, nor borrow Juniper's elapsed startup time. A sleeping Sami gets
            its sleeping picture; a Sami that is still waking gets a fresh view of its own
            starting lamp. In both cases the appearance must come from Sami's identity.
        '''
        given: 'Juniper is emerging and Sami is another genie in the list'
            def genie = Genie.named('Juniper')
            def starting = WakeScene.of(genie.waking()).after(10)
            def emerging = starting.follows(genie.waking().awake()).after(0.5)
            def other = Genie.named('Sami')

        expect: 'selecting sleeping Sami discards Juniper’s greeting and uses Sami’s appearance'
            !emerging.follows(other).moving()
            emerging.follows(other).id() == other.id()
            emerging.follows(other).play().pip() == GenieSvgUtil.appearanceOf(other.id())

        and: 'selecting waking Sami begins its own startup picture'
            starting.follows(other.waking()).moving()
            starting.follows(other.waking()).seconds() == 0
    }

    def 'Progress reports do not restart the picture and the completed scene contains its genie'() {
        reportInfo '''
            **Subject:** progress updates and rendering the genie from the shared welcome scene.

            A startup may report many steps. Updating the status text should not repeatedly
            extinguish and relight the picture: the presentation must continue uninterrupted.
            Once ready, the welcome renderer must visibly form the selected genie's avatar.
            Checking pixels in its own body colour verifies that the shared scene draws the
            genie, rather than leaving a lit lamp despite recording the correct identity.
        '''
        given: 'Juniper’s lamp has been lighting for two seconds and reports an image build'
            def genie = Genie.named('Juniper')
            def waiting = WakeScene.of(genie.waking()).after(2)
            def reported = waiting.follows(genie.waking().lampSays('building the image'))
            def formed = reported.follows(genie.waking().awake()).after(3)
            def picture = new BufferedImage(320, 220, BufferedImage.TYPE_INT_RGB)
            def graphics = picture.createGraphics()
        when: 'Juniper becomes ready and its formed greeting is painted'
            WelcomeScene.paint(graphics, 320, 220, formed.play(), formed.moment())
            graphics.dispose()
        then: 'the progress report preserved the presentation and Juniper’s body colour appears in the picture'
            reported == waiting
            def colour = GenieSvgUtil.appearanceOf(genie.id()).colour().getRGB()
            (0..<320).any { x -> (0..<220).any { y -> picture.getRGB(x, y) == colour } }
    }
}
