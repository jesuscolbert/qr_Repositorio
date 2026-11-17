var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// lease.mjs
var TTL = 3e4;
function transition(previous, request, now, policy = {}) {
  const s = { epoch: 0, owner: "", boot: "", expires: 0, ...previous };
  if (!/^[a-f0-9]{64}$/.test(request.device || "") || !/^[a-f0-9]{64}$/.test(request.boot || "") || !["acquire", "renew", "status", "release"].includes(request.action)) throw Error("Invalid request");
  const reports = request.reportsProtocol ?? 0;
  if (!Number.isSafeInteger(reports) || reports < 0 || reports > 1) throw Error("Invalid capability");
  const minimum = policy.minimumReportsProtocol ?? 0;
  const compatible = reports >= minimum || (policy.compatibleDevices || []).includes(request.device);
  const owns = s.owner === request.device && s.boot === request.boot && s.expires > now;
  if (request.action === "acquire" && compatible && s.expires <= now) {
    s.owner = request.device;
    s.boot = request.boot;
    s.epoch++;
    s.expires = now + TTL;
  } else if (request.action === "renew" && compatible && owns && request.epoch === s.epoch) {
    s.expires = now + TTL;
  } else if (request.action === "release" && owns && request.epoch === s.epoch) {
    s.expires = now;
  }
  return { state: s, reply: {
    owner: s.expires > now ? s.owner : "",
    boot: s.boot,
    epoch: s.epoch,
    remaining: Math.max(0, s.expires - now),
    needsUpdate: !compatible,
    requiredReportsProtocol: minimum
  } };
}
__name(transition, "transition");

// wallet.mjs
var hex = /^[a-f0-9]{64}$/;
function validateWallet(r) {
  if (!r || !["get", "changes", "bind", "revoke"].includes(r.action)) throw Error("Invalid action");
  if (r.action === "changes") {
    if (!Number.isSafeInteger(r.after) || r.after < 0) throw Error("Invalid cursor");
    return;
  }
  if (!hex.test(r.tag || "")) throw Error("Invalid person");
  if (r.action === "get") return;
  if (!Number.isSafeInteger(r.expected) || r.expected < 0 || !hex.test(r.requestId || "") || !hex.test(r.actor || "") || typeof r.by !== "string" || r.by.length < 3 || r.by.length > 100) throw Error("Invalid binding");
  if (r.action === "bind" && !hex.test(r.keyHash || "")) throw Error("Invalid key");
}
__name(validateWallet, "validateWallet");
async function walletOperation(tx, r, now) {
  validateWallet(r);
  const revision = await tx.get("walletRevision") || 0;
  if (r.action === "changes") {
    if (r.after > revision) throw Error("Future cursor");
    const changes = await tx.list({ prefix: "walletLog:", startAfter: "walletLog:" + String(r.after).padStart(16, "0"), limit: 100 });
    const items = [...changes.values()];
    return { ok: true, items, after: items.length ? items.at(-1).change : r.after, revision };
  }
  const previous = await tx.get("wallet:" + r.tag);
  if (r.action === "get") return { ok: true, binding: previous || null };
  const active = r.action === "bind";
  if (previous?.requestId === r.requestId) {
    if (previous.active !== active || active && previous.keyHash !== r.keyHash) throw Error("Request mismatch");
    return { ok: true, binding: previous };
  }
  if ((previous?.version || 0) !== r.expected) return { ok: false, error: "La vinculaci\xF3n cambi\xF3 en otro tel\xE9fono. Vuelve a consultar antes de confirmar." };
  if (active) {
    const other = await tx.get("walletKey:" + r.keyHash);
    if (other && other !== r.tag) return { ok: false, error: "Este celular ya est\xE1 vinculado a otro trabajador de esta empresa." };
  }
  if (previous?.active) await tx.delete("walletKey:" + previous.keyHash);
  const binding = { tag: r.tag, version: (previous?.version || 0) + 1, keyHash: active ? r.keyHash : "", active, time: now, actor: r.actor, by: r.by, requestId: r.requestId, change: revision + 1 };
  await tx.put("wallet:" + r.tag, binding);
  await tx.put("walletRevision", revision + 1);
  await tx.put("walletLog:" + String(revision + 1).padStart(16, "0"), binding);
  if (active) await tx.put("walletKey:" + r.keyHash, r.tag);
  return { ok: true, binding };
}
__name(walletOperation, "walletOperation");

// worker.mjs
function serverPolicy(env) {
  const minimumReportsProtocol = Number(env.REQUIRED_REPORTS_PROTOCOL || 0);
  const compatibleDevices = JSON.parse(env.REPORT_COMPAT_DEVICES || "[]");
  if (![0, 1].includes(minimumReportsProtocol) || !Array.isArray(compatibleDevices) || compatibleDevices.length > 100 || compatibleDevices.some((id) => typeof id !== "string" || !/^[a-f0-9]{64}$/.test(id))) throw Error("Invalid server policy");
  return { minimumReportsProtocol, compatibleDevices };
}
__name(serverPolicy, "serverPolicy");
var LeaderLease = class {
  static {
    __name(this, "LeaderLease");
  }
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }
  async fetch(request) {
    const input = await request.json();
    if (new URL(request.url).pathname === "/wallet") {
      const reply2 = await this.ctx.storage.transaction((tx) => walletOperation(tx, input, Date.now()));
      return Response.json(reply2, { headers: { "Cache-Control": "no-store" } });
    }
    const reply = await this.ctx.storage.transaction(async (tx) => {
      const next = transition(await tx.get("lease"), input, Date.now(), serverPolicy(this.env));
      await tx.put("lease", next.state);
      return { ...next.reply, walletRevision: await tx.get("walletRevision") || 0 };
    });
    return Response.json(reply, { headers: { "Cache-Control": "no-store" } });
  }
};
var worker_default = {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || !["/lease", "/wallet"].includes(url.pathname)) return new Response("Not found", { status: 404 });
    const supplied = request.headers.get("Authorization") || "";
    const expected = "Bearer " + env.NETWORK_KEY;
    const a = new TextEncoder().encode(supplied), b = new TextEncoder().encode(expected);
    if (!env.NETWORK_KEY || a.length !== b.length || !crypto.subtle.timingSafeEqual(a, b)) return new Response("Unauthorized", { status: 401 });
    const body = await request.text();
    if (body.length > 2048) return new Response("Too large", { status: 413 });
    try {
      const input = JSON.parse(body);
      if (url.pathname === "/lease") transition(void 0, input, Date.now());
      else validateWallet(input);
      return env.LEADER.get(env.LEADER.idFromName("comedor")).fetch(new Request("https://internal" + url.pathname, { method: "POST", body }));
    } catch {
      return new Response("Invalid request", { status: 400 });
    }
  }
};
export {
  LeaderLease,
  worker_default as default,
  serverPolicy
};
//# sourceMappingURL=worker.js.map
