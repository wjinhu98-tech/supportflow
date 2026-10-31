import { constants, createReadStream } from "node:fs";
import { access, lstat, mkdir, open, readFile, readdir, realpath, writeFile } from "node:fs/promises";
import { createHash, randomBytes } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createGunzip } from "node:zlib";

const repository = fileURLToPath(new URL("../", import.meta.url));
const nativeRoot = "target/rag-native/ai/onnxruntime/native/linux-x64";
const nativeNames = ["libonnxruntime.so", "libonnxruntime4j_jni.so"];
const maxImageBytes = 2 * 1024 ** 3;
const maxPayloadBytes = 3 * 1024 ** 3;
const repositoryUrl = "https://github.com/wjinhu98-tech/supportflow";
const deliveredDocs = ["docs/delivery-install.md", "docs/demo-guide.md", "docs/project-overview.md", "docs/interview-guide.md"];
const fixedFiles = [
  "deploy/nginx.conf", "deploy/prometheus.yml", "deploy/prometheus-rules.yml", "deploy/sql/init-lab.sh",
  "scripts/setup-rag-model.ps1", "scripts/session-client.mjs", "scripts/check-human-support-lab.mjs",
  "scripts/check-shop-windows-lab.mjs", ...deliveredDocs,
];
// Only these reviewed public repository files may become online evidence links. Do not inspect or copy their contents.
const sourceDocLinks = new Set([
  "README.md", "deploy/README.md", "frontend/README.md",
  "docs/after-sale-execution.md", "docs/agent-loop-budget.md", "docs/agent-resilience.md",
  "docs/human-support.md", "docs/job-readiness.md", "docs/semantic-rag.md", "docs/project-boundaries.md",
  "src/main/java/dev/supportflow/assistant/aftersale/AfterSalePolicy.java",
  "src/main/java/dev/supportflow/assistant/aftersale/AfterSaleService.java",
  "src/main/java/dev/supportflow/assistant/execution/AfterSaleExecutionProcessor.java",
  "src/main/java/dev/supportflow/assistant/execution/SimulatedRefundGateway.java",
  "src/main/java/dev/supportflow/assistant/knowledge/KnowledgeService.java",
  "src/main/java/dev/supportflow/assistant/knowledge/RagKnowledgeSearch.java",
  "src/main/java/dev/supportflow/assistant/orchestration/ConversationOrchestrator.java",
  "src/main/java/dev/supportflow/assistant/runtime/AgentLoopExecutor.java",
  "src/main/java/dev/supportflow/assistant/runtime/ReadOnlyAgentRuntime.java",
  "src/main/java/dev/supportflow/assistant/runtime/model/AgentBudget.java",
  "src/main/java/dev/supportflow/chat/conversation/ConversationController.java",
  "src/main/java/dev/supportflow/chat/conversation/ConversationService.java",
  "src/main/java/dev/supportflow/chat/infrastructure/deepseek/DeepSeekPublicKnowledgeSelector.java",
  "src/main/java/dev/supportflow/chat/support/HumanSupportService.java",
  "src/main/java/dev/supportflow/merchant/knowledge/KnowledgeImportService.java",
  "src/main/java/dev/supportflow/merchant/knowledge/KnowledgeManagementService.java",
  "src/main/java/dev/supportflow/order/api/controller/OrderController.java",
  "src/main/java/dev/supportflow/order/application/OrderQueryService.java",
  "src/main/java/dev/supportflow/order/infrastructure/persistence/jdbc/JdbcSupportOrderRepository.java",
  "src/main/resources/db/migration/V21__persistent_knowledge_vectors.sql",
  "src/main/resources/db/migration/V22__human_support_handoff.sql",
  "src/test/java/dev/supportflow/assistant/aftersale/AfterSalePolicyTest.java",
  "src/test/java/dev/supportflow/assistant/knowledge/PersistentKnowledgeVectorIT.java",
  "src/test/java/dev/supportflow/assistant/knowledge/PublicKnowledgeServiceTest.java",
  "src/test/java/dev/supportflow/assistant/runtime/AgentLoopPersistenceIT.java",
  "src/test/java/dev/supportflow/chat/support/HumanSupportApiIT.java",
]);
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const json = (value) => `${JSON.stringify(value, null, 2)}\n`;
const normalize = (text) => text.replaceAll("\r\n", "\n");

