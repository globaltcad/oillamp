package dev.oillamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/// The application that started an embedded session, as oillamp's standard input tells of it,
/// from the start of `oillamp at --embedded` to the session's end.
///
/// The application holds that input open for as long as it wants the session. When it closes it,
/// or dies, and the operating system closes it on its behalf, reading reaches the end: the
/// application is gone. A broken pipe means the same. Before that, it may write the line
/// [#LEAVE_RUNNING] to leave the session running without it; from then on, the end of the input
/// means nothing. Anything else it writes is ignored.
///
/// While the sandbox starts, which may take many minutes while its image is built, an application
/// that goes away ends that start, as Ctrl-C does in a terminal: [#whileStarting] names the thread
/// that starts it, which is interrupted then. Once the session runs, the [Supervisor] hears it
/// instead, through [#handOver].
final class ApplicationWatch {

    /// What an application writes on oillamp's standard input to leave its session running.
    static final String LEAVE_RUNNING = "leave-running";

    /// Who hears of the application once the session runs.
    interface Listener {
        /// The application left the session running.
        void leftRunning();

        /// The application is gone, without leaving the session running.
        void gone();
    }

    private final Object lock = new Object();
    private boolean leftRunning;
    private boolean gone;
    /// The thread that starts the sandbox, while it does.
    private Optional<Thread> starting = Optional.empty();
    /// Whether that thread was interrupted because the application went away.
    private boolean interrupted;
    private Optional<Listener> listener = Optional.empty();

    private ApplicationWatch() {}

    /// Watches the application from now on, on a thread of its own.
    static ApplicationWatch start(Machine machine) {
        ApplicationWatch watch = new ApplicationWatch();
        Thread.ofVirtual().name("oillamp-application-watch").start(() -> watch.read(machine));
        return watch;
    }

    private void read(Machine machine) {
        try (var input = new BufferedReader(new InputStreamReader(machine.standardInput(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (!line.strip().equals(LEAVE_RUNNING)) continue;
                Optional<Listener> told;
                synchronized (lock) {
                    leftRunning = true;
                    told = listener;
                }
                told.ifPresent(Listener::leftRunning);
                return;
            }
        } catch (IOException closed) {
            // A broken pipe means the same as a closed one: the application is gone.
        }
        Optional<Listener> told;
        synchronized (lock) {
            gone = true;
            told = listener;
            starting.ifPresent(thread -> {
                interrupted = true;
                thread.interrupt();
            });
        }
        told.ifPresent(Listener::gone);
    }

    /// From now until [#started], the application going away interrupts `thread`, which starts
    /// the sandbox.
    ///
    /// @return false, and nothing is watched, when the application is gone already
    boolean whileStarting(Thread thread) {
        synchronized (lock) {
            if (gone) return false;
            starting = Optional.of(thread);
            return true;
        }
    }

    /// The sandbox started, or did not: the starting thread is no longer interrupted from here,
    /// and an interrupt it was sent but did not notice is cleared, so that it does not end what
    /// comes next. Called on that thread.
    ///
    /// @return whether the application went away while the sandbox started
    boolean started() {
        synchronized (lock) {
            starting = Optional.empty();
            if (interrupted) Thread.interrupted();
            return gone;
        }
    }

    /// From now on, `listener` hears of the application, and at once of what it did already.
    void handOver(Listener listener) {
        boolean wasLeftRunning;
        boolean wasGone;
        synchronized (lock) {
            this.listener = Optional.of(listener);
            wasLeftRunning = leftRunning;
            wasGone = gone;
        }
        if (wasLeftRunning) listener.leftRunning();
        else if (wasGone) listener.gone();
    }
}
