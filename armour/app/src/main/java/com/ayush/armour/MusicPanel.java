package com.ayush.armour;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

/**
 * M1, the music changer: while Spotify has a song, the middle of the ring home becomes a spinning disc with
 * the cover, the song, its progress and the transport buttons, and below it a row of vibes to switch to.
 * The row turns like the app ring: the raised middle one plays on OK.
 */
final class MusicPanel extends FrameLayout {
    private static final String[][] VIBES = {
            {"LATE NIGHT", "late night chill"}, {"FOCUS", "deep focus"}, {"", ""},
            {"BOLLYWOOD", "Bollywood hits"}, {"PARTY", "party hits"}, {"ROMANTIC", "romantic Hindi songs"},
            {"WORKOUT", "workout hits"}, {"RETRO", "retro Bollywood classics"}};
    /** Index 2 of {@link #VIBES} is "MORE <artist>", filled in from the song that is playing. */
    private static final int MORE = 2;

    private final Runnable onHide;
    private final Disc disc;
    private final ImageView art;
    private final ObjectAnimator spin;
    private final TextView label, title, artist, elapsed, total;
    private final View barFill;
    private final Icon prev, play, next;
    private final TextView[] slots = new TextView[5];
    private int vibe = MORE;
    private String moreArtist = "", shownTitle;
    private NowPlaying now;
    private boolean busy;

    MusicPanel(Context c, View upTarget, Runnable onHide) {
        super(c);
        this.onHide = onHide;

        disc = new Disc(c);
        addView(disc, Ui.at(180, 170, 250, 250));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(Ui.RED);
        art.setBackground(round);
        art.setClipToOutline(true);
        addView(art, Ui.at(235, 225, 140, 140));
        View hole = new View(c);
        GradientDrawable h = new GradientDrawable();
        h.setShape(GradientDrawable.OVAL);
        h.setColor(Ui.BG);
        h.setStroke(Ui.px(2), Ui.GOLD);
        hole.setBackground(h);
        addView(hole, Ui.at(297, 287, 16, 16));
        spin = ObjectAnimator.ofFloat(art, "rotation", 0f, 360f);
        spin.setDuration(9000);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());

