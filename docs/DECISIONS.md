# Design decisions

Each entry says what was decided, why, and what was given up. Read this before changing one of
these things. If you change one, update its entry instead of adding a contradicting one, and say
why in the commit message.

The original design specification numbered its decisions (`D-01` and so on). The old number is
given at the end of each entry, only so that old commit messages and the
[archived specification](archive/oillamp-design-spec.md) can be traced. Do not use the numbers in
new code or documents.

For how the resulting system works, see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Sandbox and security

### Rootless podman is the container engine

podman runs as your user, without a background service running as root. If something escapes the
container, it lands in your account, not in root's. podman can also use other container runtimes
later (gVisor, libkrun) for a stronger boundary. Docker was not chosen because its usual setup is a
root-owned daemon.

*Given up:* a container shares the host's kernel. A kernel bug reachable from inside is an escape
route. A virtual machine would be stronger and much slower to start. *(D-01)*

### The threat model is the host, not the web

oillamp protects your machine, your files, your keys, your other projects and your local network
from mistakes and overreach by an agent, including instructions it picks up from a web page. It
does not try to stop a compromised agent from talking to the internet, and it is not designed to
withstand a determined kernel exploit.

*Why:* an agent that cannot install packages or read documentation is of little use. The line is
drawn at the host. *(no number)*

### The container has no network interface at all

The container runs with `--network=none`. The internet is reached only through an HTTP proxy that
oillamp runs on the host, reached over a Unix socket.

*Why:* there is nothing to misconfigure. A firewall rule can be wrong and fail open; a missing
network interface cannot. Tools that ignore the proxy settings simply get no network. The proxy
can apply a policy by host name and address and log every connection, without root and without
nftables.

*Given up:* tools that do their own DNS lookups do not work in the sandbox. *(D-12)*

### The network policy is checked against resolved addresses, and allows the web by default

The shipped policy allows everything except private, loopback, link-local, carrier-grade NAT and
IPv6 local ranges. Each host name is resolved on the host and every resulting address is checked.

*Why:* checking addresses means a public name that points at `127.0.0.1` or at the intranet is
refused by where it points, not trusted by what it is called. That is what makes "allow the web"
safe for the host. *(part of D-12)*

### Forwards are an explicit exception to the policy

A forward connects one fixed port inside the sandbox to one target you configured, such as a
company LLM service, without going through the proxy policy.

*Why:* agent tools expect a plain base URL, and routing an internal service through a policy that
blocks internal addresses would need an exception anyway. Forwards are named in the config, listed
in the agent guide and logged. *(D-13)*

### Two users inside the container: the agent is you, the infrastructure is nobody

Container uid 1000 (`agent`) is mapped to your host user with `--userns=keep-id:uid=1000,gid=1000`.
Container uid 1001 (`lamp`) is mapped to a subordinate id that owns nothing on the host, and runs
the compositor, VNC server, recorder and network bridges.

*Why:* files the agent writes in its home belong to you, so you can read, edit and commit them
without changing ownership. The infrastructure runs as a different user, so the agent cannot
signal it, reach its control sockets or alter the recording.

*Given up:* the agent has your uid. Its isolation comes from what the container can see, not from
file permissions. A mistake that mounts the wrong directory gives the agent your rights to it.
*(D-14)*

### The lamp has two layers

`<lamp>/` holds the configuration and oillamp's state. `<lamp>/agent-lamp-<id>/` is mounted into
the container as `/home/agent`, and it is the only part of the lamp the agent can change freely. Of
the state directory, only the session files (read-only), the three socket directories and the
recordings are mounted, each on its own. The configuration, keys and logs never are.

*Why:* the network policy, keys, logs and recordings are outside everything the agent can reach, so
the agent cannot rewrite the rules that restrict it. The agent id in the directory name means a
copied agent directory is never mistaken for a lamp. *(D-10)*

### The image's filesystem is read-only; only the agent's home persists

The container runs with `--read-only`. Anything the agent installs system-wide is gone at the end
of the session. Anything in `/home/agent` stays.

*Why:* the environment is reproducible and the agent cannot permanently change system tools. Extra
system packages are added through `image.extra_apt_packages`, which rebuilds the image. Language
package managers (npm, pip with `--user`, SDKMAN, Maven) install into the home and persist.
*(D-11)*

### Some lamp files belong to the infrastructure user, so deleting a lamp needs a command

