#!/usr/bin/env bash
# Installs SearXNG, the meta search engine behind `lamp search`, into /opt/searxng.
#
# SearXNG asks engines such as DuckDuckGo, Brave and Mojeek and merges what they find. It is still
# scraping underneath, but its community keeps the parsers working when the engines change their
# pages. It is installed the way its own container image installs it: the source at one commit,
# and its libraries in a virtualenv beside it, with the image's Python. The searx package is not
# pip-installed; `lamp search` puts the source directory on PYTHONPATH.
#
#   /opt/searxng/src    the checkout, without .git, with its version frozen into the source
#   /opt/searxng/venv   its libraries
#
# Its settings are not here but in /etc/searxng/settings.yml, from rootfs/, and `lamp search`
# starts it on the first search. See docs/WEB-SEARCH.md.
#
# Like SDKMAN and the harnesses, this may not fail the build. A sandbox without search still has
# its desktop, shell, toolchain and recording; losing those because GitHub or PyPI was briefly
# unreachable would be a bad trade made automatically. A failed install leaves no /opt/searxng
# behind, and `lamp search` then says that search is not in this image.
set -Eeuo pipefail

# Both can be changed for The_Sandbox_Image_Spec, which runs this script's failure.
SEARXNG_DIR="${SEARXNG_DIR:-/opt/searxng}"
SEARXNG_REPOSITORY="${SEARXNG_REPOSITORY:-https://github.com/searxng/searxng.git}"
commit="${1:?the commit of SearXNG to install}"

main() {
    git init -q "$SEARXNG_DIR/src"
    # One commit, not the history: a full clone is several times the size of the source.
    git -C "$SEARXNG_DIR/src" fetch -q --depth 1 "$SEARXNG_REPOSITORY" "$commit"
    git -C "$SEARXNG_DIR/src" -c advice.detachedHead=false checkout -q FETCH_HEAD

    python3 -m venv "$SEARXNG_DIR/venv"
    "$SEARXNG_DIR/venv/bin/pip" install -q --no-cache-dir -r "$SEARXNG_DIR/src/requirements.txt"

    # SearXNG reads its version from git when it starts, unless it was frozen into the source.
    # Freezing it lets .git go, and with it git commands run at every start, by a user who does not
    # own the checkout, which git refuses ("dubious ownership"). The repository's address and
    # branch are named for it, because a checkout of one commit has no upstream branch to read
    # them from; SearXNG shows them as where its source is.
    (cd "$SEARXNG_DIR/src" \
        && GITHUB_REPOSITORY=searxng/searxng GITHUB_REF_NAME=master \
           "$SEARXNG_DIR/venv/bin/python" -m searx.version freeze)
    [ -s "$SEARXNG_DIR/src/searx/version_frozen.py" ] \
        || { echo "searx.version freeze wrote no version_frozen.py" >&2; return 1; }
    rm -rf "$SEARXNG_DIR/src/.git"

    # The root filesystem is read-only at run time, so Python could not write these later, and
    # would compile every module again at each start.
    "$SEARXNG_DIR/venv/bin/python" -m compileall -q "$SEARXNG_DIR/src/searx"

    # The libraries SearXNG imports before it serves anything. A wheel missing for this machine
    # shows here, at build time, rather than at the agent's first search.
    "$SEARXNG_DIR/venv/bin/python" -c 'import flask, lxml, curl_cffi, msgspec, babel, yaml'

    # Nothing installs into this virtualenv after the build: the image is read-only at run time.
    # pip itself is 12 MB of it.
    "$SEARXNG_DIR/venv/bin/python" -m pip uninstall -q -y pip

    # Built by root, run by the agent.
    chmod -R a+rX "$SEARXNG_DIR"
    echo "installed SearXNG $(sed -n 's/^VERSION_STRING = "\(.*\)"$/\1/p' "$SEARXNG_DIR/src/searx/version_frozen.py")"
}

# Not `if ! main`: bash ignores `set -e` inside a function called as a condition, so main would
# carry on past a failed step. In a subshell of its own, the first failure ends it.
set +e
( set -e; main )
installed=$?
if [ "$installed" -ne 0 ]; then
    rm -rf "$SEARXNG_DIR"
    echo "WARNING: SearXNG did not install — the sandbox has no lamp search" >&2
fi
exit 0
