// The preferences of the Firefox that `lamp fetch` starts, copied into its temporary profile as
// user.js. lamp-fetch adds the proxy's address and Marionette's port after these lines.
//
// The aim: the page's requests, and nothing else. Firefox otherwise asks servers of its own, at
// start and now and then, for updates, telemetry, safe-browsing lists, remote settings, captive
// portal checks and the like. Each would be a request the agent never made, through the proxy
// and under the lamp's network policy, where it would also look like the agent's.
// Marionette's own recommended preferences already turn off much of this; these say it
// explicitly, so that it does not depend on them.

// Every request through the sandbox's proxy: there is no DNS in the sandbox. Manual proxy, the
// same for every protocol; the host names are resolved by the proxy.
user_pref("network.proxy.type", 1);
user_pref("network.proxy.share_proxy_settings", true);
user_pref("network.proxy.socks_remote_dns", true);
user_pref("network.proxy.no_proxies_on", "");
user_pref("network.proxy.allow_hijacking_localhost", true);

// Nothing fetched ahead of the page, or beside it.
user_pref("network.prefetch-next", false);
user_pref("network.dns.disablePrefetch", true);
user_pref("network.http.speculative-parallel-limit", 0);
user_pref("network.predictor.enabled", false);
user_pref("browser.places.speculativeConnect.enabled", false);
user_pref("network.captive-portal-service.enabled", false);
user_pref("network.connectivity-service.enabled", false);
user_pref("network.trr.mode", 5);

// No updates, of Firefox, its add-ons, search engines or media plugins.
user_pref("app.update.disabledForTesting", true);
user_pref("app.update.auto", false);
user_pref("extensions.update.enabled", false);
user_pref("extensions.getAddons.cache.enabled", false);
user_pref("extensions.systemAddon.update.enabled", false);
user_pref("browser.search.update", false);
user_pref("media.gmp-manager.updateEnabled", false);
user_pref("media.gmp-gmpopenh264.enabled", false);
user_pref("services.settings.server", "data:,");

// No telemetry, studies or reports.
user_pref("datareporting.policy.dataSubmissionEnabled", false);
user_pref("datareporting.healthreport.uploadEnabled", false);
user_pref("toolkit.telemetry.enabled", false);
user_pref("toolkit.telemetry.unified", false);
user_pref("toolkit.telemetry.server", "data:,");
user_pref("app.normandy.enabled", false);
user_pref("app.shield.optoutstudies.enabled", false);
user_pref("browser.crashReports.unsubmittedCheck.enabled", false);

// No safe-browsing or tracking-protection lists, no region or location lookups.
user_pref("browser.safebrowsing.malware.enabled", false);
user_pref("browser.safebrowsing.phishing.enabled", false);
user_pref("browser.safebrowsing.downloads.enabled", false);
user_pref("browser.safebrowsing.downloads.remote.enabled", false);
user_pref("browser.safebrowsing.blockedURIs.enabled", false);
user_pref("browser.safebrowsing.provider.mozilla.updateURL", "");
user_pref("browser.safebrowsing.provider.mozilla.gethashURL", "");
user_pref("browser.safebrowsing.provider.google4.updateURL", "");
user_pref("browser.safebrowsing.provider.google4.gethashURL", "");
user_pref("privacy.trackingprotection.enabled", false);
user_pref("browser.region.update.enabled", false);
user_pref("browser.region.network.url", "");
user_pref("geo.provider.network.url", "");

// No first-run pages, accounts, new-tab content or default-browser questions.
user_pref("browser.startup.homepage_override.mstone", "ignore");
user_pref("browser.aboutwelcome.enabled", false);
user_pref("browser.newtabpage.enabled", false);
user_pref("browser.newtabpage.activity-stream.feeds.system.topsites", false);
user_pref("browser.newtabpage.activity-stream.feeds.section.topstories", false);
user_pref("browser.shell.checkDefaultBrowser", false);
user_pref("identity.fxaccounts.enabled", false);

// Nothing kept: the profile is deleted after each fetch anyway.
user_pref("browser.cache.disk.enable", false);
user_pref("browser.sessionstore.resume_from_crash", false);

// The text is what is wanted: no images, web fonts or media, which only cost time and requests.
user_pref("permissions.default.image", 2);
user_pref("browser.display.use_document_fonts", 0);
user_pref("media.autoplay.default", 5);

// JSON shown as the text it is, not in Firefox's JSON viewer, a script application of its own.
user_pref("devtools.jsonview.enabled", false);

// A URL Firefox downloads rather than shows goes into the profile's directory `downloads`, never
// into the agent's ~/Downloads, and without a question. lamp-fetch adds browser.download.dir,
// notices the file there, and fetches the URL with curl instead.
user_pref("browser.download.folderList", 2);
user_pref("browser.download.useDownloadDir", true);
user_pref("browser.download.always_ask_before_handling_new_types", false);
user_pref("browser.download.manager.showWhenStarting", false);
