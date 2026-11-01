package dev.gui;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.gui.genie.Shelf;
import dev.gui.model.Genie;
import dev.gui.model.Lang;
import dev.gui.view.GeniesView;

import com.formdev.flatlaf.FlatClientProperties;

import sprouts.From;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.components.JGlassPane;
import swingtree.dialogs.ConfirmAnswer;

/// Starts Genies: the app, [Genies#open], in a window of its own.
final class Main {

    void main() {
        // Before anything logs, so that SwingTree's errors reach the error log too.
        System.setProperty("slf4j.provider", LoggedErrors.class.getName());
        Shelf shelf = Shelf.standard(name -> Optional.ofNullable(System.getenv(name)));
        ErrorLog errors = new ErrorLog(shelf.errorLog());
        Thread.setDefaultUncaughtExceptionHandler(errors);
        SwingUtilities.invokeLater(() -> open(shelf, errors));
    }

    private static void open(Shelf shelf, ErrorLog errors) {
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
    }

    /// Asks the user what becomes of the genies that are awake, then puts them to sleep or leaves
    /// them running, before the window goes. Without an answer, nothing closes.
    private static void quit(Genies app, JFrame frame, Var<String> title, GeniesView view) {
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