export function parseVersion(pom) {
  const project = pom.replace(/<!--[\s\S]*?-->/g, "").replace(/<parent>[\s\S]*?<\/parent>/, "");
  const version = project.match(/<version>\s*([^<]+?)\s*<\/version>/)?.[1];
  if (!version || !/^[0-9][A-Za-z0-9.-]{0,79}$/.test(version)) throw new Error("Invalid project version");
  return version;
}

export function imageTags(version, sha) {
  const suffix = `${version}-${sha.slice(0, 12)}`;
  return [`supportflow-lab-backend:${suffix}`, `supportflow-lab-frontend:${suffix}`, "mysql:8.0.45", "prom/prometheus:v3.2.1"];
}

// Every component is checked, including junctions on Windows. The CLI accepts no input/output path flags.
export async function safePath(root, relative, kind = "file") {
  if (!relative || relative.includes("\\") || relative.startsWith("/") || relative.split("/").some((part) => !part || part === "." || part === ".." || part.includes(":"))) {
    throw new Error("Unsafe bundle path");
  }
  const resolvedRoot = await realpath(root);
  let current = resolvedRoot;
  for (const part of relative.split("/")) {
    current = path.join(current, part);
    if ((await lstat(current)).isSymbolicLink()) throw new Error(`Symbolic links/junctions are forbidden: ${relative}`);
  }
  const resolved = await realpath(current);
  if (!resolved.startsWith(`${resolvedRoot}${path.sep}`)) throw new Error("Input outside bundle root");
  const stat = await lstat(current);
  if (kind === "file" ? !stat.isFile() : !stat.isDirectory()) throw new Error(`Expected ${kind}: ${relative}`);
  return current;
}

async function readSafe(root, relative) { return readFile(await safePath(root, relative)); }
async function exists(file) { try { await access(file, constants.F_OK); return true; } catch (error) { if (error.code === "ENOENT") return false; throw error; } }

