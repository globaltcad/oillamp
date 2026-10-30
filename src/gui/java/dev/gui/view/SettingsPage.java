package dev.gui.view;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.swing.JComponent;

import dev.gui.Genies;
import dev.gui.model.GeniesState;
import dev.gui.model.Lang;
import dev.gui.model.OllamaSetup;
import dev.gui.model.Palette;
import dev.gui.model.Settings;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.layout.FlowCell;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;
import swingtree.style.StyledString;

import static swingtree.UI.*;

/// The settings: where the genies' model runs, and how they reach it.
///
/// They offer two ways to a model. The simple one comes first: Genies sets up Ollama on this
/// computer, with a model suggested for it. The advanced one is folded away: Eden AI, a model
/// server elsewhere, or any model server on this computer.
///
/// Without genies, the page is the first thing the user sees, and welcomes them: the lamp lights,
/// Pip takes form from its flame ([WelcomeScene]), and a few lines say what genies are and what
/// they need.
///
/// Every field is a lens onto one value of the [GeniesState], so the page keeps nothing of its
/// own but its welcome's animation. The app keeps the settings when the page is left, by Done or
/// otherwise.
final class SettingsPage {

    private SettingsPage() {}

    /*
     *  A form on a responsive grid. The card's settings come in two halves, side by side once
     *  the card is wider than its reference width, one above the other otherwise:
     *
     *                 very small  small  medium  large  very large  oversize
     *      half            12       12     12     12       12          6
     *
     *  Each half is a grid of its own, as a pair of cells per setting, a label and its field:
     *
     *      label           12       12     12     12        4          3
     *      field           12       12     12     12        8          9
     *
     *  So in a wide half the labels stand left of their fields, and in a narrow one above them,
     *  where a long label is never cut short. The card itself is a cell of the page's grid,
     *  narrower on a wide page so lines stay short.
     */
    private static final int PAGE_REFERENCE = 900;
    private static final int CARD_REFERENCE = 760;
    private static final int HALF_REFERENCE = 560;
    private static final FlowCell CARD = AUTO_SPAN(it ->
        it.fill(true)
        .verySmall(12).small(12).medium(12).large(10).veryLarge(8).oversize(8)
    );
    private static final FlowCell WHOLE = AUTO_SPAN(it ->
        it.verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12)
    );
    private static final FlowCell HALF = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(6)
    );
    private static final FlowCell LABEL = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(12).veryLarge(4).oversize(3)
    );
    private static final FlowCell FIELD = AUTO_SPAN(it ->
        it.verySmall(12).small(12).medium(12).large(12).veryLarge(8).oversize(9)
    );

    /// The card's heading: its title and what it is for, with the switch between the simple way
    /// and the advanced way in the top right corner of a wide card, under the words in a narrow one.
    private static final Layout SWITCH_BESIDE =
        Layout.mig(
            LayoutConstraint.of("fill, ins 0, gap 24 0"),
            LayoutConstraint.of("[grow][]"),
            LayoutConstraint.of("")
        )
        .withChildConstraints(MigAddConstraint.of("growx, wmin 0, top"), MigAddConstraint.of("top, gaptop 4"));
    private static final Layout SWITCH_UNDER =
        Layout.mig(
            LayoutConstraint.of("fill, wrap 1, ins 0, gap 0 12"),
            LayoutConstraint.of("[grow]"),
            LayoutConstraint.of("")
        )
        .withChildConstraints(MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of(""));

    /*
     *  The welcome on a grid of its own, its words and the room for its picture:
     *
     *                 very small  small  medium  large  very large  oversize
     *      words           12       12     12     12        7          7
     *      picture         12       12     12     12        5          5
     *
     *  So in a wide welcome the words stand left of the picture, and in a narrow one below it.
     *  The picture is painted on the whole welcome, so that it can move from the middle to its
     *  room beside the words.
     */
    private static final int WELCOME_REFERENCE = 760;

    /// How much larger the welcome's words grow, at most, as it grows wider than its reference
    /// width: as much larger as it is wider.
    private static final double MOST_LARGER = 1.35;
    private static final int BESIDE_SPAN = 5;
    private static final FlowCell WORDS = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.CENTER)
        .verySmall(12).small(12).medium(12).large(12).veryLarge(12 - BESIDE_SPAN).oversize(12 - BESIDE_SPAN)
    );
    private static final FlowCell BESIDE = AUTO_SPAN(it ->
        it.verySmall(12).small(12).medium(12).large(12).veryLarge(BESIDE_SPAN).oversize(BESIDE_SPAN)
    );

    /// When the welcome's words fade in, and how long they take, in seconds of its play.
    private static final double WORDS_FROM = 7.4;
    private static final double WORDS_TAKE = 0.8;

    /// How tall the welcome's picture is. The genie's hat is some 140 units below its top, so the
    /// top of the picture is cut away, leaving room above the hat for the genie to float and
    /// sparkle in: `LEAST_CUT` while the welcome is `UNCUT_WIDTH` wide or wider, `PICTURE_CUT`
    /// once it is `CUT_WIDTH` or narrower, where the empty top would look tall beside the
    /// picture's narrow sides, and in between, an even share. The lamp and the genie stay their
    /// size, at the bottom.
    private static final int PICTURE_HEIGHT = 360;
    private static final int LEAST_CUT = 90;
    private static final int PICTURE_CUT = 115;
    private static final int UNCUT_WIDTH = 760;
    private static final int CUT_WIDTH = 360;

    /// When the settings' card appears below the welcome, in seconds of its play.
    private static final double CARD_AT = 8.2;

    /// In a wide welcome, when the genie, once it has taken form, moves aside, and how long that
    /// takes; then when the words fade in on its left, and how long they take, and when the
    /// settings' card appears, in seconds of its play.
    private static final double ASIDE_FROM = 8.0;
    private static final double ASIDE_TAKES = 1.0;
    private static final double WORDS_BESIDE_FROM = 8.8;
    private static final double WORDS_BESIDE_TAKE = 1.2;
    private static final double CARD_BESIDE_AT = 9.8;

    static UIForAnySwing<?, ?> of(Var<GeniesState> state, Genies app, Val<Boolean> visible) {
        Val<Theme> theme = Theme.of(state);
        Val<Palette> palette = Theme.paletteOf(state);
        Val<Lang.SettingsPage> words = Theme.langOf(state).viewAs(Lang.SettingsPage.class, Lang::SETTINGS_PAGE);
        // The window in the light palette, or the dark one.
        Var<Boolean> light = state.zoomTo(
            it -> !it.palette().isDark(),
            (it, on) -> it.withPalette(on ? Palette.LIGHT : Palette.DARK)
        );
        Var<Settings> settings = state.zoomTo(GeniesState::settings, GeniesState::withSettings);
        // Lenses all the way down: settings → Eden AI → its key, and so on. Each field
        // edits one value of the one GeniesState, and nothing else.
        Var<Settings.Place> place = settings.zoomTo(Settings::place, Settings::withPlace);
        Var<Settings.EdenAi> edenAi = settings.zoomTo(Settings::edenAi, Settings::withEdenAi);
        Var<Settings.Elsewhere> elsewhere = settings.zoomTo(Settings::elsewhere, Settings::withElsewhere);
        Var<Settings.OnThisMachine> local = settings.zoomTo(Settings::local, Settings::withLocal);
        Var<Settings.KeySource> source = edenAi.zoomTo(Settings.EdenAi::keySource, Settings.EdenAi::withKeySource);
        Var<String> edenKey = edenAi.zoomTo(Settings.EdenAi::key, Settings.EdenAi::withKey);
        Var<String> remoteAddress = elsewhere.zoomTo(Settings.Elsewhere::address, Settings.Elsewhere::withAddress);
        Var<String> remoteKey = elsewhere.zoomTo(Settings.Elsewhere::key, Settings.Elsewhere::withKey);
        Var<String> localAddress = local.zoomTo(Settings.OnThisMachine::address, Settings.OnThisMachine::withAddress);
        // The model of whichever place is chosen, so one field serves all three.
        Var<String> model = settings.zoomTo(Settings::model, Settings::withModel);
        Var<Boolean> advanced = state.zoomTo(GeniesState::advanced, GeniesState::withAdvanced);
        Var<OllamaSetup> ollama = state.zoomTo(GeniesState::ollama, GeniesState::withOllama);
        Var<String> wanted = ollama.zoomTo(OllamaSetup::wanted, OllamaSetup::withWanted);
        // Only what the service now named offered; after the place or address changed, nothing.
        Val<Tuple<String>> offered = state.viewAs(
            Tuple.classTyped(String.class),
            it -> it.lookUp().isFor(it.settings()) ? it.lookUp().models() : Tuple.of(String.class)
        );
        Val<String> lookUpNote = state.viewAsString(it -> it.lookUp().isFor(it.settings()) ? it.lookUp().note() : "");
        Val<Boolean> simple = advanced.viewAs(Boolean.class, it -> !it);
        Val<Boolean> isEdenAi = state.viewAs(Boolean.class, it -> it.advanced() && it.settings().place() == Settings.Place.EDEN_AI);
        Val<Boolean> isElsewhere = state.viewAs(Boolean.class, it -> it.advanced() && it.settings().place() == Settings.Place.ELSEWHERE);
        Val<Boolean> isLocal = state.viewAs(Boolean.class, it -> it.advanced() && it.settings().place() == Settings.Place.THIS_MACHINE);
        Val<String> problem = state.viewAsString(it -> it.settingsProblem().orElse(""));
        Val<Boolean> hasProblem = state.viewAs(Boolean.class, it -> it.advanced() && it.settingsProblem().isPresent());
        Val<Boolean> fine = state.viewAs(Boolean.class, it -> it.advanced() && it.settingsProblem().isEmpty());
        Val<Boolean> welcome = state.viewAs(Boolean.class, it -> !it.hasGenies());
        Val<String> awakeNote = state.viewAsString(it -> it.noteForAwakeGenies().orElse(""));
        Val<Boolean> anyAwake = awakeNote.viewAs(Boolean.class, it -> !it.isEmpty());
        // Whether the card's halves stand side by side.
        Var<Boolean> split = Var.of(false);
        Val<Boolean> busy = ollama.viewAs(Boolean.class, it -> it.step().isBusy());
        Val<Boolean> inUse = state.viewAs(Boolean.class, it -> it.ollama().inUse(it.settings()));
        boolean found = state.get().environmentKey().isPresent();
        // The welcome plays when Genies starts without genies, and again when the last is
        // deleted. Its clock is the seconds since it began, and it runs as long as it is shown.
        // A click on the genie starts another play of its picture, on the same clock.
        Var<Double> clock = Var.of(welcome.get() ? 0.0 : CARD_AT);
        Var<WelcomeScene.Play> scene = Var.of(WelcomeScene.Play.first(0));
        // How much of the welcome's picture is cut from its top, in units, and the welcome
        // itself, so that the page can paint its light where it is.
        Var<Integer> cut = Var.of(LEAST_CUT);
        AtomicReference<JComponent> welcomeBox = new AtomicReference<>();
        // How the welcome fits its width, and how far its picture has moved from the middle to
        // its room beside the words, from 0 to 1.
        Var<Fit> fit = Var.of(new Fit(false, 1));
        Var<Double> aside = Var.of(0.0);
        // Without genies, the cards come after the welcome has had its moment.
        Val<Boolean> cardsShown = Viewable.of(Boolean.class, welcome, clock, (first, at) ->
            !first || at >= (fit.get().wide() ? CARD_BESIDE_AT : CARD_AT)
        );
        AtomicLong began = new AtomicLong();
        AtomicBoolean playing = new AtomicBoolean();
        if (welcome.get()) play(clock, scene, fit, aside, welcome, began, playing);
        Viewable.cast(welcome).onChange(From.ALL, it -> {
            if (it.currentValue().orElse(false)) play(clock, scene, fit, aside, welcome, began, playing);
        });
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.CENTER, 0, 30)
                .withMinSize(0, 0).withPrefSize(PAGE_REFERENCE, 0)
                // The welcome's light, on the whole page behind it, so that only the window's
                // edges cut it off.
                .withStyle(Themed.of(clock, theme), (themed, it) -> {
                    var at = themed.item();
                    Palette p = themed.palette();
                    return it.backgroundColor(p.TRANSPARENT()).padding(0, 16, 0, 16)
                    .painter(UI.Layer.BACKGROUND, g -> {
                        JComponent shown = welcomeBox.get();
                        if (shown == null || !shown.isVisible()) return;
                        // SwingTree scales a painter's units to pixels; the welcome paints in pixels.
                        g.scale(1 / UI.scale(), 1 / UI.scale());
                        g.translate(shown.getX() + asideBy(shown.getWidth(), aside.get()), shown.getY());
                        WelcomeScene.light(g, p, shown.getWidth(), UI.scale(PICTURE_HEIGHT), UI.scale(cut.get()), scene.get(), at);
                    });
                })
                .add(CARD, welcome(state, clock, scene, cut, fit, aside, welcomeBox, welcome))
                .add(CARD,
                    panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 14, 12).group(Skin.CARD)
                    .withMinSize(0, 0).withPrefSize(CARD_REFERENCE, 0)
                    // Split where the grid puts the halves side by side: past its reference width.
                    .onResize(it -> split.set(it.getComponent().getWidth() > UI.scale(CARD_REFERENCE)))
                    .isVisibleIf(cardsShown)
                    .add(WHOLE,
                        box(split.viewAs(Layout.class, beside -> beside ? SWITCH_BESIDE : SWITCH_UNDER))
                        .add(
                            box("fill, wrap 1, ins 0, gap 12")
                            .add("growx, wmin 0", label(Viewable.of(String.class, welcome, words, (it, page) -> it ? page.YOUR_GENIES_MODEL() : page.SETTINGS())).group(Skin.EMPTY_TITLE))
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    Viewable.of(String.class, advanced, words, (it, page) -> it
                                        ? page.WHERE_YOUR_GENIES_MODEL_RUNS_AND_HOW_THEY_REACH_IT()
                                        : page.GENIES_SETS_UP_A_MODEL_ON_THIS_COMPUTER_FOR_YOU_WITH_OLLAMA()
                                    ),
                                    Palette::SUBTEXT,
                                    Val.of(true)
                                )
                            )
                            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, awakeNote, Palette::SUBTEXT, anyAwake))
                        )
                        .add(
                            box("ins 0, hidemode 3")
                            .add(
                                ViewPartsUtil.link(theme, words.viewAsString(it -> it.ADVANCED_EDEN_AI_OR_A_MODEL_SERVER_OF_YOUR_OWN() + "  ›")).isVisibleIf(simple)
                                .withTooltip(words.viewAsString(Lang.SettingsPage::FOR_A_MODEL_ELSEWHERE_OR_A_MODEL_SERVER_OTHER_THAN_OLLAMA))
                                .onClick(_ -> advanced.set(From.VIEW, true))
                            )
                            .add(
                                ViewPartsUtil.link(theme, words.viewAsString(it -> "‹  " + it.BACK_TO_THE_SIMPLE_WAY_OLLAMA_SET_UP_BY_GENIES())).isVisibleIf(advanced)
                                .onClick(_ -> advanced.set(From.VIEW, false))
                            )
                        )
                    )

                    // ── the simple way: Ollama, set up by Genies ──
                    // What this computer has in one half, the model and setting it up in the other.
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(simple)
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::THIS_COMPUTER)))
                        .add(FIELD,
                            ViewPartsUtil.wrapped(
                                theme,
                                Viewable.of(String.class, ollama, words, (it, page) ->
                                    it.step() == OllamaSetup.Step.LOOKING
                                        ? page.GENIES_IS_LOOKING_WHAT_THIS_COMPUTER_HAS()
                                        : it.found().hardware().words()
                                ),
                                Palette::TEXT,
                                Val.of(true)
                            )
                        )
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::OLLAMA)))
                        .add(FIELD, ViewPartsUtil.wrapped(theme, ollama.viewAsString(OllamaSetup::words), Palette::TEXT, Val.of(true)))
                    )
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(simple)
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::MODEL)))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4, hidemode 3")
                            .add("growx, wmin 0",
                                comboBox(wanted, ollama.viewAs(Tuple.classTyped(String.class), OllamaSetup::offered))
                                .isEditableIf(true).isEnabledIf(busy.viewAs(Boolean.class, it -> !it))
                            )
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    ollama.viewAsString(it -> it.found().hardware().words(it.wanted())),
                                    Palette::TEXT,
                                    ollama.viewAs(Boolean.class, it -> it.step() != OllamaSetup.Step.LOOKING && !it.wanted().isBlank())
                                )
                            )
                            .add("growx, wmin 0",
                                ViewPartsUtil.note(
                                    theme,
                                    words.viewAsString(Lang.SettingsPage::GENIES_SUGGESTS_THE_BEST_MODEL_THIS_COMPUTER_RUNS_WELL),
                                    Val.of(true)
                                )
                            )
                        )
                        .add(LABEL, label(""))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 6, hidemode 3")
                            .add(
                                button(ollama.viewAsString(OllamaSetup::todo)).group(Skin.FLAME_BUTTON)
                                .isVisibleIf(state.viewAs(Boolean.class, it ->
                                    !it.ollama().step().isBusy() &&
                                    it.ollama().step() != OllamaSetup.Step.LOOKING &&
                                    !it.ollama().inUse(it.settings()) &&
                                    !it.ollama().wanted().isBlank()
                                ))
                                .onClick(_ -> app.setUpOllama())
                            )
                            .add("growx, wmin 0",
                                label(Viewable.of(String.class, ollama, words, (it, page) -> "✓  " + page.YOUR_GENIES_USE().of(it.wanted().strip())))
                                .group(Skin.FINE)
                                .isVisibleIf(Viewable.of(Boolean.class, inUse, busy, (yes, working) -> yes && !working))
                            )
                            .add("growx, wmin 0",
                                box("fill, ins 0, gap 10", "[grow][]").isVisibleIf(busy)
                                .add("growx, wmin 60, h 6!", progress(theme, ollama.viewAs(Double.class, OllamaSetup::progress)))
                                .add(
                                    button(words.viewAsString(Lang.SettingsPage::STOP)).group(Skin.QUIET_BUTTON)
                                    .withTooltip(words.viewAsString(Lang.SettingsPage::STOP_SETTING_UP_A_MODELS_DOWNLOAD_GOES_ON_THE_NEXT_TIME))
                                    .onClick(_ -> app.stopSettingUp())
                                )
                            )
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    ollama.viewAsString(OllamaSetup::says),
                                    Palette::SUBTEXT,
                                    ollama.viewAs(Boolean.class, it -> it.step() != OllamaSetup.Step.FAILED && !it.says().isEmpty())
                                )
                            )
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    ollama.viewAsString(OllamaSetup::says),
                                    Palette::TROUBLE,
                                    ollama.viewAs(Boolean.class, it -> it.step() == OllamaSetup.Step.FAILED)
                                )
                            )
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    Viewable.of(String.class, settings, words, (it, page) -> switch (it.place()) {
                                        case EDEN_AI -> page.UNTIL_THEN_YOUR_GENIES_USE_EDEN_AI();
                                        case ELSEWHERE -> page.UNTIL_THEN_YOUR_GENIES_USE_THE_MODEL_SERVER_AT().of(it.elsewhere().address().strip());
                                        case THIS_MACHINE -> page.UNTIL_THEN_YOUR_GENIES_USE_THE_MODEL_SERVER_AT().of(it.local().address().strip());
                                    }),
                                    Palette::SUBTEXT,
                                    state.viewAs(Boolean.class, it -> !it.settings().usesOllama() && it.settingsProblem().isEmpty())
                                )
                            )
                        )
                    )

                    // ── the advanced way: where the model runs in one half, how to reach it in the other ──
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(advanced)
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::THE_MODEL_RUNS)))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 6")
                            .add("wmin 0", radioButton("", Settings.Place.EDEN_AI, place).withText(words.viewAsString(Lang.SettingsPage::AT_EDEN_AI)))
                            .add("growx, wmin 0, gapleft 24",
                                ViewPartsUtil.note(theme, words.viewAsString(Lang.SettingsPage::IN_THE_EU_WITH_YOUR_EDEN_AI_KEY), Val.of(true))
                            )
                            .add("wmin 0", radioButton("", Settings.Place.ELSEWHERE, place).withText(words.viewAsString(Lang.SettingsPage::ON_A_SERVER_ELSEWHERE)))
                            .add("growx, wmin 0, gapleft 24",
                                ViewPartsUtil.note(theme, words.viewAsString(Lang.SettingsPage::A_MODEL_SERVER_OF_YOUR_OWN_ON_ANOTHER_MACHINE), Val.of(true))
                            )
                            .add("wmin 0", radioButton("", Settings.Place.THIS_MACHINE, place).withText(words.viewAsString(Lang.SettingsPage::ON_THIS_COMPUTER)))
                            .add("growx, wmin 0, gapleft 24",
                                ViewPartsUtil.note(theme, words.viewAsString(Lang.SettingsPage::A_MODEL_SERVER_SUCH_AS_OLLAMA_LM_STUDIO_OR_LLAMA_CPP), Val.of(true))
                            )
                        )
                    )

                    // One half above the other, a line across the card between them: below it are
                    // the settings of the place chosen above.
                    .add(WHOLE,
                        box()
                        .isVisibleIf(Viewable.of(Boolean.class, advanced, split, (shown, beside) -> shown && !beside))
                        .withStyle(palette, (p, it) -> it.borderAt(UI.Edge.TOP, 1, p.BORDER()))
                    )
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(advanced)
                        // ── Eden AI ──
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::KEY)).isVisibleIf(isEdenAi))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 6").isVisibleIf(isEdenAi)
                            .add("wmin 0",
                                radioButton("", Settings.KeySource.ENVIRONMENT, source)
                                .withText(words.viewAsString(it -> it.USE().of(Settings.KEY_VARIABLE)))
                            )
                            .add("growx, wmin 0, gapleft 24",
                                ViewPartsUtil.note(
                                    theme,
                                    words.viewAsString(it -> found ? it.FOUND_WHERE_GENIES_WAS_STARTED() : it.NOT_SET_WHERE_GENIES_WAS_STARTED()),
                                    Val.of(true)
                                )
                            )
                            .add("wmin 0", radioButton("", Settings.KeySource.ENTERED, source).withText(words.viewAsString(Lang.SettingsPage::USE_THIS_KEY)))
                            .add("growx, wmin 0",
                                passwordField(edenKey).group(Skin.INPUT)
                                .isEnabledIf(source.viewAs(Boolean.class, it -> it == Settings.KeySource.ENTERED))
                            )
                            .add("growx, wmin 0", ViewPartsUtil.note(theme, words.viewAsString(Lang.SettingsPage::THE_KEY_STAYS_ON_THIS_COMPUTER_GENIES_NEVER_SEE_IT), Val.of(true)))
                        )

                        // ── a model server elsewhere ──
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::ADDRESS)).isVisibleIf(isElsewhere))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isElsewhere)
                            .add("growx, wmin 0", textField(remoteAddress).group(Skin.INPUT))
                            .add("growx, wmin 0",
                                ViewPartsUtil.note(
                                    theme,
                                    words.viewAsString(Lang.SettingsPage::WHERE_THE_SERVERS_API_IS_IT_MUST_BE_HTTPS),
                                    Val.of(true)
                                )
                            )
                        )
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::KEY)).isVisibleIf(isElsewhere))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isElsewhere)
                            .add("growx, wmin 0", passwordField(remoteKey).group(Skin.INPUT))
                            .add("growx, wmin 0",
                                ViewPartsUtil.note(
                                    theme,
                                    words.viewAsString(it ->
                                        it.LEAVE_IT_EMPTY_IF_THE_SERVER_ASKS_FOR_NONE() + " " + it.THE_KEY_STAYS_ON_THIS_COMPUTER_GENIES_NEVER_SEE_IT()
                                    ),
                                    Val.of(true)
                                )
                            )
                        )

                        // ── a model server on this computer ──
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::ADDRESS)).isVisibleIf(isLocal))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isLocal)
                            .add("growx, wmin 0", textField(localAddress).group(Skin.INPUT))
                            .add("growx, wmin 0",
                                ViewPartsUtil.note(
                                    theme,
                                    words.viewAsString(Lang.SettingsPage::WHERE_THE_SERVERS_API_IS_ON_THIS_COMPUTER_NO_KEY_IS_NEEDED),
                                    Val.of(true)
                                )
                            )
                        )

                        // ── the model, wherever it runs ──
                        .add(LABEL, label(words.viewAsString(Lang.SettingsPage::MODEL)))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4, hidemode 3")
                            .add("growx, wmin 0",
                                box("fill, ins 0, gap 8", "[grow][]")
                                .add("growx, wmin 0", comboBox(model, offered).isEditableIf(true))
                                .add(
                                    button(words.viewAsString(Lang.SettingsPage::LOOK_UP)).group(Skin.QUIET_BUTTON)
                                    .withTooltip(words.viewAsString(Lang.SettingsPage::ASK_THE_SERVICE_WHICH_MODELS_IT_OFFERS))
                                    .onClick(_ -> app.lookUpModels())
                                )
                            )
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    lookUpNote,
                                    Palette::SUBTEXT,
                                    lookUpNote.viewAs(Boolean.class, it -> !it.isEmpty())
                                )
                            )
                        )
                    )

                    .add(WHOLE,
                        label(words.viewAsString(Lang.SettingsPage::CHANGES_TAKE_EFFECT_WHEN_A_GENIE_WAKES)).group(Skin.META)
                        .isVisibleIf(welcome.viewAs(Boolean.class, it -> !it))
                    )
                    .add(WHOLE, ViewPartsUtil.wrapped(theme, problem, Palette::TROUBLE, hasProblem))
                    .add(WHOLE, label(words.viewAsString(it -> "✓  " + it.YOUR_GENIES_CAN_REACH_THEIR_MODEL())).group(Skin.FINE).isVisibleIf(fine))
                    .add(WHOLE,
                        box("fill, ins 0, hidemode 3", "[grow, right]")
                        .add(
                            button(words.viewAsString(Lang.SettingsPage::DONE)).group(Skin.FLAME_BUTTON)
                            .isVisibleIf(welcome.viewAs(Boolean.class, it -> !it))
                            .onClick(_ -> app.settingsDone())
                        )
                        .add(
                            button(words.viewAsString(it -> "+  " + it.YOUR_FIRST_GENIE())).group(Skin.FLAME_BUTTON)
                            .withTooltip(words.viewAsString(Lang.SettingsPage::A_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN_THAT_WAKES_AT_ONCE))
                            .isVisibleIf(state.viewAs(Boolean.class, it ->
                                !it.hasGenies() &&
                                it.settingsProblem().isEmpty() &&
                                !it.ollama().step().isBusy()
                            ))
                            .onClick(_ -> app.newGenie())
                        )
                    )
                )
                // How the window looks, which takes effect at once, and is kept as it changes.
                .add(CARD,
                    panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 14, 12).group(Skin.CARD)
                    .withMinSize(0, 0).withPrefSize(CARD_REFERENCE, 0)
                    .isVisibleIf(cardsShown)
                    .add(WHOLE,
                        box(split.viewAs(Layout.class, beside -> beside ? SWITCH_BESIDE : SWITCH_UNDER))
                        .add(
                            box("fill, wrap 1, ins 0, gap 12")
                            .add("growx, wmin 0", label(words.viewAsString(Lang.SettingsPage::APPEARANCE)).group(Skin.EMPTY_TITLE))
                            .add("growx, wmin 0",
                                ViewPartsUtil.wrapped(
                                    theme,
                                    words.viewAsString(Lang.SettingsPage::THE_LAMP_AT_NIGHT_OR_BY_DAY),
                                    Palette::SUBTEXT,
                                    Val.of(true)
                                )
                            )
                        )
                        .add(ViewPartsUtil.eitherOr(theme, words.viewAsString(Lang.SettingsPage::DARK), words.viewAsString(Lang.SettingsPage::LIGHT), light, Val.of(true), light::set))
                    )
                )
            );
    }

    /// The welcome, above the settings while there are no genies: its picture, and its words. In
    /// a wide welcome, the genie moves aside once it has taken form, and the words fade in on its
    /// left; in a narrow one, they fade in below it.
    private static UIForAnySwing<?, ?> welcome(
        final Var<GeniesState> state,
        final Val<Double> clock,
        final Var<WelcomeScene.Play> scene,
        final Var<Integer> cut,
        final Var<Fit> fit,
        final Val<Double> aside,
        final AtomicReference<JComponent> welcomeBox,
        final Val<Boolean> welcome
    ) {
        Val<Theme> theme = Theme.of(state);
        Val<String> foundKey = state.viewAsString(it ->
            it.environmentKey().isPresent() && it.settings().place() == Settings.Place.EDEN_AI
                ? "Genies found an Eden AI key where it was started, so your genies can think at Eden AI right away. "
                  + "Or have Genies set up a model on this computer instead: the simple way, below."
                : "To think, genies need a model. Genies can set one up for you, right here on this computer."
        );
        Val<Integer> room = cut.viewAs(Integer.class, it -> PICTURE_HEIGHT - it);
        return
            // Rows close together: the page already keeps the welcome apart from the card below.
            panel().withFlowLayout(UI.HorizontalAlignment.CENTER, 0, 4)
            .withMinSize(0, 0).withPrefSize(WELCOME_REFERENCE, 0)
            .isVisibleIf(welcome).peek(welcomeBox::set)
            .onResize(it -> {
                int width = it.getComponent().getWidth();
                double narrowed = (UNCUT_WIDTH - UI.unscale(width)) / (double) (UNCUT_WIDTH - CUT_WIDTH);
                cut.set((int) Math.round(LEAST_CUT + (PICTURE_CUT - LEAST_CUT) * Math.max(0, Math.min(1, narrowed))));
                // Wide where the grid puts the words beside the picture: from four fifths of its
                // reference width on.
                fit.set(new Fit(
                    5 * width >= 4 * UI.scale(WELCOME_REFERENCE),
                    Math.max(1, Math.min(MOST_LARGER, UI.unscale(width) / (double) WELCOME_REFERENCE))
                ));
            })
            // Painted whole, its cut top above the welcome, and as far aside as it has moved.
            .withStyle(Themed.of(clock, theme), (themed, it) -> {
                var at = themed.item();
                Palette p = themed.palette();
                int width = UI.scale(it.componentWidth());
                return it.backgroundColor(p.TRANSPARENT()).painter(UI.Layer.CONTENT, g -> {
                    // SwingTree scales a painter's units to pixels; the welcome paints in pixels.
                    g.scale(1 / UI.scale(), 1 / UI.scale());
                    g.translate(asideBy(width, aside.get()), -UI.scale(cut.get()));
                    WelcomeScene.paint(g, p, width, UI.scale(PICTURE_HEIGHT), scene.get(), at);
                });
            })
            // The genie, once it has taken form, and its lamp can be clicked: it vanishes in a
            // poof, and another genie comes.
            .onMouseMove(it -> {
                boolean onGenie = scene.get().formed(clock.get()) && onPicture(it.getEvent(), it.getComponent().getWidth(), cut, aside);
                it.getComponent().setCursor(Cursor.getPredefinedCursor(onGenie ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            })
            .onMouseClick(it -> {
                if (scene.get().formed(clock.get()) && onPicture(it.getEvent(), it.getComponent().getWidth(), cut, aside))
                    scene.set(scene.get().poof(clock.get()));
            })
            // The room for the picture: above the words in a narrow welcome, beside them in a wide one.
            .add(WHOLE,
                box().withMinSize(0, 0)
                .withHeightExactly(room)
                .isVisibleIf(fit.viewAs(Boolean.class, it -> !it.wide()))
            )
            .add(WORDS,
                box("wrap 1, ins 0, gap 12", "[grow, fill]").withMinSize(0, 0)
                .add("growx, wmin 0", fading(theme, Val.of("Welcome to Genies"), 22, Palette::TEXT, clock, fit, aside))
                .add("growx, wmin 0",
                    fading(
                        theme,
                        Val.of(
                            "A genie is an AI helper with a computer of its own: a Linux desktop in a sandbox, "
                            + "where it can browse the web, run programs and make files, without ever touching yours."
                        ),
                        14,
                        Palette::SUBTEXT,
                        clock,
                        fit,
                        aside
                    )
                )
                .add("growx, wmin 0", fading(theme, foundKey, 14, Palette::SUBTEXT, clock, fit, aside))
            )
            .add(BESIDE,
                box().withMinSize(0, 0)
                .withHeightExactly(room)
                .isVisibleIf(fit.viewAs(Boolean.class, Fit::wide))
            );
    }

    /// Whether `event` is on the genie or its lamp in a welcome `width` pixels wide. The event's
    /// own position is in pixels, as the picture is painted in; SwingTree's `mouseX()` and
    /// `mouseY()` are in units, which miss the genie by as much as the window is scaled.
    private static boolean onPicture(MouseEvent event, int width, Val<Integer> cut, Val<Double> aside) {
        return WelcomeScene.hits(
            width, UI.scale(PICTURE_HEIGHT),
            event.getX() - asideBy(width, aside.get()),
            event.getY() + UI.scale(cut.get())
        );
    }

    /// How many pixels the welcome's picture is right of the middle of a welcome `width` pixels
    /// wide, `aside` of the way to the middle of its room beside the words. It eases in and out.
    private static int asideBy(int width, double aside) {
        double eased = aside * aside * (3 - 2 * aside);
        return (int) Math.round(eased * width * (12 - BESIDE_SPAN) / 24.0);
    }

    /// Lines of `text`, `size` units large in a welcome no wider than its reference width, that
    /// fade in with the welcome's words, wrapped to their width: centred below the picture, or
    /// left-aligned beside it once it has moved aside.
    private static UIForAnySwing<?, ?> fading(
        final Val<Theme> theme,
        final Val<String> text,
        final int size,
        final Function<Palette, Color> colour,
        final Val<Double> clock,
        final Val<Fit> fit,
        final Val<Double> aside
    ) {
        return
            box().withMinSize(0, 0)
            .withStyle(Themed.of(Viewable.of(Faded.class, text, clock, (words, at) -> Faded.of(words, at, fit.get(), aside.get())), theme), (themed, it) -> {
                var faded = themed.item();
                Palette p = themed.palette();
                return it.padding(2, 0, 2, 0)
                    .text(t ->
                        t.content(
                            StyledString.of(
                                f ->
                                    f.family(themed.fonts().FONT())
                                    .size((int) Math.round(size * faded.fit().larger()))
                                    .color(ViewPartsUtil.withAlpha(colour.apply(p), (int) Math.round(255 * faded.shown())))
                                    .horizontalAlignment(faded.fit().wide() ? UI.HorizontalAlignment.LEFT : UI.HorizontalAlignment.CENTER),
                                faded.words()
                            )
                        )
                        .placement(faded.fit().wide() ? UI.Placement.TOP_LEFT : UI.Placement.TOP)
                        .wrapLines(true)
                        .autoPreferredHeight(true)
                    );
            });
    }

    /// How the welcome fits its width: whether its words stand beside its picture, and how many
    /// times larger they are than in a welcome no wider than its reference width.
    private record Fit(boolean wide, double larger) {}

    /// Words of the welcome, how far they faded in, from 0 to 1, and how the welcome fits.
    private record Faded(String words, double shown, Fit fit) {

        /// `words` at `at` seconds of the welcome's play. Beside the picture, they also wait for
        /// it to have moved most of the way aside, however late the welcome grew wide.
        static Faded of(String words, double at, Fit fit, double aside) {
            double shown = fit.wide()
                ? Math.min((at - WORDS_BESIDE_FROM) / WORDS_BESIDE_TAKE, 2 * aside - 1)
                : (at - WORDS_FROM) / WORDS_TAKE;
            return new Faded(words, Math.max(0, Math.min(1, shown)), fit);
        }
    }

    /// Plays the welcome from its start, for as long as it is shown. Played again while it plays,
    /// it starts over on the same loop. Its picture moves aside, or back to the middle, as the
    /// welcome grows wide or narrow, at the same pace whenever it does.
    private static void play(
        final Var<Double> clock,
        final Var<WelcomeScene.Play> scene,
        final Val<Fit> fit,
        final Var<Double> aside,
        final Val<Boolean> welcome,
        final AtomicLong began,
        final AtomicBoolean playing
    ) {
        began.set(System.nanoTime());
        clock.set(0.0);
        aside.set(0.0);
        scene.set(WelcomeScene.Play.first(0));
        if (playing.getAndSet(true)) return;
        UI.animateFor(LifeTime.of(1, TimeUnit.SECONDS)).asLongAs(_ -> welcome.get()).go(new Animation() {
            @Override public void run(AnimationStatus status) {
                double now = (System.nanoTime() - began.get()) / 1e9;
                double step = Math.max(0, now - clock.get()) / ASIDE_TAKES;
                aside.set(
                    fit.get().wide() && now >= ASIDE_FROM
                        ? Math.min(1, aside.get() + step)
                        : Math.max(0, aside.get() - step)
                );
                clock.set(now);
            }
            @Override public void finish(AnimationStatus status) { playing.set(false); }
        });
    }

    /// A bar that fills from the left as `done` goes from 0 to 1.
    private static UIForAnySwing<?, ?> progress(Val<Theme> theme, Val<Double> done) {
        return
            box().withMinSize(0, 6)
            .withStyle(Themed.of(done, theme), (themed, it) -> {
                var part = themed.item();
                Palette p = themed.palette();
                int length = it.componentWidth();
                int thickness = it.componentHeight();
                return
                    it.backgroundColor(p.RAISED()).borderRadius(3)
                    .painter(UI.Layer.CONTENT, g -> {
                        g.setColor(p.FLAME());
                        float filled = (float) (length * Math.max(0, Math.min(1, part)));
                        g.fill(new RoundRectangle2D.Float(0, 0, filled, thickness, thickness, thickness));
                    });
            });
    }
}
