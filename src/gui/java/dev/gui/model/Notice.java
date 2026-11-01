package dev.gui.model;

import java.util.Optional;

/// What Genies says in a genie's chat, rather than the genie or the user: that a moment was
/// saved, why the genie broke, that an answer was stopped. Kept without words, and said in the
/// language Genies speaks with [#words(Lang)], so the chat says it in another once the user picks
/// one. What oillamp, the model service or the computer said stays as they put it.
public sealed interface Notice {

    /// The genie's home was saved as a moment of its history, remembered by `message`, which
    /// may be empty.
    record Saved(String message) implements Notice {}

    /// A save saved nothing, or tidying up deleted nothing, as `note` says.
    record HistoryNote(History.Note note) implements Notice {}

    /// The genie broke, for `why`.
    record Broke(Genie.Broke why) implements Notice {}

    /// There is no model key to wake the genie with.
    record NoModelKey() implements Notice {}

    /// The genie could not be stopped, and oillamp said `reason`.
    record CouldNotBeStopped(String reason) implements Notice {}

    /// What the user decided about a request did not reach the genie's lamp, for `reason`.
    record DecisionDidNotReachTheLamp(String reason) implements Notice {}

    /// The conversation the user went to is gone.
    record ConversationGone() implements Notice {}

    /// The conversation of the question the user went to is gone.
    record QuestionsConversationGone() implements Notice {}

    /// A conversation could not be deleted, for `reason`.
    record ConversationNotDeleted(String reason) implements Notice {}

    /// What the user asked needs the genie awake, and it sleeps.
    record AsleepWakeItFirst() implements Notice {}

    /// The genie could not change to the model the settings name, and answers with `model`
    /// until it next wakes; oillamp said `reason`.
    record KeepsAnsweringWith(String model, String reason) implements Notice {}

    /// The file `name` the genie handed over was saved to `target`.
    record SavedFile(String name, String target) implements Notice {}

    /// The user stopped the genie before its answer was done.
    record Stopped() implements Notice {}

    /// The model is asked again, the `attempt`th time of `attempts`, after it failed for `why`;
    /// empty when the chat said why already.
    record TryingAgain(Optional<Notice> why, int attempt, int attempts) implements Notice {}

    /// The genie's harness could not take what the user sent, and said `reason`.
    record CouldNotTake(String reason) implements Notice {}

    /// The genie handed the user the file `file`, which the entry's title names.
    record HandedOver(Handout file) implements Notice {}

    /// The user gave the genie the file the entry's title names, which is in its `~/inbox`.
    record Gave() implements Notice {}

    /// oillamp could not reach the model service at `host`, for `why`, so the genie got no answer.
    record ModelServiceUnreachable(String host, String why) implements Notice {}

    /// The connection to the model service broke off before an answer came.
    record ConnectionBrokeOff() implements Notice {}

    /// The model service refused with the status `code`, and `said` why. `model` is the model
    /// the request was for; empty when not known.
    record ModelServiceRefused(int code, String model, String said) implements Notice {}

    /// The model could not answer, and pi got `said`, which Genies could not make out.
    record ModelCouldNotAnswer(String said) implements Notice {}

    /// Anything else, as oillamp, the model service or the computer `said` it, or as Genies
    /// worded it when it happened.
    record Said(String said) implements Notice {}

    /// What it says, in `lang`.
    default String words(Lang lang) {
        Lang.Chat words = lang.CHAT();
        return switch (this) {
            case Saved it -> words.SAVED_AS_A_MOMENT_OF_THE_HISTORY(it.message());
            case HistoryNote it -> it.note().words(lang);
            case Broke it -> it.why().words(lang);
            case NoModelKey _ -> words.THERE_IS_NO_MODEL_KEY();
            case CouldNotBeStopped it -> words.THE_GENIE_COULD_NOT_BE_STOPPED(it.reason());
            case DecisionDidNotReachTheLamp it -> words.THE_DECISION_DID_NOT_REACH_THE_GENIES_LAMP(it.reason());
            case ConversationGone _ -> words.THAT_CONVERSATION_IS_NOT_THERE_ANY_MORE();
            case QuestionsConversationGone _ -> words.THE_CONVERSATION_OF_THAT_QUESTION_IS_NOT_THERE_ANY_MORE();
            case ConversationNotDeleted it -> words.THE_CONVERSATION_COULD_NOT_BE_DELETED(it.reason());
            case AsleepWakeItFirst _ -> words.THE_GENIE_IS_ASLEEP_WAKE_IT_FIRST();
            case KeepsAnsweringWith it -> words.THE_GENIE_KEEPS_ANSWERING_WITH_UNTIL_IT_WAKES_AGAIN(it.model(), it.reason());
            case SavedFile it -> words.SAVED_TO(it.name(), it.target());
            case Stopped _ -> words.STOPPED_BEFORE_THE_ANSWER_WAS_DONE();
            case TryingAgain it -> it.why().map(why -> why.words(lang) + " ").orElse("")
                                   + words.TRYING_AGAIN(it.attempt(), it.attempts());
            case CouldNotTake it -> words.THE_GENIE_COULD_NOT_TAKE_THAT(it.reason());
            case HandedOver it -> words.HANDED_YOU_A_FILE(it.file().readableSize(lang));
            case Gave _ -> words.YOU_GAVE_THE_GENIE_THIS_FILE_IN_INBOX();
            case ModelServiceUnreachable it -> words.OILLAMP_COULD_NOT_REACH_THE_MODEL_SERVICE_AT(it.host(), it.why());
            case ConnectionBrokeOff _ -> words.THE_CONNECTION_TO_THE_MODEL_SERVICE_BROKE_OFF_BEFORE_AN_ANSWER_CAME();
            case ModelServiceRefused it -> sentence(words.WITH_STATUS(who(it, words), it.code(), it.said()));
            case ModelCouldNotAnswer it -> sentence(words.THE_MODEL_COULD_NOT_ANSWER(it.said()));
            case Said it -> it.said();
        };
    }

    /// Who refused, and how, as the status `code` says.
    private static String who(ModelServiceRefused refused, Lang.Chat words) {
        int code = refused.code();
        return code == 401 || code == 403 ? words.THE_MODEL_SERVICE_REFUSED_THE_KEY()
             : code == 429 ? words.THE_MODEL_SERVICE_TAKES_NO_MORE_REQUESTS_FOR_NOW_OR_THE_KEYS_CREDIT_RAN_OUT()
             : code >= 500 ? words.THE_MODEL_SERVICE_HAD_TROUBLE_OF_ITS_OWN()
             : refused.model().isBlank() ? words.THE_MODEL_SERVICE_REFUSED_THE_REQUEST()
             : words.REFUSED_THE_REQUEST(refused.model().strip());
    }

    /// `text` ending as a sentence, so that more can follow it.
    private static String sentence(String text) {
        return text.matches("(?s).*[.!?。！？]") ? text : text + ".";
    }
}
