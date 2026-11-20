package oillamp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Title('Reading a web page')
@Narrative('''
`lamp fetch URL` prints what a web page says, as Markdown, with its title and URL on top, so that
the agent can read a page it found with `lamp search` without wading through raw HTML. It is a
Python program in the sandbox image, `/usr/local/lib/oillamp/lamp-fetch`, which `lamp fetch` runs.

For each fetch it starts the image's Firefox, `firefox-esr`, without a window (`--headless`) and
with a profile of its own in a temporary directory, so that it never touches the Firefox on the
desktop. It drives that Firefox over Marionette, Firefox's remote protocol: each message is its
length, a colon, and JSON. It has Firefox load the page, then runs a script in the page: Reader
View's extraction, `Readability.js`, which it reads out of Firefox's `omni.ja`, followed by a few
lines that turn the article into Markdown. When the page is no article, the script returns the
page's visible text instead. A PDF is fetched again with curl and read by `pdftotext`. Afterwards
it stops Firefox and deletes the profile.

These scenarios run the real program, through the real `lamp` script, against a stand-in for
Firefox on the `PATH`. The stand-in reads the Marionette port from the profile's `user.js`, as
Firefox does, answers Marionette's commands, and returns from the page script whatever the
scenario put in `page.json`, in the shape the real script returns: `title`, `url`, `type` (the
page's content type), `status` (its HTTP status), and `markdown` for an article or `text` for
anything else. It writes down how it was started, its profile's `user.js`, and each command. So
the scenarios check what `lamp fetch` does with what Firefox reports; whether the real Firefox
loads real pages and the script extracts them well, only the real image shows: see
`docs/WEB-SEARCH.md`.
''')
class Reading_A_Web_Page_Spec extends Specification {

    @TempDir Path temporary

    Path lamp
    /** The stand-in Firefox, curl and pdftotext, and what they write down. */
    Path stubs
    /** Where `lamp fetch` makes its temporary profiles, given in `LAMP_FETCH_DIR`. */
    Path runtime
    int marionettePort

    def setup() {
        lamp = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()
        stubs = Files.createDirectories(temporary.resolve('stubs'))
        runtime = temporary.resolve('run')
        marionettePort = new ServerSocket(0).withCloseable { it.localPort }
        standInFirefox()
        standInOmniJa()
    }

    def 'An article prints as Markdown, under its title and its URL'() {
        reportInfo """
            Let's consider the agent running `lamp fetch https://sqlite.org/wal.html`, an article
            about SQLite. The page script finds the article and returns it as Markdown, with the
            page's title. `lamp fetch` prints the title as a heading, `# Write-Ahead Logging`, the
            URL Firefox ended on below it, then a blank line and the Markdown, and exits with 0.
        """
        given: 'a page whose script returns an article titled "Write-Ahead Logging"'
            page(title: 'Write-Ahead Logging', url: 'https://sqlite.org/wal.html', type: 'text/html', status: 200,
                 markdown: '## 1. Overview\n\nThe default method is a [rollback journal](https://sqlite.org/lockingv3.html).')

        when: '`lamp fetch https://sqlite.org/wal.html` is run'
            var result = fetch('https://sqlite.org/wal.html')

        then: 'it prints the title, the URL and the article, nothing on stderr, and exits with 0'
            result.status == 0
            result.output == '# Write-Ahead Logging\nhttps://sqlite.org/wal.html\n\n' +
                    '## 1. Overview\n\nThe default method is a [rollback journal](https://sqlite.org/lockingv3.html).\n'
            result.errors == ''

        and: 'Firefox was asked to load the URL, then to run a script that begins with Readability.js from `omni.ja`'
            commands().find { it.name == 'WebDriver:Navigate' }.url == 'https://sqlite.org/wal.html'
            commands().find { it.name == 'WebDriver:ExecuteScript' }.script.startsWith('/* stand-in Readability.js */\n')
    }

