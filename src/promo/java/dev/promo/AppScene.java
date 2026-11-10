package dev.promo;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

import javax.swing.AbstractButton;

import dev.gui.model.GeniesState;
import dev.gui.model.Palette;
import dev.gui.pi.PiEvent;

/// A scene in the app: the Genies window as a camera sees it, under a caption, with a pointer
/// that presses what the scene has it press. The camera moves between the views a scene names;
/// parts of the window can be held apart from it, to fly in, land or stand alone before the
/// window is there. The window is one for all these scenes, as it is one for a user; each scene
/// begins by showing the state it starts from, so it can be filmed alone.
abstract class AppScene extends Scene {

    /// The window every scene in the app shows, made when the first of them begins.
    private static StagedWindow shared;

    /// The window's size in a scene that does not change it, in its own units.
    static final double WIDE = 1240, TALL = 700;
    /// The middle of the room the window has under the caption, in the film's pixels.
    static final double MIDDLE_X = Film.WIDTH / 2.0, MIDDLE_Y = 612;

    /// The desktop every awake genie shows, and what is on it.
    static final DesktopArt ART = new DesktopArt();
    private static StandInDesktop desktop;

    StagedWindow window;

    /// What is said above the window, from when until when.
    private record Caption(double from, double until, String headline, String line, Side side, double beat) {}
    private final List<Caption> captions = new ArrayList<>();

    /// A view the camera has reached by `at`, after gliding there for `glide` seconds.
    private record View(double at, double glide, Supplier<Camera> camera, boolean gently) {}
    private final List<View> views = new ArrayList<>();

    /// A part of the window that the scene holds apart from it at times.
    private record Part(Supplier<Optional<Component>> found, DoubleFunction<Pose> held) {}
    private final List<Part> parts = new ArrayList<>();

    /// Whether the genie's desktop shows the app she built from the scene's first picture.
    private boolean appIsOpen = false;

    /// The scene is after the one in which the app was built: it is open on the genie's desktop.
    void afterTheApp() { appIsOpen = true; }

    /// Whether the window fades in as the scene begins and out as it ends, or is there from
    /// the first picture to the last, for scenes that follow one another without a break.
    private boolean fadesIn = true, fadesOut = true;

    /// Where the pointer is, in the film's pixels, and the glide that takes it elsewhere.
    private Point2D.Double pointer = new Point2D.Double(1500, 1150), from = pointer;
    private Supplier<Optional<Point2D.Double>> to = Optional::empty;
    private double glideBegan = -1, glideEnds = -1, now = 0;
    /// When the pointer last pressed, for the ring around it.
    private double pressed = -10;
    private boolean pointerShown = true;

    /// A scene with one caption, said all through it.
    AppScene(String name, double seconds, String headline, String line) {
        super(name, seconds);
        says(0.15, seconds, headline, line);
    }

    /// A scene that says what it says with [#says].
    AppScene(String name, double seconds) { super(name, seconds); }

    /// The state the scene starts from.
    abstract GeniesState start();

    @Override void begin() {
        GeniesState start = start();
        if (shared == null) shared = new StagedWindow(start);
        window = shared;
        ART.reset();
        try {
            if (desktop == null) desktop = new StandInDesktop(ART);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException(failed);
        }
        window.app.desktop = Optional.of(desktop.desktop());
        if (appIsOpen) ART.appIsOpen();
        window.show(start);
        window.size(WIDE, TALL);
        for (Fading fading : fadings) fading.kept = null;
        pointer = from = new Point2D.Double(1500, 1150);
        to = Optional::empty;
        glideBegan = glideEnds = -1;
        pressed = -10;
    }

