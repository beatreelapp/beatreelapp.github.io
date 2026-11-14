// Runs in GitHub Actions after `npx cap add android`.
// Adds BeatReel's native code, share targets, permissions, icons, version and signing.
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';

const A = 'android/app';
const SRC = `${A}/src/main`;
const PKG = `${SRC}/java/io/github/beatreelapp/twa`;
const run = Number(process.env.RUN_NUMBER || 1);
const must = (ok, what) => { if (!ok) { console.error('PATCH FAILED: ' + what); process.exit(1); } };

// 1. native code
fs.mkdirSync(PKG, { recursive: true });
for (const f of ['MainActivity.java', 'BeatReelPlugin.java', 'HomeView.java', 'GalleryView.java']) fs.copyFileSync(`native/${f}`, `${PKG}/${f}`);
// fonts for the native start screen (Audiowide, SIL OFL)
fs.mkdirSync(`${SRC}/assets/fonts`, { recursive: true });
for (const f of fs.readdirSync('native/assets/fonts')) fs.copyFileSync(`native/assets/fonts/${f}`, `${SRC}/assets/fonts/${f}`);

// 2. manifest: permissions + "Share to BeatReel"
const mp = `${SRC}/AndroidManifest.xml`;
let m = fs.readFileSync(mp, 'utf8');
const perms = [
  '<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />',
  '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
  // BeatReel's own gallery (videos, photos, songs inside the app)
  '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />',
  '<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />',
  '<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />',
  '<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />',
  '<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />',
];
for (const p of perms) if (!m.includes(p)) m = m.replace('</manifest>', `    ${p}\n</manifest>`);
// apps we can share the finished video to (Android 11+ hides other apps unless they are listed here)
const queries = `    <queries>
        <package android:name="com.zhiliaoapp.musically" />
        <package android:name="com.ss.android.ugc.trill" />
        <package android:name="com.instagram.android" />
        <package android:name="com.whatsapp" />
        <package android:name="com.whatsapp.w4b" />
        <package android:name="com.google.android.youtube" />
        <package android:name="com.facebook.katana" />
        <package android:name="com.facebook.lite" />
        <package android:name="com.snapchat.android" />
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="video/*" />
        </intent>
    </queries>
`;
if (!m.includes('<queries>')) m = m.replace('</manifest>', queries + '</manifest>');
const share = `
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="audio/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="video/*" />
            </intent-filter>
`;
const launcher = /(<category android:name="android\.intent\.category\.LAUNCHER" \/>\s*<\/intent-filter>)/;
must(launcher.test(m), 'launcher intent-filter not found');
m = m.replace(launcher, `$1\n${share}`);
fs.writeFileSync(mp, m);

// 3. gradle: version, test build id, signing
const gp = `${A}/build.gradle`;
let g = fs.readFileSync(gp, 'utf8');
must(/versionCode \d+/.test(g), 'versionCode not found');
g = g.replace(/versionCode \d+/, `versionCode ${10 + run}`).replace(/versionName "[^"]*"/, `versionName "2.0.${run}"`);
g += `
android {
    buildTypes {
        debug {
            applicationIdSuffix ".test"
        }
    }
}
if (System.getenv("KS_FILE")) {
    android {
        signingConfigs {
            release {
                storeFile file(System.getenv("KS_FILE"))
                storePassword System.getenv("KS_PASS")
                keyAlias System.getenv("KS_ALIAS")
                keyPassword System.getenv("KS_KEYPASS") ?: System.getenv("KS_PASS")
            }
        }
        buildTypes {
            release {
                signingConfig signingConfigs.release
            }
        }
    }
}
`;
fs.writeFileSync(gp, g);

// test build gets its own name so both apps can sit side by side
fs.mkdirSync(`${A}/src/debug/res/values`, { recursive: true });
fs.writeFileSync(`${A}/src/debug/res/values/strings.xml`,
  '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="app_name">BeatReel Test</string>\n    <string name="title_activity_main">BeatReel Test</string>\n</resources>\n');

// 4. icons from the site icons
const res = `${SRC}/res`;
fs.rmSync(`${res}/mipmap-anydpi-v26`, { recursive: true, force: true });
const sizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [d, s] of Object.entries(sizes)) {
  const dir = `${res}/mipmap-${d}`;
  fs.mkdirSync(dir, { recursive: true });
  for (const f of fs.readdirSync(dir)) if (f.startsWith('ic_launcher')) fs.rmSync(path.join(dir, f));
  const sq = await sharp('www/icon-512.png').resize(s, s).png().toBuffer();
  fs.writeFileSync(`${dir}/ic_launcher.png`, sq);
  const r = s / 2;
  const mask = Buffer.from(`<svg width="${s}" height="${s}"><circle cx="${r}" cy="${r}" r="${r}"/></svg>`);
  const round = await sharp('www/icon-maskable-512.png').resize(s, s).composite([{ input: mask, blend: 'dest-in' }]).png().toBuffer();
  fs.writeFileSync(`${dir}/ic_launcher_round.png`, round);
}
// 5. start screen: the BeatReel icon on the app's dark colour (replaces Capacitor's default splash picture)
let gone = 0;
for (const d of fs.readdirSync(res)) {
  if (!d.startsWith('drawable')) continue;
  for (const f of fs.readdirSync(`${res}/${d}`)) if (/^splash\.(png|9\.png|jpg|webp|xml)$/.test(f)) { fs.rmSync(`${res}/${d}/${f}`); gone++; }
}
fs.mkdirSync(`${res}/drawable`, { recursive: true });
fs.writeFileSync(`${res}/drawable/splash.xml`,
  '<?xml version="1.0" encoding="utf-8"?>\n<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n' +
  '    <item><shape android:shape="rectangle"><solid android:color="#111315"/></shape></item>\n' +
  '    <item android:gravity="center" android:width="112dp" android:height="112dp" android:drawable="@mipmap/ic_launcher"/>\n' +
  '</layer-list>\n');
console.log('splash: removed', gone, 'default pictures, icon splash written');

console.log('patch ok, versionCode', 10 + run);
