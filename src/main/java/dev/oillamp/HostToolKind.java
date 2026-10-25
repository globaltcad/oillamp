package dev.oillamp;

import java.util.Optional;

import dev.lamp.LampHostRisk;

import sprouts.Tuple;

/// Every host tool there is, as the agent is offered it: its name, its tier, what it is for, and
/// the arguments it takes.
///
/// This is the one place a tool is described. The MCP server lists tools from here, the agent's
/// guide summarises them from here, and [HostTool#read] checks each call's arguments against the
/// same parameters, so the schema the agent was shown and the one it is held to cannot drift
/// apart.
///
/// A kind is what the agent may ask for; a [HostTool] record is one such request, with its
/// arguments read.
enum HostToolKind {

    LIST_DIR("host_list_dir", LampHostRisk.PASSIVE,
            "List the files in a directory on the user's own computer, outside the sandbox. "
          + "oillamp checks the path and asks its checker before you see anything, and may refuse.",
            "list a directory",
            Parameter.text("path", "An absolute path, or one starting with ~/, such as ~/Documents.")),
    STAT("host_stat", LampHostRisk.PASSIVE,
            "Say whether a path on the user's own computer exists, its size, when it changed, and its permissions.",
            "say whether a path exists, its size, times and permissions",
            Parameter.text("path", "An absolute path, or one starting with ~/.")),
    READ_FILE("host_read_file", LampHostRisk.PASSIVE,
            "Read a text file on the user's own computer. Large files are cut; ask again with offset to read on. "
          + "Binary files are refused. oillamp checks the path and asks its checker first.",
            "read a text file; large files are cut, ask again with `offset`",
            Parameter.text("path", "An absolute path, or one starting with ~/."),
            Parameter.optionalNumber("offset", "Byte to start at. Omit for the beginning."),
            Parameter.optionalNumber("max_bytes", "How much to read. The lamp has its own, smaller limit.")),
    CLIPBOARD_READ("host_clipboard_read", LampHostRisk.PASSIVE,
            "Read the text on the user's clipboard.",
            "read the user's clipboard"),
    SHOW("host_show", LampHostRisk.PASSIVE,
            "Open a file on the user's own computer with their default application, such as a PDF or an image. "
          + "Programs, scripts, launchers and executable files are refused.",
            "open a file, such as a PDF or a picture, with the user's own application",
            Parameter.text("path", "An absolute path, or one starting with ~/.")),
    // Opening a page sends whatever the address holds to whoever serves it, so it is a way out
    // for anything the agent has read, not a read.
    OPEN_URL("host_open_url", LampHostRisk.ADDITIVE,
            "Open an http or https page in the browser on the user's own desktop. This is a visible action: "
          + "the user will see the window, and the page's server sees the address. oillamp asks its checker first.",
            "open an http or https page in the user's browser",
            Parameter.text("url", "An http:// or https:// address.")),
    WRITE_FILE("host_write_file", LampHostRisk.ADDITIVE,
            "Write a text file on the user's own computer, creating it or replacing it, as rw-r--r--. "
          + "oillamp asks its checker first, and refuses paths outside the lamp's roots.",
            "write a text file, creating or replacing it",
            Parameter.text("path", "An absolute path, or one starting with ~/."),
            Parameter.textOrEmpty("text", "The whole content of the file.")),
    EDIT_FILE("host_edit_file", LampHostRisk.ADDITIVE,
            "Edit a text file on the user's own computer: replace one exact piece of its text with another. "
          + "old_text must occur exactly once; include enough of the lines around it to make it unique. "
          + "The file is written as rw-r--r--, so an executable file, such as a script, is no longer "
          + "executable afterwards; with host_bash, chmod +x makes it so again. oillamp asks its checker first.",
            "replace one exact piece of a text file with another",
            Parameter.text("path", "An absolute path, or one starting with ~/."),
            Parameter.text("old_text", "The text to replace, exactly as it is in the file, once."),
            Parameter.textOrEmpty("new_text", "What it becomes.")),
    CLIPBOARD_WRITE("host_clipboard_write", LampHostRisk.ADDITIVE,
            "Replace the text on the user's clipboard.",
            "replace the user's clipboard",
            Parameter.textOrEmpty("text", "The new clipboard text.")),
    NOTIFY("host_notify", LampHostRisk.ADDITIVE,
            "Put a desktop notification on the user's screen.",
            "show the user a desktop notification",
            Parameter.text("title", "The line the user sees first."),
            Parameter.textOrEmpty("text", "The rest of the message.")),
    COPY_OUT("host_copy_out", LampHostRisk.ADDITIVE,
            "Copy a file from your home in the sandbox to the user's own computer, any kind of file, up to 64 MiB. "
          + "The copy is never made executable or run. oillamp asks its checker first.",
            "copy a file from your home here to the user's computer",
            Parameter.text("from", "The file in the sandbox, such as ~/workspace/report.pdf."),
            Parameter.text("path", "Where to put it on the user's machine, such as ~/Downloads/report.pdf.")),
    BASH("host_bash", LampHostRisk.EXECUTING,
            "Run a bash command on the user's own computer, not in the sandbox, and return its exit status "
          + "and output. It runs as the user, in the directory cwd, with no input. "
          + "oillamp shows its checker the whole command first, and may refuse it.",
            "run a bash command on the user's computer, in a directory you name",
            Parameter.text("command", "The command, as you would type it into bash."),
            Parameter.text("cwd", "The directory it runs in: an absolute path, or one starting with ~/."),
            Parameter.optionalNumber("timeout_seconds", "How long it may take, from 1 to "
                    + HostTool.LONGEST_COMMAND.toSeconds() + " seconds. " + HostTool.USUAL_COMMAND.toSeconds()
                    + " when omitted. It is stopped, with everything it started, when the time is up."));

