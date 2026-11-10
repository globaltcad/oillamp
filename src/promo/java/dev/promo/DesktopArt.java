package dev.promo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.imageio.ImageIO;

/// What a genie's desktop shows in the film: a lamp's desktop as oillamp makes it, with its
/// wallpaper and its dock, a terminal in which the genie builds, and then the app it built,
/// fullscreen, as a genie shows things. A picture of any size, at the moment it is painted.
final class DesktopArt {

    /// The colours of a lamp's desktop, from its dock and its window frames.
    private static final Color GROUND = new Color(0x1f2430), EDGE = new Color(0x3a4252), BUTTON = new Color(0x252a35),
                               INK = new Color(0xe6e9ef), BRASS = new Color(0xc8913f);

    private static final BufferedImage WALLPAPER = wallpaper();

    /// What the terminal prints, line by line, while the genie builds.
    private static final List<String> LINES = List.of(
        "agent@lamp:~/workspace$ npm create vite@latest plants -- --template react",
        "Scaffolding project in /home/agent/workspace/plants...",
        "Done.",
        "agent@lamp:~/workspace/plants$ npm install",
        "added 152 packages in 4s",
        "agent@lamp:~/workspace/plants$ npm run build",
        "vite v7.1.4 building for production...",
        "✓ 34 modules transformed.",
        "dist/index.html                  0.46 kB",
        "dist/assets/index-C1x9dKq2.js  148.20 kB",
        "✓ built in 1.31s",
        "agent@lamp:~/workspace/plants$ npm run preview -- --port 4173",
        "  ➜  Local:   http://localhost:4173/",
        "agent@lamp:~/workspace/plants$ firefox --kiosk http://localhost:4173 &",
        "agent@lamp:~/workspace/plants$ lamp show \"your plant app\""
    );

    /// When the terminal opened and when the app did, on the computer's clock; not yet, if negative.
    private volatile long terminalSince = -1, appSince = -1;
    /// How many of the terminal's lines are printed.
    private volatile int printed = 0;

    /// The desktop as a lamp starts it: nothing open.
    void reset() { terminalSince = appSince = -1; printed = 0; }

    void openTerminal() { terminalSince = FilmClock.nanos(); }

    /// The terminal has printed its first `lines` lines.
    void printed(int lines) { printed = Math.min(lines, LINES.size()); }

    void openApp() { appSince = FilmClock.nanos(); }

    /// The app at once, as it is once it has opened.
    void appIsOpen() { appSince = FilmClock.nanos() - 5_000_000_000L; }

    void paint(Graphics2D g, int width, int height) {
        g.setColor(GROUND);
        g.fillRect(0, 0, width, height);
        // The wallpaper is a card with round corners; its inside covers the desktop.
        double cover = Math.max(width / 1080.0, height / 580.0);
        int w = (int) Math.ceil(1200 * cover), h = (int) Math.ceil(700 * cover);
        g.drawImage(WALLPAPER, (width - w) / 2, (height - h) / 2, w, h, null);
        dock(g, width, height);
        double terminal = since(terminalSince), app = since(appSince);
        if (terminal >= 0 && app < 0.5) terminal(g, width, height, Draw.arrive(terminal, 0, 0.3));
        if (app >= 0) app(g, width, height, Draw.arrive(app, 0, 0.45));
    }

    private static double since(long moment) { return moment < 0 ? -1 : (FilmClock.nanos() - moment) / 1e9; }