The infra sockets and recordings are owned by the infra user, which is a subordinate id on the
host. `rm -rf` cannot delete them. `oillamp remove` deletes them through `podman unshare`.

*Why:* this ownership is what stops the agent tampering with its recording. The inconvenience is
the price, and a command removes it. `remove` needs `--yes`, because it deletes the agent's work
and a script may be driving it. It refuses while a sandbox for the lamp is running, and it works on
a lamp someone already half-deleted by hand. *(added during implementation)*

### Paths that are too long for Unix sockets go through a short runtime directory

Host-side socket paths are addressed through `$XDG_RUNTIME_DIR/oillamp/<agent id>/`, which holds a
symlink to the lamp's socket directory and the host-only sockets.

*Why:* Linux limits socket paths to 107 bytes, and lamp paths can be longer. *(D-25)*

---

## Desktop

### A headless Wayland compositor inside the container, watched over VNC

The sandbox runs its own compositor with a virtual screen. You watch it through a VNC viewer.

*Why:* nesting the agent's compositor as a window in your desktop would make it a client of your
session, with access to your clipboard and more. A VNC connection is a narrow, well-understood
boundary, and it allows several viewers and recording. *(D-02)*

### sway is the compositor

*Why:* it runs headless, is small, and has the best automation tools: `wayvnc`, `grim`,
`wf-recorder`, `wtype`, `wlrctl`. The desktop does not need to look like GNOME. *(D-03, D-06)*

### wayvnc on a Unix socket; TigerVNC as the viewer

*Why:* no TCP port exists anywhere. Access is controlled by file permissions. TigerVNC's
`vncviewer` connects to a socket path directly (verified). A built-in Swing viewer is a possible
later replacement. *(D-04, D-05)*

### Xwayland is included, and the agent is allowed on it

*Why:* Java Swing applications still draw through X11. Testing Swing applications is a primary use.
sway starts Xwayland as the infra user, so the entrypoint explicitly allows the agent user to
connect (`xhost +si:localuser:agent`). An X11 client can see and send input to other X11 windows
on the same display, but those are all the agent's own windows; it cannot use this to control sway
or reach the infrastructure's processes. *(D-07, extended during implementation)*

### The desktop helper `lamp` is a shell script

`lamp screenshot`, `click`, `type` and so on wrap `grim`, `wtype` and a small Python helper,
`lamp-pointer`, that sends mouse input through the desktop's VNC server.

*Why:* the original plan was a Java VNC client. It would have needed a protocol implementation,
PNG encoding and a build step that puts a jar into the image, for nothing the standard tools do not
already do. A script can be read and fixed by the agent itself, and the agent can call the tools
directly when the script does not do what it needs. *(D-27)*

The mouse was first driven with `wlrctl`, which turned out not to work: its moves are relative, and
each call's virtual mouse disappears when the call ends, taking the window's pointer focus with it,
so clicks reached nothing. Sending pointer events over a VNC connection gives absolute positions
and a device that stays for the whole move-and-click, and it is the same path the human's viewer
uses. The helper implements only the few messages needed to send pointer events. *(changed during
implementation)*

### Recording is off by default

*Why:* a continuous screen recording is an audit trail a user should switch on deliberately, not
find already running. The original plan had it on. *(changed during implementation)*

### The GPU is used when possible and never blocks a session

With `display.gpu = "auto"`, the render node is passed into the container only if everything lines
up. Otherwise the desktop uses software rendering and says why. The entrypoint also falls back to
software if the compositor cannot start with the GPU. oillamp prints the `usermod` command for a
missing group membership but does not run it, because it changes your account and only takes effect
after you log in again. *(D-24)*

### Clipboard flows into the sandbox only, by default

*Why:* pasting into the sandbox is useful. Copying out of it is how agent-produced content could
leak onto your clipboard unnoticed. *(D-23)*

---

## Sessions

### Your shell is SSH over a Unix socket

Each lamp has its own ed25519 key pair and a pinned host key.

*Why:* SSH gives the agent tools a standard login environment, works with `--network=none`, and
would still work across a future virtual-machine boundary. Your own SSH keys are never offered to
the sandbox and you are never asked to accept a host key. *(D-08)*

### A session ends in the terminal it was started from, not in the shell window

The session ends on Ctrl-C in the terminal `oillamp at` was started from, on closing that terminal,
or on `oillamp stop`. Closing the shell window or the viewer ends nothing; the user opens new ones
with `oillamp shell` and `oillamp view`.

