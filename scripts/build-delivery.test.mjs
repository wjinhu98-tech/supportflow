import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import test from "node:test";
import { buildDelivery, hashFile, imageTags, parseVersion, rewriteDocumentLinks, runtimeCompose, safePath, validateImages, validateProof, verifyDockerArchive, writeZipExclusive } from "./build-delivery.mjs";

const sha = "0123456789abcdef0123456789abcdef01234567";
const builtAt = "2026-10-30T08:00:00.000Z";
const project = new URL("../", import.meta.url);
const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");
async function put(root, relative, bytes) { const file = path.join(root, ...relative.split("/")); await mkdir(path.dirname(file), { recursive: true }); await writeFile(file, bytes, { flag: "wx" }); return file; }
async function fixture({ omitNative = false } = {}) {
  // Fresh synthetic fixtures are intentionally preserved; tests never delete files/directories.
  const root = await mkdtemp(path.join(os.tmpdir(), "supportflow-delivery-test-"));
  await put(root, "pom.xml", "<project><parent><version>9</version></parent><version>0.1.0-SNAPSHOT</version></project>");
  await put(root, "target/supportflow-backend-0.1.0-SNAPSHOT.jar", "synthetic verified JAR input");
  if (!omitNative) for (const name of ["libonnxruntime.so", "libonnxruntime4j_jni.so"]) await put(root, `target/rag-native/ai/onnxruntime/native/linux-x64/${name}`, "synthetic native bytes");
  await put(root, "frontend/dist/index.html", "<!doctype html><script src='/assets/index-123.js'></script>");
  await put(root, "frontend/dist/assets/index-123.js", "console.log('synthetic public asset')");
  await put(root, "frontend/dist/products/demo/mug.png", Buffer.from([137, 80, 78, 71]));
  for (const relative of ["deploy/compose.yml", "deploy/bundle/backend.Dockerfile", "deploy/bundle/frontend.Dockerfile", "deploy/bundle/compose.rag.yml", "deploy/bundle/.dockerignore", "deploy/bundle/supportflow.ps1"]) {
    await put(root, relative, await readFile(new URL(relative, project)));
  }
  for (const relative of ["deploy/nginx.conf", "deploy/prometheus.yml", "deploy/prometheus-rules.yml", "deploy/sql/init-lab.sh", "scripts/setup-rag-model.ps1", "scripts/session-client.mjs", "scripts/check-human-support-lab.mjs", "scripts/check-shop-windows-lab.mjs", "docs/delivery-install.md"]) await put(root, relative, `synthetic public ${relative}\n`);
  await put(root, "docs/demo-guide.md", "# Demo\n[安装](delivery-install.md)\n[角色](project-overview.md#roles)\n[面试](interview-guide.md)\n[开发](../frontend/README.md#development)\n[订单服务](../src/main/java/dev/supportflow/order/application/OrderQueryService.java)\n[外链](https://example.org/guide?mode=offline#start)\n[本页](#demo)\n");
  await put(root, "docs/project-overview.md", "# Roles\n[演示](demo-guide.md)\n[订单服务](../src/main/java/dev/supportflow/order/application/OrderQueryService.java)\n");
  await put(root, "docs/interview-guide.md", "# Interview\n[架构](project-overview.md)\n[演示](demo-guide.md)\n");
  for (const relative of ["README.md", "frontend/README.md", "src/main/java/dev/supportflow/order/application/OrderQueryService.java"]) await put(root, relative, "PUBLIC_SOURCE_NOT_SHIPPED\n");
  for (const relative of [".local/deployment-lab/lab_app_password", ".env", "http-client.private.env.json", "src/main/resources/private.yml", "frontend/src/unused.ts", ".git/config"]) await put(root, relative, "SYNTHETIC_PRIVATE_DO_NOT_SHIP");
  return root;
}
function proof() { return { sourceSha: sha, checks: ["backend-live", "frontend-mock", "automation"].map((name) => ({ name, status: "passed", command: "node --test scripts/*.test.mjs", exitCode: 0 })) }; }