export async function rewriteDocumentLinks({ root, relative, markdown, sha, bundledFiles }) {
  if (!deliveredDocs.includes(relative)) throw new Error("Document is not in the delivery allowlist");
  let result = "", end = 0;
  for (const match of markdown.matchAll(/\]\(([^)\r\n]+)\)/g)) {
    const target = match[1];
    result += markdown.slice(end, match.index);
    end = match.index + match[0].length;
    if (target.includes("\\") || /^[A-Za-z]:\//.test(target)) throw new Error("Unsafe documentation link");
    if (target.startsWith("#") || target.startsWith("//") || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(target)) {
      result += match[0]; continue;
    }
    if (/\s|\\/.test(target) || target.startsWith("/")) throw new Error("Unsafe documentation link");
    const [, file, suffix = ""] = target.match(/^([^?#]*)([?#].*)?$/);
    const parts = relative.split("/").slice(0, -1);
    for (const part of file.split("/")) {
      if (!part || part === ".") continue;
      if (part === "..") {
        if (!parts.length) throw new Error("Documentation link escapes repository root");
        parts.pop();
      } else {
        if (part.includes(":")) throw new Error("Unsafe documentation link");
        parts.push(part);
      }
    }
    const resolved = parts.join("/");
    if (bundledFiles.has(resolved)) result += match[0];
    else {
      if (!sourceDocLinks.has(resolved)) throw new Error(`Unreviewed documentation source link: ${resolved}`);
      await safePath(root, resolved); // Existence and link boundaries only; source files never enter the payload.
      result += `](${repositoryUrl}/blob/${sha}/${resolved}${suffix})`;
    }
  }
  return result + markdown.slice(end);
}

function bundleReadme(version, sha, withImages) {
  const sourceState = process.env.GITHUB_ACTIONS === "true" && process.env.GITHUB_SHA === sha ? "ci-checkout" : "local-preview";
  return `# SupportFlow 交付包\n\n版本：\`${version}\`。源码提交：\`${sha}\`。\n\n` +
    `仓库：[SupportFlow](${repositoryUrl})；该提交中的源码：[README](${repositoryUrl}/blob/${sha}/README.md)。\n\n` +
    `来源：\`${sourceState}\`。${sourceState === "local-preview" ? "本地预览可能包含未提交的工作区修改，不能据此断言与该提交完全一致。" : "检查证明仍以 validation.json 为准。"}\n\n` +
    `这是 Linux/amd64 本机隔离实验包，数据库为 \`supportflow_lab\`。DeepSeek 与 BGE 默认关闭，退款只生成模拟回执。\n\n` +
    `## Windows 启动\n\n先在新的专用目录中解压并核验下载的 ZIP。进入解压目录后执行：\n\n` +
    "```powershell\n.\\supportflow.ps1 Verify\n" +
    `.\\supportflow.ps1 ${withImages ? "LoadImages" : "BuildImages"}\n` +
    ".\\supportflow.ps1 Prepare\n.\\supportflow.ps1 Start\n.\\supportflow.ps1 VerifyRuntime\n```\n\n" +
    (withImages ? "本包包含四个运行镜像；已有 Docker/Compose 的安装机可离线加载。\n\n" : "本包不含运行镜像；BuildImages 首次需要联网获取固定版本的公共镜像，不重新编译前后端。\n\n") +
    `买家：[店铺 100](http://127.0.0.1:18090/?shopId=100)；商家：[工作台](http://127.0.0.1:18090/merchant)。两个角色使用独立浏览器或隐私窗口。账号 \`lab-buyer\` / \`lab-merchant\` 的随机密码在本机 \`.local/deployment-lab/login.txt\`，不随包分发。\n\n` +
    `## 阅读入口\n\n- [安装、校验与停止](docs/delivery-install.md)\n- [五分钟双端演示](docs/demo-guide.md)\n- [业务角色、架构与调用链](docs/project-overview.md)\n- [Java / AI 面试指南](docs/interview-guide.md)\n\n` +
    `四份指南在包内可离线阅读；未随包分发的源码证据链接固定到上述提交，需要联网查看。本文不声明检查通过，实际证明见 [validation.json](validation.json)，文件清单与校验信息见 [manifest.json](manifest.json) 和 [SHA256SUMS](SHA256SUMS)。\n`;
}

function onlyKeys(value, keys, label) {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some((key) => !keys.includes(key))) throw new Error(`Invalid ${label} fields`);
}

export function validateProof(proof, sha) {
  onlyKeys(proof, ["sourceSha", "checks"], "validation");
  if (proof.sourceSha !== sha || !Array.isArray(proof.checks) || proof.checks.length !== 3) throw new Error("Validation must identify this revision and all three jobs");
  const expected = new Set(["backend-live", "frontend-mock", "automation"]);
  for (const check of proof.checks) {
    onlyKeys(check, ["name", "status", "command", "exitCode"], "check");
    if (!expected.delete(check.name) || !["passed", "failed", "not-run"].includes(check.status)) throw new Error("Invalid/duplicate validation check");
    if (typeof check.command !== "string" || !/^[A-Za-z0-9_./ *:,=+()\[\]@;|&<>-]{1,1024}$/.test(check.command)
      || /password|api.?key|token|secret|\.local|private|login\.txt|https?:/i.test(check.command)) throw new Error("Unsafe validation command");
    if (check.status === "passed" ? check.exitCode !== 0 : check.status === "failed" ? !Number.isInteger(check.exitCode) || check.exitCode === 0 : check.exitCode !== null) throw new Error("Validation status/exit code disagree");
  }
  return { sourceSha: sha, checks: [...proof.checks].sort((a, b) => a.name.localeCompare(b.name)) };
}

export function validateImages(manifest, sha, tags) {
  onlyKeys(manifest, ["sourceSha", "images"], "image manifest");
  if (manifest.sourceSha !== sha || !Array.isArray(manifest.images) || manifest.images.length !== tags.length) throw new Error("Image manifest must identify this revision and all four images");
  const remaining = new Set(tags);
  for (const entry of manifest.images) {
    onlyKeys(entry, ["tag", "id"], "image");
    if (!remaining.delete(entry.tag) || !/^sha256:[a-f0-9]{64}$/.test(entry.id)) throw new Error("Invalid/duplicate image tag or ID");
  }
  return { sourceSha: sha, images: [...manifest.images].sort((a, b) => a.tag.localeCompare(b.tag)) };
}

// Inspect docker-save metadata without extracting anything or trusting the sidecar's tag claims.
export async function verifyDockerArchive(file, identities) {
  const expected = new Map(identities.map((entry) => [entry.tag, entry.id]));
  const fileHashes = new Map(), blobs = new Map(), files = new Set();
  let buffer = Buffer.alloc(0), remaining = 0, padding = 0, entry = null, manifest = null, ociIndex = null, ended = false, count = 0;
  function finishEntry() {
    let id = null;
    if (entry.hash) {
      id = `sha256:${entry.hash.digest("hex")}`;
      if (id !== `sha256:${path.posix.basename(entry.name).replace(/\.json$/, "")}`) throw new Error("Docker-save blob filename/hash mismatch");
      fileHashes.set(entry.name, id);
      blobs.set(id, { size: entry.size });
    }
    if (entry.parts) {
      const bytes = Buffer.concat(entry.parts);
      if (entry.name === "manifest.json") {
        if (manifest) throw new Error("Duplicate docker-save manifest");
        manifest = JSON.parse(bytes);
      } else if (entry.name === "index.json") ociIndex = JSON.parse(bytes);
      else if (id) {
        try { blobs.get(id).json = JSON.parse(bytes); } catch { /* A small binary layer is not JSON metadata. */ }
      }
    }
  }
  const gunzip = createReadStream(file).pipe(createGunzip());
  for await (const chunk of gunzip) {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length) {
      if (ended) { if (buffer.some((byte) => byte !== 0)) throw new Error("Unexpected data after tar terminator"); buffer = Buffer.alloc(0); break; }
      if (remaining) {
        const length = Math.min(remaining, buffer.length);
        const part = buffer.subarray(0, length);
        if (entry.hash) entry.hash.update(part);
        if (entry.parts) entry.parts.push(Buffer.from(part));
        remaining -= length; buffer = buffer.subarray(length);
        if (remaining) break;
        finishEntry();
      }
      if (padding) { const length = Math.min(padding, buffer.length); padding -= length; buffer = buffer.subarray(length); if (padding) break; }
      if (buffer.length < 512) break;
      const header = buffer.subarray(0, 512); buffer = buffer.subarray(512);
      if (header.every((byte) => byte === 0)) { ended = true; continue; }
      if (++count > 50000) throw new Error("Too many docker-save tar entries");
      const field = (offset, length) => header.subarray(offset, offset + length).toString("utf8").replace(/\0.*$/s, "");
      const name = [field(345, 155), field(0, 100)].filter(Boolean).join("/").replace(/\/$/, "");
      if (!name || name.includes("\\") || name.startsWith("/") || name.split("/").some((part) => !part || part === "." || part === ".." || part.includes(":"))) throw new Error("Unsafe docker-save tar path");
      const sizeText = field(124, 12).trim();
      if (!/^[0-7]+$/.test(sizeText)) throw new Error("Unsupported tar size encoding");
      const size = Number.parseInt(sizeText, 8);
      if (size > 0xffffffff) throw new Error("Docker tar entry exceeds ZIP size limit");
      const checksum = field(148, 8).trim();
      const actual = header.reduce((sum, byte, index) => sum + (index >= 148 && index < 156 ? 32 : byte), 0);
      if (!/^[0-7]+$/.test(checksum) || actual !== Number.parseInt(checksum, 8)) throw new Error("Invalid tar header checksum");
      const type = header[156];
      if (![0, 48, 53].includes(type)) throw new Error("Docker-save tar links/extended entries are unsupported");
      if (type === 53 && size !== 0) throw new Error("Invalid tar directory size");
      if (type !== 53 && files.has(name)) throw new Error("Duplicate docker-save tar file");
      if (type !== 53) files.add(name);
      const isBlob = /^(?:blobs\/sha256\/[a-f0-9]{64}|[a-f0-9]{64}\.json)$/.test(name);
      if (name === "manifest.json" && (size === 0 || size > 1024 * 1024)) throw new Error("Invalid docker-save manifest size");
      if (name === "index.json" && size > 1024 * 1024) throw new Error("Invalid OCI index size");
      entry = { name, size, hash: isBlob ? createHash("sha256") : null, parts: ["manifest.json", "index.json"].includes(name) || (isBlob && size <= 4 * 1024 ** 2) ? [] : null };
      remaining = size; padding = (512 - size % 512) % 512;
      if (size === 0) finishEntry();
    }
  }
  if (!ended || remaining || padding || !Array.isArray(manifest) || manifest.length !== 4) throw new Error("Incomplete docker-save archive");
  function reachesConfig(id, configId, depth = 0) {
    if (depth > 8) return false;
    if (id === configId) return true;
    const node = blobs.get(id)?.json;
    if (!node) return false;
    if (node.config?.digest === configId) {
      return blobs.get(configId)?.size === node.config.size && Array.isArray(node.layers)
        && node.layers.every((layer) => blobs.get(layer.digest)?.size === layer.size);
    }
    return Array.isArray(node.manifests) && node.manifests.some((descriptor) =>
      blobs.get(descriptor.digest)?.size === descriptor.size && reachesConfig(descriptor.digest, configId, depth + 1));
  }
  for (const image of manifest) {
    if (!image || !Array.isArray(image.RepoTags) || image.RepoTags.length !== 1 || !Array.isArray(image.Layers)) throw new Error("Unexpected docker-save image manifest");
    const tag = image.RepoTags[0];
    const configId = fileHashes.get(image.Config), imageId = expected.get(tag), config = blobs.get(configId)?.json;
    if (!imageId || !configId || !reachesConfig(imageId, configId) || image.Layers.some((layer) => !files.has(layer))) throw new Error("Docker-save image tags/config IDs disagree with images.json");
    if (config?.os !== "linux" || config?.architecture !== "amd64") throw new Error("Docker-save runtime images must be linux/amd64");
    if (imageId !== configId) {
      const canonical = tag.includes("/") ? `docker.io/${tag}` : `docker.io/library/${tag}`;
      if (!ociIndex?.manifests?.some((descriptor) => descriptor.digest === imageId
        && descriptor.annotations?.["io.containerd.image.name"] === canonical)) throw new Error("OCI image tag/index identity disagrees with images.json");
    }
    expected.delete(tag);
  }
  if (expected.size) throw new Error("Required runtime image is absent from docker-save archive");
}

async function distFiles(root, relative = "frontend/dist") {
  const directory = await safePath(root, relative, "directory");
  const files = [];
  for (const entry of (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    const candidate = `${relative}/${entry.name}`;
    if (entry.isSymbolicLink() || entry.name.startsWith(".") || !/^[A-Za-z0-9_.-]+$/.test(entry.name)) throw new Error("Unsafe frontend asset path");
    if (entry.isDirectory()) files.push(...await distFiles(root, candidate));
    else {
      const asset = candidate.slice("frontend/dist/".length);
      if (!/^(?:index\.html|favicon\.(?:svg|ico)|(?:assets|products)\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+\.(?:js|css|svg|png|jpe?g|webp|gif|ico|woff2?|ttf))$/.test(asset)) throw new Error(`Unexpected frontend asset: ${asset}`);
      await safePath(root, candidate);
      files.push(candidate);
    }
  }
  return files;
}

export function runtimeCompose(source) {
  let compose = normalize(source);
  for (const name of ["backend", "frontend"]) {
    const build = new RegExp(`^([ ]+)build:\\n\\1  context: \\.\\.\\n\\1  dockerfile: deploy/${name}\\.Dockerfile\\n`, "m");
    if (!build.test(compose)) throw new Error(`Deployment ${name} build contract changed`);
    compose = compose.replace(build, "");
    const image = new RegExp(`image: supportflow-lab-${name}:[^\\s]+`, "g");
    if ((compose.match(image) ?? []).length !== 1) throw new Error("Deployment image contract changed");
    compose = compose.replace(image, `image: \${SUPPORTFLOW_${name.toUpperCase()}_IMAGE:?Use the delivered .env file}`);
  }
  compose = compose.replace('SUPPORTFLOW_RAG_ENABLED: "true"', 'SUPPORTFLOW_RAG_ENABLED: "false"');
  const modelMount = "    volumes:\n      - ../.local/models/bge-small-zh-v1.5:/app/models/bge-small-zh-v1.5:ro\n";
  if (!compose.includes(modelMount)) throw new Error("Deployment model mount contract changed");
  compose = compose.replace(modelMount, "");
  for (const [port, name] of [[18090, "FRONTEND"], [18091, "MANAGEMENT"], [19090, "PROMETHEUS"]]) {
    compose = compose.replace(`127.0.0.1:${port}:`, `127.0.0.1:\${SUPPORTFLOW_${name}_PORT:-${port}}:`);
  }
  const seeds = ["seed-shops", "seed-products", "seed-product-images"];
  let previous = "seed-accounts";
  let jobs = "";
  for (const name of seeds) {
    jobs += `  ${name}:\n    <<: *java-tool\n    command: [${name}]\n    depends_on:\n      ${previous}:\n        condition: service_completed_successfully\n\n`;
    previous = name;
  }
  compose = compose.replace("  backend:\n", `${jobs}  backend:\n`);
  compose = compose.replace("      seed-accounts:\n        condition: service_completed_successfully\n    healthcheck:", "      seed-product-images:\n        condition: service_completed_successfully\n    healthcheck:");
  if (/^\s+build:/m.test(compose) || !compose.includes('SUPPORTFLOW_RAG_ENABLED: "false"') || !compose.startsWith("name: supportflow-lab\n")) throw new Error("Invalid release runtime composition");
  return compose;
}

const crcTable = Array.from({ length: 256 }, (_, value) => { let crc = value; for (let bit = 0; bit < 8; bit++) crc = (crc & 1) ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1; return crc >>> 0; });
function crcUpdate(crc, buffer) { for (const byte of buffer) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8); return crc; }

// Streaming ZIP STORE: bounded memory even for image archives, fixed timestamps, sorted UTF-8 names.
// Deliberately reject ZIP64-size inputs rather than emitting a corrupt classic ZIP.
export async function writeZipExclusive(root, files, destination) {
  if (files.length > 65535) throw new Error("ZIP64 entry count is unsupported");
  const output = await open(destination, "wx");
  const central = [];
  let offset = 0;
  async function append(bytes) {
    if (offset + bytes.length > 0xffffffff) throw new Error("ZIP64 archive size is unsupported");
    let written = 0;
    while (written < bytes.length) written += (await output.write(bytes, written, bytes.length - written)).bytesWritten;
    offset += bytes.length;
  }
  try {
    for (const relative of [...files].sort()) {
      const file = await safePath(root, relative);
      const name = Buffer.from(relative);
      const local = Buffer.alloc(30);
      local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x0808, 6);
      local.writeUInt16LE(33, 12); local.writeUInt16LE(name.length, 26);
      const start = offset;
      await append(local); await append(name);
      let crc = 0xffffffff, size = 0;
      for await (const chunk of createReadStream(file)) { crc = crcUpdate(crc, chunk); size += chunk.length; await append(chunk); }
      crc = (crc ^ 0xffffffff) >>> 0;
      const descriptor = Buffer.alloc(16);
      descriptor.writeUInt32LE(0x08074b50); descriptor.writeUInt32LE(crc, 4); descriptor.writeUInt32LE(size, 8); descriptor.writeUInt32LE(size, 12);
      await append(descriptor);
      const header = Buffer.alloc(46);
      header.writeUInt32LE(0x02014b50); header.writeUInt16LE(20, 4); header.writeUInt16LE(20, 6); header.writeUInt16LE(0x0808, 8); header.writeUInt16LE(33, 14);
      header.writeUInt32LE(crc, 16); header.writeUInt32LE(size, 20); header.writeUInt32LE(size, 24); header.writeUInt16LE(name.length, 28); header.writeUInt32LE(start, 42);
      central.push(header, name);
    }
    const centralOffset = offset;
    for (const bytes of central) await append(bytes);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10); end.writeUInt32LE(offset - centralOffset, 12); end.writeUInt32LE(centralOffset, 16);
    await append(end);
  } finally { await output.close(); }
}