The supervisor still relays the shell window's SSH connection itself, rather than watching the
terminal program: the session counts as up once that connection arrives, and `oillamp status`
reports when it has closed.

*Why:* the first design ended the session when the shell window closed. In use, that was wrong: a
user whose program hung in the shell closed the window to get a fresh shell, and lost the whole
sandbox instead. The terminal oillamp was started from is where the session reports what it is
doing, so it is the natural place to end it. Many terminal emulators (GNOME Terminal, Ptyxis) hand
the new window to a server process and exit immediately, so watching the terminal program would
say nothing about the window. *(D-09, changed on 2026-09-23)*

### Two new windows; the launching terminal is never taken over

`oillamp at` opens a new terminal window with the shell and a new viewer window. The terminal you
typed the command in stays in the foreground and keeps reporting the sandbox's health until the
session ends.

*Why:* that running report is where you look when something goes wrong. Replacing it with the shell
would hide it. Ctrl-C there is an obvious way to stop everything. *(D-16, refined during
implementation)*

### The terminal emulator is chosen from a table

oillamp knows nine terminal emulators and prefers your desktop's own. `terminal.profile` or
`terminal.command` override it.

*Why:* terminal emulators disagree on how to set a title and run a command. Keeping the differences
as data means adding one is a new table row. *(D-22)*

### A lamp keeps a history, and goes back to it

A lamp can be saved (`oillamp save`), listed (`oillamp history`) and brought back to an earlier
snapshot (`oillamp restore`). oillamp can also save by itself as each session starts and as it
ends, when anything changed. A snapshot holds the agent directory and `oillamp.toml`, never
`.oillamp/`.

*Why:* the sandbox keeps an agent away from the host, but inside its home an agent can still
delete a project or break its tools, and that work is lost. Nobody remembers to save before the
mistake, so oillamp can save by itself. *(added on 2026-09-29)*

### When oillamp saves by itself is the user's choice

Four switches under `[autosave]` say whether oillamp saves as a session starts, as it ends, just
before a run and as a run ends. The first two are off and the last two on unless the lamp says
otherwise. `oillamp autosave` and `LampDirectory.autosave` read and write them, and nothing else in
the file, only while the lamp sleeps. `oillamp save` and the save before a restore happen whatever
they say.

*Why:* saving a large home at every start and stop costs time and disk, and most sessions are not
worth going back to; a person saves by hand before the moments that matter. The saves around runs
are different: no person is watching a job run, and they are what the next run's prompt is made
of. An application can still turn them off.

*Given up:* with `after_run` off the agent is told nothing of its earlier runs but its notes, and
`schedule.max_agent_runs_per_day` counts only the runs of the session in progress, because the
history is where earlier ones are counted. *(added on 2026-10-20)*

### The history is a git repository that oillamp writes itself

The history is a bare git repository in `.oillamp/history/`, never mounted into the container.
oillamp writes git's objects directly, in Java, and never runs `git`.

*Why:* git's format gives content-addressed storage that keeps each version of a file once, and a
repository anyone can read with `git log`. But git's own commands cannot do the job: `git add`
stores a directory with its own `.git` as a pointer and drops its files, and `git checkout` refuses
to write a path through `.git`. Agents clone projects into their home, so those would be lost on a
restore. Writing the objects directly also means the host needs no `git`, and the history is part
of the lamp like every other file oillamp reads and writes itself.

*Given up:* git packs objects to save space and delta-compresses similar files; oillamp writes one
compressed file per object and reads only those, so the history is larger than git's would be, and
running `git gc` on it by hand would make it unreadable to oillamp. File times, owners and hard
links are not kept; exact permissions are, in a list of their own. *(added on 2026-09-29)*

### A restore needs the session stopped; a save does not

`oillamp restore` refuses while a session runs (`OIL-HISTORY-005`). `oillamp save` works either
way, and a save made while a session runs is marked as a running save. A restore always saves
first, and records itself as a new snapshot, so the history only grows and every restore can be
undone.

*Why:* rewriting the agent's home under running programs would leave them working in files that
changed beneath them. Saving while the session runs is the case that matters most, just before
letting the agent try something risky, and the worst it can do is catch a file half written, which
the mark warns about. *(added on 2026-09-29)*

