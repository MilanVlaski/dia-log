#!/usr/bin/env bun
/**
 * version.js — Sync the Maven reactor version from the root pom.
 *
 * The `<version>` element in the root pom.xml is the single source of truth
 * for the project version. To bump it:
 *
 *   1. Edit `<version>` in the root pom.xml by hand.
 *   2. Run:  bun src/build/version.js
 *
 * The script reads the version from the root pom and rewrites the
 * `<parent><version>` reference in every module listed in the root's
 * `<modules>` section — plus a module's own `<version>`, if it declares one.
 * It never modifies the root pom itself, and it is idempotent: running it
 * when everything is already in sync changes nothing.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function fail(message) {
  console.error(`version.js: ${message}`);
  process.exit(1);
}

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const rootPomPath = path.join(root, 'pom.xml');

let rootPomText;
try {
  rootPomText = readFileSync(rootPomPath, 'utf8');
} catch {
  fail(`cannot read root pom at ${rootPomPath}`);
}

// The project's own GAV: <groupId>/<artifactId>/<version> at the top of the
// pom, before any section. The root pom has no <parent>, so its first
// artifactId-adjacent <version> is the project version.
const gavMatch = rootPomText.match(
  /<groupId>[^<]*<\/groupId>\s*<artifactId>[^<]*<\/artifactId>\s*<version>([^<]*)<\/version>/,
);
if (!gavMatch) {
  fail('root pom has no <groupId>/<artifactId>/<version> block; cannot determine project version');
}
const rootVersion = gavMatch[1];
// Keep the replacement XML-safe and Maven-plausible; versions never contain '$' here.
if (!/^[A-Za-z0-9._-]+$/.test(rootVersion)) {
  fail(`root pom version '${rootVersion}' looks invalid (expected alphanumerics, '.', '_', '-')`);
}

// Modules are exactly what the root pom declares — no filesystem walking.
const modulesBlock = rootPomText.match(/<modules>([\s\S]*?)<\/modules>/);
if (!modulesBlock) {
  fail('root pom declares no <modules>; nothing to sync');
}
const modules = [...modulesBlock[1].matchAll(/<module>([^<]*)<\/module>/g)].map((m) => m[1]);
if (modules.length === 0) {
  fail('root pom <modules> section is empty; nothing to sync');
}

// Section tags that end the pom "header" (the region before dependencies,
// build config, etc., where a module's own GAV lives).
const headerEndRe = /<(properties|dependencies|modules|build|reporting|distributionManagement|profiles)>/;

let changed = 0;
for (const module of modules) {
  const pomPath = path.join(root, module, 'pom.xml');
  let text;
  try {
    text = readFileSync(pomPath, 'utf8');
  } catch {
    fail(`module '${module}' has no pom.xml at ${pomPath}`);
  }

  let out = text;
  const notes = [];

  // 1) <parent><version> — the module's reference to the root project.
  const parentMatch = text.match(/<parent>[\s\S]*?<\/parent>/);
  if (!parentMatch) {
    fail(`module '${module}' has no <parent> block; cannot sync its version reference`);
  }
  const parentBlock = parentMatch[0];
  const parentVersion = parentBlock.match(/<version>([^<]*)<\/version>/);
  if (parentVersion && parentVersion[1] !== rootVersion) {
    out = out.replace(
      parentBlock,
      parentBlock.replace(/(<version>)[^<]*(<\/version>)/, `$1${rootVersion}$2`),
    );
    notes.push(`parent ${parentVersion[1]} -> ${rootVersion}`);
  }

  // 2) The module's own <version>, if declared: in the header after the
  //    <parent> block (GAV convention), directly following its <artifactId>.
  const header = out.slice(parentMatch.index + parentBlock.length);
  const headerEnd = header.search(headerEndRe);
  const headerRegion = headerEnd === -1 ? header : header.slice(0, headerEnd);
  const ownGav = headerRegion.match(
    /<artifactId>([^<]*)<\/artifactId>\s*<version>([^<]*)<\/version>/,
  );
  if (ownGav && ownGav[2] !== rootVersion) {
    out = out.replace(
      new RegExp(`(<artifactId>${escapeRegExp(ownGav[1])}</artifactId>\\s*<version>)[^<]*(</version>)`),
      `$1${rootVersion}$2`,
    );
    notes.push(`own ${ownGav[2]} -> ${rootVersion}`);
  }

  if (out !== text) {
    writeFileSync(pomPath, out, 'utf8');
    // Verify the write actually landed before reporting success.
    const verify = readFileSync(pomPath, 'utf8');
    if (verify !== out) {
      fail(`verification read of ${module}/pom.xml differs from what was written`);
    }
    changed++;
    console.log(`synced ${module}/pom.xml (${notes.join('; ')})`);
  } else {
    console.log(`unchanged ${module}/pom.xml (already at ${rootVersion})`);
  }
}

console.log(`root version ${rootVersion} synced across ${modules.length} module(s), ${changed} updated`);
