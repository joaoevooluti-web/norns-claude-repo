package org.nornsmobile.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registro (log) do app, usado pela aba LOG. Seguro para uso em qualquer thread. */
final class AppLog {

    static final class Entry {
        final String text;
        final boolean error;
        final long time;

        Entry(String text, boolean error) {
            this.text = text;
            this.error = error;
            this.time = System.currentTimeMillis();
        }
    }

    interface Listener {
        void onEntry(Entry e);
    }

    private static final int MAX = 600;
    private static final ArrayList<Entry> ENTRIES = new ArrayList<Entry>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();

    private AppLog() {
    }

    static void add(String text, boolean error) {
        if (text == null) text = "null";
        if (text.length() > 4000) text = text.substring(0, 4000) + "...";
        Entry e = new Entry(text, error);
        synchronized (ENTRIES) {
            ENTRIES.add(e);
            while (ENTRIES.size() > MAX) ENTRIES.remove(0);
        }
        for (Listener l : LISTENERS) {
            try {
                l.onEntry(e);
            } catch (RuntimeException ex) {
                // não deixa a interface derrubar quem está logando
            }
        }
    }

    static void i(String s) {
        add(s, false);
    }

    static void e(String s) {
        add(s, true);
    }

    static List<Entry> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<Entry>(ENTRIES);
        }
    }

    static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
}
