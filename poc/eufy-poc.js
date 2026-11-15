#!/usr/bin/env node
"use strict";

/*
 * Proof of concept for the Eufy TV doorbell app.
 *
 * Runs on the SHIELD inside Termux and checks the three things the real app
 * depends on:
 *   1. Logging in to the Eufy cloud with a secondary (shared) account.
 *   2. Receiving ring / motion / person events through push notifications.
 *   3. Opening a P2P livestream through the HomeBase and saving a few seconds.
 *
 * It also logs timings (event delay, time to first video frame) and the
 * process's own CPU and memory use, so we can judge the load on the Shield.
 *
 * Credentials are asked for once and stored in ~/.eufy-poc/config.json inside
 * Termux's private storage (chmod 600). They are never written anywhere else.
 *
 * Usage:  node eufy-poc.js [--seconds 15] [--auto] [--allow-remote] [--debug]
 *   --seconds N      how long to record each livestream (default 15)
 *   --auto           start a livestream automatically on ring or motion
 *   --allow-remote   allow the P2P stream to go via Eufy's relay if a local
 *                    connection fails (default: local network only)
 *   --debug          verbose library logging
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const readline = require("readline");
const { spawnSync } = require("child_process");
const { EufySecurity, P2PConnectionType, VideoCodec, AudioCodec } = require("eufy-security-client");

// ---------------------------------------------------------------- options

const argv = process.argv.slice(2);
const opt = {
    seconds: Number(argValue("--seconds", 15)),
    auto: argv.includes("--auto"),
    allowRemote: argv.includes("--allow-remote"),
    debug: argv.includes("--debug"),
};

function argValue(name, fallback) {
    const i = argv.indexOf(name);
    return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
}

const DATA_DIR = path.join(os.homedir(), ".eufy-poc");
const CONFIG_FILE = path.join(DATA_DIR, "config.json");
const OUT_DIR = process.env.EUFY_POC_OUT ||
    (fs.existsSync("/sdcard/Download") ? "/sdcard/Download/eufy-poc/out" : path.join(os.homedir(), "eufy-poc-out"));

fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
fs.mkdirSync(OUT_DIR, { recursive: true });

// ---------------------------------------------------------------- logging

const T0 = Date.now();
const LOG_FILE = path.join(OUT_DIR, `poc-${stamp()}.log`);
const logStream = fs.createWriteStream(LOG_FILE, { flags: "a" });

function stamp() {
    return new Date().toISOString().replace(/[:.]/g, "-");
}

function log(...parts) {
    const line = `${new Date().toISOString()} +${((Date.now() - T0) / 1000).toFixed(1)}s  ${parts.join(" ")}`;
    console.log(line);
    logStream.write(line + "\n");
}

// ---------------------------------------------------------------- prompts

const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
let muted = false;
rl._writeToOutput = (s) => { if (!muted) rl.output.write(s); };

function ask(question, { hidden = false } = {}) {
    return new Promise((resolve) => {
        rl.question(question, (answer) => {
            if (hidden) { muted = false; rl.output.write("\n"); }
            resolve(answer.trim());
        });
        if (hidden) muted = true;
    });
}

async function loadOrCreateConfig() {
    if (fs.existsSync(CONFIG_FILE)) return JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
    console.log("\nFirst run: enter the SECONDARY Eufy account (the one the doorbell is shared with).");
    console.log(`It is saved only in ${CONFIG_FILE} on this device.\n`);
    const cfg = {
        username: await ask("Eufy email: "),
        password: await ask("Eufy password (hidden): ", { hidden: true }),
        country: ((await ask("Country code [GB]: ")) || "GB").toUpperCase(),
    };
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(cfg, null, 2), { mode: 0o600 });
    return cfg;
}

// ---------------------------------------------------------------- main

let eufy;
const pendingLive = new Map(); // serial -> { requestedAt, reason }
const activeLive = new Map(); // serial -> { files, timer, firstVideoAt }

async function main() {
    const cfg = await loadOrCreateConfig();
    log(`Node ${process.version}, output folder ${OUT_DIR}`);
    log(`Mode: ${opt.allowRemote ? "local first, remote relay allowed" : "local network only"}; record ${opt.seconds}s; auto=${opt.auto}`);

    eufy = await EufySecurity.initialize({
        username: cfg.username,
        password: cfg.password,
        country: cfg.country,
        language: "en",
        trustedDeviceName: "eufy-tv-doorbell-poc",
        persistentDir: DATA_DIR,
        p2pConnectionSetup: opt.allowRemote ? P2PConnectionType.QUICKEST : P2PConnectionType.ONLY_LOCAL,
        pollingIntervalMinutes: 10,
        eventDurationSeconds: 10,
        acceptInvitations: true,
        // Node 20+ removed RSA PKCS#1 v1.5 private decryption; the library ships its own.
        enableEmbeddedPKCS1Support: true,
        logging: { level: opt.debug ? 1 : 4 },
    });
    eufy.setCameraMaxLivestreamDuration(Math.max(opt.seconds + 5, 30));

    wireEvents();
    log("Logging in...");
    await eufy.connect();

    setInterval(reportLoad, 60_000).unref();
    commandLoop();
}

function wireEvents() {
    eufy.on("tfa request", async () => {
        log("Eufy sent a verification code to the account's email.");
        const code = await ask("Verification code: ");
        await eufy.connect({ verifyCode: code, force: false });
    });

    eufy.on("captcha request", async (id, captcha) => {
        const file = path.join(OUT_DIR, "captcha.png");
        fs.writeFileSync(file, Buffer.from(captcha.split(",").pop(), "base64"));
        log(`Eufy wants a captcha. Image saved to ${file}`);
        log("On Windows: adb pull /sdcard/Download/eufy-poc/out/captcha.png  then open it.");
        const code = await ask("Captcha text: ");
        await eufy.connect({ captcha: { captchaCode: code, captchaId: id }, force: false });
    });

    eufy.on("connect", async () => {
        log("CLOUD LOGIN OK");
        // Stations and devices load just after login.
        setTimeout(listDevices, 3000);
    });
    eufy.on("connection error", (err) => log(`Cloud connection error: ${err && err.message}`));
    eufy.on("close", () => log("Cloud connection closed"));
    eufy.on("push connect", () => log("PUSH CONNECTED (ring/motion events should now arrive)"));
    eufy.on("push close", () => log("Push connection closed"));

    eufy.on("station connect", (station) => log(`P2P connected to ${station.getName()} (${station.getSerial()})`));
    eufy.on("station close", (station) => log(`P2P closed to ${station.getName()}`));
    eufy.on("station connection error", (station, err) => log(`P2P error ${station.getName()}: ${err && err.message}`));

    eufy.on("device rings", (device, state) => onEvent(device, "RING", state));
    eufy.on("device motion detected", (device, state) => onEvent(device, "MOTION", state));
    eufy.on("device person detected", (device, state, person) => onEvent(device, `PERSON${person ? " " + person : ""}`, state));

    eufy.on("station livestream start", onLivestreamStart);
    eufy.on("station livestream stop", (station, device) => finishLive(device.getSerial(), "stream stopped"));
}

function onEvent(device, kind, state) {
    if (!state) return;
    log(`EVENT ${kind} from ${device.getName()} (${device.getSerial()})`);
    if (opt.auto && (kind === "RING" || kind === "MOTION" || kind.startsWith("PERSON"))) {
        startLive(device.getSerial(), kind).catch((e) => log(`Livestream failed: ${e.message}`));
    }
}

async function listDevices() {
    const stations = await eufy.getStations();
    log(`Stations (${stations.length}):`);
    for (const s of stations) {
        log(`  ${s.getName()}  model=${s.getModel()}  serial=${s.getSerial()}  lanIP=${s.getLANIPAddress() || "?"}`);
    }
    const devices = await eufy.getDevices();
    log(`Devices (${devices.length}):`);
    for (const d of devices) {
        log(`  ${d.getName()}  model=${d.getModel()}  type=${d.getDeviceType()}  serial=${d.getSerial()}  station=${d.getStationSerial()}  doorbell=${d.isDoorbell()}`);
    }
    log('Type "live" to test the doorbell stream, "help" for commands. Ring the doorbell to test events.');
}

async function firstDoorbellSerial() {
    const devices = await eufy.getDevices();
    const d = devices.find((x) => x.isDoorbell()) || devices.find((x) => x.isCamera());
    return d && d.getSerial();
}

async function startLive(serial, reason) {
    if (activeLive.has(serial) || pendingLive.has(serial)) return;
    pendingLive.set(serial, { requestedAt: Date.now(), reason });
    log(`Requesting livestream for ${serial} (${reason})`);
    await eufy.startStationLivestream(serial);
}

function onLivestreamStart(station, device, metadata, videostream, audiostream) {
    const serial = device.getSerial();
    const req = pendingLive.get(serial) || { requestedAt: Date.now(), reason: "unknown" };
    pendingLive.delete(serial);
    const vExt = metadata.videoCodec === VideoCodec.H265 ? "h265" : "h264";
    const base = path.join(OUT_DIR, `${serial}-${stamp()}`);
    log(`LIVESTREAM STARTED after ${(Date.now() - req.requestedAt) / 1000}s: ` +
        `${vExt.toUpperCase()} ${metadata.videoWidth}x${metadata.videoHeight} @${metadata.videoFPS}fps, ` +
        `audio=${AudioCodec[metadata.audioCodec] || metadata.audioCodec}`);

    const video = fs.createWriteStream(`${base}.${vExt}`);
    const audio = metadata.audioCodec === AudioCodec.AAC ? fs.createWriteStream(`${base}.aac`) : null;
    const entry = { base, vExt, fps: metadata.videoFPS || 15, video, audio, bytes: 0, timer: null };
    activeLive.set(serial, entry);

    videostream.once("data", () => log(`FIRST VIDEO DATA after ${(Date.now() - req.requestedAt) / 1000}s`));
    videostream.on("data", (chunk) => { entry.bytes += chunk.length; });
    videostream.pipe(video);
    if (audio) audiostream.pipe(audio); else audiostream.resume();

    entry.timer = setTimeout(() => {
        eufy.stopStationLivestream(serial).catch(() => finishLive(serial, "timer"));
    }, opt.seconds * 1000);
}

function finishLive(serial, why) {
    const entry = activeLive.get(serial);
    if (!entry) return;
    activeLive.delete(serial);
    clearTimeout(entry.timer);
    entry.video.end();
    if (entry.audio) entry.audio.end();
    log(`Livestream ended (${why}); ${(entry.bytes / 1024).toFixed(0)} KB of video saved to ${entry.base}.${entry.vExt}`);
    setTimeout(() => muxToMp4(entry), 500);
}

function muxToMp4(entry) {
    const which = spawnSync("sh", ["-c", "command -v ffmpeg"]);
    if (which.status !== 0) return;
    const args = ["-y", "-loglevel", "error", "-f", entry.vExt === "h265" ? "hevc" : "h264", "-r", String(entry.fps),
        "-i", `${entry.base}.${entry.vExt}`];
    if (entry.audio) args.push("-i", `${entry.base}.aac`);
    args.push("-c", "copy", `${entry.base}.mp4`);
    const r = spawnSync("ffmpeg", args);
    log(r.status === 0 ? `Saved playable ${entry.base}.mp4` : `ffmpeg could not mux: ${r.stderr}`);
}

let lastCpu = process.cpuUsage();
let lastCpuAt = Date.now();
function reportLoad() {
    const cpu = process.cpuUsage(lastCpu);
    const elapsedMs = Date.now() - lastCpuAt;
    lastCpu = process.cpuUsage();
    lastCpuAt = Date.now();
    const pct = ((cpu.user + cpu.system) / 1000 / elapsedMs) * 100;
    const mem = process.memoryUsage();
    log(`LOAD cpu=${pct.toFixed(1)}% of one core, rss=${(mem.rss / 1048576).toFixed(0)} MB, ` +
        `cloud=${eufy.isConnected()} push=${eufy.isPushConnected()}`);
}

function commandLoop() {
    rl.on("line", async (line) => {
        const [cmd, arg] = line.trim().split(/\s+/);
        try {
            switch (cmd) {
                case "": break;
                case "list": await listDevices(); break;
                case "live": {
                    const serial = arg || (await firstDoorbellSerial());
                    if (!serial) log("No camera found");
                    else await startLive(serial, "manual");
                    break;
                }
                case "stop": for (const s of activeLive.keys()) await eufy.stopStationLivestream(s); break;
                case "load": reportLoad(); break;
                case "quit": case "exit": shutdown(); break;
                case "help":
                default:
                    log("Commands: list | live [serial] | stop | load | quit");
            }
        } catch (e) {
            log(`Error: ${e.message}`);
        }
    });
}

function shutdown() {
    log("Shutting down");
    try { eufy && eufy.close(); } catch (_) { /* ignore */ }
    logStream.end(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("unhandledRejection", (e) => log(`Unhandled: ${e && e.stack ? e.stack : e}`));

main().catch((e) => { log(`Fatal: ${e && e.stack ? e.stack : e}`); process.exit(1); });
