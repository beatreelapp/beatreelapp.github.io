package io.github.beatreelapp.twa;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BeatReel's native start screen (drawn by Android, not by the web page):
 *  - a welcome (3 pages, first launch only)
 *  - home: big "+ Video", small Music / Edit / Clipping, "Your videos" (Movies/BeatReel), Home / Settings bar
 * Nothing plays and no file picker opens until the user taps something.
 */
public class HomeView extends FrameLayout {

    public interface Host {
        void pick(String want);          // "video" | "music" | "edit"
        void web(String js);             // run JavaScript in the editor page (and show it)
        void soon(String what);
        void openVideo(Uri u);
        void shareVideo(Uri u);
        void eval(String js, android.webkit.ValueCallback<String> cb);
    }

    static final int BG = 0xFF111315, CARD = 0xFF1C1F24, LINE = 0xFF30343B, TX = 0xFFE8EAED, MUTED = 0xFF9AA0A6,
        ACC = 0xFF5AB4F0, ACC_TX = 0xFF06233A, GOLD = 0xFFE9C46A, GOLD_TX = 0xFF3A2C05, BAR = 0xFF8FCAF3;

    final Host host;
    final Typeface aw;
    final String lang;
    final Handler ui = new Handler(Looper.getMainLooper());
    TextView coinTv, proTv, emptyTv;
    GridLayout grid;
    FrameLayout welcome;

    public HomeView(Context c, Host h) {
        super(c);
        host = h;
        Typeface t;
        try { t = Typeface.createFromAsset(c.getAssets(), "fonts/Audiowide-Regular.ttf"); } catch (Exception e) { t = Typeface.DEFAULT_BOLD; }
        aw = t;
        String l = Locale.getDefault().getLanguage();
        lang = "ar".equals(l) ? "ar" : "fr".equals(l) ? "fr" : "en";
        setBackgroundColor(BG);
        setClickable(true); // nothing under the home screen can be touched
        buildHome();
        SharedPreferences sp = c.getSharedPreferences("br", Context.MODE_PRIVATE);
        if (!sp.getBoolean("welcomed", false)) buildWelcome();
    }

