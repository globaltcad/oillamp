package gui

import dev.gui.Genies
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Home
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

@Title('A genie\'s folders, shown as a tree and linked into the user\'s own')
@Narrative('''
A genie's home is a folder on the user's computer, inside the genie's lamp, which its sandbox
sees as `/home/agent`. Genies keeps its lamps deep in its data directory, so the Actions page
shows where the home is, and its folders as a tree. Any of them can be linked into a folder of the
user's own, such as their documents, with a symbolic link, to find what the genie made from a
file manager. Genies keeps no list of the links it made.

Here the app is driven as its window drives it, through `Genies`; its lamps are oillamp's real
engine, in this JVM, on a simulated machine, with a stand-in pi. Its home and the user's folders
are real folders in a temporary directory.

Each scenario reads on its own, and shows how the window talks to the app: every call to
`Genies`, and every read of its state, is written out where it happens, on Swing's event
thread as the window makes it. Helpers that do not touch the app, such as waiting for a
condition, are fine.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class LinkingAGeniesFoldersSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    UUID juniper
    /// The user's home folder, as `HOME` names it where Genies starts.
    Path user

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80))
                            .agent { String prompt -> 'You said: ' + prompt } }
        user = Files.createDirectories(tmp.resolve('user'))
        // Genies opens as it closed last, with Juniper on its shelf, never woken, and a key in its environment.
        var genie = Genie.named('Juniper')
        juniper = genie.id()
        app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, genie), Settings.defaults(), sandbox.launcher,
                          { name -> name == Settings.KEY_VARIABLE ? Optional.of('sk-key')
                                  : name == 'HOME' ? Optional.of(user.toString()) : Optional.<String>empty() })
    }

    def cleanup() {
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        done.await(3, TimeUnit.MINUTES)
    }

    def 'Genies finds no home for a genie that never woke, and links nothing to it'() {
        reportInfo '''
            Let's consider a genie that was made but never woke. Its lamp makes its home as it
            first wakes, so there is none yet. Opening its Actions page has Genies look for the
            home, and `Home.where()` stays empty; `Genies.link` throws, with a message for the
            user that says why, rather than making a link that leads nowhere.
        '''
        when: 'the user opens the Actions page of Juniper, who never woke'
            SwingUtilities.invokeAndWait { app.state().update { it.select(juniper).withPage(GeniesState.Page.ACTIONS) } }

        then: 'Genies looked, and found no home'
            waitUntil { app.state().get().find(juniper).orElseThrow().home().read() }
            app.state().get().find(juniper).orElseThrow().home().where().isEmpty()

        when: 'the user links the workspace into their documents anyway'
            IOException refused = null
            SwingUtilities.invokeAndWait {
                try { app.link(juniper, Home.WORKSPACE, app.documents(), 'Juniper - workspace') }
                catch (IOException failed) { refused = failed }
            }

        then:
            refused.message == 'Juniper has no home yet. It gets one as it first wakes.'
            !Files.exists(user.resolve('Documents/Juniper - workspace'))
    }

    def 'The tree shows the home\'s folders, without the hidden ones, two levels deep as each is opened'() {
        reportInfo '''
            In this scenario a genie woke once and wrote a project into its workspace, with a
            `.git` folder in it, as any repository has. Opening the Actions page has Genies read
            the home: the folders in it, and the folders in each of those, so that the tree knows
            which of its folders can be opened. Names starting with a dot are left out, both the
            tools' own, such as `.pi`, and the project's `.git`. A folder further down is read
            once the user opens its parent, with `Genies.readHome`.
        '''
        given: 'Juniper woke once, and has a project in its workspace'
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil { app.state().get().find(juniper).orElseThrow().phase() == Genie.Phase.READY }
            Path workspace = homeOfJuniper().resolve('workspace')
            Files.createDirectories(workspace.resolve('lantern/src/main'))
            Files.createDirectories(workspace.resolve('lantern/.git'))
            Files.writeString(workspace.resolve('lantern/README.md'), 'A lantern.')

        when: 'the user opens its Actions page'
            SwingUtilities.invokeAndWait { app.state().update { it.select(juniper).withPage(GeniesState.Page.ACTIONS) } }

        then: 'the home is where its lamp keeps it'
            waitUntil { app.state().get().find(juniper).orElseThrow().home().root().find('workspace').isPresent() }
            app.state().get().find(juniper).orElseThrow().home().where() == Optional.of(homeOfJuniper())

        and: 'its folders are there, but none whose name starts with a dot'
            var root = app.state().get().find(juniper).orElseThrow().home().root()
            root.name() == Home.HOME
            root.folders()*.name().contains('workspace')
            root.folders()*.name().every { !it.startsWith('.') }

        and: 'the workspace shows its project, but not yet what is in it'
            root.at(Home.WORKSPACE).orElseThrow().folders()*.name() == ['lantern']
            root.at(Tuple.of(String, 'workspace', 'lantern')).orElseThrow().folders().isEmpty()

        when: 'the user opens the workspace in the tree'
            SwingUtilities.invokeAndWait { app.readHome(juniper, Home.WORKSPACE) }

        then: 'the project shows its folders, without .git and without its files'
            waitUntil {
                app.state().get().find(juniper).orElseThrow().home().root()
                   .at(Tuple.of(String, 'workspace', 'lantern')).orElseThrow().folders()*.name() == ['src']
            }
    }

    def 'Linking the workspace into the documents makes a link named after the genie, and a second time keeps it'() {
        reportInfo '''
            Let's consider a user who wants to find Juniper's work in their documents. Genies
            puts a symbolic link into `~/Documents`, called "Juniper - workspace", as
            `Home.linkName` names it, that leads to the workspace in Juniper's home. A file
            written there through the link is in the workspace. Linking again finds the same link
            there and keeps it, and `Home.Link.madeNow()` says so, rather than making a second one.
        '''
        given: 'Juniper woke once'
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil { app.state().get().find(juniper).orElseThrow().phase() == Genie.Phase.READY }

        expect: 'the documents are where HOME says, in Documents'
            app.documents() == user.resolve('Documents')

        when: 'the user links its workspace into their documents, by the name Genies offers'
            Home.Link link
            SwingUtilities.invokeAndWait {
                link = app.link(juniper, Home.WORKSPACE, app.documents(), Home.linkName('Juniper', Home.WORKSPACE))
            }

        then: 'the documents have a link to the workspace'
            link.madeNow()
            link.at() == user.resolve('Documents/Juniper - workspace')
            Files.isSymbolicLink(link.at())
            Files.readSymbolicLink(link.at()) == homeOfJuniper().resolve('workspace')

        and: 'what is written through it is in the workspace'
            Files.writeString(link.at().resolve('note.txt'), 'from the user')
            Files.readString(homeOfJuniper().resolve('workspace/note.txt')) == 'from the user'

        when: 'the user links it again'
            Home.Link again
            SwingUtilities.invokeAndWait { again = app.link(juniper, Home.WORKSPACE, app.documents(), 'Juniper - workspace') }

        then: 'the link that was there is kept'
            !again.madeNow()
            again.at() == link.at()
            Files.list(user.resolve('Documents')).count() == 1
    }

    def 'A name taken by something else gets a number, and a name with a slash is refused'() {
        reportInfo '''
            In the rare case that the user's folder already holds something called
            "Juniper - workspace" that is not a link to the workspace, such as a folder of their
            own, Genies leaves it alone and calls the link "Juniper - workspace 2". The menu of a
            genie's card links with one click and no question, so it must never fail for a name.
            A name the user typed with a slash in it would put the link elsewhere, so
            `Genies.link` throws for it instead.
        '''
        given: 'Juniper woke once, and the user has a folder of that name in their projects'
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil { app.state().get().find(juniper).orElseThrow().phase() == Genie.Phase.READY }
            Path projects = Files.createDirectories(user.resolve('Projects/Juniper - workspace')).parent

        when: 'the user links the workspace into their projects'
            Home.Link link
            SwingUtilities.invokeAndWait { link = app.link(juniper, Home.WORKSPACE, projects, 'Juniper - workspace') }

        then: 'their folder is kept, and the link is numbered'
            link.at() == projects.resolve('Juniper - workspace 2')
            Files.isSymbolicLink(link.at())
            Files.isDirectory(projects.resolve('Juniper - workspace'))
            !Files.isSymbolicLink(projects.resolve('Juniper - workspace'))

        when: 'the user names a link with a slash'
            IOException refused = null
            SwingUtilities.invokeAndWait {
                try { app.link(juniper, Home.WORKSPACE, projects, 'work/space') }
                catch (IOException failed) { refused = failed }
            }

        then:
            refused.message == 'A link needs a name without a slash, such as "workspace".'
            !Files.exists(projects.resolve('work'))
    }

    def 'A link the genie made in its home is neither shown in the tree nor linked to'() {
        reportInfo '''
            The genie writes its home, so it can put a symbolic link there that leads anywhere on
            the user's computer, such as to their `.ssh` folder. Followed, the tree would show
            that folder as the genie's, and the user would link it into their documents as such.
            So Genies follows no link in a genie's home: `Genies.readHome` leaves it out of the
            tree, `Genies.folderOf` finds no folder there, and `Genies.link` throws.
        '''
        given: 'Juniper woke once, and made a link in its workspace to a folder of the user\'s'
            SwingUtilities.invokeAndWait { app.wake(juniper) }
            waitUntil { app.state().get().find(juniper).orElseThrow().phase() == Genie.Phase.READY }
            Path secrets = Files.createDirectories(user.resolve('.ssh'))
            Files.createDirectories(homeOfJuniper().resolve('workspace/real'))
            Files.createSymbolicLink(homeOfJuniper().resolve('workspace/keys'), secrets)

        when: 'the user opens the workspace in the tree'
            SwingUtilities.invokeAndWait { app.readHome(juniper, Home.WORKSPACE) }

        then: 'only the real folder is there'
            waitUntil {
                app.state().get().find(juniper).orElseThrow().home().root()
                   .at(Home.WORKSPACE).map { it.folders()*.name() }.orElse([]) == ['real']
            }

        and: 'the link leads to no folder of the genie\'s'
            Optional<Path> found
            SwingUtilities.invokeAndWait { found = app.folderOf(juniper, Tuple.of(String, 'workspace', 'keys')) }
            found.isEmpty()

        when: 'the user links it anyway'
            IOException refused = null
            SwingUtilities.invokeAndWait {
                try { app.link(juniper, Tuple.of(String, 'workspace', 'keys'), app.documents(), 'keys') }
                catch (IOException failed) { refused = failed }
            }

        then:
            refused.message == "Juniper's home has no folder workspace/keys."
            !Files.exists(user.resolve('Documents/keys'))
    }

    def 'The documents are the folder the desktop\'s user-dirs.dirs names'() {
        reportInfo '''
            Not every desktop calls the documents folder "Documents": a German one has
            "Dokumente", and a user may have moved it. The file manager learns where it is from
            `~/.config/user-dirs.dirs`, and so does Genies: `Genies.documents()` is the folder
            its `XDG_DOCUMENTS_DIR` line names, so that a genie's workspace lands where the file
            manager shows the documents.
        '''
        given: 'the desktop calls the documents "Dokumente"'
            Files.createDirectories(user.resolve('.config'))
            Files.writeString(user.resolve('.config/user-dirs.dirs'),
                    '# written by xdg-user-dirs-update\nXDG_DESKTOP_DIR="$HOME/Schreibtisch"\nXDG_DOCUMENTS_DIR="$HOME/Dokumente"\n')

        when: 'Genies opens again, as it does when the user starts it'
            Genies reopened = Genies.open(sandbox.home.resolve('genies-again'), Tuple.of(Genie), Settings.defaults(), sandbox.launcher,
                    { name -> name == 'HOME' ? Optional.of(user.toString()) : Optional.<String>empty() })

        then:
            reopened.documents() == user.resolve('Dokumente')

        cleanup:
            var done = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { reopened?.quit(false, Optional.empty(), { done.countDown() }) }
            done.await(30, TimeUnit.SECONDS)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /// Juniper's home in its lamp, as oillamp's own API finds it.
    private Path homeOfJuniper() {
        dev.lamp.Lamp.agentHome(app.lampOf(juniper)).orElseThrow()
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        var shown = app.state().get().find(juniper).orElseThrow()
        throw new AssertionError("never happened; the genie is ${shown.phase()} (${shown.activity()}), " +
                                 "its home ${shown.home()}" as Object)
    }
}
