package dev.oillamp;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import dev.lamp.LampProblem;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// Declares everything oillamp can be asked on the command line:
/// a full specification of its CLI API in the form of a record based sum type.
/// Each [Command] is a record holding the args and options it was given.
/// See [#parse], which reads a command line ([String] array) into one of
/// the [Command] record or if it fails to do so return a [Result] which says
/// what is wrong with it; and the usage text.
sealed interface Command {

    Context.Options options();

    /// Only options, or nothing at all: oillamp shows how to use it, and fails.
    record NoCommand(Context.Options options) implements Command {}
    record Version(Context.Options options) implements Command {}
    record Help(Context.Options options) implements Command {}
    record About(Context.Options options) implements Command {}
    record Guide(Context.Options options) implements Command {}
    /// `completion bash`; bash is the only shell there is a script for.
    record Completion(Context.Options options) implements Command {}
    /// Planned once and then dropped: the image is rebuilt automatically whenever its inputs
    /// change, so there is nothing to manage by hand.
    record Image(Context.Options options) implements Command {}
    record Doctor(Optional<Path> lamp, Context.Options options) implements Command {}
    record Config(Path lamp, Config.Action action, Context.Options options) implements Command {
        enum Action { CHECK, SHOW_EFFECTIVE, PATH }
    }
    record At(Path lamp, Context.Options options) implements Command {}
    /// `system`: build the whole image again, from a freshly pulled base image, not only the
    /// agent tools.
    record Update(Path lamp, boolean system, Context.Options options) implements Command {}
    record View(Path lamp, boolean viewOnly, Context.Options options) implements Command {}
    record Shell(Path lamp, Context.Options options) implements Command {}
    record Stop(Path lamp, Context.Options options) implements Command {}
    record Status(Path lamp, Context.Options options) implements Command {}
    record Follow(Path lamp, Context.Options options) implements Command {}
    record List(Context.Options options) implements Command {}
    /// `confirmed` is `--yes`: the answer to the one question `remove` asks before it deletes
    /// the agent's home, not a general "assume yes".
    record Remove(Tuple<Path> lamps, boolean confirmed, Context.Options options) implements Command {}
    record Recordings(Path lamp, Optional<String> open, boolean prune, Context.Options options) implements Command {}
    record History(Path lamp, History.Action action, Context.Options options) implements Command {
        /// What `oillamp history <dir>` is asked to do; `ListSnapshots` when nothing is asked.
        sealed interface Action {
            /// `all` is `--all`: the snapshots of every branch, not only the current one's.
            record ListSnapshots(boolean all) implements History.Action {}
            record Save(String message) implements History.Action {}
            record Restore(String snapshot) implements History.Action {}
            record ListBranches() implements History.Action {}
            /// `snapshot` is where the branch starts; without one, at the current branch's newest.
            record CreateBranch(String name, Optional<String> snapshot) implements History.Action {}
            /// `confirmed` is `--yes`; without it, the action only says what would be deleted.
            record DeleteBranch(String name, boolean confirmed) implements History.Action {}
            record Switch(String branch) implements History.Action {}
            /// `confirmed` is `--yes`; without it, the action only says what would be lost.
            record Reset(String snapshot, boolean confirmed) implements History.Action {}
            record Prune(boolean confirmed) implements History.Action {}
            /// The moments to switch on or off, each on or off, and how long a chat must be quiet
            /// before it is saved; with none, the action only says what the lamp's file has.
            record Autosave(Optional<Boolean> startup, Optional<Boolean> shutdown,
                            Optional<Boolean> beforeRun, Optional<Boolean> afterRun,
                            Optional<Integer> chatQuietMinutes) implements History.Action {}
            /// The new `[history].max_growth_gb`, in GB; without one, the action only says what
            /// the lamp's file has.
            record MaxGrowth(Optional<Double> gb) implements History.Action {}
        }
    }
    record Schedule(Path lamp, Schedule.Action action, Context.Options options) implements Command {
        /// What `oillamp schedule <dir>` is asked to do; `ListJobs` when nothing is asked.
        sealed interface Action {
            record ListJobs() implements Schedule.Action {}
            record Add(String prompt, Optional<String> cron, Optional<String> at,
                       Optional<String> expires) implements Schedule.Action {}
            record Remove(String job) implements Schedule.Action {}
            record Enable(String job) implements Schedule.Action {}
            record Disable(String job) implements Schedule.Action {}
            record Pause() implements Schedule.Action {}
            record Resume() implements Schedule.Action {}
        }
    }
    record Conversations(Path lamp, Optional<String> conversation, Context.Options options) implements Command {}
    /// `conversation` is the one `--in` names, by its id or the start of it; without one the
    /// question starts a new conversation. `after` names an entry in it to continue after, other
    /// than one of the user's questions; `insteadOf` one of the user's questions, to ask this one
    /// instead of.
    record Ask(Path lamp, String prompt, Optional<String> conversation, Optional<String> after,
               Optional<String> insteadOf, boolean waitForAnswer, Context.Options options) implements Command {}
    record Cancel(Path lamp, Optional<String> run, Context.Options options) implements Command {}
    /// `model` is the model the running session's agent answers with from its next run on, as
    /// the service names it.
    record Model(Path lamp, String model, Context.Options options) implements Command {}
    /// `enabled` is `on` or `off`, and `requirementsEnv` the variable that holds the new
    /// requirements; with neither, the command only says what the lamp's file has.
    record HostAccess(Path lamp, Optional<Boolean> enabled, Optional<String> requirementsEnv,
                      Context.Options options) implements Command {}
    /// The person's answer to a host-access question, named by its text as the event gave it.
    record Answer(Path lamp, boolean yes, String question, Context.Options options) implements Command {}


