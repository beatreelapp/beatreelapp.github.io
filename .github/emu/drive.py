# Drives the BeatReel TEST app inside the virtual phone through Chrome DevTools (debug build only).
import json, subprocess, sys, time, urllib.request
import websocket  # pip install websocket-client

def sh(c): return subprocess.run(c, shell=True, capture_output=True, text=True).stdout
def log(*a): print(*a, flush=True)

sock = None
for _ in range(60):
    for line in sh("adb shell cat /proc/net/unix").splitlines():
        if "webview_devtools_remote" in line:
            sock = line.split("@")[-1].strip(); break
    if sock: break
    time.sleep(2)
if not sock: log("NO DEVTOOLS SOCKET"); sys.exit(1)
sh(f"adb forward tcp:9222 localabstract:{sock}")
pages = json.load(urllib.request.urlopen("http://localhost:9222/json"))
page = next(p for p in pages if p.get("type") == "page")
ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=300)
n = [0]
def ev(js, timeout=300):
    n[0] += 1
    ws.send(json.dumps({"id": n[0], "method": "Runtime.evaluate", "params": {"expression": js, "awaitPromise": True, "returnByValue": True, "timeout": timeout * 1000}}))
    while True:
        m = json.loads(ws.recv())
        if m.get("id") == n[0]:
            r = m.get("result", {})
            if "exceptionDetails" in r: return "JS ERROR: " + json.dumps(r["exceptionDetails"])[:400]
            return r.get("result", {}).get("value")
def shot(name): sh(f"adb exec-out screencap -p > shots/{name}.png")

log("APP", ev("APPV+' NAT='+(typeof NAT!=='undefined'&&NAT)"))
t0 = time.time(); last = ""
while time.time() - t0 < 180:
    m = ev("msg.textContent") or ""
    if m != last: log(f"[{int(time.time()-t0)}s] MSG:", m); last = m
    if "Your video is ready" in m or "Lyrics:" in m: break
    time.sleep(3)
shot("2_auto_done")
log("STATE", ev("JSON.stringify({SJ,CS:+CS.toFixed(1),CE:+CE.toFixed(1),lines:L.length,lyrics:L.slice(0,4),gemini:GMOOD?{mood:GMOOD.mood,model:GMOOD.model,why:GMOOD.why,scene:GMOOD.scene}:null,look:LSTY[MLOOK]&&LSTY[MLOOK].n,player:PLON(),title:PLT,artist:PLA})"))
ev("au.pause();CE=Math.min(CE,CS+6);clipUI();true")
log("DOWNLOAD", ev("$('rec').click();'clicked'"))
t0 = time.time(); last = ""
while time.time() - t0 < 600:
    m = ev("msg.textContent") or ""
    if m != last: log(f"[{int(time.time()-t0)}s] MSG:", m[:200]); last = m
    if any(k in m.lower() for k in ["saved", "gallery", "could not", "error"]) and "saving" not in m.lower(): break
    if int(time.time() - t0) % 60 == 0: shot(f"3_export_{int(time.time()-t0)}")
    time.sleep(4)
shot("4_after_save")
log("ERRORS", ev("JSON.stringify((window.ERRS||[]).slice(-5))"))
