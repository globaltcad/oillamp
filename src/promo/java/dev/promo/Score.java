package dev.promo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/// Lays the music under the film.
final class Score {

    private Score() {}

    /// Where in the piece the film begins, in seconds. The film is cut to "Recovery" by Rival
    /// Consoles from here: the piece lifts 86.7 seconds in, and that is when the flame bursts into
    /// the genie, 30.7 seconds into the film (7.3 seconds into the scene `wake`). Its quiet
    /// passage, from 183 seconds, is the film's end. Another piece, or scenes of other lengths
    /// before that moment, want another number here.
    static final double BEGINS_AT = 56.0;

    /// Writes `silent` with `music` under it to `target`: the music from where the cut begins,
    /// `before` seconds into the film, for `seconds`, fading out at the end.
    ///
    /// @param whole whether this is the whole film, whose first second fades in
    static void lay(Path music, Path silent, Path target, double before, double seconds, boolean whole) throws IOException, InterruptedException {
        double fade = 3.5;
        String filter = String.format(Locale.ROOT, "afade=t=in:st=0:d=%.2f,afade=t=out:st=%.2f:d=%.2f", whole ? 1.5 : 0.2, seconds - fade, fade);
        Process ffmpeg = new ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", silent.toString(),
            "-ss", String.format(Locale.ROOT, "%.3f", BEGINS_AT + before), "-t", String.format(Locale.ROOT, "%.3f", seconds), "-i", music.toString(),
            "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-af", filter, "-c:a", "aac", "-b:a", "256k", "-shortest", "-movflags", "+faststart",
            target.toString()
        ).inheritIO().start();
        if (ffmpeg.waitFor() != 0) throw new IOException("ffmpeg could not lay " + music + " under " + silent);
    }
}
