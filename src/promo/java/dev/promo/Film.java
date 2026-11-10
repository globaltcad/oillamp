package dev.promo;

import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/// Films scenes, one after the other, picture by picture. For each picture the film's clock
/// ([FilmClock]) is moved on by one picture's time, the scene does what is due by then, the
/// window's animations take that step, and the picture is painted on Swing's event thread and
/// goes to ffmpeg, which writes the video. However long a picture takes to paint, the film shows
/// it for its own time, so the film is the same whenever and wherever it is made.
final class Film {

    static final int WIDTH = 1920, HEIGHT = 1080;

    private final int fps;

    Film(int fps) { this.fps = fps; }

    /// How long `scenes` take together, in seconds.
    static double seconds(List<Scene> scenes) {
        return scenes.stream().mapToDouble(scene -> scene.seconds).sum();
    }

    /// Films `scenes` into the video `target`, without sound.
    void shoot(List<Scene> scenes, Path target) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Process ffmpeg = new ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgr0", "-s", WIDTH + "x" + HEIGHT, "-r", String.valueOf(fps), "-i", "-",
            "-c:v", "libx264", "-preset", "slow", "-crf", "15", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
            target.toString()
        ).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        BufferedImage picture = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) picture.getRaster().getDataBuffer()).getData();
        byte[] bytes = new byte[pixels.length * 4];
        IntBuffer asInts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        long began = System.nanoTime();
        try (OutputStream video = ffmpeg.getOutputStream()) {
            for (Scene scene : scenes) {
                int frames = (int) Math.round(scene.seconds * fps);
                long sceneBegan = System.nanoTime();
                double from = begin(scene);
                for (int frame = 0; frame < frames; frame++) {
                    double t = frame / (double) fps;
                    picture(scene, from, t, picture);
                    asInts.clear();
                    asInts.put(pixels);
                    video.write(bytes);
                }
                SwingUtilities.invokeAndWait(scene::end);
                System.out.printf("%-12s %5.1f s of film in %5.1f s%n", scene.name, scene.seconds, (System.nanoTime() - sceneBegan) / 1e9);
            }
        }
        if (ffmpeg.waitFor() != 0) throw new IOException("ffmpeg could not write " + target);
        System.out.printf("%.1f s of film in %.0f s%n", seconds(scenes), (System.nanoTime() - began) / 1e9);
    }

    /// Plays `scene` up to `t` seconds, picture by picture, and writes that moment as a PNG.
    static void still(Scene scene, double t, Path target) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        BufferedImage picture = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        double from = begin(scene);
        // Coarser steps than the film's, since only the last picture is kept.
        for (double now = 0; now < t; now += 0.1) picture(scene, from, now, picture);
        picture(scene, from, t, picture);
        SwingUtilities.invokeAndWait(scene::end);
        ImageIO.write(picture, "png", new File(target.toString()));
    }

    /// Plays `scene` up to `t` seconds, and lists the parts of the window it shows then.
    static String outline(Scene scene, double t) throws Exception {
        BufferedImage picture = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        double from = begin(scene);
        for (double now = 0; now < t; now += 0.1) picture(scene, from, now, picture);
        picture(scene, from, t, picture);
        String[] outline = {""};
        SwingUtilities.invokeAndWait(() -> outline[0] = scene instanceof AppScene app ? app.window.outline() : "The scene " + scene.name + " does not show the window.");
        SwingUtilities.invokeAndWait(scene::end);
        return outline[0];
    }

    /// Begins `scene`, and tells where on the film's clock it begins.
    private static double begin(Scene scene) throws Exception {
        if (!FilmClock.attached())
            throw new IllegalStateException("The film's clock is not attached: start the film with ./gradlew promo, which does that.");
        double from = FilmClock.seconds() + 0.5;
        FilmClock.at(from);
        SwingUtilities.invokeAndWait(() -> { scene.rewind(); scene.begin(); });
        settle();
        return from;
    }

    /// Paints `scene` as it is `t` seconds in, the film's clock being `from` when it began.
    private static void picture(Scene scene, double from, double t, BufferedImage picture) throws Exception {
        FilmClock.at(from + t);
        SwingUtilities.invokeAndWait(() -> { scene.due(t); Animations.step(); });
        settle();
        SwingUtilities.invokeAndWait(() -> { Animations.step(); paint(scene, t, picture); });
    }

    /// Waits until Swing has done what the last change asked of it: laying out again, and what
    /// follows from a size that changed.
    private static void settle() throws Exception {
        EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        for (int turn = 0; turn < 3 || (turn < 40 && queue.peekEvent() != null); turn++) SwingUtilities.invokeAndWait(() -> {});
    }

    private static void paint(Scene scene, double t, BufferedImage picture) {
        scene.due(t);
        Graphics2D g = picture.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        try {
            scene.paint(g, t);
        } finally {
            g.dispose();
        }
    }
}
