package dev.oillamp

import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Title

import sprouts.Tuple

import java.nio.file.Files

@Title('Resolving a host path')
@Narrative('''
A host request names a path the agent wrote, so nothing about it is trusted. oillamp resolves it
on the host before anything looks at it, and the lamp's `host_access.roots` decide whether it may
be touched at all. No model is asked about the roots: a path outside them is a mistake or an
attempt, not a judgement call.

```
 "~/Documents/notes/today.md"
        │ ~ is the user's home on the host, never the sandbox's /home/agent
        ▼
 /home/me/Documents/notes/today.md        "." and ".." are refused here
        │ the part that exists is resolved with its links followed;
        │ the rest, about to be created, is put back as written
        ▼
 /data/me/Documents/notes/today.md        (Documents was a link)
        │
        ▼ under one of the roots, also resolved?   no ─▶ refused, OIL-HOSTACCESS-003
```

These scenarios use real temporary directories and links, and nothing else.
''')
class ResolvingHostPathsSpec extends Specification {

    def 'A path is resolved on the host, and the roots decide whether it may be touched'() {
        reportInfo """
            The agent writes the path, so oillamp resolves it on the host before anything else
            looks at it. A leading tilde is the user's home, not the shell's idea of it, and a
            link on the way is followed once, here, so that the path judged is the path used.

            The roots are a hard bound and are enforced without asking a model: a path outside
            them is a mistake or an attempt, not a judgement call.
        """
        given: 'a home directory with a document, and a link that points out of it'
            var home = File.createTempDir('host-home', '').toPath().toRealPath()
            var outside = File.createTempDir('host-outside', '').toPath().toRealPath()
            Files.createDirectories(home.resolve('Documents'))
            Files.writeString(home.resolve('Documents/q3.md'), '# Q3\n')
            Files.createSymbolicLink(home.resolve('Documents/escape'), outside)
            var roots = sprouts.Tuple.of(String, home.resolve('Documents').toString())

        expect: 'a document under a root is fine'
            var ok = HostPaths.resolve('~/Documents/q3.md', home, roots)
            ok instanceof Result.Ok
            (ok as Result.Ok).value() == new HostPaths.ResolvedPath('~/Documents/q3.md', home.resolve('Documents/q3.md'))

        and: 'a file that does not exist yet is fine as long as where it would go is'
            var later = HostPaths.resolve('~/Documents/notes/today.md', home, roots)
            later instanceof Result.Ok
            (later as Result.Ok).value().hostPath() == home.resolve('Documents/notes/today.md')

        and: 'a link that leaves the roots is judged by where it points'
            var escaped = HostPaths.resolve('~/Documents/escape/secret.txt', home, roots)
            escaped instanceof Result.Err
            (escaped as Result.Err).problems().first().code().value() == 'OIL-HOSTACCESS-003'

        and: 'and so is a path outside them from the start'
            var outsidePath = HostPaths.resolve(outside.resolve('secret.txt').toString(), home, roots)
            outsidePath instanceof Result.Err

        cleanup:
            home.toFile().deleteDir()
            outside.toFile().deleteDir()
    }

    def 'A path that walks back with .. is refused, so the path judged is the path written'() {
        reportInfo """
            A part that does not exist yet is put back as it was written, so that a file about to
            be created can be checked. Were `..` allowed there, `Documents/new/../../.ssh` would
            look like a path under Documents while naming one outside it. So `.` and `..` are
            refused outright, and so is a path that is not absolute: the agent names the place
            itself.
        """
        given:
            var home = File.createTempDir('host-home', '').toPath().toRealPath()
            Files.createDirectories(home.resolve('Documents'))
            Files.createDirectories(home.resolve('.ssh'))
            var roots = Tuple.of(String, home.resolve('Documents').toString())

        expect:
            var refused = HostPaths.resolve(asked.replace('HOME', home.toString()), home, roots)
            refused instanceof Result.Err
            (refused as Result.Err).problems().first().code().value() == 'OIL-HOSTACCESS-003'

        cleanup:
            home.toFile().deleteDir()

        where:
            asked << ['HOME/Documents/new/../../.ssh/authorized_keys',
                      '~/Documents/../.ssh/id_ed25519',
                      '~/Documents/./q3.md',
                      'Documents/q3.md']
    }

    def 'A root given through a link is the place the link points to'() {
        reportInfo """
            Paths are compared after their links are followed, so a root has to be too: a user
            whose Documents is a link to another disk writes the link's path in the roots, and
            the files there are inside them.
        """
        given:
            var home = File.createTempDir('host-home', '').toPath().toRealPath()
            var disk = File.createTempDir('host-disk', '').toPath().toRealPath()
            Files.writeString(disk.resolve('q3.md'), '# Q3')
            Files.createSymbolicLink(home.resolve('Documents'), disk)

        expect:
            HostPaths.resolve('~/Documents/q3.md', home, Tuple.of(String, home.resolve('Documents').toString())) instanceof Result.Ok

        cleanup:
            home.toFile().deleteDir()
            disk.toFile().deleteDir()
    }
}
