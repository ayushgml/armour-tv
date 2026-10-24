package com.ayush.armour;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The red-and-gold "Armour" look. Sizes are in design pixels of the 1280×720 mockups and scaled to the
 * real screen, so every screen matches the canvas it was drawn on.
 */
final class Ui {
    // The palette in use. "armour" is the red-and-gold default; scenes swap in "batman" or "cinema" and
    // the home screen is rebuilt so every view picks the new colours up.
    static int BG, GOLD, RED, PANEL, PANEL2, RAIL, LINE, LINE2, TEXT, TEXT2, MUTED, DIM, SPOKEN;
    static String theme = "armour";

    static {
        setTheme("armour");
    }

    static void setTheme(String name) {
        switch (name) {
            case "batman":
                // Near-black with blood-red light: the accent glows red where Armour has gold.
                theme = name;
                BG = 0xFF050102; GOLD = 0xFFFF2B2B; RED = 0xFF7A0A10; PANEL = 0xFF0D0204; PANEL2 = 0xFF160306;
                RAIL = 0xFF090102; LINE = 0xFF3D0A0E; LINE2 = 0xFF5A1018; TEXT = 0xFFF4E8E8; TEXT2 = 0xFFE4CDCD;
                MUTED = 0xFFC2A0A0; DIM = 0xFF946C6C; SPOKEN = 0xFF8C6464;
                break;
            case "cinema":
                // Lights down: dim gold on black, nothing bright enough to compete with the film.
                theme = name;
                BG = 0xFF020101; GOLD = 0xFFC79A4E; RED = 0xFF3E1A0E; PANEL = 0xFF0A0706; PANEL2 = 0xFF120D0A;
                RAIL = 0xFF070504; LINE = 0xFF2E2016; LINE2 = 0xFF44301E; TEXT = 0xFFD9CDBD; TEXT2 = 0xFFC8B9A4;
                MUTED = 0xFFA8977F; DIM = 0xFF7C6C58; SPOKEN = 0xFF6E604E;
                break;
            default:
                theme = "armour";
                BG = 0xFF0A0404; GOLD = 0xFFF2B544; RED = 0xFFA3171B; PANEL = 0xFF170707; PANEL2 = 0xFF1E0909;
                RAIL = 0xFF120505; LINE = 0xFF5A2A1A; LINE2 = 0xFF6E3420; TEXT = 0xFFF6E9D8; TEXT2 = 0xFFE8D3B8;
                MUTED = 0xFFCDB79A; DIM = 0xFF9C8466; SPOKEN = 0xFF8F7A60;
        }
    }

    private static float scale = 1f;
    private static AssetManager assets;
    private static final SparseArray<Typeface> exo = new SparseArray<>();
    private static Typeface mono;

    private Ui() {
    }

    static void init(Context c) {
        scale = c.getResources().getDisplayMetrics().widthPixels / 1280f;
        assets = c.getAssets();
        if (!theme.equals(Prefs.get(c).getString("theme", "armour"))) setTheme(Prefs.get(c).getString("theme", "armour"));
    }

    /** Design pixels to real pixels. */
    static int px(float v) {
        return Math.round(v * scale);
    }

    /** Real pixels back to design pixels. */
    static float toDesign(int realPx) {
        return realPx / scale;
    }

    static Typeface exo(int weight) {
        Typeface t = exo.get(weight);
        if (t == null) {
            t = new Typeface.Builder(assets, "fonts/exo2.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
            exo.put(weight, t);
        }
        return t;
    }

    static Typeface mono() {
        if (mono == null) mono = Typeface.createFromAsset(assets, "fonts/sharetechmono.ttf");
        return mono;
    }

    /** Exo 2 text. {@code spacing} is letter spacing in design px, like the mockups' letter-spacing. */
    static TextView text(Context c, CharSequence s, float size, int color, int weight, float spacing) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(exo(weight));
        if (spacing != 0) t.setLetterSpacing(spacing / size);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Share Tech Mono label, the small caps lines of the HUD. */
    static TextView mono(Context c, CharSequence s, float size, int color, float spacing) {
        TextView t = text(c, s, size, color, 400, spacing);
        t.setTypeface(mono());
        return t;
    }

    static GradientDrawable box(int fill, int stroke, float strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (strokeW > 0) d.setStroke(Math.max(1, px(strokeW)), stroke);
        return d;
    }

    static FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w < 0 ? FrameLayout.LayoutParams.WRAP_CONTENT : px(w),
                h < 0 ? FrameLayout.LayoutParams.WRAP_CONTENT : px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    /** Anchored to the top-right corner instead: {@code right} is the gap to the right edge. */
    static FrameLayout.LayoutParams atRight(float right, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(right);
        lp.topMargin = px(y);
        lp.gravity = Gravity.TOP | Gravity.END;
        return lp;
    }

    /**
     * A focusable panel button. Unfocused it is a dark panel with a thin copper line; focused it turns red
     * with a gold border, the "selected" state of every Armour screen.
     */
    static TextView button(Context c, String label, float size, float spacing) {
        TextView b = text(c, label, size, TEXT2, 400, spacing);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(px(22), 0, px(22), 0);
        styleButton(b, false);
        b.setOnFocusChangeListener((v, f) -> styleButton((TextView) v, f));
        return b;
    }

    static void styleButton(TextView b, boolean focused) {
        b.setBackground(focused ? box(RED, GOLD, 2) : box(PANEL, LINE2, 1));
        b.setTextColor(focused ? 0xFFFFFFFF : TEXT2);
        b.setTypeface(exo(focused ? 600 : 400));
    }

    /** A flat text button for the side rail and the top bar: gold underline-free, brightens on focus. */
    static TextView flat(Context c, String label, float size, int color) {
        TextView b = text(c, label, size, color, 400, 3);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, f) -> {
            ((TextView) v).setTextColor(f ? GOLD : color);
            v.setBackground(f ? box(PANEL2, GOLD, 1) : null);
        });
        b.setPadding(px(10), px(6), px(10), px(6));
        return b;
    }

    static View line(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        return v;
    }
}
