package dev.oillamp;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.lamp.LampProblem;

import sprouts.Tuple;

/// The result of something that can fail: a value with any warnings, or a list of [LampProblem]s.
/// Expected failures are returned this way instead of thrown.
///
/// [#combine] and [#all] collect the problems of independent checks, so a user with
/// three mistakes in their `oillamp.toml` sees all three in one run.
sealed interface Result<T> {

    record Ok<T>(T value, Tuple<LampProblem> warnings) implements Result<T> {}

    record Err<T>(Tuple<LampProblem> problems) implements Result<T> {
        public Err {
            if (problems.isEmpty())
                throw new IllegalArgumentException("A failed Result must say what went wrong");
        }
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value, Tuple.of(LampProblem.class));
    }

    static <T> Result<T> ok(T value, Tuple<LampProblem> warnings) {
        return new Ok<>(value, warnings);
    }

    static <T> Result<T> err(LampProblem problem) {
        return new Err<>(Tuple.of(LampProblem.class, problem));
    }

    static <T> Result<T> err(Tuple<LampProblem> problems) {
        return new Err<>(problems);
    }

    default boolean isOk() { return this instanceof Ok<T>; }

    default Tuple<LampProblem> warnings() {
        return switch (this) {
            case Ok<T> ok   -> ok.warnings();
            case Err<T> ignored -> Tuple.of(LampProblem.class);
        };
    }

    /// Everything worth telling the user about, whether or not the result succeeded.
    default Tuple<LampProblem> problems() {
        return switch (this) {
            case Ok<T> ok   -> ok.warnings();
            case Err<T> err -> err.problems();
        };
    }

    default <U> Result<U> map(Function<T, U> f) {
        return switch (this) {
            case Ok<T> ok   -> new Ok<>(f.apply(ok.value()), ok.warnings());
            case Err<T> err -> new Err<>(err.problems());
        };
    }

    /// Chains a dependent step, carrying accumulated warnings forward.
    default <U> Result<U> flatMap(Function<T, Result<U>> f) {
        return switch (this) {
            case Err<T> err -> new Err<>(err.problems());
            case Ok<T> ok -> switch (f.apply(ok.value())) {
                case Ok<U> next  -> new Ok<>(next.value(), ok.warnings().addAll(next.warnings()));
                case Err<U> next -> new Err<>(next.problems());
            };
        };
    }

    /// Adds a warning without changing the outcome.
    default Result<T> warn(LampProblem warning) {
        return switch (this) {
            case Ok<T> ok   -> new Ok<>(ok.value(), ok.warnings().add(warning));
            case Err<T> err -> new Err<>(err.problems().add(warning));
        };
    }

    /// Combines two _independent_ results, collecting the problems of both.
    /// This is what makes "all your config errors at once" possible.
    static <A, B, C> Result<C> combine(Result<A> a, Result<B> b, BiFunction<A, B, C> f) {
        if (a instanceof Ok<A> okA && b instanceof Ok<B> okB)
            return new Ok<>(f.apply(okA.value(), okB.value()), okA.warnings().addAll(okB.warnings()));
        return new Err<>(a.problems().addAll(b.problems()).retainIf(LampProblem::isError));
    }

    /// Turns a tuple of independent results into a result of a tuple, collecting every problem.
    static <T> Result<Tuple<T>> all(Class<T> type, Tuple<Result<T>> results) {
        Tuple<T> values = Tuple.of(type);
        Tuple<LampProblem> failures = Tuple.of(LampProblem.class);
        Tuple<LampProblem> warnings = Tuple.of(LampProblem.class);
        for (Result<T> each : results) {
            switch (each) {
                case Ok<T> ok   -> { values = values.add(ok.value()); warnings = warnings.addAll(ok.warnings()); }
                case Err<T> err -> failures = failures.addAll(err.problems());
            }
        }
        return failures.isEmpty() ? new Ok<>(values, warnings) : new Err<>(failures.addAll(warnings));
    }

    /// For tests and for the top of the shell, where a failure is a bug.
    /// The value, or what `onError` makes of the problems.
    default T orElseGet(Function<Tuple<LampProblem>, T> onError) {
        return switch (this) {
            case Ok<T> ok -> ok.value();
            case Err<T> err -> onError.apply(err.problems());
        };
    }

    default T orElseThrow(Supplier<String> context) {
        return switch (this) {
            case Ok<T> ok   -> ok.value();
            case Err<T> err -> throw new IllegalStateException(
                    context.get() + ": " + err.problems().first().code() + " " + err.problems().first().title());
        };
    }
}
