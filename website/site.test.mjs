import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const html = await readFile(new URL("./index.html", import.meta.url), "utf8");
const css = await readFile(new URL("./styles.css", import.meta.url), "utf8");
const script = await readFile(new URL("./site.js", import.meta.url), "utf8");

test("public landing page includes required product sections and navigation", () => {
  for (const id of ["features", "how-it-works", "apps", "faq"]) {
    assert.match(html, new RegExp(`id="${id}"`));
    assert.match(html, new RegExp(`href="#${id}"`));
  }
  assert.match(html, /<html lang="zh-CN">/);
  assert.match(html, /设备本地执行/);
  assert.match(html, /按 Android 系统流程授权/);
});

test("public landing page does not ship placeholder or fabricated external URLs", () => {
  assert.doesNotMatch(html, /example\.com|TODO|FIXME/i);
  assert.doesNotMatch(html, /href="https?:\/\//);
  assert.match(html, /data-console-link/);
  assert.match(html, /正式下载渠道准备中/);
});

test("console CTA is enabled only for an explicitly configured HTTP(S) URL", () => {
  assert.match(script, /window\.flowpilotSiteConfig\?\.consoleUrl/);
  assert.match(script, /parsedUrl\.protocol === "https:" \|\| parsedUrl\.protocol === "http:"/);
  assert.match(script, /aria-disabled/);
});

test("landing page includes mobile and reduced-motion adaptations", () => {
  assert.match(css, /@media \(max-width: 680px\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css, /:focus-visible/);
});