### The session holds the agent, and wakes it on a schedule only while it runs

A running session owns one harness: pi in RPC mode, which the supervisor starts over ssh and alone
prompts. Jobs on the lamp's schedule, and `oillamp ask`, wake it; runs happen one at a time, and
the rest wait. Jobs run only while a session runs. Nothing wakes a lamp that is not running, so
only a person starts one. The schedule is off unless `schedule.enabled` is on, or the session was
started with `--enable-scheduling`.

*Why:* one owner is the only way to know whether the agent is busy, and the only way two runs
never write the same files at once. Tying the schedule to a running session keeps the system
small: there is no daemon, no second process that could start sandboxes behind the user's back,
and the terminal the session runs in shows everything that happens. Off by default, because every
run costs model tokens.

*Given up:* a schedule does nothing while the lamp is not running, and the terminal it was started
from has to stay open. A pi the user starts by hand in the sandbox's shell is not counted as busy;
running one alongside a run is the user's own risk. *(added on 2026-09-29)*

### Every run starts a fresh conversation, and the agent remembers through its notes

Each run is a new pi conversation. Its prompt carries what the agent needs from before: its own
notes in `~/workspace/NOTES.md`, which files the last three runs changed, and what the last run
said at its end. `AGENTS.md` tells the agent to keep the notes short and current. The lamp is saved
just before and just after every run, the run's snapshot always, with the agent's last message in
its commit message, unless `autosave.before_run` or `autosave.after_run` is off.

*Why:* a conversation that goes on across runs grows without end, costs more with every run, and
fills with detail that no longer matters. Notes the agent rewrites stay small, and they are in its
home, where the user can read and correct them. The two saves make the history a log of runs: what
the user changed and what each run changed are separate snapshots, and any run can be undone with
`oillamp restore`.

*Given up:* the agent only knows what it chose to write down. *(added on 2026-09-29)*

### A session's model applies to every run, old conversations too, and pi is told only when it differs

A session can be given a model, as the service names it: `oillamp at --model <id>`, or
`LampDirectory.model(id)`. Every run of the session is then answered by that model, whether it
starts a conversation or continues one. Two things see to that. The session writes the model as
pi's default (`defaultProvider` `edenai` and `defaultModel` in pi's `settings.json`), once the
sandbox is up and before pi starts, so that a new conversation starts on it by itself; see "The
engine sets up pi's files". And after opening the conversation, and moving within it if the
question says so, the harness compares the model pi reports in `get_state` with the session's
model, and sends `set_model` (provider `edenai`, the only one pi in a lamp has) only when they
differ, which is when an old conversation was on another model. A `set_model` that pi refuses
fails the run, with pi's reason and the model's name; the question is not asked. Without a model,
nothing is written and nothing is sent, and pi decides as it always did.

*Why:* pi writes the model it is on into each conversation's file, and goes back to that model
whenever the conversation is opened again. A default model in pi's settings therefore only
reaches new conversations; an old one stays on the model it started with. The default is still
worth writing: without it, every new conversation would start on another model and need a switch,
with the cost below. A genie broke that way:
its old chat was on a model the service had started refusing, while its settings named one that
worked. A user who changes the model expects the next answer to come from the new model,
whichever chat they are in. The earlier answers keep the model that gave them; when the model
changes, pi does not send the earlier model's reasoning to the new one, only the text of its
answers.

pi is only told when the model differs, because telling it is not free even for the model it is
on: `set_model` always writes a `model_change` entry into the conversation's file, and sets the
thinking level again from pi's settings, which can differ from the level the conversation was on.
Sending it before every question would fill the file with entries and could change the thinking
level unasked. With the same model, nothing changes, and pi sends the model its own earlier
reasoning back with each question, as before. Both were checked against real pi 1.3.0, with a
stand-in model service.

Failing the run is better than going on with the old model: the old model may be the very one that
fails, and a run answered by another model than the user chose would be a surprise found much
later.

The model is a setting of the session, given on the command line or by the application, not of
oillamp.toml. pi already keeps a default model of its own, in its settings in the agent's home,
which a user at the sandbox's terminal changes with pi's `/model`; a second, lasting setting in
oillamp.toml could silently disagree with it, and would only reach the runs, not a pi started by
hand. An application such as Genies keeps the user's choice in its own settings and hands it over
each time it starts the lamp, the way it hands over the model service and the key.

