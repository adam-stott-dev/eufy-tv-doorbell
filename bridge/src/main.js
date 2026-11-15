"use strict";

/*
 * The Eufy connection, running on the Node.js runtime embedded in the app.
 *
 * The app (Kotlin) listens on a loopback port and starts this script with
 * BRIDGE_PORT and BRIDGE_TOKEN in the environment. We connect, prove who we
 * are with the token, and from then on exchange frames:
 *
 *   [type: 1 byte][length: 4 bytes, big-endian][payload]
 *     type 1  JSON message (both directions)
 *     type 2  video data (Annex-B H.264/H.265) for the current livestream
 *     type 3  audio data (ADTS AAC) for the current livestream
 *
 * The Eufy password only ever arrives over this socket, never on disk here.
 */

// The embedded Node is built without ICU, so Intl is missing. The library and
// its dependencies only need a few English-only basics from it.
if (typeof globalThis.Intl === "undefined") {
    const tz = process.env.TZ || "UTC";
    function DateTimeFormat() {
        return {
            resolvedOptions: () => ({ timeZone: tz, locale: "en-US", calendar: "gregory", numberingSystem: "latn" }),
            format: (d) => new Date(d === undefined ? Date.now() : d).toISOString(),
            formatToParts: () => { throw new RangeError("Intl is not available"); },
        };
    }
    DateTimeFormat.supportedLocalesOf = () => ["en-US"];
    function ListFormat(_locale, opts = {}) {
        const word = opts.type === "disjunction" ? "or" : "and";
        return {
            format: (items) => {
                const list = Array.from(items, String);
                if (list.length <= 1) return list.join("");
                if (list.length === 2) return `${list[0]} ${word} ${list[1]}`;
                return `${list.slice(0, -1).join(", ")}, ${word} ${list[list.length - 1]}`;
            },
        };
    }
    function NumberFormat() {
        return { format: (n) => String(n), resolvedOptions: () => ({ locale: "en-US" }) };
    }
    function Collator() {
        return { compare: (a, b) => (a < b ? -1 : a > b ? 1 : 0) };
    }
    globalThis.Intl = { DateTimeFormat, ListFormat, NumberFormat, Collator };
}

const net = require("net");
const fs = require("fs");
const { EufySecurity, P2PConnectionType, VideoCodec, AudioCodec } = require("eufy-security-client");

const T_JSON = 1;
const T_VIDEO = 2;
const T_AUDIO = 3;

const port = Number(process.env.BRIDGE_PORT);
const token = process.env.BRIDGE_TOKEN || "";

let sock = null;
let eufy = null;
let config = null; // last login command
let state = "idle";
let retryTimer = null;
let retryDelayMs = 30_000;
let live = null; // { serial, video, audio }

// ------------------------------------------------------------------ socket

function send(obj) {
    writeFrame(T_JSON, Buffer.from(JSON.stringify(obj), "utf8"));
}

function writeFrame(type, payload) {
    if (!sock || sock.destroyed) return true;
    const head = Buffer.allocUnsafe(5);
    head.writeUInt8(type, 0);
    head.writeUInt32BE(payload.length, 1);
    sock.write(head);
    return sock.write(payload);
}

function log(level, msg) {
    send({ ev: "log", level, msg: String(msg) });
}

function setState(next, extra = {}) {
    state = next;
    send({ ev: "state", state: next, ...extra });
}

function connectToApp() {
    sock = net.connect({ host: "127.0.0.1", port }, () => {
        send({ ev: "hello", token, node: process.version, lib: libVersion() });
    });
    sock.setNoDelay(true);
    let buf = Buffer.alloc(0);
    sock.on("data", (chunk) => {
        buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
        while (buf.length >= 5) {
            const len = buf.readUInt32BE(1);
            if (buf.length < 5 + len) break;
            const type = buf.readUInt8(0);
            const payload = buf.subarray(5, 5 + len);
            buf = buf.subarray(5 + len);
            if (type === T_JSON) {
                let msg;
                try { msg = JSON.parse(payload.toString("utf8")); } catch (e) { continue; }
                handle(msg).catch((e) => log("warn", `${msg.cmd} failed: ${e && e.message}`));
            }
        }
    });
    // Without the app there is nothing to do: exit so the app can start us fresh.
    sock.on("error", (e) => console.error(`[eufy-bridge] socket error: ${e && e.message}`));
    sock.on("close", () => {
        console.error("[eufy-bridge] connection to the app closed, exiting");
        shutdown(0);
    });
}

