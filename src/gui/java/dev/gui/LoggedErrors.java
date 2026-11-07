package dev.gui;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/// Takes what Genies, SwingTree, Sprouts and oillamp's `dev.lamp` log through slf4j: their errors
/// to the [ErrorLog], their warnings to the error output, and all of it, as far as it keeps that
/// level, to the [AppLog].
///
/// SwingTree catches an exception thrown in an event handler, such as a button's, and logs it
/// instead of letting it through. Without this, Genies would never hear of it.
///
/// Chosen by its name in the system property `slf4j.provider`, which [Main#main] sets before
/// anything logs. It is not listed as a service, so oillamp's engine, which runs from the same
/// classpath in a process of its own, logs through slf4j-simple to its error output instead. A
/// lamp logs what the engine wrote there, which brings it here too.
public final class LoggedErrors implements SLF4JServiceProvider {

    private final ILoggerFactory loggers = Logger::new;
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter context = new NOPMDCAdapter();

    @Override public ILoggerFactory getLoggerFactory() { return loggers; }
    @Override public IMarkerFactory getMarkerFactory() { return markers; }
    @Override public MDCAdapter getMDCAdapter() { return context; }
    @Override public String getRequestedApiVersion() { return "2.0.99"; }
    @Override public void initialize() { }

    private static final class Logger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        Logger(String name) {
            this.name = name;
        }

        @Override public boolean isTraceEnabled() { return AppLog.shared().keeps(AppLog.Level.TRACE); }
        @Override public boolean isDebugEnabled() { return AppLog.shared().keeps(AppLog.Level.DEBUG); }
        @Override public boolean isInfoEnabled() { return AppLog.shared().keeps(AppLog.Level.INFO); }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }

        @Override protected @Nullable String getFullyQualifiedCallerName() { return null; }

        @Override protected void handleNormalizedLoggingCall(
            final Level level,
            final @Nullable Marker marker,
            final String pattern,
            final Object @Nullable [] arguments,
            final @Nullable Throwable thrown
        ) {
            String message = MessageFormatter.basicArrayFormat(pattern, arguments);
            // The error log hands its errors to the app log itself.
            if (level == Level.ERROR && Thread.getDefaultUncaughtExceptionHandler() instanceof ErrorLog log) {
                log.record(name + ", which logged: " + message,
                           thrown != null ? thrown : new IllegalStateException(message));
                return;
            }
            // With the stack trace, which says where it happened. The app log cuts it to its first
            // frames; the error output keeps it whole.
            String said = message + (thrown == null ? "" : ": " + stackTrace(thrown));
            AppLog.shared().add(AppLog.Level.valueOf(level.name()), name, said);
            if (level == Level.ERROR || level == Level.WARN)
                System.err.println("genies: " + level + " from " + name + ": " + said);
        }

        private static String stackTrace(Throwable thrown) {
            StringWriter trace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            return trace.toString().strip();
        }
    }
}