function tar(entries) {
  const records = [];
  for (const [name, body, type = 48] of entries) {
    const bytes = Buffer.from(body); const header = Buffer.alloc(512);
    header.write(name, 0, 100); header.write("0000644\0", 100); header.write("0000000\0", 108); header.write("0000000\0", 116);
    header.write(`${bytes.length.toString(8).padStart(11, "0")}\0`, 124); header.write("00000000000\0", 136); header.fill(32, 148, 156); header[156] = type;
    header.write("ustar\0", 257); header.write("00", 263);
    const checksum = header.reduce((sum, byte) => sum + byte, 0);
    header.write(`${checksum.toString(8).padStart(6, "0")}\0 `, 148);
    records.push(header, bytes, Buffer.alloc((512 - bytes.length % 512) % 512));
  }
  records.push(Buffer.alloc(1024));
  return gzipSync(Buffer.concat(records));
}
function syntheticImages(tags = imageTags("0.1.0-SNAPSHOT", sha)) {
  const entries = [], identities = [], manifests = [];
  for (let index = 0; index < tags.length; index++) {
    const config = JSON.stringify({ syntheticImage: index, architecture: "amd64", os: "linux" });
    const id = digest(config); const name = `blobs/sha256/${id}`;
    entries.push([name, config]); identities.push({ tag: tags[index], id: `sha256:${id}` }); manifests.push({ Config: name, RepoTags: [tags[index]], Layers: [] });
  }
  entries.push(["manifest.json", JSON.stringify(manifests)]);
  return { archive: tar(entries), identities, entries };
}
function syntheticOciImages({ architecture = "amd64" } = {}) {
  const tags = imageTags("0.1.0-SNAPSHOT", sha), entries = [], identities = [], manifests = [], roots = [];
  const blob = (value) => { const bytes = JSON.stringify(value), id = `sha256:${digest(bytes)}`; entries.push([`blobs/sha256/${id.slice(7)}`, bytes]); return { digest: id, size: Buffer.byteLength(bytes) }; };
  for (let index = 0; index < tags.length; index++) {
    const config = blob({ syntheticImage: index, architecture, os: "linux" });
    const image = blob({ schemaVersion: 2, config, layers: [] });
    const root = blob({ schemaVersion: 2, manifests: [{ ...image, platform: { architecture, os: "linux" } }] });
    identities.push({ tag: tags[index], id: root.digest });
    roots.push({ ...root, annotations: { "io.containerd.image.name": tags[index].includes("/") ? `docker.io/${tags[index]}` : `docker.io/library/${tags[index]}` } });
    manifests.push({ Config: `blobs/sha256/${config.digest.slice(7)}`, RepoTags: [tags[index]], Layers: [] });
  }
  entries.push(["index.json", JSON.stringify({ schemaVersion: 2, manifests: roots })], ["manifest.json", JSON.stringify(manifests)]);
  return { entries, archive: tar(entries), identities };
}
function zipEntries(bytes) {
  const end = bytes.length - 22;
  assert.equal(bytes.readUInt32LE(end), 0x06054b50);
  const count = bytes.readUInt16LE(end + 10); let cursor = bytes.readUInt32LE(end + 16);
  const entries = new Map();
  for (let index = 0; index < count; index++) {
    assert.equal(bytes.readUInt32LE(cursor), 0x02014b50);
    const size = bytes.readUInt32LE(cursor + 24), length = bytes.readUInt16LE(cursor + 28), local = bytes.readUInt32LE(cursor + 42);
    const name = bytes.subarray(cursor + 46, cursor + 46 + length).toString("utf8");
    assert.equal(bytes.readUInt32LE(local), 0x04034b50);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    entries.set(name, bytes.subarray(start, start + size));
    cursor += 46 + length + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  return entries;
}

test("version parsing uses project version and refuses path/placeholder versions", () => {
  assert.equal(parseVersion("<project><parent><version>8</version></parent><version>0.1.0-SNAPSHOT</version></project>"), "0.1.0-SNAPSHOT");
  for (const value of ["../x", "${revision}", "x/secret", ""]) assert.throws(() => parseVersion(`<project><version>${value}</version></project>`));
});

test("package allowlist excludes private/source data; inventory and deterministic ZIP match bytes", async () => {
  const root = await fixture(); await put(root, "target/delivery-input/validation.json", JSON.stringify(proof()));
  const first = await buildDelivery({ root, sha, builtAt, outputId: "first" });
  const second = await buildDelivery({ root, sha, builtAt, outputId: "second" });
  assert.equal(first.archiveSha256, second.archiveSha256);
  const bytes = await readFile(first.archive); assert.equal(bytes.includes(Buffer.from("SYNTHETIC_PRIVATE_DO_NOT_SHIP")), false);
  const entries = zipEntries(bytes); const manifest = JSON.parse(entries.get("manifest.json"));
  assert.equal(manifest.sourceSha, sha); assert.equal(manifest.validation.allJobsPassed, true); assert.equal(manifest.images.included, false);
  assert.ok(entries.has("target/supportflow-backend-0.1.0-SNAPSHOT.jar")); assert.ok(entries.has("frontend/dist/index.html"));
  assert.equal([...entries.keys()].some((name) => name.startsWith(".local/") || name.startsWith("src/") || name.startsWith("frontend/src/") || name.startsWith(".git/")), false);
  for (const entry of manifest.files) { assert.equal(entries.get(entry.path).length, entry.size); assert.equal(digest(entries.get(entry.path)), entry.sha256); }
  assert.equal(digest(entries.get("manifest.json")), entries.get("SHA256SUMS").toString().match(/([a-f0-9]{64})  manifest\.json/)[1]);
  assert.match(entries.get("deploy/backend.Dockerfile").toString(), /COPY.*supportflow-backend-0\.1\.0-SNAPSHOT\.jar/);
  assert.doesNotMatch(entries.get("deploy/frontend.Dockerfile").toString(), /npm|frontend\/src|FROM node/);
  assert.equal(await hashFile(first.archive), first.archiveSha256);
  assert.match(await readFile(`${first.archive}.sha256`, "utf8"), new RegExp(`^${first.archiveSha256}  `));
  await assert.rejects(buildDelivery({ root, sha, builtAt, outputId: "first" }), /overwrite/);
});

test("delivery contains offline guides and a useful entry README; online evidence is pinned to the source revision", async () => {
  const root = await fixture();
  const original = await readFile(path.join(root, "docs", "demo-guide.md"), "utf8");
  const result = await buildDelivery({ root, sha, builtAt });
  const bytes = await readFile(result.archive), entries = zipEntries(bytes);
  const readme = entries.get("README.md").toString("utf8");
  assert.match(readme, /0\.1\.0-SNAPSHOT/);
  assert.ok(readme.includes(sha));
  assert.match(readme, /local-preview.*未提交/s);
  assert.ok(readme.includes(`https://github.com/wjinhu98-tech/supportflow/blob/${sha}/README.md`));
  assert.match(readme, /supportflow\.ps1 Verify\n.*supportflow\.ps1 BuildImages\n.*supportflow\.ps1 Prepare\n.*supportflow\.ps1 Start\n.*supportflow\.ps1 VerifyRuntime/);
  assert.match(readme, /不声明检查通过/);
  for (const name of ["delivery-install", "demo-guide", "project-overview", "interview-guide"]) {
    assert.ok(entries.has(`docs/${name}.md`));
    assert.ok(readme.includes(`](docs/${name}.md)`));
  }
  const demo = entries.get("docs/demo-guide.md").toString("utf8");
  assert.ok(demo.includes("](project-overview.md#roles)"));
  assert.ok(demo.includes(`](https://github.com/wjinhu98-tech/supportflow/blob/${sha}/frontend/README.md#development)`));
  assert.ok(demo.includes(`](https://github.com/wjinhu98-tech/supportflow/blob/${sha}/src/main/java/dev/supportflow/order/application/OrderQueryService.java)`));
  assert.ok(demo.includes("](https://example.org/guide?mode=offline#start)"));
  assert.ok(demo.includes("](#demo)"));
  assert.equal(await readFile(path.join(root, "docs", "demo-guide.md"), "utf8"), original);
  assert.equal(bytes.includes(Buffer.from("PUBLIC_SOURCE_NOT_SHIPPED")), false);
  const manifest = JSON.parse(entries.get("manifest.json"));
  for (const name of ["README.md", "docs/demo-guide.md", "docs/project-overview.md", "docs/interview-guide.md"]) {
    assert.equal(manifest.files.find((entry) => entry.path === name)?.sha256, digest(entries.get(name)));
  }
});

test("documentation links reject root escapes, private and unknown files; approved evidence must exist", async () => {
  const root = await fixture();
  const rewrite = (markdown) => rewriteDocumentLinks({ root, relative: "docs/demo-guide.md", markdown, sha, bundledFiles: new Set(["docs/demo-guide.md"]) });
  for (const target of ["../../README.md", "../../../src/main/anything", "../.local/deployment-lab/lab_app_password", "../docs/not-reviewed.md", "C:\\private", "/outside"]) {
    await assert.rejects(rewrite(`[invalid](${target})`), /escapes|Unreviewed|Unsafe/);
  }
  await assert.rejects(rewrite("[missing](../deploy/README.md)"), /ENOENT/);
  assert.equal(await rewrite("[repository](../README.md#start)"), `[repository](https://github.com/wjinhu98-tech/supportflow/blob/${sha}/README.md#start)`);
  const external = "[url](https://example.org/a?x=1#part) [email](mailto:demo@example.org) [anchor](#part)";
  assert.equal(await rewrite(external), external);
});

test("current public guides export without relative references to files absent from the delivery", async () => {
  const root = fileURLToPath(project);
  const bundledFiles = new Set(["docs/delivery-install.md", "docs/demo-guide.md", "docs/project-overview.md", "docs/interview-guide.md"]);
  for (const relative of bundledFiles) {
    const markdown = await readFile(new URL(relative, project), "utf8");
    const exported = await rewriteDocumentLinks({ root, relative, markdown, sha, bundledFiles });
    for (const [, target] of exported.matchAll(/\]\(([^)\r\n]+)\)/g)) {
      if (target.startsWith("#") || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(target)) continue;
      assert.ok(bundledFiles.has(path.posix.normalize(path.posix.join(path.posix.dirname(relative), target.split("#")[0]))), `Offline document target is absent: ${relative} -> ${target}`);
    }
  }
});