    // ---------- helpers ----------
    int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    String t(String en, String fr, String ar) { return "ar".equals(lang) ? ar : "fr".equals(lang) ? fr : en; }
    TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(getContext());
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }
    GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    // ---------- home ----------
    void buildHome() {
        LinearLayout page = new LinearLayout(getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        addView(page, new LayoutParams(-1, -1));

        ScrollView sv = new ScrollView(getContext());
        sv.setFillViewport(true);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(16));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));

        // header: animated name + coins + PRO
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Wordmark wm = new Wordmark(getContext(), aw);
        head.addView(wm, new LinearLayout.LayoutParams(dp(190), dp(46)));
        head.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        coinTv = text("B  3", 13, GOLD, true);
        coinTv.setPadding(dp(10), dp(5), dp(10), dp(5));
        coinTv.setBackground(round(0, GOLD, 14));
        coinTv.setOnClickListener(v -> host.web("try{document.getElementById('cbc').click()}catch(e){}"));
        head.addView(coinTv);
        proTv = text("PRO", 13, GOLD_TX, true);
        proTv.setPadding(dp(12), dp(5), dp(12), dp(5));
        proTv.setBackground(round(GOLD, 0, 14));
        proTv.setOnClickListener(v -> host.web("try{document.getElementById('cbp').click()}catch(e){}"));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = dp(6);
        head.addView(proTv, pl);
        col.addView(head);

        // big + Video
        LinearLayout big = new LinearLayout(getContext());
        big.setOrientation(LinearLayout.VERTICAL);
        big.setGravity(Gravity.CENTER);
        big.setBackground(round(ACC, 0, 22));
        TextView plus = text("+", 58, ACC_TX, true);
        plus.setGravity(Gravity.CENTER);
        plus.setIncludeFontPadding(false);
        big.addView(plus);
        TextView bl = text(t("Video", "Vidéo", "فيديو"), 18, ACC_TX, true);
        bl.setGravity(Gravity.CENTER);
        big.addView(bl);
        big.setOnClickListener(v -> host.pick("video"));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(168));
        bp.topMargin = dp(18);
        col.addView(big, bp);

        // small: Music / Edit / Clipping
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(92));
        rp.topMargin = dp(10);
        col.addView(row, rp);
        row.addView(small("music", t("Music", "Musique", "موسيقى"), null, v -> host.pick("music")), cell(0));
        row.addView(small("edit", "Edit", "PRO", v -> host.pick("edit")), cell(1));
        row.addView(small("clip", "Clipping", "Soon", v -> host.soon(t("Clipping: coming soon", "Clipping : bientôt disponible", "Clipping: قريباً"))), cell(2));

        // your videos
        TextView yv = text(t("Your videos", "Tes vidéos", "فيديوهاتك"), 14, MUTED, true);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(-2, -2);
        yp.topMargin = dp(22);
        yp.bottomMargin = dp(8);
        col.addView(yv, yp);
        emptyTv = text(t("The videos you make will appear here.", "Les vidéos que tu crées apparaîtront ici.", "الفيديوهات التي تصنعها ستظهر هنا."), 13, MUTED, false);
        col.addView(emptyTv);
        grid = new GridLayout(getContext());
        grid.setColumnCount(3);
        col.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        // bottom bar
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BG);
        bar.setPadding(0, dp(6), 0, dp(6));
        View line = new View(getContext());
        line.setBackgroundColor(LINE);
        page.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(tab("home", t("Home", "Accueil", "الرئيسية"), true, null), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(tab("settings", t("Settings", "Réglages", "الإعدادات"), false,
            v -> host.web("try{tabGo('mo');document.getElementById('tabs').scrollIntoView({block:'start'})}catch(e){}")), new LinearLayout.LayoutParams(0, -2, 1));
    }

    LinearLayout.LayoutParams cell(int i) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        if (i > 0) p.leftMargin = dp(8);
        return p;
    }

    View small(String icon, String label, String badge, OnClickListener click) {
        FrameLayout f = new FrameLayout(getContext());
        f.setBackground(round(CARD, LINE, 16));
        LinearLayout v = new LinearLayout(getContext());
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.addView(new Icon(getContext(), icon, ACC), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView l = text(label, 13, TX, true);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(6), 0, 0);
        v.addView(l);
        f.addView(v, new LayoutParams(-1, -1));
        if (badge != null) {
            TextView b = text(badge, 9, "PRO".equals(badge) ? GOLD_TX : 0xFFC9CCD1, true);
            b.setPadding(dp(5), dp(1), dp(5), dp(1));
            b.setBackground(round("PRO".equals(badge) ? GOLD : LINE, 0, 6));
            LayoutParams bp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.topMargin = dp(5);
            bp.setMarginEnd(dp(5));
            f.addView(b, bp);
        }
        f.setOnClickListener(click);
        return f;
    }

    View tab(String icon, String label, boolean on, OnClickListener click) {
        LinearLayout v = new LinearLayout(getContext());
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.addView(new Icon(getContext(), icon, on ? ACC : MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView l = text(label, 11, on ? ACC : MUTED, false);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(2), 0, 0);
        v.addView(l);
        if (click != null) v.setOnClickListener(click);
        return v;
    }

    /** Called every time the home screen is shown: coins/Pro from the page, and the saved videos. */
    public void refresh() {
        host.eval("(function(){try{return JSON.stringify({c:(typeof COINS!=='undefined'&&COINS!=null)?COINS:(typeof COINMAX!=='undefined'?COINMAX:3),p:!!((typeof CPRO!=='undefined'&&CPRO)||(typeof PRO!=='undefined'&&PRO))})}catch(e){return ''}})()", v -> {
            try {
                if (v == null || v.equals("null")) return;
                String s = new org.json.JSONArray("[" + v + "]").getString(0);
                if (s.isEmpty()) return;
                org.json.JSONObject o = new org.json.JSONObject(s);
                boolean pro = o.optBoolean("p");
                coinTv.setText(pro ? "B  ∞" : "B  " + o.optInt("c", 3));
                proTv.setText(pro ? "PRO ✓" : "PRO");
            } catch (Exception ignored) { }
        });
        loadVideos();
    }

    void loadVideos() {
        final Context ctx = getContext();
        new Thread(() -> {
            final List<Uri> uris = new ArrayList<>();
            final List<Bitmap> bmps = new ArrayList<>();
            try {
                ContentResolver cr = ctx.getContentResolver();
                Uri coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String sel;
                String[] args;
                if (Build.VERSION.SDK_INT >= 29) {
                    sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
                    args = new String[] { "Movies/BeatReel%" };
                } else {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { post(uris, bmps); return; }
                    sel = MediaStore.Video.Media.DATA + " LIKE ?";
                    args = new String[] { "%/Movies/BeatReel/%" };
                }
                try (Cursor c = cr.query(coll, new String[] { MediaStore.Video.Media._ID }, sel, args, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                    while (c != null && c.moveToNext() && uris.size() < 12) {
                        long id = c.getLong(0);
                        Uri u = ContentUris.withAppendedId(coll, id);
                        Bitmap b = null;
                        try {
                            if (Build.VERSION.SDK_INT >= 29) b = cr.loadThumbnail(u, new Size(270, 480), null);
                            else b = MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                        } catch (Exception ignored) { }
                        uris.add(u);
                        bmps.add(b);
                    }
                }
            } catch (Exception ignored) { }
            post(uris, bmps);
        }).start();
    }

    void post(List<Uri> uris, List<Bitmap> bmps) {
        ui.post(() -> {
            grid.removeAllViews();
            emptyTv.setVisibility(uris.isEmpty() ? VISIBLE : GONE);
            int w = (getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels) - dp(32) - dp(16);
            int tw = w / 3, th = tw * 16 / 9;
            for (int i = 0; i < uris.size(); i++) {
                final Uri u = uris.get(i);
                FrameLayout tile = new FrameLayout(getContext());
                tile.setBackground(round(CARD, LINE, 12));
                tile.setClipToOutline(true);
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (bmps.get(i) != null) iv.setImageBitmap(bmps.get(i));
                tile.addView(iv, new LayoutParams(-1, -1));
                tile.setOnClickListener(v -> host.openVideo(u));
                Icon sh = new Icon(getContext(), "share", TX);
                sh.setBackground(round(0xB3111315, 0, 16));
                sh.setPadding(dp(6), dp(6), dp(6), dp(6));
                sh.setOnClickListener(v -> host.shareVideo(u));
                LayoutParams sp = new LayoutParams(dp(32), dp(32), Gravity.BOTTOM | Gravity.END);
                sp.setMargins(dp(5), dp(5), dp(5), dp(5));
                tile.addView(sh, sp);
                GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
                gp.width = tw;
                gp.height = th;
                gp.setMargins(i % 3 == 0 ? 0 : dp(4), dp(4), i % 3 == 2 ? 0 : dp(4), dp(4));
                grid.addView(tile, gp);
            }
        });
    }

    // ---------- welcome (first launch only) ----------
    int wp = 0;
    TextView wTitle, wSub, wBtn;
    FrameLayout wArt;
    LinearLayout wDots;

    void buildWelcome() {
        welcome = new FrameLayout(getContext());
        welcome.setBackgroundColor(BG);
        welcome.setClickable(true);
        addView(welcome, new LayoutParams(-1, -1));

        TextView skip = text(t("Skip", "Passer", "تخطّي"), 14, MUTED, false);
        skip.setPadding(dp(18), dp(16), dp(18), dp(16));
        skip.setOnClickListener(v -> endWelcome());
        welcome.addView(skip, new LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        mid.setPadding(dp(28), 0, dp(28), 0);
        wArt = new FrameLayout(getContext());
        mid.addView(wArt, new LinearLayout.LayoutParams(-1, dp(150)));
        wTitle = text("", 24, TX, true);
        wTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(26);
        mid.addView(wTitle, tp);
        wSub = text("", 15, MUTED, false);
        wSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-1, -2);
        sp2.topMargin = dp(10);
        mid.addView(wSub, sp2);
        welcome.addView(mid, new LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(24), 0, dp(24), dp(28));
        wDots = new LinearLayout(getContext());
        wDots.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(wDots, new LinearLayout.LayoutParams(-2, dp(8)));
        wBtn = text("", 16, ACC_TX, true);
        wBtn.setGravity(Gravity.CENTER);
        wBtn.setBackground(round(ACC, 0, 26));
        wBtn.setOnClickListener(v -> { if (wp < 2) showPage(wp + 1); else endWelcome(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(52));
        bp.topMargin = dp(18);
        bottom.addView(wBtn, bp);
        welcome.addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM));

        final float[] x0 = { 0 };
        welcome.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) x0[0] = e.getX();
            else if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - x0[0];
                if ("ar".equals(lang)) dx = -dx;
                if (dx < -dp(60) && wp < 2) showPage(wp + 1);
                else if (dx > dp(60) && wp > 0) showPage(wp - 1);
            }
            return true;
        });
        showPage(0);
    }

    void showPage(int p) {
        wp = p;
        wArt.removeAllViews();
        if (p == 0) {
            wArt.addView(new Wordmark(getContext(), aw), new LayoutParams(dp(280), dp(72), Gravity.CENTER));
            wTitle.setText(t("Turn any song into a video", "Transforme une chanson en vidéo", "حوّل أي أغنية إلى فيديو"));
            wSub.setText(t("The lyrics appear on the beat, by themselves.", "Les paroles apparaissent sur le rythme, toutes seules.", "الكلمات تظهر على الإيقاع، وحدها."));
        } else if (p == 1) {
            wArt.addView(new Icon(getContext(), "chat", ACC), new LayoutParams(dp(110), dp(110), Gravity.CENTER));
            wTitle.setText(t("Auto captions with AI", "Sous-titres automatiques avec l'IA", "ترجمة تلقائية بالذكاء الاصطناعي"));
            wSub.setText(t("Add a video: the words and the effects appear by themselves.", "Ajoute une vidéo : les mots et les effets apparaissent tout seuls.", "أضف فيديو: الكلمات والمؤثرات تظهر وحدها."));
        } else {
            wArt.addView(new Icon(getContext(), "spark", GOLD), new LayoutParams(dp(110), dp(110), Gravity.CENTER));
            wTitle.setText(t("Free. No account.", "Gratuit. Sans compte.", "مجاني. بدون حساب."));
            wSub.setText(t("Start now. Pro is only if you want more.", "Commence maintenant. Pro seulement si tu veux plus.", "ابدأ الآن. Pro فقط إذا أردت المزيد."));
        }
        wBtn.setText(p < 2 ? t("Next", "Suivant", "التالي") : t("Start", "Commencer", "ابدأ"));
        wDots.removeAllViews();
        for (int i = 0; i < 3; i++) {
            View d = new View(getContext());
            d.setBackground(round(i == p ? ACC : LINE, 0, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == p ? 20 : 8), dp(8));
            if (i > 0) lp.leftMargin = dp(6);
            wDots.addView(d, lp);
        }
    }

    void endWelcome() {
        getContext().getSharedPreferences("br", Context.MODE_PRIVATE).edit().putBoolean("welcomed", true).apply();
        if (welcome != null) { removeView(welcome); welcome = null; }
    }

    // ---------- the animated name: glass "Beat" with a moving shine, R, dancing sound bars, l ----------
    static class Wordmark extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        final long t0 = System.currentTimeMillis();
        final Runnable tick = new Runnable() { public void run() { invalidate(); if (isShown()) postDelayed(this, 33); } };

        Wordmark(Context c, Typeface tf) {
            super(c);
            for (Paint p : new Paint[] { fill, edge, shine }) p.setTypeface(tf);
            fill.setColor(0x29FFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(0xE6FFFFFF);
            bar.setColor(BAR);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onVisibilityChanged(View v, int vis) { super.onVisibilityChanged(v, vis); removeCallbacks(tick); if (vis == VISIBLE) post(tick); }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), ts = h * 0.74f, base = h * 0.80f, sec = (System.currentTimeMillis() - t0) / 1000f;
            for (Paint p : new Paint[] { fill, edge, shine }) p.setTextSize(ts);
            edge.setStrokeWidth(Math.max(1f, h / 40f));
            float wBeat = fill.measureText("Beat"), wR = fill.measureText("R"), gap = ts * 0.06f;
            float bw = ts * 0.085f, bg = ts * 0.055f, barsW = 6 * bw + 5 * bg;
            float total = wBeat + gap * 2 + wR + gap + barsW + gap + fill.measureText("l");
            float x = Math.max(0, (getWidth() - total) / 2f);
            // shine: a bright band that crosses the whole name every 3.4 s
            float cyc = (sec % 3.4f) / 3.4f, sx = -total * 0.4f + cyc * total * 2.2f;
            shine.setShader(new LinearGradient(x + sx - ts * 0.6f, 0, x + sx + ts * 0.6f, 0,
                new int[] { 0x00FFFFFF, 0xE6FFFFFF, 0x00FFFFFF }, new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
            drawGlass(c, "Beat", x, base);
            x += wBeat + gap * 2;
            drawGlass(c, "R", x, base);
            x += wR + gap;
            float[] f = { 3.1f, 4.3f, 2.7f, 5.0f, 3.7f, 4.6f }, ph = { 0f, 1.3f, 2.1f, 0.6f, 2.8f, 1.9f };
            for (int i = 0; i < 6; i++) {
                float k = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(sec * f[i] + ph[i]));
                float bh = ts * 0.72f * k;
                c.drawRoundRect(new RectF(x, base - bh, x + bw, base), bw * 0.3f, bw * 0.3f, bar);
                x += bw + bg;
            }
            x += gap;
            drawGlass(c, "l", x, base);
        }

        void drawGlass(Canvas c, String s, float x, float y) {
            c.drawText(s, x, y, fill);
            c.drawText(s, x, y, edge);
            c.drawText(s, x, y, shine);
        }
    }

    // ---------- simple line icons drawn with Paint (no pictures needed) ----------
    static class Icon extends View {
        final String kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context c, String k, int color) {
            super(c);
            kind = k;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            float pl = getPaddingLeft(), pt = getPaddingTop();
            float w = getWidth() - pl - getPaddingRight(), h = getHeight() - pt - getPaddingBottom(), s = Math.min(w, h) / 24f;
            c.save();
            c.translate(pl + (w - 24 * s) / 2f, pt + (h - 24 * s) / 2f);
            c.scale(s, s);
            p.setStrokeWidth(2f);
            p.setStyle(Paint.Style.STROKE);
            Path a = new Path();
            switch (kind) {
                case "music":
                    a.moveTo(9, 18); a.lineTo(9, 5); a.lineTo(20, 3); a.lineTo(20, 16);
                    c.drawPath(a, p); c.drawCircle(6, 18, 3, p); c.drawCircle(17, 16, 3, p);
                    break;
                case "edit":
                    c.drawCircle(6, 7, 3, p); c.drawCircle(6, 17, 3, p);
                    a.moveTo(8.6f, 8.6f); a.lineTo(20, 18); a.moveTo(8.6f, 15.4f); a.lineTo(20, 6);
                    c.drawPath(a, p);
                    break;
                case "clip":
                    c.drawRoundRect(new RectF(3, 5, 21, 19), 2.5f, 2.5f, p);
                    a.moveTo(12, 3); a.lineTo(12, 21); c.drawPath(a, p);
                    break;
                case "home":
                    a.moveTo(4, 11); a.lineTo(12, 4); a.lineTo(20, 11); a.moveTo(6, 10); a.lineTo(6, 20); a.lineTo(18, 20); a.lineTo(18, 10);
                    c.drawPath(a, p);
                    break;
                case "settings":
                    c.drawCircle(12, 12, 3, p); c.drawCircle(12, 12, 7.5f, p);
                    for (int i = 0; i < 8; i++) { double r = i * Math.PI / 4; c.drawLine(12 + 7.5f * (float) Math.cos(r), 12 + 7.5f * (float) Math.sin(r), 12 + 10f * (float) Math.cos(r), 12 + 10f * (float) Math.sin(r), p); }
                    break;
                case "share":
                    c.drawCircle(18, 5, 3, p); c.drawCircle(6, 12, 3, p); c.drawCircle(18, 19, 3, p);
                    a.moveTo(8.6f, 13.5f); a.lineTo(15.4f, 17.5f); a.moveTo(15.4f, 6.5f); a.lineTo(8.6f, 10.5f);
                    c.drawPath(a, p);
                    break;
                case "chat":
                    p.setStrokeWidth(1.4f);
                    c.drawRoundRect(new RectF(2.5f, 3.5f, 21.5f, 16.5f), 3, 3, p);
                    a.moveTo(7, 16.5f); a.lineTo(6, 21); a.lineTo(11, 16.5f); c.drawPath(a, p);
                    c.drawLine(6.5f, 8, 17.5f, 8, p); c.drawLine(6.5f, 12, 14, 12, p);
                    break;
                case "spark":
                    p.setStrokeWidth(1.4f);
                    a.moveTo(12, 2); a.quadTo(13, 11, 22, 12); a.quadTo(13, 13, 12, 22); a.quadTo(11, 13, 2, 12); a.quadTo(11, 11, 12, 2); a.close();
                    c.drawPath(a, p);
                    break;
            }
            c.restore();
        }
    }
}
