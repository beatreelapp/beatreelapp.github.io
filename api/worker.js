// BeatReel API (Cloudflare Worker). No secrets here: Workers AI is reached through the "AI" binding. Deployed by GitHub Actions (api/ folder).
// Routes:
//   POST /transcribe  {audio: base64 WAV, language?}         -> Whisper large-v3-turbo result (text, segments, words)
//   POST /style       {text, options: {font:[...], ...}}      -> {font, col, size, anim, fx, frame, mood} (indexes into the given lists)
const ALLOWED = ["https://beatreelapp.github.io"];
const STYLE_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const STYLE_KEYS = ["font", "col", "size", "anim", "fx", "frame"];

export default {
  async fetch(req, env) {
    const origin = req.headers.get("Origin") || "";
    const cors = {
      "Access-Control-Allow-Origin": ALLOWED.includes(origin) ? origin : ALLOWED[0],
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    };
    if (req.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(req.url);
    const route = url.pathname;
    if (req.method !== "POST" || (route !== "/transcribe" && route !== "/style"))
      return new Response("BeatReel API OK", { headers: cors });
    if (!ALLOWED.includes(origin))
      return new Response("Forbidden", { status: 403, headers: cors });
    try {
      const body = await req.json();
      if (route === "/transcribe") return Response.json(await transcribe(env, body), { headers: cors });
      return Response.json(await style(env, body), { headers: cors });
    } catch (e) {
      const status = e && e.status ? e.status : 500;
      return Response.json({ error: String((e && e.message) || e) }, { status, headers: cors });
    }
  },
};

function bad(message) {
  const e = new Error(message);
  e.status = 400;
  return e;
}

async function transcribe(env, { audio, language }) {
  if (typeof audio !== "string" || !audio) throw bad("audio (base64) required");
  const input = { audio, task: "transcribe", vad_filter: true };
  if (language) input.language = language;
  return env.AI.run("@cf/openai/whisper-large-v3-turbo", input);
}

async function style(env, { text, options }) {
  if (typeof text !== "string" || !text.trim()) throw bad("text required");
  if (!options || typeof options !== "object") throw bad("options required");
  const lists = {};
  for (const k of STYLE_KEYS) {
    const arr = options[k];
    if (!Array.isArray(arr) || !arr.length || arr.length > 40) throw bad("options." + k + " must be a list");
    lists[k] = arr.map((v) => String(v).slice(0, 40));
  }
  const menu = STYLE_KEYS.map((k) => k + ": " + lists[k].map((v, i) => i + "=" + v).join(", ")).join("\n");
  const system =
    "You are the art director of BeatReel, an app that puts animated captions on short vertical videos. " +
    "Read the spoken words, decide the mood (for example energetic, calm, funny, sad, romantic, motivational, serious, mysterious) " +
    "and pick ONE option for each style setting so the captions match that mood and are easy to read on a phone. " +
    "Answer with JSON only, no other text, in exactly this shape: " +
    '{"mood":"one or two words","font":0,"col":0,"size":0,"anim":0,"fx":0,"frame":0}. Each number is an index from the menu.';
  const user = "Style menu (index=name):\n" + menu + "\n\nSpoken words:\n" + text.slice(0, 1500);
  const out = await env.AI.run(STYLE_MODEL, {
    messages: [
      { role: "system", content: system },
      { role: "user", content: user },
    ],
    max_tokens: 120,
    temperature: 0.4,
  });
  const raw = out && (typeof out.response === "string" ? out.response : JSON.stringify(out.response || ""));
  const m = /\{[\s\S]*\}/.exec(raw || "");
  if (!m) throw new Error("style model gave no JSON");
  let j;
  try { j = JSON.parse(m[0]); } catch { throw new Error("style model gave bad JSON"); }
  const res = { mood: String(j.mood || "").slice(0, 30) };
  for (const k of STYLE_KEYS) {
    const n = Number(j[k]);
    if (Number.isInteger(n) && n >= 0 && n < lists[k].length) res[k] = n;
  }
  return res;
}