    @Override void paint(Graphics2D g, double t) {
        now = t;
        window.layOut();
        Sand.paint(g, FilmClock.seconds(), lit(t), drawnInto(), drawnIn(t));
        below(g, t);
        Camera camera = camera(t);
        List<StagedWindow.Layer> layers = new ArrayList<>();
        for (Part part : parts) {
            Pose pose = part.held().apply(t);
            // A part in its place is the window's to paint, unless the window is not all there.
            if (pose.landed() && windowShown(t) >= 1) continue;
            part.found().get().ifPresent(found -> layers.add(new StagedWindow.Layer(found, pose)));
        }
        // Seen at an angle, the window and what is on it are painted flat first, then turned.
        Lens.Tilt tilt = tilt(t);
        BufferedImage plane = tilt.flat() ? null : Lens.plane();
        Graphics2D on = g;
        if (plane != null) {
            on = plane.createGraphics();
            on.setRenderingHints(g.getRenderingHints());
        }
        window.paint(on, camera, windowShown(t), layers);
        for (Fading fading : fadings)
            if (fading.kept != null) window.paint(on, camera, fading.kept, fading.pose(window, t));
        highlights(on, t, camera);
        above(on, t);
        if (plane != null) {
            on.dispose();
            Lens.seen(g, plane, tilt, MIDDLE_X, MIDDLE_Y);
        }
        dims(g, t);
        captions(g, t);
        pointer(g, t, camera);
    }

    // ─── the lens ──────────────────────────────────────────────────────────────────────────

    /// How the window is seen `t` seconds in: straight on, unless a scene says otherwise. The
    /// pointer is where it would be straight on, so a scene tilts only while nothing is pressed.
    Lens.Tilt tilt(double t) { return Lens.Tilt.NONE; }

    // ─── what is pointed out ───────────────────────────────────────────────────────────────

    /// A place in the window that is pointed out for a while.
    private record Highlight(double from, double until, Supplier<Optional<Rectangle2D>> place) {}
    private final List<Highlight> highlights = new ArrayList<>();

    /// Points out what `place` finds in the window from `from` until `until` seconds in: a
    /// border of the flame's colour around it, and a light that runs around the border.
    void pointsOut(double from, double until, Supplier<Optional<Rectangle2D>> place) { highlights.add(new Highlight(from, until, place)); }

    private void highlights(Graphics2D g, double t, Camera camera) {
        for (Highlight highlight : highlights) {
            double shown = Draw.shown(t, highlight.from(), highlight.until(), 0.35);
            if (shown <= 0) continue;
            highlight.place().get().ifPresent(place -> {
                Point2D.Double corner = camera.film(place.getX(), place.getY());
                double w = place.getWidth() * camera.scale(), h = place.getHeight() * camera.scale(), grow = 6 * (1 - Draw.arrive(t, highlight.from(), highlight.from() + 0.5)) + 4;
                Effects.border(g, new java.awt.geom.RoundRectangle2D.Double(corner.x - grow, corner.y - grow, w + 2 * grow, h + 2 * grow, 22, 22), t - highlight.from(), shown);
            });
        }
    }

    // ─── what fades where it was ───────────────────────────────────────────────────────────

    private static final class Fading {
        final double at, seconds;
        StagedWindow.Kept kept;
        /// What the part was in, and where that was then: the part fades where it was in it,
        /// should it move, as a bar that is lower once the button on it is gone.
        Component in;
        Rectangle2D inWas;
        Fading(double at, double seconds) { this.at = at; this.seconds = seconds; }

        /// The part `t` seconds in: it glides with what it was in, as that moves, and fades.
        Pose pose(StagedWindow window, double t) {
            double alpha = 1 - Draw.ease(t, at, at + seconds);
            if (in == null || in.getParent() == null || !in.isVisible()) return new Pose(0, 0, 1, 0, alpha);
            Rectangle2D now = window.unitsOf(in);
            double along = Draw.ease(t, at, at + seconds * 0.8);
            return new Pose((now.getCenterX() - inWas.getCenterX()) * along, (now.getCenterY() - inWas.getCenterY()) * along, 1, 0, alpha);
        }
    }
    private final List<Fading> fadings = new ArrayList<>();