    def 'Firefox runs headless with a profile of its own, through the proxy, and is gone with its profile afterwards'() {
        reportInfo """
            The Firefox that `lamp fetch` starts must not be the one on the desktop, nor share its
            profile: it is started with `--headless`, `--marionette` and `--profile`, naming a new
            directory in `LAMP_FETCH_DIR` (`/run/agent/lamp-fetch` in the sandbox). That profile's
            `user.js` holds the preferences shipped in the image, which turn off Firefox's own
            background requests, and then the proxy from `HTTP_PROXY` and the Marionette port.
            There is no DNS in the sandbox, so every request must go through the proxy. After the
            fetch, Firefox has ended and the profile is deleted; only the lock file, which keeps
            two fetches from sharing Marionette's port, is left.
        """
        given: 'an article, and `HTTP_PROXY=http://127.0.0.1:3128`'
            page(title: 'A page', url: 'https://example.org/', type: 'text/html', status: 200, markdown: 'Text.')

        when: '`lamp fetch https://example.org/` is run'
            var result = fetch([HTTP_PROXY: 'http://127.0.0.1:3128'], 'https://example.org/')

        then: 'Firefox was started with `--headless`, `--marionette` and `--profile`, and a profile in `LAMP_FETCH_DIR`'
            result.status == 0
            var start = startsOfFirefox().first()
            start.args.take(4) == ['--headless', '--marionette', '--no-remote', '--profile']
            Path.of(start.args[4]).parent == runtime

        and: 'its `user.js` held the shipped preferences, then the proxy 127.0.0.1:3128 for HTTP and HTTPS, and the Marionette port'
            start.userJs.startsWith(Files.readString(Path.of('src/main/resources/image/rootfs/usr/local/lib/oillamp/lamp-fetch-prefs.js')))
            start.userJs.contains('user_pref("network.proxy.http", "127.0.0.1");\nuser_pref("network.proxy.http_port", 3128);')
            start.userJs.contains('user_pref("network.proxy.ssl", "127.0.0.1");\nuser_pref("network.proxy.ssl_port", 3128);')
            start.userJs.contains("user_pref(\"marionette.port\", $marionettePort);")

        and: 'Firefox has ended, and `LAMP_FETCH_DIR` holds only the file `lock`'
            endsWithinSeconds(start.pid as long, 5)
            Files.list(runtime).map { it.fileName.toString() }.toList() == ['lock']
    }

    def 'A page that is no article prints as its visible text, and stderr says so'() {
        reportInfo """
            Not every page is an article: a list of search results, a shop, an application.
            Readability.js then finds nothing, and the page script returns the page's visible text,
            `document.body.innerText`, as `text`. `lamp fetch` prints that text under the title and
            URL, and says on stderr that no article was found, so that the agent knows why the text
            reads like a page's menus and buttons.
        """
        given: 'a page whose script found no article and returned its visible text'
            page(title: 'Results', url: 'https://example.org/search?q=wal', type: 'text/html', status: 200,
                 text: 'Home\nResult one\nResult two')

        when: '`lamp fetch https://example.org/search?q=wal` is run'
            var result = fetch('https://example.org/search?q=wal')

        then: 'it prints the title, the URL and the text, and exits with 0'
            result.status == 0
            result.output == '# Results\nhttps://example.org/search?q=wal\n\nHome\nResult one\nResult two\n'

        and: 'stderr says that no article was found, so this is all the page\'s visible text'
            result.errors.trim() == 'lamp: no article found on this page, so this is all its visible text'
    }

    def 'A long page is cut at 20000 characters, or `--max N`, and stderr says how to print the next part with `--start`'() {
        reportInfo """
            A page can run to hundreds of thousands of characters, which would flood the agent's
            context. `lamp fetch` prints at most 20000 characters of the page's text, or as many
            as `--max N` says, and when it cut the text, stderr says which characters it printed,
            of how many, and the command that prints the next part: `--start N` begins at
            character N. The title and URL are printed every time. Here the text is 25 characters
            long, and `--max 10` cuts it.
        """
        given: 'an article whose text is the 25 characters `aaaaaaaaaabbbbbbbbbbccccc`'
            page(title: 'Long', url: 'https://example.org/long', type: 'text/html', status: 200,
                 markdown: 'aaaaaaaaaabbbbbbbbbbccccc')

        when: '`lamp fetch --max 10 https://example.org/long` is run, then the same with `--start 20`'
            var first = fetch('--max', '10', 'https://example.org/long')
            var last = fetch('--max', '10', '--start', '20', 'https://example.org/long')

        then: 'the first prints the first 10 characters, and stderr names characters 0 to 10 of 25 and the command for the next part'
            first.status == 0
            first.output == '# Long\nhttps://example.org/long\n\naaaaaaaaaa\n'
            first.errors.trim() == 'lamp: cut at 10 characters: this is characters 0 to 10 of 25. ' +
                    '`lamp fetch --start 10 https://example.org/long` prints the next part.'

        and: 'the second prints the last 5 characters, and stderr says nothing, since nothing was left out after them'
            last.status == 0
            last.output == '# Long\nhttps://example.org/long\n\nccccc\n'
            last.errors == ''
    }

