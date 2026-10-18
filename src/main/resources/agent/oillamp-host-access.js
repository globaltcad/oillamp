// oillamp's host tools for pi.
//
// oillamp writes this file into the agent's home, as ~/.pi/agent/extensions/oillamp-host-access.js,
// at the start of every session while the lamp's host access is switched on, and removes it when
// it is off. pi loads every extension in that directory.
//
// Nothing here does anything to the host. Each tool sends one request to the session on the host,
// through the socket below, and shows the agent what the host answered. The host checks the
// request against the lamp's settings, asks a model about it, and does the work only if that
// model allows it. So replacing this file gains an agent nothing: it has no capability of its
// own, only the socket, which checks anyway.
//
// The protocol is one JSON object per line, one request per connection. The request does not
// say which run it belongs to: the session knows which run the agent is in, and judges by that.

import { Type } from "@earendil-works/pi-ai";
import net from "node:net";

// The bridge the sandbox gives the extension: a TCP port that socat forwards to the session's
// socket. The socket itself is the fallback, for a sandbox where the bridge did not start.
const ADDRESS = process.env.OILLAMP_HOST_ACCESS || "";

function connect() {
    if (!ADDRESS.startsWith("http://")) return net.createConnection("/oillamp/sockets/host/host-access.sock");
    const url = new URL(ADDRESS);
    return net.createConnection(Number(url.port), url.hostname);
}

// A request may wait for a person, so the socket waits longer than a tool call normally would.
const PATIENCE_MS = 120000;

// The tools this lamp offers. oillamp fills this in as it writes the file, so the agent sees only
// tools that can be allowed: a closed tier is absent rather than a tool that always refuses.
const OFFERED = new Set(/* offered */[]);

function register(pi, tool) {
    if (OFFERED.has(tool.name)) pi.registerTool(tool);
}

function ask(request, signal) {
    return new Promise((resolve, reject) => {
        const socket = connect();
        let answer = "";
        socket.setEncoding("utf8");
        socket.setTimeout(PATIENCE_MS, () => socket.destroy(new Error("oillamp did not answer in time")));
        socket.on("connect", () => socket.end(JSON.stringify(request) + "\n"));
        socket.on("data", (chunk) => { answer += chunk; });
        socket.on("end", () => {
            try {
                resolve(JSON.parse(answer.split("\n")[0]));
            } catch {
                reject(new Error("oillamp answered with something that is not JSON"));
            }
        });
        socket.on("error", (error) => reject(new Error(
            error.code === "ENOENT" || error.code === "ECONNREFUSED"
                ? "oillamp's host access cannot be reached: the session is ending, or the user switched it off"
                : error.message)));
        if (signal) signal.addEventListener("abort", () => socket.destroy(new Error("stopped")));
    });
}

async function call(tool, args, signal) {
    const reply = await ask({ op: "host", request: JSON.stringify({ tool, args }) }, signal);
    if (!reply.ok) throw new Error(reply.error || "the host refused, without saying why");
    return { content: [{ type: "text", text: reply.result ?? "Done." }], details: undefined };
}

