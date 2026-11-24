package dev.gui;

import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.gui.genie.Shelf;
import dev.gui.genie.ShelfClaim;
import dev.gui.genie.ShelfWindow;
import dev.gui.genie.SystemLook;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Lang;
import dev.gui.model.Palette;
import dev.gui.view.GeniesView;

import com.formdev.flatlaf.FlatClientProperties;

import org.slf4j.LoggerFactory;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.components.JGlassPane;
import swingtree.dialogs.ConfirmAnswer;

/// Starts Genies: the app, [Genies#open], in a window of its own. Once on each shelf: started
/// while another Genies has the shelf, as from the desktop's "New Window", it asks the other to
/// show its window instead, and ends.
final class Main {

    /// How far this Genies' window is, for another Genies that asks to see it.
    private final ShelfWindow window = new ShelfWindow();
    /// The claim on the shelf, held until Genies ends.
    private static Optional<ShelfClaim> claim = Optional.empty();

    void main() {
        // Before anything logs, so that SwingTree's errors reach the error log too.
        System.setProperty("slf4j.provider", LoggedErrors.class.getName());
        Function<String, Optional<String>> environment = name -> Optional.ofNullable(System.getenv(name));
        Shelf shelf = Shelf.standard(environment);
        ErrorLog errors = new ErrorLog(shelf.errorLog());
        Thread.setDefaultUncaughtExceptionHandler(errors);
        // Before Genies joins the lamps left running, which a Genies open already holds.
        try {
            claim = ShelfClaim.claimOrShowTheOther(shelf, window::askedToShow, answer -> sayWhyNoWindow(shelf, environment, answer));
            if (claim.isEmpty()) System.exit(0);
            // Only the Genies that holds the shelf writes its log, so that two never write one file.
            AppLog.shared().writeTo(shelf.appLog());
        } catch (IOException failed) {
            // Without the claim, Genies opens all the same, as it did before it took one.
            LoggerFactory.getLogger(Main.class).warn("Could not claim the shelf {}, so a second Genies would open on it too",
                                                     shelf.root(), failed);
        }
        SwingUtilities.invokeLater(() -> {
            try {
                open(shelf, errors);
            } catch (RuntimeException | Error failed) {
                // Without a window, Genies ends, rather than hold the shelf and the lamps it joined
                // where the user cannot reach them; the next Genies started opens instead.
                errors.record("opening the window", failed);
                System.exit(1);
            }
        });
    }

    /// Tells the user why no window came: the Genies that has the shelf is closing, or did not
    /// answer.
    private static void sayWhyNoWindow(Shelf shelf, Function<String, Optional<String>> environment, ShelfClaim.Answer answer) {
        SystemLook desktop = SystemLook.ofThisComputer(environment);
        Palette palette = shelf.paletteOr(desktop);
        Lang lang = shelf.langOr(desktop);
        Lang.Dialogs words = lang.DIALOGS();
        try {
            SwingUtilities.invokeAndWait(() -> {
                GeniesView.setUpLook(GeniesState.of(Tuple.of(Genie.class), shelf.settings(), Optional.empty())
                                                .withPalette(palette).withLang(lang));
                JOptionPane.getRootFrame().setIconImage(GeniesView.windowIcon());
                JOptionPane.showMessageDialog(null,
                        answer == ShelfClaim.Answer.CLOSING
                            ? words.GENIES_IS_STILL_CLOSING_OPEN_IT_AGAIN_ONCE_ITS_WINDOW_HAS_GONE()
                            : words.GENIES_IS_OPEN_ALREADY_LOOK_FOR_ITS_WINDOW(),
                        words.GENIES_IS_ALREADY_OPEN(), JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (InterruptedException | InvocationTargetException notShown) {
            LoggerFactory.getLogger(Main.class).warn("Could not say that Genies is open already", notShown);
        }
    }

    private void open(Shelf shelf, ErrorLog errors) {
        Genies app = Genies.open(shelf, errors);
        GeniesView.setUpLook(app.state().get());
        // The window's title, which its title bar says too.
        Var<String> title = Var.of("Genies");
        JFrame frame = new JFrame(title.get());
        Viewable.cast(title).onChange(From.ALL, it -> frame.setTitle(it.currentValue().orElse("Genies")));
        new JGlassPane(frame.getRootPane());
        GeniesView view = new GeniesView(app);
        frame.setContentPane(view);
        // Genies draws the title bar itself, and says its name there.
        frame.setJMenuBar(view.titleBar(title));
        frame.getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        frame.getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        frame.setIconImage(GeniesView.windowIcon());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { quit(app, frame, title, view); }
        });
        // The timeline, and anything else said relative to now, moves on with the clock.
        new Timer(30_000, _ -> app.state().update(it -> it.withNow(Instant.now()))).start();
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        // Shown again for another Genies: no longer minimized, and brought to the front, if the
        // desktop lets it.
        window.opened(() -> SwingUtilities.invokeLater(() -> {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            frame.toFront();
        }));
    }

    /// Asks the user what becomes of the genies that are awake, then puts them to sleep or leaves
    /// them running, before the window goes. Without an answer, nothing closes.
    private void quit(Genies app, JFrame frame, Var<String> title, GeniesView view) {
        // A lamp from elsewhere is left running whatever the answer, so it is not asked about.
        List<String> awake = new ArrayList<>();
        for (Genie genie : app.state().get().genies())
            if (!genie.fromElsewhere() && (genie.phase().isAwake() || genie.phase() == Genie.Phase.WAKING)) awake.add(genie.name());
        Lang.Dialogs words = app.state().get().lang().DIALOGS();
        boolean leaveRunning = false;
        if (!awake.isEmpty()) {
            ConfirmAnswer answer = UI.confirmation(words.ARE_AWAKE_PUT_THEM_TO_SLEEP_OR_KEEP_THEM_RUNNING_IN_THE_BACKGROUND(awake))
                    .titled(words.GENIES_ARE_AWAKE()).yesOption(words.PUT_TO_SLEEP()).noOption(words.KEEP_RUNNING())
                    .cancelOption(words.CANCEL()).parent(frame).show();
            if (answer.isCancelOrClose()) return;
            leaveRunning = answer.isNo();
        }
        boolean keep = leaveRunning;
        window.closing();
        title.set(keep ? words.GENIES_LEAVING_THE_GENIES_RUNNING() : words.GENIES_PUTTING_THE_GENIES_TO_SLEEP());
        // A desktop shown at the size of the panel gets its own size back, for the genie that
        // goes on without the panel, before its lamp is let go. A genie put to sleep takes its
        // desktop with it.
        Optional<Thread> givingBack = keep ? view.letGoOfTheDesktop() : Optional.empty();
        app.quit(keep, givingBack, () -> {
            frame.dispose();
            System.exit(0);
        });
    }
}