    def '`lamp fetch` reads only http:// and https:// URLs, and does not start Firefox for #url'() {
        reportInfo """
            Firefox can open more than web pages: `file:` reads this machine's files, `about:`
            Firefox's own pages, and `javascript:` runs code. `lamp fetch` is for the web, and a
            page or a mistake must not have it print local files. So it refuses any URL that is not
            `http://` or `https://` with a host, with exit status 1, before it starts Firefox.
        """
        when: '`lamp fetch` is run with the URL'
            var result = fetch(url)

        then: 'it exits with 1 and says that it reads only http:// and https:// pages'
            result.status == 1
            result.errors.trim() == "lamp: fetch reads only http:// and https:// pages, not $url"

        and: 'Firefox was not started'
            startsOfFirefox().isEmpty()

        where:
            url << ['file:///etc/passwd', 'about:config', 'javascript:alert(1)', 'ftp://example.org/', 'https:///no-host']
    }

    def 'When the page does not load within `LAMP_FETCH_LOAD_SECONDS`, `lamp fetch` fails, and stops Firefox and deletes its profile'() {
        reportInfo """
            Some pages never finish loading. `lamp fetch` gives Firefox a page-load timeout, 30
            seconds unless `LAMP_FETCH_LOAD_SECONDS` says otherwise, when it opens the Marionette
            session; Firefox then answers the command to load the page with the error `timeout`.
            `lamp fetch` says the page did not load in time and exits with 1, and leaves nothing
            behind: Firefox has ended, and its profile is deleted. Here the timeout is 7 seconds,
            and the stand-in Firefox answers with `timeout` at once.
        """
        given: 'a stand-in Firefox that answers the command to load the page with the error `timeout`'
            navigationFails('timeout', 'Navigation timed out after 7000 ms')

        when: '`lamp fetch https://example.org/slow` is run with `LAMP_FETCH_LOAD_SECONDS=7`'
            var result = fetch([LAMP_FETCH_LOAD_SECONDS: '7'], 'https://example.org/slow')

        then: 'Firefox was given a page-load timeout of 7000 milliseconds'
            commands().find { it.name == 'WebDriver:NewSession' }.pageLoad == 7000

        and: 'it exits with 1 and says that the page did not load within 7 seconds'
            result.status == 1
            result.errors.trim() == 'lamp: https://example.org/slow did not load within 7 seconds'

        and: 'Firefox has ended, and `LAMP_FETCH_DIR` holds only the file `lock`'
            endsWithinSeconds(startsOfFirefox().first().pid as long, 5)
            Files.list(runtime).map { it.fileName.toString() }.toList() == ['lock']
    }

    def 'A page that answers with an HTTP error, such as 404, fails with its status and title'() {
        reportInfo """
            A missing page still loads: the server sends a page that says "Not Found", with HTTP
            status 404. Printing that page as if it were the one asked for would mislead the
            agent. The page script returns the status Firefox received, and for 400 or more,
            `lamp fetch` prints nothing on stdout, says on stderr that the URL answered with that
            status, with the page's title, and exits with 1.
        """
        given: 'a page that answered with HTTP 404 and the title "Not Found"'
            page(title: 'Not Found', url: 'https://sqlite.org/missing.html', type: 'text/html', status: 404, text: 'Not Found')

        when: '`lamp fetch https://sqlite.org/missing.html` is run'
            var result = fetch('https://sqlite.org/missing.html')

        then: 'it exits with 1, prints nothing on stdout, and says on stderr that the URL answered HTTP 404'
            result.status == 1
            result.output == ''
            result.errors.trim() == 'lamp: https://sqlite.org/missing.html answered HTTP 404: Not Found'
    }

    def 'When a network rule of the lamp refuses the site, `lamp fetch` prints the proxy\'s message, which names the rule'() {
        reportInfo """
            The lamp's network policy may refuse a site. oillamp's proxy then answers the request
            to connect with HTTP 403 and a body that names the rule, which the agent should report
            as it is. Firefox shows only an error page, which Marionette reports as an error that
            names `about:neterror`. So `lamp fetch` asks the proxy itself once more, the way
            Firefox asked it, `CONNECT example.com:443`, and prints the proxy's answer. Here a
            stand-in proxy, in `HTTP_PROXY`, answers 403 with the message oillamp's proxy gives.
        """
        given: 'a stand-in Firefox whose page load fails with an error page, and a proxy that refuses `example.com:443` by a rule'
            navigationFails('unknown error', 'Reached error page: about:neterror?e=proxyConnectFailure&u=https%3A//example.com/')
            var proxy = refusingProxy('403 Forbidden', 'oillamp: connection to example.com:443 denied by rule "no example" in oillamp.toml')

        when: '`lamp fetch https://example.com/` is run with that proxy in `HTTP_PROXY`'
            var result = fetch([HTTP_PROXY: "http://127.0.0.1:${proxy.port}".toString()], 'https://example.com/')

        then: 'the proxy was asked `CONNECT example.com:443`'
            proxy.requests == ['CONNECT example.com:443 HTTP/1.1']

        and: 'it exits with 1 and prints the proxy\'s message, which names the rule'
            result.status == 1
            result.errors.trim() == 'lamp: the proxy refused https://example.com/: ' +
                    'oillamp: connection to example.com:443 denied by rule "no example" in oillamp.toml'

        cleanup:
            proxy?.socket?.close()
    }