A running session can be given another model, with `oillamp model <dir> <id>` or
`Lamp.useModel(id)`, which reach the session over its control socket like `oillamp cancel`. It
applies from the next run on, whatever kind of run, and becomes pi's default at once: pi reads its
settings each time it starts or opens a conversation, also when its process has been running for
a while, so the next new conversation starts on it. The run in progress keeps the model it started
with, so that one answer never mixes two models. The change lasts for the session only. An
application changes the model when its user does, without starting the lamp again, which would end
everything running on the agent's desktop. The service and the key cannot be changed this way: the
relay holds them from the start of the session, and changing them needs a new session.

pi records the model with every answer, so `LampConversation.Entry.model` says which model wrote
each answer, and `LampAgentProgress.Answered.model` says it as the answer comes. An event from an
older engine, without it, reads with no model.

*Given up:* a model that is not in the service's list fails every run until the session is given
another one. Only pi's runs are covered: opencode keeps its own model, and a pi started by hand in
the sandbox gets the session's model only as its default. *(added on 2026-10-18; changed on
2026-10-18: the session writes pi's default too, which Genies did before)*

### The engine sets up pi's files; applications give it the model and the instructions

What pi needs from a session goes into pi's own files in the agent's home, and the engine writes
them, never an application: the session's model as pi's default in `~/.pi/agent/settings.json`,
keeping every other key, and the session's instructions in `~/.pi/agent/APPEND_SYSTEM.md`, which
pi adds to its system prompt. An application gives both to the lamp, with `LampDirectory.model`
and `LampDirectory.instructions` (by hand: `--model` and `--instructions-env`), and `Lamp.useModel`
later. `Harness` writes the files once the sandbox is up, before the first run starts pi, and
again for the default whenever the model changes; `PiSetupUtil` decides what goes in them.

A session without instructions leaves `APPEND_SYSTEM.md` as it is, and one without a model leaves
`settings.json` as it is. Both are in the agent's home, where the user, the agent or pi itself may
have written them, so a session that has nothing to say keeps out, even when an earlier session
wrote the file. To take instructions away, delete the file, or give a session other instructions.

The files are written without following a symbolic link anywhere in the agent's home: each
directory is opened through the previous one without following links, and the file is created
and renamed there. A link is a warning (`OIL-LAMP-012`), and the file stays unwritten. The same
now goes for oillamp's pi extensions in `~/.pi/agent/extensions`, which followed links before.
Settings that are not a JSON object are left alone, with a warning (`OIL-LAMP-013`).

The instructions reach the engine in its environment, in a variable the command line names:
`LampDirectory.instructions` puts them in `OILLAMP_INSTRUCTIONS` and passes
`--instructions-env OILLAMP_INSTRUCTIONS`. `at` reads the variable before anything else and
refuses one that is not set, empty or longer than 64 KiB.

*Why the engine:* which agent runs in a lamp, and how it keeps its settings, belongs behind
`dev.lamp`. An application that wrote these files itself would have to know pi's directory, its
file names and the keys of its settings, and to be careful with links on its own, and every change
in pi's layout would reach every application. With the files the engine's, an application uses a
small, stable API, and can be tested against it: by what it hands the lamp, on the engine's
command line and in its environment, not by which files appear in the agent's home.

*Why after the sandbox is up:* the sandbox's first start copies pi's settings from the image
into the agent's home, but only where the home has no such file yet. Those settings list pi's
packages, among them the pi-edenai extension that gives pi its one provider. A `settings.json`
written before that copy would keep it from happening.

*Why the environment for the instructions:* they can run over many lines and a few kilobytes.
On the command line they would be visible to every user of the machine in `ps`, and Linux limits
one argument to 128 KiB. The engine's standard input already has a job: the session lasts while
it is open, and `leave-running` arrives on it; mixing text into it would need framing, and would
work only for an embedded engine. A file would have to be written by the application, kept until
the engine has read it, and removed afterwards, also when the application crashes. The key
already travels in the environment, which only this user can read; the instructions go the same
way, in a variable of their own, and nothing is left behind. By hand that is one shell line:
`GENIE="$(cat about.md)" oillamp at <dir> --instructions-env GENIE`.

*Given up:* someone running `oillamp at` by hand puts the instructions in a variable rather than
naming a file. A file in the agent's home that the agent replaced by a link is not written until
someone removes the link. *(added on 2026-10-18)*

