package gui

import dev.gui.model.History
import dev.gui.model.Lang
import dev.gui.model.Lines
import spock.lang.Specification
import sprouts.Tuple

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 *  How the history page draws the branches of a genie's history, and which branch it shows.
 *
 *  <p>oillamp keeps a genie's history as branches: lines of moments, each with a home of its
 *  own. The genie lives on one branch at a time, and every save goes to that one. A branch
 *  started at a moment holds that moment and every moment before it, so the moments from before
 *  a branch was started are on both branches.
 *
 *  <p>The page draws the branch it shows as the line it always drew, with a card of words for
 *  each moment. Every other branch with moments of its own is a line of dots to its left, a
 *  lane, which bends into the moment its branch was started at:
 *
 *  <pre>
 *   lanes  shown line
 *    │      ◉   now
 *    •      │         rust-rewrite saved meanwhile
 *    │      ●   Answered: …
 *    ╰──╮   ●   before the big refactor     rust-rewrite was started here
 *  </pre>
 *
 *  <p>All of it is {@code History.lines(zone)}, a pure function of the history, so these
 *  scenarios build histories by hand, as the lamp would report them through
 *  {@code LampDirectory.branches()} and {@code LampDirectory.historyOfAllBranches()}, and read
 *  the rows the page draws.
 */
class DrawingAGeniesBranchesSpec extends Specification {

    static final Instant NOON = Instant.parse('2026-09-29T10:00:00Z')
    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')

    def 'A history of one branch is drawn as one line, as it always was: now, the day, and a card for each moment'() {
        reportInfo """
            Let's consider a genie whose lamp has only the branch main, with two answers saved
            this morning. The page must look as it did before oillamp had branches: "now" at the
            top, the day, and a card for each moment, newest first, on one rail. No lane is drawn,
            and nothing about branches is said, since there is only one.
        """
        given: 'two answers on main, the branch the genie lives on'
            var first = moment('first', 120, 'main')
            var second = moment('second', 60, 'main')
            var history = History.UNREAD.readAs(1, branches(lampOn('main', second)), moments(second, first))

        when: 'the page draws it'
            var lines = history.lines(BERLIN)

        then: 'no lanes, and the rows of one line'
            lines.lanes().isEmpty()
            lines.rows()*.class*.simpleName == ['Now', 'Day', 'Shown', 'Shown']
            lines.rows().findAll { it instanceof Lines.Row.Shown }*.moment()*.id() == ['second', 'first']
            lines.rows().every { it.strands().isEmpty() }
    }

    def 'Another branch is a line of dots to the left of the shown one, and bends into the moment it was started at'() {
        reportInfo """
            Let's consider a genie that lives on main, with a branch rust-rewrite started at its
            first moment. Since then, main saved two moments and rust-rewrite two of its own, in
            turns. The page shows main, where the genie lives, with its words. rust-rewrite is a
            line of its own to the left of main's, a lane, with a dot for each of its own moments. A dot is drawn in a row
            between the two moments of main it was saved between, so the page still says which
            came first, while main keeps a card for each of its moments. The lane ends by bending
            into main's first moment, where rust-rewrite was started.
        """
        given: 'main and rust-rewrite, started at main\'s first moment, saving in turns'
            var started = moment('started', 240, 'main', 'rust-rewrite')
            var mainOne = moment('main-1', 180, 'main')
            var rustOne = moment('rust-1', 150, 'rust-rewrite')
            var mainTwo = moment('main-2', 120, 'main')
            var rustTwo = moment('rust-2', 90, 'rust-rewrite')
            var history = History.UNREAD.readAs(1,
                    branches(lampOn('main', mainTwo), other('rust-rewrite', rustTwo)),
                    moments(rustTwo, mainTwo, rustOne, mainOne, started))

        when: 'the page draws it'
            var lines = history.lines(BERLIN)

        then: 'rust-rewrite is the one lane, and main is the line shown, with the lamp\'s now on it'
            lines.lanes()*.branch() == ['rust-rewrite']
            !lines.lanes().first().lampIsOn()
            (lines.rows().first() as Lines.Row.Now).lampLane().isEmpty()

        and: 'each of rust-rewrite\'s moments is a dot in a row between main\'s, in the order they were saved'
            lines.rows().collect { described(it) } ==
                    ['now', 'between: rust-2', 'day', 'card: main-2', 'between: rust-1', 'card: main-1', 'card: started']

        and: 'the lane runs from its newest dot down to main\'s first moment, and bends into it there'
            var lane = lines.rows()*.strands()*.get(0)
            !lane[1].above() && lane[1].below()
            lane[2..5].every { it.above() && it.below() }
            lane[6].above() && !lane[6].below()
            lane[6].end() == Optional.of(new Lines.End.OnShownLine())
            !lane[0].above() && lane[0].isEmpty()
    }

