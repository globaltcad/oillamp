# Working on oillamp

## Where things are explained

| Question | Document |
|---|---|
| What is oillamp, and how do I use it? | [README.md](README.md) |
| What are containers, uid maps, Wayland, VNC, Spock…? | [docs/TECH-STACK.md](docs/TECH-STACK.md) |
| How does the application work, and where does its state live? | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Why is it built this way? | [docs/DECISIONS.md](docs/DECISIONS.md) |
| What works, what is verified, what is missing? | [docs/STATUS.md](docs/STATUS.md) |
| How do I use Sprouts collections? | [docs/SproutsCheatSheet.md](docs/SproutsCheatSheet.md) |

`docs/archive/oillamp-design-spec.md` is the original design, kept for history. It is out of date.
Do not cite it.

When you change a design decision, update its entry in DECISIONS.md. When you find or close a gap
between what the code does and what anything says it does, update STATUS.md.

## Build and test

You need a JDK 25. Gradle downloads itself through the wrapper.

```sh
./gradlew build          # compile, run the scenarios, check the architecture rules
./gradlew test           # the scenarios only; then read build/spock-reports/*.md
./gradlew spikes         # checks against real podman; slow, needs podman and a network
./gradlew installDist    # build/install/oillamp/bin/oillamp, for development
./gradlew singleFile     # build/dist/oillamp, the single-file executable
```

`./gradlew build` must stay fast and must pass on a machine without podman. Anything that needs a
real container belongs in a spike (tag it `spike`).

After changing anything under `src/main/resources/image/`, rebuild with `installDist` or
`singleFile` before trying it. The image tag is a hash of those files, so the next session builds a
new image automatically, but only if you run the new build of oillamp. Old images are not removed;
clear them now and then with `podman images localhost/oillamp/sandbox` and `podman rmi`.

## Getting to know the code

1. **Run it with `--dry-run`.** `oillamp at /tmp/x --dry-run --verbose` prints everything a real
   run would do, including the full `podman run` command, and changes nothing. Read that output
   next to `LampPlanUtil.java`.
2. **`OilLamp.java`**: the entry point.
3. **`Machine.java`**: every effect the program can have on your computer. The exceptions are the
   lamp's own files (`FilesystemUtil.java`) and the sockets of a running session (`Relay.java`,
   `Control.java`, `Egress.java`).
4. **`Step.java`**: every kind of change oillamp can make during setup.
5. **`LampPlanUtil.java`**: a pure function turning facts into a plan. The clearest example of the
   style of the whole code base.
6. **`SandboxPhase.java`**: where the container command is built.
7. **`SessionMachine.java`**: every rule about how a session starts and ends.
8. **`src/main/resources/image/rootfs/usr/local/lib/oillamp/entrypoint`**: the container's first
   process.

### The difficult parts

- **The entrypoint** is a bash script of about 330 lines running as the container's first process.
  It is the least type-checked part of the system and the one with the biggest consequences when it
  breaks. `The_Sandbox_Image_Spec` checks that it parses, and the spikes run it for real.
- **Unix socket paths are limited to 107 bytes.** Host-side sockets are reached through the short
  runtime directory under `$XDG_RUNTIME_DIR`. Any change that moves a socket must respect this.
- **`/home/agent` is a bind mount**, so anything the image puts there at build time is hidden when
  the container runs. SDKMAN and pi's settings are installed elsewhere and copied in by the
  entrypoint, entry by entry, only where the home has nothing yet; any new tool that installs into
  the home needs the same treatment. That is also why the session writes its model into pi's
  settings only once the sandbox is up: a file there before the copy would keep it from happening.
- **The agent can change anything in its home**, and on the host it runs as you. oillamp writes
  files under the home's `.pi/` only with `FilesystemUtil.writeInAgentHome`, which never follows
  a link the agent may have put there.
- **The agent runs as your uid.** Its isolation comes from what the container can see. Think twice
  before attaching another directory or changing the uid map.

