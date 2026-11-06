package dev.gui.view;

import java.awt.Color;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;

import com.formdev.flatlaf.FlatClientProperties;

import dev.gui.AppLog;
import dev.gui.Genies;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Lang;
import dev.gui.model.Notifications;
import dev.gui.model.Notifications.Notification;
import dev.gui.model.Notifications.Status;
import dev.gui.model.Palette;
import dev.gui.model.Trouble;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForPanel;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.layout.FlowCell;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;

import static swingtree.UI.*;

/// A genie's notifications, in three tiers: what its lamp asked the user, with what became of it,
/// and the links it published; what its lamp told them, such as what the checker model decided
/// about a request; and what went on inside Genies, what went wrong unexpectedly and, folded
/// away until the user asks for it, Genies' own log.
///
/// The header's bell shows and hides the page; see [GeniesView]. Like the rest of the window, a
/// function of the [GeniesState]: a lens onto the selected genie's [Notifications], its
/// troubles, and [Genies] for answering a question or opening a link. Only the log is not in the
/// state: it is read from the [AppLog] while it is unfolded.
final class NotificationsPage {

    /*
     *  A responsive grid of three cards, one per tier, and the log under them:
     *
     *                        very small  small  medium  large  very large  from THREE_FROM
     *      what is said on top    12       12     12     12       12            12
     *      asked of you           12       12     12      6        6             4
     *      from the lamp          12       12     12      6        6             4
     *      inside Genies          12       12     12     12       12             4
     *      the log, unfolded      12       12     12     12       12            12
     *
     *  The width decides THREE_FROM, not the size class, as on the host access page. Each row
     *  of a card says what happened in a line or two, and its status and time under it, so it
     *  needs no more room in a narrow card than in a wide one; its details fold away.
     */
    private static final int REFERENCE = 1100;
    private static final int THREE_FROM = 1240;
    private static final FlowCell WHOLE = span(12, 12, 12);
    private static final FlowCell TIER = span(12, 6, 4);
    private static final FlowCell INSIDE = span(12, 12, 4);
    /// How tall the log is, unfolded.
    private static final int LOG_HEIGHT = 320;
    /// From what page width on the log's levels stand in a row, in SwingTree's unscaled pixels:
    /// wide enough for them and a short filter field beside them.
    private static final int LEVELS_IN_A_ROW_FROM = 480;
    /// How narrow the log's filter field may get.
    private static final int FILTER_AT_LEAST = 70;
    /// How often the log looks for new lines while it is unfolded, in milliseconds.
    private static final int LOG_EVERY = 600;
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter LOGGED = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    /// A cell that spans `narrow` columns up to MEDIUM, `large` from LARGE up, and `wide` on a
    /// page wider than [#THREE_FROM].
    private static FlowCell span(int narrow, int large, int wide) {
        return AUTO_SPAN(it -> {
            int span = it.parentSize().widthOrElse(0) >= UI.scale(THREE_FROM) ? wide : large;
            return
                it.align(UI.VerticalAlignment.TOP)
                .verySmall(narrow).small(narrow).medium(narrow)
                .large(span).veryLarge(span).oversize(span);
        });
    }

    private final Var<GeniesState> state;
    private final Genies app;
    private final Look look;
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Val<Lang.NotificationsPage> words;
    private final Val<Lang.Header> headerWords;

    // Lenses, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<Notifications> notifications;
    /// The notifications and troubles whose details the user unfolded.
    private final Var<Tuple<Object>> open = Var.of(Tuple.of(Object.class));

    /// Whether the user wants the log unfolded, and how far it has unfolded on its way there.
    private final Var<Boolean> logShown = Var.of(false);
    private final Var<Unfolding> logUnfolding = Var.of(Unfolding.FOLDED);
    private boolean logUnfoldingPlays;
    /// The finest level of the log's lines shown, and words a line must hold to be shown.
    private final Var<AppLog.Level> least = Var.of(AppLog.Level.INFO);
    private final Var<String> filter = Var.of("");
    /// The log's lines, as last read, and what they were read for: refreshed only when one changed.
    private JTextArea logLines = new JTextArea();
    private long logRead = -1;
    private String logReadFor = "";
    private final Timer logReader;