    /// Keeps how what `part` finds looks `at` seconds in, and lets that fade where it was over
    /// `seconds`, whatever becomes of the part itself: a button that is gone once it was pressed.
    /// Where it was is where it was in what it was in, which may move meanwhile.
    void fades(double at, double seconds, Supplier<Optional<Component>> part) {
        Fading fading = new Fading(at, seconds);
        fadings.add(fading);
        at(at, () -> {
            Optional<Component> found = part.get();
            fading.kept = found.map(window::keep).orElse(null);
            fading.in = found.map(Component::getParent).orElse(null);
            fading.inWas = fading.in == null ? null : window.unitsOf(fading.in);
        });
    }

    /// How light the ground behind the window is `t` seconds in: the night (0), or dusk with
    /// sand swirling in it (1), as it is while the app is shown.
    double lit(double t) { return 1; }

    /// How far the ground, light and sand, is drawn into the point [#drawnInto] `t` seconds in:
    /// from not at all (0) to all of it, and the night again (1). As the app's scenes end, it goes
    /// with the window into a point of light; as they begin, it grows out of the window.
    double drawnIn(double t) { return 0; }

    /// The film's point the ground is drawn into, or grows out of.
    java.awt.geom.Point2D drawnInto() { return Effects.WISHES; }

    /// What a scene paints under the window, and above it but under its caption and the pointer.
    void below(Graphics2D g, double t) {}
    void above(Graphics2D g, double t) {}

    /// The night the whole film is set in, whatever colours the window wears.
    static void backdrop(Graphics2D g) {
        g.setColor(Draw.mix(Palette.DARK.NIGHT(), Color.BLACK, 0.35));
        g.fillRect(0, 0, Film.WIDTH, Film.HEIGHT);
    }

    // ─── how scenes follow one another ─────────────────────────────────────────────────────

    /// The window is there from the scene's first picture: the scene before left it so.
    void cutsIn() { fadesIn = false; }

    /// The window stays to the scene's last picture: the scene after goes on from it.
    void cutsOut() { fadesOut = false; }

    /// How visible the window itself is `t` seconds in. A scene that assembles the window from
    /// its parts says otherwise.
    double windowShown(double t) {
        return Math.min(fadesIn ? Draw.ease(t, 0, 0.45) : 1, fadesOut ? 1 - Draw.ease(t, seconds - 0.45, seconds) : 1);
    }

    // ─── the caption ───────────────────────────────────────────────────────────────────────

    /// Where a caption rests while the app has the viewer's eye: above the window or below it,
    /// or tucked into a corner of the picture, the one where what is shown matters least. The
    /// camera then shows the app in the room the caption leaves it.
    enum Side { TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        boolean corner() { return this != TOP && this != BOTTOM; }
        boolean left() { return this == TOP_LEFT || this == BOTTOM_LEFT; }
        boolean top() { return this == TOP || this == TOP_LEFT || this == TOP_RIGHT; }
    }

    /// The room a near view of the window has in the film beside a caption at each side. A
    /// caption in a corner leaves nearly all of the picture, a little less on its side.
    static Rectangle2D roomBeside(Side side) {
        return switch (side) {
            case TOP -> ROOM;
            case BOTTOM -> new Rectangle2D.Double(140, 70, Film.WIDTH - 280, 800);
            case TOP_LEFT, BOTTOM_LEFT -> new Rectangle2D.Double(440, 70, 1400, 940);
            case TOP_RIGHT, BOTTOM_RIGHT -> new Rectangle2D.Double(80, 70, 1400, 940);
        };
    }

    /// Says `headline`, and `line` under it, above the window from `from` until `until` seconds
    /// in: word by word as it comes, all at once as it goes.
    void says(double from, double until, String headline, String line) { says(from, until, headline, line, Side.TOP, 0); }