## Coding conventions

- Data is immutable `record`s. Validation happens in the record's constructor, so an invalid value
  cannot exist.
- Collections in records are Sprouts persistent collections (`Tuple`, `ValueSet`, `Association`),
  never `java.util.List`, `Set`, `Map` or arrays. See
  [docs/SproutsCheatSheet.md](docs/SproutsCheatSheet.md).
- Alternatives are `sealed interface`s with record cases, handled with exhaustive `switch`
  statements without `default`, so adding a case forces every `switch` to be updated.
- No `null`. Absence is `Optional`. NullAway checks this during compilation.
- Time comes from `Machine.now()`, never `Instant.now()`.
- Only final Java 25 features; no preview features.

## Architecture rules the build enforces

`The_Shape_Of_The_Code_Spec` fails the build if:

- a third type in `dev.oillamp`, or an unlisted one in `dev.lamp`, becomes `public`;
- `dev.lamp` imports anything from `dev.oillamp`;
- a public method uses a package-private type in its signature;
- a class outside the allowlist uses files, processes, threads, `System` or `SecureRandom`.

If you need to change one of these rules, change the test deliberately and say why in the commit.

## How the tests are organised

All tests are Spock specifications. The engine's are in `src/test/groovy/oillamp/`, a package
outside `dev.oillamp`, so they can only use the public types, like any other caller: `dev.lamp`,
`OilLamp.run` and the command line, the sockets a session serves, and the files it writes.
Genies' are in `src/test/groovy/gui/`, except two in `src/test/groovy/dev/gui/view/` that reach
package-private view code.

### Scenarios

Each scenario describes a situation a user can be in and runs the real `OilLamp.run(...)` against a
`SimulatedMachine`. The helper `ScenarioHost.groovy` sets up a simulated Ubuntu machine with a
temporary home; a scenario changes what it needs:

```groovy
host.machine { it.withoutPodman().withoutSubordinateIds() }
var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')
outcome.reported('OIL-PKG-001')
```

Files in the lamp are real, in a temporary directory: the lamp's security is made of permission
bits, ownership and symlinks, and a simulated filesystem would hide bugs where they matter most.
Session scenarios run a real supervisor with real Unix sockets; only the container is simulated.
Scenarios need no podman, no network and no display, and together they run in about two minutes.