    /// Reads the command line into a [Command], or says what is wrong with it.
    static Result<Command> parse(Tuple<String> arguments) {
        Context.Options options = Context.Options.defaults();
        // The options only one command reads.
        boolean viewOnly = false;
        boolean confirmed = false;
        boolean prune = false;
        boolean wait = true;
        boolean system = false;
        boolean all = false;
        boolean delete = false;
        // The value of each option in VALUE_OPTIONS that was given, given either as `--open=x` or
        // as the argument after it; pending is the option still waiting for its value.
        Association<String, String> values = Association.between(String.class, String.class);
        Optional<String> pending = Optional.empty();
        Tuple<String> positional = Tuple.of(String.class);
        // Every option other than the four that apply to all commands, as it is spelt in usage(),
        // so that a command given one it does not take can refuse it.
        Tuple<String> commandOptions = Tuple.of(String.class);
        // After `--`, everything is taken as it is written, so a prompt may start with a dash.
        boolean literal = false;
        for (String argument : arguments) {
            if (literal) {
                positional = positional.add(argument);
                continue;
            }
            if (pending.isPresent()) {
                values = values.put(pending.get(), argument);
                pending = Optional.empty();
                continue;
            }
            if (argument.equals("--")) {
                literal = true;
                continue;
            }
            String option = VALUE_OPTIONS.stream().filter(taking -> argument.startsWith(taking + "="))
                    .findFirst().orElse(argument.equals("-y") ? "--yes"
                                      : argument.equals("-m") ? "--message" : argument);
            if (OPTIONS_OF.values().any(taken -> taken.contains(option)))
                commandOptions = commandOptions.add(option);
            if (VALUE_OPTIONS.contains(option) && argument.startsWith(option + "=")) {
                values = values.put(option, argument.substring(option.length() + 1));
                continue;
            }
            switch (option) {
                case "--verbose", "-v" -> options = options.withVerbose(true);
                case "--debug"         -> options = options.withDebug(true).withVerbose(true);
                case "--no-color"      -> { }   // handled by execute, before anything was printed
                case "--dry-run"       -> options = options.withDryRun(true);
                case "--no-install"    -> options = options.withAutoInstall(false);
                case "--init"          -> options = options.withInit(true);
                case "--no-viewer"     -> options = options.withViewer(false);
                case "--no-windows"    -> options = options.withWindows(false);
                case "--embedded"      -> options = options.withEmbedded(true);
                case "--enable-scheduling" -> options = options.withScheduling(true);
                case "--view-only"     -> viewOnly = true;
                case "--yes"           -> confirmed = true;
                case "--prune"         -> prune = true;
                case "--no-wait"       -> wait = false;
                case "--system"        -> system = true;
                case "--all"           -> all = true;
                case "--delete"        -> delete = true;
                default -> {
                    if (VALUE_OPTIONS.contains(option))
                        pending = Optional.of(option);
                    else if (argument.startsWith("-"))
                        return Result.err(ProblemCatalogUtil.usage(
                                "'" + argument + "' is not an option oillamp knows", usage()));
                    else
                        positional = positional.add(argument);
                }
            }
        }

        if (pending.isPresent()) return Result.err(switch (pending.get()) {
            case "--message" -> ProblemCatalogUtil.usage(
                    "--message needs the text to save with, for example --message \"before the upgrade\"",
                    usageOf("history <dir> save"));
            case "--in" -> ProblemCatalogUtil.usage(
                    "--in needs a conversation, as `oillamp conversations` lists them", usageOf("ask"));
            case "--after", "--instead-of" -> ProblemCatalogUtil.usage(pending.get()
                    + " needs an entry, as `oillamp conversations <dir> <conversation>` shows them", usageOf("ask"));
            case "--cron" -> ProblemCatalogUtil.usage(
                    "--cron needs a value, for example --cron \"0 9 * * 1-5\"", usageOf("schedule"));
            case "--at" -> ProblemCatalogUtil.usage(
                    "--at needs a value, for example --at \"2026-10-01 09:00\"", usageOf("schedule"));
            case "--expires" -> ProblemCatalogUtil.usage(
                    "--expires needs a value, for example --expires \"in 14d\"", usageOf("schedule"));
            case "--model-service" -> ProblemCatalogUtil.usage(
                    "--model-service needs a value, for example --model-service https://api.eu.edenai.run",
                    usageOf("at"));
            case "--model-key-env" -> ProblemCatalogUtil.usage(
                    "--model-key-env needs a value, for example --model-key-env MY_MODEL_KEY", usageOf("at"));
            case "--model" -> ProblemCatalogUtil.usage(
                    "--model needs a value, for example --model azure/gpt-6.1-sol", usageOf("at"));
            case "--instructions-env" -> ProblemCatalogUtil.usage(
                    "--instructions-env needs a value, for example --instructions-env MY_INSTRUCTIONS", usageOf("at"));
            case "--security-requirements-env" -> ProblemCatalogUtil.usage(
                    "--security-requirements-env needs a value, for example --security-requirements-env MY_RULES",
                    usageOf("at"));
            case "--requirements-env" -> ProblemCatalogUtil.usage(
                    "--requirements-env needs a value, for example --requirements-env MY_RULES", usageOf("host-access"));
            default -> ProblemCatalogUtil.usage(
                    "--open needs the session to play, for example --open 20260101-120000",
                    "oillamp recordings <dir> [--open <session>] [--prune]");
        });

        Optional<String> modelService = values.get("--model-service");
        Optional<String> modelKeyEnv = values.get("--model-key-env");
        Optional<String> model = values.get("--model");
        if (modelService.isPresent()) {
            Optional<String> wrong = ConfigLoadingUtil.serviceProblem(modelService.get());
            if (wrong.isPresent())
                return Result.err(ProblemCatalogUtil.usage(
                        "--model-service \"" + modelService.get() + "\": " + wrong.get(), usageOf("at")));
        }
        if (modelKeyEnv.isPresent() && !ConfigLoadingUtil.isVariableName(modelKeyEnv.get()))
            return Result.err(ProblemCatalogUtil.usage(
                    "--model-key-env \"" + modelKeyEnv.get() + "\": expected the name of an "
                  + "environment variable, such as EDENAI_API_KEY", usageOf("at")));
        if (model.isPresent()) {
            Optional<String> wrong = Context.ModelOverride.idProblem(model.get());
            if (wrong.isPresent())
                return Result.err(ProblemCatalogUtil.usage(
                        "--model \"" + model.get() + "\": " + wrong.get(), usageOf("at")));
        }
        options = options.withModel(new Context.ModelOverride(modelService.map(URI::create), modelKeyEnv, model));
        Optional<String> instructionsEnv = values.get("--instructions-env");
        if (instructionsEnv.isPresent() && !ConfigLoadingUtil.isVariableName(instructionsEnv.get()))
            return Result.err(ProblemCatalogUtil.usage(
                    "--instructions-env \"" + instructionsEnv.get() + "\": expected the name of an "
                  + "environment variable, such as MY_INSTRUCTIONS", usageOf("at")));
        options = options.withInstructionsEnv(instructionsEnv);

        // What an application asks for with the `Lamp` API. The requirements are a long text, so
        // the variable is named and the text is read from the environment, like the instructions.
        Optional<String> requirementsEnv = values.get("--security-requirements-env");
        if (requirementsEnv.isPresent() && !ConfigLoadingUtil.isVariableName(requirementsEnv.get()))
            return Result.err(ProblemCatalogUtil.usage(
                    "--security-requirements-env \"" + requirementsEnv.get() + "\": expected the name of an "
                  + "environment variable, such as OILLAMP_SECURITY_REQUIREMENTS", usageOf("at")));
        if (requirementsEnv.isPresent())
            options = options.withHostAccess(Optional.of(new Context.Options.HostAccessGrant(requirementsEnv.get())));

        if (positional.isEmpty()) return Result.ok(new NoCommand(options));

        String command = positional.first();
        Tuple<String> rest = positional.removeFirst();

        Optional<ValueSet<String>> takes = OPTIONS_OF.get(command);
        if (takes.isPresent()) {
            Optional<String> misplaced = commandOptions.stream()
                    .filter(option -> !takes.get().contains(option)).findFirst();
            if (misplaced.isPresent())
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` does not take " + misplaced.get(), usageOf(command)));
            if (NEEDS_A_LAMP.contains(command) && rest.isEmpty())
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` needs the path of a lamp directory",
                        "oillamp " + command + " <dir>"));
            // Usually a shell pattern such as `test*` that expanded to more than one; acting on
            // the first and ignoring the rest would look as if it had done them all.
            if (ONE_LAMP_ONLY.contains(command) && rest.size() > 1)
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` works on one lamp, but was given " + rest.size()
                      + " directories (" + rest.join(", ") + "); run it once for each",
                        "oillamp " + command + " <dir>"));
            int allowed = MOST_ARGUMENTS.get(command).orElse(Integer.MAX_VALUE);
            if (rest.size() > allowed && allowed == 0)
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` takes no arguments, but was given "
                      + rest.join(" "), usageOf(command)));
            if (rest.size() > allowed)
                return Result.err(ProblemCatalogUtil.usage("`oillamp " + command
                      + "` was given more than it takes, starting with '" + rest.get(allowed) + "'", usageOf(command)));
        }

        return switch (command) {
            case "version" -> Result.ok(new Version(options));
            case "help"    -> Result.ok(new Help(options));
            case "about"   -> Result.ok(new About(options));
            case "guide"   -> Result.ok(new Guide(options));
            case "image"   -> Result.ok(new Image(options));
            case "completion" -> {
                String shell = rest.isEmpty() ? "bash" : rest.first();
                if (!shell.equals("bash"))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "oillamp only ships a completion script for bash, not '" + shell + "'",
                            "oillamp completion bash"));
                yield Result.ok(new Completion(options));
            }
            case "doctor" -> Result.ok(new Doctor(
                    rest.isEmpty() ? Optional.empty() : Optional.of(Path.of(rest.first())), options));
            case "config" -> {
                String action = rest.size() > 1 ? rest.get(1) : "check";
                yield switch (action) {
                    case "check"          -> Result.ok(new Config(Path.of(rest.first()), Config.Action.CHECK, options));
                    case "show-effective" ->
                            Result.ok(new Config(Path.of(rest.first()), Config.Action.SHOW_EFFECTIVE, options));
                    case "path"           -> Result.ok(new Config(Path.of(rest.first()), Config.Action.PATH, options));
                    default -> Result.err(ProblemCatalogUtil.usage("'" + action + "' is not a config action",
                            "oillamp config <dir> (check | show-effective | path)"));
                };
            }
            case "at"     -> Result.ok(new At(Path.of(rest.first()), options));
            case "update" -> Result.ok(new Update(Path.of(rest.first()), system, options));
            case "view"   -> Result.ok(new View(Path.of(rest.first()), viewOnly, options));
            case "shell"  -> Result.ok(new Shell(Path.of(rest.first()), options));
            case "stop"   -> Result.ok(new Stop(Path.of(rest.first()), options));
            case "status" -> Result.ok(new Status(Path.of(rest.first()), options));
            case "follow" -> Result.ok(new Follow(Path.of(rest.first()), options));
            case "list"   -> Result.ok(new List(options));
            // The one command that takes several lamps, since a pattern like `test*` is the
            // natural way to clean up after experiments.
            case "remove" -> Result.ok(new Remove(rest.mapTo(Path.class, Path::of), confirmed, options));
            case "recordings" -> Result.ok(new Recordings(Path.of(rest.first()), values.get("--open"),
                    prune, options));
            case "history" -> {
                Optional<String> action = rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty();
                Tuple<String> after = rest.size() > 2 ? rest.removeFirst().removeFirst() : Tuple.of(String.class);
                Optional<String> message = values.get("--message");
                if (action.isPresent() && !HISTORY_ACTIONS.contains(action.get()))
                    yield Result.err(ProblemCatalogUtil.usage("'" + action.get() + "' is not something "
                            + "`oillamp history <dir>` does; it does list, save, restore, autosave, branches, "
                            + "branch, switch, reset, prune and max-growth", usageOf("history")));
                if (message.isPresent() && !action.equals(Optional.of("save")))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--message only goes with `oillamp history <dir> save`", usageOf("history <dir> save")));
                if (all && !(action.isEmpty() || action.equals(Optional.of("list"))))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--all only goes with `oillamp history <dir> list`", usageOf("history <dir> [list]")));
                if (delete && !action.equals(Optional.of("branch")))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--delete only goes with `oillamp history <dir> branch`", usageOf("history <dir> branch")));
                if (confirmed && !(action.equals(Optional.of("reset")) || action.equals(Optional.of("prune")) || delete))
                    yield Result.err(ProblemCatalogUtil.usage("--yes only goes with `oillamp history <dir> reset`, `oillamp history <dir> prune` "
                            + "and `oillamp history <dir> branch --delete`", usageOf("history <dir> reset")
                            + "\n" + usageOf("history <dir> branch")));
                Path lamp = Path.of(rest.first());
                if (action.isEmpty())
                    yield Result.ok(new History(lamp, new History.Action.ListSnapshots(all), options));
                String usage = usageOf("history <dir> " + action.get());
                String written = "`oillamp history <dir> " + action.get() + (delete ? " --delete" : "") + "`";
                yield switch (action.get()) {
                    case "list", "save", "branches", "prune" -> {
                        if (!after.isEmpty())
                            yield Result.err(ProblemCatalogUtil.usage(written + " takes nothing more, but was given '"
                                    + after.first() + "'", action.get().equals("list") ? usageOf("history <dir> [list]") : usage));
                        yield Result.ok(new History(lamp, switch (action.get()) {
                            case "list" -> new History.Action.ListSnapshots(all);
                            case "save" -> new History.Action.Save(message.orElse(""));
                            case "prune" -> new History.Action.Prune(confirmed);
                            default     -> new History.Action.ListBranches();
                        }, options));
                    }
                    case "restore", "reset", "switch" -> {
                        String needed = action.get().equals("switch")
                                ? "the branch to switch to, as `oillamp history <dir> branches` lists them"
                                : "the snapshot to go back to, as `oillamp history <dir>` lists them";
                        if (after.isEmpty())
                            yield Result.err(ProblemCatalogUtil.usage(written + " needs " + needed, usage));
                        if (after.size() > 1)
                            yield Result.err(ProblemCatalogUtil.usage(written + " takes one "
                                    + (action.get().equals("switch") ? "branch" : "snapshot") + ", but was also given '"
                                    + after.get(1) + "'", usage));
                        yield Result.ok(new History(lamp, switch (action.get()) {
                            case "restore" -> new History.Action.Restore(after.first());
                            case "reset"   -> new History.Action.Reset(after.first(), confirmed);
                            default        -> new History.Action.Switch(after.first());
                        }, options));
                    }
                    case "branch" -> {
                        int most = delete ? 1 : 2;
                        if (after.isEmpty())
                            yield Result.err(ProblemCatalogUtil.usage(written + " needs the name of the branch"
                                    + (delete ? " to delete" : ", and may be given the snapshot it starts at"), usage));
                        if (after.size() > most)
                            yield Result.err(ProblemCatalogUtil.usage(written + " takes "
                                    + (delete ? "one branch" : "a name and a snapshot") + ", but was also given '"
                                    + after.get(most) + "'", usage));
                        if (delete)
                            yield Result.ok(new History(lamp, new History.Action.DeleteBranch(after.first(), confirmed), options));
                        Optional<String> wrong = dev.oillamp.History.branchNameProblem(after.first());
                        if (wrong.isPresent())
                            yield Result.err(ProblemCatalogUtil.usage("the branch name '" + after.first() + "': "
                                    + wrong.get(), usage));
                        yield Result.ok(new History(lamp, new History.Action.CreateBranch(after.first(),
                                after.size() > 1 ? Optional.of(after.get(1)) : Optional.empty()), options));
                    }
                    case "max-growth" -> maxGrowth(lamp, after, options);
                    default -> autosave(lamp, after, options);
                };
            }
            case "schedule" -> {
                String action = rest.size() > 1 ? rest.get(1) : "list";
                Optional<String> argument = rest.size() > 2 ? Optional.of(rest.get(2)) : Optional.empty();
                Optional<String> cron = values.get("--cron");
                Optional<String> at = values.get("--at");
                Optional<String> expires = values.get("--expires");
                boolean needsArgument = ValueSet.of("add", "remove", "enable", "disable").contains(action);
                if (needsArgument && argument.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage(action.equals("add")
                            ? "`oillamp schedule <dir> add` needs the prompt the agent is woken with, in quotes"
                            : "`oillamp schedule <dir> " + action + "` needs the job, such as job-3", usageOf("schedule")));
                if (!needsArgument && argument.isPresent())
                    yield Result.err(ProblemCatalogUtil.usage("`oillamp schedule <dir> " + action
                            + "` takes nothing more, but was given '" + argument.get() + "'", usageOf("schedule")));
                if (!action.equals("add") && (cron.isPresent() || at.isPresent() || expires.isPresent()))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--cron, --at and --expires only go with `oillamp schedule <dir> add`", usageOf("schedule")));
                String job = argument.orElse("");
                Optional<Schedule.Action> chosen = switch (action) {
                    case "list"    -> Optional.of(new Schedule.Action.ListJobs());
                    case "add"     -> Optional.of(new Schedule.Action.Add(job, cron, at, expires));
                    case "remove"  -> Optional.of(new Schedule.Action.Remove(job));
                    case "enable"  -> Optional.of(new Schedule.Action.Enable(job));
                    case "disable" -> Optional.of(new Schedule.Action.Disable(job));
                    case "pause"   -> Optional.of(new Schedule.Action.Pause());
                    case "resume"  -> Optional.of(new Schedule.Action.Resume());
                    default        -> Optional.empty();
                };
                if (chosen.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "'" + action + "' is not something `oillamp schedule` does", usageOf("schedule")));
                yield Result.ok(new Schedule(Path.of(rest.first()), chosen.get(), options));
            }
            case "conversations" -> Result.ok(new Conversations(Path.of(rest.first()),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty(), options));
            case "ask" -> {
                Optional<String> in = values.get("--in");
                Optional<String> after = values.get("--after");
                Optional<String> insteadOf = values.get("--instead-of");
                if (rest.size() < 2)
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp ask` needs something to ask the agent, in quotes", usageOf("ask")));
                if ((after.isPresent() || insteadOf.isPresent()) && in.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--after and --instead-of name an entry of the conversation that --in names", usageOf("ask")));
                if (after.isPresent() && insteadOf.isPresent())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "a question goes either after an entry or instead of a question, not both", usageOf("ask")));
                yield Result.ok(new Ask(Path.of(rest.first()), rest.get(1), in, after, insteadOf,
                        wait, options));
            }
            case "model" -> {
                if (rest.size() < 2)
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp model` needs the model, as the service names it, such as azure/gpt-6.1-sol",
                            usageOf("model")));
                Optional<String> wrong = Context.ModelOverride.idProblem(rest.get(1));
                if (wrong.isPresent())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp model` \"" + rest.get(1) + "\": " + wrong.get(), usageOf("model")));
                yield Result.ok(new Model(Path.of(rest.first()), rest.get(1), options));
            }
            case "cancel" -> Result.ok(new Cancel(Path.of(rest.first()),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty(), options));
            case "host-access" -> {
                Optional<String> rulesEnv = values.get("--requirements-env");
                if (rulesEnv.isPresent() && !ConfigLoadingUtil.isVariableName(rulesEnv.get()))
                    yield Result.err(ProblemCatalogUtil.usage("--requirements-env \"" + rulesEnv.get()
                          + "\": expected the name of an environment variable, such as MY_RULES", usageOf("host-access")));
                Optional<String> switched = rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty();
                if (switched.isPresent() && !switched.get().equals("on") && !switched.get().equals("off"))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp host-access` \"" + switched.get() + "\": expected on or off", usageOf("host-access")));
                yield Result.ok(new HostAccess(Path.of(rest.first()), switched.map("on"::equals), rulesEnv, options));
            }
            case "answer" -> {
                if (rest.size() < 3 || !(rest.get(1).equals("yes") || rest.get(1).equals("no")))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp answer` needs yes or no, and the question as the session asked it", usageOf("answer")));
                yield Result.ok(new Answer(Path.of(rest.first()), rest.get(1).equals("yes"), rest.get(2), options));
            }
            default -> Result.err(ProblemCatalogUtil.usage("'" + command + "' is not an oillamp command", usage()));
        };
    }

    /// `oillamp history <dir> autosave [<moment> (on | off) | chat-quiet-minutes <minutes>]...`,
    /// from the words after `autosave`.
    private static Result<Command> autosave(Path lamp, Tuple<String> words, Context.Options options) {
        String usage = usageOf("history <dir> autosave");
        java.util.Map<String, Boolean> moments = new java.util.HashMap<>();
        Optional<Integer> quiet = Optional.empty();
        for (int i = 0; i < words.size(); i += 2) {
            String moment = words.get(i);
            if (moment.equals(CHAT_QUIET_MINUTES)) {
                String written = i + 1 < words.size() ? words.get(i + 1) : "";
                if (!written.matches("[0-9]{1,4}") || Integer.parseInt(written) > LampConfig.Autosave.MOST_QUIET_MINUTES)
                    return Result.err(ProblemCatalogUtil.usage("`oillamp history <dir> autosave` " + CHAT_QUIET_MINUTES
                            + " needs a number of minutes from 0 to " + LampConfig.Autosave.MOST_QUIET_MINUTES
                            + " after it; 0 saves around each question", usage));
                quiet = Optional.of(Integer.parseInt(written));
                continue;
            }
            if (!AUTOSAVE_MOMENTS.contains(moment))
                return Result.err(ProblemCatalogUtil.usage("`oillamp history <dir> autosave` \"" + moment
                        + "\": expected startup, shutdown, before-run, after-run or " + CHAT_QUIET_MINUTES, usage));
            String switched = i + 1 < words.size() ? words.get(i + 1) : "";
            if (!switched.equals("on") && !switched.equals("off"))
                return Result.err(ProblemCatalogUtil.usage("`oillamp history <dir> autosave` " + moment
                        + " needs on or off after it", usage));
            moments.put(moment, switched.equals("on"));
        }
        return Result.ok(new History(lamp, new History.Action.Autosave(
                Optional.ofNullable(moments.get("startup")), Optional.ofNullable(moments.get("shutdown")),
                Optional.ofNullable(moments.get("before-run")), Optional.ofNullable(moments.get("after-run")), quiet),
                options));
    }

    /// `oillamp history <dir> max-growth [<gb>]`, from the words after `max-growth`: a size in GB
    /// from 0 to 10000, written as digits, with a point and more digits if need be.
    private static Result<Command> maxGrowth(Path lamp, Tuple<String> words, Context.Options options) {
        String usage = usageOf("history <dir> max-growth");
        if (words.isEmpty())
            return Result.ok(new History(lamp, new History.Action.MaxGrowth(Optional.empty()), options));
        if (words.size() > 1)
            return Result.err(ProblemCatalogUtil.usage("`oillamp history <dir> max-growth` takes one size, but was also given '"
                    + words.get(1) + "'", usage));
        String written = words.first();
        if (!written.matches("[0-9]+(\\.[0-9]+)?") || Double.parseDouble(written) > LampConfig.History.MOST_GB)
            return Result.err(ProblemCatalogUtil.usage("`oillamp history <dir> max-growth` \"" + written
                    + "\": expected a size in GB from 0 to 10000, such as 2 or 0.5; 0 never deletes a snapshot", usage));
        return Result.ok(new History(lamp, new History.Action.MaxGrowth(Optional.of(Double.parseDouble(written))), options));
    }

    /// The options each command takes, besides `--verbose`, `--debug` and `--no-color`, which
    /// every command takes. `doctor` and `config` change nothing anyway, so they accept the two
    /// options that promise that.
    Association<String, ValueSet<String>> OPTIONS_OF =
            Association.between(String.class, ValueSet.classTyped(String.class))
                .put("at",         ValueSet.of("--init", "--dry-run", "--no-install", "--no-viewer", "--no-windows",
                                               "--embedded", "--model-service", "--model-key-env",
                                               "--model", "--instructions-env", "--enable-scheduling",
                                               "--security-requirements-env"))
                .put("view",       ValueSet.of("--view-only"))
                .put("update",     ValueSet.of("--system", "--dry-run", "--no-install"))
                .put("remove",     ValueSet.of("--yes", "--dry-run", "--embedded"))
                .put("recordings", ValueSet.of("--open", "--prune", "--dry-run"))
                .put("doctor",     ValueSet.of("--dry-run", "--no-install"))
                .put("config",     ValueSet.of("--dry-run", "--no-install"))
                .put("history",    ValueSet.of("--message", "--all", "--delete", "--yes", "--embedded"))
                .put("schedule",   ValueSet.of("--cron", "--at", "--expires", "--embedded"))
                .put("ask",        ValueSet.of("--in", "--after", "--instead-of", "--no-wait", "--embedded"))
                .put("cancel",     ValueSet.of("--embedded"))
                .put("model",      ValueSet.of("--embedded"))
                .put("host-access", ValueSet.of("--requirements-env", "--embedded"))
                .put("answer",     ValueSet.of("--embedded"))
                .put("conversations", ValueSet.of("--embedded"))
                .put("shell",      ValueSet.of(String.class))
                .put("stop",       ValueSet.of("--embedded"))
                .put("status",     ValueSet.of("--embedded"))
                .put("follow",     ValueSet.of("--embedded"))
                .put("list",       ValueSet.of("--embedded"))
                .put("completion", ValueSet.of(String.class))
                .put("version",    ValueSet.of(String.class))
                .put("help",       ValueSet.of(String.class))
                .put("about",      ValueSet.of(String.class))
                .put("guide",      ValueSet.of(String.class));

    /// The options that take a value.
    ValueSet<String> VALUE_OPTIONS = ValueSet.of(
            "--open", "--model-service", "--model-key-env", "--model", "--instructions-env", "--message", "--cron", "--at", "--expires",
            "--security-requirements-env", "--requirements-env",
            "--in", "--after", "--instead-of");

    /// What `oillamp history <dir>` does besides listing the snapshots.
    ValueSet<String> HISTORY_ACTIONS = ValueSet.of("list", "save", "restore", "autosave",
                                                   "branches", "branch", "switch", "reset", "prune",
                                                   "max-growth");

    /// What `oillamp history <dir> autosave` switches: the moments oillamp saves a lamp by itself.
    ValueSet<String> AUTOSAVE_MOMENTS = ValueSet.of("startup", "shutdown", "before-run", "after-run");

    /// What `oillamp history <dir> autosave` takes, followed by minutes, for how long a chat must
    /// be quiet before oillamp saves it.
    String CHAT_QUIET_MINUTES = "chat-quiet-minutes";

    /// The commands that take a lamp directory and cannot do without it.
    ValueSet<String> NEEDS_A_LAMP = ValueSet.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "config", "remove",
            "history", "schedule", "ask", "conversations", "cancel", "model", "update",
            "host-access", "answer");

    /// The commands that work on one lamp. `remove` takes several.
    ValueSet<String> ONE_LAMP_ONLY = ValueSet.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "doctor", "update");

    /// How many arguments may follow the other commands.
    Association<String, Integer> MOST_ARGUMENTS =
            Association.between(String.class, Integer.class)
                .put("config", 2).put("completion", 1).put("schedule", 3).put("ask", 2)
                .put("conversations", 2).put("cancel", 2).put("model", 2).put("host-access", 2).put("answer", 3)
                // The lamp, `autosave`, and each moment and chat-quiet-minutes with its value.
                .put("history", 2 + 2 * (AUTOSAVE_MOMENTS.size() + 1))
                .put("list", 0).put("version", 0).put("help", 0).put("about", 0).put("guide", 0);

    /// The lines of [#usage] that describe `command`, such as `oillamp stop <dir>`, or the four
    /// of `oillamp history`. Only the lines indented by two spaces name a command; the lines
    /// below them describe it.
    static String usageOf(String command) {
        java.util.List<String> lines = usage().lines()
                .filter(line -> line.startsWith("  ") && !line.startsWith("   ")).map(String::strip)
                .filter(line -> line.equals(command) || line.startsWith(command + " "))
                .map(line -> "oillamp " + line).toList();
        return lines.isEmpty() ? usage() : String.join("\n", lines);
    }

    static String usage() {
        return """
            oillamp [--verbose] [--debug] [--no-color] <command>

            After `--`, every argument is taken as written, so a prompt may start with a dash.

            New here? `oillamp guide` walks through a first session; `oillamp about` says what
            oillamp is for and what it is built from.

              at <dir> [--init] [--dry-run] [--no-install] [--no-viewer] [--no-windows]
                       [--embedded] [--model-service <url>] [--model-key-env <name>]
                       [--model <id>] [--instructions-env <name>] [--enable-scheduling]
                       [--security-requirements-env <name>]
                    Set up (if needed) and run a session. Opens a shell window and a viewer
                    onto the sandbox's desktop, and stays in the foreground until the session
                    ends: Ctrl-C here, closing this terminal, or `oillamp stop <dir>`.
                      --init          accept a directory that is not empty as a new lamp
                      --dry-run       show every step and change nothing; with --verbose, also
                                      the full podman command
                      --no-install    report missing host packages instead of installing them
                      --no-viewer     open the shell window, but no viewer
                      --no-windows    open no windows at all, so no display is needed (under
                                      tmux, or over ssh); get in with `shell` and `view`
                      --embedded      for applications that start oillamp themselves: no
                                      windows, events as JSON lines, ends when stdin closes
                      --model-service <url>, --model-key-env <name>
                                      use this model service, and the key in this variable,
                                      instead of the lamp's [model] settings, for this session
                      --model <id>    answer every run with this model, as the service names
                                      it, such as azure/gpt-6.1-sol: new conversations, and old
                                      ones that were on another model; it also becomes pi's
                                      default. Without it, a new conversation gets pi's default,
                                      and an old one keeps its own
                      --instructions-env <name>
                                      tell the agent what this variable holds, in addition to
                                      pi's own instructions: written to the agent's
                                      ~/.pi/agent/APPEND_SYSTEM.md. Without it, that file stays
                                      as it is
                      --enable-scheduling
                                      let jobs on the schedule wake the agent in this session,
                                      as `enabled = true` under [schedule] would
                      --security-requirements-env <name>
                                      for an application that decided this itself: judge the
                                      agent's requests to the host against what this variable
                                      holds, in place of the requirements under [host_access].
                                      The feature must be on in the lamp's file; the model that
                                      judges is the file's checker_model, or else the agent's
              view <dir> [--view-only]
                    Open another viewer onto a running session's desktop. Needs a display.
                    --view-only lets you watch without typing.
              update <dir> [--system] [--dry-run] [--no-install]
                    Install opencode and pi again, and download opencode's model list again,
                    into a new image for this lamp. The lamp must be stopped. The home is not
                    touched, and the lamp switches to the new image only if the build succeeds.
                    Versions come from agent_tools.versions (latest by default).
                      --system  build the whole image again, from the newest Debian, with
                                every package's updates. Takes as long as a first build.
              shell <dir>
                    Open an extra shell in this terminal. Needs no display. Closing it does
                    not end the session.
              stop <dir>
                    Ask a running session to shut down, or clean up after one that crashed.
              status <dir>
                    What a running session is doing.
              follow <dir> [--embedded]
                    Report what a running session reports, as it happens, until it ends:
                    first what it is doing now, then everything after. Ctrl-C stops
                    following, not the session.
              list
                    Every oillamp sandbox running on this host.
              remove <dir>... --yes
                    Delete one or more lamps: the agent's home, the state, the config. Without
                    --yes it only says what would go. Every lamp is checked first; if any cannot
                    be removed, none is. Needed because parts of a lamp belong to the sandbox's
                    own users and `rm -rf` cannot remove them.
              history <dir> [list] [--all]
                    List the snapshots on the branch the lamp is on, newest first. --all lists
                    those of every branch, each marked with the branches it is on.
              history <dir> save [--message <text>]
                    Take a snapshot of the lamp: the agent's home, oillamp.toml and the
                    schedule. Works while a session runs. oillamp also saves by itself around
                    each run, and as each session starts and ends if `history <dir> autosave`
                    switches that on.
              history <dir> restore <snapshot>
                    Bring the lamp back to a snapshot, named by the start of its id: the
                    home, oillamp.toml and the jobs. The snapshot must be on the branch the lamp
                    is on. The session must be stopped. The lamp is saved first, so this can be
                    undone.
              history <dir> branches
                    List the branches: alternative states of the lamp, each a line of snapshots
                    with its own home, oillamp.toml and jobs. The lamp is on one at a time, and
                    every save goes to it. The first is main.
              history <dir> branch <name> [<snapshot>]
                    Start a branch at a snapshot of any branch, or at the newest snapshot of the
                    branch the lamp is on. The lamp stays where it is; switch to the branch to
                    use it. Works while a session runs. A name is lowercase letters, digits,
                    -, _ and ., begins with a letter or a digit, and is at most 64 long.
              history <dir> branch --delete <name> [--yes]
                    Delete a branch other than the one the lamp is on. Without --yes it only
                    says how many snapshots are on that branch alone.
              history <dir> switch <name>
                    Bring the lamp to that branch's newest snapshot, and save to that branch
                    from now on. What changed since the last snapshot is saved first, on the
                    branch the lamp leaves. The session must be stopped.
              history <dir> reset <snapshot> [--yes]
                    Bring the lamp and its branch back to a snapshot on that branch, as
                    `git reset --hard` does: the snapshots after it leave the branch, and
                    changes not yet saved are thrown away. Nothing is saved first. Without
                    --yes it only says what would be lost. The session must be stopped.
              history <dir> autosave [(startup | shutdown | before-run | after-run) (on | off)
                                     | chat-quiet-minutes <minutes>]...
                    Say when oillamp saves the lamp by itself: [autosave] in oillamp.toml.
                    With moments and on or off, or minutes, write those there, and change
                    nothing else in the file. before-run and after-run are around a job's
                    run; questions are saved together, once the chat was quiet that many
                    minutes (0 saves around each). Writing needs the session to be stopped.
              history <dir> prune [--yes]
                    Say which snapshots would be deleted to keep the history within
                    [history].max_growth_gb, or delete them with --yes. Every save also does
                    this by itself. The setting is how far the history may grow beyond its
                    first snapshot, on all branches together. The snapshots saved longest ago
                    go first; every branch's newest snapshot is kept.
              history <dir> max-growth [<gb>]
                    Say how far the history may grow beyond its first snapshot, in GB:
                    [history].max_growth_gb in oillamp.toml. With a size from 0 to 10000,
                    write it there, and change nothing else in the file; 0 never deletes a
                    snapshot. Writing needs the session to be stopped.
              schedule <dir> [add | remove <job> | enable <job> | disable <job> | pause | resume]
                    List the jobs that wake the agent while a session runs, or change them.
                    Jobs run only in a session started with --enable-scheduling, or with
                    `enabled = true` under [schedule] in oillamp.toml.
                      add (--cron "<expression>" | --at <time>) [--expires <time>] "<prompt>"
                                      --cron "0 9 * * 1-5" repeats, as cron would; --at runs once,
                                      at "2026-10-01 09:00" on this machine's clock or "in 2h"
              ask <dir> [--in <conversation> [--after <entry> | --instead-of <entry>]] [--no-wait] "<prompt>"
                    Wake the agent in a running session with a prompt, and print its answer.
                    If it is busy, the prompt waits its turn. The lamp is saved before and after.
                      --in            continue that conversation where it stands, instead of
                                      starting a new one; `oillamp conversations` lists them
                      --after         continue after that entry, such as an earlier answer
                      --instead-of    ask this instead of that question, leaving the old one
                                      and its answers as a branch of their own
                      --no-wait       say which run answers it, and return at once
              cancel <dir> [<run>]
                    Stop the run the agent is working on, or a run that is still waiting.
              model <dir> <id>
                    Have a running session's agent answer with this model from its next run
                    on, as `at --model` would have: in new conversations and in old ones, and
                    as pi's default. A run in progress finishes on the model it started with.
              conversations <dir> [<conversation>]
                    List the agent's conversations, or show one, with the ids of its entries.
              host-access <dir> [on | off] [--requirements-env <name>]
                    Say whether the agent may ask to reach the host, and what the checker
                    judges its requests against: `enabled` and `requirements` under
                    [host_access] in oillamp.toml. With on or off, or the requirements this
                    variable holds, write them there, and change nothing else in the file.
                    Writing needs the session to be stopped.
              answer <dir> (yes | no) "<question>"
                    Answer a running session's host-access question, as it asked it.
              recordings <dir> [--open <session>] [--prune]
                    List this lamp's screen recordings. --open plays one, --prune
                    applies the configured retention now instead of at the next start.
              completion bash
                    Print a bash completion script. Use it with:
                        eval "$(oillamp completion bash)"
              doctor [<dir>]
                    Check the host, and the lamp if one is given. Changes nothing.
              config <dir> (check | show-effective | path)
                    Validate the configuration, print it, or print its path.
              guide
                    A first session, step by step.
              about
                    Why oillamp exists, and what it is built from.
              version
              help
            """;
    }
}