    /// Says `headline`, and `line` under it, from `from` until `until` seconds in. For `beat`
    /// seconds the words have the picture to themselves: large, in its middle, word by word,
    /// over a window gone dim. Then they glide to rest at `side`, smaller, the window lights
    /// up again, and the viewer's eye is the app's. With no beat, they come at rest.
    void says(double from, double until, String headline, String line, Side side, double beat) {
        captions.add(new Caption(from, until, headline, line, side, beat));
    }

    /// How far a caption is from its beat to its rest, `t` seconds in: 0 while the words have
    /// the picture, 1 once they rest.
    private static double rested(Caption caption, double t) {
        return caption.beat() <= 0 ? 1 : Draw.ease(t, caption.from() + caption.beat(), caption.from() + caption.beat() + 0.75);
    }

    /// The window goes dim under words that have the picture to themselves.
    private void dims(Graphics2D g, double t) {
        double dim = 0;
        for (Caption caption : captions)
            if (t >= caption.from() - 0.4 && t <= caption.until())
                dim = Math.max(dim, Draw.ease(t, caption.from() - 0.4, caption.from() + 0.1) * (1 - rested(caption, t)));
        if (dim <= 0) return;
        g.setColor(Draw.alpha(NIGHT, 0.5 * dim));
        g.fillRect(0, 0, Film.WIDTH, Film.HEIGHT);
    }

    private static final Color NIGHT = Draw.mix(Palette.DARK.NIGHT(), Color.BLACK, 0.35);

    /// How far from the picture's edges a caption's card rests, and how far its words are in
    /// from the card's edges, at rest and while they have the picture.
    private static final double MARGIN = 36, PAD = 26, BEAT_PAD = 42;

