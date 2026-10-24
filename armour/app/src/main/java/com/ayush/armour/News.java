package com.ayush.armour;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** The last day's AI news (labs, models, products, policy) from Google News search RSS, for the briefing. */
final class News {
    private static List<String> cache = new ArrayList<>();
    private static long fetched;

    private News() {
    }

    /** Blocking; cached for 20 minutes. */
    static List<String> headlines() {
        if (System.currentTimeMillis() - fetched < 20 * 60_000L && !cache.isEmpty()) return cache;
        try {
            String rss = Net.get("https://news.google.com/rss/search?q=%22artificial+intelligence%22+OR+OpenAI+OR+Anthropic"
                    + "+OR+%22Google+DeepMind%22+OR+LLM+OR+%22AI+model%22+when:1d&hl=en-IN&gl=IN&ceid=IN:en");
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(rss));
            List<String> out = new ArrayList<>();
            boolean inItem = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT && out.size() < 15; e = p.next()) {
                if (e == XmlPullParser.START_TAG && "item".equals(p.getName())) inItem = true;
                else if (e == XmlPullParser.START_TAG && inItem && "title".equals(p.getName())) out.add(p.nextText().trim());
                else if (e == XmlPullParser.END_TAG && "item".equals(p.getName())) inItem = false;
            }
            if (!out.isEmpty()) {
                cache = out;
                fetched = System.currentTimeMillis();
            }
        } catch (Exception ignored) {
        }
        return cache;
    }
}
