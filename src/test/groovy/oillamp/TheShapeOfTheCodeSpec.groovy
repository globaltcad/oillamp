package oillamp

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 *  Promises about the structure of the code that are easy to break by accident and expensive to
 *  notice late. They are checked here rather than left to review: most are about what is
 *  *absent*, and absences are exactly what review misses.
 */
class TheShapeOfTheCodeSpec extends Specification {

    @Shared JavaClasses code = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages('dev.oillamp', 'dev.lamp')

    /** The engine, as far as anything outside it is concerned. */
    static final Set<String> ENGINE_API = ['OilLamp', 'Machine'] as Set

    /** What an application that embeds oillamp sees: the words oillamp speaks in, and a lamp. */
    static final Set<String> EMBEDDING_API = [
            'LampEvent', 'LampProblem', 'LampExitStatus', 'Lamp', 'LampDirectory', 'LampFailedException', 'LampSave',
            'LampSaveKind', 'LampJob', 'LampJobAuthor', 'LampStartupPhase', 'LampStep', 'LampSessionStatus',
            'LampRun', 'LampRunOutcome', 'LampAgentProgress', 'LampHostRisk', 'LampHostVerdict', 'LampPrompt',
            'LampConversation', 'LampLauncher'
    ] as Set

    static final Set<String> PUBLIC_API = ENGINE_API + EMBEDDING_API

    def 'The engine exposes two types, and nothing else'() {
        reportInfo """
            The public surface is the part that cannot be changed later without breaking someone.
            Keeping the engine's to two types - the entry point and the machine it runs against -
            is what leaves everything else free to be rewritten. The events, problems and exit
            codes it speaks in are public too, but they live in `dev.lamp`, because an
            application that embeds oillamp reads them as well.

            This is enforced by the compiler rather than by convention: the engine is one package,
            so a class that is not marked public simply cannot be reached from outside it. This
            scenario exists to catch the moment someone adds `public` to another class - which is
            easy to do while debugging and easy to forget to undo.

            The scenarios in this package are themselves the proof that these are enough: they
            live outside `dev.oillamp` and drive the whole tool through nothing else.
        """
        when: 'we look at every top-level type oillamp defines'
            var exposed = code.findAll { it.packageName == 'dev.oillamp' }
                              .findAll { it.enclosingClass.empty && !it.simpleName.contains('\$') }
                              .findAll { it.modifiers.contains(JavaModifier.PUBLIC) }
                              .collect { it.simpleName }
                              .toSet()

        then: 'exactly the two are public'
            exposed.sort() == ENGINE_API.sort()
    }

    def 'The client API in dev.lamp has exactly the public types listed in EMBEDDING_API'() {
        reportInfo """
            `dev.lamp` is the client library: the package that other Java programs compile
            against when they start and control oillamp from their own code (a game that runs
            an agent in a lamp, for example), rather than running `oillamp` from a shell.
            Those programs never touch `dev.oillamp`. They use `Lamp` to start the engine as a
            separate process and read its `LampEvent`s.

            Every public top-level type in `dev.lamp` is something those programs may come to
            rely on, so it cannot be removed or renamed later without breaking them. This
            scenario fails when a type becomes public there without being added to
            EMBEDDING_API, or when a listed type stops being public, so that every change to
            the client API is deliberate.
        """
        when:
            var exposed = code.findAll { it.packageName == 'dev.lamp' }
                              .findAll { it.enclosingClass.empty && !it.simpleName.contains('\$') }
                              .findAll { it.modifiers.contains(JavaModifier.PUBLIC) }
                              .collect { it.simpleName }
                              .toSet()

        then:
            exposed.sort() == EMBEDDING_API.sort()
    }

    def 'A type in dev.lamp says by its name whether it is public: Lamp... if it is, Internal... if not'() {
        reportInfo """
            A program that uses oillamp imports from `dev.lamp` next to its own types and those
            of other libraries. A prefix on every public type makes it obvious there which ones
            are oillamp's: `LampRun` and `LampProblem` cannot be confused with a `Run` or
            `Problem` of the program's own, and an IDE lists the whole API together when you
            type `Lamp`.

            The types that are not public get the prefix `Internal`, so that the name alone says
            whether a type is a promise to those programs or free to change. A type called
            `InternalSomething` that becomes public, or a `LampSomething` that is not, is a
            mistake in one direction or the other, and this scenario says which.

            Nested types are judged by the type they are nested in: `LampConversation.Entry` is
            named through `LampConversation`. The engine, `dev.oillamp`, is not held to this; its
            types are all internal, apart from the two the first scenario names.
        """
        given: 'every top-level type in dev.lamp'
            var types = code.findAll { it.packageName == 'dev.lamp' }
                            .findAll { it.enclosingClass.empty && !it.simpleName.contains('\$') }
                            .findAll { it.simpleName != 'package-info' }

        when: 'we compare each one\'s name with whether it is public'
            var misnamed = types.findResults { type ->
                boolean exposed = type.modifiers.contains(JavaModifier.PUBLIC)
                if (exposed && !type.simpleName.startsWith('Lamp'))
                    return "${type.simpleName} is public, so its name must start with 'Lamp'"
                if (!exposed && !type.simpleName.startsWith('Internal'))
                    return "${type.simpleName} is not public, so its name must start with 'Internal'"
                return null
            }

        then: 'every name says which it is'
            types.size() > 0
            misnamed.isEmpty()
    }