    /// Each caption, as it is at the moment: its rows of words, each where it is and how large,
    /// and the card under them, made from those very rows, so that the words never leave it.
    private void captions(Graphics2D g, double t) {
        for (Caption caption : captions) {
            if (t < caption.from() || t > caption.until()) continue;
            double rested = rested(caption, t), there = Draw.ease(t, caption.from(), caption.from() + 0.5) * (1 - Draw.ease(t, caption.until() - 0.45, caption.until()));
            Side side = caption.side();
            boolean corner = side.corner();
            double restHead = corner ? 44 : 50, restLine = corner ? 25 : 27, beatHead = 88, beatLine = 36;
            List<String> heads = corner ? Letters.wrapped(caption.headline(), Draw.font(restHead, true, -0.01), 470) : List.of(caption.headline());
            List<String> lines = corner ? Letters.wrapped(caption.line(), Draw.font(restLine, false), 470) : List.of(caption.line());
            // Large, no line is wider than the picture has room for.
            double widest = 1;
            for (String head : heads) widest = Math.max(widest, Letters.width(head, Draw.font(beatHead, true, -0.01)));
            for (String line : lines) widest = Math.max(widest, Letters.width(line, Draw.font(beatLine, false)));
            double fits = Math.min(1, 1640 / widest);
            beatHead *= fits;
            beatLine *= fits;

            // The rows, with their baselines from the first one's, large and at rest.
            int count = heads.size() + lines.size();
            String[] texts = new String[count];
            boolean[] bold = new boolean[count];
            double[] beatSize = new double[count], restSize = new double[count], beatDown = new double[count], restDown = new double[count];
            double beatAt = 0, restAt = 0;
            for (int k = 0; k < count; k++) {
                boolean head = k < heads.size();
                if (k == heads.size()) { beatAt += 26 + beatLine * 0.3 - beatHead * 0.12; restAt += corner ? 14 + restLine * 0.3 - restHead * 0.12 : 48 - restHead * 1.12; }
                texts[k] = head ? heads.get(k) : lines.get(k - heads.size());
                bold[k] = head;
                beatSize[k] = head ? beatHead : beatLine;
                restSize[k] = head ? restHead : restLine;
                beatDown[k] = beatAt;
                restDown[k] = restAt;
                beatAt += head ? beatHead * 1.12 : beatLine * 1.3;
                restAt += head ? restHead * 1.12 : restLine * 1.32;
            }
            double beatTop = beatHead * 0.95, beatTall = beatTop + beatDown[count - 1] + beatLine * 0.3;
            double restTop = restHead * 0.95, restTall = restTop + restDown[count - 1] + restLine * 0.3;
            double restWide = 0;
            for (int k = 0; k < count; k++) restWide = Math.max(restWide, Letters.width(texts[k], font(restSize[k], bold[k])));
            // Large, in the middle of the picture; at rest, in its place.
            double beatFirst = Film.HEIGHT / 2.0 - beatTall / 2 + beatTop;
            // Above the window, a little higher than in a corner, where the window's top is not near.
            double restFirst = side == Side.TOP ? 82 : side.top() ? MARGIN + PAD * 0.8 + restTop : Film.HEIGHT - MARGIN - PAD * 0.8 - restTall + restTop;
            double restLeft = side.left() ? MARGIN + PAD : Film.WIDTH - MARGIN - PAD - restWide;

            double[] x = new double[count], y = new double[count], size = new double[count];
            double left = Double.MAX_VALUE, right = -Double.MAX_VALUE, top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
            for (int k = 0; k < count; k++) {
                size[k] = Draw.mix(beatSize[k], restSize[k], rested);
                double wide = Letters.width(texts[k], font(size[k], bold[k]));
                double beatX = MIDDLE_X - Letters.width(texts[k], font(beatSize[k], bold[k])) / 2;
                double restX = corner ? restLeft : MIDDLE_X - Letters.width(texts[k], font(restSize[k], bold[k])) / 2;
                x[k] = Draw.mix(beatX, restX, rested);
                y[k] = Draw.mix(beatFirst + beatDown[k], restFirst + restDown[k], rested);
                left = Math.min(left, x[k]);
                right = Math.max(right, x[k] + wide);
                top = Math.min(top, y[k] - size[k] * 0.95);
                bottom = Math.max(bottom, y[k] + size[k] * 0.3);
            }
            // A card under the words, raised a little from what is behind it.
            double pad = Draw.mix(BEAT_PAD, PAD, rested);
            card(g, new java.awt.geom.RoundRectangle2D.Double(left - pad, top - pad * 0.8, right - left + 2 * pad, bottom - top + 1.6 * pad, 24, 24), there);

            int word = 0;
            double headsDone = caption.from();
            for (int k = 0; k < count; k++) {
                if (k == heads.size()) {
                    headsDone = caption.from() + Math.max(0, word - 1) * 0.11 + 0.55;
                    word = 0;
                }
                double apart = bold[k] ? 0.11 : 0.055, begins = bold[k] ? caption.from() : headsDone - 0.2;
                Letters letters = Letters.from(texts[k], font(size[k], bold[k]), x[k], y[k]);
                Letters.Holding holding = letters.wordByWord(t, begins + word * apart, apart, caption.until());
                shadow(g, letters, holding, size[k]);
                letters.paint(g, bold[k] ? Palette.DARK.TEXT() : Draw.mix(Palette.DARK.SUBTEXT(), Palette.DARK.TEXT(), 0.25 * (1 - rested)), holding);
                word += letters.words();
            }
        }
    }

    /// A soft shadow under words `size` pixels large, a little below them, so that they stand
    /// off the card: the words, dark and faint, a few times, each a little elsewhere.
    private static void shadow(Graphics2D g, Letters letters, Letters.Holding holding, double size) {
        double down = Math.max(1.2, size / 30), spread = Math.max(0.8, size / 45);
        double[][] offsets = {{0, down * 1.8, 0.22}, {-spread, down * 1.2, 0.18}, {spread, down * 1.2, 0.18}, {0, down * 0.7, 0.3}};
        for (double[] offset : offsets)
            letters.paint(g, Color.BLACK, letter -> {
                Letters.Held held = holding.of(letter);
                return new Letters.Held(held.dx() + offset[0], held.dy() + offset[1], held.scale(), held.turn(), held.alpha() * offset[2]);
            });
    }

