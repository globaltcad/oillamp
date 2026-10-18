package dev.oillamp;

import java.time.Duration;
import java.util.Optional;

import dev.lamp.Problem;

import sprouts.Tuple;

/// The problem codes of supervised host access.
///
/// A refusal is not a bug and not a crash: it is the feature working. Each one says what the
/// agent asked for, why it did not happen, and what the agent can do instead, so that the agent
/// hears a refusal it can act on rather than an error.
final class HostAccessProblems {

    private HostAccessProblems() {}

    /// The agent asked for a tool this lamp does not have.
    static Problem noSuchTool(String tool) {
        return refusal("OIL-HOSTACCESS-001", "No such host tool",
                "the agent asked for \"" + tool + "\", which this lamp does not offer",
                "the agent can still work in the sandbox; reaching the host needs a tool the lamp has");
    }

    /// The arguments to a tool did not fit its shape.
    static Problem badArguments(String tool, String expected) {
        return refusal("OIL-HOSTACCESS-002", "Host tool arguments do not fit",
                tool + " was called without what it needs",
                "a tool runs only when every argument it reads is there and is the kind it expects")
            .withEvidence(new Problem.Evidence.Value("expected", expected));
    }

    /// A path the tool names is outside the lamp's roots.
    static Problem outsideTheRoots(String asked, String why) {
        return refusal("OIL-HOSTACCESS-003", "Path outside what this lamp may reach",
                "\"" + asked + "\" cannot be touched: " + why,
                "host_access.roots is the outer bound, and oillamp checks it before any model is "
              + "asked, so a bad path is a mistake rather than something to judge")
            .withFix(Problem.Fix.of("use a path under the lamp's roots, or ask the user to add it to host_access.roots"));
    }

    /// The checker said no.
    static Problem denied(String tool, String reason) {
        return refusal("OIL-HOSTACCESS-004", "The checker refused a host request",
                tool + " was refused: " + reason,
                "every request outside the sandbox is judged against the user's requirements, "
              + "and a refusal is final for that request");
    }

    /// The checker's answer could not be read as a verdict.
    static Problem unreadableVerdict(String answered) {
        return refusal("OIL-HOSTACCESS-005", "The checker's answer was not a verdict",
                "the checker answered with something that is not one JSON verdict, so the request was refused",
                "an answer oillamp cannot read is not permission")
            .withEvidence(new Problem.Evidence.Excerpt("the checker's answer", shorten(answered)));
    }

    /// The checker could not be asked at all.
    static Problem checkerUnreachable(String why) {
        return refusal("OIL-HOSTACCESS-006", "The checker could not be asked",
                "oillamp could not ask the model that judges host requests: " + why,
                "with no verdict there is no permission, so the request was refused and nothing on "
              + "the host changed");
    }

    /// The checker did not answer in time.
    static Problem checkerTooSlow(Duration took) {
        return refusal("OIL-HOSTACCESS-007", "The checker took too long",
                "the checker did not answer within " + took.toSeconds() + " seconds",
                "a request the checker does not answer in time is refused rather than done unjudged");
    }

    /// The user was asked, and did not answer.
    static Problem userDidNotAnswer(Duration waited) {
        return refusal("OIL-HOSTACCESS-008", "The user did not answer",
                "the user was asked about this request and did not answer within " + waited.toSeconds() + " seconds",
                "no answer is not permission, so the request was refused");
    }

    /// The user answered no.
    static Problem userSaidNo(String tool) {
        return refusal("OIL-HOSTACCESS-009", "The user refused",
                "the user was asked about " + tool + " and said no",
                "a request the user refuses does not happen, whatever the checker thought of it");
    }

    /// The request needed the person, and the person could not be asked.
    static Problem userNotAsked(String tool, String why) {
        return refusal("OIL-HOSTACCESS-013", "The user could not be asked",
                tool + " needs the user's yes, and the user could not be asked: " + why,
                "a request that needs a person does not happen without one");
    }

    /// The host work itself failed, after it was allowed.
    static Problem failed(String tool, String why) {
        return refusal("OIL-HOSTACCESS-010", "The host could not do what was asked",
                tool + " was allowed, but it failed: " + why,
                "the request was judged and allowed; the failure is the host's, and the agent can "
              + "try another way");
    }

    /// The checker was on, but there is no key for the model service.
    static Problem checkerHasNoKey(String variable) {
        return new Problem(new Problem.Code("OIL-HOSTACCESS-012"), Problem.Severity.WARNING,
                "Host access is on, but the checker has no key",
                "the variable " + variable + " is not set where oillamp was started, so no model can be asked",
                "every request from the agent to the host will be refused until a session starts with the key",
                Tuple.of(Problem.Evidence.class),
                Tuple.of(Problem.Fix.of("set " + variable + " in the environment you start oillamp from, then start the session again")),
                Optional.empty());
    }

    /// An application gave host-access rules for a lamp whose file has the feature off.
    static Problem grantIgnored() {
        return new Problem(new Problem.Code("OIL-HOSTACCESS-014"), Problem.Severity.WARNING,
                "Host access is off in this lamp",
                "the application gave requirements for host access, but this lamp's oillamp.toml does not turn it on, so they were not used",
                "an application sets the rules; only the lamp's own file opens the way to the host",
                Tuple.of(Problem.Evidence.class),
                Tuple.of(Problem.Fix.of("set enabled = true under [host_access] in the lamp's oillamp.toml, with its own requirements")),
                Optional.empty());
    }

    /// Host access is on, but nothing says which model judges: the lamp names no `checker_model`,
    /// and the session was given no model for the agent either.
    static Problem noModelToJudgeWith() {
        return new Problem(new Problem.Code("OIL-HOSTACCESS-015"), Problem.Severity.ERROR,
                "Host access has no model to judge with",
                "this lamp's oillamp.toml turns host access on, but names no checker_model, and the session was started without --model",
                "every request to the host is judged by a model before it happens; without one, the way to the host stays closed",
                Tuple.of(Problem.Evidence.class),
                Tuple.of(Problem.Fix.of("start the session with --model <id>, so that requests are judged by the model the agent answers with"),
                         Problem.Fix.of("or set checker_model under [host_access] in the lamp's oillamp.toml")),
                Optional.empty());
    }

    /// The line the sandbox sent is not a request at all.
    static Problem notARequest() {
        return refusal("OIL-HOSTACCESS-011", "Not a host request",
                "the host tools socket received something that is not one JSON request",
                "oillamp reads only its own protocol there; anything else is ignored before a model "
              + "is asked or a path is touched");
    }

    private static Problem refusal(String code, String title, String whatHappened, String whyItMatters) {
        return new Problem(new Problem.Code(code), Problem.Severity.WARNING, title, whatHappened,
                whyItMatters, Tuple.of(Problem.Evidence.class), Tuple.of(Problem.Fix.class), Optional.empty());
    }

    private static String shorten(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= 400 ? single : single.substring(0, 400) + "…";
    }
}