test("missing proof never claims tests passed; malformed or leaked proof is refused", async () => {
  const result = await buildDelivery({ root: await fixture(), sha, builtAt });
  const manifest = JSON.parse(await readFile(path.join(result.directory, "manifest.json")));
  assert.equal(manifest.validation.allJobsPassed, false);
  assert.match(await readFile(path.join(result.directory, "validation.json"), "utf8"), /No validation proof/);
  for (const change of [
    (value) => { value.sourceSha = "f".repeat(40); },
    (value) => { value.checks[0].exitCode = 1; },
    (value) => { value.checks[0].command = "curl --password SYNTHETIC_PRIVATE"; },
    (value) => { value.checks[0].logs = "SYNTHETIC_PRIVATE"; },
    (value) => { value.checks[0].name = "untrusted"; },
  ]) { const value = proof(); change(value); assert.throws(() => validateProof(value, sha)); }
});

test("artifact paths reject traversal, junctions, unexpected assets and missing native libraries", async (context) => {
  await assert.rejects(buildDelivery({ root: await fixture({ omitNative: true }), sha, builtAt }), /ENOENT/);
  const root = await fixture();
  for (const name of ["../private", "/absolute", "C:/private", "frontend\\dist", "frontend/./dist"]) await assert.rejects(safePath(root, name), /Unsafe/);
  await put(root, "frontend/dist/assets/private.env", "SYNTHETIC_PRIVATE");
  await assert.rejects(buildDelivery({ root, sha, builtAt }), /Unexpected frontend asset/);
  const linked = await fixture();
  const outside = await mkdtemp(path.join(os.tmpdir(), "supportflow-outside-test-"));
  await put(outside, "secret.js", "SYNTHETIC_PRIVATE");
  try { await symlink(outside, path.join(linked, "frontend", "dist", "outside"), process.platform === "win32" ? "junction" : "dir"); }
  catch (failure) { if (["EPERM", "EACCES"].includes(failure.code)) { context.diagnostic("OS denied creating test junction; direct traversal tests passed"); return; } throw failure; }
  await assert.rejects(safePath(linked, "frontend/dist/outside/secret.js"), /Symbolic links/);
  await assert.rejects(buildDelivery({ root: linked, sha, builtAt }), /Unsafe frontend asset path/);
});