| Spec | About |
|---|---|
| `Checking_The_Machine_Spec` | `doctor` and host prerequisites |
| `Setting_Up_A_Lamp_Spec` | creating, reusing, refusing and removing lamps; recordings |
| `Configuring_A_Lamp_Spec` | the configuration file and its validation |
| `Starting_The_Sandbox_Spec` | readiness and socket checks |
| `Supervising_A_Session_Spec` | a whole session: windows, shutdown, failures, published web servers |
| `The_Session_Commands_Spec` | `status`, `stop`, `shell`, `view`, `list` |
| `Waking_The_Agent_Spec` | the schedule, `ask`, runs and their snapshots, and the agent's scheduling tools, with a stand-in pi |
| `Deciding_What_The_Sandbox_May_Reach_Spec` | the egress proxy and policy, over the real socket |
| `Using_The_Command_Line_Spec` | usage errors, version, crash handling |
| `The_Lamp_Command_Spec` | the `lamp` script, with stub tools on `PATH` |
| `The_Pointer_Spec` | what `lamp click`, `move`, `drag` and `scroll` send, against a stand-in VNC server |
| `The_Launcher_Spec` | the single-file launcher script |
| `The_Sandbox_Image_Spec` | static checks of the image files |
| `Updating_The_Agent_Tools_Spec` | `update` and `update --system`: a new image for one lamp, kept only when its build succeeds; the warnings about old images |
| `The_Shape_Of_The_Code_Spec` | the public types, `dev.lamp` staying apart from the engine, and the deciding/doing split |
| `Keeping_The_Model_Key_On_The_Host_Spec` | the model relay: the sandbox sends a placeholder, the host adds the real key |
| `Reaching_The_Host_Spec` | host access: every request judged by a stand-in checker before it happens; tiers, roots, the person, the log |
| `Working_On_The_Host_Spec` | `host_edit_file` and `host_bash` on the host, with `host_access.commands`, timeouts and cancellation |
| `Serving_The_Host_Tools_Over_Mcp_Spec` | the host tools as an MCP server: initialize, tools/list, JSON-RPC errors, connections |
| `Showing_The_User_The_Desktop_Spec` | `lamp show` telling the host there is something to look at |
| `Keeping_Windows_On_The_Desktop_Spec` | floating windows kept in reach when the desktop changes size |
| `Giving_The_Agent_A_Git_Identity_Spec` | the name and email on the agent's commits |
| `Travelling_Back_In_Time_Spec` | `save`, `history` and `restore` |
| `Keeping_History_Within_A_Disk_Budget_Spec` | the initial-size allowance, automatic pruning, shallow branches, previews, active-run bases and retained run numbers |
| `Asking_In_A_Conversation_Spec` | `ask --in`, `--after`, `--instead-of` |
| `Reading_The_Agents_Conversations_Spec` | `conversations` and `Lamp.conversations` |
| `Following_A_Run_Spec` | `ask --no-wait`, `cancel`, and following a run through `Lamp` |
| `Following_A_Session_Spec` | `follow` |
| `Embedding_A_Session_Spec` | `oillamp at --embedded`, started by another application |
| `The_Event_Wire_Format_Spec` | the one-line JSON events between the engine and an application |
| `Holding_A_Lamp_Spec` | an application holding a lamp through `dev.lamp.Lamp` |
| `Choosing_The_Model_For_A_Lamp_Spec` | an application choosing the model service and key |
| `Choosing_The_Agents_Model_Spec` | `--model`, `oillamp model` and the Lamp API's: one model for every run, old conversations too, written as pi's default; how a model gets from an application to an answer; with a stand-in pi that keeps track of its model |
| `Giving_The_Agent_Instructions_Spec` | `--instructions-env` and `LampDirectory.instructions`: the text's way from an application to pi's `APPEND_SYSTEM.md`, leaving pi's files alone without, and refusing links the agent planted |
| `Listing_A_Services_Models_Spec` | `Lamp.models`, against a stand-in server: where it asks, the key, the errors |

Genies' scenarios, in `src/test/groovy/gui/` and `src/test/groovy/dev/gui/view/`, run its model
and its `GenieRunner` without a window: `KeepingManyGeniesSpec`, `KeepingGeniesBetweenRunsSpec`,
`KeepingAGenieAliveSpec`, `KeepingAGeniesHistorySpec`, `DrawingAGeniesBranchesSpec`, `KeepingAGeniesScheduleSpec`,
`PlanningAGeniesWeekSpec`, `FollowingAConversationSpec`, `BranchingAConversationSpec`,
`SettingUpAModelSpec`, `FindingOllamaSpec`, `NoticingWhatWentWrongSpec`,
`WatchingAGeniesDesktopSpec`, `ZoomingIntoAGeniesDesktopSpec`, `DrawingAGenieSpec` and
`ReadingAGeniesMarkdownSpec`.

### Spikes

A simulation only knows what its author already believed about podman, sway or sshd. Spikes check
those beliefs against the real tools: they pull images, build the real image (without the
toolchain) and start real containers. They are tagged `spike`, excluded from `test`, and skip
themselves when podman is not available. `Spike.groovy` runs commands through `Machine.real()`, the
same code path oillamp uses.

