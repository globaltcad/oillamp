# Searching and reading the web

## Intent

A genie should find things out the way ChatGPT or Claude in the browser do: search the web,
read a few pages, answer. It can do far more than they can on its own machine (a shell, Python,
a desktop, files that last), but on its own it cannot *search*. `curl` reaches any page through
the proxy, yet there is no way to find the page: scraping Google or DuckDuckGo by hand runs into
captchas, and what comes back is raw HTML full of menus and scripts.

So a sandbox gets two commands:

- `lamp search "query"`: which pages are about this? A list of titles, links and a line or two
  from each.
- `lamp fetch <url>`: what does this page say? Its text, cleaned up, as Markdown.

Both run entirely inside the sandbox. The oillamp engine gains no logic: it ships them in the
image and mentions them in `AGENTS.md`, as it does for `lamp screenshot`. Their requests leave
through the proxy like any other, under the lamp's network policy. No key, no hole, no service
outside the sandbox.

## Search: SearXNG in the image

[SearXNG](https://github.com/searxng/searxng) is an open-source meta search engine. It asks
engines such as DuckDuckGo, Brave and Mojeek, and merges their results. It is still scraping
underneath, but a community keeps its parsers working when the engines change their pages, which
our own scraping would not survive.

### What the agent sees

```
$ lamp search "sqlite write ahead log"
1. Write-Ahead Logging - SQLite
   https://sqlite.org/wal.html
   1. Overview The default method by which SQLite implements atomic commit and rollback is a …

2. SQLite WAL Mode: How Write-Ahead Logging Works and When to Use It
   https://sqldocs.org/sqlite-write-ahead-logging/
   Write-ahead logging (WAL) lets SQLite readers keep reading while a writer writes, which …
…
```

- The top 5 results by default; `-n COUNT` asks for another number. Each is numbered, with its
  title, its link, and SearXNG's snippet on one line, cut at 300 characters.
- Engines that failed are named on stderr with SearXNG's reason, such as
  `brave (too many requests), duckduckgo (CAPTCHA)`; the results are printed all the same.
- No results with engines failing is an error, exit status 1: engines refuse a burst of
  automated searches for a while, and "no results" would tell the agent that nothing exists.
  No results with no engine failing prints `No results for "…".` and exits with 0.
- SearXNG missing from the image, ending while it starts, or not answering is exit status 1, with
  a message that names its log.

`AGENTS.md` has a short section, `### Searching and reading the web`, under "Network".

### How it is put together

| Part | Where | What |
|---|---|---|
| SearXNG | `/opt/searxng/src`, `/opt/searxng/venv` | installed by `build/install-searxng.sh` in the toolchain part of the Containerfile, so not with `WITH_TOOLCHAIN=false`; 153 MB |
| its version | `ARG SEARXNG_COMMIT` in the Containerfile | one commit; moving it re-runs only that layer and the harnesses' |
| its settings | `/etc/searxng/settings.yml`, from `rootfs/` | laid over SearXNG's own defaults with `use_default_settings` |
| the command | `lamp search` in `/usr/local/bin/lamp` | starts SearXNG when needed, asks it, prints the results |
| its state | `/run/agent/searxng/` | `searxng.sock`, `searxng.log`, `start.lock`, SearXNG's caches; gone with the session |

If the install fails (GitHub or PyPI unreachable), the image builds without it, as it does
without SDKMAN or a harness, and `lamp search` says that SearXNG is not installed.

The settings, for one local user:

- A **Unix socket**, not a TCP port. oillamp offers every port the agent listens on to the user,
  to open in their browser (`[web] publish = "ask"` or `"any"`), and would offer SearXNG's at the
  first search. werkzeug, which serves SearXNG as `python -m searx.webapp`, reads `unix://…` as a
  socket.
- JSON output on; limiter and valkey (Redis) off, since the bot is the only user; no `secret_key`
  in the file, where every image would share it: `lamp search` passes a random one at each start.
- No plugins (see below).
- Every engine request through `http://127.0.0.1:3128`, oillamp's fixed proxy port, with a
  timeout of 5 seconds rather than 3 for the extra hop.
- Five engines: `duckduckgo` and `duckduckgo web` (two addresses, blocked separately), `brave`,
  `mojeek` and `yahoo`. Left out: Google (captchas); Startpage and Qwant (refused every request in
  trials); Bing (in trials, pages unrelated to the query); Wikipedia (Wikimedia asks automated
  clients for a contact address, `general.contact_url`, which a sandbox has none of). Mojeek is
  inactive in SearXNG's defaults because of a proof-of-work puzzle; it is solved in about a
  second.

SearXNG is **started by the first search**, not with the session, so a lamp that never searches
pays nothing for it. When nothing answers on the socket, `lamp search` takes `start.lock`, checks
again, removes a socket left by a SearXNG that ended, starts SearXNG detached, and waits up to 30
seconds for it. SearXNG then keeps running for later searches. Its log has two `botdetection`
errors per request ("No remote IP could be determined"): a socket client has no address. With
the limiter off they are harmless.

### What goes out

Only the engines' searches, each through the proxy under the lamp's network policy. In a real
session the network log listed `duckduckgo.com`, `html.duckduckgo.com`, `links.duckduckgo.com`,
`search.brave.com`, `www.mojeek.com` and `search.yahoo.com`, and nothing else.

That takes the empty `plugins` list. SearXNG's default plugins include a tracker-URL remover,
which at every start downloads ClearURLs' rules from `cdn.jsdelivr.net` (or `clearurls.xyz`),
before SearXNG has read its proxy settings; in the sandbox it reached the proxy only through the
proxy variables of the agent's environment. Marking it inactive is not enough: SearXNG
initializes inactive plugins too. The other default plugins add answers `lamp search` does not
print. Favicons, the image proxy and autocompletion are off, as in SearXNG's defaults or in the
settings.

**A network rule that refuses an engine's site** answers with a 403 that names the rule, but
SearXNG passes on only "HTTP connection error". So `lamp search` adds, for each engine that
failed that way, that a network rule of the lamp may refuse its site, and that oillamp shows the
human each refusal with its rule. The rule's name does not reach the agent; it is in the
session's terminal and the network log.

### Its limit

Search in bursts, say fifty queries in a minute, and the engines block it for a while; some
block some addresses more readily than others. Every search leaves from the user's own address.
If this proves too flaky, a paid search API (Brave, Tavily, Exa) can come later, with oillamp
adding the key outside the sandbox as it does for Eden AI.

### How it is tested

- `The_Lamp_Command_Spec` runs the real `lamp search` against a stand-in SearXNG, a small Python
  HTTP server on the socket: results, failing engines, nothing found, a connection error, start
  and reuse, two first searches at once, a stale socket, a SearXNG that ends or never answers,
  and SearXNG missing.
- `The_Sandbox_Image_Spec` checks the Containerfile's step and pin and the settings, and runs the
  install script's failure path.
- `Setting_Up_A_Lamp_Spec` checks the section in `AGENTS.md`.
- Real searches, in a real session: see [STATUS.md](STATUS.md#web-search).

## Fetch: Firefox, without a window

`curl` gets HTML; many pages build their text with JavaScript, and even those that do not bury
it under navigation. Firefox solves both: it runs the page's scripts, and Reader View's
extraction, Readability.js, which Firefox carries, cuts an article down to its text.

### What the agent sees

```
$ lamp fetch https://sqlite.org/wal.html
# Write-Ahead Logging
https://sqlite.org/wal.html

Write-Ahead Logging

Table Of Contents

## 1. Overview

The default method by which SQLite implements [atomic commit and rollback](https://sqlite.org/atomiccommit.html) is …
lamp: cut at 20000 characters: this is characters 0 to 20000 of 34480. `lamp fetch --start 20000 https://sqlite.org/wal.html` prints the next part.
```

- The title as a heading, the URL Firefox ended on, then the article as Markdown: headings,
  paragraphs, lists, links, code blocks, tables as `|` rows. Images are left out.
- A page with no article (search results, an application) prints its visible text instead, and
  stderr says so.
- Anything that is not an HTML page is downloaded again with curl (at most 50 MB) and printed
  from there: a PDF (a file beginning `%PDF-`, whatever its content type) as the text
  `pdftotext` reads; `text/…`, `application/json`, `application/xml` and `…+json`/`…+xml` as
  they are, character for character, under the last part of the URL's path as title. Firefox's
  own viewers would change them (XML laid out as a tree, JSON in a viewer application). Any
  other type, such as a `.zip`, fails with its type, size and the `curl -LO` that downloads it.
- At most 20000 characters, or `--max N`. When the text was cut, stderr names the characters
  printed, of how many, and the command for the next part, `--start N`. Each call loads the page
  again, which keeps `lamp fetch` without state.
- Exit status 1, with one line on stderr, for: a URL that is not `http://` or `https://` (so
  `file:`, `about:` and `javascript:` cannot read this machine through Firefox); an HTTP status of
  400 or more; a page that does not load within 30 seconds (`LAMP_FETCH_LOAD_SECONDS`); a Firefox
  that does not start, with its last words; and a connection that fails. For a failed
  connection, `lamp fetch` asks the proxy directly what it says about that host, so the agent
  gets the proxy's own words: the rule that refused it, or `cannot resolve <host>`.

### How it works

`lamp fetch` runs `/usr/local/lib/oillamp/lamp-fetch`, a Python program (standard library only):

1. It makes a profile in `/run/agent/lamp-fetch/profile-…`: `user.js` from
   `/usr/local/lib/oillamp/lamp-fetch-prefs.js`, then the proxy from `HTTP_PROXY` and the
   Marionette port.
2. It starts `firefox-esr --headless --marionette --no-remote --profile …`. Firefox is ready in
   about half a second.
3. Over **Marionette**, Firefox's own remote protocol (length-prefixed JSON over TCP; a few lines
   of Python, where WebDriver BiDi would need a WebSocket client), it opens a session with a
   page-load timeout, loads the URL, and runs one script in the page: Firefox's own
   `Readability.js`, read out of `/usr/lib/firefox-esr/omni.ja`, so nothing is bundled and it
   always matches the Firefox, then about 80 lines that turn the article into Markdown, or
   return `document.body.innerText`, and the page's content type and HTTP status.
