package dev.oillamp;

import java.time.Duration;
import java.util.Optional;

import dev.lamp.LampProblem;

import sprouts.Tuple;

/// The problem codes of supervised host access.
///
/// A refusal is not a bug and not a crash: it is the feature working. Each one says what the
/// agent asked for, why it did not happen, and what the agent can do instead, so that the agent
/// hears a refusal it can act on rather than an error.
final class HostAccessProblems {

    private HostAccessProblems() {}

    /// The code of [#commandsUnchecked], which the console prints louder than any other warning.
    static final String COMMANDS_UNCHECKED = "OIL-HOSTACCESS-016";

    /// The agent asked for a tool this lamp does not have.
    static LampProblem noSuchTool(String tool) {
        return refusal("OIL-HOSTACCESS-001", "No such host tool",
                "the agent asked for \"" + tool + "\", which this lamp does not offer",
                "the agent can still work in the sandbox; reaching the host needs a tool the lamp has");
    }

    /// The arguments to a tool did not fit its shape.
    ///
    /// @param expected what did not fit, and what was expected instead, such as
    ///                 `path: a string that is not empty`
    static LampProblem badArguments(String tool, String expected) {
        return refusal("OIL-HOSTACCESS-002", "Host tool arguments do not fit",
                tool + " was called with arguments that do not fit it; expected " + expected,
                "a tool runs only when every argument it reads is there and is the kind it expects")
            .withEvidence(new LampProblem.Evidence.Value("expected", expected));
    }

    /// A path the tool names is outside the lamp's allowlists, or not one oillamp can judge.
    static LampProblem outsideTheAllowlists(String asked, String why) {
        return refusal("OIL-HOSTACCESS-003", "Path outside what this lamp may reach",
                "\"" + asked + "\" cannot be touched: " + why,
                "host_access.read_allowlist and change_allowlist are the outer bound, and oillamp checks them "
              + "before any model is asked, so a bad path is a mistake rather than something to judge")
            .withFix(LampProblem.Fix.of("use a path in the lamp's allowlists, or ask the user to add its folder to "
                    + "host_access.read_allowlist or change_allowlist"));
    }

    /// The checker said no.
    static LampProblem denied(String tool, String reason) {
        return refusal("OIL-HOSTACCESS-004", "The checker refused a host request",
                tool + " was refused: " + reason,
                "every request outside the sandbox is judged against the user's checker instructions, "
              + "and a refusal is final for that request");
    }

    /// The checker's answer could not be read as a verdict.
    static LampProblem unreadableVerdict(String answered) {
        return refusal("OIL-HOSTACCESS-005", "The checker's answer was not a verdict",
                "the checker answered with something that is not one JSON verdict, so the request was refused",
                "an answer oillamp cannot read is not permission")
            .withEvidence(new LampProblem.Evidence.Excerpt("the checker's answer", shorten(answered)));
    }

    /// The checker could not be asked at all.
    static LampProblem checkerUnreachable(String why) {
        return refusal("OIL-HOSTACCESS-006", "The checker could not be asked",
                "oillamp could not ask the model that judges host requests: " + why,
                "with no verdict there is no permission, so the request was refused and nothing on "
              + "the host changed");
    }

    /// The checker did not answer in time.
    static LampProblem checkerTooSlow(Duration took) {
        return refusal("OIL-HOSTACCESS-007", "The checker took too long",
                "the checker did not answer within " + took.toSeconds() + " seconds",
                "a request the checker does not answer in time is refused rather than done unjudged");
    }

    /// The user was asked, and did not answer.
    static LampProblem userDidNotAnswer(Duration waited) {
        return refusal("OIL-HOSTACCESS-008", "The user did not answer",
                "the user was asked about this request and did not answer within " + waited.toSeconds() + " seconds",
                "no answer is not permission, so the request was refused");
    }

    /// The user answered no.
    static LampProblem userSaidNo(String tool) {
        return refusal("OIL-HOSTACCESS-009", "The user refused",
                "the user was asked about " + tool + " and said no",
                "a request the user refuses does not happen, whatever the checker thought of it");
    }

    /// The request needed the person, and the person could not be asked.
    static LampProblem userNotAsked(String tool, String why) {
        return refusal("OIL-HOSTACCESS-013", "The user could not be asked",
                tool + " needs the user's yes, and the user could not be asked: " + why,
                "a request that needs a person does not happen without one");
    }

