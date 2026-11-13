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
ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=300, suppress_origin=True)
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

# Android 9 and older ask for storage when saving: tap "Allow" like the user does
import threading, re as _re
def _allow():
    while True:
        try:
            x = sh("adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml")
            m = _re.search(r'text="(ALLOW|Allow|AUTORISER)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
            if m:
                a, b, c, d2 = map(int, m.groups()[1:])
                sh(f"adb shell input tap {(a+c)//2} {(b+d2)//2}"); log("PERMISSION DIALOG: tapped", m.group(1))
        except Exception as e: pass
        time.sleep(3)
threading.Thread(target=_allow, daemon=True).start()
log("APP", ev("APPV+' NAT='+(typeof NAT!=='undefined'&&NAT)"))
# native start screen: welcome on first launch, then home; nothing may play or open by itself
time.sleep(2); shot("0b_welcome")
log("STILL", ev("JSON.stringify({auPaused:au.paused,bgv:!!$('bgv').src,curFile:!!curFile,home:getComputedStyle(document.getElementById('home')).display,mcs:getComputedStyle(document.querySelector('.mcs')).display})"))
def tap_text(words):
    x = sh("adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml")
    import re as _r
    for w in words:
        m = _r.search(r'text="' + w + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            a1, b1, c1, d1 = map(int, m.groups()); sh(f"adb shell input tap {(a1+c1)//2} {(b1+d1)//2}"); return w
    return None
log("WELCOME NEXT", tap_text(["Next"])); time.sleep(1); shot("0c_welcome2")
log("WELCOME SKIP", tap_text(["Skip"])); time.sleep(2); shot("0d_home")
log("CLIP SOON", tap_text(["Clipping"])); time.sleep(1); shot("0e_soon")
log("HIDE HOME", ev("BRN.hideHome().then(()=>'hidden')")); time.sleep(1)
# 1) share the song from "another app" (Android share sheet) and see what the app receives
ev("window.__SH=null;try{BRN.addListener('shared',d=>{window.__SH=d})}catch(e){window.__SH={err:String(e)}};true")
import os
sid = os.environ.get("SONG_ID", "")
log("SHARE INTENT", sh(f"adb shell am start -a android.intent.action.SEND -t audio/mpeg --eu android.intent.extra.STREAM content://media/external/audio/media/{sid} --grant-read-uri-permission -n io.github.beatreelapp.twa.test/io.github.beatreelapp.twa.MainActivity"))
time.sleep(10)
got = ev("JSON.stringify(window.__SH)")
log("SHARE RECEIVED", got, "| curFile:", ev("curFile?curFile.name+' '+curFile.size:'none'"))
shot("1b_after_share")
if ev("!!curFile") is not True:
    log("SHARE DID NOT LOAD A SONG -> loading a generated song with JS instead")
    log(ev("""(async()=>{const sr=22050,d=60,oc=new OfflineAudioContext(1,sr*d,sr);const ch=[[220,277,330],[196,247,294],[262,330,392],[349,440,523]];
for(let i=0;i<d/2;i++){const sec=(i>=8&&i<16)||(i>=24)?2:0;const set=ch[sec+(i%2)];set.forEach(f=>{const o=oc.createOscillator(),g=oc.createGain();o.frequency.value=f;g.gain.value=(sec?0.25:0.1);o.connect(g);g.connect(oc.destination);o.start(i*2);o.stop(i*2+2)})}
const b=await oc.startRendering(),x=b.getChannelData(0),n=x.length,ab=new ArrayBuffer(44+n*2),v=new DataView(ab),W=(o,t)=>{for(let k=0;k<t.length;k++)v.setUint8(o+k,t.charCodeAt(k))};
W(0,'RIFF');v.setUint32(4,36+n*2,true);W(8,'WAVE');W(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,sr,true);v.setUint32(28,sr*2,true);v.setUint16(32,2,true);v.setUint16(34,16,true);W(36,'data');v.setUint32(40,n*2,true);
for(let k=0;k<n;k++)v.setInt16(44+k*2,Math.max(-1,Math.min(1,x[k]))*32000,true);
const f=new File([ab],'generated-song.wav',{type:'audio/wav'});EDIT=false;setMode('music');sceneUI();$('f').onchange({target:{files:[f],value:''}});return 'generated song loaded'})()"""))
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
# the saved file must be a real H.264 MP4 made by the phone's encoder (MediaCodec), and it must play
log("NATENC", ev("""(async()=>{const o={enc:LASTENC,secs:LASTSECS,ext:ext,size:blob.size,natbad:NATBAD};try{const v=document.createElement('video');v.muted=true;v.src=URL.createObjectURL(blob);
 await new Promise((r,j)=>{v.onloadeddata=r;v.onerror=()=>j(new Error('decode error '+(v.error&&v.error.code)));setTimeout(()=>j(new Error('load timeout')),15000)});o.w=v.videoWidth;o.h=v.videoHeight;o.dur=+v.duration.toFixed(2);
 v.currentTime=Math.min(2,v.duration/2);await new Promise(r=>{v.onseeked=r;setTimeout(r,5000)});const c2=document.createElement('canvas');c2.width=72;c2.height=128;const x=c2.getContext('2d');x.drawImage(v,0,0,72,128);const p=x.getImageData(0,0,72,128).data;let s=0,mn=255,mx=0;for(let i=0;i<p.length;i+=4){const g=p[i]+p[i+1]+p[i+2];s+=g;mn=Math.min(mn,g/3);mx=Math.max(mx,g/3)}o.avg=Math.round(s/(p.length/4)/3);o.min=Math.round(mn);o.max=Math.round(mx)}catch(e){o.err=String(e&&e.message||e)}return JSON.stringify(o)})()"""))
log("SHARE ROW", ev("JSON.stringify({uri:LASTURI,row:!$('shr').hidden,btns:[...$('shr').querySelectorAll('button')].map(b=>b.textContent),save:!$('save').hidden,pv:!$('pv').hidden})"))
log("SHARE APPS", ev("BRN.shareApps().then(r=>JSON.stringify((r.apps||[]).map(a=>a.id+':'+(a.icon||'').length)))"))
log("SHARE MORE", ev("BRN.share({uri:LASTURI}).then(()=>'opened').catch(e=>'ERR '+e)")); time.sleep(3); shot("4b_share_sheet"); sh("adb shell input keyevent 4"); time.sleep(2)
log("HOME AFTER SAVE", ev("BRN.showHome().then(()=>'shown')")); time.sleep(3); shot("4c_home_videos")
sh("adb shell input keyevent 4"); time.sleep(1); shot("4d_after_back")
log("HIDE HOME", ev("BRN.hideHome().then(()=>'hidden')")); time.sleep(1)

# 3) AI Story for real (Gemini plan + Cloudflare FLUX pictures), with typed lyrics
log("STORY", ev("""(()=>{L=['I left my home at night','the city lights were cold','I miss your voice','one day I will come back'];RNG=L.map((x,i)=>({s:(CS||0)+i*3,e:(CS||0)+i*3+3}));RW=L.map(()=>[]);$('lyt').value=L.join('\\n');makeStory();return 'started'})()"""))
t0 = time.time(); last = ""
while time.time() - t0 < 170:
    m = ev("msg.textContent") or ""
    if m != last and "drawing your story" not in m: log(f"[{int(time.time()-t0)}s] MSG:", m[:200]); last = m
    if "Story ready" in m or "Could not" in m or "could not" in m: break
    time.sleep(3)
log("STORY ERROR", ev("String(window.__STERR||'')"))
log("STORY STATE", ev("JSON.stringify(STORY?{n:STORY.sc.length,look:STORY.look,size:STORY.sc.map(x=>x.img.width+'x'+x.img.height)}:null)"))
for k, tt in enumerate([0.5, 3.5, 6.5, 9.5]):
    ev(f"au.pause();au.currentTime=(CS||0)+{tt};true"); time.sleep(1.5); shot(f"5_story_{k}")

# 4) Auto effects director for real (Gemini, Groq backup): plan for typed caption lines + animated emoji decode
log("FX", ev("""(async()=>{const list=await aelGet();const lines=['Did you know that 90% of people','leave a video in the first 3 seconds?','The secret is simple: start with a question','never start by introducing yourself','this trick made me 1000 dollars','هل تعرف أن هذا خطأ كبير؟'];
 const t0=performance.now();const r=await fetch(API_URL+'/fx',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({lines:lines,emojis:list.map(x=>x.c+' '+(x.t||''))})});
 const j=await r.json().catch(()=>null);const e=(j&&j.fx||[]).find(x=>x.k==='emoji');const c=e?e.e:'1f525';await aemLoad(c);const o=AEM[c];const dec=c+' '+(o.f?o.f.length+' frames':o.img?'img':'bad')
 return 'status '+r.status+' '+Math.round(performance.now()-t0)+'ms list '+list.length+' '+JSON.stringify(j).slice(0,900)+' emoji '+dec})()"""))

# the app may be in the background after the Back key above: videos only play when it is in front
sh("adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n io.github.beatreelapp.twa.test/io.github.beatreelapp.twa.MainActivity"); time.sleep(4)
log("HIDE HOME", ev("BRN.hideHome().then(()=>'hidden')")); time.sleep(1)
log("VISIBLE", ev("document.visibilityState"))
# 5) Edit timeline (CapCut style): the video saved above goes into Edit, split / delete / undo, a photo clip, an image on top, export
log("EDIT", ev("""(async()=>{try{const vf=new File([blob],'made.mp4',{type:'video/mp4'});
 EDIT=true;setMode('music');SJ=10;sceneUI();$('vf').onchange({target:{files:[vf],value:''}});
 for(let i=0;i<120&&!(clDur()>0&&!SHBUSY&&SHK===$('bgv').src);i++)await new Promise(r=>setTimeout(r,500));
 const o={dur:+clDur().toFixed(2),shots:SH.length,edon:EDON};edGo(2);edSplit();o.split=CL.length;CLSEL=0;edTL();edDel();o.del=CL.length;edUndo();o.undo=CL.length;
 const c2=document.createElement('canvas');c2.width=400;c2.height=500;const x=c2.getContext('2d');x.fillStyle='#ee77cc';x.fillRect(0,0,400,500);
 const pb=await new Promise(r=>c2.toBlob(r,'image/png'));CLSEL=-1;edAddMedia(new File([pb],'p.png',{type:'image/png'}),false);await new Promise(r=>setTimeout(r,1500));o.clips=CL.map(q=>q.src+':'+cW(q).toFixed(1));
 edAddOvl(new File([pb],'o.png',{type:'image/png'}));await new Promise(r=>setTimeout(r,1000));o.ovl=OVL.length;
 au.pause();CE=Math.min(DUR||CE,CS+9);clipUI();o.len=+clLen().toFixed(2);o.ce=+(CE-CS).toFixed(2);return JSON.stringify(o)}catch(e){return 'ERR '+(e&&e.stack||e)}})()""", 200))
shot("6_edit_timeline")
log("EDIT EXPORT", ev("""(async()=>{const t0=performance.now();let r;try{r=await offlineExport()}catch(e){r='ERR '+e}const o={r:r,secs:Math.round((performance.now()-t0)/1000),enc:LASTENC,size:blob&&blob.size};
 try{const v=document.createElement('video');v.muted=true;v.src=URL.createObjectURL(blob);await new Promise((a,b)=>{v.onloadeddata=a;v.onerror=b;setTimeout(b,15000)});o.dur=+v.duration.toFixed(2);
  const st=clStart(CL.findIndex(q=>q.src>0))+1;v.currentTime=st;await new Promise(a=>{v.onseeked=a;setTimeout(a,5000)});const c2=document.createElement('canvas');c2.width=72;c2.height=128;const x=c2.getContext('2d');x.drawImage(v,0,0,72,128);const p=x.getImageData(36,64,1,1).data;o.photoPx=[p[0],p[1],p[2]];o.at=+st.toFixed(2)}catch(e){o.err=String(e)}
 return JSON.stringify(o)})()""", 600))