    /// The name the agent calls the tool by, such as `host_read_file`.
    final String toolName;
    /// The tool's tier. It is the ceiling: the checker may put a request lower, never higher,
    /// and [HostAccess] refuses a request in a tier that is closed.
    final LampHostRisk tier;
    /// What the model reads about the tool when it is listed.
    final String description;
    /// A few words for the agent's guide.
    final String summary;
    /// The arguments it takes, in the order they are listed. A Sprouts `Tuple` cannot be changed,
    /// though Error Prone cannot know that.
    @SuppressWarnings("ImmutableEnumChecker")
    final Tuple<Parameter> parameters;

    HostToolKind(String toolName, LampHostRisk tier, String description, String summary, Parameter... parameters) {
        this.toolName = toolName;
        this.tier = tier;
        this.description = description;
        this.summary = summary;
        this.parameters = Tuple.of(Parameter.class, parameters);
    }

    /// The kind with that name, or empty when there is no such tool.
    static Optional<HostToolKind> named(String toolName) {
        for (HostToolKind kind : values()) if (kind.toolName.equals(toolName)) return Optional.of(kind);
        return Optional.empty();
    }

    /// One argument of a tool.
    ///
    /// @param name        as the agent writes it, such as `max_bytes`
    /// @param type        what kind of JSON value it must be
    /// @param required    whether a call without it is refused
    /// @param description what the model reads about it
    record Parameter(String name, Type type, boolean required, String description) {

        static Parameter text(String name, String description) {
            return new Parameter(name, Type.TEXT, true, description);
        }

        static Parameter textOrEmpty(String name, String description) {
            return new Parameter(name, Type.TEXT_OR_EMPTY, true, description);
        }

        static Parameter optionalNumber(String name, String description) {
            return new Parameter(name, Type.WHOLE_NUMBER, false, description);
        }
    }

    /// The JSON value an argument must be.
    enum Type {
        /// A string with something in it.
        TEXT,
        /// A string, possibly empty, such as the new content of a file.
        TEXT_OR_EMPTY,
        /// A JSON number without a fraction.
        WHOLE_NUMBER
    }
}
