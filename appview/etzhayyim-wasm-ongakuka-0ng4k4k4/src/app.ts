import {
  asAgentTool,
  b2Get,
  createWorkerExport,
  decodeJson,
  genID,
  nowISO,
  nsid,
  withCapabilityTags,
  type B2Env,
  type HostSDK,
} from "@etzhayyim/kotodama-host-sdk";


let primaryRegistered = false;
let composerRegistered = false;

async function ensurePathDids(sdk: HostSDK): Promise<void> {
  if (!primaryRegistered) {
    sdk.pds.dispatch({ type: "com.atproto.identity.create", payload: { path: "", displayName: "Ongakuka", description: "AI Music Generator (controller)" } });
    primaryRegistered = true;
  }
  if (!composerRegistered) {
    sdk.pds.dispatch({ type: "com.atproto.identity.create", payload: { path: "actor:composer", displayName: "Composer", description: "Phase 0 instrumental composer (MusicGen-small)" } });
    composerRegistered = true;
  }
}

// ---------------------------------------------------------------------------
// BPMN dispatcher helpers (ADR-0056, proxyToBpmn pattern)
// ---------------------------------------------------------------------------
type InternalSecret = string | { get(): Promise<string> };
type EnvLike = { DISPATCHER_URL?: string; HYPERDRIVE?: unknown; DISPATCHER_INTERNAL_SECRET?: InternalSecret };
function envOf(sdk: unknown): EnvLike { return ((sdk as { env?: EnvLike }).env ?? {}) as EnvLike; }
function dispatcherUrl(sdk: unknown): string { return envOf(sdk).DISPATCHER_URL ?? "https://dispatcher.etzhayyim.com"; }
async function internalTrustHeader(sdk: unknown): Promise<string> {
  const binding = envOf(sdk).DISPATCHER_INTERNAL_SECRET;
  if (!binding) return "";
  try { return typeof binding === "string" ? binding : await binding.get(); } catch { return ""; }
}
async function proxyToBpmn(sdk: HostSDK, toolNsid: string, input: unknown): Promise<string> {
  const started = Date.now();
  const trust = await internalTrustHeader(sdk);
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (trust) headers["x-internal-trust"] = trust;
  const resp = await fetch(`${dispatcherUrl(sdk)}/xrpc/${toolNsid}`, {
    method: "POST", headers, body: JSON.stringify(input ?? {}),
  });
  const text = await resp.text();
  let result: unknown;
  try { result = text ? JSON.parse(text) : {}; } catch { result = { raw: text }; }
  return JSON.stringify({ ok: resp.ok, status: resp.status, nsid: toolNsid, result, latencyMs: Date.now() - started });
}


async function cmdHealth(): Promise<string> {
  return JSON.stringify({ ok: true, app: "ongakuka", ts: nowISO() });
}

async function cmdListTracks(sdk: HostSDK, body: Uint8Array): Promise<string> {
  const _ = sdk;
  const input = decodeJson(body, { offset: 0, limit: 50 });
  const offset = Math.max(0, Number(input.offset) || 0);
  const limit = Math.min(200, Math.max(1, Number(input.limit) || 50));
  return JSON.stringify({ items: [], total: 0, offset, limit, note: "Phase 0 stub — wire createKyselyDb in next iteration" });
}

export default createWorkerExport((sdk) => {
  sdk.app.query(nsid("com.etzhayyim.ongakuka.listTracks"), async (_ctx: unknown, body: Uint8Array) => cmdListTracks(sdk, body));

  // Enqueue 1 track: fire-and-forget to LangServer compose.bpmn (ADR-0056).
  // Heavy work: murakumo audio gen (~30s per attempt, up to 8 retries) +
  // B2 upload + RW writes. Must run in L7 LangServer, not L3 CF Worker.
  sdk.app.command(nsid("com.etzhayyim.ongakuka.compose"),
    async (_ctx: unknown, body: Uint8Array) => {
      const input = decodeJson(body, { title: "", style: "", durationSec: 0, seed: 0, preGenSha: "" });
      const style    = String(input.style    || "").trim();
      const preGenSha = String((input as { preGenSha?: string }).preGenSha || "").trim();
      if (!style && !preGenSha) return JSON.stringify({ error: "style or preGenSha is required" });
      const projectId = `proj-${genID()}`;
      const trackRkey = genID();
      await ensurePathDids(sdk);
      return proxyToBpmn(sdk, "com.etzhayyim.ongakuka.compose", {
        style,
        title:       String(input.title || `Track ${nowISO().slice(0, 16)}`),
        duration_sec: Math.min(30, Math.max(5, Number(input.durationSec) || 15)),
        seed:         input.seed ? Number(input.seed) : 0,
        project_id:   projectId,
        track_rkey:   trackRkey,
        pre_gen_sha:  preGenSha,
      });
    },
    asAgentTool("Enqueue a music track for generation (style prompt → LangServer compose.bpmn → ongakuka.music.generate)"),
    withCapabilityTags("write", "music", "audio", "generation"),
  );
  sdk.app.command(nsid("com.etzhayyim.ongakuka.health"), async () => cmdHealth());

  // Public blob serving for generated tracks (Phase 0).
  // GET https://ongakuka.etzhayyim.com/blobs/{sha256}.wav → B2 stream.
  const router = (sdk as unknown as { router: any }).router;
  router.get("/blobs/:key", async (c: any) => {
    const cenv = c?.env as B2Env | undefined;
    if (!cenv?.B2_KEY_ID) return new Response("B2 not configured", { status: 500 });
    const key = c.req.param("key") as string;
    if (!key || key.includes("..") || key.includes("/")) return new Response("invalid key", { status: 400 });
    const obj = await b2Get(cenv, `ongakuka/tracks/${key}`);
    if (!obj) return new Response("not found", { status: 404 });
    return new Response(obj.body, {
      headers: {
        "content-type": obj.contentType || "audio/wav",
        "cache-control": "public, max-age=31536000, immutable",
        "access-control-allow-origin": "*",
      },
    });
  });
});
