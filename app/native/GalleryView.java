package io.github.beatreelapp.twa;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BeatReel's own gallery (like CapCut): the phone's videos, photos and songs inside the app, newest first.
 * kind: "video" (videos), "media" (videos + photos), "image" (photos), "music" (songs).
 * multi: pick several (numbers show the order); otherwise one tap = one file.
 */
public class GalleryView extends FrameLayout {

    public static class Item {
        Uri uri; String mime; String name; long dur; boolean image; String sub;
    }

    public interface Done {
        void picked(List<Item> items);   // empty list = closed without a choice
        void other();                    // "Other files" (the phone's file picker)
    }

    static final int BG = 0xFF111315, CARD = 0xFF1C1F24, LINE = 0xFF30343B, TX = 0xFFE8EAED, MUTED = 0xFF9AA0A6,
        ACC = 0xFF5AB4F0, ACC_TX = 0xFF06233A;

    final String kind, lang;
    final boolean multi;
    final Done done;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    final List<Item> sel = new ArrayList<>();
    List<Item> shown = new ArrayList<>();
    String tab;
    GridView grid;
    ListView list;
    TextView addBtn, hint, empty;
    LinearLayout tabs;
    BaseAdapter gridAd, listAd;
    boolean closed = false;

    public GalleryView(Context c, String kind, boolean multi, Done done) {
        super(c);
        this.kind = kind;
        this.multi = multi && !"music".equals(kind);
        this.done = done;
        String l = Locale.getDefault().getLanguage();
        lang = "ar".equals(l) ? "ar" : "fr".equals(l) ? "fr" : "en";
        setBackgroundColor(BG);
        setClickable(true);
        build();
    }

