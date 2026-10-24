package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The TV's sources through the TV input framework: HDMI ports, AV, and the tuner behind Live TV. */
final class TvInputs {
    static final class Source {
        final String id, label;
        final boolean hdmi;
        /** {@link TvInputManager} state, or -1 when the TV reports nonsense (every port "connected"). */
        final int state;

        Source(String id, String label, boolean hdmi, int state) {
            this.id = id;
            this.label = label;
            this.hdmi = hdmi;
            this.state = state;
        }
    }

    private TvInputs() {
    }

    static List<Source> list(Context c) {
        List<Source> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> infos = new ArrayList<>(tim.getTvInputList());
        int hdmiPorts = 0, hdmiConnected = 0;
        for (TvInputInfo i : infos) {
            if (!i.isPassthroughInput() || i.getType() != TvInputInfo.TYPE_HDMI) continue;
            hdmiPorts++;
            if (tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
        }
        boolean bogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
        for (TvInputInfo i : infos) {
            if (!i.isPassthroughInput()) continue;
            boolean hdmi = i.getType() == TvInputInfo.TYPE_HDMI;
            CharSequence l = i.loadLabel(c);
            String label = l == null ? i.getId() : l.toString().toUpperCase(Locale.ROOT);
            if (hdmi && !label.contains("HDMI")) label = "HDMI " + label;
            out.add(new Source(i.getId(), label, hdmi, hdmi && bogus ? -1 : tim.getInputState(i.getId())));
        }
        out.sort((a, b) -> a.hdmi != b.hdmi ? (a.hdmi ? -1 : 1) : a.label.compareTo(b.label));
        return out;
    }

    static List<String> names(Context c) {
        List<String> n = new ArrayList<>();
        for (Source s : list(c)) n.add(s.label);
        return n;
    }

    static Intent open(String inputId) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Live TV through the hardware tuner input when there is one, else the Live TV app as is. */
    static Intent liveTv(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        String tuner = null;
        if (tim != null) {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.getType() != TvInputInfo.TYPE_TUNER) continue;
                if ((i.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                if (tuner == null || i.getId().matches(".*/HW\\d+$")) tuner = i.getId();
            }
        }
        Intent app = c.getPackageManager().getLeanbackLaunchIntentForPackage(Prefs.LIVE_TV);
        if (app == null) return null;
        if (tuner != null) {
            return new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tuner), "vnd.android.cursor.item/channel")
                    .setPackage(Prefs.LIVE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** "hdmi 2", "HDMI2", "live tv", "antenna" → a source, by loose name match. */
    static Intent byName(Context c, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.contains("live") || n.contains("antenna") || n.contains("cable") || n.contains("tuner")) return liveTv(c);
        for (Source s : list(c)) {
            if (s.label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(n)) return open(s.id);
        }
        for (Source s : list(c)) {
            if (s.label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains(n)) return open(s.id);
        }
        return null;
    }
}