    def 'When the proxy cannot resolve the host, `lamp fetch` prints the proxy\'s message'() {
        reportInfo """
            There is no DNS in the sandbox: the proxy resolves host names. For a name that does
            not resolve, oillamp's proxy answers the request to connect with HTTP 502 and the
            body `oillamp: cannot resolve <host>`. Firefox shows only an error page, which
            Marionette reports as an error that names `about:neterror`, and whose own reason,
            a failed connection, would send the agent looking in the wrong place. So, as for a
            refused site, `lamp fetch` asks the proxy itself, and prints its answer after the URL.
        """
        given: 'a stand-in Firefox whose page load fails with an error page, and a proxy that cannot resolve `no-such-host.invalid`'
            navigationFails('unknown error', 'Reached error page: about:neterror?e=connectionFailure&u=https%3A//no-such-host.invalid/')
            var proxy = refusingProxy('502 Bad Gateway', 'oillamp: cannot resolve no-such-host.invalid')

        when: '`lamp fetch https://no-such-host.invalid/` is run with that proxy in `HTTP_PROXY`'
            var result = fetch([HTTP_PROXY: "http://127.0.0.1:${proxy.port}".toString()], 'https://no-such-host.invalid/')

        then: 'it exits with 1, and says that the URL could not be loaded, in the proxy\'s words'
            result.status == 1
            result.errors.trim() == 'lamp: https://no-such-host.invalid/ could not be loaded: oillamp: cannot resolve no-such-host.invalid'

        cleanup:
            proxy?.socket?.close()
    }

    def 'A PDF is downloaded again with curl and printed as text by pdftotext'() {
        reportInfo """
            Firefox shows a PDF in its own viewer, from which no text can be had as from a page.
            For anything that is not an HTML page, `lamp fetch` downloads the URL again with
            curl, which goes through the proxy like any program here, into the file `download`
            in the temporary profile, at most 50 MB. A file that begins with `%PDF-` is a PDF,
            whatever content type the server gave it, and `lamp fetch` prints the text
            `pdftotext` reads from it, under the title Firefox gave the PDF. Here the stand-in
            curl downloads a file that begins with `%PDF-1.4`.
        """
        given: 'a URL whose content type is `application/pdf`, and a PDF that curl downloads'
            page(title: '1706.03762v7.pdf', url: 'https://arxiv.org/pdf/1706.03762', type: 'application/pdf', status: 200)
            download('200', 'application/pdf', '%PDF-1.4\n')

        when: '`lamp fetch https://arxiv.org/pdf/1706.03762` is run'
            var result = fetch('https://arxiv.org/pdf/1706.03762')

        then: 'it prints the title, the URL and the PDF\'s text, and exits with 0'
            result.status == 0
            result.output == '# 1706.03762v7.pdf\nhttps://arxiv.org/pdf/1706.03762\n\nAttention Is All You Need\n'

        and: 'curl downloaded the URL to a file `download` in the profile, at most 50 MB, and pdftotext read that file'
            var calls = Files.readAllLines(stubs.resolve('calls.txt'))
            var file = (calls[0] =~ /-o (\S+\/profile-\w+\/download) /)[0][1]
            calls[0] == "curl -sSL --max-time 30 --max-filesize 50000000 -o $file -w %{http_code} %{content_type} https://arxiv.org/pdf/1706.03762"
            calls[1] == "pdftotext -enc UTF-8 $file -"
    }

    def 'Plain text, JSON and XML print as they are: #contentType'() {
        reportInfo """
            An agent often reads files rather than pages: a `robots.txt`, a README from
            raw.githubusercontent.com, an answer from a JSON API, an XML feed. Firefox shows each
            in a viewer of its own, which changes it: XML is laid out as a tree, and JSON, unless
            the profile turns that viewer off, as an application. So, as for a PDF, `lamp fetch`
            downloads anything that is not an HTML page again with curl, and prints types
            `text/…`, `application/json`, `application/xml` and those ending in `+json` or `+xml`
            as the text the server sent, character for character, under the last part of the URL's
            path as title. Here the text holds blank lines and indentation that must be kept.
        """
        given: 'a URL whose content type is #contentType, and that text, with blank lines and indentation, as curl downloads it'
            page(title: '', url: 'https://example.org/api/item', type: contentType, status: 200)
            download('200', "$contentType; charset=utf-8", text)

        when: '`lamp fetch https://example.org/api/item` is run'
            var result = fetch('https://example.org/api/item')

        then: 'it prints `# item`, the URL, and the text unchanged, and exits with 0'
            result.status == 0
            result.output == "# item\nhttps://example.org/api/item\n\n$text"
            result.errors == ''

        where:
            contentType            | text
            'text/plain'           | 'User-agent: *\n\n\nDisallow: /private\n'
            'application/json'     | '{\n  "name": "searxng",\n\n  "stars": 1\n}\n'
            'application/xml'      | '<feed>\n  <entry/>\n</feed>\n'
            'application/ld+json'  | '{"@type": "Thing"}\n'
    }

