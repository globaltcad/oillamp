package dev.oillamp;

import java.time.Duration;

/// Something the session asks the person, who answers yes or no.
///
/// A question arrives on the session's event queue, and [SessionMachine] decides whether it can
/// be asked: one at a time, and only while the session runs. The supervisor shows it to the
/// person as a public event, and the person answers by its [#text()], which is also how the
/// supervisor tells waiting questions apart.
sealed interface Question {

    /// How long the person has to answer. No answer is a no.
    Duration PATIENCE = Duration.ofSeconds(60);

    /// What the person is shown, and answers by.
    String text();

    /// Where the session's remark about the answer belongs, such as `host access`.
    default String area() {
        return switch (this) {
            case HostRequest ignored -> "host access";
        };
    }

    /// A host request the checker allowed that the person must allow too.
    ///
    /// @param run  the run the request came from, or empty when the agent asked outside one
    /// @param tool the tool, such as `host_bash`
    /// @param what what is about to happen, in words, and where
    record HostRequest(String run, String tool, String what) implements Question {
        @Override public String text() { return what; }
    }
}
