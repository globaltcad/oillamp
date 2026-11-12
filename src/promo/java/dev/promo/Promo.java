package dev.promo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Films Genies' introduction video from [Storyboard].
///
///     ./gradlew promo                                  the whole film, build/promo/genies-promo.mp4
///     ./gradlew promo --args="--scenes wake,ask"       only these scenes, build/promo/cut.mp4
///     ./gradlew promo --args="--still ask@6.5"         one picture, build/promo/stills/ask@6.5.png
///     ./gradlew promo --args="--list"                  the scenes and how long each is
///     ./gradlew promo --args="--outline ask@12.5"      the window's parts at that moment, to name one in a scene
///
/// The score is the file `score.*` in src/promo/music, or the one `--music <file>` names; without
/// one the film is silent. `--fps <n>` films at another rate than 30, and
/// `--out <dir>` writes elsewhere than build/promo.
final class Promo {

    public static void main(String[] args) throws Exception {
        // The window says where the user's home is, on the host access page: in the film it is
        // nobody's in particular.
        System.setProperty("user.home", "/home/you");
        Path out = Path.of("build", "promo");
        List<String> wanted = new ArrayList<>(), stills = new ArrayList<>(), outlines = new ArrayList<>();
        Path music = null;
        int fps = 30;
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scenes" -> wanted.addAll(List.of(args[++i].split(",")));
                case "--still" -> stills.addAll(List.of(args[++i].split(",")));
                case "--music" -> music = Path.of(args[++i]);
                case "--fps" -> fps = Integer.parseInt(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--list" -> list = true;
                case "--outline" -> outlines.add(args[++i]);
                default -> throw new IllegalArgumentException("Unknown: " + args[i]);
            }
        }
        if (music == null) music = score();
        List<Scene> scenes = Storyboard.scenes();
        if (list) {
            double at = 0;
            for (Scene scene : scenes) {
                System.out.printf("%6.1f  %-14s %5.1f s%n", at, scene.name, scene.seconds);
                at += scene.seconds;
            }
            System.out.printf("%6.1f  the end%n", at);
            System.exit(0);
        }
        for (String outline : outlines) {
            int at = outline.indexOf('@');
            System.out.println(Film.outline(named(scenes, outline.substring(0, at)), Double.parseDouble(outline.substring(at + 1))));
        }
        if (!outlines.isEmpty()) System.exit(0);
        if (!stills.isEmpty()) {
            for (String still : stills) {
                int at = still.indexOf('@');
                Scene scene = named(scenes, still.substring(0, at));
                Path target = out.resolve("stills").resolve(still + ".png");
                Film.still(scene, Double.parseDouble(still.substring(at + 1)), target);
                System.out.println(target);
            }
            System.exit(0);
        }
        List<Scene> cut = new ArrayList<>();
        double before = 0;
        boolean whole = wanted.isEmpty();
        for (Scene scene : scenes) {
            if (whole || wanted.contains(scene.name)) cut.add(scene);
            else if (cut.isEmpty()) before += scene.seconds;
        }
        if (cut.isEmpty()) throw new IllegalArgumentException("No such scenes: " + wanted);
        Path silent = out.resolve(whole ? "genies-promo-silent.mp4" : "cut-silent.mp4");
        Path target = out.resolve(whole ? "genies-promo.mp4" : "cut.mp4");
        new Film(fps).shoot(cut, silent);
        if (music != null) {
            Score.lay(music, silent, target, before, Film.seconds(cut), whole);
            System.out.println(target);
        } else {
            System.out.println(silent);
        }
        System.exit(0);
    }

    /// The score in src/promo/music, if one was put there: a file called `score`, whatever its kind.
    private static Path score() throws java.io.IOException {
        Path folder = Path.of("src", "promo", "music");
        if (!java.nio.file.Files.isDirectory(folder)) return null;
        try (var files = java.nio.file.Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().startsWith("score.")).findFirst().orElse(null);
        }
    }

    private static Scene named(List<Scene> scenes, String name) {
        return scenes.stream().filter(scene -> scene.name.equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No such scene: " + name));
    }
}