    /// The host work itself failed, after it was allowed.
    static LampProblem failed(String tool, String why) {
        return refusal("OIL-HOSTACCESS-010", "The host could not do what was asked",
                tool + " was allowed, but it failed: " + why,
                "the request was judged and allowed; the failure is the host's, and the agent can "
              + "try another way");
    }

    /// The checker was on, but there is no key for the model service.
    static LampProblem checkerHasNoKey(String variable) {
        return new LampProblem(new LampProblem.Code("OIL-HOSTACCESS-012"), LampProblem.Severity.WARNING,
                "Host access is on, but the checker has no key",
                "the variable " + variable + " is not set where oillamp was started, so no model can be asked",
                "every request from the agent to the host will be refused until a session starts with the key",
                Tuple.of(LampProblem.Evidence.class),
                Tuple.of(LampProblem.Fix.of("set " + variable + " in the environment you start oillamp from, then start the session again")),
                Optional.empty());
    }

    /// An application gave host-access rules for a lamp whose file has the feature off.
    static LampProblem grantIgnored() {
        return new LampProblem(new LampProblem.Code("OIL-HOSTACCESS-014"), LampProblem.Severity.WARNING,
                "Host access is off in this lamp",
                "the application gave checker instructions for host access, but this lamp's oillamp.toml does not turn it on, so they were not used",
                "an application sets the rules; only the lamp's own file opens the way to the host",
                Tuple.of(LampProblem.Evidence.class),
                Tuple.of(LampProblem.Fix.of("set may under [host_access] in the lamp's oillamp.toml to what the agent may do, such as \"read\"")),
                Optional.empty());
    }

    /// Host access is on, but nothing says which model judges: the lamp names no `checker_model`,
    /// and the session was given no model for the agent either.
    static LampProblem noModelToJudgeWith() {
        return new LampProblem(new LampProblem.Code("OIL-HOSTACCESS-015"), LampProblem.Severity.ERROR,
                "Host access has no model to judge with",
                "this lamp's oillamp.toml turns host access on with a checker among its guards, but names no checker_model, and the session was started without --model",
                "every request to the host is judged by a model before it happens; without one, the way to the host stays closed",
                Tuple.of(LampProblem.Evidence.class),
                Tuple.of(LampProblem.Fix.of("start the session with --model <id>, so that requests are judged by the model the agent answers with"),
                         LampProblem.Fix.of("or set checker_model under [host_access] in the lamp's oillamp.toml")),
                Optional.empty());
    }

    /// The lamp lets the agent run commands on the host with nothing but the allowlists in the
    /// way: `may = "read_change_run"` with `guards = "allowlists"`. It is allowed, because the
    /// machine is the user's, and said as loudly as anything oillamp says, every time the lamp's
    /// settings are read for a session, checked or written.
    static LampProblem commandsUnchecked() {
        return new LampProblem(new LampProblem.Code(COMMANDS_UNCHECKED), LampProblem.Severity.WARNING,
                "The agent may run any command on this machine, unchecked",
                "host_access.may is \"read_change_run\" and host_access.guards is \"allowlists\": every command the "
              + "agent asks for runs on this machine, as you, with no checker model judging it and nobody asked first",
                "a command can do anything you can, anywhere: read your keys and send them away, change or delete your "
              + "files. The allowlists bound only the folder a command starts in, not what it does from there",
                Tuple.of(LampProblem.Evidence.class),
                Tuple.of(LampProblem.Fix.of("set guards = \"allowlists_checker\" under [host_access], so that a checker model "
                                + "judges every command first, or \"allowlists_checker_user\" to be asked too"),
                         LampProblem.Fix.of("or set may = \"read_change\", so that the agent runs no commands here")),
                Optional.empty());
    }

    /// The line the sandbox sent is not a request at all.
    static LampProblem notARequest() {
        return refusal("OIL-HOSTACCESS-011", "Not a host request",
                "the host tools socket received a line that is not one JSON-RPC message",
                "oillamp reads only its own protocol there; anything else is ignored before a model "
              + "is asked or a path is touched");
    }

    private static LampProblem refusal(String code, String title, String whatHappened, String whyItMatters) {
        return new LampProblem(new LampProblem.Code(code), LampProblem.Severity.WARNING, title, whatHappened,
                whyItMatters, Tuple.of(LampProblem.Evidence.class), Tuple.of(LampProblem.Fix.class), Optional.empty());
    }

    private static String shorten(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= 400 ? single : single.substring(0, 400) + "…";
    }
}