test("saved images must contain all exact tags and config IDs; tar paths/links and damaged gzip are refused", async () => {
  const root = await fixture(); const images = syntheticImages();
  await put(root, "target/delivery-input/images.json", JSON.stringify({ sourceSha: sha, images: images.identities }));
  const archive = await put(root, "target/delivery-input/supportflow-images.tar.gz", images.archive);
  await verifyDockerArchive(archive, images.identities);
  const result = await buildDelivery({ root, sha, builtAt, withImages: true });
  const manifest = JSON.parse(await readFile(path.join(result.directory, "manifest.json")));
  assert.equal(manifest.images.included, true); assert.equal(manifest.images.identities.length, 4);
  assert.match(await readFile(path.join(result.directory, "README.md"), "utf8"), /supportflow\.ps1 LoadImages/);
  const wrong = structuredClone(images.identities); wrong[0].id = `sha256:${"f".repeat(64)}`;
  await assert.rejects(verifyDockerArchive(archive, wrong), /disagree/);
  const unsafe = await put(root, "bad-path.tar.gz", tar([["../private", "synthetic"]]));
  await assert.rejects(verifyDockerArchive(unsafe, images.identities), /Unsafe/);
  const linked = await put(root, "linked.tar.gz", tar([["linked-config", "", 50]]));
  await assert.rejects(verifyDockerArchive(linked, images.identities), /links/);
  const truncated = await put(root, "truncated.tar.gz", images.archive.subarray(0, images.archive.length - 6));
  await assert.rejects(verifyDockerArchive(truncated, images.identities));
  assert.throws(() => validateImages({ sourceSha: sha, images: [...images.identities.slice(1), images.identities[1]] }, sha, imageTags("0.1.0-SNAPSHOT", sha)), /duplicate/);
});