    private void dock(Graphics2D g, int width, int height) {
        int buttons = 2, size = 36, gap = 6, w = buttons * size + (buttons + 1) * gap, h = size + 2 * gap;
        int x = (width - w) / 2, y = height - h - 8;
        g.setColor(GROUND);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 20, 20));
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 20, 20));
        for (int i = 0; i < buttons; i++) {
            int bx = x + gap + i * (size + gap), by = y + gap;
            g.setColor(BUTTON);
            g.fill(new RoundRectangle2D.Double(bx, by, size, size, 14, 14));
            g.setColor(INK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (i == 0) {                               // a terminal: a prompt
                Path2D prompt = new Path2D.Double();
                prompt.moveTo(bx + 11, by + 12);
                prompt.lineTo(bx + 17, by + 18);
                prompt.lineTo(bx + 11, by + 24);
                g.draw(prompt);
                g.drawLine(bx + 20, by + 25, bx + 26, by + 25);
            } else {                                    // a browser: a globe
                g.setColor(new Color(0xf0955a));
                g.draw(new Ellipse2D.Double(bx + 9, by + 9, 18, 18));
                g.draw(new Ellipse2D.Double(bx + 14, by + 9, 8, 18));
                g.drawLine(bx + 9, by + 18, bx + 27, by + 18);
            }
        }
    }

    private void terminal(Graphics2D g, int width, int height, double shown) {
        double w = Math.min(width - 48, 760), h = Math.min(height - 120, 430);
        double x = (width - w) / 2, y = (height - h) / 2 - 20 + 16 * (1 - shown);
        var before = Draw.fade(g, shown);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Double(x - 3, y + 5, w + 6, h + 6, 12, 12));
        g.setColor(new Color(0x14171f));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        g.setColor(BRASS);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        double size = Math.max(11, Math.min(14.5, w / 50));
        g.setFont(Draw.mono(size));
        double line = size * 1.5;
        int fits = (int) ((h - 24) / line);
        int first = Math.max(0, printed - fits);
        var clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(x + 2, y + 2, w - 4, h - 4, 8, 8));
        for (int i = first; i < printed; i++) {
            String text = LINES.get(i);
            boolean prompt = text.startsWith("agent@");
            double baseline = y + 14 + (i - first + 1) * line - 4;
            if (prompt) {
                int end = text.indexOf('$') + 1;
                g.setColor(new Color(0x8fd19e));
                g.drawString(text.substring(0, end), (float) (x + 14), (float) baseline);
                g.setColor(INK);
                g.drawString(text.substring(end), (float) (x + 14 + Draw.width(g, text.substring(0, end))), (float) baseline);
            } else {
                g.setColor(text.contains("✓") || text.contains("➜") ? new Color(0x8fd19e) : new Color(0xa9b0bf));
                g.drawString(text, (float) (x + 14), (float) baseline);
            }
        }
        g.setClip(clip);
        g.setComposite(before);
    }

    // ─── the app the genie built ───────────────────────────────────────────────────────────

    private record Plant(String name, String every, double thirst, String due, Color leaf, int shape) {}

    private static final List<Plant> PLANTS = List.of(
        new Plant("Fern", "every 3 days", 1.0, "today", new Color(0x4f9d69), 0),
        new Plant("Monstera", "once a week", 0.72, "in 2 days", new Color(0x2f7d4f), 1),
        new Plant("Basil", "every 2 days", 0.5, "tomorrow", new Color(0x7bb661), 2),
        new Plant("Aloe", "every 2 weeks", 0.2, "in 11 days", new Color(0x5fae8f), 3),
        new Plant("Fig", "once a week", 0.45, "in 4 days", new Color(0x3d8b5a), 1),
        new Plant("Orchid", "every 10 days", 0.85, "in 2 days", new Color(0x6aa57a), 2)
    );

    private static final Color PAPER = new Color(0xf5f1e6), CARD = new Color(0xfffdf8), SOIL = new Color(0x2b3a30),
                               QUIET = new Color(0x7b857c), WATER = new Color(0x4a90c2), POT = new Color(0xc9773f);

    private void app(Graphics2D g, int width, int height, double shown) {
        var before = Draw.fade(g, shown);
        g.setColor(PAPER);
        g.fillRect(0, 0, width, height);
        double unit = Math.max(0.8, Math.min(1.25, width / 760.0));
        double margin = 32 * unit, top = 40 * unit;
        leaf(g, margin + 2, top - 4 * unit, 30 * unit, new Color(0x4f9d69));
        g.setColor(SOIL);
        g.setFont(Draw.font(30 * unit, true, -0.01));
        g.drawString("My plants", (float) (margin + 40 * unit), (float) (top + 22 * unit));
        g.setFont(Draw.font(15 * unit, false));
        g.setColor(QUIET);
        g.drawString("Thursday, 12 November · the fern is thirsty today", (float) margin, (float) (top + 54 * unit));

        int columns = width < 520 ? 2 : 3;
        double gap = 16 * unit, cardW = (width - 2 * margin - (columns - 1) * gap) / columns;
        double gridTop = top + 80 * unit, rows = Math.ceil(PLANTS.size() / (double) columns);
        double cardH = Math.min(230 * unit, (height - gridTop - margin - (rows - 1) * gap) / rows);
        for (int i = 0; i < PLANTS.size(); i++) {
            Plant plant = PLANTS.get(i);
            double x = margin + (i % columns) * (cardW + gap), y = gridTop + Math.floorDiv(i, columns) * (cardH + gap);
            double rise = Draw.arrive(shown, 0.2 + i * 0.08, 1) * shown;
            y += 18 * (1 - rise);
            g.setColor(new Color(0x2b, 0x3a, 0x30, 18));
            g.fill(new RoundRectangle2D.Double(x, y + 3, cardW, cardH, 22, 22));
            g.setColor(CARD);
            g.fill(new RoundRectangle2D.Double(x, y, cardW, cardH, 20, 20));
            boolean thirsty = plant.thirst() >= 1;
            if (thirsty) {
                g.setColor(WATER);
                g.setStroke(new BasicStroke((float) (2 * unit)));
                g.draw(new RoundRectangle2D.Double(x + 1, y + 1, cardW - 2, cardH - 2, 20, 20));
            }
            // A card too short for its plant, on a desktop as small as a phone's, does without.
            double art = Math.min(cardH * 0.42, cardW * 0.5);
            if (cardH >= 150 * unit) potted(g, x + cardW / 2, y + 14 * unit + art, art, plant);
            // A card with no room for more says only which plant it is.
            boolean roomy = cardH >= 84 * unit;
            double text = roomy ? y + cardH - 58 * unit : y + cardH - 30 * unit;
            g.setColor(SOIL);
            g.setFont(Draw.font(17 * unit, true));
            g.drawString(plant.name(), (float) (x + 16 * unit), (float) text);
            String due = thirsty ? "Water today" : plant.due();
            if (roomy) {
                g.setFont(Draw.font(12.5 * unit, false));
                g.setColor(QUIET);
                g.drawString(plant.every(), (float) (x + 16 * unit), (float) (text + 19 * unit));
                g.setColor(thirsty ? WATER : QUIET);
                g.setFont(Draw.font(12.5 * unit, thirsty));
                g.drawString(due, (float) (x + cardW - 16 * unit - Draw.width(g, due)), (float) (text + 19 * unit));
            }
            double barY = y + cardH - 22 * unit, barW = cardW - 32 * unit;
            g.setColor(new Color(0xe7e2d4));
            g.fill(new RoundRectangle2D.Double(x + 16 * unit, barY, barW, 7 * unit, 7 * unit, 7 * unit));
            g.setColor(thirsty ? WATER : new Color(0x9cc5df));
            g.fill(new RoundRectangle2D.Double(x + 16 * unit, barY, barW * plant.thirst() * rise, 7 * unit, 7 * unit, 7 * unit));
        }
        g.setComposite(before);
    }

    /// A plant in its pot, standing on `ground`, `size` tall.
    private static void potted(Graphics2D g, double middle, double ground, double size, Plant plant) {
        double potW = size * 0.46, potH = size * 0.32;
        Path2D pot = new Path2D.Double();
        pot.moveTo(middle - potW / 2, ground - potH);
        pot.lineTo(middle + potW / 2, ground - potH);
        pot.lineTo(middle + potW * 0.36, ground);
        pot.lineTo(middle - potW * 0.36, ground);
        pot.closePath();
        double stem = ground - potH;
        g.setColor(plant.leaf());
        switch (plant.shape()) {
            case 0 -> { for (int i = -3; i <= 3; i++) frond(g, middle, stem, size * 0.62, i * 0.33); }
            case 1 -> {
                leaf(g, middle - size * 0.34, stem - size * 0.56, size * 0.36, plant.leaf());
                leaf(g, middle + size * 0.02, stem - size * 0.66, size * 0.4, plant.leaf().darker());
                leaf(g, middle - size * 0.12, stem - size * 0.34, size * 0.3, plant.leaf());
            }
            case 2 -> {
                for (int i = 0; i < 5; i++) {
                    double angle = -Math.PI / 2 + (i - 2) * 0.5, reach = size * (0.34 + 0.08 * (i % 2));
                    double x = middle + Math.cos(angle) * reach, y = stem + Math.sin(angle) * reach;
                    g.setColor(i % 2 == 0 ? plant.leaf() : plant.leaf().darker());
                    g.fill(new Ellipse2D.Double(x - size * 0.13, y - size * 0.1, size * 0.26, size * 0.2));
                }
            }
            default -> {
                for (int i = -2; i <= 2; i++) {
                    Path2D spike = new Path2D.Double();
                    double lean = i * size * 0.17, tall = size * (0.62 - Math.abs(i) * 0.1);
                    spike.moveTo(middle - size * 0.06 + i * size * 0.04, stem);
                    spike.quadTo(middle + lean * 0.6, stem - tall * 0.6, middle + lean, stem - tall);
                    spike.quadTo(middle + lean * 0.8 + size * 0.07, stem - tall * 0.5, middle + size * 0.06 + i * size * 0.04, stem);
                    spike.closePath();
                    g.setColor(i % 2 == 0 ? plant.leaf() : plant.leaf().darker());
                    g.fill(spike);
                }
            }
        }
        g.setColor(POT);
        g.fill(pot);
        g.setColor(POT.darker());
        g.fill(new RoundRectangle2D.Double(middle - potW * 0.56, ground - potH - size * 0.05, potW * 1.12, size * 0.09, size * 0.04, size * 0.04));
    }

    private static void frond(Graphics2D g, double x, double y, double length, double lean) {
        Path2D frond = new Path2D.Double();
        double tipX = x + Math.sin(lean) * length, tipY = y - Math.cos(lean) * length;
        double side = length * 0.11;
        frond.moveTo(x, y);
        frond.quadTo((x + tipX) / 2 - side * Math.cos(lean), (y + tipY) / 2 - side * Math.sin(lean), tipX, tipY);
        frond.quadTo((x + tipX) / 2 + side * Math.cos(lean), (y + tipY) / 2 + side * Math.sin(lean), x, y);
        g.fill(frond);
    }

    /// A leaf whose box has its top left corner at `x`, `y`, `size` across.
    private static void leaf(Graphics2D g, double x, double y, double size, Color colour) {
        Path2D leaf = new Path2D.Double();
        leaf.moveTo(x, y + size);
        leaf.curveTo(x - size * 0.05, y + size * 0.3, x + size * 0.45, y, x + size, y);
        leaf.curveTo(x + size, y + size * 0.6, x + size * 0.65, y + size * 1.02, x, y + size);
        g.setColor(colour);
        g.fill(leaf);
        g.setColor(Draw.mix(colour, Color.WHITE, 0.35));
        g.setStroke(new BasicStroke((float) Math.max(1, size * 0.045), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D vein = new Path2D.Double();
        vein.moveTo(x + size * 0.08, y + size * 0.92);
        vein.quadTo(x + size * 0.4, y + size * 0.5, x + size * 0.85, y + size * 0.15);
        g.draw(vein);
    }

    private static BufferedImage wallpaper() {
        try (InputStream in = DesktopArt.class.getResourceAsStream("/image/rootfs/usr/share/oillamp/wallpaper.png")) {
            if (in == null) throw new IOException("the lamp's wallpaper is not on the classpath");
            return ImageIO.read(in);
        } catch (IOException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
