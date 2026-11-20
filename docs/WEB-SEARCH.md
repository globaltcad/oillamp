# Searching and reading the web

## Intent

A genie should find things out the way ChatGPT or Claude in the browser do: search the web,
read a few pages, answer. It can do far more than they can on its own machine (a shell, Python,
a desktop, files that last), but on its own it cannot *search*. `curl` reaches any page through
the proxy, yet there is no way to find the page: scraping Google or DuckDuckGo by hand runs into
captchas, and what comes back is raw HTML full of menus and scripts.

So a sandbox gets two commands:

- `lamp search "query"`: which pages are about this? A list of titles, links and a line or two
  from each. **Built.**
- `lamp fetch <url>`: what does this page say? Its text, cleaned up, as Markdown. **Planned, not
  built yet.**

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

`AGENTS.md` has a short section, `### Searching the web`, under "Network".

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

## Fetch: Firefox, without a window (planned)

`curl` gets HTML; many pages build their text with JavaScript, and even those that do not bury
it under navigation. Firefox already solves both: it runs the page's scripts, and its Reader View
(Readability.js, part of Firefox) cuts an article down to its text.

- `lamp fetch <url>` starts a headless Firefox with a profile of its own, so it never touches the
  Firefox on the desktop, loads the page, lets Reader View extract it, and prints it as Markdown,
  with the page's title and URL on top.
- PDFs are turned into text too.
- The output is capped, saying so when it was cut, so a huge page cannot flood a model's context.

Open: how exactly to drive Firefox. Firefox ESR in trixie speaks WebDriver BiDi, so a small client
can load the page; whether Reader View's code can be reached from there, or must be bundled, a
prototype will tell. If Firefox proves awkward, a Python extractor such as trafilatura is the
fallback, at the cost of pages that need JavaScript.
