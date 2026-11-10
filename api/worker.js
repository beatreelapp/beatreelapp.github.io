// BeatReel API (Cloudflare Worker). No secrets here: Workers AI is reached through the "AI" binding. Deployed by GitHub Actions (api/ folder).
// Routes:
//   POST /transcribe  {audio: base64 WAV, language?}         -> Whisper large-v3-turbo result (text, segments, words)
//   POST /style       {text, lines, looks, colors, emojis}       -> {mood, look, color, e:[emoji index per line], k:[keyword index per line]}
//                     (old shape {text, options} still works)
//   POST /hit         text body: event name                    -> counts anonymous usage (no IP, no personal data stored)
//   GET  /stats?k=KEY                                          -> private usage page (only the SHA-256 of the key is stored here)
import { DurableObject } from "cloudflare:workers";
const STATS_KEY_SHA256 = "3cfa6b59a11a89f3a83c08f056b077129a9cf05e77458de92e556dfc8b23c3a7";
const EVENTS = ["visit", "dev", "new", "app", "start", "export_music", "export_cap", "apk", "ai_tr", "ai_style"];
const ALLOWED = ["https://beatreelapp.github.io"];
const STYLE_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const STYLE_KEYS = ["font", "col", "size", "anim", "fx", "frame"];

export default {
  async fetch(req, env, ctx) {
    const origin = req.headers.get("Origin") || "";
    const cors = {
      "Access-Control-Allow-Origin": ALLOWED.includes(origin) ? origin : ALLOWED[0],
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    };
    if (req.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(req.url);
    const route = url.pathname;
    if (route === "/stats" && req.method === "GET") return statsPage(env, url.searchParams.get("k") || "");
    if (route === "/hit" && req.method === "POST") {
      if (!ALLOWED.includes(origin)) return new Response(null, { status: 204, headers: cors });
      const ev = (await req.text()).trim().slice(0, 20);
      if (EVENTS.includes(ev)) {
        const cc = ev === "dev" && req.cf && /^[A-Z]{2}$/.test(req.cf.country || "") ? req.cf.country : "";
        ctx.waitUntil(count(env, ev, cc));
      }
      return new Response(null, { status: 204, headers: cors });
    }
    if (req.method !== "POST" || (route !== "/transcribe" && route !== "/style"))
      return new Response("BeatReel API OK", { headers: cors });
    if (!ALLOWED.includes(origin))
      return new Response("Forbidden", { status: 403, headers: cors });
    try {
      const body = await req.json();
      ctx.waitUntil(count(env, route === "/transcribe" ? "ai_tr" : "ai_style", ""));
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

// ---------- Anonymous usage counter (one SQLite Durable Object, free plan) ----------
function stub(env) { return env.STATS.get(env.STATS.idFromName("main")); }
async function count(env, ev, cc) { try { await stub(env).add(ev, cc); } catch (_) {} }

export class Stats extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS c (day TEXT, ev TEXT, n INTEGER, PRIMARY KEY (day, ev))");
  }
  add(ev, cc) {
    const day = new Date().toISOString().slice(0, 10);
    const up = "INSERT INTO c (day, ev, n) VALUES (?, ?, 1) ON CONFLICT (day, ev) DO UPDATE SET n = n + 1 WHERE n < 1000000";
    this.sql.exec(up, day, ev);
    if (cc) this.sql.exec(up, day, "cc:" + cc);
  }
  rows(since) { return this.sql.exec("SELECT day, ev, n FROM c WHERE day >= ? ORDER BY day DESC", since).toArray(); }
  all() { return this.sql.exec("SELECT ev, SUM(n) AS n FROM c GROUP BY ev").toArray(); }
}

async function sha(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

const LABELS = [
  ["dev", "زوار (جهاز مختلف في اليوم)"],
  ["new", "زوار جدد لأول مرة"],
  ["app", "فتحوا من تطبيق APK"],
  ["visit", "مرات فتح الموقع"],
  ["start", "ضغطوا Start"],
  ["export_music", "فيديو موسيقى محفوظ"],
  ["export_cap", "فيديو ترجمة محفوظ"],
  ["apk", "ضغطات تحميل APK"],
  ["ai_style", "استعمال الذكاء (ستايل)"],
  ["ai_tr", "طلبات تفريغ الصوت"],
];

async function statsPage(env, k) {
  const h = { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Robots-Tag": "noindex" };
  if ((await sha(k)) !== STATS_KEY_SHA256) return new Response("Not found", { status: 404, headers: h });
  const d = (n) => new Date(Date.now() - n * 864e5).toISOString().slice(0, 10);
  const s = stub(env);
  const rows = await s.rows(d(29));
  const tot = Object.fromEntries((await s.all()).map((r) => [r.ev, r.n]));
  const sum = (ev, from) => rows.filter((r) => r.ev === ev && r.day >= from).reduce((a, r) => a + r.n, 0);
  const esc = (x) => String(x).replace(/[<>&"]/g, (c) => "&#" + c.charCodeAt(0) + ";");
  let html = '<table><tr><th></th><th>اليوم</th><th>7 أيام</th><th>30 يوم</th><th>الكل</th></tr>';
  for (const [ev, name] of LABELS)
    html += "<tr><td>" + name + "</td><td>" + sum(ev, d(0)) + "</td><td>" + sum(ev, d(6)) + "</td><td>" + sum(ev, d(29)) + "</td><td>" + (tot[ev] || 0) + "</td></tr>";
  html += "</table><h2>كل يوم (زوار / فيديوهات محفوظة)</h2><table><tr><th>اليوم</th><th>زوار</th><th>جدد</th><th>فيديوهات</th></tr>";
  for (let i = 0; i < 14; i++) {
    const day = d(i), g = (ev) => rows.filter((r) => r.day === day && r.ev === ev).reduce((a, r) => a + r.n, 0);
    html += "<tr><td>" + day + "</td><td>" + g("dev") + "</td><td>" + g("new") + "</td><td>" + (g("export_music") + g("export_cap")) + "</td></tr>";
  }
  const cc = {};
  for (const r of rows) if (r.ev.startsWith("cc:")) cc[r.ev.slice(3)] = (cc[r.ev.slice(3)] || 0) + r.n;
  const top = Object.entries(cc).sort((a, b) => b[1] - a[1]).slice(0, 15);
  html += "</table><h2>الدول (30 يوم)</h2><table>" + (top.length ? top.map(([c, n]) => "<tr><td>" + esc(c) + "</td><td>" + n + "</td></tr>").join("") : "<tr><td>لا شيء بعد</td></tr>") + "</table>";
  const page = '<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>BeatReel stats</title><style>body{font:16px system-ui,sans-serif;margin:16px;background:#0d0d12;color:#eee}h1{font-size:22px}h2{font-size:17px;margin-top:24px;color:#bbb}table{border-collapse:collapse;width:100%}td,th{padding:8px 6px;border-bottom:1px solid #2a2a33;text-align:center}td:first-child,th:first-child{text-align:right}th{color:#999;font-weight:500}small{color:#888}</style></head><body><h1>إحصائيات BeatReel</h1><small>بدون أسماء ولا عناوين IP. الأيام بتوقيت UTC. أعد تحميل الصفحة للتحديث.</small>' + html + "</body></html>";
  return new Response(page, { headers: h });
}