test("ZIP writer refuses overwrite and runtime compose preserves lab guards without build/model requirements", async () => {
  const root = await fixture(); const file = path.join(root, "existing.zip"); await writeFile(file, "preserve");
  await assert.rejects(writeZipExclusive(root, ["pom.xml"], file), /EEXIST/);
  assert.equal(await readFile(file, "utf8"), "preserve");
  const compose = runtimeCompose(await readFile(new URL("deploy/compose.yml", project), "utf8"));
  assert.match(compose, /^name: supportflow-lab$/m); assert.doesNotMatch(compose, /^\s+build:|\.local\/models/m);
  assert.match(compose, /SUPPORTFLOW_RAG_ENABLED: "false"/);
  assert.match(compose, /127\.0\.0\.1:\$\{SUPPORTFLOW_FRONTEND_PORT:-18090\}:8080/);
  assert.match(compose, /127\.0\.0\.1:\$\{SUPPORTFLOW_MANAGEMENT_PORT:-18091\}:9091/);
  assert.match(compose, /127\.0\.0\.1:\$\{SUPPORTFLOW_PROMETHEUS_PORT:-19090\}:9090/);
  assert.match(compose, /seed-product-images:\n        condition: service_completed_successfully/);
  assert.doesNotMatch(compose, /host\.docker\.internal|network_mode: host|supportflow_rebuild/);
});

test("Docker 29 OCI index IDs are linked to exact tags, complete content and amd64 configs", async () => {
  const root = await fixture(), images = syntheticOciImages();
  const archive = await put(root, "oci.tar.gz", images.archive);
  await verifyDockerArchive(archive, images.identities);
  const changed = images.entries.map(([name, body]) => [name, body]);
  changed[0][1] += "tampered";
  await assert.rejects(verifyDockerArchive(await put(root, "oci-tampered.tar.gz", tar(changed)), images.identities), /hash mismatch/);
  const arm = syntheticOciImages({ architecture: "arm64" });
  await assert.rejects(verifyDockerArchive(await put(root, "oci-arm.tar.gz", arm.archive), arm.identities), /linux\/amd64/);
  const swapped = images.entries.map(([name, body]) => [name, body]);
  const index = JSON.parse(swapped.at(-2)[1]);
  index.manifests[0].annotations["io.containerd.image.name"] = "docker.io/library/wrong:tag";
  swapped.at(-2)[1] = JSON.stringify(index);
  await assert.rejects(verifyDockerArchive(await put(root, "oci-wrong-tag.tar.gz", tar(swapped)), images.identities), /tag\/index/);
});
