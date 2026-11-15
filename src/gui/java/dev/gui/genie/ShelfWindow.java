package dev.gui.genie;

import java.util.Optional;

/// How far the window of the Genies that holds the [ShelfClaim] is, for what it answers another
/// Genies that asks it to show itself: from opening, through open, to closing.
///
/// Changed on Swing's event thread, as the window opens and closes; asked on the claim's own.
public final class ShelfWindow {

    private enum Stage { OPENING, OPEN, CLOSING }

    private volatile Stage stage = Stage.OPENING;
    /// Shows the window, once it is open.
    private volatile Optional<Runnable> show = Optional.empty();

    /// The window is open, and `show` shows it, on any thread.
    public void opened(Runnable show) {
        this.show = Optional.of(show);
        stage = Stage.OPEN;
    }

    /// The user closes the window: the genies are being put to sleep or left running.
    public void closing() { stage = Stage.CLOSING; }

    /// Another Genies asks this one to show its window: shows it, when it is open, and returns
    /// the answer: [ShelfClaim.Answer#SHOWN] while it opens or is open, and
    /// [ShelfClaim.Answer#CLOSING] while it closes.
    public ShelfClaim.Answer askedToShow() {
        return switch (stage) {
            case OPENING -> ShelfClaim.Answer.SHOWN;
            case OPEN -> {
                show.ifPresent(Runnable::run);
                yield ShelfClaim.Answer.SHOWN;
            }
            case CLOSING -> ShelfClaim.Answer.CLOSING;
        };
    }
}
