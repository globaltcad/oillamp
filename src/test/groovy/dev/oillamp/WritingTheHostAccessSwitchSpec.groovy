package dev.oillamp

import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Title

import sprouts.Tuple

import java.nio.file.Path

@Title('Writing the host access switch and requirements into a lamp\'s file')
@Narrative('''
An application's settings page changes two things in a lamp's `oillamp.toml`: whether host access
is on, and the requirements the checker judges by. The file is also the user's, with their own
comments and settings, so those two lines are replaced where they are and nothing else is touched.

These scenarios are about text only: the writer, and the reader `at` uses, on the same string.
''')
class WritingTheHostAccessSwitchSpec extends Specification {

    def 'Only the two keys change, where they are, and the rest of the file stays as it was'() {
        given:
            var file = '''\
                schema_version = 1
                # my display
                [display]
                width = 2560

                [host_access]
                enabled = false
                # what the checker judges by
                requirements = \'\'\'
                old rules
                [schedule]
                \'\'\'
                roots = ["/home/me/Documents"]

                # my schedule
                [schedule]
                enabled = true
                '''.stripIndent()

        when:
            var written = ConfigFileEditUtil.withHostAccess(file, Optional.of(true), Optional.of('{"may_read": ["~/Documents"]}'))

        then: 'the old value is gone, the line in it that looked like a table with it'
            written == '''\
                schema_version = 1
                # my display
                [display]
                width = 2560

                [host_access]
                enabled = true
                # what the checker judges by
                requirements = \'\'\'
                {"may_read": ["~/Documents"]}\'\'\'
                roots = ["/home/me/Documents"]

                # my schedule
                [schedule]
                enabled = true
                '''.stripIndent()

        and: 'it reads back as what was written'
            read(written).enabled()
            read(written).requirements() == '{"may_read": ["~/Documents"]}'
    }

    def 'A file without the table gets one, and without a key gets it under the header'() {
        expect:
            ConfigFileEditUtil.withHostAccess('schema_version = 1\n', Optional.of(false), Optional.of('x')) ==
                    'schema_version = 1\n\n[host_access]\nenabled = false\nrequirements = \'\'\'\nx\'\'\'\n'
            ConfigFileEditUtil.withHostAccess('schema_version = 1\n[host_access]\nroots = []\n', Optional.of(true), Optional.empty()) ==
                    'schema_version = 1\n[host_access]\nenabled = true\nroots = []\n'
    }

    def 'Any table\'s keys are written the same way, and a bare value keeps its alignment and comment'() {
        given:
            var file = '''\
                schema_version = 1
                [autosave]
                startup    = false   # as a session starts
                after_run  = true    # as a run ends
                '''.stripIndent()
            var values = new LinkedHashMap<String, String>()
            values.put('startup', 'true')
            values.put('before_run', 'false')

        expect:
            ConfigFileEditUtil.withValues(file, 'autosave', values) == '''\
                schema_version = 1
                [autosave]
                before_run = false
                startup    = true   # as a session starts
                after_run  = true    # as a run ends
                '''.stripIndent()
    }

    def 'Requirements come back exactly, whatever they hold'() {
        expect:
            var written = ConfigFileEditUtil.withHostAccess('schema_version = 1\n', Optional.of(false), Optional.of(requirements))
            read(written).requirements() == requirements

        and: 'and again when they are written over themselves'
            read(ConfigFileEditUtil.withHostAccess(written, Optional.of(false), Optional.of(requirements))).requirements() == requirements

        where:
            requirements << [
                '',
                '{\n  "may_read": ["~/Documents"],\n  "anything_else": "Don\'t touch my taxes."\n}\n',
                'Three quotes \'\'\' are fine too.',
                'It ends with a quote\'',
                'A tab\there, and a carriage return\r\nthere.',
                'Back\\slash and "quotes".',
            ]
    }

    private static LampConfig.HostAccess read(String file) {
        var loaded = ConfigLoadingUtil.load(Tuple.of(ConfigSource, ConfigSource.lamp(Path.of('/lamp/oillamp.toml'), file)))
        assert loaded instanceof Result.Ok
        ((loaded as Result.Ok).value() as LampConfig).hostAccess()
    }
}
