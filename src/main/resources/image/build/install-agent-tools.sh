#!/usr/bin/env bash
# The agent harnesses, installed into the image so the agent has them the moment it logs in.
#
# Two modes. In an ordinary build every step reports its failure and carries on, and the script
# exits 0: a sandbox without a harness still has a desktop, a shell and a recording, and a build
# that failed because npm was briefly unreachable would take all of that away.
#
# `oillamp update` sets AGENT_TOOLS_STRICT=true. Then any failure, including a harness that
# reports a different version from the one asked for, makes the script exit 1 and fails the
# build, so the lamp keeps its previous image.
#
# Takes friendly names ("pi", "opencode") or raw npm specifiers. The friendly names are what a
# user writes in oillamp.toml; the mapping to a published package lives here because that is what
# changes when a project renames or moves.
set -Eeuo pipefail
failed=0

# Where pi looks for extensions when PI_CODING_AGENT_DIR points at it (pi's own convention).
PI_DIR=/usr/local/share/oillamp/pi

# Extensions are installed at build time so they are there from the first session. (`pi install`
# also works inside the sandbox, through the egress proxy.)
PI_EXTENSIONS_DEFAULT="git:github.com/edenai/pi-edenai"

# opencode's configuration, read through OPENCODE_CONFIG (set in /etc/profile.d/oillamp.sh). It
# is kept out of the agent's home for the same reason as pi's directory, and is read in place.
OPENCODE_CONFIG_FILE=/usr/local/share/oillamp/opencode/opencode.json

specifier_for() {
    case "$1" in
        # Verified against the registry: `pi` is the binary, the package is scoped.
        pi)       echo "@earendil-works/pi-coding-agent" ;;
        opencode) echo "opencode-ai" ;;
        *)        echo "$1" ;;
    esac
}

install_tools() {
    for tool in "$@"; do
        specifier=$(specifier_for "$tool")
        if npm install -g --no-fund --no-audit "$specifier"; then
            echo "installed $tool ($specifier)"
        else
            echo "WARNING: could not install $tool ($specifier) — the sandbox works without it" >&2
            failed=1
        fi
    done
}

# pi's extensions are installed into an image directory rather than into the agent's home,
# because the agent's home is a bind mount from the lamp and would hide anything put there at
# build time. The entrypoint seeds a copy into the home at session start, where pi finds it and
# the agent can add to it.
install_pi_extensions() {
    command -v pi >/dev/null 2>&1 || { echo "pi is not installed; no extensions to add"; return 0; }
    mkdir -p "$PI_DIR"
    for extension in "$@"; do
        if PI_CODING_AGENT_DIR="$PI_DIR" pi install "$extension" </dev/null; then
            echo "installed pi extension $extension"
        else
            echo "WARNING: could not install pi extension $extension — pi works without it" >&2
            failed=1
        fi
    done
    chmod -R a+rX "$PI_DIR" 2>/dev/null || true
}

# Both harnesses use Eden AI only through its EU endpoint. pi's extension is told so by the
# environment (/etc/profile.d/oillamp.sh); opencode needs a configuration file.
write_opencode_config() {
    command -v opencode >/dev/null 2>&1 || { echo "opencode is not installed; no configuration to write"; return 0; }
    if node "$(dirname "$0")/write-opencode-config.mjs" "$OPENCODE_CONFIG_FILE"; then
        chmod -R a+rX "$(dirname "$OPENCODE_CONFIG_FILE")" 2>/dev/null || true
    else
        echo "WARNING: could not configure opencode for Eden AI's EU endpoint — opencode works without it" >&2
        failed=1
    fi
}

main() {
    [ $# -gt 0 ] || { echo "no agent tools requested"; return 0; }
    install_tools "$@"
    # shellcheck disable=SC2086
    install_pi_extensions ${PI_EXTENSIONS:-$PI_EXTENSIONS_DEFAULT}
    write_opencode_config
    check_versions "$@"
    [ "$failed" = 0 ]
}

# Prints each harness's version, so the build log says what was installed. When an exact
# version was asked for, it must be the one installed.
check_versions() {
    for tool in "$@"; do
        case "$tool" in
            opencode|opencode-ai|opencode-ai@*) binary=opencode ;;
            pi|@earendil-works/pi-coding-agent|@earendil-works/pi-coding-agent@*) binary=pi ;;
            *) continue ;;
        esac
        if ! command -v "$binary" >/dev/null 2>&1; then
            echo "WARNING: $binary is not installed" >&2
            failed=1
            continue
        fi
        if ! output=$("$binary" --version 2>&1); then
            echo "WARNING: $binary --version failed: $output" >&2
            failed=1
            continue
        fi
        # What --version prints is the tool's choice, such as "1.2.0" or "pi 1.2.0", so only
        # the first version number in it is compared.
        version=$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+[0-9A-Za-z.+-]*' <<<"$output" | head -n 1 || true)
        echo "$binary version: ${version:-unknown ($output)}"
        requested=${tool##*@}
        if [[ "$tool" == *@* && "$requested" =~ ^[0-9]+\.[0-9]+\.[0-9]+ && "$version" != "$requested" ]]; then
            echo "ERROR: $binary is version ${version:-unknown}, but $requested was asked for" >&2
            failed=1
        fi
    done
}

# Ordinary builds are tolerant; explicit updates fail rather than selecting incomplete tools.
if ! main "$@"; then
    if [ "${AGENT_TOOLS_STRICT:-false}" = true ]; then
        echo "ERROR: agent tools did not fully install; the lamp keeps the image it had" >&2
        exit 1
    fi
    echo "WARNING: agent tooling did not fully install — the sandbox is unaffected" >&2
fi
exit 0
