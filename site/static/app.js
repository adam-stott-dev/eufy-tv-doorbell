// Refreshes the download button and changelog from GitHub Releases when the page loads,
// so a new release shows here straight away. Without it (or if GitHub's API is
// unavailable) the page keeps the details it was built with.
(function () {
  var site = window.SITE || {};
  if (!site.repo || !window.fetch) return;

  var CACHE_KEY = "releases:" + site.repo;
  var CACHE_MS = 10 * 60 * 1000;

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  function date(iso) {
    return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
  }
  function size(bytes) {
    return bytes ? (bytes / 1024 / 1024).toFixed(1) + " MB" : "";
  }
  function simplify(list) {
    return list
      .filter(function (r) { return !r.draft && !r.prerelease; })
      .map(function (r) {
        var apk = (r.assets || []).filter(function (a) { return /\.apk$/.test(a.name); })[0];
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

  function render(releases) {
    if (!releases.length) return;
    var latest = releases.filter(function (r) { return r.apk; })[0];
    if (latest) {
      var button = document.getElementById("download-button");
      if (button) button.href = latest.apk.url;
      var v = document.getElementById("release-version");
      if (v) v.textContent = latest.name;
      var m = document.getElementById("release-meta");
      if (m) m.textContent = date(latest.date) + " · " + size(latest.apk.size);
    }
    var list = document.getElementById("changelog-list");
    if (list) {
      // Release notes come from GitHub's own sanitised HTML rendering.
      list.innerHTML = releases.slice(0, 5).map(function (r) {
        return '<li class="release"><div class="release-head"><a href="' + esc(r.url) + '"><strong>' + esc(r.name) +
          '</strong></a><time datetime="' + esc(r.date) + '">' + esc(date(r.date)) + '</time></div><div class="release-notes">' +
          r.notes + "</div></li>";
      }).join("");
    }
  }

  function readCache() {
    try {
      var c = JSON.parse(sessionStorage.getItem(CACHE_KEY) || "null");
      if (c && Date.now() - c.at < CACHE_MS) return c.releases;
    } catch (e) {}
    return null;
  }
  function writeCache(releases) {
    try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), releases: releases })); } catch (e) {}
  }

  document.addEventListener("DOMContentLoaded", function () {
    var cached = readCache();
    if (cached) return render(cached);
    fetch("https://api.github.com/repos/" + site.repo + "/releases?per_page=10", {
      headers: { Accept: "application/vnd.github.html+json" },
    })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
      .then(function (list) {
        var releases = simplify(list);
        writeCache(releases);
        render(releases);
      })
      .catch(function () { /* keep the built-in details */ });
  });
})();