### The agent schedules its own jobs by asking the host, which applies the rules

The agent's tools (`schedule_add`, `schedule_list`, `schedule_remove`, `run_history`) are a small pi
extension that sends requests over a socket to the session, never touching the schedule file,
which is outside the sandbox. The session applies the same rules to the agent as to the user, plus
limits from `[schedule]`: how many jobs, how often each may run, how far ahead, when they end, and
how many runs a day. The agent cannot change or remove the user's jobs. Its jobs always end.

*Why:* the agent must not be able to give itself unlimited work, which costs the user money, or
undo what the user set up. Keeping the rules on the host means the extension can be as simple as
possible, and changing it in the sandbox gains the agent nothing. The limits are in `oillamp.toml`,
which the agent cannot see.

*Given up:* the tools are pi's only; another harness in the sandbox gets none, although it could
speak the socket's protocol itself. *(added on 2026-09-29)*

### Applications read conversations from pi's files, and ask through the session

`Lamp.conversations` parses pi's session files in the lamp directly. Asking, continuing a
conversation, moving within it, following a run and stopping it all go through the session's one
pi (`Lamp.send`, `Lamp.ask`, `Lamp.cancel`).

*Why:* reading files needs no running session and no process start, so an application can list
conversations of a lamp that is not running, and read them again after every change. Everything
that makes the agent act goes through the session, so that one pi does one run at a time, and
every run is saved before and after.

*Given up:* the reader depends on pi's session file format (version 3). A change to it in a new pi
needs a change in `InternalPiSessionFileUtil`. *(added on 2026-09-30)*

### Host access, when it exists, is judged on the host by a model with its own prompt

Planned, not built; see [HOST-ACCESS.md](HOST-ACCESS.md). The agent gets typed tools whose
implementation is on the host. Each request is judged by a separate model call that sees the
user's requirements, the request and the run's activity, but never the agent's conversation. The
tools are in tiers, deterministic checks enforce the outermost one, and the feature is off by
default with no command-line switch.

*Why:* permission dialogues are for people who enjoy permission dialogues. A judgement call made
per request, bounded by checks that do not depend on the model, and off unless asked for, is the
smallest thing that can work. The threat is a prompt injection, so the judge must not share the
poisoned context, and the judge may only ever tighten what the checks allow. *(added on
2026-10-18)*

---

## Host setup

### oillamp installs its own prerequisites with apt

On a Debian or Ubuntu host, missing packages are installed with `sudo apt-get`, after showing the
list and the reason for each. `--no-install` turns this off. A missing subordinate id range is
added with `sudo usermod`.

*Why:* one command from a stock desktop to a working sandbox. Other distributions get the package
list to install themselves. *(D-17)*

### `crun` and `slirp4netns` are required host packages

*Why:* Ubuntu 24.04 installs podman with `runc`, which cannot pass the GPU render group into the
container (`--group-add keep-groups`); without `crun`, the GPU is silently never used. And rootless
podman on Ubuntu 24.04 has no network backend without `slirp4netns`, which building the image needs
even though the sandbox itself runs without a network. Both were found on the first real-hardware
run. *(added during implementation)*

---

## Code

### Java 25, one Gradle module, the engine in one package, few public types

The engine, `dev.oillamp`, exposes two types: `OilLamp` and `Machine`. The events, problems and
exit codes it speaks in are public too, in `dev.lamp`, where an application that embeds oillamp
finds them.

*Why:* the public API is the part that cannot change without breaking someone, so it is kept small.
A single package lets the compiler enforce it: package-private classes cannot be used from outside.
The tests sit in a different package and so can only use the public API. Eight Gradle modules were
planned and dropped: one ArchUnit test enforces the same rules with far less build machinery.
*(D-26)*

### Decide in pure functions, act in a few named classes

Planning is done by functions that take values and return values. Only an allowlist of classes
touches files, processes, the clock or randomness, and a test enforces it.

*Why:* the logic can be tested quickly without podman, and `--dry-run` shows exactly what a real run
would do, because both build the same plan. A future Swing interface can drive the same core.
*(programming model of the original spec)*

### Other applications embed oillamp through `dev.lamp`, with the engine in its own process

A Java application, such as the Tribalism game, uses oillamp through a second package, `dev.lamp`,
in the same jar. Its `Lamp` type starts the engine (`dev.oillamp`) as a separate Java process with
`oillamp at <dir> --embedded`, using the classpath it was itself loaded from, and talks to it:
events arrive as JSON lines on the engine's standard output, requests go to the control socket, and
commands in the sandbox run over the existing SSH relay. `dev.lamp` never imports `dev.oillamp`.

