package com.ayush.armour;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * Files sent to the TV to present: a PDF shown slide by slide, or a video played on a loop (a background
 * for filming in front of the TV). Only the latest one is kept, so "present" with no file reopens it.
 */
final class Present {
    private Present() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "present");
        d.mkdirs();
        return d;
    }

    /** Where the remote server streams an upload before {@link #receive} names it. */
    static File incoming(Context c) {
        return new File(dir(c), ".incoming");
    }

    static boolean isVideo(String name) {
        return name.matches("(?i).*\\.(mp4|m4v|mov|webm|mkv)$");
    }

    /** Takes the uploaded file (or, with none, the last one) and opens it full screen. Returns a note for the sender. */
    static String receive(Context c, String uploadPath, String name) {
        File f;
        if (uploadPath == null) {
            String last = Prefs.get(c).getString("present_file", "");
            f = new File(last);
            if (last.isEmpty() || !f.exists()) return "Nothing to present yet. Send a PDF or a video.";
        } else {
            File up = new File(uploadPath);
            // Only ever the server's own incoming file, never a path from elsewhere.
            if (!up.getAbsolutePath().equals(incoming(c).getAbsolutePath())) return "Nothing to present.";
            String n = name == null ? "" : new File(name).getName().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
            if (n.isEmpty() || n.startsWith(".")) n = (isPdf(up) ? "slides.pdf" : "video.mp4");
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\.(pptx?|key|odp)$")) {
                up.delete();
                return "The TV shows PDFs: export the deck as PDF, or send it from the Mac with tv present, which converts it.";
            }
            if (!isVideo(lower) && !isPdf(up)) {
                up.delete();
                return "That isn't a PDF or a video.";
            }
            if (!isVideo(lower) && !lower.endsWith(".pdf")) n += ".pdf";
            File[] old = dir(c).listFiles();
            if (old != null) for (File o : old) if (!o.equals(up)) o.delete();
            f = new File(dir(c), n);
            if (!up.renameTo(f)) return "Couldn't save the file on the TV.";
            Prefs.get(c).edit().putString("present_file", f.getPath()).apply();
        }
        c.startActivity(new Intent(c, PresentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("file", f.getPath()));
        return isVideo(f.getName()) ? "Playing " + f.getName() + " on a loop. OK pauses, Back stops."
                : "Presenting " + f.getName() + ". Left and right change slides, Back stops.";
    }

    private static boolean isPdf(File f) {
        byte[] head = new byte[5];
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read(head) == 5 && new String(head, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (Exception e) {
            return false;
        }
    }
}
