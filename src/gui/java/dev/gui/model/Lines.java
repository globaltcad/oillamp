package dev.gui.model;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import sprouts.Tuple;

/// The lines a genie's history page draws, from top to bottom, as rows.
///
/// The branch shown, [History#shownLine], is the line down the middle, with a card of words for
/// each of its moments, as a history of one branch is drawn. Every other branch with moments of
/// its own is a line of its own to the left of it, a lane, with a dot for each of those moments
/// and no words. A moment on several branches, such as one from before a branch was started, is
/// drawn once: on the shown line when it is on that one, and otherwise on the leftmost lane of a
/// branch it is on. The line of a lane ends where its branch was started: it bends into that
/// moment, on the shown line or on another lane.
///
/// The rows follow the history's order, newest first. Between two moments of the shown line, the
/// other branches' moments saved meanwhile share one row of their own, so the shown line keeps
/// its rhythm however busy the others were. While the shown line shows only its newest
/// [History#SHOWN] moments, the other branches' moments older than those are left out too, and
/// the lanes that reach them go on past the bottom.
///
/// ```
///  lanes   shown line
///   │       ◉   now                    the genie lives on the shown line
///   •       │                          a row between: rust-rewrite saved meanwhile
///   •       │
///   ╰──╮    ●   Answered: …            rust-rewrite was started at this moment
///           ●   before the big refactor
/// ```
///
/// @param lanes the other branches drawn, from left to right
/// @param rows  from top to bottom: now first, then the days, the shown line's moments and the
///              rows between them
public record Lines(Tuple<Lane> lanes, Tuple<Row> rows) {

    /// How many colours the branches take turns with.
    public static final int COLOURS = 8;

    /// A branch drawn as a line of its own, to the left of the shown line.
    ///
    /// @param branch   its name
    /// @param colour   which of the [#COLOURS] its dots have; see [#colour]
    /// @param lampIsOn whether the genie lives on it
    public record Lane(String branch, int colour, boolean lampIsOn) {}

    /// One row of the page, with how each lane passes through it, one [Strand] a lane, in the
    /// order of [#lanes].
    public sealed interface Row {
        Tuple<Strand> strands();

        /// How the genie is now, at the top. Its node is on the line of the branch it lives on.
        ///
        /// @param lampLane the lane of the branch the genie lives on; empty when that is the shown line
        record Now(Optional<Integer> lampLane, Tuple<Strand> strands) implements Row {}

        /// The day the moments of the shown line below it were saved on, up to the next day.
        record Day(LocalDate date, Tuple<Strand> strands) implements Row {}

        /// A moment of the shown line, with its card.
        ///
        /// @param newestOf the other branches whose newest moment it is, by name
        record Shown(History.Moment moment, Tuple<String> newestOf, Tuple<Strand> strands) implements Row {}

        /// The other branches' moments saved between two moments of the shown line.
        ///
        /// @param slots how many there are, on all lanes together: each takes a slot, the newest
        ///              the first, so that a row shows which was saved before which
        record Between(int slots, Tuple<Strand> strands) implements Row {}
    }

    /// How one lane passes through one row.
    ///
    /// @param above whether its line comes in at the row's top
    /// @param below whether its line goes on at the row's bottom
    /// @param dots  its moments in this row
    /// @param end   where its line bends into the moment its branch was started at, when that is in this row
    public record Strand(boolean above, boolean below, Tuple<Dot> dots, Optional<End> end) {
        static final Strand NONE = new Strand(false, false, Tuple.of(Dot.class), Optional.empty());

        public boolean isEmpty() { return equals(NONE); }
    }

    /// A moment on a lane.
    ///
    /// @param slot     its place in its row: see [Row.Between#slots]
    /// @param newestOf the other branches whose newest moment it is, by name
    public record Dot(History.Moment moment, int slot, Tuple<String> newestOf) {}

    /// The moment a lane's line bends into.
    public sealed interface End {
        /// The node of the shown line in this row.
        record OnShownLine() implements End {}
        /// A dot of another lane in this row.
        ///
        /// @param lane its index in [#lanes]
        record OnLane(int lane, int slot) implements End {}
    }

    /// Which of the [#COLOURS] the branch `name` has: one that follows from its name, so that it
    /// keeps its colour as other branches come and go, unless a branch before it in alphabetical
    /// order has that one already, and then the next one free.
    public static int colour(Tuple<History.Branch> branches, String name) {
        Set<Integer> taken = new HashSet<>();
        for (String branch : branches.mapTo(String.class, History.Branch::name).sort(String::compareTo)) {
            if (taken.size() == COLOURS) taken.clear();
            int colour = Math.floorMod(branch.hashCode(), COLOURS);
            while (taken.contains(colour)) colour = (colour + 1) % COLOURS;
            if (branch.equals(name)) return colour;
            taken.add(colour);
        }
        return Math.floorMod(name.hashCode(), COLOURS);
    }

    static Lines of(History history, ZoneId zone) {
        Tuple<History.Moment> all = history.all();
        String shown = history.shownLine();
        String lamp = history.lampBranch();
        Tuple<History.Moment> line = history.onLine(shown);
        int hidden = history.hidden();
        // Past `end`, in the order of every moment, nothing is drawn: the shown line's moments
        // left out, and what the other branches saved meanwhile.
        int end = hidden == 0 ? all.size() : all.firstIndexOf(line.get(line.size() - hidden - 1)) + 1;

        // Each moment not on the shown line belongs to the first branch it is on, in alphabetical
        // order; "" for those on the shown line.
        List<String> others = history.branches().stream().map(History.Branch::name)
                .filter(name -> !name.equals(shown)).sorted().toList();
        String[] owner = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            History.Moment moment = all.get(i);
            owner[i] = moment.isOn(shown) ? "" : others.stream().filter(moment::isOn).findFirst().orElse("");
        }

