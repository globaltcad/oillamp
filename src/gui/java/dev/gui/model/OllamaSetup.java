package dev.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import sprouts.Tuple;

/// Ollama on this computer, as Genies found it, and Genies setting it up: the simple way to give
/// the genies a model, which the settings offer first.
///
/// Setting up goes through the steps that are left, in order: install Ollama, start it, download
/// the model, prepare it for genies, and try it. Then the genies use it.
///
/// @param found    what Genies found when it last looked
/// @param wanted   the model the user wants, as Ollama names it, such as `qwen3:8b`; at first the
///                 one Genies suggests for this computer
/// @param step     what Genies does now
/// @param progress how far the step is, from 0 to 1
/// @param says     what happens, such as how far a download is, or why a step failed; empty when
///                 there is nothing to say
/// @param library  what Genies learned from ollama.com: the models it offers, and the newest Ollama
public record OllamaSetup(Found found, String wanted, Step step, double progress, Optional<Says> says, OllamaLibrary library) {

    /// What Genies found on this computer.
    ///
    /// @param installed where the `ollama` program is, or empty when it is not installed
    /// @param version   the version of the Ollama answering on this computer, or empty when none answers
    /// @param models    the models that Ollama has, by name
    /// @param hardware  what the computer has for running a model
    /// @param ours      whether the `ollama` program is the one Genies installed, which Genies can
    ///                  update; one installed otherwise is updated the way it was installed
    public record Found(String installed, String version, Tuple<String> models, Hardware hardware, boolean ours) {
        public static final Found NOTHING = new Found("", "", Tuple.of(String.class), Hardware.UNKNOWN, false);

        public boolean isInstalled() { return !installed.isEmpty(); }

        public boolean isRunning() { return !version.isEmpty(); }

        /// Whether Ollama has `model`. A name without a tag is the model's `latest`.
        public boolean has(String model) {
            String name = model.strip();
            return models.contains(name) || (!name.contains(":") && models.contains(name + ":latest"));
        }
    }

    public enum Step {
        /// Genies looks at what the computer has, as it does when it starts.
        LOOKING,
        /// Nothing happens.
        IDLE,
        INSTALLING, STARTING, DOWNLOADING, PREPARING, TRYING,
        /// Genies stops the Ollama it installed, installs the newest, and starts that.
        UPDATING,
        /// The last step failed, and [#says] why.
        FAILED;

        /// Whether Genies is setting Ollama up.
        public boolean isBusy() { return this != LOOKING && this != IDLE && this != FAILED; }
    }

    /// What happens while Genies sets Ollama up, and how it ended when it was stopped or failed.
    /// Said in the language Genies speaks with [#words(Lang)].
    public sealed interface Says {
        /// Genies looks at which steps are left.
        record LookingAtWhatIsLeft() implements Says {}
        /// Downloading Ollama, `done` bytes of `total` so far; `total` is 0 while unknown.
        record DownloadingOllama(long done, long total) implements Says {}
        /// Downloading what Ollama needs for AMD graphics cards, as [DownloadingOllama].
        record DownloadingAmdSupport(long done, long total) implements Says {}
        /// Starting Ollama.
        record StartingOllama() implements Says {}
        /// Stopping Ollama, to update it.
        record StoppingOllama() implements Says {}
        /// Downloading the model `model`, as [DownloadingOllama].
        record DownloadingModel(String model, long done, long total) implements Says {}
        /// Making Genies' version of the model `model`.
        record Preparing(String model) implements Says {}
        /// Asking Genies' version of the model `model` its first answer, which loads it.
        record Trying(String model) implements Says {}
        /// The user stopped setting up.
        record Stopped() implements Says {}
        /// A step failed, as `failure` says.
        record Failed(Failure failure) implements Says {}

