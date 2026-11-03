package oillamp

import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Title

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Title('The pointer')
@Narrative('''
What the mouse commands of `lamp` send to the desktop.

`lamp click`, `move`, `drag` and `scroll` send pointer events to the desktop's VNC server, the way
the human's viewer does. These scenarios run the real `lamp` script and its real pointer helper,
`lamp-pointer`, against a small stand-in VNC server, which records every pointer event it receives:
the buttons held down, and the position. No container or desktop is needed.

Whether those events then reach a real application is checked by the desktop spike, which clicks
and types into an X11 program in a real sandbox.
''')
@Requires({ Files.isExecutable(Path.of('/usr/bin/python3')) })
class The_Pointer_Spec extends Specification {

    static final Path LAMP = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()
    static final Path HELPER = Path.of('src/main/resources/image/rootfs/usr/local/lib/oillamp/lamp-pointer').toAbsolutePath()
    static final int LEFT = 1, RIGHT = 4, WHEEL_DOWN = 16

    Path directory
    ServerSocketChannel server
    /** Every pointer event received, as [buttons, x, y]. */
    final List<List<Integer>> events = new CopyOnWriteArrayList<>()
    /** How many connections the server has seen to their end. */
    final AtomicInteger finished = new AtomicInteger()

    def setup() {
        // Short on purpose: a Unix socket path may be at most 107 bytes, and Spock's temporary
        // directories are named after the scenario.
        directory = Files.createTempDirectory(Path.of('/tmp'), 'lamp-pointer-')
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(directory.resolve('vnc.sock')))
        Thread.start { serveOneViewerAtATime(1280, 720) }
    }

    def cleanup() {
        server.close()
        directory.toFile().deleteDir()
    }

    def '`lamp click X Y` is one press and one release of the left button, at exactly that position'() {
        when: '`lamp click 300 200` is run'
            var result = lamp('click', '300', '200')

        then: 'the server received one press of the left button at 300,200, and then a release there'
            result.status == 0
            presses() == [[LEFT, 300, 200]]
            events.last() == [0, 300, 200]
    }

    def 'A double click needs no button name: `lamp click X Y double`'() {
        reportInfo """
            The button is optional, so `lamp click X Y double` is a left double click. It was once
            refused with "unknown button 'double'", although that is exactly what an agent
            types.
        """
        when: '`lamp click 300 200 double` is run'
            var result = lamp('click', '300', '200', 'double')

        then: 'the server received two presses of the left button at 300,200'
            result.status == 0
            presses() == [[LEFT, 300, 200], [LEFT, 300, 200]]
    }

    def 'A right click, `lamp click X Y right`, presses the right button'() {
        when: '`lamp click 40 50 right` is run'
            lamp('click', '40', '50', 'right')

        then: 'the server received one press of the right button at 40,50'
            presses() == [[RIGHT, 40, 50]]
    }

    def '`lamp drag` holds the button down all the way from one position to the other'() {
        when: '`lamp drag 100 100 500 300` is run'
            lamp('drag', '100', '100', '500', '300')

        then: 'it presses at the start, moves with the button held, and releases at the end'
            events.first() == [0, 100, 100]
            events[1] == [LEFT, 100, 100]
            events.findAll { it[0] == LEFT }.last() == [LEFT, 500, 300]
            events.last() == [0, 500, 300]
    }

    def '`lamp scroll` without a position scrolls where the last click was'() {
        given: 'a click at 640,100, after which the received events are forgotten'
            lamp('click', '640', '100')
            events.clear()

        when: '`lamp scroll 3` is run'
            lamp('scroll', '3')

        then: 'three steps of the wheel, at the position of the click'
            presses() == [[WHEEL_DOWN, 640, 100]] * 3
    }

    def '`lamp click` refuses a position outside the screen, and clicks nothing'() {
        when: '`lamp click 1280 10` is run on a screen 1280 wide'
            var result = lamp('click', '1280', '10')

        then: 'it fails, says the position is outside the 1280x720 screen, and pressed nothing'
            result.status != 0
            result.output.contains('outside the screen, which is 1280x720')
            presses().isEmpty()
    }

    def '`lamp info` reads the desktop\'s size from the greeting the VNC server sends a viewer'() {
        reportInfo """
            The stand-in desktop is 1280x720. The pointer helper connects, reads the size from the
            server's greeting, and sends nothing else.
        """
        when: '`lamp info` is run'
            var result = lamp('info')

        then: 'it reports `size 1280x720`, and sent no pointer event'
            result.status == 0
            result.output.readLines().first() == 'size       1280x720'
            events.isEmpty()
    }

    def 'When the desktop\'s VNC server cannot be reached, `lamp click` says so'() {
        given: 'the stand-in VNC server is closed, and its socket gone'
            server.close()
            Files.deleteIfExists(directory.resolve('vnc.sock'))

        when: '`lamp click 10 10` is run'
            var result = lamp('click', '10', '10')

        then: 'it exits with 3, and says it cannot reach the desktop\'s VNC server'
            result.status == 3
            result.output.contains("cannot reach the desktop's VNC server")
    }

    // ─── the stand-in VNC server and the lamp command ──────────────────────────────────────

    /** The events with a button or wheel held down. */
    private List<List<Integer>> presses() { events.findAll { it[0] != 0 } }

    private Map lamp(String... arguments) {
        int before = finished.get()
        var process = new ProcessBuilder(['bash', LAMP.toString()] + (arguments as List<String>))
        process.redirectErrorStream(true)
        process.environment().putAll(
                LAMP_POINTER: HELPER.toString(),
                LAMP_VNC_SOCKET: directory.resolve('vnc.sock').toString(),
                XDG_RUNTIME_DIR: directory.toString(),
                HOME: directory.toString(),
                OILLAMP_DISPLAY_WIDTH: '1280', OILLAMP_DISPLAY_HEIGHT: '720')
        var started = process.start()
        var output = started.inputStream.text
        started.waitFor()
        // The helper may exit before this server has read its last events.
        var deadline = System.currentTimeMillis() + 2000
        while (finished.get() == before && server.isOpen() && System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        [status: started.exitValue(), output: output]
    }

    /**
     * The server half of the VNC handshake (RFB 3.8, no password), then every pointer event the
     * client sends: message type 5, a button mask, and the position.
     */
    private void serveOneViewerAtATime(int width, int height) {
        while (server.isOpen()) {
            try (var client = server.accept()) {
                var input = new DataInputStream(Channels.newInputStream(client))
                var output = new DataOutputStream(Channels.newOutputStream(client))
                output.write('RFB 003.008\n'.bytes)
                input.readFully(new byte[12])                       // the client's version
                output.write([1, 1] as byte[])                     // one security type: none
                input.readByte()                                   // the client picks it
                output.writeInt(0)                                 // security result: OK
                input.readByte()                                   // shared session or not
                output.writeShort(width); output.writeShort(height)
                output.write(new byte[16])                         // pixel format
                output.writeInt(0)                                 // an empty desktop name
                output.flush()
                while (true) {
                    int type = input.read()
                    if (type < 0) break
                    assert type == 5, "lamp sent a message that is not a pointer event: type $type"
                    events.add([input.readUnsignedByte(), input.readUnsignedShort(), input.readUnsignedShort()])
                }
            } catch (IOException closed) {
                // The server was closed at the end of a scenario, or a client went away.
            } finally {
                finished.incrementAndGet()
            }
        }
    }
}
