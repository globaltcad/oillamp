package dev.oillamp;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import dev.lamp.LampWallpaper;

import sprouts.Association;
import sprouts.Tuple;

/// A lamp's complete, validated configuration.
///
/// [ConfigLoadingUtil] checks every value before building this: the display size is in range,
/// the address ranges parse, no two forwards share a name or a port, and `llm.forward` names a
/// forward that exists. Code that reads it does not need to validate again, and every configuration
/// mistake is reported in one place, with its file and key path.
///
/// Some settings are read and validated but not yet used; see "Configuration reference" in
/// `docs/ARCHITECTURE.md`.
record LampConfig(
    Display display,
    Viewer viewer,
    Terminal terminal,
    Recording recording,
    Limits limits,
    NetworkPolicy network,
    Tuple<Forward> forwards,
    Optional<Llm> llm,
    Model model,
    Git git,
    AgentTools agentTools,
    Image image,
    Host host,
    Timeouts timeouts,
    Schedule schedule,
    HostAccess hostAccess,
    Autosave autosave,
    History history,
    Web web
) {
    public LampConfig {
        // A published forward port would give the host's browser the forward's target, which the
        // user chose for the sandbox only.
        for (Web.Port port : web.ports())
            if (forwards.any(forward -> forward.port() == port.port()))
                throw new IllegalArgumentException("web port " + port.port() + " is also a forward's port");
    }

    /// The same configuration with other `[display]` settings, such as the wallpaper a new lamp
    /// was given on the command line.
    public LampConfig withDisplay(Display changed) {
        return new LampConfig(changed, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, schedule, hostAccess, autosave, history, web);
    }

    /// The same configuration with other `[model]` settings, such as those an application gave
    /// on the command line for one session.
    public LampConfig withModel(Model changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              changed, git, agentTools, image, host, timeouts, schedule, hostAccess, autosave, history, web);
    }

    /// The same configuration with other `[schedule]` settings.
    public LampConfig withSchedule(Schedule changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, changed, hostAccess, autosave, history, web);
    }

    /// The same configuration with other `[host_access]` settings, such as the requirements an
    /// application gave for one session.
    public LampConfig withHostAccess(HostAccess changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, schedule, changed, autosave, history, web);
    }

    /// The same configuration with other `[autosave]` settings.
    public LampConfig withAutosave(Autosave changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, schedule, hostAccess, changed, history, web);
    }

    /// The same configuration with other `[web]` settings.
    public LampConfig withWeb(Web changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, schedule, hostAccess, autosave, history, changed);
    }

    /// The same configuration with another `[history]` setting.
    public LampConfig withHistory(History changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, schedule, hostAccess, autosave, changed, web);
    }

    /// `[web]`: which web servers inside the sandbox the host's browser may open.
    ///
    /// Each published port is reached from the host on a loopback TCP port the session picks. A
    /// page from it runs in the user's browser, where it can send requests to the user's other
    /// local web servers and to other lamps' published ports. That is why publishing has levels,
    /// and why `off` exists.
    ///
    /// @param publish which ports are published
    /// @param ports   the ports `listed` publishes, each with a name; sorted by name
    public record Web(Publishing publish, Tuple<Port> ports) {

        public Web {
            for (int i = 0; i < ports.size(); i++)
                for (int j = i + 1; j < ports.size(); j++) {
                    if (ports.get(i).name().equals(ports.get(j).name()))
                        throw new IllegalArgumentException("web port names must be unique: " + ports.get(i).name());
                    if (ports.get(i).port() == ports.get(j).port())
                        throw new IllegalArgumentException("web ports must be unique: " + ports.get(i).port());
                }
        }

        /// The ports a session publishes: none with `off`, the listed ones with `listed`.
        public Tuple<Port> published() {
            return switch (publish) {
                case OFF -> Tuple.of(Port.class);
                case LISTED -> ports;
            };
        }

        /// `web.publish`. Later levels, such as publishing whatever the agent starts or asking the
        /// user first, are further constants here.
        public enum Publishing {
            /// Nothing is published, whatever `ports` lists.
            OFF,
            /// The ports in `ports`, and no others.
            LISTED;

            public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
        }

        /// One entry of `web.ports`: a name, and the port inside the sandbox a server listens on.
        public record Port(String name, int port) {
            /// The longest name, so that the host's path to `web-<name>.sock` stays well inside the
            /// kernel's 107-byte limit on socket paths.
            public static final int LONGEST_NAME = 32;

            public Port {
                if (!name.matches("[a-z0-9-]+") || name.length() > LONGEST_NAME)
                    throw new IllegalArgumentException("A web port name is up to " + LONGEST_NAME
                            + " lowercase letters, digits and dashes: " + name);
                if (port < 1024 || port > 65535)
                    throw new IllegalArgumentException("A web port must be in 1024-65535, got: " + port);
                if (port == Forward.PROXY_PORT || port == Forward.MODEL_PORT)
                    throw new IllegalArgumentException("Port " + port + " is oillamp's own inside the sandbox");
            }

            /// `web-<name>.sock` in the infra socket directory, where the sandbox serves this port.
            public String socketFileName() { return "web-" + name + ".sock"; }
        }
    }

    /// How far a lamp's history may grow beyond its first snapshot, the baseline, on all branches
    /// together: `[history].max_growth_gb`. Past it, each save deletes the snapshots saved longest
    /// ago. Zero never deletes a snapshot. Like recording retention, a GB here is 1024 cubed bytes.
    public record History(double maxGrowthGb) {
        /// The largest `max_growth_gb` a lamp can have.
        static final double MOST_GB = 10_000;

        public History {
            if (!Double.isFinite(maxGrowthGb) || maxGrowthGb < 0 || maxGrowthGb > MOST_GB)
                throw new IllegalArgumentException("history.max_growth_gb must be between 0 and 10000");
        }
        long allowanceBytes() { return (long) (maxGrowthGb * 1024L * 1024L * 1024L); }
    }

    /// The version of the config schema this build writes and understands.
    public static final int SCHEMA_VERSION = 1;

    /// @param background the colour the wallpaper is drawn on, as `#rrggbb`, or empty for the
    ///                   wallpaper's own, see [LampWallpaper#background()]
    public record Display(int width, int height, double scale, GpuMode gpu, WindowLayout windows,
                          LampWallpaper wallpaper, String background) {
        public Display {
            if (width  < 640 || width  > 7680) throw new IllegalArgumentException("display.width out of range: " + width);
            if (height < 480 || height > 4320) throw new IllegalArgumentException("display.height out of range: " + height);
            if (scale  < 0.5 || scale  > 4.0)  throw new IllegalArgumentException("display.scale out of range: " + scale);
            if (!background.isEmpty() && !LampWallpaper.isColour(background))
                throw new IllegalArgumentException("display.background is not a colour such as \"#15121c\": " + background);
        }
        public String size() { return width + "x" + height; }
        /// The colour the desktop's wallpaper is drawn on: [#background] or, where that is empty,
        /// the wallpaper's own.
        public String backgroundShown() { return background.isEmpty() ? wallpaper.background() : background; }
        public Display withWallpaper(LampWallpaper changed) {
            return new Display(width, height, scale, gpu, windows, changed, background);
        }
        public Display withBackground(String changed) {
            return new Display(width, height, scale, gpu, windows, wallpaper, changed);
        }
    }

    public record Viewer(boolean openOnStart, ClipboardMode clipboard, boolean viewOnly, int maxFps) {
        public Viewer {
            if (maxFps < 1 || maxFps > 120) throw new IllegalArgumentException("viewer.max_fps out of range: " + maxFps);
        }
    }

    /// Which terminal emulator opens the sandbox shell.
    public sealed interface Terminal {
        /// Detect it: the desktop's own terminal first, then the first installed one from the table.
        record Auto() implements Terminal {}
        record Profile(TerminalProfileId id) implements Terminal {}
        /// A user-supplied argv template containing `{cmd}` and optionally `{title}`.
        record Custom(Tuple<String> template) implements Terminal {}
    }

    public record Recording(boolean enabled, String codec, int crf, int maxFps,
                            int maxAgeDays, int maxTotalGb) {
        public Recording {
            if (crf < 0 || crf > 51)   throw new IllegalArgumentException("recording.crf out of range: " + crf);
            if (maxFps < 1)            throw new IllegalArgumentException("recording.max_fps must be positive");
            if (maxAgeDays < 0)        throw new IllegalArgumentException("recording.max_age_days cannot be negative");
            if (maxTotalGb < 0)        throw new IllegalArgumentException("recording.max_total_gb cannot be negative");
        }
    }

    public record Limits(String memory, int cpus, int pids) {
        public Limits {
            if (!memory.matches("\\d+[kmgKMG]?")) throw new IllegalArgumentException("limits.memory: " + memory);
            if (cpus < 0)  throw new IllegalArgumentException("limits.cpus cannot be negative");
            if (pids < 64) throw new IllegalArgumentException("limits.pids is far too low: " + pids);
        }
        /// `cpus = 0` means all host CPUs but one, so the host's own desktop stays responsive.
        public int resolveCpus(int hostCpuCount) { return cpus > 0 ? cpus : Math.max(1, hostCpuCount - 1); }
    }

    /// `[llm]`. `apiKeyEnv` and `apiKeyFile` are not used yet.
    public record Llm(String forward, String basePath, String apiKeyEnv, String apiKeyFile,
                      Tuple<String> models, String providerName) {}

    /// `[model]`: where oillamp sends the sandbox's model requests, and where it finds the key.
    ///
    /// Both stay on the host. Inside the sandbox the harnesses always see the same local address
    /// and a placeholder key, whatever is set here.
    ///
    /// @param service the model service, such as `https://api.eu.edenai.run`, or a model server
    ///                on this machine with the path of its API, such as
    ///                `http://127.0.0.1:11434/v1`. Plain `http` only for a service on this
    ///                machine's loopback, so the key never crosses a network unencrypted
    /// @param keyEnv  the host environment variable holding the key, read when a session starts
    public record Model(URI service, String keyEnv) {
        public Model {
            if (!keyEnv.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("model.key_env is not a variable name: " + keyEnv);
        }

        /// Whether the harnesses offer only models served in the EU. True for Eden AI, whose
        /// model list says where each model is served. Any other service is the user's own
        /// choice, and its model list says nothing about regions, so filtering it by region
        /// would leave no model at all.
        public boolean euOnly() {
            String host = Optional.ofNullable(service.getHost()).orElse("");
            return host.equals("edenai.run") || host.endsWith(".edenai.run");
        }
    }

    /// `[git]`: the name and email on the commits the agent makes.
    ///
    /// Without one, git in the sandbox refuses to commit, and an agent then tends to set one
    /// itself inside the repository, where it stays and is used by the user's own tools too.
    ///
    /// @param name  used only with [GitIdentity#CUSTOM]
    /// @param email used only with [GitIdentity#CUSTOM]
    public record Git(GitIdentity identity, String name, String email) {
        public Git {
            if (name.matches("(?s).*[\\r\\n].*") || email.matches("(?s).*[\\r\\n].*"))
                throw new IllegalArgumentException("git.name and git.email are one line each");
        }
    }

    /// `[agent_tools]`. Versions of the friendly names `opencode` and `pi`, latest by default.
    public record AgentTools(Tuple<String> install, Association<String, String> versions) {}

    public record Image(String base, String nodeVersion, String jdkPackage, Tuple<String> extraAptPackages) {}

    /// `[host]`. `autoInstall = false` has the same effect as `--no-install`: missing host packages are reported, not installed.
    public record Host(boolean autoInstall) {}

    /// `[timeouts]`. The wait for readiness is doubled straight after an image build.
    public record Timeouts(int containerReadySeconds, int terminalConnectSeconds, int stopSeconds) {
        public Timeouts {
            if (containerReadySeconds  < 1) throw new IllegalArgumentException("timeouts.container_ready_seconds");
            if (terminalConnectSeconds < 1) throw new IllegalArgumentException("timeouts.terminal_connect_seconds");
            if (stopSeconds            < 1) throw new IllegalArgumentException("timeouts.stop_seconds");
        }
        public Duration containerReady()  { return Duration.ofSeconds(containerReadySeconds); }
        public Duration terminalConnect() { return Duration.ofSeconds(terminalConnectSeconds); }
        public Duration stop()            { return Duration.ofSeconds(stopSeconds); }
    }

    /// `[schedule]`: whether jobs may wake the agent while a session runs, and the limits on the
    /// jobs the agent adds for itself. The user's own jobs have no limits besides the run time.
    ///
    /// @param maxAgentJobs            how many jobs the agent may have on the schedule at once
    /// @param minAgentIntervalMinutes the shortest time between two runs of one of the agent's jobs
    /// @param maxAgentDays            how long one of the agent's jobs may stay on the schedule
    /// @param maxAgentRunsPerDay      how many of the agent's jobs may run in any 24 hours
    /// @param maxRunMinutes           how long any run may take before it is stopped
    /// @param notesMaxKb              how large the agent's notes may grow, in kilobytes
    /// @param maxDelayMinutes         how late a job's run may start; a run found later than this
    ///                                is missed, not made. 0 for no limit
    public record Schedule(boolean enabled, int maxAgentJobs, int minAgentIntervalMinutes,
                           int maxAgentDays, int maxAgentRunsPerDay, int maxRunMinutes,
                           int notesMaxKb, int maxDelayMinutes) {
        public Schedule {
            if (maxAgentJobs < 0)            throw new IllegalArgumentException("schedule.max_agent_jobs cannot be negative");
            if (minAgentIntervalMinutes < 1) throw new IllegalArgumentException("schedule.min_agent_interval_minutes");
            if (maxAgentDays < 1)            throw new IllegalArgumentException("schedule.max_agent_days");
            if (maxAgentRunsPerDay < 0)      throw new IllegalArgumentException("schedule.max_agent_runs_per_day cannot be negative");
            if (maxRunMinutes < 1)           throw new IllegalArgumentException("schedule.max_run_minutes");
            if (notesMaxKb < 1)              throw new IllegalArgumentException("schedule.notes_max_kb");
            if (maxDelayMinutes < 0)         throw new IllegalArgumentException("schedule.max_delay_minutes cannot be negative");
        }
        public Schedule switchedOn() {
            return new Schedule(true, maxAgentJobs, minAgentIntervalMinutes, maxAgentDays,
                                maxAgentRunsPerDay, maxRunMinutes, notesMaxKb, maxDelayMinutes);
        }
        public Duration minAgentInterval() { return Duration.ofMinutes(minAgentIntervalMinutes); }
        public Duration maxAgentLife()     { return Duration.ofDays(maxAgentDays); }
        public Duration maxRun()           { return Duration.ofMinutes(maxRunMinutes); }
        /// How late a run may start, or empty when there is no limit.
        public Optional<Duration> maxDelay() {
            return maxDelayMinutes == 0 ? Optional.empty() : Optional.of(Duration.ofMinutes(maxDelayMinutes));
        }
    }

    /// `[autosave]`: the moments oillamp saves the lamp by itself. `oillamp history <dir> save`
    /// and the save before a restore happen whatever these say.
    ///
    /// @param startup   as a session starts, before the sandbox runs
    /// @param shutdown  as a session ends, after the sandbox has stopped
    /// @param beforeRun just before a run wakes the agent, so that what the user changed and what
    ///                  the run changed are separate snapshots
    /// @param afterRun  as a run ends. The next job's prompt tells the agent what its last runs
    ///                  said and changed from these snapshots, so without them it is told nothing
    /// @param chatQuietMinutes how long a chat must have been quiet before it is saved, once for
    ///                  all its questions. `beforeRun` and `afterRun` then apply to jobs' runs
    ///                  only. 0 saves around each question as around a job's run
    public record Autosave(boolean startup, boolean shutdown, boolean beforeRun, boolean afterRun, int chatQuietMinutes) {
        /// The longest a chat can be left unsaved: a day.
        public static final int MOST_QUIET_MINUTES = 24 * 60;

        public Autosave {
            if (chatQuietMinutes < 0 || chatQuietMinutes > MOST_QUIET_MINUTES)
                throw new IllegalArgumentException("autosave.chat_quiet_minutes must be between 0 and " + MOST_QUIET_MINUTES);
        }

        /// Whether a question's run is saved by itself, as a job's is, rather than with the rest
        /// of its chat once it is quiet.
        boolean savesEachQuestion() { return chatQuietMinutes == 0; }
    }

    public Optional<Forward> forwardNamed(String name) {
        for (Forward forward : forwards)
            if (forward.name().equals(name)) return Optional.of(forward);
        return Optional.empty();
    }

    /// The forward that `llm.forward` names, if both are configured.
    public Optional<Forward> llmForward() {
        return llm.flatMap(config -> forwardNamed(config.forward()));
    }

    /// `[host_access]`: whether the agent may reach the host through oillamp, and what it is
    /// allowed to do there.
    ///
    /// Off unless `enabled` is true. The requirements are the user's own words; they are the
    /// only authority the checker has, and the agent never sees them.
    ///
    /// @param enabled         whether a session serves the host tools at all
    /// @param requirements    the user's text, given to the checker with every request
    /// @param checkerModel    the model that judges, as the service names it; empty to judge
    ///                        with the model the agent answers with
    /// @param roots           absolute host paths the tools may mention at all; empty means the
    ///                        user's home directory
    /// @param maxReadBytes    how much of one file the agent may be handed
    /// @param commands        whether the agent may run shell commands on the host, and who
    ///                        must allow each one
    /// @param allowAdditive   whether writing, copying out, opening pages and notifications are
    ///                        offered at all
    /// @param askTheUser      whether a request outside `roots` goes to a person once the checker
    ///                        allows it, rather than being refused
    public record HostAccess(boolean enabled, String requirements, String checkerModel,
                             Tuple<String> roots, int maxReadBytes, Commands commands,
                             boolean allowAdditive, boolean askTheUser) {
        public HostAccess {
            if (maxReadBytes < 1) throw new IllegalArgumentException("host_access.max_read_bytes");
            for (String root : roots)
                if (!root.startsWith("/"))
                    throw new IllegalArgumentException("host_access.roots entries must be absolute paths: " + root);
        }
        public HostAccess switchedOn() {
            return new HostAccess(true, requirements, checkerModel, roots, maxReadBytes, commands,
                                  allowAdditive, askTheUser);
        }

        /// `host_access.commands`: whether `host_bash` exists in this lamp, and who allows each
        /// command. Running a command is the executing tier, the one that can do anything the
        /// user can, so it is off unless the user names one of the other two.
        public enum Commands {
            /// No `host_bash`: the tool is not offered, and a call is refused.
            OFF,
            /// The checker judges each command, and then the person is asked before it runs.
            ASK,
            /// The checker alone judges each command.
            CHECKER
        }
    }
}