        /// What happens, in `lang`.
        default String words(Lang lang) {
            Lang.SettingsPage words = lang.SETTINGS_PAGE();
            return switch (this) {
                case LookingAtWhatIsLeft _ -> words.LOOKING_AT_WHAT_IS_LEFT_TO_DO();
                case DownloadingOllama it -> it.done() == 0 && it.total() == 0 ? words.DOWNLOADING_OLLAMA()
                    : words.DOWNLOADING_OLLAMA_SO_FAR(amount(it.done(), it.total(), words));
                case DownloadingAmdSupport it -> words.DOWNLOADING_WHAT_OLLAMA_NEEDS_FOR_AMD_GRAPHICS_CARDS(amount(it.done(), it.total(), words));
                case StartingOllama _ -> words.STARTING_OLLAMA();
                case StoppingOllama _ -> words.STOPPING_OLLAMA_TO_UPDATE_IT();
                case DownloadingModel it -> it.done() == 0 && it.total() == 0 ? words.DOWNLOADING(it.model())
                    : words.DOWNLOADING_SO_FAR(it.model(), amount(it.done(), it.total(), words));
                case Preparing it -> words.PREPARING_FOR_GENIES(it.model());
                case Trying it -> words.TRYING_ITS_FIRST_ANSWER_LOADS_IT_WHICH_CAN_TAKE_A_FEW_MINUTES(it.model());
                case Stopped _ -> words.STOPPED_WHAT_WAS_DOWNLOADED_OF_A_MODEL_IS_KEPT();
                case Failed it -> it.failure().words(lang);
            };
        }

        /// How much of a download arrived, such as "12 MB of 1.4 GB", or "12 MB" while how much
        /// there is to download is unknown.
        private static String amount(long done, long total, Lang.SettingsPage words) {
            return total > 0 ? words.AMOUNT_OF(amount(done), amount(total)) : amount(done);
        }

        /// `bytes` in MB, or in GB from one GB on, such as "640 MB" or "1.4 GB".
        private static String amount(long bytes) {
            return bytes < 1L << 30 ? (bytes >> 20) + " MB" : String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
    }

    /// Why a step of setting Ollama up failed. Said in the language Genies speaks with
    /// [#words(Lang)]; what Ollama, ollama.com or the computer said stays as they put it.
    public sealed interface Failure {
        /// There is no `ollama` program to start.
        record NotInstalled() implements Failure {}
        /// Ollama did not answer within `seconds` of starting; what it said is in the file `log`.
        record DidNotStart(long seconds, String log) implements Failure {}
        /// Ollama knows no model called `model`.
        record NoSuchModel(String model) implements Failure {}
        /// Ollama could not download `model`, and `said` why.
        record CouldNotDownload(String model, String said) implements Failure {}
        /// Ollama stopped downloading `model` without saying why.
        record StoppedDownloading(String model) implements Failure {}
        /// `model` cannot use tools, as a genie must.
        record CannotUseTools(String model) implements Failure {}
        /// ollama.com answered `status` for the archive at `archive`.
        record ArchiveRefused(int status, String archive) implements Failure {}
        /// Downloading Ollama broke off, for `reason`.
        record DownloadFailed(String reason) implements Failure {}
        /// Downloading what Ollama needs for AMD graphics cards broke off, for `reason`.
        record AmdDownloadFailed(String reason) implements Failure {}
        /// Unpacking Ollama failed, and `tar` said why in `reason`.
        record UnpackingFailed(String reason) implements Failure {}
        /// Ollama answered `status` without saying why.
        record Answered(int status) implements Failure {}
        /// Ollama refused, and `said` why.
        record Said(String said) implements Failure {}
        /// Nothing answers at Ollama's address on this computer.
        record NotAnswering() implements Failure {}
        /// An Ollama still answers after Genies stopped its own, to update it: another Ollama,
        /// which Genies did not install, serves this computer.
        record AnotherOllamaAnswers() implements Failure {}
        /// After updating, Ollama still says it is the version `version`.
        record NotUpdated(String version) implements Failure {}
        /// Anything else, as the computer `said` it.
        record Other(String said) implements Failure {}