    def 'Showing another branch labels its line, and moves the branch the genie lives on to the left, with the lamp\'s node at its top'() {
        reportInfo """
            Let's consider a genie that lives on main, with a branch rust-rewrite started at main's
            first moment; since then main saved one moment and rust-rewrite one. The user clicks
            the chip rust-rewrite above the lines, and the window calls
            `History.showLine('rust-rewrite')`. That only changes what the page shows: the genie
            still lives on main, and `History.lampBranch()` still says so. rust-rewrite's line
            now has the cards, main's moments of its own become dots on a line of their own to the
            left, a lane, and
            the moment both branches share, from before rust-rewrite was started, is a card of
            rust-rewrite's line, drawn once. The lamp's "now" node goes with main: at the top of
            its lane, so the user always sees where the genie lives.
        """
        given: 'main, where the genie lives, and rust-rewrite, started at main\'s first moment'
            var started = moment('started', 240, 'main', 'rust-rewrite')
            var mainOne = moment('main-1', 180, 'main')
            var rustOne = moment('rust-1', 150, 'rust-rewrite')
            var history = History.UNREAD.readAs(1,
                    branches(lampOn('main', mainOne), other('rust-rewrite', rustOne)),
                    moments(rustOne, mainOne, started))

        when: 'the user clicks rust-rewrite'
            var shown = history.showLine('rust-rewrite')
            var lines = shown.lines(BERLIN)

        then: 'its line has the cards, and the genie still lives on main'
            shown.shownLine() == 'rust-rewrite'
            shown.lampBranch() == 'main'
            lines.rows().collect { described(it) } == ['now', 'day', 'card: rust-1', 'between: main-1', 'card: started']

        and: 'main is the lane to the left, lit as the one the genie lives on, with the lamp\'s node at its top'
            lines.lanes()*.branch() == ['main']
            lines.lanes().first().lampIsOn()
            (lines.rows().first() as Lines.Row.Now).lampLane() == Optional.of(0)
            lines.rows().first().strands().first().below()

        and: 'the moment both branches share is drawn once'
            lines.rows().count { it instanceof Lines.Row.Shown && it.moment().id() == 'started' } == 1
            lines.rows().sum { it.strands().collectMany { it.dots().toList() }.count { it.moment().id() == 'started' } } == 0

        when: 'the user clicks main again'
            var back = shown.showLine('main')

        then: 'the page follows the genie\'s branch again'
            back.line() == ''
            back.shownLine() == 'main'
    }

    def 'A branch started at a moment of another lane bends into that lane, and the card of a branch with nothing of its own yet says it stands there'() {
        reportInfo """
            Let's consider a genie living on main, with rust-rewrite started at main's first
            moment, and docs started at rust-rewrite's first moment of its own. Since then,
            rust-rewrite and docs each saved a moment of their own. Last, the user started a
            branch try at main's newest moment, and try saved nothing yet. The page shows main,
            with its words; each other branch with moments of its own is a line of dots to the
            left of main's, a lane.

            rust-rewrite's first moment is on rust-rewrite and on docs, and is drawn once: as a
            dot on the lane of docs, the first of the two in alphabetical order. rust-rewrite's
            lane bends into that dot, not into main, since that moment is not on main; docs bends
            into main's first moment, which every branch here holds. try has no moment of its own,
            so it gets no lane: main's newest card says "⑂ try stands here".
        """
        given:
            var started = moment('started', 300, 'docs', 'main', 'rust-rewrite')
            var rustOne = moment('rust-1', 240, 'docs', 'rust-rewrite')
            var rustTwo = moment('rust-2', 200, 'rust-rewrite')
            var docsOne = moment('docs-1', 180, 'docs')
            var mainOne = moment('main-1', 120, 'main', 'try')
            var history = History.UNREAD.readAs(1,
                    branches(other('docs', docsOne), lampOn('main', mainOne), other('rust-rewrite', rustTwo), other('try', mainOne)),
                    moments(mainOne, docsOne, rustTwo, rustOne, started))

        when:
            var lines = history.lines(BERLIN)

        then: 'docs and rust-rewrite are lanes, in alphabetical order, and try is none'
            lines.lanes()*.branch() == ['docs', 'rust-rewrite']
            lines.rows().collect { described(it) } == ['now', 'day', 'card: main-1', 'between: docs-1, rust-2, rust-1', 'card: started']

        and: 'rust-1 is a dot of docs, below docs-1; rust-2 is rust-rewrite\'s'
            var between = lines.rows()[3] as Lines.Row.Between
            between.strands()[0].dots()*.moment()*.id() == ['docs-1', 'rust-1']
            between.strands()[1].dots()*.moment()*.id() == ['rust-2']

        and: 'rust-rewrite bends into the dot of rust-1, and docs into main\'s first moment'
            between.strands()[1].end() == Optional.of(new Lines.End.OnLane(0, 2))
            lines.rows()[4].strands()[0].end() == Optional.of(new Lines.End.OnShownLine())

        and: 'main\'s newest card says try stands there'
            (lines.rows()[2] as Lines.Row.Shown).newestOf().toList() == ['try']
    }

