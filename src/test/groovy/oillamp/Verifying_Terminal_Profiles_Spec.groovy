package oillamp

import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag
import spock.lang.Title

import java.time.Duration

@Title('Verifying terminal profiles')
@Narrative('''
Checks the terminal table in `TerminalEmulatorUtil.java`, and why a session is tied to the ssh
connection rather than to the terminal program.

This spike runs on the host, not in a container. It can check only the terminals installed on this
machine, and reports which it could not check.
''')
@Tag('spike')
class Verifying_Terminal_Profiles_Spec extends Specification {

    /** The option each terminal in {@code TerminalEmulatorUtil.java} is given to set the window title. */
    static final Map<String, String> TITLE_OPTION = [
            'ptyxis'        : null,          // uses --new-window, no title option
            'gnome-terminal': '--title',
            'kgx'           : '--title',
            'konsole'       : '-p',
            'kitty'         : '--title',
            'foot'          : '--title',
            'alacritty'     : '--title',
            'wezterm'       : null,          // `wezterm start --`, no title option
            'xterm'         : '-T',
    ]

    def 'The terminals installed here accept the arguments oillamp gives them'() {
        reportInfo """
            The terminal table in `TerminalEmulatorUtil.java` holds nine argument templates, each
            one a guess until something runs it. A wrong template shows only as a window that never
            opens: the session ends while oillamp waits for the shell window to connect, with
            `OIL-TERM-002`, which says that the terminal failed, but not which argument was wrong.

            This scenario checks the installed terminals by reading their own `--help`, rather than
            by opening windows, so that it can run unattended. It reports which terminals it could
            not check, because a passing result that covered only one row would mislead.
        """
        given: 'the terminals from the table that exist on this machine'
            var installed = TITLE_OPTION.keySet().findAll { Spike.run('command', '-v', it).ok ||
                                                            Spike.run('which', it).ok }

        expect: 'at least one, or this scenario proves nothing and should say so'
            !installed.isEmpty()

        and: 'each one still has the option oillamp passes it'
            installed.each { terminal ->
                var option = TITLE_OPTION[terminal]
                if (option == null) {
                    reportInfo("$terminal: no title option in the table, nothing to check.")
                    return
                }
                var help = Spike.run(Duration.ofSeconds(30), terminal, '--help-all')
                if (!help.ok) help = Spike.run(Duration.ofSeconds(30), terminal, '--help')
                assert help.text.contains(option),
                        "oillamp passes `$option` to $terminal, but its help does not mention it:\n" +
                        help.describe()
                reportInfo("$terminal: `$option` accepted.")
            }

        and: 'it reports which rows of the table nobody has checked yet'
            var unchecked = TITLE_OPTION.keySet() - installed
            reportInfo(unchecked.isEmpty()
                    ? 'Every terminal in the table was verified on this machine.'
                    : "Not installed here, so still unverified: ${unchecked.join(', ')}.")
            true
    }

    @Requires({ Spike.run('command', '-v', 'gnome-terminal').ok || Spike.run('which', 'gnome-terminal').ok })
    def 'A terminal program returns long before its child exits, so its process id says nothing about the session'() {
        reportInfo """
            Oillamp decides that a session has ended by watching the ssh connection it relays to
            the shell window, and on purpose not by watching the terminal program it started. The
            reason is that many terminal programs do not stay running: they hand the new window to
            a separate server process that already runs, and exit at once, so the process id
            oillamp holds means nothing a moment later.

            How a session ends depends on this, so this scenario measures it: gnome-terminal is
            asked to run a child that sleeps for three seconds, and the launch must return before
            that.

            Should this scenario ever fail, because a terminal did stay running until its child
            exited, watching the connection would still be right for the other terminal programs,
            but the simpler design that was rejected would deserve a second look.

            gnome-terminal has `--wait`, which would make it block. Oillamp does not use it: the
            design would then be right for gnome-terminal, and wrong for every terminal program
            without such an option.
        """
        given: 'a child that takes clearly longer than the terminal needs to start'
            var childSeconds = 3

        when: 'the terminal is launched with it, as oillamp would'
            var started = System.nanoTime()
            Spike.run(Duration.ofSeconds(30), 'gnome-terminal', '--title=oillamp-spike',
                      '--', 'sh', '-c', "sleep $childSeconds")
            var elapsed = Duration.ofNanos(System.nanoTime() - started)

        then: 'it came back well before the child was done'
            elapsed.toMillis() < childSeconds * 1000
            reportInfo("gnome-terminal returned after ${elapsed.toMillis()} ms " +
                       "while its child ran for ${childSeconds}s, so its PID says nothing about the session.")
    }
}