        // A lane for each branch with a moment of its own drawn, and for the one the genie lives on.
        Set<String> drawnOwners = new HashSet<>();
        for (int i = 0; i < end; i++) drawnOwners.add(owner[i]);
        List<String> laned = others.stream().filter(branch -> branch.equals(lamp) || drawnOwners.contains(branch)).toList();
        Tuple<Lane> lanes = Tuple.of(Lane.class);
        for (String branch : laned) lanes = lanes.add(new Lane(branch, colour(history.branches(), branch), branch.equals(lamp)));

        // The rows, and in which row, and which slot of it, each moment drawn is.
        List<Row> rows = new ArrayList<>();
        int[] rowOf = new int[all.size()];
        int[] slotOf = new int[all.size()];
        rows.add(new Row.Now(Optional.empty(), Tuple.of(Strand.class)));
        List<Integer> between = new ArrayList<>();
        Optional<LocalDate> day = Optional.empty();
        for (int i = 0; i < end; i++) {
            History.Moment moment = all.get(i);
            if (!owner[i].isEmpty()) {
                between.add(i);
            } else if (moment.isOn(shown)) {
                addBetween(rows, between, rowOf, slotOf);
                LocalDate date = History.dayOf(moment.at(), zone);
                if (day.isEmpty() || !day.get().equals(date)) rows.add(new Row.Day(date, Tuple.of(Strand.class)));
                day = Optional.of(date);
                rowOf[i] = rows.size();
                rows.add(new Row.Shown(moment, newestOf(history, moment, shown), Tuple.of(Strand.class)));
            }
        }
        addBetween(rows, between, rowOf, slotOf);

        // How each lane runs: from its top, the now row or its newest dot, down to where it ends,
        // which is the moment its branch was started at: the next one on it, past its own.
        List<List<Strand>> strands = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) strands.add(new ArrayList<>());
        for (String branch : laned) {
            List<Integer> own = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) if (owner[i].equals(branch)) own.add(i);
            List<Integer> drawn = own.stream().filter(i -> i < end).toList();
            int top = branch.equals(lamp) ? 0 : rowOf[drawn.getFirst()];
            int lastOwn = own.isEmpty() ? -1 : own.getLast();
            int base = -1;
            for (int i = lastOwn + 1; i < all.size() && base < 0; i++) if (all.get(i).isOn(branch)) base = i;
            boolean onwards = lastOwn >= end || base >= end;
            Optional<End> bend = Optional.empty();
            int bottom = drawn.isEmpty() ? top : rowOf[drawn.getLast()];
            if (onwards) {
                bottom = rows.size() - 1;
            } else if (base >= 0) {
                bottom = rowOf[base];
                bend = Optional.of(owner[base].isEmpty() ? new End.OnShownLine()
                                                        : new End.OnLane(laned.indexOf(owner[base]), slotOf[base]));
            }
            List<Tuple<Dot>> dotsOfRow = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) dotsOfRow.add(Tuple.of(Dot.class));
            for (int i : drawn)
                dotsOfRow.set(rowOf[i], dotsOfRow.get(rowOf[i]).add(new Dot(all.get(i), slotOf[i], newestOf(history, all.get(i), branch))));
            for (int r = 0; r < rows.size(); r++)
                strands.get(r).add(r < top || r > bottom ? Strand.NONE
                        : new Strand(r > top, r < bottom || onwards, dotsOfRow.get(r), r == bottom ? bend : Optional.empty()));
        }

        Optional<Integer> lampLane = laned.contains(lamp) ? Optional.of(laned.indexOf(lamp)) : Optional.empty();
        Tuple<Row> drawnRows = Tuple.of(Row.class);
        for (int r = 0; r < rows.size(); r++) {
            Tuple<Strand> through = Tuple.of(Strand.class, strands.get(r));
            drawnRows = drawnRows.add(switch (rows.get(r)) {
                case Row.Now _ -> new Row.Now(lampLane, through);
                case Row.Day it -> new Row.Day(it.date(), through);
                case Row.Shown it -> new Row.Shown(it.moment(), it.newestOf(), through);
                case Row.Between it -> new Row.Between(it.slots(), through);
            });
        }
        return new Lines(lanes, drawnRows);
    }

    /// The row for the other branches' moments `between` two of the shown line's, if there are any.
    private static void addBetween(List<Row> rows, List<Integer> between, int[] rowOf, int[] slotOf) {
        if (between.isEmpty()) return;
        for (int slot = 0; slot < between.size(); slot++) {
            rowOf[between.get(slot)] = rows.size();
            slotOf[between.get(slot)] = slot;
        }
        rows.add(new Row.Between(between.size(), Tuple.of(Strand.class)));
        between.clear();
    }

    /// The branches other than `besides` whose newest moment `moment` is.
    private static Tuple<String> newestOf(History history, History.Moment moment, String besides) {
        return history.branches()
                .removeIf(it -> it.name().equals(besides) || !it.newest().equals(moment.id()))
                .mapTo(String.class, History.Branch::name);
    }
}
