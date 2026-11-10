// BeatReel API (Cloudflare Worker). No secrets here: Workers AI is reached through the "AI" binding. Deployed by GitHub Actions.
const ALLOWED = ["https://beatreelapp.github.io"];

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
    if (req.method !== "POST" || url.pathname !== "/transcribe")
      return new Response("BeatReel API OK", { headers: cors });
    if (!ALLOWED.includes(origin))
      return new Response("Forbidden", { status: 403, headers: cors });
    try {
      const { audio, language } = await req.json();
      if (typeof audio !== "string" || !audio)
        return Response.json({ error: "audio (base64) required" }, { status: 400, headers: cors });
      const input = { audio, task: "transcribe", vad_filter: true };
      if (language) input.language = language;
      const out = await env.AI.run("@cf/openai/whisper-large-v3-turbo", input);
      return Response.json(out, { headers: cors });
    } catch (e) {
      return Response.json({ error: String(e) }, { status: 500, headers: cors });
    }
  },
};