An embedded session opens no windows and waits for no terminal. It ends when the application closes
the engine's standard input, which the operating system also does when the application dies. An
application that writes `leave-running` on that input first (`Lamp.leaveRunning()`) leaves the
session running on its own, until `oillamp stop`; the engine runs under `setsid`, so the closing
of the terminal the application was started from does not end it either.

An application that opens again finds such a session with `Lamp.at(dir).isRunning()` and joins it
with `join()`, which runs `oillamp follow <dir> --embedded`: the same events arrive, starting with
what the session is doing now, so the application uses the lamp as if it had started it.

*Why leaving running is asked for, not assumed:* an application that crashes says nothing, so its
sandbox still ends with it. Only a deliberate choice, such as a user closing Genies with a genie
that should keep its schedule, leaves a sandbox running.

*Why:* the supervisor is the sandbox's proxy, relays and shutdown sequence. Inside the
application's own process, a crash of the application would cut the agent's network mid-task and
skip the cleanup. As a separate process it shuts down properly, and `oillamp status`, `shell` and
`view` work on a lamp an application started. Starting the engine from the same jar means the
application and the engine are always the same version, so nothing has to be installed.

*Given up:* a second process per lamp, and the control protocol is no longer only spoken between
two copies of the command-line tool. *(added on 2026-09-28)*

### Libraries: Jackson 2 for TOML and JSON, Sprouts for collections, no CLI library

*Why:* Jackson 3 has no TOML module. Sprouts provides immutable collections that fit records.
picocli was dropped because the command line is small and hand-written parsing makes exit code 2
for every usage error easy to guarantee. There is no logging framework; oillamp reports through
events. *(D-19, D-20)*

### Configuration is TOML; arrays replace instead of merging

*Why:* TOML supports comments and has no YAML indentation traps. When a lamp lists network rules,
it gets exactly those rules, never a merge with the global file's rules, which nobody could reason
about. Unknown keys are errors, because a misspelled rule key would silently not apply. *(D-20)*

### The configuration file is read by walking the parsed tree, not by binding it to classes

*Why:* walking the tree keeps the key path (`network.rules[0].cidrs`), so every error can say
exactly where it is. *(changed during implementation)*

---

## Distribution

### One self-extracting file instead of a `.deb`

`./gradlew singleFile` builds one executable: a shell script with an archive of a reduced Java
runtime and the program attached. It unpacks itself into your cache directory on first run.

*Why:* a `.deb` must be installed as root and only suits Debian-family systems. The goal was a file
you can copy to a colleague and run. The original reason for bundling a runtime, "no JDK needed on
the host", still holds. *(D-21, changed during implementation)*

### No `oillamp image` command

*Why:* the image tag is a hash of everything that goes into it, so a changed input always produces a
rebuild and an unchanged one never does. There is nothing left to manage by hand. *(changed during
implementation)*

---

## The agent's environment

### Everyday shell tools are in the base image

`ping`, `vim`, `htop`, `dig`, `tmux` and about thirty others are always installed.

*Why:* an agent that cannot inspect its own environment wastes its turns guessing. *(added during
implementation)*

### Agent harnesses are built into the image; an update must succeed or change nothing