    def 'A URL Firefox downloads rather than shows is read with curl, and a type lamp fetch cannot read fails at once'() {
        reportInfo """
            Some URLs are not shown but downloaded: an archive, or a file sent with
            `Content-Disposition: attachment`. Firefox then loads no page, and Marionette would
            wait for one until its timeout. The profile sends downloads into its own directory
            `downloads`, named in `browser.download.dir`, never into the agent's `~/Downloads`,
            and without asking. `lamp fetch` watches that directory while the page loads: when a
            file appears there, it stops Firefox and downloads the URL with curl, as for any
            file. A `.zip` is not text, so `lamp fetch` fails with exit status 1, names the
            content type and size, and the curl command that downloads it. A PDF sent as an
            attachment would be printed, as in the scenario about PDFs.
        """
        given: 'a stand-in Firefox that downloads the URL into its download directory, and a zip file of 4 bytes that curl downloads'
            Files.createFile(stubs.resolve('navigate-downloads'))
            download('200', 'application/zip', 'PK\u0003\u0004')

        when: '`lamp fetch https://example.org/archive.zip` is run'
            var result = fetch('https://example.org/archive.zip')

        then: 'the profile\'s `user.js` sent downloads into the profile\'s directory `downloads`'
            var start = startsOfFirefox().first()
            start.userJs.contains("user_pref(\"browser.download.dir\", \"${start.args[4]}/downloads\");")

        and: 'it exits with 1, and says that the URL is application/zip of 4 bytes, and how to download it with curl'
            result.status == 1
            result.errors.trim() == 'lamp: https://example.org/archive.zip is application/zip, 4 bytes, which lamp fetch does not read. ' +
                    'curl downloads it: curl -LO https://example.org/archive.zip'

        and: 'Firefox has ended, and `LAMP_FETCH_DIR` holds only the file `lock`'
            endsWithinSeconds(start.pid as long, 5)
            Files.list(runtime).map { it.fileName.toString() }.toList() == ['lock']
    }

    def 'When Firefox ends while it starts, `lamp fetch` fails at once with its exit code and its last words'() {
        reportInfo """
            Let's consider a Firefox that cannot start, for example because its profile cannot be
            written. It prints why and ends. `lamp fetch` waits for Marionette to answer, but
            notices that Firefox has ended, and fails at once with exit status 1, rather than wait
            out its 30 seconds. The message gives Firefox's exit code and the last lines Firefox
            wrote, since its log is deleted with the profile. Here the stand-in Firefox prints
            "stand-in Firefox: cannot start" and ends with exit code 1.
        """
        when: '`lamp fetch https://example.org/` is run with a Firefox that ends at once with exit code 1'
            var result = fetch([STUB_FIREFOX_EXIT: '1'], 'https://example.org/')

        then: 'it exits with 1, and says that Firefox ended while starting, with exit code 1 and what Firefox printed'
            result.status == 1
            result.errors.trim() == 'lamp: Firefox ended while starting (exit code 1): stand-in Firefox: cannot start'

        and: '`LAMP_FETCH_DIR` holds only the file `lock`'
            Files.list(runtime).map { it.fileName.toString() }.toList() == ['lock']
    }

    def 'A Firefox left over by a killed fetch is stopped, its profile deleted, and the next fetch works'() {
        reportInfo """
            Let's consider a fetch that was killed with SIGKILL, by hand or by the out-of-memory
            killer, which no program can catch. Its Firefox lives on, in a process group of its
            own, still listening on Marionette's port, and its profile stays in `LAMP_FETCH_DIR`.
            The next fetch would talk to that Firefox. So each fetch, once it holds the lock,
            looks for a Firefox started with `--marionette` and a profile `profile-…` in
            `LAMP_FETCH_DIR`, which can only be left over from an earlier fetch, stops it, and
            deletes every `profile-…` directory there. Here the test starts such a leftover
            stand-in Firefox itself, with the profile `profile-old`.
        """
        given: 'a stand-in Firefox started with `--marionette --profile <LAMP_FETCH_DIR>/profile-old`, listening on Marionette\'s port'
            var leftover = startedLeftoverFirefox()
            page(title: 'A page', url: 'https://example.org/', type: 'text/html', status: 200, markdown: 'Text.')

        when: '`lamp fetch https://example.org/` is run'
            var result = fetch('https://example.org/')

        then: 'it prints the page and exits with 0'
            result.status == 0
            result.output == '# A page\nhttps://example.org/\n\nText.\n'

        and: 'the leftover Firefox has ended, and the page was asked of the Firefox this fetch started'
            endsWithinSeconds(leftover.pid(), 5)
            startsOfFirefox().size() == 2
            commands()*.name == ['WebDriver:NewSession', 'WebDriver:Navigate', 'WebDriver:ExecuteScript', 'Marionette:Quit']

        and: '`profile-old` is deleted: `LAMP_FETCH_DIR` holds only the file `lock`'
            Files.list(runtime).map { it.fileName.toString() }.toList() == ['lock']

        cleanup:
            leftover?.destroyForcibly()
    }

