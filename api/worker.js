// BeatReel API (Cloudflare Worker). No secrets here: Workers AI is reached through the "AI" binding. Deployed by GitHub Actions (api/ folder).
// Routes:
//   POST /transcribe  {audio: base64 WAV, language?}         -> Whisper large-v3-turbo result (text, segments, words)
//   POST /style       {text, lines, looks, colors, emojis}       -> {mood, look, color, e:[emoji index per line], k:[keyword index per line]}
//                     (old shape {text, options} still works)
//   POST /clips      {job, lines:[[start,end,text],...]}        -> {clips:[{a,b,title}]}  Pro only: best moments (line a to b) + hook title
//   POST /hit         text body: event name                    -> counts anonymous usage (no IP, no personal data stored)
//   POST /me      {did}               -> {coins, max, pro, reset}      (AI coins: 3 free per device per day, Pro = unlimited)
//   POST /job     {did}               -> {job, coins} or 402 no_coins  (one coin = one Auto captions / Auto lyrics run)
//   POST /refund  {job}               -> gives the coin back if the run produced nothing
//   POST /checkout {did}              -> {url}  Stripe Checkout (Managed Payments, 4.99 EUR / month)
//   POST /claim   {did, session}      -> {pro, code}  after paying; code restores Pro on other devices (max 3)
//   POST /restore {did, code}         -> {pro}
//   POST /portal  {did}               -> {url}  Stripe customer portal (cancel, change card)
//   POST /yt      {did, url}          -> {title, len, video:{url}, audio:{url}|null}  YouTube link (RapidAPI, key in secret RAPIDAPI_KEY)
//                                       free: 1 try per device ever, max 2 min; Pro: 5 a day, max 10 min; whole app: ~32 links a month (free plan = 100 units, 3 per link)
//   GET  /ytget?u=URL                 -> streams a googlevideo.com file with CORS (only if the phone cannot fetch it directly)
//   POST /err     {w, m, v, ua}       -> stores an app problem (where, message, app version, phone/browser), last 60 kept, shown on /stats
//   GET  /stats?k=KEY                                          -> private usage page (only the SHA-256 of the key is stored here)
import { DurableObject } from "cloudflare:workers";
const STATS_KEY_SHA256 = "3cfa6b59a11a89f3a83c08f056b077129a9cf05e77458de92e556dfc8b23c3a7";
const YT_HOST = "youtube-media-downloader.p.rapidapi.com", YT_MONTH_CAP = 32, YT_FREE_MONTH_CAP = 15, YT_PRO_DAY = 5, YT_FREE_LEN = 120, YT_PRO_LEN = 600;
const JOB_TR_MAX = 22; // about 10 minutes of audio (30 s parts) per Auto captions run
const FREE_COINS = 3, IP_DAY_CAP = 15, STYLE_FREE_CAP = 60, JOB_TTL = 30 * 60 * 1000, JOB_CALLS = 120, MAX_DEVICES = 3;
const PRICE_EUR_CENTS = 499, TAX_CODE = "txcd_10103001", STRIPE_VERSION = "2026-04-22.dahlia";
const SITE = "https://beatreelapp.github.io";
const ACCT_ROUTES = ["/yt", "/me", "/job", "/refund", "/checkout", "/claim", "/restore", "/portal"];
const EVENTS = ["visit", "dev", "new", "app", "start", "export_music", "export_cap", "apk", "ai_tr", "ai_style", "pro_new", "clip", "ai_clip", "yt", "yt_ok"];
const ALLOWED = ["https://beatreelapp.github.io"];
// Google Fonts the app offers; picks are counted as "f_<id>" so the menu can show the most used first
const FONT_IDS = ["dirt", "glitch", "wet", "bang", "mono", "shade", "mark", "audio", "anton", "bebas", "mont", "pop", "archivo", "russo", "lilita", "right", "nsil", "nmint", "spice", "oshade", "fold", "tint", "ramp", "kaln"];
const STYLE_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const STYLE_KEYS = ["font", "col", "size", "anim", "fx", "frame"];