        /// Why it failed, in `lang`.
        default String words(Lang lang) {
            Lang.SettingsPage words = lang.SETTINGS_PAGE();
            return switch (this) {
                case NotInstalled _ -> words.OLLAMA_IS_NOT_INSTALLED();
                case DidNotStart it -> words.OLLAMA_DID_NOT_START_WITHIN(it.seconds(), it.log());
                case NoSuchModel it -> words.OLLAMA_HAS_NO_MODEL_CALLED(it.model());
                case CouldNotDownload it -> words.OLLAMA_COULD_NOT_DOWNLOAD(it.model(), it.said());
                case StoppedDownloading it -> words.OLLAMA_STOPPED_DOWNLOADING_WITHOUT_SAYING_WHY(it.model());
                case CannotUseTools it -> words.CANNOT_USE_TOOLS_SO_A_GENIE_COULD_NOT_WORK_WITH_IT(it.model());
                case ArchiveRefused it -> words.OLLAMA_COM_ANSWERED_FOR(it.status(), it.archive());
                case DownloadFailed it -> words.DOWNLOADING_OLLAMA_FAILED(it.reason());
                case AmdDownloadFailed it -> words.DOWNLOADING_WHAT_OLLAMA_NEEDS_FOR_AMD_GRAPHICS_CARDS_FAILED(it.reason());
                case UnpackingFailed it -> words.UNPACKING_OLLAMA_FAILED(it.reason());
                case Answered it -> words.OLLAMA_ANSWERED(it.status());
                case Said it -> it.said();
                case NotAnswering _ -> words.OLLAMA_DOES_NOT_ANSWER_ON_THIS_COMPUTER();
                case AnotherOllamaAnswers _ -> words.ANOTHER_OLLAMA_ANSWERS_ON_THIS_COMPUTER_UPDATE_IT_THE_WAY_IT_WAS_INSTALLED();
                case NotUpdated it -> words.OLLAMA_STILL_SAYS_IT_IS_VERSION(it.version());
                case Other it -> it.said();
            };
        }
    }

    /// The genies use the model `m` as `genies/m`: Ollama's model with a context large enough
    /// for a genie, which Genies makes from it.
    public static final String PREFIX = "genies/";

    /// The tokens of context Genies gives the models it prepares: Ollama's own is a few thousand,
    /// which a genie's instructions and the output of its tools overflow.
    public static final int CONTEXT = 32_768;

    public static final OllamaSetup NOT_YET = new OllamaSetup(Found.NOTHING, "", Step.LOOKING, 0, Optional.empty(), OllamaLibrary.BUILT_IN);

    public OllamaSetup withWanted(String wanted) { return new OllamaSetup(found, wanted, step, progress, says, library); }

    public OllamaSetup withLibrary(OllamaLibrary library) { return new OllamaSetup(found, wanted, step, progress, says, library); }

    /// At `step`, `progress` of the way through it, saying `says`.
    public OllamaSetup at(Step step, double progress, Says says) { return new OllamaSetup(found, wanted, step, progress, Optional.of(says), library); }

    /// At `step`, `progress` of the way through it, saying nothing.
    public OllamaSetup at(Step step, double progress) { return new OllamaSetup(found, wanted, step, progress, Optional.empty(), library); }

    /// What Genies `found`. Unless the user chose a model already, the one the genies use is
    /// wanted, if they use Ollama; otherwise the one Genies suggests.
    public OllamaSetup found(Found found, Settings settings) {
        String inUse = settings.usesOllama() ? settings.local().model().strip() : "";
        String chosen = !wanted.isBlank() ? wanted
                      : !inUse.isEmpty() ? inUse.substring(inUse.startsWith(PREFIX) ? PREFIX.length() : 0)
                      : found.hardware().suggested();
        return new OllamaSetup(found, chosen, step == Step.LOOKING ? Step.IDLE : step, progress, says, library);
    }

    /// The models to choose from, each once: those Ollama has; the one Genies suggests for this
    /// computer; then those ollama.com offers and those Genies knows, the ones that run well here
    /// first, in Ollama's usual quantisation. Genies' own versions of the models Ollama has are
    /// not among them, but the models they are made from are.
    public Tuple<String> offered() {
        Tuple<String> had = found.models().removeIf(model -> model.startsWith(PREFIX));
        Tuple<String> others = library.models().addAll(ModelFacts.known());
        Tuple<String> well = others.retainIf(this::runsWell);
        Tuple<String> all = had.add(found.hardware().suggested()).addAll(well).addAll(others.removeIf(this::runsWell));
        List<String> once = new ArrayList<>();
        for (String model : all) if (once.stream().noneMatch(model::equalsIgnoreCase)) once.add(model);
        return Tuple.of(String.class, once);
    }

