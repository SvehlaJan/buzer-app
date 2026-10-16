import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const backendRoot = fileURLToPath(new URL("..", import.meta.url));

describe("Docker image dependency install", () => {
  it("installs without lifecycle scripts so better-sqlite3 13 does not need a compiler", () => {
    // npm runs an implicit `node-gyp rebuild` for better-sqlite3 13 (it ships binding.gyp and no
    // install script), and node-gyp needs Python, which node:22-bookworm-slim does not have.
    const dockerfile = readFileSync(`${backendRoot}/../Dockerfile`, "utf8");

    expect(dockerfile).toContain("RUN npm ci --ignore-scripts");
  });

  it("only skips install scripts the image does not need", () => {
    const lockfile = JSON.parse(readFileSync(`${backendRoot}/package-lock.json`, "utf8")) as {
      packages: Record<string, { hasInstallScript?: boolean; dev?: boolean }>;
    };
    const productionPackagesWithScripts = Object.entries(lockfile.packages)
      .filter(([, entry]) => entry.hasInstallScript === true && entry.dev !== true)
      .map(([path]) => path);

    // puppeteer's postinstall only downloads browsers; the image uses Debian's Chromium.
    expect(productionPackagesWithScripts).toEqual(["node_modules/puppeteer"]);
  });

  it("relies on the better-sqlite3 prebuild for the image's platform", () => {
    expect(existsSync(`${backendRoot}/node_modules/better-sqlite3/prebuilds/linux-x64.node`)).toBe(true);
  });
});
