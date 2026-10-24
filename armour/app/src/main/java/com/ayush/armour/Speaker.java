package com.ayush.armour;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads text aloud one sentence at a time with the TV's Google speech engine, so the screen can highlight
 * the sentence being spoken.
 */
final class Speaker {
    interface Listener {
        /** Sentence {@code index} started; -1 when everything has been said or speech was stopped. */
        void onSentence(int index);
    }

    private static Speaker instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;
    private Listener listener;
    private int count;
    private List<String> pending;

    static Speaker get(Context c) {
        if (instance == null) instance = new Speaker(c.getApplicationContext());
        return instance;
    }

    private Speaker(Context c) {
        tts = new TextToSpeech(c, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ready = true;
            Locale in = new Locale("en", "IN");
            if (tts.isLanguageAvailable(in) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(in);
            else tts.setLanguage(Locale.UK);
            pickVoice();
            tts.setSpeechRate(1.0f);
            if (pending != null) {
                List<String> p = pending;
                pending = null;
                speakSentences(p);
            }
        }, "com.google.android.tts");
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                post(Integer.parseInt(id));
            }

            @Override
            public void onDone(String id) {
                if (Integer.parseInt(id) == count - 1) post(-1);
            }

            @Override
            public void onError(String id) {
                post(-1);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                post(-1);
            }
        });
    }

    /** Prefer an installed, non-network male-sounding voice when the engine offers several. */
    private void pickVoice() {
        try {
            Voice best = null;
            for (Voice v : tts.getVoices()) {
                if (!v.getLocale().getLanguage().equals("en") || v.isNetworkConnectionRequired()) continue;
                if (!v.getLocale().getCountry().equals(tts.getVoice().getLocale().getCountry())) continue;
                if (best == null || v.getName().contains("male") || v.getQuality() > best.getQuality()) best = v;
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) {
        }
    }

    private void post(int index) {
        main.post(() -> {
            if (listener != null) listener.onSentence(index);
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.trim().split("(?<=[.!?])\\s+")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    void speak(String text) {
        speakSentences(sentences(text));
    }

    void speakSentences(List<String> parts) {
        if (!ready) {
            pending = parts;
            return;
        }
        tts.stop();
        count = parts.size();
        for (int i = 0; i < parts.size(); i++) {
            tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, String.valueOf(i));
        }
    }

    void stop() {
        pending = null;
        if (tts != null) tts.stop();
    }
}