    /// Every variant ollama.com listed of `model`, such as its other quantisations, once asked for.
    public Tuple<String> variantsOf(String model) {
        return library.variantsOf(model);
    }

    /// What Genies knows `model` is made of.
    public ModelFacts facts(String model) {
        return library.factsOf(model);
    }

    /// How `model` would run on this computer.
    public Hardware.Fit fit(String model) {
        return found.hardware().fit(facts(model));
    }

    /// Whether `model` answers quickly or at a fair pace on this computer.
    public boolean runsWell(String model) {
        Hardware.Fit fit = fit(model);
        return fit.runs() != Hardware.Runs.TOO_LARGE && fit.runs() != Hardware.Runs.UNKNOWN && fit.pace();
    }

    /// The newest Ollama's version, while it is newer than the one that runs here.
    public Optional<String> newerOllama() {
        return found.isRunning() && isNewer(library.newest(), found.version()) ? Optional.of(library.newest()) : Optional.empty();
    }

    /// Whether Genies can update Ollama to [#newerOllama]: it installed that Ollama itself, and
    /// sets nothing up just now.
    public boolean canUpdate() {
        return newerOllama().isPresent() && found.ours() && !step.isBusy();
    }

    /// Whether the version `candidate`, such as `0.40.17` or `v0.40.17`, is newer than `than`. A
    /// candidate that is not numbers between dots, such as a prerelease, is never newer. The
    /// version installed may be a prerelease, such as `0.41.0-rc1`, which is older than the
    /// release of its numbers, `0.41.0`.
    public static boolean isNewer(String candidate, String than) {
        Optional<int[]> a = numbers(candidate);
        String installed = than.strip();
        int dash = installed.indexOf('-');
        Optional<int[]> b = numbers(dash < 0 ? installed : installed.substring(0, dash));
        if (a.isEmpty() || b.isEmpty()) return false;
        for (int i = 0; i < Math.max(a.get().length, b.get().length); i++) {
            int x = i < a.get().length ? a.get()[i] : 0;
            int y = i < b.get().length ? b.get()[i] : 0;
            if (x != y) return x > y;
        }
        return dash >= 0;
    }

    private static Optional<int[]> numbers(String version) {
        String it = version.strip();
        if (it.startsWith("v")) it = it.substring(1);
        if (!it.matches("\\d+(\\.\\d+)*")) return Optional.empty();
        return Optional.of(java.util.Arrays.stream(it.split("\\.")).mapToInt(part -> {
            try {
                return Integer.parseInt(part);
            } catch (NumberFormatException tooLong) {
                return Integer.MAX_VALUE;
            }
        }).toArray());
    }

    /// Whether the genies use [#wanted], as Genies set it up.
    public boolean inUse(Settings settings) {
        return settings.usesOllama() && settings.local().model().strip().equals(PREFIX + wanted.strip());
    }

    /// What setting up does, as the button that starts it says it.
    public String todo(Lang lang) {
        Lang.SettingsPage words = lang.SETTINGS_PAGE();
        String model = wanted.strip();
        return !found.isInstalled() && !found.isRunning() ? words.INSTALL_OLLAMA_AND_GET(model)
             : !found.has(model) ? words.GET(model)
             : words.USE_MODEL(model);
    }

    /// Ollama, in a sentence: whether it is installed, and whether it runs.
    public String words(Lang lang) {
        Lang.SettingsPage words = lang.SETTINGS_PAGE();
        return step == Step.LOOKING ? words.GENIES_IS_LOOKING_WHETHER_OLLAMA_IS_ON_THIS_COMPUTER()
             : found.isRunning() ? words.OLLAMA_RUNS_ON_THIS_COMPUTER(found.version())
             : found.isInstalled() ? words.OLLAMA_IS_INSTALLED_GENIES_STARTS_IT_WHEN_THE_GENIES_NEED_IT()
             : words.OLLAMA_IS_NOT_INSTALLED_YET_GENIES_INSTALLS_IT_FOR_YOU();
    }
}