    def 'When a program that is no leftover listens on Marionette\'s port, `lamp fetch` fails and leaves it running'() {
        reportInfo """
            Marionette's port is fixed, 2828 in the sandbox, so another program may hold it: the
            agent's own `firefox --marionette`, for example. `lamp fetch` must not talk to that
            program as if it were its own Firefox, and must not stop a program it did not start.
            So when something still listens on the port after leftovers of earlier fetches are
            gone, `lamp fetch` fails with exit status 1 before it starts Firefox, names the port,
            and says how to see which program holds it. Here a Python HTTP server listens on the
            port given in `LAMP_MARIONETTE_PORT`.
        """
        given: 'a Python HTTP server listening on 127.0.0.1 at Marionette\'s port'
            var other = new ProcessBuilder('python3', '-m', 'http.server', '--bind', '127.0.0.1', marionettePort.toString())
                    .redirectErrorStream(true).start()
            waitUntilListening(marionettePort)

        when: '`lamp fetch https://example.org/` is run'
            var result = fetch('https://example.org/')

        then: 'it exits with 1, and says that another program listens on the port, and how to see which'
            result.status == 1
            result.errors.trim() == "lamp: another program listens on 127.0.0.1:$marionettePort, the port of Marionette, which " +
                    "lamp fetch's Firefox needs. lamp fetch does not stop a program it did not start; " +
                    "`ss -ltnp 'sport = :$marionettePort'` shows which one it is."

        and: 'Firefox was not started, and the HTTP server still runs'
            startsOfFirefox().isEmpty()
            other.alive

        cleanup:
            other?.destroyForcibly()
    }

    def 'Sent SIGTERM or SIGHUP while the page loads, `lamp fetch` still stops Firefox and deletes its profile: #signal'() {
        reportInfo """
            A fetch can be ended from outside: the agent's harness stops a command that takes too
            long with SIGTERM, and a closed terminal sends SIGHUP. `lamp` runs lamp-fetch with
            `exec`, so the signal reaches lamp-fetch itself, which ends as if the program exited:
            with 128 plus the signal's number, after stopping Firefox and deleting the profile.
            Here the stand-in Firefox never finishes loading the page, and the signal is sent to
            `lamp` once Firefox has been asked to load it.
        """
        given: 'a stand-in Firefox that never finishes loading the page'
            Files.createFile(stubs.resolve('navigate-hangs'))

        when: '`lamp fetch https://example.org/slow` is started, and sent #signal once Firefox was asked to load the page'
            var started = startFetch([:], 'https://example.org/slow')
            waitUntil { commands().any { it.name == 'WebDriver:Navigate' } }
            new ProcessBuilder('kill', "-$signal", started.process.pid().toString()).start().waitFor()
            var result = finished(started)

        then: 'it exits with #status'
            result.status == status

        and: 'Firefox has ended, and `LAMP_FETCH_DIR` holds only the file `lock`'
            endsWithinSeconds(startsOfFirefox().first().pid as long, 5)
            Files.list(runtime).map { it.fileName.toString() }.toList() == ['lock']

        where:
            signal || status
            'TERM' || 143
            'HUP'  || 129
    }

    def '`lamp help` lists `lamp fetch` with its options `--max` and `--start`'() {
        when: '`lamp help` is run'
            var help = new ProcessBuilder('bash', lamp.toString(), 'help').start()
            var output = help.inputStream.text
            help.waitFor()

        then: 'it lists `lamp fetch [--max N] [--start N] URL`, and what it prints'
            output.contains('  lamp fetch [--max N] [--start N] URL              what a page says, as Markdown; a PDF as text')
    }

    // ─── the stand-ins ─────────────────────────────────────────────────────────────────────────

