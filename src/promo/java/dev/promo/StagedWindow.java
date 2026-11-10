package dev.promo;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;

import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.view.GeniesView;
import sprouts.Var;
import swingtree.components.JGlassPane;

/// The Genies window, as [dev.gui.Main] opens it, but never shown on this computer's screen: it
/// is laid out at whatever size a scene gives it, and painted into the film. Behind it is
/// [StagedGenies], so what it shows is what the scenes put into its state.
final class StagedWindow {

    /// How many of its own pixels one unit of the window is: the app's own scale, as on a screen
    /// of fine pixels. At 2, the camera can come well nearer than a plain view of the window
    /// before anything is painted larger than it was laid out. Other scales were tried, and the
    /// window laid itself out differently at them: its title bar taller, its pages narrower.
    static final double UI = 2;

    /// How round the window's corners are in the film, in its own units.
    private static final double CORNER = 12;

    final StagedGenies app;
    private final JFrame frame;
    /// The window as painted last, kept so that no picture is made anew for each one filmed.
    private BufferedImage painted = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB_PRE);

    StagedWindow(GeniesState state) {
        app = new StagedGenies(state);
        // The app's own scale, as on a screen of finer pixels. Stretching what it paints instead
        // would blur it, and painting it stretched by a fraction takes half a second a picture.
        System.setProperty("flatlaf.uiScale", String.valueOf(UI));
        swingtree.SwingTree.get().setUiScaleFactor((float) UI);
        GeniesView.setUpLook(state);
        ownFont();
        frame = new JFrame("Genies");
        new JGlassPane(frame.getRootPane());
        GeniesView view = new GeniesView(app);
        frame.setContentPane(view);
        frame.setJMenuBar(view.titleBar(Var.of("Genies")));
        frame.getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        frame.getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        // Packed, it lays out and paints as a window on screen does; it is never made visible.
        frame.pack();
    }

    /// Has what the window leaves to the look, such as a plain label, written in the app's own
    /// typeface. The look takes that from the desktop Genies runs on, and the film should not
    /// look like the desktop of whoever makes it. To be done again after the look was set up
    /// anew, as when the colours change.
    static void ownFont() {
        // From the style context, whose fonts fall back to others for signs theirs lack, such as ✦.
        java.awt.Font own = javax.swing.text.StyleContext.getDefaultStyleContext().getFont(dev.gui.model.Fonts.DEFAULT.FONT(), java.awt.Font.PLAIN, (int) Math.round(13 * UI));
        javax.swing.UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(own));
    }

    GeniesState state() { return app.state().get(); }

    /// The first component that `is` so, from the top.
    Optional<Component> component(Predicate<Component> is) { return find(frame.getRootPane(), is); }

    /// Shows `state`, as when a scene begins.
    void show(GeniesState state) { app.state().set(state); }

    void change(UnaryOperator<GeniesState> change) { app.state().update(change); }

    /// Changes the genie the window shows.
    void genie(UnaryOperator<Genie> change) { app.state().update(it -> it.withGenie(change.apply(it.genie()))); }

    /// The window's size, in its own units.
    double width() { return frame.getWidth() / UI; }
    double height() { return frame.getHeight() / UI; }

    /// Gives the window a size, in its own units.
    void size(double width, double height) {
        int w = (int) Math.round(width * UI), h = (int) Math.round(height * UI);
        if (frame.getWidth() == w && frame.getHeight() == h) return;
        frame.setSize(w, h);
        layOut();
    }

    /// Lays the window out until it is laid out. Swing lays out a window again by itself only
    /// while it is shown, which this one never is; and a text that wraps learns how wide it is
    /// as it is laid out, and only then how tall it wants to be. Laid out once, a message box
    /// that has just come is as tall as its text unwrapped, for a picture, and all above it is
    /// pushed aside.
    ///
    /// What is new in the window also gets its style, and so its padding and border, only as it
    /// is painted the first time. So whenever the window was laid out anew, it is painted once,
    /// out of sight, and laid out again, until that changes nothing.
    void layOut() {
        long before = laidOut(frame.getRootPane());
        frame.validate();
        for (int pass = 0; pass < 4; pass++) {
            long now = laidOut(frame.getRootPane());
            if (now == before) return;
            before = now;
            paintOutOfSight();
            invalidateAll(frame.getRootPane());
            frame.validate();
        }
    }

    /// Paints the window where nobody sees it, for what painting it does besides.
    private void paintOutOfSight() {
        if (rehearsal.getWidth() < frame.getWidth() || rehearsal.getHeight() < frame.getHeight())
            rehearsal = new BufferedImage(Math.max(1, frame.getWidth()), Math.max(1, frame.getHeight()), BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D p = rehearsal.createGraphics();
        hints(p);
        frame.getRootPane().paint(p);
        p.dispose();
    }

    private BufferedImage rehearsal = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB_PRE);

    /// Where everything in the window is, and whether it shows, in a number.
    private static long laidOut(Component from) {
        long sum = from.isVisible() ? 1 : 2;
        sum = sum * 31 + from.getX();
        sum = sum * 31 + from.getY();
        sum = sum * 31 + from.getWidth();
        sum = sum * 31 + from.getHeight();
        if (from instanceof Container container)
            for (Component child : container.getComponents()) sum = sum * 17 + laidOut(child);
        return sum;
    }

    private static void invalidateAll(Component from) {
        from.invalidate();
        if (from instanceof Container container)
            for (Component child : container.getComponents()) invalidateAll(child);
    }

    // ─── painting it into the film ─────────────────────────────────────────────────────────

    /// What of the window a picture of the film shows beside the window itself: parts of it
    /// held away from their places, each painted as a layer of its own.
    ///
    /// @param part the component, found in the window as it is now
    /// @param pose how it is held
    record Layer(Component part, Pose pose) {}

    /// Paints the window as `camera` sees it. The window itself, with round corners and a
    /// shadow under it, is `alpha` visible, and painted without the parts in `layers`; those are
    /// painted above it, each as its pose says, where the camera sees its place. With the
    /// window not visible at all, only the layers are there: parts of a window yet to come.
    ///
    /// Everything is painted as fine as the camera needs: at the window's own pixels, or at a
    /// whole multiple of them when the camera is nearer than that, and then brought to the
    /// film's size. Painting it stretched by a fraction instead takes half a second a picture.
    void paint(Graphics2D g, Camera camera, double alpha, List<Layer> layers) {
        layOut();
        Set<Component> apart = new HashSet<>();
        for (Layer layer : layers) apart.add(layer.part());
        if (alpha > 0) ground(g, camera, alpha, apart);
        for (Layer layer : layers) if (layer.pose().alpha() > 0) layer(g, camera, layer, apart);
    }

    private void ground(Graphics2D g, Camera camera, double alpha, Set<Component> apart) {
        Rectangle2D seen = camera.seen().createIntersection(new Rectangle2D.Double(0, 0, width(), height()));
        if (seen.isEmpty()) return;
        int fine = fineness(camera.scale());
        double perUnit = UI * fine;
        // Whole pixels of the window, so that what is painted is where the window has it.
        int left = (int) Math.floor(seen.getMinX() * UI), top = (int) Math.floor(seen.getMinY() * UI);
        int right = Math.min(frame.getWidth(), (int) Math.ceil(seen.getMaxX() * UI)), bottom = Math.min(frame.getHeight(), (int) Math.ceil(seen.getMaxY() * UI));
        int w = (right - left) * fine, h = (bottom - top) * fine;
        if (w <= 0 || h <= 0) return;
        if (painted.getWidth() < w || painted.getHeight() < h)
            painted = new BufferedImage(w + 64, h + 64, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D p = painted.createGraphics();
        p.setComposite(AlphaComposite.Clear);
        p.fillRect(0, 0, w, h);
        p.setComposite(AlphaComposite.SrcOver);
        hints(p);
        p.setClip(0, 0, w, h);
        p.scale(fine, fine);
        p.translate(-left, -top);
        without(apart, () -> frame.getRootPane().paint(p));
        // The corners are cut round afterwards: a clip would leave them jagged.
        p.setClip(null);
        p.setComposite(AlphaComposite.DstOut);
        double r = CORNER * UI;
        Area outside = new Area(new Rectangle2D.Double(left - 1, top - 1, right - left + 2, bottom - top + 2));
        outside.subtract(new Area(new RoundRectangle2D.Double(0, 0, frame.getWidth(), frame.getHeight(), 2 * r, 2 * r)));
        p.setColor(Color.BLACK);
        p.fill(outside);
        p.dispose();

        var before = Draw.fade(g, alpha);
        Point2D corner = camera.film(0, 0);
        double filmW = width() * camera.scale(), filmH = height() * camera.scale();
        shadow(g, corner.getX(), corner.getY() + 5 * camera.scale(), filmW, filmH, camera.scale() / Camera.PLAIN);
        Point2D at = camera.film(left / UI, top / UI);
        AffineTransform place = new AffineTransform();
        place.translate(at.getX(), at.getY());
        place.scale(camera.scale() / perUnit, camera.scale() / perUnit);
        Object smoothing = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(painted.getSubimage(0, 0, w, h), place, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smoothing == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : smoothing);
        g.setColor(new Color(255, 255, 255, 30));
        g.setStroke(new java.awt.BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(corner.getX() + 0.5, corner.getY() + 0.5, filmW - 1, filmH - 1, 2 * CORNER * camera.scale(), 2 * CORNER * camera.scale()));
        g.setComposite(before);
    }

    private void layer(Graphics2D g, Camera camera, Layer layer, Set<Component> apart) {
        Component part = layer.part();
        Pose pose = layer.pose();
        if (part.getWidth() <= 0 || part.getHeight() <= 0) return;
        int fine = fineness(camera.scale() * pose.scale());
        // No larger than a picture can be: a part as large as the window, seen from very near.
        while (fine > 1 && (long) part.getWidth() * fine * part.getHeight() * fine > 60_000_000L) fine--;
        BufferedImage image = new BufferedImage(part.getWidth() * fine, part.getHeight() * fine, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D p = image.createGraphics();
        hints(p);
        p.scale(fine, fine);
        // Without the other parts that are held apart and are inside this one.
        Set<Component> inside = new HashSet<>();
        for (Component other : apart) if (other != part && SwingUtilities.isDescendingFrom(other, part)) inside.add(other);
        without(inside, () -> part.paint(p));
        p.dispose();

        Rectangle2D place = unitsOf(part);
        Point2D middle = camera.film(place.getCenterX() + pose.dx(), place.getCenterY() + pose.dy());
        double scale = camera.scale() * pose.scale() / (UI * fine);
        AffineTransform held = new AffineTransform();
        held.translate(middle.getX(), middle.getY());
        held.rotate(pose.turn());
        held.scale(scale, scale);
        held.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
        var before = Draw.fade(g, pose.alpha());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, held, null);
        g.setComposite(before);
    }

    /// A part of the window as it looked at a moment, kept after the window no longer has it:
    /// a button that was pressed and is gone, which the film lets fade where it was.
    ///
    /// @param image what it looked like, `fine` times finer than the window's pixels
    /// @param place where it was, in the window's units
    record Kept(BufferedImage image, Rectangle2D place, int fine) {}

    /// Keeps how `part` looks now.
    Kept keep(Component part) {
        int fine = 3;
        BufferedImage image = new BufferedImage(Math.max(1, part.getWidth() * fine), Math.max(1, part.getHeight() * fine), BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D p = image.createGraphics();
        hints(p);
        p.scale(fine, fine);
        part.paint(p);
        p.dispose();
        return new Kept(image, unitsOf(part), fine);
    }

    /// Paints what was kept where `camera` sees its place, held as `pose` says.
    void paint(Graphics2D g, Camera camera, Kept kept, Pose pose) {
        if (pose.alpha() <= 0) return;
        Point2D middle = camera.film(kept.place().getCenterX() + pose.dx(), kept.place().getCenterY() + pose.dy());
        double scale = camera.scale() * pose.scale() / (UI * kept.fine());
        AffineTransform held = new AffineTransform();
        held.translate(middle.getX(), middle.getY());
        held.rotate(pose.turn());
        held.scale(scale, scale);
        held.translate(-kept.image().getWidth() / 2.0, -kept.image().getHeight() / 2.0);
        var before = Draw.fade(g, pose.alpha());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(kept.image(), held, null);
        g.setComposite(before);
    }

    /// How many times finer than its own pixels the window is painted for a camera at `scale`:
    /// once, unless the camera is nearer than the window's pixels are fine.
    private static int fineness(double scale) {
        return Math.clamp((int) Math.ceil(scale / UI - 0.02), 1, 6);
    }

    private static void hints(Graphics2D p) {
        p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        p.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /// Paints with `parts` left out, and has them back after. A part that is not visible is
    /// not painted, and Swing lays nothing out anew before it is visible again.
    private static void without(Set<Component> parts, Runnable painting) {
        List<Component> hidden = new ArrayList<>();
        for (Component part : parts) if (part.isVisible()) { part.setVisible(false); hidden.add(part); }
        try {
            painting.run();
        } finally {
            for (Component part : hidden) part.setVisible(true);
        }
    }

    /// How far the shadow reaches beyond the window, and how large the square is it was painted
    /// around, once: its corners and edges are drawn around a window of any size.
    private static final int REACH = 30, SQUARE = 160;
    private static final BufferedImage SHADOW = shadowAround();

    private static BufferedImage shadowAround() {
        int size = SQUARE + 2 * REACH;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = REACH; i >= 1; i -= 2) {
            double near = 1 - i / (double) REACH;
            g.setColor(new Color(0, 0, 0, (int) Math.round(1 + 13 * near * near * near)));
            g.fill(new RoundRectangle2D.Double(REACH - i, REACH - i, SQUARE + 2 * i, SQUARE + 2 * i, 2 * (i + 14), 2 * (i + 14)));
        }
        g.dispose();
        return image;
    }

    private static void shadow(Graphics2D g, double x, double y, double width, double height, double zoom) {
        int reach = (int) Math.round(REACH * zoom), half = (int) Math.round(Math.min(SQUARE / 2.0 * zoom, Math.min(width, height) / 2)), corner = REACH + SQUARE / 2;
        int[] sx = {0, corner, corner + 1, 2 * corner};
        int[] dx = {(int) Math.round(x) - reach, (int) Math.round(x) + half, (int) Math.round(x + width) - half, (int) Math.round(x + width) + reach};
        int[] dy = {(int) Math.round(y) - reach, (int) Math.round(y) + half, (int) Math.round(y + height) - half, (int) Math.round(y + height) + reach};
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 3; column++)
                if (row != 1 || column != 1)
                    g.drawImage(SHADOW, dx[column], dy[row], dx[column + 1], dy[row + 1], sx[column], sx[row], sx[column + 1], sx[row + 1], null);
    }

    // ─── finding and pressing what the window shows ────────────────────────────────────────

    /// Where the button or label that says `text` is, in the window's units; the first one that
    /// shows, from the top.
    Optional<Rectangle2D> where(String text) {
        return find(frame.getRootPane(), it -> text.equals(textOf(it))).map(this::unitsOf);
    }

    /// The first button that `is` so, from the top.
    Optional<AbstractButton> button(Predicate<AbstractButton> is) {
        return find(frame.getRootPane(), it -> it instanceof AbstractButton button && is.test(button)).map(AbstractButton.class::cast);
    }

    /// The first button whose words have `text` in them.
    Optional<AbstractButton> buttonSaying(String text) {
        return button(it -> it.getText() != null && it.getText().contains(text));
    }

    /// The first label whose words have `text` in them.
    Optional<Component> labelSaying(String text) {
        return find(frame.getRootPane(), it -> it instanceof JLabel label && label.getText() != null && label.getText().contains(text));
    }

    /// Where `component` is in the window, in its units.
    Rectangle2D unitsOf(Component component) {
        Point corner = SwingUtilities.convertPoint(component, 0, 0, frame.getRootPane());
        return new Rectangle2D.Double(corner.x / UI, corner.y / UI, component.getWidth() / UI, component.getHeight() / UI);
    }

    /// The component `levels` above `component`: what it is in, and what that is in.
    static Component above(Component component, int levels) {
        Component up = component;
        for (int i = 0; i < levels && up.getParent() != null; i++) up = up.getParent();
        return up;
    }

    /// The window's components that show, as an outline: each with its kind, its words if it has
    /// any, and its place in units. For whoever writes a scene and looks for a part to name.
    String outline() {
        StringBuilder outline = new StringBuilder();
        outline(frame.getRootPane(), 0, outline);
        return outline.toString();
    }

    private void outline(Component from, int depth, StringBuilder outline) {
        if (!from.isVisible() || from.getWidth() == 0 || from.getHeight() == 0) return;
        Rectangle2D at = unitsOf(from);
        String words = textOf(from);
        outline.append("  ".repeat(depth)).append(from.getClass().getSimpleName().isEmpty() ? from.getClass().getName() : from.getClass().getSimpleName())
            .append(words == null || words.isBlank() ? "" : " \"" + words + "\"")
            .append(String.format(java.util.Locale.ROOT, "  %.0f,%.0f %.0f×%.0f%n", at.getX(), at.getY(), at.getWidth(), at.getHeight()));
        if (from instanceof Container container)
            for (Component child : container.getComponents()) outline(child, depth + 1, outline);
    }

    /// Lights the button up as under the pointer, or lets it go.
    void hover(AbstractButton button, boolean over) { button.getModel().setRollover(over); }

    /// Holds the button down, as while it is pressed.
    void hold(AbstractButton button, boolean down) {
        button.getModel().setArmed(down);
        button.getModel().setPressed(down);
    }

    /// Lets go of a held button without clicking it, then clicks it, as its listeners hear it.
    void click(AbstractButton button) {
        // Disarmed first: letting go of an armed button would click it, and so twice.
        button.getModel().setArmed(false);
        button.getModel().setPressed(false);
        button.doClick(0);
    }

    /// Every component that shows, for a scene that looks for one in a way of its own.
    <C extends Component> List<C> all(Class<C> type) {
        List<C> found = new ArrayList<>();
        collect(frame.getRootPane(), type, found);
        return found;
    }

    private static <C extends Component> void collect(Component from, Class<C> type, List<C> found) {
        if (!from.isVisible()) return;
        if (type.isInstance(from)) found.add(type.cast(from));
        if (from instanceof Container container)
            for (Component child : container.getComponents()) collect(child, type, found);
    }

    private static String textOf(Component component) {
        if (component instanceof AbstractButton button) return button.getText();
        if (component instanceof JLabel label) return label.getText();
        return null;
    }

    private static Optional<Component> find(Component from, Predicate<Component> is) {
        if (!from.isVisible() || from.getWidth() == 0) return Optional.empty();
        if (is.test(from)) return Optional.of(from);
        if (from instanceof Container container)
            for (Component child : container.getComponents()) {
                Optional<Component> found = find(child, is);
                if (found.isPresent()) return found;
            }
        return Optional.empty();
    }
}