4. While the page loads, it watches the profile's `downloads` directory, which the profile
   names in `browser.download.dir`: a URL Firefox downloads rather than shows (an archive, an
   attachment) puts a file there, and no page ever finishes loading. It then stops waiting.
5. Anything that is not HTML, or that Firefox downloaded, is fetched again with curl, through
   the proxy, as above.
6. Firefox and the processes it started are stopped (their process group), and the profile is
   deleted, also on an error, Ctrl-C, SIGTERM or SIGHUP. `lamp` runs lamp-fetch with `exec`, so
   a signal sent to `lamp` reaches it.

SIGKILL cannot be caught, and leaves a Firefox running on Marionette's port. So each fetch,
holding the lock, first stops any Firefox started with `--marionette` and a profile in
`/run/agent/lamp-fetch/profile-…`, which can only be left over from a killed fetch, and deletes
old profiles. If something else still listens on the port, such as the agent's own
`firefox --marionette`, it fails and names the port, and stops nothing it did not start.

**Firefox per fetch, not kept running.** A whole fetch takes 2 to 4.5 seconds in a real
session, of which Firefox's start is about half a second. Keeping it running would save little
and bring back state: a profile with history and cookies, a process to watch, a port that stays
open.

**One fetch at a time.** Marionette listens on a fixed port, 2828, on the loopback. A lock,
`/run/agent/lamp-fetch/lock`, makes a second fetch wait for the first; two at once take about the
sum of their times. The port exists only while a fetch runs. Firefox's remote protocols have no
Unix socket, unlike SearXNG, so the agent listens on a TCP port; oillamp never offers 2828 to the
person under `[web] publish = "ask"` or `"any"`, as it never offers its own ports 3128 and 3129
(`Forward.MARIONETTE_PORT`, which lamp-fetch's default must match; `The_Sandbox_Image_Spec` pins
lamp-fetch's side).

**What goes out.** The page's own requests only, through the proxy. The profile turns off what
Firefox fetches on its own (updates, telemetry, safe-browsing lists, remote settings, captive
portal checks, prefetching), and images, web fonts and media, which only cost time. Remote
settings ignore their preference unless `MOZ_REMOTE_SETTINGS_DEVTOOLS=1` is set, so lamp-fetch
sets it; without it, Firefox asked `firefox.settings.services.mozilla.com` at every start. In a
real session the network log showed only the fetched sites and what their pages load (on
react.dev: Google Analytics and a sandbox bundler).

**Firefox, then curl, for files.** Firefox learns a URL's type only by loading it, so a file is
requested twice. The alternative, asking curl first for every URL, would double every page
instead, and files are the rarer case. curl gives the exact bytes; Firefox's DOM for plain text is
the same text, but for XML it is a tree view, and one rule for every file is simpler than one per
viewer.

**PDFs** need `poppler-utils`, installed with Firefox in the toolchain layer: about 11 MB with
its libraries (libpoppler 4.9 MB, libnss3 4.5 MB, poppler-utils 0.7 MB, gpgme 1.3 MB).

### How it is tested

- `Reading_A_Web_Page_Spec` runs the real `lamp fetch` against a stand-in Firefox that speaks
  Marionette and returns what a scenario gives as the page script's result: an article, a page
  with no article, a cut page, refused URL schemes, a page-load timeout (Firefox and its profile
  gone afterwards), a Firefox that ends while starting, an HTTP error, a site refused by a rule, a
  name the proxy cannot resolve, a PDF, plain text, JSON and XML, a download, a Firefox left over
  by a killed fetch, another program on Marionette's port, SIGTERM and SIGHUP, and `lamp help`.
- `The_Sandbox_Image_Spec` checks the profile's preferences and that the image installs
  `poppler-utils` and `lamp-fetch`.
- `Setting_Up_A_Lamp_Spec` checks the paragraph in `AGENTS.md`.
- The page script itself, Readability on real pages and the Markdown it makes, only runs in a
  real Firefox: see [STATUS.md](STATUS.md#web-search).