    def 'A long history of several branches shows the newest forty moments of the line shown, and the lanes go on past them'() {
        reportInfo """
            Let's consider a genie used for weeks: fifty answers on main, one an hour, after the
            moment a branch experiment was started at, which saved a moment of its own between
            each two of main's answers. The page shows main, and experiment as a line of dots to
            the left of main's. It shows only the newest forty moments of main, and the button
            "Show 11 earlier moments" shows the rest. What experiment saved before those forty is
            left out too, so the page does not grow with it, and experiment's line goes on past
            the bottom, towards the moment it was started at, which is not drawn yet.
        """
        given:
            var mains = (0..<50).collect { moment("main-${it}", 60 * it, 'main') }
            var experiments = (0..<49).collect { moment("experiment-${it}", 60 * it + 30, 'experiment') }
            var oldest = moment('started', 60 * 50, 'main', 'experiment')
            var all = [mains, experiments].transpose().flatten() + [mains.last(), oldest]
            var history = History.UNREAD.readAs(1, branches(lampOn('main', mains.first()), other('experiment', experiments.first())),
                    moments(*all))

        when:
            var lines = history.lines(BERLIN)

        then: 'forty of main\'s fifty-one moments, and eleven left out'
            history.hidden() == 11
            lines.rows().count { it instanceof Lines.Row.Shown } == 40

        and: 'experiment\'s line goes on past the last row'
            lines.rows().last().strands().first().below()
            lines.rows().last().strands().first().end().isEmpty()

        and: 'with every moment shown, it bends into the moment it was started at'
            var everything = history.withEarlier(true).lines(BERLIN)
            everything.rows().count { it instanceof Lines.Row.Shown } == 51
            everything.rows().last().strands().first().end() == Optional.of(new Lines.End.OnShownLine())
    }

    def 'A reading that finishes after a newer one changes nothing, and the same reading again changes nothing either'() {
        reportInfo """
            Let's consider a genie whose history page read its history twice: `HistoryKeeper`
            called `LampDirectory.branches()` and `LampDirectory.historyOfAllBranches()` for each
            reading, numbering it as it started, and a save in between added a moment, so the
            second reading holds it and the first does not. Readings are engine
            processes, and nothing promises that the first finishes first. Whatever order
            `History.readAs` hears them in, the page must end up showing the newer one; and
            hearing a reading it shows already must leave the history equal to what it was, so
            that nothing on the page is drawn anew.
        """
        given: 'a reading before the save, numbered 1, and one after it, numbered 2'
            var before = moment('before', 120, 'main')
            var saved = moment('saved', 60, 'main')
            var older = [1L, branches(lampOn('main', before)), moments(before)]
            var newer = [2L, branches(lampOn('main', saved)), moments(saved, before)]

        when: 'they arrive in order, and the other way round'
            var inOrder = History.UNREAD.readAs(*older).readAs(*newer)
            var outOfOrder = History.UNREAD.readAs(*newer).readAs(*older)

        then: 'both show the newer reading'
            inOrder == outOfOrder
            inOrder.moments()*.id() == ['saved', 'before']

        and: 'hearing the newer reading again changes nothing'
            inOrder.readAs(*newer) == inOrder
    }

    def 'The line shown and the moment picked stay while the newest reading has them; a plan stays only while nothing changed'() {
        reportInfo """
            Let's consider a genie living on main, whose page shows its branch try, with try's
            newest moment picked. The user clicked "Delete try…", Genies called
            `LampDirectory.wouldDeleteBranch('try')`, and the banner says one moment is on try
            alone; the user has not clicked "Delete try" yet. That number holds only for the
            history the lamp counted it in. A reading with the same moments and branches keeps
            the banner. A reading with one more moment on try, saved meanwhile by
            `oillamp history <dir> save` at a terminal, drops it, since deleting try would now lose
            two: the user clicks "Delete try…" again to see the new count, and the line shown and
            the moment picked stay. When someone then deletes try at the command line, with
            `oillamp history <dir> branch --delete try --yes`, the next reading has no try, and
            the page cannot show a line that is not there: it shows main and picks nothing.
        """
        given: 'the page shows try, its newest moment picked, and the banner about deleting it'
            var started = moment('started', 120, 'main', 'try')
            var tried = moment('tried', 60, 'try')
            var both = [branches(lampOn('main', started), other('try', tried)), moments(tried, started)]
            var looking = History.UNREAD.readAs(1, *both).showLine('try').pick('tried')
                                 .withPlan(Optional.of(new History.Plan.DeleteBranch('try', 1)))

        when: 'a reading with the same moments and branches'
            var same = looking.readAs(2, *both)

        then: 'all three stay'
            same.shownLine() == 'try' && same.picked() == 'tried'
            same.plan() == Optional.of(new History.Plan.DeleteBranch('try', 1))

        when: 'a reading with one more moment on try'
            var more = moment('more', 30, 'try')
            var changed = looking.readAs(3, branches(lampOn('main', started), other('try', more)), moments(more, tried, started))

        then: 'the banner goes, and the line and the picked moment stay'
            changed.plan().isEmpty()
            changed.shownLine() == 'try' && changed.picked() == 'tried'

        when: 'a reading after try was deleted'
            var gone = changed.readAs(4, branches(lampOn('main', started)), moments(started))

        then:
            gone.shownLine() == 'main'
            gone.picked() == ''
    }