export async function hashFile(file) { const hash = createHash("sha256"); for await (const chunk of createReadStream(file)) hash.update(chunk); return hash.digest("hex"); }

export async function buildDelivery({ root = repository, sha, builtAt = new Date().toISOString(), withImages = false, outputId }) {
  if (!/^[a-f0-9]{7,40}$/.test(sha ?? "")) throw new Error("--sha must be 7-40 lowercase hexadecimal characters (CI supplies the full revision)");
  if (typeof builtAt !== "string" || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(builtAt) || !Number.isFinite(Date.parse(builtAt))) throw new Error("--built-at must be an ISO UTC timestamp");
  const version = parseVersion((await readSafe(root, "pom.xml")).toString("utf8"));
  const tagList = imageTags(version, sha);
  const proofPath = "target/delivery-input/validation.json";
  const proof = await exists(path.join(root, proofPath)) ? validateProof(JSON.parse(await readSafe(root, proofPath)), sha) : { sourceSha: sha, checks: [], note: "No validation proof supplied; this bundle makes no test-pass claim." };
  let images = null;
  if (withImages) {
    images = validateImages(JSON.parse(await readSafe(root, "target/delivery-input/images.json")), sha, tagList);
    const imageFile = await safePath(root, "target/delivery-input/supportflow-images.tar.gz");
    if ((await lstat(imageFile)).size > maxImageBytes) throw new Error("Saved image archive exceeds the 2 GiB delivery limit");
    await verifyDockerArchive(imageFile, images.images);
  }
  const jar = `target/supportflow-backend-${version}.jar`;
  const sources = [jar, ...nativeNames.map((name) => `${nativeRoot}/${name}`), ...await distFiles(root), ...fixedFiles];
  if (!sources.includes("frontend/dist/index.html")) throw new Error("Built frontend index.html is required");
  // Validate every input before creating an output. No repository-wide traversal occurs.
  for (const relative of sources) await safePath(root, relative);
  const generated = new Map([
    ["README.md", bundleReadme(version, sha, withImages)],
    ["deploy/compose.yml", runtimeCompose((await readSafe(root, "deploy/compose.yml")).toString("utf8"))],
    ["deploy/backend.Dockerfile", normalize((await readSafe(root, "deploy/bundle/backend.Dockerfile")).toString("utf8")).replaceAll("@@VERSION@@", version)],
    ["deploy/frontend.Dockerfile", normalize((await readSafe(root, "deploy/bundle/frontend.Dockerfile")).toString("utf8"))],
    ["deploy/compose.rag.yml", normalize((await readSafe(root, "deploy/bundle/compose.rag.yml")).toString("utf8"))],
    [".dockerignore", normalize((await readSafe(root, "deploy/bundle/.dockerignore")).toString("utf8"))],
    ["supportflow.ps1", normalize((await readSafe(root, "deploy/bundle/supportflow.ps1")).toString("utf8"))],
    [".env", `SUPPORTFLOW_BACKEND_IMAGE=${tagList[0]}\nSUPPORTFLOW_FRONTEND_IMAGE=${tagList[1]}\n`],
    ["validation.json", json(proof)],
  ]);
  const bundledFiles = new Set([...sources, ...generated.keys()]);
  for (const relative of deliveredDocs) {
    generated.set(relative, await rewriteDocumentLinks({ root, relative, markdown: normalize((await readSafe(root, relative)).toString("utf8")), sha, bundledFiles }));
  }
  if (images) sources.push("target/delivery-input/supportflow-images.tar.gz");
  let inputBytes = 0;
  for (const relative of sources) inputBytes += (await lstat(await safePath(root, relative))).size;
  if (inputBytes > maxPayloadBytes) throw new Error("Delivery payload exceeds the 3 GiB delivery limit");
  const deliveryParent = path.join(await realpath(root), "target", "delivery");
  await safePath(root, "target", "directory");
  if (await exists(deliveryParent)) await safePath(root, "target/delivery", "directory");
  else await mkdir(deliveryParent);
  const id = outputId ?? `${builtAt.replaceAll(/[-:.TZ]/g, "")}-${randomBytes(4).toString("hex")}`;
  if (!/^[A-Za-z0-9-]{1,80}$/.test(id)) throw new Error("Invalid output identifier");
  const stem = `${version}-${sha.slice(0, 12)}-${id}`;
  const directory = path.join(deliveryParent, stem);
  const archive = `${directory}.zip`;
  if (await exists(directory) || await exists(archive) || await exists(`${archive}.sha256`)) throw new Error("Refusing to overwrite an existing delivery output");
  await mkdir(directory);
  const inventory = [];
  async function record(relative, bytes) {
    const file = path.join(directory, ...relative.split("/"));
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, bytes, { flag: "wx" });
    inventory.push({ path: relative, size: bytes.length, sha256: sha256(bytes) });
  }
  for (const relative of sources.sort()) {
    if (generated.has(relative)) continue; // Documentation copies were rewritten without changing the repository originals.
    const destination = relative === "target/delivery-input/supportflow-images.tar.gz" ? "supportflow-images.tar.gz" : relative;
    // Stream large artifacts to disk instead of holding JAR/images in memory.
    const file = path.join(directory, ...destination.split("/"));
    await mkdir(path.dirname(file), { recursive: true });
    const handle = await open(file, "wx");
    const hash = createHash("sha256"); let size = 0;
    try {
      for await (const chunk of createReadStream(await safePath(root, relative))) {
        hash.update(chunk); size += chunk.length;
        let written = 0; while (written < chunk.length) written += (await handle.write(chunk, written, chunk.length - written)).bytesWritten;
      }
    } finally { await handle.close(); }
    inventory.push({ path: destination, size, sha256: hash.digest("hex") });
  }
  for (const [relative, bytes] of generated) await record(relative, Buffer.from(bytes));
  inventory.sort((a, b) => a.path.localeCompare(b.path));
  const manifest = {
    schemaVersion: 1, application: "supportflow-lab", version, sourceSha: sha, builtAt,
    sourceState: process.env.GITHUB_ACTIONS === "true" && process.env.GITHUB_SHA === sha ? "ci-checkout" : "local-preview",
    platform: "linux/amd64", database: "supportflow_lab", ragEnabledByDefault: false,
    payloadBytes: inventory.reduce((sum, entry) => sum + entry.size, 0),
    validation: { proof: "validation.json", allJobsPassed: proof.checks.length === 3 && proof.checks.every((check) => check.status === "passed") },
    images: { included: withImages, archive: withImages ? "supportflow-images.tar.gz" : null, archiveBytes: inventory.find((entry) => entry.path === "supportflow-images.tar.gz")?.size ?? 0, requiredTags: tagList, identities: images?.images ?? [] },
    files: [...inventory],
  };
  await record("manifest.json", Buffer.from(json(manifest)));
  await record("SHA256SUMS", Buffer.from(inventory.map((entry) => `${entry.sha256}  ${entry.path}\n`).join("")));
  await writeZipExclusive(directory, inventory.map((entry) => entry.path), archive);
  const archiveSha256 = await hashFile(archive);
  await writeFile(`${archive}.sha256`, `${archiveSha256}  ${path.basename(archive)}\n`, { flag: "wx" });
  return { directory, archive, archiveSha256, archiveBytes: (await lstat(archive)).size, payloadBytes: manifest.payloadBytes, imageArchiveBytes: manifest.images.archiveBytes, version, sourceSha: sha, imageTags: tagList, allJobsPassed: manifest.validation.allJobsPassed };
}

async function main() {
  const args = process.argv.slice(2); const options = {};
  while (args.length) {
    const option = args.shift();
    if (option === "--with-images") { if (options.withImages) throw new Error("Duplicate --with-images"); options.withImages = true; }
    else if (option === "--sha" || option === "--built-at") {
      const key = option === "--sha" ? "sha" : "builtAt";
      if (options[key] || !args.length) throw new Error(`Invalid ${option}`);
      options[key] = args.shift();
    } else throw new Error(`Unknown argument: ${option}`);
  }
  console.log(json(await buildDelivery(options)).trim());
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((failure) => { console.error(`DELIVERY_FAILED ${failure.message}`); process.exitCode = 1; });
}
