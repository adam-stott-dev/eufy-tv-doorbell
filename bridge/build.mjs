// Bundles the bridge and eufy-security-client into one file for the app's assets.
// Usage: node build.mjs [outDir]
import { build } from "esbuild";
import { cpSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const out = resolve(process.argv[2] || "dist");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

await build({
    entryPoints: ["src/main.js"],
    outfile: join(out, "main.js"),
    bundle: true,
    platform: "node",
    target: "node24",
    format: "cjs",
    minify: true,
    legalComments: "linked",
    logLevel: "warning",
});

// The library loads these at run time from next to its own code, which is now main.js.
const lib = join(dirname(require.resolve("eufy-security-client/package.json")), "build");
cpSync(join(lib, "push", "proto"), join(out, "proto"), { recursive: true });
cpSync(join(lib, "mqtt", "proto"), join(out, "proto"), { recursive: true });
cpSync(join(lib, "mqtt", "mqtt-eufy.crt"), join(out, "mqtt-eufy.crt"));
console.log(`Bridge bundled to ${out}`);
