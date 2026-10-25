// oillamp's host tools for pi.
//
// oillamp writes this file into the agent's home, as ~/.pi/agent/extensions/oillamp-host-access.js,
// at the start of every session while the lamp's host access is switched on, and removes it when
// it is off. pi loads every extension in that directory.
//
// It only tells pi where an MCP server is: the session on the host, behind the socket below.
// pi starts socat, which copies MCP's lines between pi and that socket, and asks the server which
// tools there are. The host lists only the tools this lamp offers, checks every call against the
// lamp's settings, asks a model about it, and does the work only if that model allows it. So
// replacing this file gains an agent nothing: it has no capability of its own, only the socket,
// which checks anyway.
//
// pi names the tools mcp__oillamp__<tool>, such as mcp__oillamp__host_read_file.

// The session's socket, reached directly, as the scheduling tools reach theirs.
const SOCKET = "/oillamp/sockets/host/host-access.sock";

// How long pi waits for one call, in seconds. A call may wait for the checker, then for a person,
// then run a command for as long as the agent allowed; oillamp fills in the sum as it writes the
// file. pi's own default, 60 seconds, would give up on a call the host is still carrying out.
const TIMEOUT_SECONDS = /* timeout */0;

export default function (pi) {
    pi.registerMcpServer("oillamp", {
        command: "socat",
        args: ["STDIO", "UNIX-CONNECT:" + SOCKET],
        // Declared to the model like pi's own tools: there are few, and they are used often.
        exposure: "direct",
        timeout: TIMEOUT_SECONDS,
        description: "The user's own computer, outside this sandbox. oillamp judges every call first.",
    });
}
