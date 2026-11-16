// Builds the project website into site/_site.
//
// Everything that can change with the app is read from the repository at build time:
//   - user guide pages from docs/guide/*.md, plus docs/PLAN.md and docs/POC-RESULTS.md
//   - package name, minimum Android version and CPU types from app/build.gradle.kts
//   - the latest releases (version, APK, changelog) from the GitHub API
// The landing page also refreshes the release details in the browser, so a new
// release shows up even before the site is rebuilt.
//
// Usage: node build.mjs            (set GITHUB_TOKEN to avoid API rate limits)

import { readFileSync, writeFileSync, mkdirSync, rmSync, readdirSync, copyFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";
import { dirname, join, resolve, basename, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { Marked, Renderer } from "marked";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const out = join(here, "_site");
const repo = process.env.GITHUB_REPOSITORY || "adam-stott-dev/eufy-tv-doorbell";
const [owner, repoName] = repo.split("/");
const repoUrl = `https://github.com/${repo}`;
const siteUrl = `https://${owner.toLowerCase()}.github.io/${repoName}/`;
const branch = "main";

// ---------- helpers ----------

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const read = (p) => readFileSync(join(root, p), "utf8").replace(/\r\n/g, "\n");
const write = (p, s) => { mkdirSync(dirname(join(out, p)), { recursive: true }); writeFileSync(join(out, p), s); };

// GitHub's heading anchor rules, so links like install.md#3-let-it-show work on both.
function slugify(text) {
  return text.replace(/<[^>]+>/g, "").replace(/&(#39|quot|amp|lt|gt);/g, "").toLowerCase().trim().replace(/[^\p{L}\p{N}\s_-]/gu, "").replace(/\s/g, "-");
}

function parseFrontMatter(src) {
  const m = src.match(/^---\n([\s\S]*?)\n---\n/);
  if (!m) return { meta: {}, body: src };
  const meta = {};
  for (const line of m[1].split("\n")) {
    const i = line.indexOf(":");
    if (i > 0) meta[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return { meta, body: src.slice(m[0].length) };
}

function formatDate(iso) {
  return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

function formatSize(bytes) {
  return bytes ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : "";
}

// ---------- app facts from the Android build ----------

const ANDROID_VERSIONS = { 21: "5.0", 22: "5.1", 23: "6.0", 24: "7.0", 25: "7.1", 26: "8.0", 27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16" };

function readAppFacts() {
  const gradle = read("app/build.gradle.kts");
  const pick = (re) => gradle.match(re)?.[1];
  const minSdk = Number(pick(/minSdk\s*=\s*(\d+)/));
  const abis = [...gradle.matchAll(/abiFilters\s*\+?=\s*(?:listOf\()?([^)\n]+)/g)]
    .flatMap((m) => [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]));
  return {
    packageName: pick(/applicationId\s*=\s*"([^"]+)"/) || "",
    minSdk,
    minAndroid: ANDROID_VERSIONS[minSdk] || `API ${minSdk}`,
    abis,
  };
}

// ---------- releases ----------

async function fetchReleases() {
  // For previewing offline: RELEASES_FILE=saved-api-response.json node build.mjs
  if (process.env.RELEASES_FILE) return simplifyReleases(JSON.parse(readFileSync(process.env.RELEASES_FILE, "utf8")));
  const headers = { Accept: "application/vnd.github.html+json", "User-Agent": "site-build" };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=10`, { headers });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return simplifyReleases(await res.json());
  } catch (e) {
    console.warn(`Couldn't read releases (${e.message}); the page will load them in the browser.`);
    return [];
  }
}

// Same shape as site/app.js builds in the browser.
function simplifyReleases(list) {
  return list
    .filter((r) => !r.draft && !r.prerelease)
    .map((r) => {
      const apk = (r.assets || []).find((a) => a.name.endsWith(".apk"));
      return {
        tag: r.tag_name,
        name: r.name || r.tag_name,
        url: r.html_url,
        date: r.published_at,
        notes: r.body_html || "",
        apk: apk ? { name: apk.name, url: apk.browser_download_url, size: apk.size } : null,
      };
    });
}

// ---------- markdown ----------

function makeMarkdown(linkMap, sourcePath) {
  const marked = new Marked({ gfm: true });
  const used = new Map();
  marked.use({
    renderer: {
      heading({ tokens, depth }) {
        const html = this.parser.parseInline(tokens);
        let id = slugify(html);
        const n = used.get(id) || 0;
        used.set(id, n + 1);
        if (n) id += `-${n}`;
        const anchor = depth > 1 ? `<a class="anchor" href="#${id}" aria-label="Link to this section">#</a>` : "";
        return `<h${depth} id="${id}">${html}${anchor}</h${depth}>\n`;
      },
      link({ href, title, tokens }) {
        const text = this.parser.parseInline(tokens);
        const target = rewriteLink(href, linkMap, sourcePath);
        const external = /^https?:/.test(target) && !target.startsWith(siteUrl);
        return `<a href="${esc(target)}"${title ? ` title="${esc(title)}"` : ""}${external ? ' rel="noopener"' : ""}>${text}</a>`;
      },
      table(token) {
        // Wrap tables so they scroll sideways on phones instead of widening the page.
        return `<div class="table-wrap">${Renderer.prototype.table.call(this, token)}</div>`;
      },
    },
  });
  return marked;
}

// Links between markdown files become links between pages; other repo files go to GitHub.
function rewriteLink(href, linkMap, sourcePath) {
  if (/^([a-z]+:|#)/i.test(href)) return href;
  const [path, hash] = href.split("#");
  const target = relative(root, resolve(root, dirname(sourcePath), path)).replace(/\\/g, "/");
  if (linkMap[target]) return linkMap[target] + (hash ? `#${hash}` : "");
  return `${repoUrl}/blob/${branch}/${target}${hash ? `#${hash}` : ""}`;
}

// ---------- pages ----------

function loadDocs() {
  const guideDir = join(root, "docs/guide");
  const guide = readdirSync(guideDir)
    .filter((f) => f.endsWith(".md") && f.toLowerCase() !== "readme.md")
    .map((f) => {
      const src = `docs/guide/${f}`;
      const { meta, body } = parseFrontMatter(read(src));
      return { src, slug: basename(f, ".md"), section: "Guide", order: Number(meta.order || 99), title: meta.title, summary: meta.summary, body };
    })
    .sort((a, b) => a.order - b.order);

  const background = [
    { src: "docs/PLAN.md", slug: "plan", title: "Project plan", summary: "Goals, design and how the app is put together." },
    { src: "docs/POC-RESULTS.md", slug: "poc-results", title: "Proof of concept", summary: "Measurements from the first test on a SHIELD TV." },
  ]
    .filter((d) => existsSync(join(root, d.src)))
    .map((d) => ({ ...d, section: "Background", body: parseFrontMatter(read(d.src)).body }));

  const docs = [...guide, ...background];
  for (const d of docs) d.title ||= d.body.match(/^#\s+(.+)$/m)?.[1] || d.slug;
  return docs;
}

function layout({ title, description, depth, active, body, extraHead = "" }) {
  const up = depth ? "../".repeat(depth) : "";
  const nav = [
    ["Download", `${up}index.html#download`, "download"],
    ["Docs", `${up}docs/index.html`, "docs"],
    ["Changelog", `${up}index.html#changelog`, "changelog"],
  ];
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:type" content="website">
<meta name="color-scheme" content="light dark">
<link rel="icon" href="${up}icon.svg" type="image/svg+xml">
<link rel="stylesheet" href="${up}style.css">
${extraHead}</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<header class="topbar">
  <div class="wrap topbar-inner">
    <a class="brand" href="${up}index.html"><img src="${up}icon.svg" alt="" width="28" height="28"> Eufy TV Doorbell</a>
    <nav aria-label="Main">
      ${nav.map(([label, href, key]) => `<a href="${href}"${key === active ? ' aria-current="page"' : ""}>${label}</a>`).join("\n      ")}
      <a href="${repoUrl}" class="gh" aria-label="Source code on GitHub"><svg viewBox="0 0 16 16" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg></a>
    </nav>
  </div>
</header>
<main id="main">
${body}
</main>
<footer class="footer">
  <div class="wrap">
    <p>Not affiliated with or endorsed by Eufy or Anker. Eufy is a trademark of Anker Innovations.</p>
    <p>Open source on <a href="${repoUrl}">GitHub</a>. Built from <a href="${repoUrl}/commit/${commit.full}"><code>${commit.short}</code></a> on ${esc(formatDate(new Date().toISOString()))}.</p>
  </div>
</footer>
</body>
</html>
`;
}

function docPage(doc, docs, marked) {
  const sections = [...new Set(docs.map((d) => d.section))];
  const side = sections
    .map((s) => `<p class="side-head">${esc(s)}</p><ul>${docs
      .filter((d) => d.section === s)
      .map((d) => `<li><a href="${d.slug}.html"${d === doc ? ' aria-current="page"' : ""}>${esc(d.title)}</a></li>`)
      .join("")}</ul>`)
    .join("");
  const i = docs.indexOf(doc);
  const prev = docs[i - 1], next = docs[i + 1];
  const pager = `<nav class="pager" aria-label="Pages">
      ${prev ? `<a href="${prev.slug}.html" class="prev"><span>Previous</span>${esc(prev.title)}</a>` : "<span></span>"}
      ${next ? `<a href="${next.slug}.html" class="next"><span>Next</span>${esc(next.title)}</a>` : "<span></span>"}
    </nav>`;
  return layout({
    title: `${doc.title} · Eufy TV Doorbell`,
    description: doc.summary || `${doc.title}: Eufy TV Doorbell documentation.`,
    depth: 1,
    active: "docs",
    body: `<div class="wrap docs-layout">
  <aside class="side"><nav aria-label="Documentation">${side}</nav></aside>
  <article class="prose">
    ${marked.parse(doc.body)}
    <p class="edit"><a href="${repoUrl}/blob/${branch}/${doc.src}">Edit this page on GitHub</a></p>
    ${pager}
  </article>
</div>`,
  });
}

function docsIndex(docs) {
  const sections = [...new Set(docs.map((d) => d.section))];
  return layout({
    title: "Documentation · Eufy TV Doorbell",
    description: "How to install, sign in to and set up Eufy TV Doorbell on Android TV.",
    depth: 1,
    active: "docs",
    body: `<div class="wrap narrow">
  <h1 class="page-title">Documentation</h1>
  ${sections.map((s) => `<h2 class="section-label">${esc(s)}</h2>
  <div class="cards">${docs.filter((d) => d.section === s).map((d) => `
    <a class="card" href="${d.slug}.html"><strong>${esc(d.title)}</strong><span>${esc(d.summary || "")}</span></a>`).join("")}
  </div>`).join("\n")}
</div>`,
  });
}

// ---------- landing page ----------

function releaseBlocks(releases) {
  const latest = releases.find((r) => r.apk) || releases[0];
  const download = latest?.apk
    ? { href: latest.apk.url, version: latest.name, meta: `${formatDate(latest.date)} · ${formatSize(latest.apk.size)}` }
    : { href: `${repoUrl}/releases/latest`, version: "Latest release", meta: "On GitHub Releases" };
  const changelog = releases.length
    ? releases.slice(0, 5).map(changelogItem).join("")
    : `<li class="muted">See <a href="${repoUrl}/releases">GitHub Releases</a>.</li>`;
  return { download, changelog };
}

function changelogItem(r) {
  return `<li class="release"><div class="release-head"><a href="${esc(r.url)}"><strong>${esc(r.name)}</strong></a><time datetime="${esc(r.date)}">${esc(formatDate(r.date))}</time></div><div class="release-notes">${r.notes}</div></li>`;
}

function landing(app, releases, docs) {
  const { download, changelog } = releaseBlocks(releases);
  const pkg = app.packageName;
  const req = [`Android TV or Google TV, Android ${app.minAndroid} or later`];
  if (app.abis.length) req.push(`${app.abis.includes("arm64-v8a") && app.abis.length === 1 ? "64-bit ARM" : app.abis.join(", ")} (such as the SHIELD TV)`);
  req.push("A Eufy doorbell or camera, signed in with an email and password");
  const guide = docs.filter((d) => d.section === "Guide");

  return layout({
    title: "Eufy TV Doorbell: live doorbell video on Android TV",
    description: "Free, open-source Android TV app that pops up your Eufy doorbell's live video when someone rings or motion is detected.",
    depth: 0,
    active: "",
    extraHead: `<script>window.SITE = ${JSON.stringify({ repo, releases: releases.slice(0, 5) }).replace(/</g, "\\u003c")};</script>
<script src="app.js" defer></script>
`,
    body: `
<section class="hero">
  <div class="wrap hero-inner">
    <div class="hero-copy">
      <p class="eyebrow">Free and open source · Android TV</p>
      <h1>See who's at the door without leaving the sofa.</h1>
      <p class="lede">When your Eufy doorbell rings or a camera sees motion, the live video pops up on your TV over whatever you're watching, then hides itself. No extra hardware: it all runs on the TV box.</p>
      <div class="cta">
        <a class="button primary" id="download-button" href="${esc(download.href)}">
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M12 3a1 1 0 0 1 1 1v9.59l3.3-3.3a1 1 0 1 1 1.4 1.42l-5 5a1 1 0 0 1-1.4 0l-5-5a1 1 0 1 1 1.4-1.42l3.3 3.3V4a1 1 0 0 1 1-1zM5 19a1 1 0 1 0 0 2h14a1 1 0 1 0 0-2H5z"/></svg>
          <span>Download for Android TV</span>
        </a>
        <a class="button" href="docs/install.html">Install guide</a>
      </div>
      <p class="release-line"><span id="release-version">${esc(download.version)}</span> · <span id="release-meta">${esc(download.meta)}</span></p>
    </div>
    <div class="hero-art" aria-hidden="true">
      <div class="tv">
        <div class="screen">
          <div class="scene"></div>
          <div class="pip">
            <div class="pip-video"><div class="person"></div></div>
            <div class="pip-label"><span class="dot"></span>Front door · now</div>
          </div>
        </div>
      </div>
      <div class="tv-stand"></div>
    </div>
  </div>
</section>

<section class="wrap features" aria-label="Features">
  <div class="feature"><h3>Over everything</h3><p>Shows on top of Netflix, YouTube, live TV, the home screen and the screensaver. It can wake the TV, too.</p></div>
  <div class="feature"><h3>Fast and local</h3><p>Video goes straight from your HomeBase to the TV over your home network. On a SHIELD TV it starts in under a second.</p></div>
  <div class="feature"><h3>Ring, motion or person</h3><p>Choose which cameras and which events pop up, with quiet hours and a wait between motion pop-ups.</p></div>
  <div class="feature"><h3>Your way</h3><p>Picture-in-picture in any corner and size, or full screen. Set how long it stays up and when it plays sound.</p></div>
  <div class="feature"><h3>Tells you when it can't</h3><p>If Eufy signs you out or the connection drops, a message appears on screen until it's fixed, so you never miss the door silently.</p></div>
  <div class="feature"><h3>Light</h3><p>A small background service: a few percent of one CPU core and about 95 MB of memory on a SHIELD TV.</p></div>
</section>

<section class="band" id="download">
  <div class="wrap two-col">
    <div>
      <h2>Get started</h2>
      <ol class="steps">
        <li><strong>Install the APK</strong> with <code>adb install</code> from a computer, or with the Downloader app on the TV.</li>
        <li><strong>Let it show over other apps.</strong> Android TV has no screen for this, so run once from a computer:
          <pre><code>adb shell appops set ${esc(pkg)} SYSTEM_ALERT_WINDOW allow</code></pre></li>
        <li><strong>Sign in to Eufy.</strong> Open <em>Eufy TV Doorbell</em> on the TV and scan the QR code with your phone, or type with the remote.</li>
        <li><strong>Test it.</strong> Select <em>Show a test pop-up</em>, then ring your doorbell.</li>
      </ol>
      <p><a href="docs/install.html">Full install guide →</a></p>
    </div>
    <div class="aside-stack">
      <div class="panel">
        <h3>You'll need</h3>
        <ul class="ticks">${req.map((r) => `<li>${esc(r)}</li>`).join("")}</ul>
      </div>
      <div class="panel note">
        <h3>Google or Apple sign-in?</h3>
        <p>Eufy accounts that use Google or Apple to sign in have no password, so they can't be used here. Create a second Eufy account with an email and password, and share your doorbell and cameras with it from the Eufy app. A second account is a good idea anyway: signing in on the TV with your main account can sign your phone out.</p>
        <p><a href="docs/sign-in.html">About signing in →</a></p>
      </div>
    </div>
  </div>
</section>

<section class="wrap" id="docs">
  <h2>Documentation</h2>
  <div class="cards">${guide.map((d) => `
    <a class="card" href="docs/${d.slug}.html"><strong>${esc(d.title)}</strong><span>${esc(d.summary || "")}</span></a>`).join("")}
  </div>
</section>

<section class="wrap" id="changelog">
  <h2>What's new</h2>
  <ol class="releases" id="changelog-list">${changelog}</ol>
  <p><a href="${repoUrl}/releases">All releases on GitHub →</a></p>
</section>
`,
  });
}

function downloadPage(releases) {
  const latest = releases.find((r) => r.apk);
  const fallback = latest ? latest.apk.url : `${repoUrl}/releases/latest`;
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Downloading Eufy TV Doorbell</title>
<meta name="robots" content="noindex">
<link rel="icon" href="../icon.svg" type="image/svg+xml">
<link rel="stylesheet" href="../style.css">
<script>
// Always fetch the newest APK, even if the site hasn't been rebuilt since the release.
fetch("https://api.github.com/repos/${repo}/releases/latest")
  .then(function (r) { return r.ok ? r.json() : Promise.reject(); })
  .then(function (r) {
    var a = (r.assets || []).filter(function (x) { return /\\.apk$/.test(x.name); })[0];
    location.replace(a ? a.browser_download_url : ${JSON.stringify(fallback)});
  })
  .catch(function () { location.replace(${JSON.stringify(fallback)}); });
</script>
<noscript><meta http-equiv="refresh" content="0; url=${esc(fallback)}"></noscript>
</head>
<body>
<main class="wrap narrow center-page">
  <h1>Downloading the latest version…</h1>
  <p>If nothing happens, <a href="${esc(fallback)}">download it here</a>.</p>
</main>
</body>
</html>
`;
}

// ---------- build ----------

const commit = (() => {
  try {
    const full = process.env.GITHUB_SHA || execSync("git rev-parse HEAD", { cwd: root }).toString().trim();
    return { full, short: full.slice(0, 7) };
  } catch {
    return { full: branch, short: branch };
  }
})();

const app = readAppFacts();
const releases = await fetchReleases();
const docs = loadDocs();

const linkMap = {};
for (const d of docs) linkMap[d.src] = d.slug + ".html";
linkMap["README.md"] = "../index.html";

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

write("index.html", landing(app, releases, docs));
write("docs/index.html", docsIndex(docs));
for (const d of docs) write(`docs/${d.slug}.html`, docPage(d, docs, makeMarkdown(linkMap, d.src)));
write("download/index.html", downloadPage(releases));
write("releases.json", JSON.stringify({ built: new Date().toISOString(), app, releases }, null, 2));
write(".nojekyll", "");
for (const f of ["style.css", "app.js", "icon.svg"]) copyFileSync(join(here, "static", f), join(out, f));

console.log(`Built ${docs.length} docs pages, ${releases.length} releases, package ${app.packageName}, Android ${app.minAndroid}+ → ${relative(root, out)}`);