`opencode`, `pi` (with pi's Eden AI extension) and SDKMAN are installed while the image is built.
The harnesses go into `/usr/bin`. SDKMAN and pi's settings go into `/usr/local/share/oillamp/`,
and the entrypoint copies them into the agent's home at session start.

*Why:* `/home/agent` is a bind mount, so anything the image put there would be hidden at run time,
and SDKMAN must be writable. In an ordinary build a failed install is only a warning: the desktop,
shell and SSH are the product, the harnesses are a convenience. *(added during implementation)*

`oillamp update <lamp>` is the one exception. It builds a new image for one lamp, with the tool
layer forced to run again, and every install, version check and model list download must succeed.
Only then does the lamp switch to the new image. A failure leaves the lamp on its old image.

*Why:* the user asked for new tools, so a build that quietly kept old or missing ones would be
lying. Updating the image, rather than the home, keeps the harness in the read-only image, where
the agent cannot change it. All requested tools are refreshed together, and the choice is one
revision file, rather than a lock file or a version resolver. *(added 2026-10-15)*

### Debian's updates arrive through `update --system`, not by themselves

Podman keeps an image's base and its cached layers, so without help nothing in the image ever
gets newer. `oillamp update <lamp> --system` builds the whole image again with `--pull=always
--no-cache`. A session started from an image older than 30 days warns and names that command.

*Why:* a full build takes many minutes, so oillamp never starts one without being asked. Doing
it at every start would make starting slow; doing it never would leave Firefox and openssh
without security fixes, with nobody told. A warning leaves the timing to the user. *(added
2026-10-15)*

### Old images are the user's to remove; every build says so

oillamp removes only the image an `oillamp update` replaces. Every other build leaves the images
before it on disk, and warns (`OIL-IMAGE-003`) with the podman commands that list and remove them.

*Why:* which old images are still needed depends on which lamps the user still means to use, and
an image may be shared by several lamps. Guessing wrong would cost a 3.5 GB rebuild, or remove an
image another lamp needs. Saying nothing let the disk fill up on the development machine in two
days. *(added 2026-10-15)*

### The model key stays on the host; the sandbox reaches Eden AI through oillamp

The Eden AI key is never put into the sandbox: not into its environment, not into
`runtime.env`, not into any file the agent can read. Inside the sandbox, the harnesses send their
requests in plain HTTP to `http://127.0.0.1:3129/v3`, with a placeholder key. oillamp receives
each request on the host, replaces the placeholder with the real key, and sends it over HTTPS to
Eden AI's EU endpoint, `https://api.eu.edenai.run`, and nowhere else. The key comes from
`EDENAI_API_KEY` in the environment oillamp is started from, and stays in oillamp's memory.

*Why:* an agent can read everything in its sandbox, and whatever it can read, it can send
anywhere. Instructions picked up from a web page are enough to make it do so. With the key in the
sandbox, one such page could take the key away, to be used elsewhere for as long as it stays
valid. With the key on the host, the most a misled agent can do is use the model while its session
runs, through a channel oillamp sees. It also means the key, the endpoint and the model service
can change without rebuilding the image, and that every model request can be counted.

*Given up:* the harnesses speak plain HTTP inside the sandbox. That traffic never leaves the
container's loopback and the host's Unix socket, and the connection to Eden AI is HTTPS. *(added
on 2026-09-28, replacing the earlier design, in which the key was copied into the sandbox)*

### Eden AI is used only through its EU endpoint

oillamp forwards model requests to `https://api.eu.edenai.run` unless the user names another
service on the host (`model.service`, or `--model-service` and `Lamp.modelService` for one
session); nothing inside the sandbox can. With Eden AI, the harnesses offer only the models the EU
endpoint serves, and the shipped network policy refuses the global endpoint, `api.edenai.run`, for
anything in the sandbox that tries it directly (it would have no key anyway).

A service of the user's own, such as a model server on their machine, is not filtered by region:
its model list names no regions, so the filter would leave nothing, and where it runs is the
user's decision. oillamp tells the sandbox which applies, in `OILLAMP_MODEL_EU_ONLY`.

*Why:* the team requires its model traffic to stay in the EU. Holding the key on the host makes
this a property of oillamp rather than a setting inside the sandbox: a request can only be sent
with the key by oillamp, and oillamp only sends it to the EU. *(added on 2026-09-24, changed on
2026-09-28 when the key moved to the host, and again when services of the user's own were
allowed)*

### One environment for every kind of shell

oillamp writes `~/.bashrc` (once) that reads `/etc/profile.d/oillamp.sh`.

*Why:* `/etc/profile` is only read by login shells. Without the `.bashrc`, `ssh <lamp> 'command'`,
which is how scripts and agents drive the sandbox, got no proxy settings, no display and no `sdk`.
The profile script can be read more than once safely, because Debian's `/etc/profile` resets `PATH`
and a simple "already done" guard would lose entries. *(added during implementation)*

### The sandbox describes itself to the agent

`~/AGENTS.md` is rewritten each session from the live configuration. It says what persists, how to
use the desktop, that there is no DNS, what the proxy policy's default is, how a denial looks, and
what the agent cannot do.

*Why:* an agent that does not know where it is will try `sudo apt install`, conclude the network is
broken, or retry a denied request. A paragraph of accurate text prevents that. *(from the original
spec)*