    private static java.awt.Font font(double size, boolean bold) { return bold ? Draw.font(size, true, -0.01) : Draw.font(size, false); }

    /// The card under a caption, `shown` as much as that is: not black but of the window's
    /// raised colour, with a fine edge, and a soft shadow under it.
    static void card(Graphics2D g, java.awt.geom.RoundRectangle2D card, double shown) {
        if (shown <= 0) return;
        for (int i = 14; i >= 1; i--) {
            double near = 1 - i / 14.0;
            g.setColor(Draw.alpha(Color.BLACK, 0.035 * near * near * shown));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(card.getX() - i, card.getY() - i + 6, card.getWidth() + 2 * i, card.getHeight() + 2 * i,
                card.getArcWidth() + 2 * i, card.getArcHeight() + 2 * i));
        }
        g.setColor(Draw.alpha(Draw.mix(Palette.DARK.RAISED(), Palette.DARK.CARD(), 0.4), shown));
        g.fill(card);
        g.setColor(Draw.alpha(Draw.mix(Palette.DARK.BORDER(), Palette.DARK.SUBTEXT(), 0.25), 0.9 * shown));
        g.setStroke(new java.awt.BasicStroke(1.5f));
        g.draw(card);
    }

    // ─── the camera ────────────────────────────────────────────────────────────────────────

    /// The whole window, in the middle of the picture, or under a caption above it: where the
    /// camera is unless a scene says otherwise.
    Camera whole() {
        return new Camera(window.width() / 2, window.height() / 2, Camera.PLAIN).with(MIDDLE_X, Draw.mix(Film.HEIGHT / 2.0, MIDDLE_Y, roomAbove(now)));
    }

    /// How much room the whole window leaves above it `t` seconds in, from none, when it is in
    /// the middle of the picture, to all a caption above it needs: it makes room shortly before
    /// one comes, and takes it back after it went.
    private double roomAbove(double t) {
        double room = 0;
        for (Caption caption : captions)
            if (caption.side() == Side.TOP)
                room = Math.max(room, Draw.ease(t, caption.from() - 1.1, caption.from() + 0.2) * (1 - Draw.ease(t, caption.until(), caption.until() + 1.2)));
        return room;
    }

    /// The room a view of part of the window has in the film: under the caption, with a margin.
    static final Rectangle2D ROOM = new Rectangle2D.Double(140, 200, Film.WIDTH - 280, Film.HEIGHT - 260);

    /// A view of what `place` finds in the window, with `around` units of the window around it,
    /// as large as the room under the caption allows but no nearer than `nearest` pixels a unit.
    /// The whole window while `place` finds nothing.
    Supplier<Camera> near(Supplier<Optional<Rectangle2D>> place, double around, double nearest) { return near(place, around, nearest, ROOM); }

    /// The same, in `room` of the film: beside a caption that rests at a side, see [#roomBeside].
    Supplier<Camera> near(Supplier<Optional<Rectangle2D>> place, double around, double nearest, Rectangle2D room) {
        return () -> place.get().map(found -> {
            Rectangle2D with = new Rectangle2D.Double(found.getX() - around, found.getY() - around, found.getWidth() + 2 * around, found.getHeight() + 2 * around);
            Camera framed = Camera.framing(with, room);
            return framed.scale() > nearest ? new Camera(with.getCenterX(), with.getCenterY(), nearest).with(room.getCenterX(), room.getCenterY()) : framed;
        }).orElseGet(this::whole);
    }

    /// Has the camera glide to `view`, arriving `at` seconds in after `glide` seconds on the way.
    /// The view is looked up anew for each picture, so it follows a window that changes.
    void looks(double at, double glide, Supplier<Camera> view) { views.add(new View(at, glide, view, false)); }

    /// The same, unhurried: the camera sets out as gently as it arrives, for a view that is to
    /// come into the picture bit by bit.
    void looksGently(double at, double glide, Supplier<Camera> view) { views.add(new View(at, glide, view, true)); }

    /// Where the camera is `t` seconds in.
    Camera camera(double t) {
        Camera camera = whole();
        for (View view : views) {
            if (t < view.at() - view.glide()) break;
            double from = view.at() - view.glide();
            camera = camera.toward(view.camera().get(), view.glide() <= 0 ? 1 : view.gently() ? Draw.ease(t, from, view.at()) : Draw.swift(t, from, view.at()));
        }
        return camera;
    }

    // ─── parts of the window ───────────────────────────────────────────────────────────────

    /// Holds the part of the window that `found` finds as `held` says for each moment of the
    /// scene: away, on its way, or landed in its place. See [Pose].
    void holds(Supplier<Optional<Component>> found, DoubleFunction<Pose> held) { parts.add(new Part(found, held)); }

    // ─── the pointer ───────────────────────────────────────────────────────────────────────

    /// Has the pointer glide to the middle of what `target` finds in the window, arriving `at`
    /// seconds in, after `glide` seconds on the way. It stays on it as the camera moves.
    void point(double at, double glide, Supplier<Optional<Rectangle2D>> target) {
        at(at - glide, () -> {
            from = pointerAt(now, camera(now));
            to = () -> target.get().map(found -> new Point2D.Double(found.getCenterX(), found.getCenterY()));
            glideBegan = at - glide;
            glideEnds = at;
        });
    }

    /// Has the pointer glide to the button that says `text` and press it `at` seconds in.
    void press(double at, String text) {
        Supplier<Optional<AbstractButton>> button = () -> window.buttonSaying(text);
        point(at - 0.12, 0.7, () -> button.get().map(window::unitsOf));
        at(at - 0.12, () -> button.get().ifPresent(it -> window.hover(it, true)));
        at(at, () -> { pressed = at; button.get().ifPresent(it -> window.hold(it, true)); });
        at(at + 0.13, () -> button.get().ifPresent(it -> { window.click(it); window.hover(it, false); }));
    }

    /// Has the pointer glide to the card of the genie called `name`, in the list of genies, and
    /// press it `at` seconds in; `then` is what the window does about it.
    void pressCard(double at, String name, Runnable then) {
        point(at, 0.7, () -> window.component(it -> it instanceof javax.swing.JLabel label && name.equals(label.getText())
            && it.getClass().getSimpleName().length() > 0 && window.unitsOf(it).getX() < 250).map(window::unitsOf)
            .map(it -> new Rectangle2D.Double(it.getX(), it.getY() + 4, 120, it.getHeight())));
        at(at, () -> pressed = at);
        at(at + 0.13, then);
    }

    /// Takes the pointer out of the picture from `at` seconds in.
    void pointerLeaves(double at) {
        at(at, () -> {
            from = pointerAt(now, camera(now));
            Point2D.Double out = new Point2D.Double(from.x + 260, Film.HEIGHT + 60);
            to = Optional::empty;
            leaving = out;
            glideBegan = at;
            glideEnds = at + 0.9;
        });
    }

    /// Where the pointer goes when it leaves the picture, in the film's pixels.
    private Point2D.Double leaving = new Point2D.Double(1500, 1150);

    void pointerShown(boolean shown) { pointerShown = shown; }

    private Point2D.Double pointerAt(double t, Camera camera) {
        if (glideEnds < 0) return pointer;
        Point2D.Double target = to.get().map(units -> camera.film(units.x, units.y)).orElse(leaving);
        double along = Draw.ease(t, glideBegan, glideEnds);
        // A slight bow, as a hand moves a mouse.
        double bow = Math.sin(along * Math.PI) * 0.06 * from.distance(target);
        double dx = target.x - from.x, dy = target.y - from.y, length = Math.max(1, Math.hypot(dx, dy));
        return new Point2D.Double(Draw.mix(from.x, target.x, along) - dy / length * bow, Draw.mix(from.y, target.y, along) + dx / length * bow);
    }

    private void pointer(Graphics2D g, double t, Camera camera) {
        if (!pointerShown) return;
        pointer = pointerAt(t, camera);
        double ring = (t - pressed) / 0.45;
        if (ring >= 0 && ring < 1) {
            g.setColor(Draw.alpha(Palette.DARK.FLAME(), 0.55 * (1 - ring)));
            double r = 10 + 30 * Draw.arrive(ring, 0, 1);
            g.setStroke(new java.awt.BasicStroke(3f));
            g.draw(new Ellipse2D.Double(pointer.x - r, pointer.y - r, 2 * r, 2 * r));
        }
        double s = 1.7 * (t - pressed < 0.13 && t >= pressed ? 0.88 : 1);
        Path2D arrow = new Path2D.Double();
        double[][] shape = {{0, 0}, {0, 17}, {4.2, 13.2}, {7.2, 20}, {9.8, 18.9}, {6.9, 12.2}, {12.2, 12.2}};
        arrow.moveTo(pointer.x, pointer.y);
        for (int i = 1; i < shape.length; i++) arrow.lineTo(pointer.x + shape[i][0] * s, pointer.y + shape[i][1] * s);
        arrow.closePath();
        g.setColor(new Color(0, 0, 0, 70));
        g.translate(1.5, 2.5);
        g.fill(arrow);
        g.translate(-1.5, -2.5);
        g.setColor(new Color(0xfb, 0xf6, 0xec));
        g.fill(arrow);
        g.setColor(new Color(0x15, 0x12, 0x1c));
        g.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.draw(arrow);
    }

    // ─── what the user and the genie do ────────────────────────────────────────────────────

    /// The user writes `text` into the message box from `at` seconds in, `pace` characters a
    /// second, a little unevenly, as hands do. Returns when the last character is written.
    double write(double at, String text, double pace) {
        double when = at;
        for (int i = 1; i <= text.length(); i++) {
            String sofar = text.substring(0, i);
            at(when, () -> window.genie(it -> it.withDraft(sofar)));
            char written = text.charAt(i - 1);
            double pause = (written == ' ' ? 1.5 : written == ',' || written == '.' ? 3.2 : 1) * (0.75 + 0.5 * ((i * 7919) % 13) / 12.0);
            when += pause / pace;
        }
        return when;
    }

    /// The genie writes `text` as part of its answer from `at` seconds in, `pace` characters a
    /// second, a few at a time, as a model's answer arrives. Returns when it is written.
    double says(double at, String text, double pace) {
        double when = at;
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + 2 + (i * 31) % 4);
            String piece = text.substring(i, end);
            at(when, () -> hears(new PiEvent.Said(piece)));
            when += piece.length() / pace;
            i = end;
        }
        return when;
    }

    /// The genie uses `tool` for `seconds` from `at` seconds in; `summary` is what it does, in a
    /// line, and `printed` what the tool printed. Returns when it is done.
    double uses(double at, String tool, String summary, double seconds, String printed) {
        String call = tool + "@" + at;
        at(at, () -> hears(new PiEvent.ToolStarted(call, tool, summary)));
        at(at + seconds, () -> hears(new PiEvent.ToolFinished(call, false, printed)));
        return at + seconds;
    }

    void hears(PiEvent event) { window.genie(it -> it.hear(event)); }

    /// Brings every page of the window back to its top, as a page is when the user comes to it.
    void toTheTop() {
        for (javax.swing.JScrollPane pane : window.all(javax.swing.JScrollPane.class)) pane.getVerticalScrollBar().setValue(0);
    }
}