function libVersion() {
    try { return require("eufy-security-client/package.json").version; } catch (_) { return "?"; }
}

// ------------------------------------------------------------------ commands

async function handle(msg) {
    switch (msg.cmd) {
        case "login":
            config = msg;
            retryDelayMs = 30_000;
            try {
                await login();
            } catch (e) {
                onConnectionError(e);
            }
            break;
        case "verify":
            if (!eufy) return;
            setState("connecting");
            await eufy.connect({ verifyCode: String(msg.code || "").trim(), force: false });
            break;
        case "captcha":
            if (!eufy) return;
            setState("connecting");
            await eufy.connect({ captcha: { captchaCode: String(msg.code || "").trim(), captchaId: msg.id }, force: false });
            break;
        case "devices":
            await sendDevices();
            break;
        case "live_start":
            await startLive(msg.serial);
            break;
        case "live_stop":
            await stopLive(msg.serial);
            break;
        case "reconnect":
            if (config && !(eufy && eufy.isConnected())) await reconnect();
            break;
        case "shutdown":
            shutdown(0);
            break;
        default:
            log("warn", `Unknown command ${msg.cmd}`);
    }
}

// ------------------------------------------------------------------ Eufy

async function login() {
    clearTimeout(retryTimer);
    setState("connecting");
    if (eufy) {
        const old = eufy;
        eufy = null;
        old.removeAllListeners();
        try { old.close(); } catch (_) { /* ignore */ }
    }
    fs.mkdirSync(config.persistentDir, { recursive: true });
    eufy = await EufySecurity.initialize({
        username: config.username,
        password: config.password,
        country: config.country || "GB",
        language: "en",
        trustedDeviceName: config.trustedDeviceName || "Eufy TV Doorbell",
        persistentDir: config.persistentDir,
        p2pConnectionSetup: config.localOnly === false ? P2PConnectionType.QUICKEST : P2PConnectionType.ONLY_LOCAL,
        pollingIntervalMinutes: 10,
        eventDurationSeconds: 10,
        acceptInvitations: true,
        // Node 20+ removed RSA PKCS#1 v1.5 private decryption; the library ships its own.
        enableEmbeddedPKCS1Support: true,
        logging: { level: config.debug ? 1 : 4 },
    });
    eufy.setCameraMaxLivestreamDuration(600);
    wire(eufy);
    await eufy.connect();
}

async function reconnect() {
    setState("connecting");
    try {
        if (!eufy) await login();
        else await eufy.connect();
    } catch (e) {
        onConnectionError(e);
    }
}

function scheduleRetry() {
    clearTimeout(retryTimer);
    const delay = retryDelayMs;
    retryDelayMs = Math.min(retryDelayMs * 2, 5 * 60_000);
    retryTimer = setTimeout(() => reconnect(), delay);
}

/** Wrong password or account problems need the user; anything else is retried. */
function isAuthProblem(err) {
    const text = `${err && err.name} ${err && err.message} ${err && err.code} ${JSON.stringify((err && err.context) || {})}`;
    return /password|credential|account|unauthori[sz]ed|26006|26050|26052|100028|login fail/i.test(text);
}

function onConnectionError(err) {
    const message = (err && err.message) || String(err);
    if (isAuthProblem(err)) {
        clearTimeout(retryTimer);
        setState("login_failed", { message });
    } else {
        setState("disconnected", { message, retryInSeconds: Math.round(retryDelayMs / 1000) });
        scheduleRetry();
    }
}