export default {
  async fetch(req, env, ctx) {
    const origin = req.headers.get("Origin") || "";
    const cors = {
      "Access-Control-Allow-Origin": ALLOWED.includes(origin) ? origin : ALLOWED[0],
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    };
    if (req.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(req.url);
    const route = url.pathname;
    if (route === "/ytget" && req.method === "GET") return ytProxy(req, url, cors, origin);
    if (route === "/fonts" && req.method === "GET") {
      const out = {};
      try { (await stub(env).all()).forEach((r) => { if (String(r.ev).startsWith("f_") && FONT_IDS.includes(r.ev.slice(2))) out[r.ev.slice(2)] = r.n; }); } catch (_) {}
      return Response.json(out, { headers: { ...cors, "Cache-Control": "public, max-age=3600" } });
    }
    if (route === "/stats" && req.method === "GET") return statsPage(env, url.searchParams.get("k") || "");
    if (route === "/err" && req.method === "POST") {
      if (!ALLOWED.includes(origin)) return new Response(null, { status: 204, headers: cors });
      let j = {};
      try { j = JSON.parse((await req.text()).slice(0, 1500)); } catch (_) {}
      const ip = await ipKey(req);
      ctx.waitUntil(stub(env).err(ip, String(j.w || "").slice(0, 40), String(j.m || "").slice(0, 400), String(j.v || "").slice(0, 10), String(j.ua || "").slice(0, 80)).catch(() => {}));
      return new Response(null, { status: 204, headers: cors });
    }
    if (route === "/hit" && req.method === "POST") {
      if (!ALLOWED.includes(origin)) return new Response(null, { status: 204, headers: cors });
      const ev = (await req.text()).trim().slice(0, 20);
      if (EVENTS.includes(ev) || (ev.startsWith("f_") && FONT_IDS.includes(ev.slice(2)))) {
        const cc = ev === "dev" && req.cf && /^[A-Z]{2}$/.test(req.cf.country || "") ? req.cf.country : "";
        ctx.waitUntil(count(env, ev, cc));
      }
      return new Response(null, { status: 204, headers: cors });
    }
    if (ACCT_ROUTES.includes(route) && req.method === "POST") {
      if (!ALLOWED.includes(origin)) return new Response("Forbidden", { status: 403, headers: cors });
      try {
        const body = await req.json().catch(() => ({}));
        const out = await account(env, route, body, req);
        return Response.json(out.body, { status: out.status || 200, headers: cors });
      } catch (e) {
        return Response.json({ error: String((e && e.message) || e) }, { status: (e && e.status) || 500, headers: cors });
      }
    }
    if (req.method !== "POST" || (route !== "/transcribe" && route !== "/style" && route !== "/clips"))
      return new Response("BeatReel API OK", { headers: cors });
    if (!ALLOWED.includes(origin))
      return new Response("Forbidden", { status: 403, headers: cors });
    try {
      const body = await req.json();
      // /transcribe needs a paid-for job (one coin). /style is cheap: allowed with a job, or a few times a day per network without one.
      const gate = await bank(env).gate(route, typeof body.job === "string" ? body.job : "", await ipKey(req));
      if (!gate.ok) return Response.json({ error: gate.error }, { status: 402, headers: cors });
      ctx.waitUntil(count(env, route === "/transcribe" ? "ai_tr" : route === "/clips" ? "ai_clip" : "ai_style", ""));
      if (route === "/clips") return CLIPS_ON ? Response.json(await clips(env, body), { headers: cors }) : Response.json({ error: "soon" }, { status: 503, headers: cors });
      if (route === "/transcribe") {
        const out = await transcribe(env, body);
        if (body.job) ctx.waitUntil(Promise.resolve().then(() => bank(env).worked(body.job)).catch(() => {}));
        return Response.json(out, { headers: cors });
      }
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

// Clipping (Pro): read the timed lines of a long video, pick the 3 to 5 strongest moments and write a short hook title for each.
async function clips(env, body) {
  if (!Array.isArray(body.lines) || body.lines.length < 3 || body.lines.length > 400) throw bad("lines must be a list");
  const ls = body.lines.map((x) => ({ s: Number(x && x[0]) || 0, e: Number(x && x[1]) || 0, t: String((x && x[2]) || "").slice(0, 90) }));
  const N = ls.length, total = ls[N - 1].e - ls[0].s;
  const system =
    "You are the video editor of BeatReel. You cut long talking videos into short viral clips for TikTok, Reels and Shorts. " +
    "You get numbered lines of speech with their start and end time in seconds. Pick the 3 to 5 strongest moments: " +
    "a strong hook at the start, one complete idea, story, tip, joke or emotional moment, and a clean ending (never cut in the middle of a sentence). " +
    "Each clip must be between 15 and 60 seconds long (end time of line b minus start time of line a), and clips must not overlap. Best clip first. " +
    "For each clip write a short hook title (3 to 7 words, max 40 characters) that makes people want to watch, in the SAME language as the speech " +
    "(Arabic speech gets an Arabic title, French gets French). No hashtags, no emojis, no quotes. " +
    'Answer with JSON only, no other text, exactly this shape: {"clips":[{"a":0,"b":7,"title":"..."}]} where a is the first line and b the last line of the clip.';
  const user = "Video length: " + Math.round(total) + " s, " + N + " lines.\n\n" +
    ls.map((x, i) => i + " [" + x.s.toFixed(1) + "-" + x.e.toFixed(1) + "] " + x.t).join("\n");
  const out = await env.AI.run(STYLE_MODEL, {
    messages: [{ role: "system", content: system }, { role: "user", content: user }],
    max_tokens: 500,
    temperature: 0.3,
  });
  const raw = out && (typeof out.response === "string" ? out.response : JSON.stringify(out.response || ""));
  const m = /\{[\s\S]*\}/.exec(raw || "");
  if (!m) throw new Error("clip model gave no JSON");
  let j;
  try { j = JSON.parse(m[0]); } catch { throw new Error("clip model gave bad JSON"); }
  const res = [];
  for (const k of Array.isArray(j.clips) ? j.clips : []) {
    let a = Number(k && k.a), b = Number(k && k.b);
    if (!Number.isInteger(a) || !Number.isInteger(b)) continue;
    a = Math.max(0, Math.min(N - 1, a)); b = Math.max(a, Math.min(N - 1, b));
    while (b < N - 1 && ls[b].e - ls[a].s < 12) b++;          // too short: grow to the next lines
    while (b > a && ls[b].e - ls[a].s > 75) b--;              // too long: shrink
    const d = ls[b].e - ls[a].s;
    if (d < 8 || d > 90) continue;
    if (res.some((r) => !(b < r.a || a > r.b))) continue;     // overlap
    res.push({ a, b, title: String((k && k.title) || "").replace(/["#]/g, "").trim().slice(0, 40) });
    if (res.length >= 5) break;
  }
  return { clips: res };
}

// ---------- Anonymous usage counter (one SQLite Durable Object, free plan) ----------
function stub(env) { return env.STATS.get(env.STATS.idFromName("main")); }
async function count(env, ev, cc) { try { await stub(env).add(ev, cc); } catch (_) {} }

export class Stats extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS c (day TEXT, ev TEXT, n INTEGER, PRIMARY KEY (day, ev))");
    this.sql.exec("CREATE TABLE IF NOT EXISTS errs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, w TEXT, m TEXT, v TEXT, ua TEXT, u TEXT)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS errip (ip TEXT, day TEXT, n INTEGER, PRIMARY KEY (ip, day))");
  }
  // app problems: max 30 per network per day, only the last 60 are kept
  err(ip, w, m, v, ua) {
    const day = new Date().toISOString().slice(0, 10);
    const r = this.sql.exec("SELECT n FROM errip WHERE ip = ? AND day = ?", ip, day).toArray()[0];
    if (r && r.n >= 30) return;
    this.sql.exec("INSERT INTO errip (ip, day, n) VALUES (?, ?, 1) ON CONFLICT (ip, day) DO UPDATE SET n = n + 1", ip, day);
    this.sql.exec("DELETE FROM errip WHERE day < ?", day);
    this.sql.exec("INSERT INTO errs (ts, w, m, v, ua, u) VALUES (?, ?, ?, ?, ?, ?)", Date.now(), w, m, v, ua, ip.slice(0, 4));
    this.sql.exec("DELETE FROM errs WHERE id <= (SELECT MAX(id) FROM errs) - 60");
  }
  errs() { return this.sql.exec("SELECT ts, w, m, v, ua, u FROM errs ORDER BY id DESC LIMIT 50").toArray(); }
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
  ["pro_new", "اشتراكات Pro جديدة"],
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
  const er = await s.errs();
  html += "<h2>المشاكل (آخر 50) · الوقت UTC</h2>" + (er.length ? "<table><tr><th>متى</th><th>أين</th><th>ماذا</th><th>نسخة</th><th>الجهاز</th></tr>" + er.map((e) =>
    "<tr><td>" + esc(new Date(e.ts).toISOString().slice(5, 16).replace("T", " ")) + "</td><td>" + esc(e.w) + "</td><td style=\"text-align:left;direction:ltr;font-size:12px;word-break:break-all\">" + esc(e.m) + "</td><td>" + esc(e.v) + "</td><td style=\"font-size:12px\">" + esc(e.ua) + " <small>#" + esc(e.u) + "</small></td></tr>").join("") + "</table>" : "<p>لا توجد مشاكل مسجّلة 👍</p>");
  const page = '<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>BeatReel stats</title><style>body{font:16px system-ui,sans-serif;margin:16px;background:#0d0d12;color:#eee}h1{font-size:22px}h2{font-size:17px;margin-top:24px;color:#bbb}table{border-collapse:collapse;width:100%}td,th{padding:8px 6px;border-bottom:1px solid #2a2a33;text-align:center}td:first-child,th:first-child{text-align:right}th{color:#999;font-weight:500}small{color:#888}</style></head><body><h1>إحصائيات BeatReel</h1><small>بدون أسماء ولا عناوين IP. الأيام بتوقيت UTC. أعد تحميل الصفحة للتحديث.</small>' + html + "</body></html>";
  return new Response(page, { headers: h });
}

// ---------- AI coins and Pro (one SQLite Durable Object "Bank") ----------
function bank(env) { return env.BANK.get(env.BANK.idFromName("main")); }
function today() { return new Date().toISOString().slice(0, 10); }
function resetIn() { const n = new Date(); return Math.ceil((Date.UTC(n.getUTCFullYear(), n.getUTCMonth(), n.getUTCDate() + 1) - n.getTime()) / 1000); }
async function ipKey(req) { return (await sha(today() + "|" + (req.headers.get("CF-Connecting-IP") || "?") + "|br")).slice(0, 24); }
function okDid(d) { if (typeof d !== "string" || !/^[A-Za-z0-9-]{16,64}$/.test(d)) throw bad("did required"); return d; }

async function stripe(env, method, path, form) {
  if (!env.STRIPE_SECRET_KEY) throw Object.assign(new Error("payments are not set up yet"), { status: 503 });
  const r = await fetch("https://api.stripe.com/v1/" + path, {
    method,
    headers: { Authorization: "Bearer " + env.STRIPE_SECRET_KEY, "Content-Type": "application/x-www-form-urlencoded", "Stripe-Version": STRIPE_VERSION },
    body: form ? new URLSearchParams(form).toString() : undefined,
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error("stripe: " + ((j.error && j.error.message) || r.status)), { status: 502, code: (j.error && j.error.code) || "", http: r.status });
  return j;
}
// Pro rows checked before payments went live (test mode) are checked again at once
const LIVE_SINCE = Date.parse("2026-11-10T20:55:00Z");
// the owner's free Pro code: only its SHA-256 is here, the code itself is kept by the owner
const OWNER_HASH = "acdc6f23621658d6567f27f164aa9718d095b8af3835f40520ef3410969ac918";
const YT_ON = false, CLIPS_ON = false; // YouTube links and Clipping are shown as "Soon" in the app
const ACTIVE = ["active", "trialing", "past_due"];

async function subStatus(env, b, row, force) {
  if (row.sub === "owner") return "active";
  // refresh from Stripe at most every 6 hours (or when forced)
  if (!force && row.checked > LIVE_SINCE && Date.now() - row.checked < 6 * 3600e3) return row.status;
  try {
    const sub = await stripe(env, "GET", "subscriptions/" + row.sub);
    await b.setStatus(row.code, sub.status, sub.customer || row.cust);
    return sub.status;
  } catch (e) {
    // a subscription made with the test key does not exist for the live key: treat it as ended
    if (e && (e.code === "resource_missing" || e.http === 404)) { await b.setStatus(row.code, "canceled", row.cust); return "canceled"; }
    return row.status;
  }
}
const stripeMode = (env) => (/_live_/.test(String(env.STRIPE_SECRET_KEY || "")) ? "live" : "test");

// ---------- YouTube link -> video file (RapidAPI "YouTube Media Downloader", DataFanatic) ----------
function ytId(u) {
  u = String(u || "").trim();
  if (/^[A-Za-z0-9_-]{11}$/.test(u)) return u;
  const m = /(?:youtu\.be\/|[?&]v=|\/shorts\/|\/embed\/|\/live\/|\/v\/)([A-Za-z0-9_-]{11})/.exec(u);
  let host = "";
  try { host = new URL(/^https?:\/\//i.test(u) ? u : "https://" + u).hostname.toLowerCase(); } catch { host = ""; }
  return m && /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)$/.test(host) ? m[1] : "";
}
const num = (v) => { const n = parseFloat(v); return isFinite(n) ? n : 0; };
function fmtList(x) { return Array.isArray(x) ? x : x && Array.isArray(x.items) ? x.items : []; }
function pickYt(j) {
  const vs = fmtList(j.videos).filter((v) => v && typeof v.url === "string" && /^https:/.test(v.url));
  const as = fmtList(j.audios).filter((v) => v && typeof v.url === "string" && /^https:/.test(v.url));
  const h = (v) => num(v.height) || num(String(v.quality || v.qualityLabel || v.label || "").replace(/[^0-9]/g, ""));
  const mt = (v) => String(v.mimeType || v.mime || v.extension || v.ext || "");
  const bad = (v) => /vp9|vp09|av01|av1|webm/i.test(mt(v) + " " + String(v.codec || v.codecs || ""));
  const avc = (v) => /avc1/i.test(mt(v) + " " + String(v.codec || v.codecs || ""));
  const mp4 = (v) => /mp4/i.test(mt(v)) && !bad(v);
  const muxed = vs.filter((v) => mp4(v) && v.hasAudio === true && h(v) <= 1080).sort((a, b) => h(b) - h(a));
  const only = vs.filter((v) => mp4(v) && v.hasAudio !== true && h(v) <= 720).sort((a, b) => (avc(b) - avc(a)) || (h(b) - h(a)));
  // YouTube can carry several sound tracks (dubbed / AI-translated voices): keep the ORIGINAL voice
  const meta = (v) => { try { return JSON.stringify(Object.fromEntries(Object.entries(v).filter(([k]) => k !== "url"))).toLowerCase(); } catch { return ""; } };
  const orig = (v) => /original|"isdefault":true|"default":true|"is_default":true/.test(meta(v));
  const dub = (v) => /dub|translat|"isautodubbed":true|secondary/.test(meta(v)) && !orig(v);
  const lang = (v) => String(v.language || v.lang || v.languageCode || v.audioLanguage || (v.audioTrack && (v.audioTrack.id || v.audioTrack.displayName)) || "");
  let aud = as.filter((v) => /mp4|m4a|aac/i.test(mt(v)) && !/opus|webm/i.test(mt(v)) && !dub(v))
    .sort((a, b) => (orig(b) - orig(a)) || (num(b.bitrate) - num(a.bitrate)));
  const many = new Set(aud.map(lang)).size > 1;
  if (many && !aud.some(orig)) aud = []; // several voices and none marked original: use the file that has its own sound
  // Only the single file with picture AND sound: YouTube always puts the ORIGINAL voice in it
  // (the separate sound files can be AI-dubbed in other languages, with no way to tell which one is original).
  const c = [];
  muxed.slice(0, 2).forEach((v) => c.push({ url: v.url, h: h(v), mime: mt(v).slice(0, 80), sep: false }));
  if (!c.length) return null;
  const strip = (v) => { const o = {}; Object.keys(v || {}).slice(0, 14).forEach((k) => { if (k !== "url") o[k] = typeof v[k] === "object" ? "{}" : String(v[k]).slice(0, 40); }); return o; };
  return { cands: c, audio: null, video: c[0], dbg: {
    videos: vs.slice(0, 14).map((v) => [h(v), mt(v).slice(0, 45), v.hasAudio === true ? "+sound" : "-", bad(v) ? "skip" : ""].join(" ")),
    audios: as.slice(0, 10).map((v) => meta(v).replace(/[{}"]/g, "").slice(0, 110)),
  } };
}
async function yt(env, b, did, pro, body, req) {
  if (!YT_ON) return { status: 503, body: { error: "soon" } };
  if (!env.RAPIDAPI_KEY) return { status: 503, body: { error: "not_ready" } };
  const id = ytId(body.url);
  if (!id) return { status: 400, body: { error: "bad_link" } };
  const ip = await ipKey(req);
  const can = await b.ytCan(did, pro, ip);
  if (!can.ok) return { status: 402, body: { error: can.error, pro, reset: resetIn() } };
  await b.ytUse(did, pro, ip);
  let r, j;
  try {
    r = await fetch("https://" + YT_HOST + "/v2/video/details?videoId=" + id + "&urlAccess=normal&videos=true&audios=true&subtitles=false&related=false",
      { headers: { "x-rapidapi-key": env.RAPIDAPI_KEY, "x-rapidapi-host": YT_HOST } });
    j = await r.json().catch(() => ({}));
  } catch (e) { await b.ytUndo(did, pro, ip, true); return { status: 502, body: { error: "service_down" } }; }
  if (!r.ok || (j.errorId && j.errorId !== "Success")) {
    await b.ytUndo(did, pro, ip, r.status === 429 || r.status === 401 || r.status === 403);
    return { status: 502, body: { error: r.status === 429 ? "month_full" : "service", detail: String(j.message || j.errorId || r.status).slice(0, 120) } };
  }
  const len = num(j.lengthSeconds) || num(j.duration) || 0, max = pro ? YT_PRO_LEN : YT_FREE_LEN;
  if (len > max + 1) { await b.ytUndo(did, pro, ip, false); return { status: 400, body: { error: "too_long", len, max, pro } }; }
  const f = pickYt(j);
  if (!f) {
    await b.ytUndo(did, pro, ip, false);
    return { status: 502, body: { error: "no_format", detail: "keys:" + Object.keys(j).slice(0, 15).join(",") + " v:" + fmtList(j.videos).length + " a:" + fmtList(j.audios).length } };
  }
  return { body: { title: String(j.title || "").slice(0, 100), len, video: f.video, audio: f.audio, cands: f.cands, dbg: f.dbg, pro } };
}
async function ytProxy(req, url, cors, origin) {
  if (!ALLOWED.includes(origin)) return new Response("Forbidden", { status: 403, headers: cors });
  let u;
  try { u = new URL(url.searchParams.get("u") || ""); } catch { return new Response("bad", { status: 400, headers: cors }); }
  if (u.protocol !== "https:" || !/(^|\.)googlevideo\.com$/.test(u.hostname)) return new Response("bad", { status: 400, headers: cors });
  const h = {};
  const range = req.headers.get("Range");
  if (range) h.Range = range;
  const r = await fetch(u.toString(), { headers: h });
  const out = new Headers(cors);
  ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"].forEach((k) => { const v = r.headers.get(k); if (v) out.set(k, v); });
  out.set("Access-Control-Expose-Headers", "Content-Length, Content-Range");
  return new Response(r.body, { status: r.status, headers: out });
}

async function account(env, route, body, req) {
  const b = bank(env);
  if (route === "/refund") { await b.refund(String(body.job || "")); return { body: { ok: true } }; }
  const did = okDid(body.did);
  const proRow = async (force) => {
    const row = await b.proOf(did);
    if (!row) return null;
    const st = await subStatus(env, b, row, force);
    return ACTIVE.includes(st) ? row : null;
  };
  if (route === "/yt") return yt(env, b, did, !!(await proRow(false)), body, req);
  if (route === "/me") {
    const pro = await proRow(false);
    return { body: { pro: !!pro, code: pro ? pro.code : "", coins: pro ? FREE_COINS : await b.coins(did, FREE_COINS), max: FREE_COINS, reset: resetIn() } };
  }
  if (route === "/job") {
    const pro = await proRow(false);
    const r = await b.newJob(did, !!pro, await ipKey(req), FREE_COINS);
    if (!r.ok) return { status: 402, body: { error: "no_coins", coins: 0, reset: resetIn() } };
    return { body: { job: r.job, coins: pro ? FREE_COINS : r.coins, pro: !!pro, reset: resetIn() } };
  }
  if (route === "/checkout") {
    const pk = "price_" + stripeMode(env);
    let price = await b.getKV(pk);
    if (!price) {
      const prod = await stripe(env, "POST", "products", {
        name: "BeatReel Pro",
        description: "Unlimited AI captions and lyrics, no watermark, Full HD, no clip limit.",
        tax_code: TAX_CODE,
        "default_price_data[currency]": "eur",
        "default_price_data[unit_amount]": String(PRICE_EUR_CENTS),
        "default_price_data[recurring][interval]": "month",
      });
      price = prod.default_price;
      await b.setKV(pk, price);
    }
    const ses = await stripe(env, "POST", "checkout/sessions", {
      mode: "subscription",
      "line_items[0][price]": price,
      "line_items[0][quantity]": "1",
      "managed_payments[enabled]": "true",
      client_reference_id: did,
      "metadata[did]": did,
      "subscription_data[metadata][did]": did,
      success_url: SITE + "/?paid={CHECKOUT_SESSION_ID}",
      cancel_url: SITE + "/?paid=cancel",
    });
    return { body: { url: ses.url } };
  }
  if (route === "/claim") {
    const id = String(body.session || "");
    if (!/^cs_[A-Za-z0-9_]+$/.test(id)) throw bad("session required");
    const ses = await stripe(env, "GET", "checkout/sessions/" + id);
    if (ses.status !== "complete" || !ses.subscription) return { status: 402, body: { error: "not_paid" } };
    const subId = typeof ses.subscription === "string" ? ses.subscription : ses.subscription.id;
    const sub = await stripe(env, "GET", "subscriptions/" + subId);
    const code = await b.claim(subId, sub.customer || ses.customer || "", sub.status, did, MAX_DEVICES);
    return { body: { pro: ACTIVE.includes(sub.status), code } };
  }
  if (route === "/restore") {
    const code = String(body.code || "").trim().toUpperCase();
    if (!/^BR-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code)) return { status: 404, body: { error: "bad_code" } };
    let row = await b.byCode(code);
    if (!row && (await sha(code)) === OWNER_HASH) row = await b.owner(code);
    if (!row) return { status: 404, body: { error: "bad_code" } };
    const st = await subStatus(env, b, row, true);
    if (!ACTIVE.includes(st)) return { status: 402, body: { error: "not_active" } };
    const r = await b.link(code, did, MAX_DEVICES);
    if (!r.ok) return { status: 409, body: { error: "device_limit" } };
    return { body: { pro: true, code } };
  }
  if (route === "/portal") {
    const row = await b.proOf(did);
    if (!row || !row.cust) return { status: 404, body: { error: "no_subscription" } };
    const p = await stripe(env, "POST", "billing_portal/sessions", { customer: row.cust, return_url: SITE + "/" });
    return { body: { url: p.url } };
  }
  throw bad("unknown route");
}

function rndCode() {
  const A = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789", u = crypto.getRandomValues(new Uint8Array(12));
  let s = "BR";
  for (let i = 0; i < 12; i++) { if (i % 4 === 0) s += "-"; s += A[u[i] % A.length]; }
  return s;
}

export class Bank extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    const q = ctx.storage.sql;
    this.sql = q;
    q.exec("CREATE TABLE IF NOT EXISTS used (did TEXT, day TEXT, n INTEGER, PRIMARY KEY (did, day))");
    q.exec("CREATE TABLE IF NOT EXISTS ipuse (ip TEXT, day TEXT, n INTEGER, s INTEGER DEFAULT 0, PRIMARY KEY (ip, day))");
    q.exec("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, did TEXT, day TEXT, exp INTEGER, calls INTEGER, paid INTEGER, tr INTEGER DEFAULT 0, ok INTEGER DEFAULT 0)");
    q.exec("CREATE TABLE IF NOT EXISTS pro (code TEXT PRIMARY KEY, sub TEXT UNIQUE, cust TEXT, status TEXT, checked INTEGER)");
    q.exec("CREATE TABLE IF NOT EXISTS link (did TEXT PRIMARY KEY, code TEXT)");
    q.exec("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)");
    q.exec("CREATE TABLE IF NOT EXISTS yt (did TEXT, day TEXT, n INTEGER, PRIMARY KEY (did, day))");
    q.exec("CREATE TABLE IF NOT EXISTS ytfree (k TEXT PRIMARY KEY)");
    q.exec("CREATE TABLE IF NOT EXISTS ytm (month TEXT PRIMARY KEY, n INTEGER, f INTEGER DEFAULT 0)");
  }
  ytCan(did, pro, ip) {
    const mo = today().slice(0, 7), m = this.one("SELECT n, f FROM ytm WHERE month = ?", mo) || { n: 0, f: 0 };
    if (m.n >= YT_MONTH_CAP) return { ok: false, error: "month_full" };
    if (pro) {
      const r = this.one("SELECT n FROM yt WHERE did = ? AND day = ?", did, today());
      return r && r.n >= YT_PRO_DAY ? { ok: false, error: "day_limit" } : { ok: true };
    }
    if (this.one("SELECT k FROM ytfree WHERE k = ?", "d:" + did) || this.one("SELECT k FROM ytfree WHERE k = ?", "i:" + ip)) return { ok: false, error: "free_used" };
    if (m.f >= YT_FREE_MONTH_CAP) return { ok: false, error: "free_full" };
    return { ok: true };
  }
  ytUse(did, pro, ip) {
    const mo = today().slice(0, 7);
    this.sql.exec("INSERT INTO ytm (month, n, f) VALUES (?, 1, ?) ON CONFLICT (month) DO UPDATE SET n = n + 1, f = f + excluded.f", mo, pro ? 0 : 1);
    if (pro) this.sql.exec("INSERT INTO yt (did, day, n) VALUES (?, ?, 1) ON CONFLICT (did, day) DO UPDATE SET n = n + 1", did, today());
    else { this.sql.exec("INSERT OR IGNORE INTO ytfree (k) VALUES (?)", "d:" + did); this.sql.exec("INSERT OR IGNORE INTO ytfree (k) VALUES (?)", "i:" + ip); }
  }
  // give the user's try back; give the month back too when RapidAPI did not count the call
  ytUndo(did, pro, ip, month) {
    if (month) this.sql.exec("UPDATE ytm SET n = MAX(0, n - 1), f = MAX(0, f - ?) WHERE month = ?", pro ? 0 : 1, today().slice(0, 7));
    if (pro) this.sql.exec("UPDATE yt SET n = MAX(0, n - 1) WHERE did = ? AND day = ?", did, today());
    else { this.sql.exec("DELETE FROM ytfree WHERE k = ?", "d:" + did); this.sql.exec("DELETE FROM ytfree WHERE k = ?", "i:" + ip); }
  }
  one(q, ...a) { const r = this.sql.exec(q, ...a).toArray(); return r[0] || null; }
  clean() {
    const old = new Date(Date.now() - 3 * 864e5).toISOString().slice(0, 10);
    this.sql.exec("DELETE FROM used WHERE day < ?", old);
    this.sql.exec("DELETE FROM ipuse WHERE day < ?", old);
    this.sql.exec("DELETE FROM jobs WHERE exp < ?", Date.now() - 864e5);
  }
  coins(did, max) { const r = this.one("SELECT n FROM used WHERE did = ? AND day = ?", did, today()); return Math.max(0, max - (r ? r.n : 0)); }
  newJob(did, pro, ip, max) {
    const day = today();
    if (Math.random() < 0.05) this.clean();
    let left = max;
    if (!pro) {
      left = this.coins(did, max);
      const ipr = this.one("SELECT n FROM ipuse WHERE ip = ? AND day = ?", ip, day);
      if (left <= 0 || (ipr && ipr.n >= IP_DAY_CAP)) return { ok: false };
      this.sql.exec("INSERT INTO used (did, day, n) VALUES (?, ?, 1) ON CONFLICT (did, day) DO UPDATE SET n = n + 1", did, day);
      this.sql.exec("INSERT INTO ipuse (ip, day, n) VALUES (?, ?, 1) ON CONFLICT (ip, day) DO UPDATE SET n = n + 1", ip, day);
      left -= 1;
    }
    const id = crypto.randomUUID();
    this.sql.exec("INSERT INTO jobs (id, did, day, exp, calls, paid) VALUES (?, ?, ?, ?, 0, ?)", id, did, day, Date.now() + JOB_TTL, pro ? 0 : 1);
    return { ok: true, job: id, coins: left };
  }
  gate(route, job, ip) {
    if (job) {
      const j = this.one("SELECT * FROM jobs WHERE id = ?", job);
      if (j && j.exp > Date.now() && j.calls < JOB_CALLS) {
        if (route === "/clips" && j.paid) return { ok: false, error: "pro_only" };
        if (route === "/transcribe" && (j.tr || 0) >= JOB_TR_MAX) return { ok: false, error: "too_long" };
        this.sql.exec("UPDATE jobs SET calls = calls + 1, tr = tr + ? WHERE id = ?", route === "/transcribe" ? 1 : 0, job);
        return { ok: true };
      }
      if (route === "/transcribe" || route === "/clips") return { ok: false, error: "job_expired" };
    }
    if (route === "/transcribe") return { ok: false, error: "no_coins" };
    if (route === "/clips") return { ok: false, error: "pro_only" };
    const day = today(), r = this.one("SELECT s FROM ipuse WHERE ip = ? AND day = ?", ip, day);
    if (r && r.s >= STYLE_FREE_CAP) return { ok: false, error: "no_coins" };
    this.sql.exec("INSERT INTO ipuse (ip, day, n, s) VALUES (?, ?, 0, 1) ON CONFLICT (ip, day) DO UPDATE SET s = s + 1", ip, day);
    return { ok: true };
  }
  refund(job) {
    const j = this.one("SELECT * FROM jobs WHERE id = ?", job);
    if (!j || !j.paid || j.ok > 0) return; // only when the online AI produced nothing
    this.sql.exec("UPDATE jobs SET paid = 0, exp = 0 WHERE id = ?", job);
    this.sql.exec("UPDATE used SET n = MAX(0, n - 1) WHERE did = ? AND day = ?", j.did, j.day);
  }
  worked(job) { this.sql.exec("UPDATE jobs SET ok = ok + 1 WHERE id = ?", job); }
  proOf(did) { return this.one("SELECT pro.* FROM link JOIN pro ON pro.code = link.code WHERE link.did = ?", did); }
  byCode(code) { return this.one("SELECT * FROM pro WHERE code = ?", code); }
  setStatus(code, status, cust) { this.sql.exec("UPDATE pro SET status = ?, cust = ?, checked = ? WHERE code = ?", status, cust || "", Date.now(), code); }
  owner(code) { this.sql.exec("INSERT OR IGNORE INTO pro (code, sub, cust, status, checked) VALUES (?, 'owner', '', 'active', ?)", code, Date.now()); return this.one("SELECT * FROM pro WHERE code = ?", code); }
  getKV(k) { const r = this.one("SELECT v FROM kv WHERE k = ?", k); return r ? r.v : ""; }
  setKV(k, v) { this.sql.exec("INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v", k, v); }
  claim(sub, cust, status, did, maxDev) {
    let row = this.one("SELECT * FROM pro WHERE sub = ?", sub);
    if (!row) {
      const code = rndCode();
      this.sql.exec("INSERT INTO pro (code, sub, cust, status, checked) VALUES (?, ?, ?, ?, ?)", code, sub, cust, status, Date.now());
      row = { code };
    } else this.setStatus(row.code, status, cust);
    this.link(row.code, did, maxDev + 1); // the paying device always gets in
    return row.code;
  }
  link(code, did, maxDev) {
    const cur = this.one("SELECT code FROM link WHERE did = ?", did);
    if (cur && cur.code === code) return { ok: true };
    const n = this.one("SELECT COUNT(*) AS n FROM link WHERE code = ?", code).n;
    if (n >= maxDev) return { ok: false };
    this.sql.exec("INSERT INTO link (did, code) VALUES (?, ?) ON CONFLICT (did) DO UPDATE SET code = excluded.code", did, code);
    return { ok: true };
  }
}