    /**
     * Puts `firefox-esr`, `curl` and `pdftotext` in `stubs`, which goes first on the `PATH`.
     * The stand-in Firefox records its start in `starts.txt` and each Marionette command in
     * `commands.txt`. With `STUB_FIREFOX_EXIT` it prints one line and ends with that exit code
     * instead of listening. It answers the page load from `navigate.json` when that exists (an error).
     * With `navigate-hangs` it never answers the page load; with `navigate-downloads` it also puts a
     * file into the download directory its `user.js` names, as Firefox does when it downloads a URL
     * rather than show it. It answers the page script with `page.json`.
     * The stand-in curl answers with `download` as the file and `download-type` as the status and
     * content type it prints (`-w`); the stand-in pdftotext prints "Attention Is All You Need".
     */
    private void standInFirefox() {
        script('firefox-esr', [
            '#!/usr/bin/env python3',
            'import json, os, re, socket, sys',
            "here = '$stubs'",
            'def record(name, value):',
            '    with open(os.path.join(here, name), "a") as file: file.write(json.dumps(value) + "\\n")',
            'arguments = sys.argv[1:]',
            'user_js = open(os.path.join(arguments[arguments.index("--profile") + 1], "user.js")).read()',
            'record("starts.txt", {"pid": os.getpid(), "args": arguments, "userJs": user_js})',
            'if os.environ.get("STUB_FIREFOX_EXIT"):',
            '    print("stand-in Firefox: cannot start", flush=True)',
            '    sys.exit(int(os.environ["STUB_FIREFOX_EXIT"]))',
            'port = int(re.search(r"marionette\\.port\\", (\\d+)", user_js).group(1))',
            'server = socket.socket()',
            'server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)',
            'server.bind(("127.0.0.1", port))',
            'server.listen(1)',
            'connection, _ = server.accept()',
            'def send(value):',
            '    data = json.dumps(value).encode()',
            '    connection.sendall(str(len(data)).encode() + b":" + data)',
            'send({"applicationType": "gecko", "marionetteProtocol": 3})',
            'buffer = b""',
            'while True:',
            '    while b":" not in buffer: buffer += connection.recv(65536)',
            '    length, _, buffer = buffer.partition(b":")',
            '    while len(buffer) < int(length): buffer += connection.recv(65536)',
            '    (_, id, name, parameters), buffer = json.loads(buffer[:int(length)]), buffer[int(length):]',
            '    if name == "WebDriver:NewSession":',
            '        record("commands.txt", {"name": name, "pageLoad": parameters["capabilities"]["alwaysMatch"]["timeouts"]["pageLoad"]})',
            '        send([1, id, None, {"sessionId": "stand-in", "capabilities": {}}])',
            '    elif name == "WebDriver:Navigate":',
            '        record("commands.txt", {"name": name, "url": parameters["url"]})',
            '        failure = os.path.join(here, "navigate.json")',
            '        if os.path.exists(os.path.join(here, "navigate-downloads")):',
            '            directory = re.search(r\'download\\.dir", "([^"]+)"\', user_js).group(1)',
            '            open(os.path.join(directory, "file.zip.part"), "w").close()',
            '        if os.path.exists(os.path.join(here, "navigate-downloads")) or os.path.exists(os.path.join(here, "navigate-hangs")):',
            '            while True: connection.recv(65536) or sys.exit(0)',
            '        send([1, id, json.load(open(failure)), None] if os.path.exists(failure) else [1, id, None, {"value": None}])',
            '    elif name == "WebDriver:ExecuteScript":',
            '        record("commands.txt", {"name": name, "script": parameters["script"]})',
            '        send([1, id, None, {"value": json.load(open(os.path.join(here, "page.json")))}])',
            '    elif name == "Marionette:Quit":',
            '        record("commands.txt", {"name": name})',
            '        send([1, id, None, {"cause": "shutdown"}])',
            '        sys.exit(0)',
        ])
        script('curl', ['#!/usr/bin/env bash',
                        "echo \"curl \$*\" >> '$stubs/calls.txt'",
                        'while [ "$1" != -o ]; do shift; done',
                        "cat '$stubs/download' > \"\$2\"",
                        "cat '$stubs/download-type'"])
        script('pdftotext', ['#!/usr/bin/env bash',
                             "echo \"pdftotext \$*\" >> '$stubs/calls.txt'",
                             'echo "Attention Is All You Need"'])
    }