    def 'A branch name is checked as oillamp checks it, before Genies calls `LampDirectory.branch`: "#name"'() {
        reportInfo """
            Let's consider a user who types a name for a new branch in the dialog that "New
            branch…" opens. oillamp keeps branches as files in a git repository, and refuses a
            name git would not take. `History.branchNameProblem` checks oillamp's rules first, so
            the dialog opens again, with the name typed and a sentence above it that says what is
            wrong, rather than the call to `LampDirectory.branch` failing after the dialog closed.
        """
        given:
            var history = History.UNREAD.readAs(1, branches(lampOn('main', moment('m', 60, 'main'))), moments(moment('m', 60, 'main')))

        expect:
            history.branchNameProblem(name, Lang.ENGLISH).map { it.contains(says) }.orElse(says.isEmpty())

        where:
            name               | says
            'rust-rewrite'     | ''
            'v2.1_try'         | ''
            ''                 | 'needs a name'
            'Rust'             | 'lowercase letters'
            '-rust'            | 'begins with a letter or a digit'
            'my branch'        | 'lowercase letters'
            'a..b'             | 'cannot hold ..'
            'try.lock'         | 'end in . or .lock'
            'x' * 65           | 'at most 64 characters'
            'main'             | 'There is a branch called main already'
    }

    def 'Each branch keeps its colour when another branch is started or deleted'() {
        reportInfo """
            Let's consider a genie with the branches main and rust-rewrite, each drawn in a colour
            of its own. The user starts the branch docs, and later deletes rust-rewrite. Each
            colour follows from the branch's name, so main keeps its colour through both, and the
            user does not learn the lines anew. Only when two names would give the same colour
            does the one later in alphabetical order take the next one free, so up to eight
            branches all differ.
        """
        given:
            var two = branches(lampOn('main', moment('m', 1, 'main')), other('rust-rewrite', moment('m', 1, 'main')))
            var three = two.add(other('docs', moment('m', 1, 'main')))
            var eight = Tuple.of(History.Branch, (1..8).collect { other("b${it}", moment('m', 1, 'main')) } as History.Branch[])

        expect:
            Lines.colour(two, 'main') == Lines.colour(three, 'main')
            Lines.colour(three, 'main') == Lines.colour(three.removeIf { it.name() == 'rust-rewrite' }, 'main')
            eight.collect { Lines.colour(eight, it.name()) }.toSet().size() == 8
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** An answer saved `minutesAgo` before noon, on `branches`. */
    private static History.Moment moment(String id, int minutesAgo, String... branches) {
        new History.Moment(id, NOON.minus(Duration.ofMinutes(minutesAgo)), History.Kind.RAN, '', 'run-1', '',
                           Optional.empty(), '', Tuple.of(String, branches.sort() as String[]))
    }

    private static History.Branch lampOn(String name, History.Moment newest) { new History.Branch(name, true, newest.id()) }

    private static History.Branch other(String name, History.Moment newest) { new History.Branch(name, false, newest.id()) }

    private static Tuple<History.Branch> branches(History.Branch... branches) { Tuple.of(History.Branch, branches) }

    private static Tuple<History.Moment> moments(History.Moment... moments) { Tuple.of(History.Moment, moments) }

    /** A row in a few words: what it is, and the moments it draws. */
    private static String described(Lines.Row row) {
        switch (row) {
            case Lines.Row.Now: return 'now'
            case Lines.Row.Day: return 'day'
            case Lines.Row.Shown: return 'card: ' + row.moment().id()
            default: return 'between: ' + row.strands().collectMany { it.dots().toList() }.sort { it.slot() }*.moment()*.id().join(', ')
        }
    }
}
