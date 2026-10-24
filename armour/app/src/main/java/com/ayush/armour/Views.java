package com.ayush.armour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Custom-drawn parts of the Armour screens, all drawn in the 1280×720 design space and scaled. */
final class Views {
    private Views() {
    }

    private static Paint stroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
        return p;
    }

    private static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }

    /**
     * F's living wallpaper. The sky follows the real time of day: plum and amber at dawn, a warmer, brighter
     * copper by day with the sun crossing the screen, orange at golden hour, crimson at dusk, and the original
     * near-black at night with a moon and slowly twinkling stars. The dark-red planet rises from the bottom
     * edge in front of it all, so the sun really sets behind its rim. Redrawn five times a second.
     */
    static final class Sky extends View {
        /** Minutes after midnight to show instead of the clock, for previews from the phone API; -1 = live. */
        static volatile int override = -1;
        static volatile long overrideUntil;
        /** Phone tilt, -1..1: near layers (planet) shift less than far ones (stars), like a window. */
        private static volatile float tiltX, tiltY;
        /** 0 = lit room, 1 = dark room (from the webcam): pulls the sky towards its night look. */
        static volatile float roomDark;
        private static volatile long tiltAt;
        private float curX, curY;

        static void tilt(float x, float y) {
            tiltX = Math.max(-1, Math.min(1, x));
            tiltY = Math.max(-1, Math.min(1, y));
            tiltAt = System.currentTimeMillis();
        }

        // Keyframes: minute offsets are relative to sunrise (r) or sunset (s), resolved in onDraw.
        private static final int NIGHT_TOP = 0xFF0A0404, NIGHT_LOW = 0xFF0A0404;
        private static final int DAWN_TOP = 0xFF1C0C16, DAWN_LOW = 0xFF7A3A22;
        private static final int DAY_TOP = 0xFF2E1813, DAY_LOW = 0xFF74402A;
        private static final int GOLD_TOP = 0xFF2A1010, GOLD_LOW = 0xFF9A4C1E;
        private static final int DUSK_TOP = 0xFF16080E, DUSK_LOW = 0xFF4E1A1A;

        private final Paint skyPaint = new Paint();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = stroke(alpha(Ui.GOLD, 0.5f), 1.5f);
        private final Paint dashed = stroke(alpha(Ui.GOLD, 0.25f), 1f);
        private final Paint rule = stroke(alpha(Ui.GOLD, 0.6f), 1.5f);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path rules = new Path();
        private final float[][] stars = new float[48][3];
        private int sunrise = 6 * 60 + 10, sunset = 18 * 60 + 10;
        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                invalidate();
                // Smooth 30 fps while the phone is tilting the sky, five frames a second otherwise.
                postDelayed(this, System.currentTimeMillis() - tiltAt < 2000 ? 33 : 200);
            }
        };

        Sky(Context c) {
            super(c);
            dashed.setPathEffect(new DashPathEffect(new float[]{2, 10}, 0));
            rules.moveTo(60, 148);
            rules.rLineTo(220, 0);
            rules.rLineTo(18, -18);
            rules.rLineTo(160, 0);
            rules.moveTo(1220, 148);
            rules.rLineTo(-220, 0);
            rules.rLineTo(-18, -18);
            rules.rLineTo(-160, 0);
            java.util.Random r = new java.util.Random(19);
            for (float[] st : stars) {
                st[0] = r.nextFloat() * 1280;
                st[1] = r.nextFloat() * 420;
                st[2] = r.nextFloat() * 6.28f;
            }
        }

        void setSun(int sunriseMin, int sunsetMin) {
            sunrise = sunriseMin;
            sunset = sunsetMin;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(frame);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(frame);
            super.onDetachedFromWindow();
        }

        private float minuteNow() {
            if (override >= 0 && System.currentTimeMillis() < overrideUntil) return override;
            java.util.Calendar c = java.util.Calendar.getInstance();
            return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                    + c.get(java.util.Calendar.SECOND) / 60f;
        }

        /** Sky colours (top, horizon) and how much "day" there is, 0..1, at minute {@code m}. */
        private float[] palette(float m, int[] out) {
            float[] keys = {0, sunrise - 70, sunrise, sunrise + 80, sunset - 100, sunset - 10, sunset + 50, 1440};
            int[] tops = {NIGHT_TOP, NIGHT_TOP, DAWN_TOP, DAY_TOP, DAY_TOP, GOLD_TOP, DUSK_TOP, NIGHT_TOP};
            int[] lows = {NIGHT_LOW, NIGHT_LOW, DAWN_LOW, DAY_LOW, DAY_LOW, GOLD_LOW, NIGHT_LOW, NIGHT_LOW};
            float[] day = {0, 0, 0.5f, 1, 1, 0.8f, 0.15f, 0};
            int i = 0;
            while (i < keys.length - 2 && m >= keys[i + 1]) i++;
            float t = Math.max(0, Math.min(1, (m - keys[i]) / Math.max(1, keys[i + 1] - keys[i])));
            t = t * t * (3 - 2 * t);
            out[0] = mix(tops[i], tops[i + 1], t);
            out[1] = mix(lows[i], lows[i + 1], t);
            return new float[]{day[i] + (day[i + 1] - day[i]) * t};
        }

        private static int mix(int a, int b, float t) {
            int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
            int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
            int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / 1280f;
            float m = minuteNow();
            int[] cols = new int[2];
            float dayness = palette(m, cols)[0];
            float dark = roomDark * 0.75f;
            if (dark > 0) {
                dayness *= 1 - dark;
                cols[0] = mix(cols[0], NIGHT_TOP, dark);
                cols[1] = mix(cols[1], NIGHT_LOW, dark);
            }
            c.save();
            c.scale(s, s);
            skyPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, 720, cols[0], cols[1],
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1280, 720, skyPaint);
            if (Ui.theme.equals("batman")) {
                // Gotham: the same sky soaked in red, darker at the top.
                skyPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, 720, 0xB8100003, 0x70700008,
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, 1280, 720, skyPaint);
            } else if (Ui.theme.equals("cinema")) {
                skyPaint.setShader(null);
                skyPaint.setColor(0xA0000000);
                c.drawRect(0, 0, 1280, 720, skyPaint);
            }
            // Ease towards the phone's tilt; drift back to centre a few seconds after it stops.
            boolean live = System.currentTimeMillis() - tiltAt < 4000;
            curX += ((live ? tiltX : 0) - curX) * 0.18f;
            curY += ((live ? tiltY : 0) - curY) * 0.18f;
            float farX = curX * 46, farY = curY * 30, midX = curX * 28, midY = curY * 18, nearX = curX * 12, nearY = curY * 8;

            long now = System.currentTimeMillis();
            // Stars fade out as the day comes in.
            if (dayness < 0.4f) {
                float vis = 1 - dayness / 0.4f;
                for (float[] st : stars) {
                    float tw = 0.45f + 0.55f * (float) Math.sin(now / 1400.0 + st[2]);
                    star.setColor(alpha(0xF6E9D8, 0.55f * vis * tw));
                    c.drawCircle(st[0] + farX, st[1] + farY, 1.4f, star);
                }
            }
            // Sun from sunrise to sunset, moon through the night, both on a wide arc over the planet.
            boolean isDay = m >= sunrise - 20 && m <= sunset + 20;
            float p = isDay ? (m - (sunrise - 20)) / Math.max(1f, sunset - sunrise + 40)
                    : ((m > sunset ? m - sunset - 20 : m + 1440 - sunset - 20) / Math.max(1f, 1440 - (sunset - sunrise) - 40));
            p = Math.max(0, Math.min(1, p));
            float bx = 60 + p * 1160 + midX;
            float by = 640 - (float) Math.sin(p * Math.PI) * 470 + midY;
            float pulse = 1 + 0.04f * (float) Math.sin(now / 900.0);
            if (isDay) {
                int core = mix(0xFFFFD27A, 0xFFFF8A3D, 1 - dayness);
                glow.setShader(new android.graphics.RadialGradient(bx, by, 260 * pulse,
                        new int[]{alpha(core, 0.55f * Math.max(0.3f, dayness)), alpha(core, 0.12f), 0},
                        new float[]{0, 0.35f, 1}, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(bx, by, 260 * pulse, glow);
                body.setColor(alpha(core, 0.95f));
                c.drawCircle(bx, by, 34, body);
            } else {
                glow.setShader(new android.graphics.RadialGradient(bx, by, 120,
                        new int[]{alpha(0xE8D9B8, 0.18f), 0}, null, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(bx, by, 120, glow);
                body.setColor(alpha(0xE8D9B8, 0.85f));
                c.drawCircle(bx, by, 20, body);
                body.setColor(cols[0]);
                c.drawCircle(bx + 8, by - 5, 17, body);   // crescent bite in the sky's own colour
            }
            // The planet: darker red at night, warmer and brighter rim by day.
            fill.setColor(mix(0x8C3A0A0A | 0xFF000000, 0xFF5E1C14, dayness));
            fill.setAlpha(Math.round(140 + 60 * dayness));
            c.drawCircle(640 + nearX, 1180 + nearY, 760, fill);
            rim.setColor(alpha(Ui.GOLD, 0.5f + 0.3f * dayness));
            c.drawCircle(640 + nearX, 1180 + nearY, 760, rim);
            c.drawCircle(640 + nearX, 1180 + nearY, 700, dashed);
            c.drawPath(rules, rule);
            c.restore();
            if (Ui.theme.equals("batman")) drawBatSignal(c, now, farX * s, farY * s);
        }

        private android.graphics.Bitmap bat;
        private final Paint batPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        /** The bat signal in the sky, breathing slowly; drawn once into a bitmap (blur is too slow per frame). */
        private void drawBatSignal(Canvas c, long now, float dx, float dy) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            if (bat == null || bat.getWidth() != w) bat = batSignal(w, h);
            batPaint.setAlpha(Math.round(255 * (0.82f + 0.18f * (float) Math.sin(now / 1600.0))));
            c.drawBitmap(bat, dx * 0.6f, dy * 0.6f, batPaint);
        }

        private static android.graphics.Bitmap batSignal(int w, int h) {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float s = w / 1280f;
            c.scale(s, s);
            float cx = 1010, cy = 250, rx = 190, ry = 118;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // Beam and halo.
            p.setShader(new android.graphics.RadialGradient(cx, cy, 330, new int[]{0x66FF1A1A, 0x22FF1A1A, 0},
                    new float[]{0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, 330, p);
            p.setShader(new android.graphics.RadialGradient(cx, cy, rx, new int[]{0xCCFF3030, 0x99C01018},
                    null, android.graphics.Shader.TileMode.CLAMP));
            c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(0xFFFF5A4A);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(10, android.graphics.BlurMaskFilter.Blur.SOLID));
            c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p);
            // The bat, in the sky's darkest red.
            android.graphics.Path bat = batPath(cx, cy, 1.6f);
            p.setStyle(Paint.Style.FILL);
            p.setMaskFilter(null);
            p.setColor(0xFF0A0103);
            c.drawPath(bat, p);
            return b;
        }

        /**
         * A bat emblem built from curves: right half traced, then mirrored. {@code k} scales the 100-unit
         * half-width. Ears and wing tips are sharp; the lower wing edge has three soft scallops.
         */
        private static android.graphics.Path batPath(float cx, float cy, float k) {
            android.graphics.Path p = new android.graphics.Path();
            float[][] right = {
                    // x, y, then control point for a quad into this point (NaN = straight line)
                    {0, -14, Float.NaN, 0}, {5, -15, Float.NaN, 0}, {9, -32, Float.NaN, 0}, {13, -13, Float.NaN, 0},
                    {24, -16, 18, -10}, {58, -34, 34, -32}, {100, -10, 84, -34},
                    {82, 2, 88, -6}, {68, 6, 76, -6}, {54, 12, 62, 0}, {38, 20, 46, 6}, {22, 22, 32, 8},
                    {0, 40, 8, 18}};
            p.moveTo(cx, cy - 14 * k);
            for (float[] pt : right) {
                if (Float.isNaN(pt[2])) p.lineTo(cx + pt[0] * k, cy + pt[1] * k);
                else p.quadTo(cx + pt[2] * k, cy + pt[3] * k, cx + pt[0] * k, cy + pt[1] * k);
            }
            // Mirror: walk the right half backwards with x flipped (the control point belongs to the segment
            // ending at pt, so going backwards it sits between pt and the previous point).
            for (int i = right.length - 1; i > 0; i--) {
                float[] pt = right[i], prev = right[i - 1];
                if (Float.isNaN(pt[2])) p.lineTo(cx - prev[0] * k, cy + prev[1] * k);
                else p.quadTo(cx - pt[2] * k, cy + pt[3] * k, cx - prev[0] * k, cy + prev[1] * k);
            }
            p.close();
            return p;
        }
    }

    /** F6: wires from the screen box to every source, the focused one lit in gold. */
    static final class Wires extends View {
        private final Paint idle = stroke(Ui.LINE, 2f);
        private final Paint live = stroke(Ui.GOLD, 3f);
        float[] rowCenters = new float[0];
        int active = -1;

        Wires(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (rowCenters.length == 0) return;
            float s = getWidth() / 1280f;
            c.save();
            c.scale(s, s);
            float busX = 520, core = 360;
            c.drawLine(busX, rowCenters[0], busX, rowCenters[rowCenters.length - 1], idle);
            for (int i = 0; i < rowCenters.length; i++) c.drawLine(busX, rowCenters[i], 560, rowCenters[i], i == active ? live : idle);
            c.drawLine(380, core, busX, core, live);
            if (active >= 0) {
                c.drawLine(busX, core, busX, rowCenters[active], live);
                c.drawLine(busX, rowCenters[active], 560, rowCenters[active], live);
            }
            c.restore();
        }
    }

    /**
     * F7: the night screen. A dim clock inside a slowly turning ring, the camera's view of the room, and a
     * drift every minute so nothing burns into the panel.
     */
    static final class Night extends FrameLayout {
        private final Paint outer = stroke(alpha(0xB8924A, 0.35f), 1f);
        private final Paint segments = stroke(alpha(0x5E1012, 0.7f), 6f);
        private final Paint inner = stroke(alpha(0xB8924A, 0.2f), 1f);
        private final TextView time, date, cam1, cam2;
        private float cx = 820, cy = 330, spin;
        private final Runnable turn = new Runnable() {
            @Override
            public void run() {
                spin = (spin + 1.5f) % 360;
                invalidate();
                postDelayed(this, 1000);
            }
        };

        Night(Context c) {
            super(c);
            setWillNotDraw(false);
            setBackgroundColor(0xFF000000);
            segments.setPathEffect(new DashPathEffect(new float[]{60, 10}, 0));
            inner.setPathEffect(new DashPathEffect(new float[]{2, 8}, 0));
            int dim = 0xFFB8924A;
            time = Ui.text(c, "", 110, dim, 100, 0);
            date = Ui.mono(c, "", 14, dim, 4);
            cam1 = Ui.mono(c, "", 13, dim, 3);
            cam2 = Ui.mono(c, "", 13, 0xFF7A5E2C, 3);
            addView(time);
            addView(date);
            addView(cam1, Ui.at(80, 620, -1, -1));
            addView(cam2, Ui.at(80, 642, -1, -1));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(turn);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(turn);
            super.onDetachedFromWindow();
        }

        /** Called every minute: new time, a new spot on screen, and the camera line. */
        void update(Weather.Now w, int people, long emptyMs) {
            Date now = new Date();
            time.setText(new SimpleDateFormat("h:mm", Locale.ENGLISH).format(now));
            date.setText(new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(now).toUpperCase(Locale.ROOT)
                    + (w.ok() ? " · " + w.temp + "°" : ""));
            int minute = (int) (now.getTime() / 60000);
            float[][] spots = {{820, 330}, {460, 300}, {640, 380}, {760, 260}, {520, 400}};
            cx = spots[minute % spots.length][0];
            cy = spots[minute % spots.length][1];
            time.measure(0, 0);
            date.measure(0, 0);
            time.setLayoutParams(Ui.at(cx - Ui.toDesign(time.getMeasuredWidth()) / 2f, cy - 70, -1, -1));
            date.setLayoutParams(Ui.at(cx - Ui.toDesign(date.getMeasuredWidth()) / 2f, cy + 52, -1, -1));
            if (people == Presence.OFFLINE) {
                cam1.setText("CAMERA OFFLINE");
                cam2.setText("PRESS ANY KEY TO WAKE");
            } else if (people == 0) {
                long left = Math.max(0, 10 - emptyMs / 60000);
                cam1.setText("CAMERA · ROOM EMPTY · SCREEN OFF IN " + left + " MIN");
                cam2.setText("SIT DOWN OR PRESS ANY KEY");
            } else {
                cam1.setText("CAMERA · " + Presence.describe(people).toUpperCase(Locale.ROOT) + " IN THE ROOM");
                cam2.setText("PRESS ANY KEY TO WAKE");
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / 1280f;
            c.save();
            c.scale(s, s);
            c.drawCircle(cx, cy, 210, outer);
            c.save();
            c.rotate(spin, cx, cy);
            c.drawCircle(cx, cy, 190, segments);
            c.restore();
            c.drawCircle(cx, cy, 170, inner);
            c.restore();
        }
    }
}