    /** An `omni.ja` holding only Readability.js, at the path Firefox keeps it, as one comment. */
    private void standInOmniJa() {
        new ZipOutputStream(Files.newOutputStream(stubs.resolve('omni.ja'))).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry('moz-src/toolkit/components/reader/readability/Readability.js'))
            zip.write('/* stand-in Readability.js */'.bytes)
            zip.closeEntry()
        }
    }

    private void script(String name, List<String> lines) {
        var file = stubs.resolve(name)
        Files.writeString(file, lines.join('\n') + '\n')
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    /** What the stand-in curl downloads, and the status and content type it reports. */
    private void download(String status, String contentType, String content) {
        Files.writeString(stubs.resolve('download'), content)
        Files.writeString(stubs.resolve('download-type'), "$status $contentType")
    }

    /** What the page script returns, in the shape `lamp-fetch`'s script returns it. */
    private void page(Map page) {
        Files.writeString(stubs.resolve('page.json'), JsonOutput.toJson(page))
    }

    /** Has the stand-in Firefox answer the command to load the page with this Marionette error. */
    private void navigationFails(String error, String message) {
        Files.writeString(stubs.resolve('navigate.json'), JsonOutput.toJson([error: error, message: message]))
    }

    /** A proxy on a free port that answers every request with this status and body, and keeps the request lines. */
    private static Map refusingProxy(String status, String body) {
        var socket = new ServerSocket(0)
        var requests = Collections.synchronizedList([])
        Thread.start {
            while (!socket.closed) {
                try {
                    socket.accept().withCloseable { connection ->
                        var reader = new BufferedReader(new InputStreamReader(connection.inputStream))
                        requests << reader.readLine()
                        while (reader.readLine()) { }
                        var bytes = (body + '\n').getBytes('UTF-8')
                        connection.outputStream.write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.length}\r\nConnection: close\r\n\r\n").getBytes('US-ASCII'))
                        connection.outputStream.write(bytes)
                    }
                } catch (IOException ignored) { }
            }
        }
        [socket: socket, port: socket.localPort, requests: requests]
    }

    private List<Map> startsOfFirefox() { recorded('starts.txt') }

    private List<Map> commands() { recorded('commands.txt') }

    private List<Map> recorded(String name) {
        var file = stubs.resolve(name)
        Files.exists(file) ? Files.readAllLines(file).collect { new JsonSlurper().parseText(it) as Map } : []
    }

    /** Whether the process ends, or has ended, within that many seconds. */
    private static boolean endsWithinSeconds(long pid, int seconds) {
        var process = ProcessHandle.of(pid)
        if (process.isEmpty()) return true
        try {
            process.get().onExit().get(seconds, TimeUnit.SECONDS)
            return true
        } catch (TimeoutException ignored) {
            return false
        }
    }

    /** Starts the stand-in Firefox as a fetch killed earlier would have left it, and waits until it listens. */
    private Process startedLeftoverFirefox() {
        var profile = Files.createDirectories(runtime.resolve('profile-old'))
        Files.writeString(profile.resolve('user.js'), "user_pref(\"marionette.port\", $marionettePort);\n")
        var leftover = new ProcessBuilder(stubs.resolve('firefox-esr').toString(),
                '--headless', '--marionette', '--no-remote', '--profile', profile.toString(), 'about:blank').start()
        waitUntilListening(marionettePort)
        leftover
    }

    private static void waitUntilListening(int port) {
        waitUntil { try { new Socket('127.0.0.1', port).close(); true } catch (IOException ignored) { false } }
    }

    private static void waitUntil(Closure<Boolean> condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assert System.nanoTime() < deadline, 'waited 10 seconds in vain'
            Thread.sleep(50)
        }
    }

    private Map fetch(String... arguments) { fetch([:], arguments) }

    /** Runs `lamp fetch` against the stand-ins, with stdout and stderr kept apart. */
    private Map fetch(Map<String, String> extraEnvironment, String... arguments) {
        finished(startFetch(extraEnvironment, arguments))
    }

    private Map startFetch(Map<String, String> extraEnvironment, String... arguments) {
        var errors = Files.createTempFile(temporary, 'stderr', '.txt')
        var process = new ProcessBuilder(['bash', lamp.toString(), 'fetch'] + (arguments as List<String>))
        process.redirectError(errors.toFile())
        process.environment().putAll(
                HOME: Files.createDirectories(temporary.resolve('home')).toString(),
                PATH: "$stubs:${System.getenv('PATH')}".toString(),
                LAMP_FETCH: Path.of('src/main/resources/image/rootfs/usr/local/lib/oillamp/lamp-fetch').toAbsolutePath().toString(),
                LAMP_FETCH_PREFS: Path.of('src/main/resources/image/rootfs/usr/local/lib/oillamp/lamp-fetch-prefs.js').toAbsolutePath().toString(),
                LAMP_FIREFOX_OMNI: stubs.resolve('omni.ja').toString(),
                LAMP_FETCH_DIR: runtime.toString(),
                LAMP_MARIONETTE_PORT: marionettePort.toString())
        process.environment().remove('HTTP_PROXY')
        process.environment().remove('http_proxy')
        process.environment().putAll(extraEnvironment)
        [process: process.start(), errors: errors]
    }

    private static Map finished(Map started) {
        Process process = started.process
        var output = process.inputStream.text
        process.waitFor()
        [status: process.exitValue(), output: output, errors: Files.readString(started.errors as Path)]
    }
}
