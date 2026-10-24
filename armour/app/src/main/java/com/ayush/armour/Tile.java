package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * An app tile showing the app's own TV banner (its logo artwork), framed in the Armour style: a thin
 * copper line, or red-and-gold when selected. Apps without a banner get their icon and name instead.
 */
final class Tile extends FrameLayout {
    private static final Map<String, Drawable> banners = new HashMap<>();
    private static final Map<String, Drawable> icons = new HashMap<>();

    private final ImageView banner;
    private final LinearLayout fallback;
    private final ImageView icon;
    private final TextView name;
    private boolean selected;

    Tile(Context c) {
        super(c);
        int inset = Ui.px(3);
        setPadding(inset, inset, inset, inset);
        banner = new ImageView(c);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(banner, new LayoutParams(-1, -1));
        fallback = new LinearLayout(c);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fallback.addView(icon, new LinearLayout.LayoutParams(Ui.px(44), Ui.px(44)));
        name = Ui.text(c, "", 15, Ui.TEXT2, 400, 2);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.px(8), 0, 0);
        fallback.addView(name);
        addView(fallback, new LayoutParams(-1, -1));
        setSelectedStyle(false);
    }

    void bind(Apps.App a) {
        if (a == null) {
            banner.setImageDrawable(null);
            fallback.setVisibility(GONE);
            return;
        }
        Drawable b = banner(getContext(), a.pkg);
        if (b != null) {
            banner.setImageDrawable(b);
            banner.setVisibility(VISIBLE);
            fallback.setVisibility(GONE);
        } else {
            banner.setVisibility(GONE);
            fallback.setVisibility(VISIBLE);
            icon.setImageDrawable(icon(getContext(), a.pkg));
            name.setText(a.title());
        }
        setContentDescription(a.label);
    }

    void setSelectedStyle(boolean on) {
        selected = on;
        setBackground(on ? Ui.box(Ui.RED, Ui.GOLD, 3) : Ui.box(Ui.PANEL, Ui.LINE2, 1));
    }

    boolean isSelectedStyle() {
        return selected;
    }

    /** The activity's leanback banner first (where most TV apps declare it), then the app's. */
    static Drawable banner(Context c, String pkg) {
        if (banners.containsKey(pkg)) return banners.get(pkg);
        Drawable d = null;
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null) d = pm.getActivityBanner(i);
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (Exception ignored) {
        }
        banners.put(pkg, d);
        return d;
    }

    static Drawable icon(Context c, String pkg) {
        if (icons.containsKey(pkg)) return icons.get(pkg);
        Drawable d = null;
        try {
            d = c.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception ignored) {
        }
        icons.put(pkg, d);
        return d;
    }
}
