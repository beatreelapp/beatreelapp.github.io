// BeatReel API (Cloudflare Worker). No secrets here: Workers AI is reached through the "AI" binding. Deployed by GitHub Actions (api/ folder).
// Routes:
//   POST /transcribe  {audio: base64 WAV, language?}         -> Whisper large-v3-turbo result (text, segments, words)
//   POST /style       {text, lines, looks, colors, emojis}       -> {mood, look, color, e:[emoji index per line], k:[keyword index per line]}
//                     (old shape {text, options} still works)
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
      if (Array.isArray(body.looks)) return Response.json(await look(env, body), { headers: cors });
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

async function transcribe(env, { audio, language, vad, prompt }) {
  if (typeof audio !== "string" || !audio) throw bad("audio (base64) required");
  // vad=false for songs: the voice filter tends to drop singing over music
  const input = { audio, task: "transcribe", vad_filter: vad !== false };
  if (language) input.language = language;
  if (typeof prompt === "string" && prompt.trim()) input.initial_prompt = prompt.slice(0, 200);
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

function strList(v, name, max, len) {
  if (!Array.isArray(v) || !v.length || v.length > max) throw bad(name + " must be a list");
  return v.map((x) => String(x).slice(0, len));
}

// CapCut-style captions: one ready-made look + highlight color, and per line an emoji (by meaning) and the key word.
async function look(env, body) {
  const looks = strList(body.looks, "looks", 12, 220);
  const colors = strList(body.colors, "colors", 12, 20);
  const emojis = strList(body.emojis, "emojis", 80, 40);
  const lines = strList(body.lines, "lines", 80, 80);
  const text = String(body.text || lines.join("\n")).slice(0, 1500);
  const N = lines.length;
  const system =
    "You are the caption designer of BeatReel. The captions must look like premium CapCut auto captions: " +
    "short lines of bold, easy-to-read words inside a clean frame, the spoken word highlighted, and a fitting emoji above some lines. " +
    "Never shaky, glitchy or wobbly. Your job:\n" +
    "1. Read all the words and decide the mood of the video.\n" +
    "2. look: pick the ONE look from the menu that best fits that mood.\n" +
    "3. color: pick the ONE highlight color that fits the mood (yellow or green for energy and money, pink or purple for love and beauty, cyan for tech and calm, red or orange for urgency).\n" +
    "4. e: for every numbered line, the index of the emoji whose meaning matches what the line is about, or -1. " +
    "Use an emoji on roughly half of the lines, only when it really fits the meaning (understand the meaning in any language, Arabic too, not just exact words). " +
    "Never the same emoji on two lines in a row. Prefer -1 for filler lines.\n" +
    "5. k: for every numbered line, the 0-based index of the single most important word (a number, a strong noun, verb or adjective, never a small word like the, a, of, في, من, و), or -1 if none.\n" +
    "Answer with JSON only, no other text, exactly this shape: " +
    '{"mood":"one or two English words","look":0,"color":0,"e":[...],"k":[...]} where e and k each have exactly ' + N + " numbers, one per line, in order.";
  const user =
    "Looks:\n" + looks.map((v, i) => i + " = " + v).join("\n") +
    "\n\nColors: " + colors.map((v, i) => i + "=" + v).join(", ") +
    "\n\nEmojis:\n" + emojis.map((v, i) => i + "=" + v).join(", ") +
    "\n\nLines (" + N + "):\n" + lines.map((v, i) => i + ": " + v).join("\n") +
    "\n\nFull text:\n" + text;
  const out = await env.AI.run(STYLE_MODEL, {
    messages: [
      { role: "system", content: system },
      { role: "user", content: user },
    ],
    max_tokens: 400 + N * 8,
    temperature: 0.3,
  });
  const raw = out && (typeof out.response === "string" ? out.response : JSON.stringify(out.response || ""));
  const m = /\{[\s\S]*\}/.exec(raw || "");
  if (!m) throw new Error("style model gave no JSON");
  let j;
  try { j = JSON.parse(m[0]); } catch { throw new Error("style model gave bad JSON"); }
  const idx = (v, n) => (Number.isInteger(Number(v)) && Number(v) >= 0 && Number(v) < n ? Number(v) : -1);
  const res = { mood: String(j.mood || "").slice(0, 30), look: Math.max(0, idx(j.look, looks.length)), color: Math.max(0, idx(j.color, colors.length)), e: [], k: [] };
  const E = Array.isArray(j.e) ? j.e : [], K = Array.isArray(j.k) ? j.k : [];
  for (let i = 0; i < N; i++) {
    let e = idx(E[i], emojis.length);
    if (i > 0 && e >= 0 && e === res.e[i - 1]) e = -1;
    res.e.push(e);
    res.k.push(idx(K[i], lines[i].split(/\s+/).filter(Boolean).length));
  }
  return res;
}