        label = Ui.mono(c, "NOW PLAYING · SPOTIFY", 13, Ui.GOLD, 3);
        addView(label, Ui.at(474, 196, -1, -1));
        title = Ui.text(c, "", 54, Ui.TEXT, 600, 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addView(title, Ui.at(474, 222, 700, -1));
        artist = Ui.text(c, "", 22, Ui.MUTED, 300, 0);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        addView(artist, Ui.at(474, 290, 700, -1));
        elapsed = Ui.mono(c, "0:00", 13, Ui.MUTED, 0);
        addView(elapsed, Ui.at(474, 332, -1, -1));
        addView(Ui.line(c, Ui.LINE), Ui.at(520, 339, 300, 3));
        barFill = Ui.line(c, Ui.GOLD);
        addView(barFill, Ui.at(520, 339, 0, 3));
        total = Ui.mono(c, "", 13, Ui.MUTED, 0);
        addView(total, Ui.at(832, 332, -1, -1));

        prev = new Icon(c, Icon.PREV);
        play = new Icon(c, Icon.PAUSE);
        next = new Icon(c, Icon.NEXT);
        addView(prev, Ui.at(474, 366, 56, 56));
        addView(play, Ui.at(544, 366, 72, 56));
        addView(next, Ui.at(630, 366, 56, 56));
        prev.setOnClickListener(v -> transport("previous"));
        play.setOnClickListener(v -> transport("play_pause"));
        next.setOnClickListener(v -> transport("next"));
        Icon[] row = {prev, play, next};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            row[i].setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (idx > 0) row[idx - 1].requestFocus();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (idx < 2) row[idx + 1].requestFocus();
                } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (upTarget != null) upTarget.requestFocus();
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) slots[2].requestFocus();
                else return false;
                return true;
            });
        }

        TextView change = Ui.mono(c, "CHANGE THE MUSIC", 12, Ui.MUTED, 3);
        change.setGravity(Gravity.CENTER);
        addView(change, Ui.at(0, 470, 1280, -1));
        // Ring-style row: sizes and lifts as drawn in M1, bottoms on one line, the middle one raised.
        float[][] at = {{174, 556, 150, 72}, {342, 530, 170, 84}, {530, 500, 220, 104}, {768, 530, 170, 84}, {956, 556, 150, 72}};
        float[] sizes = {16, 17, 22, 17, 16};
        for (int i = 0; i < 5; i++) {
            TextView t = Ui.text(c, "", sizes[i], i == 2 ? Ui.TEXT : i % 2 == 1 ? Ui.TEXT2 : Ui.MUTED, i == 2 ? 700 : 400, 2);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(Ui.px(10), 0, Ui.px(10), 0);
            t.setBackground(i == 2 ? Ui.box(Ui.PANEL2, Ui.GOLD, 1) : Ui.box(i % 2 == 1 ? Ui.PANEL2 : Ui.PANEL, i % 2 == 1 ? Ui.LINE2 : Ui.LINE, 1));
            addView(t, Ui.at(at[i][0], at[i][1], at[i][2], at[i][3]));
            slots[i] = t;
        }
        TextView center = slots[2];
        center.setFocusable(true);
        center.setClickable(true);
        center.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? Ui.box(Ui.RED, Ui.GOLD, 2) : Ui.box(Ui.PANEL2, Ui.GOLD, 1));
            ((TextView) v).setTextColor(f ? 0xFFFFFFFF : Ui.TEXT);
        });
        center.setOnClickListener(v -> playVibe());
        center.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) turn(-1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) turn(1);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) play.requestFocus();
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) this.onHide.run();
            else return false;
            return true;
        });

        TextView hint = Ui.mono(c, "◀ ▶ PICK A VIBE · OK TO PLAY · ▲ CONTROLS · ▼ BACK TO APPS", 12, Ui.DIM, 3);
        hint.setGravity(Gravity.CENTER);
        addView(hint, Ui.at(0, 672, 1280, -1));
        bindVibes();
    }

    void focus() {
        slots[2].requestFocus();
    }

    /** Called every second with Spotify's session while the panel is up. */
    void update(NowPlaying n) {
        now = n;
        if (n == null) return;
        if (!n.title.equals(shownTitle)) {
            shownTitle = n.title;
            title.setText(n.title);
            artist.setText(n.artist);
            art.setImageBitmap(n.art);
            String first = n.artist.split("[,&]")[0].trim();
            if (!first.equalsIgnoreCase(moreArtist)) {
                moreArtist = first;
                bindVibes();
            }
            title.setAlpha(0f);
            title.animate().alpha(1f).setDuration(300).start();
        } else if (n.art != null && art.getDrawable() == null) {
            art.setImageBitmap(n.art);
        }
        if (!busy) label.setText(n.playing ? "NOW PLAYING · SPOTIFY" : "PAUSED · SPOTIFY");
        float p = n.duration > 0 ? Math.min(1f, n.position / (float) n.duration) : 0f;
        elapsed.setText(time(n.position));
        total.setText(n.duration > 0 ? time(n.duration) : "");
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) barFill.getLayoutParams();
        lp.width = Math.round(Ui.px(300) * p);
        barFill.setLayoutParams(lp);
        disc.setProgress(p);
        play.setKind(n.playing ? Icon.PAUSE : Icon.PLAY);
        if (n.playing && !spin.isStarted()) spin.start();
        else if (n.playing && spin.isPaused()) spin.resume();
        else if (!n.playing && spin.isRunning() && !spin.isPaused()) spin.pause();
    }

    void stop() {
        spin.cancel();
    }

    private void transport(String key) {
        if (now == null) return;
        android.media.session.MediaController.TransportControls t = now.controller.getTransportControls();
        if (key.equals("next")) t.skipToNext();
        else if (key.equals("previous")) t.skipToPrevious();
        else if (now.playing) t.pause();
        else t.play();
    }

    private void turn(int dir) {
        vibe = (vibe + dir + VIBES.length) % VIBES.length;
        bindVibes();
        for (int i = 0; i < 5; i++) {
            TextView t = slots[i];
            t.setTranslationX(Ui.px(dir * 22));
            t.setAlpha(0.4f);
            t.animate().translationX(0).alpha(1f).setDuration(180).start();
        }
    }

    private void bindVibes() {
        for (int i = 0; i < 5; i++) slots[i].setText(name((vibe + i - 2 + VIBES.length) % VIBES.length));
    }

    private String name(int i) {
        if (i != MORE) return VIBES[i][0];
        return moreArtist.isEmpty() ? "MORE LIKE THIS" : "MORE " + moreArtist.toUpperCase(Locale.ROOT);
    }

    /** Plays the middle vibe on Spotify, then brings the home screen back in front of it. */
    private void playVibe() {
        if (busy) return;
        String query = vibe == MORE ? (moreArtist.isEmpty() ? shownTitle : moreArtist) : VIBES[vibe][1];
        if (query == null || query.isEmpty()) return;
        busy = true;
        String what = name(vibe);
        label.setText("CHANGING TO " + what + "…");
        Context c = getContext().getApplicationContext();
        Net.IO.execute(() -> {
            String said = Spotify.play(c, query);
            Commander.goHome(c, null);
            post(() -> {
                busy = false;
                MainActivity.flash(c, said.startsWith("Playing") ? what : "SPOTIFY DIDN'T START IT");
            });
        });
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** The record: dark disc, two grooves, and the song's progress as a gold arc round the edge. */
    private static final class Disc extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private float progress;

        Disc(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
        }

        void setProgress(float p) {
            if (Math.abs(p - progress) < 0.001f) return;
            progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 250f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            fill.setColor(Ui.PANEL);
            c.drawCircle(cx, cy, 122 * k, fill);
            line.setStrokeWidth(1.5f * k);
            line.setColor(Ui.LINE);
            c.drawCircle(cx, cy, 122 * k, line);
            line.setStrokeWidth(k);
            line.setColor(Ui.LINE2);
            line.setAlpha(110);
            c.drawCircle(cx, cy, 104 * k, line);
            c.drawCircle(cx, cy, 92 * k, line);
            line.setAlpha(255);
            if (progress <= 0) return;
            line.setStrokeWidth(3 * k);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(Ui.GOLD);
            arc.set(cx - 118 * k, cy - 118 * k, cx + 118 * k, cy + 118 * k);
            c.drawArc(arc, -90, 360 * progress, false, line);
            line.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    /** A transport button: previous, play, pause or next, drawn on the 24-unit grid of the mockup icons. */
    private static final class Icon extends View {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int kind;

        Icon(Context c, int kind) {
            super(c);
            this.kind = kind;
            setFocusable(true);
            setClickable(true);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            style(false);
            setOnFocusChangeListener((v, f) -> style(f));
        }

        void setKind(int k) {
            if (k == kind) return;
            kind = k;
            invalidate();
        }

        private void style(boolean focused) {
            setBackground(focused ? Ui.box(Ui.RED, Ui.GOLD, 2) : Ui.box(Ui.PANEL, Ui.LINE2, 1));
            p.setColor(focused ? 0xFFFFFFFF : Ui.TEXT2);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = Ui.px(22) / 24f, ox = (getWidth() - 24 * u) / 2f, oy = (getHeight() - 24 * u) / 2f;
            c.save();
            c.translate(ox, oy);
            c.scale(u, u);
            path.reset();
            p.setStrokeWidth(2.2f);
            if (kind == PAUSE) {
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(8, 5, 8, 19, p);
                c.drawLine(16, 5, 16, 19, p);
            } else if (kind == PLAY) {
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                path.moveTo(7, 4);
                path.lineTo(19, 12);
                path.lineTo(7, 20);
                path.close();
                c.drawPath(path, p);
            } else {
                if (kind == NEXT) c.scale(-1, 1, 12, 12);
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                path.moveTo(19, 20);
                path.lineTo(9, 12);
                path.lineTo(19, 4);
                path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(5, 5, 5, 19, p);
            }
            c.restore();
        }
    }
}