function wire(e) {
    e.on("tfa request", () => setState("need_code"));
    e.on("captcha request", (id, captcha) => {
        setState("need_captcha", { id, image: String(captcha || "").split(",").pop() });
    });
    e.on("connect", () => {
        retryDelayMs = 30_000;
        clearTimeout(retryTimer);
        setState("connected", { push: e.isPushConnected() });
        // Stations and devices load just after login.
        setTimeout(() => sendDevices().catch(() => {}), 3000);
    });
    e.on("connection error", onConnectionError);
    e.on("close", () => {
        if (state === "connected") {
            setState("disconnected", { message: "Connection to Eufy closed" });
            scheduleRetry();
        }
    });
    e.on("push connect", () => send({ ev: "push", connected: true }));
    e.on("push close", () => send({ ev: "push", connected: false }));
    e.on("station connect", (s) => send({ ev: "station", serial: s.getSerial(), name: s.getName(), connected: true }));
    e.on("station close", (s) => send({ ev: "station", serial: s.getSerial(), name: s.getName(), connected: false }));
    e.on("station connection error", (s, err) => log("warn", `P2P error ${s.getName()}: ${err && err.message}`));
    e.on("device added", () => sendDevices().catch(() => {}));
    e.on("device removed", () => sendDevices().catch(() => {}));

    e.on("device rings", (d, on) => on && event(d, "ring"));
    e.on("device motion detected", (d, on) => on && event(d, "motion"));
    e.on("device person detected", (d, on) => on && event(d, "person"));

    e.on("station livestream start", onLiveStart);
    e.on("station livestream stop", (s, d) => endLive(d.getSerial(), "stopped"));
}

function event(device, type) {
    send({ ev: "event", type, serial: device.getSerial(), name: device.getName(), at: Date.now() });
}

async function sendDevices() {
    if (!eufy) return;
    const devices = await eufy.getDevices();
    send({
        ev: "devices",
        list: devices.filter((d) => d.isCamera() || d.isDoorbell()).map((d) => ({
            serial: d.getSerial(),
            name: d.getName(),
            model: d.getModel(),
            station: d.getStationSerial(),
            doorbell: d.isDoorbell(),
        })),
    });
}

// ------------------------------------------------------------------ livestream

async function startLive(serial) {
    if (!eufy) throw new Error("Not signed in");
    if (live && live.serial === serial) {
        send({ ev: "live_start", serial, ...live.meta });
        return;
    }
    if (live) await stopLive(live.serial);
    live = { serial, requestedAt: Date.now(), meta: null };
    log("info", `Starting livestream for ${serial}`);
    try {
        await eufy.startStationLivestream(serial);
    } catch (e) {
        live = null;
        send({ ev: "live_error", serial, message: e.message });
    }
}

async function stopLive(serial) {
    if (!eufy || !live || live.serial !== serial) return;
    try {
        await eufy.stopStationLivestream(serial);
    } catch (_) {
        endLive(serial, "stopped");
    }
}

function onLiveStart(station, device, metadata, video, audio) {
    const serial = device.getSerial();
    if (!live || live.serial !== serial) live = { serial, requestedAt: Date.now() };
    const meta = {
        codec: metadata.videoCodec === VideoCodec.H265 ? "h265" : "h264",
        width: metadata.videoWidth,
        height: metadata.videoHeight,
        fps: metadata.videoFPS || 15,
        audio: metadata.audioCodec === AudioCodec.AAC ? "aac" : "none",
    };
    live.meta = meta;
    live.video = video;
    live.audio = audio;
    log("info", `Livestream ${serial} started after ${Date.now() - live.requestedAt} ms: ${meta.codec} ${meta.width}x${meta.height}`);
    send({ ev: "live_start", serial, ...meta });

    video.on("data", (chunk) => writeFrame(T_VIDEO, chunk));
    if (meta.audio === "aac") audio.on("data", (chunk) => writeFrame(T_AUDIO, chunk));
    else audio.resume();
}

function endLive(serial, why) {
    if (!live || live.serial !== serial) return;
    try { live.video && live.video.removeAllListeners("data"); } catch (_) { /* ignore */ }
    try { live.audio && live.audio.removeAllListeners("data"); } catch (_) { /* ignore */ }
    live = null;
    send({ ev: "live_stop", serial, why });
}

// ------------------------------------------------------------------ lifecycle

let stopping = false;
function shutdown(code) {
    if (stopping) return;
    stopping = true;
    try { eufy && eufy.close(); } catch (_) { /* ignore */ }
    setTimeout(() => process.exit(code), 500);
}

process.on("unhandledRejection", (e) => log("warn", `Unhandled: ${e && e.stack ? e.stack : e}`));
process.on("uncaughtException", (e) => log("error", `Uncaught: ${e && e.stack ? e.stack : e}`));

if (!port) {
    console.error("BRIDGE_PORT is not set");
    process.exit(2);
}
connectToApp();
