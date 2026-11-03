package oillamp

import dev.lamp.LampExitStatus
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

@Title('Giving the agent a git identity')
@Narrative('''
At the start of each session, oillamp gives the agent a git identity: the name and email on its
commits.

This came from real use. The sandbox had no git identity, so git refused the agent's first commit,
and the agent fixed that the obvious way: `git config user.name "opencode agent"`, inside the
repository. The repository lives in the lamp and is shared with the host, so from then on the
user's own IDE on the host committed as "opencode agent" too.

So oillamp now writes an identity for the sandbox each session, the agent's own by default.
`identity` in the `[git]` section of `oillamp.toml` can choose the user's (`"host"`), another one
(`"custom"`), or none (`"none"`). Oillamp writes it to `.oillamp/session/gitconfig`, which the
image's `/etc/gitconfig` includes. That is git's lowest level, so the agent's `~/.gitconfig` and a
repository's own setting still win.
''')
@Subject(OilLamp)
class Giving_The_Agent_A_Git_Identity_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'By default, the agent commits as `genie agent`, with its lamp\'s id in the email, and nothing about the user enters the sandbox'() {
        reportInfo """
            The user's name and email address are the user's, and the sandbox is where the agent
            reads untrusted text. So by default oillamp gives the agent its own identity: the name
            `genie agent`, and the email `genie@<agent id>`, with the lamp's eight-character agent
            id. A commit then says both that an agent made it and in which lamp. `oillamp at`
            prints whose name the commits will carry.
        """
        given: 'a host whose git configuration names Ada Lovelace <ada@example.com>'
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())
            var lampId = Files.list(host.lampPath()).map { it.fileName.toString() }
                              .filter { it.startsWith('agent-lamp-') }.findFirst().orElseThrow() - 'agent-lamp-'

        then: 'the session ended with success'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the session\'s git configuration names `genie agent` and `genie@<agent id>`'
            gitValue('user.name') == 'genie agent'
            gitValue('user.email') == "genie@${lampId}".toString()

        and: 'it holds neither the user\'s name nor their email'
            !Files.readString(sessionGitConfig()).contains('Ada')
            !Files.readString(sessionGitConfig()).contains('ada@example.com')

        and: '`oillamp at` printed whose name the agent\'s commits will carry'
            outcome.console().contains("the agent's commits will carry genie agent <genie@${lampId}>")
    }

    def 'With `git.identity = "host"`, the agent commits under the user\'s own name and email'() {
        reportInfo """
            Some users want the agent's commits in their history under their own name, as the
            commits of any other tool they run are. With `identity = "host"` in `[git]`, oillamp
            reads `user.name` and `user.email` from the user's global git configuration when a
            session starts.
        """
        given: 'a host whose git configuration names Ada Lovelace, and a lamp with `identity = "host"`'
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "host"
            '''.stripIndent())

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session ended with success'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the session\'s git configuration names Ada Lovelace <ada@example.com>'
            gitValue('user.name') == 'Ada Lovelace'
            gitValue('user.email') == 'ada@example.com'

        and: '`oillamp at` printed that the agent\'s commits will carry that identity'
            outcome.console().contains("the agent's commits will carry Ada Lovelace <ada@example.com>")
    }

    def 'With `git.identity = "custom"`, the agent commits under the name and email in `[git]`'() {
        reportInfo """
            Some people want to see at a glance which commits an agent made, or use a separate
            email address for them. With `identity = "custom"`, oillamp uses `name` and `email`
            from `[git]` instead of the user's. Oillamp writes them into a git configuration file,
            so a quote or a backslash in a name must reach git unchanged. Real git reads the file
            back to check that.
        """
        given: 'a lamp whose custom name holds an apostrophe, backslashes and double quotes'
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "custom"
                name     = "Ada's \\\\agent\\\\ \\"helper\\""
                email    = "agent@example.com"
            '''.stripIndent())

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'real git reads the name and the email exactly as `oillamp.toml` gives them'
            outcome.status() == LampExitStatus.SUCCESS
            gitValue('user.name') == 'Ada\'s \\agent\\ "helper"'
            gitValue('user.email') == 'agent@example.com'
    }

    def 'With `git.identity = "none"`, oillamp gives the agent no git identity'() {
        reportInfo """
            With `identity = "none"`, git in the sandbox refuses to commit until an identity is set
            there, as it did before oillamp gave one. That suits a user who wants no commits from
            the agent at all, or wants the agent to choose its own in `~/.gitconfig`.
        """
        given: 'a lamp with `identity = "none"`'
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "none"
            '''.stripIndent())

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session\'s git configuration has no `[user]` section, and `oillamp at` says the agent has no git identity'
            outcome.status() == LampExitStatus.SUCCESS
            !Files.readString(sessionGitConfig()).contains('[user]')
            outcome.console().contains('the agent has no git identity')
    }

    def 'Oillamp warns that the agent cannot commit when `git.identity = "host"` finds no identity on the host, and still starts the session'() {
        reportInfo """
            Let's consider a lamp with `identity = "host"`, on a host without git, or whose git
            configuration names no one. There is nothing to copy. That is no reason to stop a
            session, but it is why the agent's first commit will fail, so `oillamp at` says so,
            rather than leaving the user to find out from the agent.
        """
        given: 'a host without git, and a lamp with `identity = "host"`'
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "host"
            '''.stripIndent())

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session ended with success, its git configuration has no `[user]` section, and `oillamp at` says the user\'s git configuration has no `user.name` and `user.email`'
            outcome.status() == LampExitStatus.SUCCESS
            !Files.readString(sessionGitConfig()).contains('[user]')
            outcome.console().contains('your git configuration has no user.name and user.email')
    }

    def 'Oillamp refuses to start a session for a custom git identity without an email'() {
        reportInfo """
            `identity = "custom"` without `email` would give git half an identity, and a commit
            inside the sandbox would fail for a reason set on the host. So oillamp refuses to start
            the session, and names the keys: `git.identity` needs both `git.name` and
            `git.email`.
        """
        given: 'a lamp with `identity = "custom"` and a name, but no email'
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "custom"
                name     = "Agent"
            '''.stripIndent())

        when: '`oillamp at` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'it fails, and says that `git.identity` needs both `git.name` and `git.email`'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.console().contains('git.identity')
            outcome.console().contains('needs both git.name and git.email')
    }

    def 'The image\'s `/etc/gitconfig` makes every git in the sandbox read the session\'s identity'() {
        reportInfo """
            The session's git configuration only matters if git reads it. The image's
            `/etc/gitconfig` includes it from `/oillamp/session`, where the lamp's session
            directory is mounted in the sandbox. Every git process reads `/etc/gitconfig`, whatever
            its environment, so a harness or an IDE started without a login shell gets the identity
            too. The scenario points a copy of `/etc/gitconfig` at the written file, and has real
            git follow the include.
        """
        given: 'the session\'s git configuration, as oillamp writes it'
            host.oillamp.run('at', host.lampPath().toString())

        and: 'a copy of the image\'s `/etc/gitconfig`, pointed at it instead of at the mount'
            var system = Files.readString(Path.of('src/main/resources/image/rootfs/etc/gitconfig'))
            system.contains('path = /oillamp/session/gitconfig')
            var copy = tmp.resolve('gitconfig')
            Files.writeString(copy, system.replace('/oillamp/session/gitconfig', sessionGitConfig().toString()))

        expect: 'real git, following the include, reads `genie agent`'
            Spike.run('git', 'config', '--file', copy.toString(), '--includes', '--get', 'user.name')
                 .out.trim() == 'genie agent'
    }

    private Path sessionGitConfig() { host.lampPath().resolve('.oillamp/session/gitconfig') }

    /** A value from the session's file, as real git reads it. */
    private String gitValue(String key) {
        Spike.run('git', 'config', '--file', sessionGitConfig().toString(), '--get', key).out.trim()
    }
}
