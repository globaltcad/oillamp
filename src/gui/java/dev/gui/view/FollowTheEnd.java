package dev.gui.view;

import java.awt.EventQueue;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.concurrent.TimeUnit;

import javax.swing.BoundedRangeModel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import swingtree.UI;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;

/// Decides the chat's scroll position: at the bottom while the user is there, and otherwise
/// where the user last scrolled to.
///
/// Only the user's input changes either: the mouse wheel or touchpad over the chat, and dragging
/// or clicking its scroll bar. Scrolling up by any amount leaves the bottom; scrolling down to
/// within [#SLACK] of it returns there. Every other change of the scroll bar comes from Swing's
/// layout: rows added, removed or growing, the thinking bar appearing under the chat, the
/// composer growing, the window being resized. After each such change, the scroll position is set
/// back: to the bottom, or to where the user last scrolled to.
///
/// The one exception is a search in the chat, which glides it to a match ([#glideTo]): from then
/// on, that is where the chat stays, or at the bottom if it glided there, as if the user had
/// scrolled there.
final class FollowTheEnd {

    /// How far above the bottom, in the window's units, a scroll down still counts as reaching it.
    private static final int SLACK = 48;

    /// How long the chat takes to glide to a match of a search.
    private static final LifeTime GLIDE = LifeTime.of(0.4, TimeUnit.SECONDS);

    private final BoundedRangeModel range;
    private boolean atBottom = true;
    /// The scroll position the user last scrolled to, used while not at the bottom.
    private int userPosition = 0;
    /// The scroll position when the user pressed the scroll bar, to tell up from down on release.
    private int pressedAt = 0;
    /// True while the user holds the mouse button on the scroll bar.
    private boolean dragging = false;
    /// True while this class sets the scroll position, so that its own change is not acted on.
    private boolean scrolling = false;
    /// Counts the glides started. A glide moves the chat only while it is the last one started;
    /// the user's own scrolling, and [#toEnd], count as one too, and so stop it.
    private int glides = 0;

    private FollowTheEnd(JScrollPane pane) {
        this.range = pane.getVerticalScrollBar().getModel();
        range.addChangeListener(_ -> changed());
        // Added after the look-and-feel's wheel listener, so it runs after that has moved the
        // scroll bar. A touchpad turns the wheel by fractions, so only the precise rotation says
        // which way.
        MouseWheelListener wheel = event -> userScrolled(event.getPreciseWheelRotation() < 0);
        pane.addMouseWheelListener(wheel);
        // A look-and-feel set up anew, as when the palette changes, gives the pane a new UI
        // delegate, which adds its wheel listener again, after this one. So this one goes after
        // it once more; otherwise it would see each scroll before it happens, and set it back.
        pane.addPropertyChangeListener("UI", _ -> {
            pane.removeMouseWheelListener(wheel);
            pane.addMouseWheelListener(wheel);
        });
        pane.getVerticalScrollBar().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                dragging = true;
                pressedAt = range.getValue();
            }
            @Override public void mouseReleased(MouseEvent event) {
                dragging = false;
                userScrolled(range.getValue() < pressedAt);
            }
        });
    }

    /// Attaches to `pane`. The listeners this needs have no SwingTree methods.
    static FollowTheEnd on(JScrollPane pane) { return new FollowTheEnd(pane); }

    /// Scrolls to the bottom and stays there as the chat changes, as after sending a message or
    /// opening another conversation.
    void toEnd() {
        glides++;
        atBottom = true;
        restore();
    }

    /// Glides to the scroll position `position`, easing in and out, and stays there as the chat
    /// changes, as when a search shows a match. Scrolling by the user stops the glide where it is.
    /// A glide to the bottom stays at the bottom, following what streams in, as the user does who
    /// scrolled there.
    void glideTo(int position) {
        int glide = ++glides;
        int from = range.getValue();
        boolean toBottom = position >= range.getMaximum() - range.getExtent();
        // Not at the bottom while gliding, or each change of the chat would jump there.
        atBottom = false;
        userPosition = from;
        if (from == position) {
            atBottom = toBottom;
            return;
        }
        // Not `asLongAs`: that would play the glide again and again, for as long as it holds.
        UI.animateFor(GLIDE).go(new Animation() {
            @Override public void run(AnimationStatus status) {
                if (glide != glides) return;
                userPosition = from + (int) Math.round((position - from) * status.fadeIn());
                restore();
            }

            @Override public void finish(AnimationStatus status) {
                if (glide != glides) return;
                userPosition = position;
                atBottom = toBottom;
                restore();
            }
        });
    }

    private void userScrolled(boolean up) {
        glides++;
        boolean nearBottom = range.getValue() + range.getExtent() >= range.getMaximum() - UI.scale(SLACK);
        atBottom = !up && nearBottom;
        userPosition = range.getValue();
    }

    private void changed() {
        if (scrolling || dragging) return;
        int wanted = atBottom ? range.getMaximum() - range.getExtent() : userPosition;
        if (range.getValue() == wanted) return;
        // A wheel scroll changes the scroll bar before userScrolled has seen it, so that is set
        // back later. Any other change is set back at once: a layout is painted right after it,
        // and set back later, the chat would show at the wrong place for a moment, such as at its
        // top when it is shown again.
        if (EventQueue.getCurrentEvent() instanceof MouseWheelEvent) SwingUtilities.invokeLater(this::restore);
        else restore();
    }

    /// Sets the scroll position to the bottom, or to where the user last scrolled to. While the
    /// content is too short for that, the scroll bar stops at the bottom, and the next change of
    /// the content sets it again.
    private void restore() {
        if (dragging) return;
        scrolling = true;
        try {
            range.setValue(atBottom ? range.getMaximum() - range.getExtent() : userPosition);
        } finally {
            scrolling = false;
        }
    }
}