| Spec | Checks |
|---|---|
| `Verifying_Podman_Assumptions_Spec` | rootless podman on Ubuntu; the uid mapping; read-only root with writable mounts; Unix sockets in both directions across the container boundary |
| `Verifying_The_Image_Base_Spec` | every package the image needs exists in Debian trixie |
| `Verifying_Terminal_Profiles_Spec` | installed terminals accept oillamp's arguments; terminal emulators return before their window closes |
| `Verifying_The_Sandbox_Desktop_Spec` | the real image: readiness, SSH login as `agent`, screen size and rendering, vncviewer on a Unix socket, clicking and typing with `lamp`, a playable recording, a clean second session |
| `Running_A_Lamp_For_An_Application_Spec` | a lamp's whole life through `dev.lamp.Lamp`, with the engine as a separate process |
| `Verifying_Published_Web_Servers_Spec` | `[web]` in a real sandbox: a listed port and, with `publish = "any"`, a port the agent opens, both answering an HTTP request from this machine; `/proc/net/tcp` read with `podman exec --user lamp` |
| `Two_Lamps_At_Once_Spec` | one application holding two lamps that must not see each other |
| `When_An_Application_Or_Its_Sandbox_Dies_Spec` | the application crashing, or the container dying |
| `Keeping_The_Model_Key_Out_Of_A_Real_Sandbox_Spec` | the model key absent from a real sandbox; real model requests when `EDENAI_API_KEY` is set |
| `Using_A_Model_On_This_Machine_Spec` | a lamp using Ollama on this machine |
| `RunningARealGenieSpec` | a genie as Genies runs one: real lamp, real pi, real desktop |
| `UsingGeniesForRealSpec` | the Genies app's actions, as its buttons call them, against real lamps |

The results of these checks, and what was tested by hand on real hardware, are in STATUS.md.

## Writing comments, documentation and test descriptions

The people maintaining oillamp are programmers, not container or Linux-graphics specialists. Write
for them.

- **Say the thing.** Do not point at it. "Unix socket paths are limited to 107 bytes, so sockets are
  reached through the short runtime directory" is useful. "See D-25" or "per §9.2" is not.
  Requirement codes, section numbers, spike numbers (S1, S2, …) and milestone names (M3, M4, …) mean
  nothing to a reader who was not there.
- **Name the concept.** Write "the infra user (container uid 1001)", not "the other user" or "the
  one described above".
- **Explain the reason, once, where it applies.** A comment that says *why* is worth keeping; a
  comment that restates the code is not.
- **Keep it true.** A comment that describes how things used to be is worse than none. When you
  change behaviour, search for comments and documents that describe it.
- **Plain sentences.** Short sentences, ordinary words, few dashes. Avoid figures of speech and
  words like "load-bearing".
- **Define a term the first time** in a document if a reader might not know it (user namespace,
  subordinate id, bind mount, compositor).
- **Doc comments are Markdown** (`///`, supported since Java 23), not HTML Javadoc: `code` in
  backticks, `[OtherType#method]` for a link, a blank `///` line between paragraphs, `-` for a
  list. Groovy has no Markdown comments, so the Spock specs keep `/** */`.
- **Javadoc:** the first sentence says what the type or method is. Explain visibility only for the
  public types; everything else is package-private by default and needs no comment about it.
- **Scenarios:** the name says what the user experiences. The `reportInfo` block explains, without
  referring to other documents, why the behaviour matters. It is rendered into the test report and
  should make sense on its own. A specification whose scenarios only make sense with the mechanism
  behind them says how it works in a `@Narrative`, with a drawing of the flow in a ``` block where
  it helps. Both are rendered as Markdown, without the source's indentation, by the template in
  `src/test/resources/spock-reports/`. The labels of `given:`, `when:`, `then:` and `and:` read as
  sentences, and helpers are named for what they mean in the scenario.

## License

oillamp is MIT licensed (see `LICENSE`), and so is what you contribute to it.

## Commit messages

Say what changed and why, in plain words. If a change fixes something found on real hardware, add
it to "Lessons from real hardware" in STATUS.md.
