package dev.oillamp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/// Finds the model a session was given among the models pi offers, when pi names it differently.
///
/// Eden AI names each model twice. Its plain `GET /v3/models` lists one entry per endpoint, named
/// `provider/model` or `provider/vendor/model`, such as `mistral/mistral-small-latest` or
/// `tensorx/deepseek/deepseek-v4.1-flash-1017`. Its `?view=models` lists one entry per model, by
/// the name provider routing takes, such as `mistral-small-latest`, which Eden AI sends to one of
/// that model's endpoints. Which of the two pi offers depends on the version of pi-edenai in the
/// sandbox, and a program choosing a model, such as Genies, may have listed the other. The model
/// is the same either way, so the session uses it under pi's name rather than fail the run.
///
/// Only the same name is matched: an endpoint to the model name it ends in, a model's name to an
/// endpoint that ends in it, or the same name in other letter case. pi's list says no more than
/// the names, so a model the service routes under a name that one of its other models' endpoints
/// also ends in is taken for that model; the session's warning names both, for the user to see.
///
/// The session never picks one provider's endpoint in place of another's: that would be choosing
/// a provider for the user. A model's name is different: it is the service's own way to say "this
/// model, from whichever provider routing picks", which an endpoint chosen from the same model's
/// list may also be routed to. The warning says so.
///
/// Names that only look alike, such as `amazon/anthropic.claude-haiku-5-5` and `claude-haiku-5.5`,
/// are never used in place of one another, only suggested ([#similar]): providers name their
/// endpoints in their own ways, and a guess could be another model.
final class ModelNameMatchUtil {

    private ModelNameMatchUtil() {}

    /// How many similar names a failure suggests.
    static final int SUGGESTED = 3;
    /// How many leading characters a name must share with the wanted one to be suggested.
    private static final int SIMILAR_PREFIX = 4;

    /// The model among `offered` that is `wanted` under the name pi gives it, in this order of
    /// preference: the same name; the same name in other letter case; for an endpoint, the
    /// model's name; for a model's name, the first of its endpoints pi lists, one whose last
    /// part has the same letter case before one that does not.
    ///
    /// @param wanted  the model the session was given
    /// @param offered the models pi offers, in pi's order
    /// @return pi's name for the model; empty when pi does not offer it under any name
    static Optional<String> sameModel(String wanted, List<String> offered) {
        String name = wanted.strip();
        if (name.isEmpty()) return Optional.empty();
        if (offered.contains(name)) return Optional.of(name);
        Optional<String> otherCase = offered.stream().filter(name::equalsIgnoreCase).findFirst();
        if (otherCase.isPresent()) return otherCase;
        if (name.contains("/")) {
            String model = lastPart(name);
            if (offered.contains(model)) return Optional.of(model);
            return offered.stream().filter(model::equalsIgnoreCase).findFirst();
        }
        Optional<String> endpoint = offered.stream()
                .filter(it -> it.contains("/") && lastPart(it).equals(name)).findFirst();
        if (endpoint.isPresent()) return endpoint;
        return offered.stream().filter(it -> it.contains("/") && lastPart(it).equalsIgnoreCase(name)).findFirst();
    }

    /// Up to [#SUGGESTED] of `offered` whose names are most like `wanted`, the most alike first,
    /// for a failure to name. Names are compared in the [#plain] form of their last part: one
    /// that contains the other comes first, then the longer their shared beginning. Names that
    /// share fewer than four leading characters and do not contain one another are not
    /// suggested.
    static List<String> similar(String wanted, List<String> offered) {
        String model = plain(wanted.strip());
        record Scored(String name, int score, int order) {}
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < offered.size(); i++) {
            String other = plain(offered.get(i));
            int shared = sharedPrefix(model, other);
            boolean contained = !model.isEmpty() && !other.isEmpty() && (other.contains(model) || model.contains(other));
            int score = (contained ? 1_000 : 0) + shared;
            if (contained || shared >= SIMILAR_PREFIX) scored.add(new Scored(offered.get(i), score, i));
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order))
                .limit(SUGGESTED).map(Scored::name).toList();
    }

    /// What a failure adds to pi's reason when pi does not offer `wanted`: how many models pi
    /// does offer, and which of them are most like it, so that the name can be put right.
    ///
    /// @param provider the provider pi was asked for, such as `edenai`
    static String whatPiOffers(String wanted, List<String> offered, String provider) {
        if (offered.isEmpty()) return "pi offers no models from " + provider
                + "; is the model service reachable from the sandbox?";
        List<String> like = similar(wanted, offered);
        String count = "pi offers " + offered.size() + " model" + (offered.size() == 1 ? "" : "s") + " from " + provider;
        if (like.isEmpty()) return count + ", none of them like " + wanted;
        return count + "; the most alike: " + String.join(", ", like);
    }

    /// A name's last part as providers' spellings of it have in common, to compare: in lower
    /// case, without a vendor before a dot (`anthropic.claude-haiku-5-5`) or a version after a
    /// colon (`gpt-oss-120b-1:0`), and with `.` and `_` read as `-`.
    static String plain(String name) {
        return lastPart(name).toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z0-9]+\\.(?=[a-z])", "")
                .replaceFirst(":[0-9]+$", "")
                .replaceAll("[._]", "-");
    }

    /// The part of a name after its last `/`: the model's own name in an endpoint's id.
    static String lastPart(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static int sharedPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }
}