    def 'The embedding package does not depend on the engine'() {
        reportInfo """
            The engine runs in a process of its own, and an application talks to it. If
            `dev.lamp` imported `dev.oillamp`, that separation would be a convention rather than
            a fact, and the application would be one careless import away from running the
            supervisor inside itself. The engine may use `dev.lamp`; never the other way round.
        """
        when:
            var reaching = [] as Set
            code.findAll { it.packageName == 'dev.lamp' }.each { type ->
                type.directDependenciesFromSelf.each { dependency ->
                    if (dependency.targetClass.packageName == 'dev.oillamp')
                        reaching << "${type.simpleName} -> ${dependency.targetClass.simpleName}"
                }
            }

        then:
            reaching.isEmpty()
    }

    def 'Nothing internal leaks out through the types that are public'() {
        reportInfo """
            A small public API is not small if one of its methods returns an internal type: the
            caller can then reach everything that type touches, and the freedom to change it is
            gone without anyone deciding to give it up.

            So every parameter and return type of every public method must itself be public -
            either one of the public types, one of their nested types, or something from the JDK
            or a library.
        """
        when:
            var leaks = []
            // By package as well as name: a dev.lamp type may share its name with an internal one.
            code.findAll { (it.packageName == 'dev.oillamp' && it.simpleName in ENGINE_API)
                        || (it.packageName == 'dev.lamp' && it.simpleName in EMBEDDING_API) }.each { type ->
                type.methods.findAll { it.modifiers.contains(JavaModifier.PUBLIC) }.each { method ->
                    (method.rawParameterTypes + [method.rawReturnType]).each { used ->
                        if (used.packageName in ['dev.oillamp', 'dev.lamp'] && !used.modifiers.contains(JavaModifier.PUBLIC))
                            leaks << "${type.simpleName}.${method.name} exposes ${used.simpleName}"
                    }
                }
            }

        then:
            leaks.isEmpty()
    }

    def 'Only the classes listed here may use files, processes, threads, System or SecureRandom'() {
        reportInfo """
            oillamp's logic (which packages to install, whether a directory may become a lamp,
            which recordings to delete, what the plan is) lives in pure functions. They take
            plain values as input and return a result, and they never read a file or start a
            process themselves. The I/O happens in a small set of classes, listed below as
            `allowed`. Those gather the inputs and carry out the result.

            This separation is what lets the scenarios test broken machines, dangerous
            directories and malformed configuration with plain values, without installing
            anything or starting a container. It breaks silently: a planner that calls
            `Files.readString` still compiles and still works, but can no longer be tested that way.

            So this scenario scans every class outside the `allowed` list and fails if one
            depends directly on `Files`, `ProcessBuilder`, `Process`, `Thread`, `System` or
            `SecureRandom`. If a class really is a new I/O boundary, add it to `allowed` with a
            comment saying what it touches and which pure class makes its decisions.
        """
        given: 'the classes whose job is to touch the outside world'
            var allowed = ['RealMachine', 'SimulatedMachine', 'FilesystemUtil', 'LampLock', 'HostProbeUtil',
                           'StepRunner', 'LampPhase', 'HostPhase', 'CommandExecutionUtil', 'ConsoleRenderer',
                           'OilLamp', 'Machine', 'LampDirectory', 'InternalLampUtil',
                           // The session. These three bind sockets, move bytes between them
                           // and start the windows. They decide nothing: every decision a
                           // session makes is SessionMachine's.
                           'Supervisor', 'Relay', 'Control',
                           // The egress proxy: sockets, DNS and byte copying. Whether a
                           // connection is allowed is decided by NetworkPolicyUtil, a pure function.
                           'Egress',
                           // The lamp's history: reads the agent's home and writes git
                           // objects, as FilesystemUtil does the lamp's other files. What a
                           // snapshot looks like is decided by GitObjectUtil, which is pure.
                           'History',
                           // The agent the session holds, and the runs that wake it: a process
                           // in the sandbox, and threads that wait for it. What a run's
                           // prompt says is decided by WakePromptUtil, and the rules of the
                           // schedule by Schedule, which are both pure.
                           'Harness', 'AgentRunner',
                           // An application's handle on a lamp: starts the engine's process
                           // and reads its output. It decides nothing about the sandbox.
                           // LampLauncher is how it starts that process, and can be replaced.
                           'Lamp', 'LampLauncher',
                           // Supervised host access. It binds the socket the sandbox asks on and
                           // touches the host once a request is allowed, as Control and Egress
                           // do, and decides nothing: what a request means is HostTool, what the
                           // model is asked and how its answer is read is HostAccessChecker,
                           // where a path really goes is HostPaths (which reads nothing but the
                           // resolution of a path), and the effects are HostAccessTools.
                           'HostAccess', 'HostAccessTools', 'HostPaths', 'HostAccessChecker'] as Set

        and: 'the things only they may use'
            var effects = ['java.nio.file.Files', 'java.lang.ProcessBuilder', 'java.lang.Process',
                           'java.security.SecureRandom', 'java.lang.Thread', 'java.lang.System']

        when: 'a nested class is judged by the class it lives in, not by its own name'
            var offenders = [] as Set
            code.findAll { !(outermostNameOf(it) in allowed) }.each { type ->
                type.directDependenciesFromSelf.each { dependency ->
                    if (dependency.targetClass.fullName in effects)
                        offenders << "${type.simpleName} -> ${dependency.targetClass.simpleName}"
                }
            }

        then: 'no decision-making class reaches for an effect'
            offenders.isEmpty()
    }

    /**
     *  The name of the top-level class a type lives in. A helper nested inside an allowed class
     *  is part of that class's job - ArchUnit reports it under its own short name, which would
     *  otherwise make every private inner worker look like a new offender.
     */
    private static String outermostNameOf(type) {
        var outer = type
        while (outer.enclosingClass.present) outer = outer.enclosingClass.get()
        outer.simpleName
    }
}