    int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    String t(String en, String fr, String ar) { return "ar".equals(lang) ? ar : "fr".equals(lang) ? fr : en; }
    GradientDrawable round(int fill, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }
    TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(getContext());
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    void build() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -1));

        // top bar: ✕  title  Other files
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(8), dp(10), dp(4));
        TextView x = text("✕", 22, TX, false);
        x.setGravity(Gravity.CENTER);
        x.setOnClickListener(v -> close(new ArrayList<>()));
        top.addView(x, new LinearLayout.LayoutParams(dp(48), dp(48)));
        String title = "music".equals(kind) ? t("Choose a song", "Choisir une chanson", "اختر أغنية")
            : "image".equals(kind) ? t("Choose a photo", "Choisir une photo", "اختر صورة")
            : t("Add to your video", "Ajouter à ta vidéo", "أضف إلى الفيديو");
        TextView tt = text(title, 17, TX, true);
        tt.setGravity(Gravity.CENTER);
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView other = text(t("Files", "Fichiers", "الملفات"), 13, ACC, true);
        other.setGravity(Gravity.CENTER);
        other.setPadding(dp(10), dp(8), dp(10), dp(8));
        other.setBackground(round(CARD, LINE, 16));
        other.setOnClickListener(v -> { if (closed) return; closed = true; done.other(); });
        top.addView(other);
        col.addView(top);

        // tabs
        tabs = new LinearLayout(getContext());
        tabs.setPadding(dp(12), dp(4), dp(12), dp(8));
        List<String[]> ts = new ArrayList<>();
        if ("video".equals(kind) || "media".equals(kind)) ts.add(new String[] { "v", t("Videos", "Vidéos", "فيديوهات") });
        if ("image".equals(kind) || "media".equals(kind)) ts.add(new String[] { "p", t("Photos", "Photos", "صور") });
        if ("music".equals(kind)) ts.add(new String[] { "m", t("Music", "Musique", "موسيقى") });
        for (int i = 0; i < ts.size(); i++) {
            final String id = ts.get(i)[0];
            TextView b = text(ts.get(i)[1], 14, TX, true);
            b.setGravity(Gravity.CENTER);
            b.setTag(id);
            b.setOnClickListener(v -> showTab(id));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1);
            if (i > 0) p.leftMargin = dp(8);
            tabs.addView(b, p);
        }
        col.addView(tabs);

        FrameLayout body = new FrameLayout(getContext());
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        grid = new GridView(getContext());
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setPadding(dp(8), 0, dp(8), dp(8));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        gridAd = new GridAd();
        grid.setAdapter(gridAd);
        // taps go through the grid itself (a click listener on each tile missed the first tile on the P20)
        grid.setOnItemClickListener((a, v, pos, id) -> { if (pos >= 0 && pos < shown.size()) tap(shown.get(pos)); });
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        body.addView(grid, new LayoutParams(-1, -1));
        list = new ListView(getContext());
        list.setDivider(null);
        list.setPadding(dp(12), 0, dp(12), dp(8));
        list.setClipToPadding(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listAd = new ListAd();
        list.setAdapter(listAd);
        list.setOnItemClickListener((a, v, pos, id) -> { if (pos >= 0 && pos < shown.size()) tap(shown.get(pos)); });
        body.addView(list, new LayoutParams(-1, -1));
        empty = text("", 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), 0, dp(30), 0);
        body.addView(empty, new LayoutParams(-1, -1));

        // bottom: hint + Add
        LinearLayout bot = new LinearLayout(getContext());
        bot.setGravity(Gravity.CENTER_VERTICAL);
        bot.setPadding(dp(16), dp(8), dp(12), dp(12));
        hint = text("", 13, MUTED, false);
        bot.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        addBtn = text("", 16, ACC_TX, true);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setPadding(dp(26), 0, dp(26), 0);
        addBtn.setOnClickListener(v -> { if (!sel.isEmpty()) close(new ArrayList<>(sel)); });
        bot.addView(addBtn, new LinearLayout.LayoutParams(-2, dp(48)));
        col.addView(bot);

        showTab(ts.get(0)[0]);
    }

    void showTab(String id) {
        tab = id;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView b = (TextView) tabs.getChildAt(i);
            boolean on = id.equals(b.getTag());
            b.setTextColor(on ? ACC_TX : 0xFFCFD3D8);
            b.setBackground(round(on ? ACC : CARD, on ? ACC : LINE, 19));
        }
        if (!multi && !"m".equals(id)) sel.clear();
        grid.setVisibility("m".equals(id) ? GONE : VISIBLE);
        list.setVisibility("m".equals(id) ? VISIBLE : GONE);
        empty.setVisibility(GONE);
        shown = new ArrayList<>();
        gridAd.notifyDataSetChanged();
        listAd.notifyDataSetChanged();
        updateBar();
        final Context ctx = getContext();
        pool.execute(() -> {
            final List<Item> r = query(ctx, id);
            ui.post(() -> {
                if (!id.equals(tab)) return;
                shown = r;
                empty.setVisibility(r.isEmpty() ? VISIBLE : GONE);
                empty.setText(t("Nothing here yet. Tap \"Files\" to look in other folders.",
                    "Rien ici pour l'instant. Touche « Fichiers » pour chercher ailleurs.",
                    "لا يوجد شيء هنا. اضغط «الملفات» للبحث في مجلدات أخرى."));
                gridAd.notifyDataSetChanged();
                listAd.notifyDataSetChanged();
            });
        });
    }

    static List<Item> query(Context ctx, String id) {
        List<Item> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        Uri coll;
        String[] cols;
        String selq = null;
        if ("v".equals(id)) {
            coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            cols = new String[] { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Video.Media.DURATION };
        } else if ("p".equals(id)) {
            coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            cols = new String[] { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME };
        } else {
            coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            cols = new String[] { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST };
            selq = MediaStore.Audio.Media.DURATION + " >= 5000";
        }
        try (Cursor c = cr.query(coll, cols, selq, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < 900) {
                Item it = new Item();
                long rid = c.getLong(0);
                it.uri = ContentUris.withAppendedId(coll, rid);
                it.mime = c.getString(1);
                it.name = c.getString(2);
                it.image = "p".equals(id);
                if (!"p".equals(id)) it.dur = c.getLong(3);
                if ("m".equals(id)) {
                    String ti = c.getString(4), ar = c.getString(5);
                    if (ti != null && ti.length() > 0) it.name = ti;
                    it.sub = (ar != null && !ar.startsWith("<")) ? ar : "";
                }
                if (it.name == null) it.name = "file";
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String time(long ms) {
        long s = Math.round(ms / 1000.0);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    void tap(Item it) {
        int k = indexOf(it);
        if (multi) {
            if (k >= 0) sel.remove(k); else sel.add(it);
        } else {
            sel.clear();
            if (k < 0) sel.add(it);
            // one video or photo: a single tap is enough
            if (!"m".equals(tab) && !sel.isEmpty()) { close(new ArrayList<>(sel)); return; }
        }
        gridAd.notifyDataSetChanged();
        listAd.notifyDataSetChanged();
        updateBar();
    }

    int indexOf(Item it) {
        for (int i = 0; i < sel.size(); i++) if (sel.get(i).uri.equals(it.uri)) return i;
        return -1;
    }

    void updateBar() {
        boolean any = !sel.isEmpty();
        addBtn.setText("music".equals(kind) ? t("Use this song", "Utiliser", "استعمل الأغنية")
            : any ? t("Add", "Ajouter", "أضف") + " (" + sel.size() + ")" : t("Add", "Ajouter", "أضف"));
        addBtn.setTextColor(any ? ACC_TX : 0xFF7B8189);
        addBtn.setBackground(round(any ? ACC : LINE, 0, 24));
        hint.setText(any ? ("music".equals(kind) ? "♪ " + sel.get(0).name : sel.size() + " " + t("picked", "choisis", "مختارة"))
            : multi ? t("Tap to pick, in order", "Touche pour choisir, dans l'ordre", "اضغط للاختيار بالترتيب")
            : t("Tap to pick", "Touche pour choisir", "اضغط للاختيار"));
        addBtn.setVisibility(multi || "music".equals(kind) ? VISIBLE : GONE);
    }

    void close(List<Item> items) {
        if (closed) return;
        closed = true;
        pool.shutdownNow();
        done.picked(items);
    }

    void thumb(final Item it, final ImageView iv) {
        final String key = it.uri.toString();
        iv.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        final Context ctx = getContext();
        try {
            pool.execute(() -> {
                Bitmap bm = null;
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    if (Build.VERSION.SDK_INT >= 29) bm = cr.loadThumbnail(it.uri, new Size(256, 256), null);
                    else {
                        long id = ContentUris.parseId(it.uri);
                        bm = it.image ? MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                            : MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                    }
                } catch (Exception ignored) { }
                final Bitmap fb = bm;
                if (fb != null) cache.put(key, fb);
                ui.post(() -> { if (key.equals(iv.getTag()) && fb != null) iv.setImageBitmap(fb); });
            });
        } catch (Exception ignored) { }
    }

    class GridAd extends BaseAdapter {
        public int getCount() { return "m".equals(tab) ? 0 : shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            FrameLayout f;
            int w = (parent.getWidth() > 0 ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels) - dp(16) - dp(8);
            int tw = w / 3, th = tw * 14 / 9;
            if (cv == null) {
                f = new FrameLayout(getContext());
                f.setBackground(round(CARD, 0, 8));
                f.setClipToOutline(true);
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                f.addView(iv, new LayoutParams(-1, -1));
                TextView d = text("", 11, 0xFFFFFFFF, true);
                d.setPadding(dp(5), dp(1), dp(5), dp(1));
                d.setBackground(round(0x73000000, 0, 6));
                LayoutParams dp_ = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                dp_.setMargins(0, 0, dp(5), dp(4));
                f.addView(d, dp_);
                TextView n = text("", 12, ACC_TX, true);
                n.setGravity(Gravity.CENTER);
                LayoutParams np = new LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
                np.setMargins(0, dp(5), dp(5), 0);
                f.addView(n, np);
                View ring = new View(getContext());
                f.addView(ring, new LayoutParams(-1, -1));
            } else f = (FrameLayout) cv;
            f.setLayoutParams(new AbsListView.LayoutParams(-1, th));
            final Item it = shown.get(i);
            ImageView iv = (ImageView) f.getChildAt(0);
            TextView d = (TextView) f.getChildAt(1);
            TextView n = (TextView) f.getChildAt(2);
            View ring = f.getChildAt(3);
            thumb(it, iv);
            d.setText(it.image ? "" : time(it.dur));
            d.setVisibility(it.image ? GONE : VISIBLE);
            int k = indexOf(it);
            if (multi) {
                n.setVisibility(VISIBLE);
                n.setText(k >= 0 ? String.valueOf(k + 1) : "");
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(k >= 0 ? ACC : 0x33000000);
                g.setStroke(dp(2), k >= 0 ? ACC : 0xFFFFFFFF);
                n.setBackground(g);
            } else n.setVisibility(GONE);
            if (k >= 0) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.TRANSPARENT);
                g.setCornerRadius(dp(8));
                g.setStroke(dp(3), ACC);
                ring.setBackground(g);
            } else ring.setBackground(null);
            return f;
        }
    }

    class ListAd extends BaseAdapter {
        public int getCount() { return "m".equals(tab) ? shown.size() : 0; }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv == null) {
                row = new LinearLayout(getContext());
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.addView(new HomeView.Icon(getContext(), "music", ACC), new LinearLayout.LayoutParams(dp(24), dp(24)));
                LinearLayout tx = new LinearLayout(getContext());
                tx.setOrientation(LinearLayout.VERTICAL);
                tx.setPadding(dp(12), 0, dp(8), 0);
                TextView a = text("", 14, TX, true);
                a.setSingleLine(true);
                a.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.addView(a);
                TextView b = text("", 12, MUTED, false);
                b.setSingleLine(true);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.addView(b);
                row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
                TextView ck = text("✓", 18, ACC, true);
                row.addView(ck);
            } else row = (LinearLayout) cv;
            AbsListView.LayoutParams lp = new AbsListView.LayoutParams(-1, -2);
            row.setLayoutParams(lp);
            final Item it = shown.get(i);
            LinearLayout tx = (LinearLayout) row.getChildAt(1);
            ((TextView) tx.getChildAt(0)).setText(it.name);
            String s = it.sub == null || it.sub.isEmpty() ? time(it.dur) : it.sub + " · " + time(it.dur);
            ((TextView) tx.getChildAt(1)).setText(s);
            boolean on = indexOf(it) >= 0;
            row.getChildAt(2).setVisibility(on ? VISIBLE : INVISIBLE);
            GradientDrawable g = round(CARD, on ? ACC : LINE, 12);
            android.graphics.drawable.InsetDrawable ins = new android.graphics.drawable.InsetDrawable(g, 0, dp(3), 0, dp(3));
            row.setBackground(ins);
            return row;
        }
    }
}