    NotificationsPage(Var<GeniesState> state, Genies app, Look look) {
        this.state = state;
        this.app = app;
        this.look = look;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        words = Theme.langOf(state).viewAs(Lang.NotificationsPage.class, Lang::NOTIFICATIONS_PAGE);
        headerWords = Theme.langOf(state).viewAs(Lang.Header.class, Lang::HEADER);
        genie = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        notifications = genie.zoomTo(Genie::notifications, Genie::withNotifications);
        Viewable.cast(least).onChange(From.ALL, it -> {
            AppLog.Level shown = it.currentValue().orElse(AppLog.Level.INFO);
            // Information is always kept, so that there is something to show when the user looks.
            AppLog.shared().keep(shown.atLeast(AppLog.Level.INFO) ? AppLog.Level.INFO : shown);
        });
        logReader = new Timer(LOG_EVERY, _ -> readTheLog());
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        Viewable.cast(logShown).onChange(From.ALL, _ -> readingTheLog());
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 18, 18)
                .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).padding(10, 4, 28, 4))
                .add(WHOLE, top())
                .add(TIER, askedOfYou())
                .add(TIER, fromTheLamp())
                .add(INSIDE, insideGenies())
                .add(WHOLE, theLog())
            );
    }

    // ─── on top: what this is ──────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> top() {
        return
            box("fill, wrap 1, ins 0, gap 6", "[grow]")
            .add(label(words.viewAsString(Lang.NotificationsPage::NOTIFICATIONS)).group(Skin.EMPTY_TITLE))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    Viewable.of(String.class, genie, words, (it, page) -> page.WHAT_THE_LAMP_ASKED_AND_TOLD_YOU(it.name())),
                    Palette::SUBTEXT,
                    Val.of(true)
                )
            );
    }

    // ─── the cards ─────────────────────────────────────────────────────────────────────────

    /// A row of a card: a notification or a trouble, whether its details are unfolded, whether
    /// it came since the user last looked, and what became of it by now: a question that went
    /// unanswered while the page was open is drawn anew.
    private record Row(Object item, boolean unfolded, boolean fresh, Status status) {}

    /// The rows of `items`, newest first.
    private Val<Tuple<Row>> rows(Function<GeniesState, List<?>> items, Function<GeniesState, Instant> seen) {
        return Viewable.of(Tuple.classTyped(Row.class), state, open, (s, unfolded) -> {
            List<?> all = items.apply(s);
            List<Row> rows = new ArrayList<>(all.size());
            Instant since = seen.apply(s);
            for (int i = all.size() - 1; i >= 0; i--) {
                Object item = all.get(i);
                Instant at = item instanceof Notification n ? n.at() : item instanceof Trouble t ? t.when() : Instant.EPOCH;
                Status status = item instanceof Notification n ? n.statusAt(s.now()) : Status.TOLD;
                rows.add(new Row(item, unfolded.stream().anyMatch(item::equals), at.isAfter(since), status));
            }
            return Tuple.of(Row.class, rows);
        });
    }

    private static List<Notification> asked(GeniesState s) {
        return s.genie().notifications().all().stream()
            .filter(it -> it.about() instanceof Notifications.Question || it.about() instanceof Notifications.Published)
            .toList();
    }

    private static List<Notification> told(GeniesState s) {
        return s.genie().notifications().all().stream().filter(it -> it.status() == Status.TOLD).toList();
    }

    private UIForAnySwing<?, ?> askedOfYou() {
        return card(
            words.viewAsString(Lang.NotificationsPage::ASKED_OF_YOU),
            rows(NotificationsPage::asked, s -> s.genie().notifications().seen()),
            words.viewAsString(Lang.NotificationsPage::NOTHING_WAS_ASKED_YET),
            words.viewAsString(Lang.NotificationsPage::REMOVE_ALL_BUT_THE_QUESTIONS_STILL_WAITING_FOR_YOUR_ANSWER),
            () -> notifications.update(From.VIEW, it -> {
                Notifications cleared = it;
                for (Notification asked : asked(state.get())) if (asked.statusAt(state.get().now()) != Status.WAITING) cleared = cleared.without(asked);
                return cleared;
            })
        );
    }

    private UIForAnySwing<?, ?> fromTheLamp() {
        return card(
            words.viewAsString(Lang.NotificationsPage::FROM_THE_LAMP),
            rows(NotificationsPage::told, s -> s.genie().notifications().seen()),
            words.viewAsString(Lang.NotificationsPage::NOTHING_WAS_REPORTED_YET),
            words.viewAsString(Lang.NotificationsPage::REMOVE_ALL_BUT_THE_QUESTIONS_STILL_WAITING_FOR_YOUR_ANSWER),
            () -> notifications.update(From.VIEW, it -> {
                Notifications cleared = it;
                for (Notification told : told(state.get())) cleared = cleared.without(told);
                return cleared;
            })
        );
    }

    private UIForPanel<JPanel> insideGenies() {
        Val<String> logWords = Viewable.of(String.class, logShown, words, (shown, page) -> shown ? page.HIDE_THE_LOG() : page.SHOW_THE_LOG());
        return
            card(
                words.viewAsString(Lang.NotificationsPage::INSIDE_GENIES),
                rows(s -> s.troubles().stream().toList(), _ -> Instant.EPOCH),
                words.viewAsString(Lang.NotificationsPage::NOTHING_WENT_WRONG),
                words.viewAsString(Lang.NotificationsPage::REMOVE_WHAT_WENT_WRONG_THE_ERROR_LOG_KEEPS_IT),
                () -> state.update(From.VIEW, it -> it.withoutTroubles(it.troubles()))
            )
            .add("gaptop 6",
                button(logWords).group(Skin.QUIET_BUTTON)
                .withStyle(Themed.of(logShown, theme), (themed, it) -> it
                    .icon(SignSvgUtil.sign(themed.item() ? SignSvgUtil.FOLD : SignSvgUtil.UNFOLD, themed.palette().TEXT()))
                    .backgroundColor(themed.item() ? themed.palette().CARD() : themed.palette().RAISED())
                )
                .onClick(_ -> {
                    logShown.update(From.VIEW, shown -> !shown);
                    unfoldTheLog();
                })
            );
    }

    /// A card of `rows` under `title`, with how many there are and Clear, which `clear`s them;
    /// `empty` says there are none.
    private UIForPanel<JPanel> card(Val<String> title, Val<Tuple<Row>> rows, Val<String> empty, Val<String> clearTooltip, Runnable clear) {
        Val<Boolean> none = rows.viewAs(Boolean.class, Tuple::isEmpty);
        return
            panel("fillx, wrap 1, ins 12 14 12 14, gap 4, hidemode 3", "[grow]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.CARD()).borderRadius(14).border(1, p.BORDER()))
            .add("growx, wmin 0",
                box("fillx, ins 0, gap 8, hidemode 3", "[][grow][]")
                .add("aligny center", label(title.viewAsString(it -> it.toUpperCase(Locale.ROOT))).group(Skin.SECTION))
                .add("aligny center", ViewPartsUtil.tag(theme, rows.viewAsString(it -> String.valueOf(it.size()))).isVisibleIf(none.viewAs(Boolean.class, it -> !it)))
                .add("aligny center",
                    button(words.viewAsString(Lang.NotificationsPage::CLEAR)).group(Skin.ICON_BUTTON)
                    .isVisibleIf(none.viewAs(Boolean.class, it -> !it))
                    .withTooltip(clearTooltip)
                    .withStyle(theme, (now, it) -> it.padding(2, 8, 2, 8).componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().BRASS())))
                    .onClick(_ -> clear.run())
                )
            )
            .add("growx, wmin 0", ViewPartsUtil.note(theme, empty, none))
            .add("growx, wmin 0",
                panel("fillx, wrap 1, ins 0, gap 0", "[grow]")
                .withMinSize(0, 0)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .addAll("growx, wmin 0", rows, row -> UI.of(UI.use(look, () -> rowView(row).get(JPanel.class))))
            );
    }

    // ─── a row ─────────────────────────────────────────────────────────────────────────────

    /*
     *  A row says what happened in a line or two, and what became of it and when under it, so
     *  that it needs no more room in a narrow card than in a wide one:
     *
     *      ●  Asked you: run `inkscape a.svg`                        ×
     *         [Waits for you]  12:03:44
     *         host_run                                    (unfolded)
     *                                         [Refuse]  [Allow]
     *
     *  The dot says whether it needs the user, went against the agent, or went its way. A row
     *  that came since the user last looked is in bold. Clicking its words unfolds its details.
     */
    private UIForPanel<JPanel> rowView(Row row) {
        Val<RowWords> said = words.viewAs(RowWords.class, page -> RowWords.of(row, page));
        Val<Boolean> unfolded = Val.of(row.unfolded());
        return
            panel("fillx, ins 8 2 8 2, gap 8 2, hidemode 3", "[][grow][]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).borderAt(UI.Edge.TOP, 1, ViewPartsUtil.withAlpha(p.BORDER(), 140)))
            .add("cell 0 0, aligny top, gaptop 5", ViewPartsUtil.dot(palette.viewAs(Color.class, p -> colour(row, p)), 8))
            .add("cell 1 0, growx, wmin 0",
                ViewPartsUtil.words(theme, said.viewAsString(RowWords::summary), 12, row.fresh() ? 2f : 1f, Palette::TEXT)
                .withTooltip(words.viewAsString(Lang.NotificationsPage::CLICK_TO_SHOW_OR_HIDE_THE_DETAILS))
                .withCursor(UI.Cursor.HAND)
                .onMouseClick(_ -> open.update(From.VIEW, it -> row.unfolded() ? it.removeIf(row.item()::equals) : it.add(row.item())))
            )
            .add("cell 2 0, aligny top",
                button("×").group(Skin.ICON_BUTTON)
                .isVisibleIf(Val.of(row.status() != Status.WAITING))
                .withTooltip(words.viewAsString(Lang.NotificationsPage::REMOVE))
                .withStyle(theme, (now, it) -> it.padding(0, 6, 0, 6).componentFont(f -> f.family(now.fonts().FONT()).size(14).color(now.palette().SUBTEXT())))
                .onClick(_ -> remove(row.item()))
            )
            .add("cell 1 1, span 2, growx, wmin 0",
                box("ins 0, gap 8, hidemode 3")
                .add(ViewPartsUtil.tag(theme, said.viewAsString(RowWords::status)).isVisibleIf(said.viewAs(Boolean.class, it -> !it.status().isEmpty())))
                .add("aligny center", label(said.viewAsString(RowWords::when)).group(Skin.META))
            )
            .add("cell 1 2, span 2, growx, wmin 0, gaptop 2", details(row, said, unfolded))
            .add("cell 1 3, span 2, right, gaptop 4", answers(row));
    }

    /// The details of a row, unfolded: the stack trace of a trouble, in the font of code, and the
    /// rest as words.
    private UIForAnySwing<?, ?> details(Row row, Val<RowWords> said, Val<Boolean> unfolded) {
        if (row.item() instanceof Trouble trouble)
            return
                textArea(trouble.details()).group(Skin.INPUT)
                .isVisibleIf(unfolded)
                .isEditableIf(false)
                .withStyle(theme, (now, it) -> it.padding(6, 8, 6, 8).componentFont(f -> f.family(now.fonts().MONO()).size(11).color(now.palette().SUBTEXT())))
                .peek(area -> area.setCaretPosition(0));
        return
            ViewPartsUtil.note(theme, said.viewAsString(RowWords::details), Viewable.of(Boolean.class, unfolded, said, (on, it) -> on && !it.details().isEmpty()));
    }

    /// Refuse and Allow under a question that still waits, and Open under a link that works.
    private UIForAnySwing<?, ?> answers(Row row) {
        if (!(row.item() instanceof Notification notification)) return box().isVisibleIf(false);
        Status status = row.status();
        if (status == Status.WAITING && notification.about() instanceof Notifications.Question question)
            return
                box("ins 0, gap 8")
                .add(button(headerWords.viewAsString(Lang.Header::REFUSE)).group(Skin.QUIET_BUTTON).onClick(_ -> app.decidePermission(genie.get().id(), question.what(), false)))
                .add(button(headerWords.viewAsString(Lang.Header::ALLOW)).group(Skin.FLAME_BUTTON).onClick(_ -> app.decidePermission(genie.get().id(), question.what(), true)));
        if ((status == Status.SHOWN || status == Status.PUT_AWAY) && notification.about() instanceof Notifications.Published server)
            return
                box("ins 0, gap 8")
                .add(
                    button(headerWords.viewAsString(Lang.Header::OPEN)).group(Skin.QUIET_BUTTON)
                    .withTooltip(headerWords.viewAsString(Lang.Header::OPEN_IT_IN_YOUR_BROWSER))
                    .onClick(_ -> app.openInBrowser(server.link()))
                );
        return box().isVisibleIf(false);
    }

    private void remove(Object item) {
        open.update(From.VIEW, it -> it.removeIf(item::equals));
        switch (item) {
            case Notification notification -> notifications.update(From.VIEW, it -> it.without(notification));
            case Trouble trouble -> state.update(From.VIEW, it -> it.withoutTroubles(Tuple.of(Trouble.class, trouble)));
            default -> {}
        }
    }

    /// The dot of a row: flame while it waits on the user, trouble when it went against what the
    /// agent wanted or went wrong, brass while a link works or a request went through, and faded
    /// once it is over.
    private static Color colour(Row row, Palette p) {
        if (row.item() instanceof Trouble) return p.TROUBLE();
        if (!(row.item() instanceof Notification notification)) return p.SUBTEXT();
        return switch (row.status()) {
            case WAITING -> p.FLAME();
            case ALLOWED, SHOWN, PUT_AWAY -> p.BRASS();
            case REFUSED -> p.TROUBLE();
            case UNANSWERED, GONE -> p.SUBTEXT();
            case TOLD -> notification.important() ? p.TROUBLE() : p.BRASS();
        };
    }

    /// What a row says: what happened, what became of it, when, and its details.
    private record RowWords(String summary, String status, String when, String details) {

        static RowWords of(Row row, Lang.NotificationsPage page) {
            Instant now = Instant.now();
            return switch (row.item()) {
                case Notification n -> new RowWords(summary(n.about(), page), status(n, row.status(), page), when(n.at(), now) + extra(n.about()), details(n.about(), page));
                case Trouble t -> new RowWords(t.what(), "", when(t.when(), now) + " · " + t.where(), "");
                default -> new RowWords("", "", "", "");
            };
        }

        private static String summary(Notifications.About about, Lang.NotificationsPage page) {
            return switch (about) {
                case Notifications.HostTool tool -> page.ASKED_YOU(tool.what());
                case Notifications.WebPort port -> page.ASKED_TO_PUBLISH_THE_WEB_SERVER_ON_PORT(port.sandboxPort());
                case Notifications.Published server -> server.openedByTheAgent()
                                                       ? page.LISTENS_ON_PORT_NOW_OPEN_TO_YOUR_BROWSER(server.sandboxPort())
                                                       : page.PORT_OPEN_TO_YOUR_BROWSER_AS_THE_LAMP_LISTS_IT(server.sandboxPort(), server.name());
                case Notifications.Judged judged -> (judged.allowed() ? page.THE_CHECKER_ALLOWED(judged.tool()) : page.THE_CHECKER_REFUSED(judged.tool()))
                                                    + (judged.said().isBlank() ? "" : ": " + judged.said());
                case Notifications.Refused refused -> page.REFUSED_A_REQUEST_FOR(refused.tool());
                case Notifications.Warned warned -> warned.title();
            };
        }

        private static String status(Notification n, Status status, Lang.NotificationsPage page) {
            return switch (status) {
                case WAITING -> page.WAITS_FOR_YOU();
                case ALLOWED -> page.ALLOWED();
                case REFUSED -> page.REFUSED();
                case UNANSWERED -> page.UNANSWERED();
                case SHOWN, PUT_AWAY -> page.LINK_OPEN();
                case GONE -> page.LINK_GONE();
                case TOLD -> switch (n.about()) {
                    case Notifications.Judged judged -> judged.allowed() ? page.ALLOWED() : page.REFUSED();
                    case Notifications.Refused _ -> page.REFUSED();
                    case Notifications.Warned _ -> page.WARNING();
                    default -> "";
                };
            };
        }

        /// The tool and the tier under a row about a request, beside its time: these are names
        /// the lamp gives, which stay as they are in any language.
        private static String extra(Notifications.About about) {
            return switch (about) {
                case Notifications.HostTool tool -> " · " + tool.tool();
                case Notifications.Judged judged -> " · " + judged.tool() + " · " + judged.risk();
                case Notifications.Refused refused -> " · " + refused.tool();
                default -> "";
            };
        }

        /// What the lamp said beyond the summary, as it said it.
        private static String details(Notifications.About about, Lang.NotificationsPage page) {
            return switch (about) {
                case Notifications.HostTool tool -> tool.what();
                case Notifications.WebPort port -> port.what();
                case Notifications.Published server -> page.THE_LINK_LEADS_TO_PORT_IN_THE_SANDBOX(server.link().toString(), server.sandboxPort());
                case Notifications.Judged judged -> judged.reason();
                case Notifications.Refused refused -> refused.why();
                case Notifications.Warned warned -> warned.whatHappened();
            };
        }

        private static String when(Instant at, Instant now) {
            ZoneId zone = ZoneId.systemDefault();
            boolean today = LocalDate.ofInstant(at, zone).equals(LocalDate.ofInstant(now, zone));
            return (today ? TODAY : EARLIER).format(at.atZone(zone));
        }
    }

    // ─── the log ───────────────────────────────────────────────────────────────────────────

    /*
     *  The log unfolds under the cards, across the page, as the explanation on the host access
     *  page does: first the panel opens, then its lines fade in. Above the lines, words a line
     *  must hold, and which level and up to show:
     *
     *      What was logged, the last … lines
     *      🔍 Filter… [________________]   [ERROR | WARN | INFO | DEBUG | TRACE]
     *      12:03:44.120  INFO   lamp 1b2c…  Info[area=…, text=…]
     *      …
     *
     *  On a page narrower than LEVELS_IN_A_ROW_FROM, the levels stand one under the other, and
     *  what was logged moves beside them, with room around it, over the filter, which stays on
     *  the lines:
     *
     *                                    [ERROR]
     *         What was logged, the       [WARN ]
     *           last … lines             [INFO ]
     *                                    [DEBUG]
     *      🔍 Filter… [______________]   [TRACE]
     *      12:03:44.120  INFO   lamp 1b2c…  Info[area=…, text=…]
     */
    private UIForAnySwing<?, ?> theLog() {
        // The area is in SwingTree's unscaled pixels, as the threshold is.
        Val<Boolean> inARow = state.viewAs(Boolean.class, it -> it.area().width() >= LEVELS_IN_A_ROW_FROM);
        Val<Boolean> stacked = inARow.viewAs(Boolean.class, it -> !it);
        Val<String> whatWasLogged = words.viewAsString(page -> page.WHAT_WAS_LOGGED_THE_LAST_LINES(AppLog.MOST));
        List<ViewPartsUtil.Choice<AppLog.Level>> levels = new ArrayList<>();
        for (AppLog.Level level : AppLog.Level.values())
            levels.add(ViewPartsUtil.Choice.of(level, Val.of(level.name()), words.viewAsString(Lang.NotificationsPage::SHOW_LINES_OF_THIS_LEVEL_AND_ABOVE)));
        return
            box().withLayout(logUnfolding.viewAs(Layout.class, it -> new UnfoldingLayout(it.height())))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.SMOKE()).borderRadius(14))
            .add(
                box("fillx, wrap 1, ins 12 14 14 14, gap 8, hidemode 3", "[grow]")
                .add("growx, wmin 0", ViewPartsUtil.note(theme, whatWasLogged, inARow))
                .add("growx, wmin 0",
                    box("fill, ins 0, gap 10, hidemode 3", "[grow][]")
                    .add("grow, wmin 0",
                        box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]", "[grow][]")
                        // Its room around it comes from insets: gaps would stay when it is hidden.
                        .add("growx, wmin 0, aligny center",
                            box("fillx, ins 6 12 10 12", "[grow]").isVisibleIf(stacked)
                            .add("growx, wmin 0", ViewPartsUtil.note(theme, whatWasLogged, Val.of(true)))
                        )
                        .add("growx, wmin " + FILTER_AT_LEAST + ", aligny bottom", filterField())
                    )
                    .add("aligny bottom", ViewPartsUtil.oneOf(theme, levels, least, Val.of(true), inARow, level -> least.set(From.VIEW, level)))
                )
                .add("growx, wmin 0, h " + LOG_HEIGHT + "!",
                    scrollPane().withEmptyBorder(0)
                    .add(
                        textArea("").group(Skin.INPUT)
                        .isEditableIf(false)
                        .withStyle(Themed.of(logUnfolding, theme), (themed, it) -> {
                            Color shade = ViewPartsUtil.withAlpha(themed.palette().TEXT(), (int) Math.round(255 * themed.item().words()));
                            return it.componentFont(f -> f.family(themed.fonts().MONO()).size(11).color(shade));
                        })
                        .peek(area -> logLines = area)
                    )
                )
            );
    }

    /// Where the user types words a line of the log must hold, with a magnifying glass before them.
    private UIForAnySwing<?, ?> filterField() {
        return
            textField(filter).group(Skin.INPUT)
            .withTooltip(words.viewAsString(Lang.NotificationsPage::FILTER))
            .withStyle(palette, (p, it) -> it
                .padding(6, 10, 6, 30)
                // In front: the field paints its own background over what lies behind its words.
                .image(UI.Layer.FOREGROUND, img -> img
                    .image(SignSvgUtil.sign(SignSvgUtil.SEARCH, p.SUBTEXT()))
                    .placement(UI.Placement.LEFT)
                    .size(14, 14)
                    // An offset, not padding: the size includes the padding.
                    .horizontalOffset(10)
                )
            )
            // FlatLaf's grey words in the empty field, in the language of the moment.
            .peek(field -> {
                field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, words.get().FILTER());
                Viewable.cast(words).onChange(From.ALL, it -> it.currentValue().ifPresent(now ->
                    field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, now.FILTER())
                ));
            });
    }

    /// Unfolds the log, or folds it away, one frame after the other, until it arrived where the
    /// user wants it. The user may change their mind on the way: it turns back from where it is.
    private void unfoldTheLog() {
        if (logUnfoldingPlays || logUnfolding.get().arrived(logShown.get())) return;
        logUnfoldingPlays = true;
        UI.animateFor(LifeTime.of(1, TimeUnit.SECONDS)).asLongAs(_ -> !logUnfolding.get().arrived(logShown.get())).go(new Animation() {
            private double previous;

            @Override public void run(AnimationStatus status) {
                double elapsed = status.repeats() + status.progress();
                logUnfolding.update(From.VIEW, it -> it.toward(logShown.get(), elapsed - previous));
                previous = elapsed;
            }

            @Override public void finish(AnimationStatus status) {
                logUnfoldingPlays = false;
                unfoldTheLog();
            }
        });
    }

    /// Looks at the log every [#LOG_EVERY] while it is unfolded, and not otherwise.
    private void readingTheLog() {
        if (!logShown.get()) {
            logReader.stop();
            return;
        }
        if (!logReader.isRunning()) logReader.start();
        readTheLog();
    }

    /// Shows the lines of the log the user asked for, when there are new ones or they asked for
    /// others, while it is on screen: not while another page is.
    private void readTheLog() {
        if (!logLines.isShowing()) return;
        AppLog log = AppLog.shared();
        String readFor = least.get() + "\n" + filter.get();
        long added = log.added();
        if (added == logRead && readFor.equals(logReadFor)) return;
        logRead = added;
        logReadFor = readFor;
        String wanted = filter.get().strip().toLowerCase(Locale.ROOT);
        StringBuilder text = new StringBuilder();
        for (AppLog.Line line : log.lines()) {
            if (!line.level().atLeast(least.get())) continue;
            String shown = String.format("%s  %-5s  %s  %s", LOGGED.format(line.when().atZone(ZoneId.systemDefault())), line.level(), line.source(), line.text());
            if (!wanted.isEmpty() && !shown.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            text.append(shown).append('\n');
        }
        if (text.isEmpty()) text.append(words.get().NO_LINE_MATCHES());
        boolean atTheEnd = logLines.getCaretPosition() >= logLines.getDocument().getLength();
        logLines.setText(text.toString());
        if (atTheEnd) logLines.setCaretPosition(logLines.getDocument().getLength());
    }
}
