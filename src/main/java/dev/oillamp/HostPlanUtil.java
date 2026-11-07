package dev.oillamp;

import dev.lamp.LampStartupPhase;
import dev.lamp.LampProblem;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// Decides what has to be fixed on the host before a sandbox can run: packages to install, a
/// subordinate id range to add, or problems to report.
///
/// It is a pure function, so situations such as "a stock Ubuntu with nothing installed",
/// "Fedora" or "podman blocked by AppArmor" are tested without a virtual machine.
///
/// - **Every problem is collected**, not just the first. A user missing three packages and a
///   subordinate id range learns all of it in one run.
/// - **Podman is not checked while it is still to be installed.** After the fixes,
///   [HostPhase] probes again and plans with [Options#afterFixes()], which plans
///   nothing and reports anything still wrong.
final class HostPlanUtil {

    private HostPlanUtil() {}

    /// How much oillamp is allowed to do, and how strict this pass is.
    ///
    /// @param installing               may oillamp change this machine, and if not, why not
    /// @param requiresGraphicalSession true for commands that open windows; false for `doctor`
    /// @param afterFixes               the second pass after the fixes: plan nothing, report everything still wrong
    /// @param willExecute              false for a dry run, where steps are only described
    public record Options(Installing installing, boolean requiresGraphicalSession,
                          boolean afterFixes, boolean willExecute) {
        public Options verifying() {
            return new Options(installing, requiresGraphicalSession, true, willExecute);
        }

        /// True only when oillamp may actually fix what it finds on this run.
        public boolean mayInstall() {
            return installing.allowed();
        }
    }

    public static Result<Plan> plan(HostFacts facts, HostRequirements requirements, Options options) {
        if (!facts.isLinux())
            return Result.err(ProblemCatalogUtil.hostNotLinux(facts.os().osName()));

        Tuple<LampProblem> problems = Tuple.of(LampProblem.class);
        Tuple<Step> steps = Tuple.of(Step.class);

        if (!facts.isAptBased())
            problems = problems.add(ProblemCatalogUtil.hostNotApt(facts.os().id(), requirements.packages()));

        if (options.requiresGraphicalSession() && !facts.hasGraphicalSession())
            problems = problems.add(ProblemCatalogUtil.hostNoGraphics());

        // ── packages ───────────────────────────────────────────────────────────────────────
        Tuple<String> missing = requirements.missingFrom(facts.installedPackages());
        // If podman is not installed at all, "podman not found" tells the user nothing they were
        // not just told. Report the missing package and leave it at that.
        boolean podmanIsAbsent = missing.contains("podman");
        if (!missing.isEmpty()) {
            if (!options.mayInstall() || options.afterFixes()) {
                problems = problems.add(ProblemCatalogUtil.packagesMissing(
                        missing, requirements.installCommand(missing), options.installing()));
            } else if (options.willExecute() && facts.rootAccess() instanceof RootAccess.Unavailable none) {
                problems = problems.add(ProblemCatalogUtil.noSudo(none.reason()));
                problems = problems.add(ProblemCatalogUtil.packagesMissing(
                        missing, requirements.installCommand(missing), options.installing()));
            } else {
                steps = steps.add(installStep(requirements, missing, facts.rootAccess()));
            }
        }

        // ── subordinate id range ───────────────────────────────────────────────────────────
        if (facts.subIds() instanceof SubIdFacts.Missing gap) {
            if (!options.mayInstall() || options.afterFixes()) {
                problems = problems.add(ProblemCatalogUtil.hostNoSubIds(facts.user().name()));
            } else if (options.willExecute() && facts.rootAccess() instanceof RootAccess.Unavailable none) {
                if (problems.none(p -> p.code().equals(ProblemCatalogUtil.PKG_NO_SUDO)))
                    problems = problems.add(ProblemCatalogUtil.noSudo(none.reason()));
                problems = problems.add(ProblemCatalogUtil.hostNoSubIds(facts.user().name()));
            } else {
                IdRange block = SubIdRangeUtil.allocate(
                        gap.allocatedUidRanges().addAll(gap.allocatedGidRanges()), SubIdFacts.REQUIRED_SIZE);
                String range = block.asUsermodArgument();
                steps = steps.add(new Step.AddSubIds(facts.user().name(), block,
                        facts.rootAccess().asRoot(Tuple.of(String.class, "usermod",
                                "--add-subuids", range, "--add-subgids", range, facts.user().name()))));
                // podman caches the id map, so it has to be told the map changed.
                steps = steps.add(new Step.PodmanMigrate());
            }
        }

        // ── podman itself ──────────────────────────────────────────────────────────────────
        if (!podmanIsAbsent) {
            problems = problems.addAll(podmanProblems(facts));
        }

        // ── a terminal to put the shell in ─────────────────────────────────────────────────
        if (options.requiresGraphicalSession() && facts.terminals().isEmpty())
            problems = problems.add(ProblemCatalogUtil.noTerminal(TerminalEmulatorUtil.supportedNames()));

        Tuple<LampProblem> errors = problems.retainIf(LampProblem::isError);
        if (!errors.isEmpty())
            return Result.err(problems);
        return Result.ok(Plan.of(LampStartupPhase.HOST, steps), problems);
    }

    private static Tuple<LampProblem> podmanProblems(HostFacts facts) {
        Tuple<LampProblem> problems = Tuple.of(LampProblem.class);
        if (facts.podman().isEmpty())
            return problems.add(ProblemCatalogUtil.podmanUnusable(
                    "`podman version --format json` did not produce a usable answer"));

        PodmanFacts podman = facts.podman().get();
        if (!podman.isAtLeastMinimum())
            problems = problems.add(ProblemCatalogUtil.podmanTooOld(podman.version(), PodmanFacts.MINIMUM_VERSION));
        if (!podman.rootless())
            problems = problems.add(ProblemCatalogUtil.podmanNotRootless(
                    "podman info reports host.security.rootless = false"));

        if (facts.userns() instanceof UserNameSpaceFacts.Fails failure) {
            problems = problems.add(failure.apparmorRestricted()
                    ? ProblemCatalogUtil.apparmorBlocked(failure.evidence())
                    : ProblemCatalogUtil.usernsBroken(failure.evidence()));
        }
        return problems;
    }

    private static Step.InstallPackages installStep(HostRequirements requirements, Tuple<String> missing,
                                                    RootAccess root) {
        ValueSet<String> packages = ValueSet.of(String.class, missing);
        Association<String, String> reasons = Association.betweenSorted(String.class, String.class);
        for (String pkg : missing)
            reasons = reasons.put(pkg, requirements.reasons().get(pkg).orElse("required by oillamp"));
        Tuple<String> command = Tuple.of(String.class, "apt-get", "install", "-y", "--no-install-recommends")
                .addAll(missing);
        return new Step.InstallPackages(root.asRoot(command), packages, reasons);
    }
}