export default function (pi) {
    register(pi, {
        name: "host_list_dir",
        label: "List a directory on the user's machine",
        description:
            "List the files in a directory on the user's own computer. This is outside the sandbox: " +
            "oillamp checks the path and asks its checker before you see anything, and may refuse.",
        parameters: Type.Object({
            path: Type.String({ description: "An absolute path, or one starting with ~/, such as ~/Documents." }),
        }),
        async execute(_id, params, signal) {
            return call("host_list_dir", { path: params.path }, signal);
        },
    });

    register(pi, {
        name: "host_stat",
        label: "Describe a file on the user's machine",
        description: "Say whether a path on the user's own computer exists, its size, when it changed, and its permissions.",
        parameters: Type.Object({
            path: Type.String({ description: "An absolute path, or one starting with ~/." }),
        }),
        async execute(_id, params, signal) {
            return call("host_stat", { path: params.path }, signal);
        },
    });

    register(pi, {
        name: "host_read_file",
        label: "Read a file on the user's machine",
        description:
            "Read a text file on the user's own computer. Large files are cut; ask again with offset to read on. " +
            "Binary files are refused. oillamp checks the path and asks its checker first.",
        parameters: Type.Object({
            path: Type.String({ description: "An absolute path, or one starting with ~/." }),
            offset: Type.Optional(Type.Number({ description: "Byte to start at. Omit for the beginning." })),
            max_bytes: Type.Optional(Type.Number({ description: "How much to read. The lamp has its own, smaller limit." })),
        }),
        async execute(_id, params, signal) {
            return call("host_read_file", {
                path: params.path,
                offset: String(params.offset ?? 0),
                max_bytes: String(params.max_bytes ?? 0),
            }, signal);
        },
    });

    register(pi, {
        name: "host_open_url",
        label: "Open a page in the user's browser",
        description:
            "Open an http or https page in the browser on the user's own desktop. This is a visible action: " +
            "the user will see the window, and the page's server sees the address. oillamp asks its checker first.",
        parameters: Type.Object({
            url: Type.String({ description: "An http:// or https:// address." }),
        }),
        async execute(_id, params, signal) {
            return call("host_open_url", { url: params.url }, signal);
        },
    });

    register(pi, {
        name: "host_show",
        label: "Show a file to the user",
        description:
            "Open a file on the user's own computer with their default application, such as a PDF or an image. " +
            "Programs, scripts, launchers and executable files are refused.",
        parameters: Type.Object({
            path: Type.String({ description: "An absolute path, or one starting with ~/." }),
        }),
        async execute(_id, params, signal) {
            return call("host_show", { path: params.path }, signal);
        },
    });

    register(pi, {
        name: "host_write_file",
        label: "Write a file on the user's machine",
        description:
            "Write a text file on the user's own computer, creating it or replacing it. The user can see and undo it. " +
            "oillamp asks its checker first, and refuses paths outside the lamp's roots.",
        parameters: Type.Object({
            path: Type.String({ description: "An absolute path, or one starting with ~/." }),
            text: Type.String({ description: "The whole content of the file." }),
        }),
        async execute(_id, params, signal) {
            return call("host_write_file", { path: params.path, text: params.text }, signal);
        },
    });

    register(pi, {
        name: "host_notify",
        label: "Notify the user",
        description: "Put a desktop notification on the user's screen.",
        parameters: Type.Object({
            title: Type.String({ description: "The line the user sees first." }),
            text: Type.String({ description: "The rest of the message." }),
        }),
        async execute(_id, params, signal) {
            return call("host_notify", { title: params.title, text: params.text }, signal);
        },
    });

    register(pi, {
        name: "host_copy_out",
        label: "Copy a file from the sandbox to the user's machine",
        description:
            "Copy a file from your home in the sandbox to the user's own computer, any kind of file, up to 64 MiB. " +
            "The copy is never made executable or run. oillamp asks its checker first.",
        parameters: Type.Object({
            from: Type.String({ description: "The file in the sandbox, such as ~/workspace/report.pdf." }),
            path: Type.String({ description: "Where to put it on the user's machine, such as ~/Downloads/report.pdf." }),
        }),
        async execute(_id, params, signal) {
            return call("host_copy_out", { from: params.from, path: params.path }, signal);
        },
    });

    register(pi, {
        name: "host_run",
        label: "Run a program on the user's machine",
        description:
            "Run one of the programs the user allowed, on their own computer, and return its output. " +
            "The lamp names the programs it allows; if this tool is here, the list is not empty. " +
            "oillamp asks its checker first, and the user before anything runs.",
        parameters: Type.Object({
            executable: Type.String({ description: "The program's name, as the user allowed it, such as inkscape." }),
            args: Type.Optional(Type.String({ description: "Its arguments, written as on a command line. Never run through a shell." })),
        }),
        async execute(_id, params, signal) {
            return call("host_run", { executable: params.executable, args: params.args ?? "" }, signal);
        },
    });
}
