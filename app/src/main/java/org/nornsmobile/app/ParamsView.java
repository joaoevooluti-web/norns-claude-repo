package org.nornsmobile.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.app.AlertDialog;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/** Aba PARAMS: controles gerados a partir dos parâmetros (params) do script em execução. */
final class ParamsView extends LinearLayout {

    interface Callbacks {
        void onNorm(String id, double norm);

        void onDelta(String id, int d);

        void onTrigger(String id);
        void onText(String id, String value);
        void onFile(String id, String path);

        void onPresetRead(int slot);

        void onPresetWrite(int slot);

        void onPresetDelete(int slot);
    }

    private static final class P {
        String kind;
        String id;
        String name;
        String str;
        String path = "";
        double norm;
        int presetSlot = 1;
    }

    private static final class Row {
        P p;
        TextView value;
        SeekBar seek;
        boolean tracking = false;
    }

    private final Context ctx;
    private final Callbacks cb;
    private final LinearLayout list;
    private final TextView empty;
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private String signature = "";
    private int selectedPresetSlot = 1;
    private TextView slotLabel;

    ParamsView(Context c, Callbacks cb) {
        super(c);
        this.ctx = c;
        this.cb = cb;
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG);

        TextView head = Theme.label(c, "PARAMS", 14, Theme.TEXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setBackgroundColor(Theme.PANEL);
        head.setPadding(Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 14));
        addView(head, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout presetPanel = new LinearLayout(c);
        presetPanel.setOrientation(VERTICAL);
        presetPanel.setPadding(Theme.dp(c, 12), Theme.dp(c, 8), Theme.dp(c, 12), Theme.dp(c, 8));
        presetPanel.setBackgroundColor(Theme.PANEL2);
        slotLabel = Theme.label(c, "PRESET 01", 12, Theme.ACCENT);
        slotLabel.setTypeface(Typeface.DEFAULT_BOLD);
        presetPanel.addView(slotLabel);
        HorizontalScrollView slotsScroll = new HorizontalScrollView(c);
        slotsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout slots = new LinearLayout(c);
        slots.setOrientation(HORIZONTAL);
        for (int slot = 1; slot <= 16; slot++) {
            final int value = slot;
            TextView choice = Theme.button(c, String.format(java.util.Locale.US, "%02d", slot), Theme.PANEL, Theme.TEXT);
            LinearLayout.LayoutParams choiceLp = Theme.margin(Theme.lp(Theme.dp(c, 42), Theme.dp(c, 38)), 0, Theme.dp(c, 4), 0, 0);
            choice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectPresetSlot(value); }
            });
            slots.addView(choice, choiceLp);
        }
        slotsScroll.addView(slots);
        presetPanel.addView(slotsScroll, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 42)));
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(HORIZONTAL);
        TextView load = Theme.button(c, "CARREGAR", Theme.PANEL, Theme.TEXT);
        TextView save = Theme.button(c, "SALVAR", Theme.ACCENT_BG, Theme.ACCENT);
        TextView delete = Theme.button(c, "EXCLUIR", Theme.PANEL, Theme.MUTED);
        actions.addView(load, Theme.weight(1f));
        actions.addView(save, Theme.weight(1f));
        actions.addView(delete, Theme.weight(1f));
        load.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.onPresetRead(selectedPresetSlot); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.onPresetWrite(selectedPresetSlot); }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(ctx).setTitle("Excluir preset")
                        .setMessage("Excluir o preset " + String.format(java.util.Locale.US, "%02d", selectedPresetSlot) + "?")
                        .setNegativeButton("CANCELAR", null)
                        .setPositiveButton("EXCLUIR", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                cb.onPresetDelete(selectedPresetSlot);
                            }
                        }).show();
            }
        });
        presetPanel.addView(actions);
        addView(presetPanel, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = Theme.label(c, "Rode um script para ver os parâmetros dele aqui.", 14, Theme.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Theme.dp(c, 24), Theme.dp(c, 40), Theme.dp(c, 24), 0);
        addView(empty, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(Theme.dp(c, 12), Theme.dp(c, 4), Theme.dp(c, 12), Theme.dp(c, 24));
        ScrollView sv = new ScrollView(c);
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void showTextEditor(final Row row) {
        final EditText input = new EditText(ctx);
        input.setText(row.p.str);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(ctx).setTitle(row.p.name).setView(input)
                .setNegativeButton("CANCELAR", null)
                .setPositiveButton("SALVAR", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        cb.onText(row.p.id, input.getText().toString());
                    }
                }).show();
    }

    private void selectPresetSlot(int slot) {
        selectedPresetSlot = Math.max(1, Math.min(16, slot));
        slotLabel.setText(String.format(java.util.Locale.US, "PRESET %02d", selectedPresetSlot));
    }

    void clear() {
        rows.clear();
        list.removeAllViews();
        signature = "";
        empty.setVisibility(VISIBLE);
    }

    /** Recebe a lista serializada pelo Lua (linhas separadas por \n, campos por \t). */
    void setData(String data) {
        List<P> ps = parse(data);
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            P p = ps.get(i);
            if (p.presetSlot > 0) selectPresetSlot(p.presetSlot);
            sig.append(p.kind).append('|').append(p.id).append('|').append(p.name).append('\n');
        }
        if (ps.isEmpty()) {
            clear();
            return;
        }
        empty.setVisibility(GONE);
        if (sig.toString().equals(signature) && rows.size() == ps.size()) {
            update(ps);
        } else {
            signature = sig.toString();
            rebuild(ps);
        }
    }

    private static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) return value == null ? "" : value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') out.append('\n');
                else if (next == 'r') out.append('\r');
                else if (next == 't') out.append('\t');
                else if (next == '\\') out.append('\\');
                else { out.append('\\'); out.append(next); }
            } else out.append(c);
        }
        return out.toString();
    }

    private static List<P> parse(String data) {
        ArrayList<P> out = new ArrayList<P>();
        if (data == null || data.length() == 0) return out;
        String[] lines = data.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length < 5) continue;
            P p = new P();
            p.kind = unescape(f[0]);
            p.id = unescape(f[1]);
            p.name = unescape(f[2]);
            p.str = unescape(f[3]);
            if (f.length > 5) {
                try { p.presetSlot = Integer.parseInt(f[5]); } catch (NumberFormatException ignored) { p.presetSlot = 1; }
            }
            if (f.length > 6) p.path = unescape(f[6]);
            try {
                p.norm = Double.parseDouble(f[4]);
            } catch (NumberFormatException e) {
                p.norm = 0.0;
            }
            out.add(p);
        }
        return out;
    }

    private void update(List<P> ps) {
        for (int i = 0; i < ps.size(); i++) {
            Row r = rows.get(i);
            P p = ps.get(i);
            r.p = p;
            if (r.value != null) r.value.setText(p.kind.equals("file") && p.str.length() == 0 ? "Selecionar arquivo" : p.str);
            if (r.seek != null && !r.tracking) r.seek.setProgress((int) Math.round(p.norm * 1000.0));
        }
    }

    private void rebuild(List<P> ps) {
        rows.clear();
        list.removeAllViews();
        for (int i = 0; i < ps.size(); i++) {
            rows.add(buildRow(ps.get(i)));
        }
    }

    private Row buildRow(final P p) {
        final Row row = new Row();
        row.p = p;
        String k = p.kind;
        if (k.equals("separator") || k.equals("group")) {
            TextView h = Theme.label(ctx, p.name.toUpperCase(), 11, Theme.ACCENT);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(Theme.dp(ctx, 2), Theme.dp(ctx, 18), 0, Theme.dp(ctx, 4));
            list.addView(h, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setBackground(Theme.box(Theme.PANEL, Theme.dp(ctx, 10)));
        card.setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 8), Theme.dp(ctx, 12), Theme.dp(ctx, 8));
        LinearLayout.LayoutParams clp = Theme.margin(
                Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Theme.dp(ctx, 6), 0, 0);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Theme.label(ctx, p.name, 13, Theme.TEXT);
        top.addView(name, Theme.weight(1f));

        if (k.equals("control") || k.equals("number")) {
            row.value = Theme.mono(ctx, p.str, 12, Theme.ACCENT);
            top.addView(row.value);
            card.addView(top);
            SeekBar sb = new SeekBar(ctx);
            sb.setMax(1000);
            sb.setProgress((int) Math.round(p.norm * 1000.0));
            sb.setProgressTintList(ColorStateList.valueOf(Theme.ACCENT));
            sb.setThumbTintList(ColorStateList.valueOf(Theme.ACCENT));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (fromUser) cb.onNorm(row.p.id, progress / 1000.0);
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    row.tracking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    row.tracking = false;
                }
            });
            row.seek = sb;
            card.addView(sb, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (k.equals("option")) {
            TextView prev = Theme.button(ctx, "◀", Theme.PANEL2, Theme.TEXT);
            TextView next = Theme.button(ctx, "▶", Theme.PANEL2, Theme.TEXT);
            prev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onDelta(row.p.id, -1);
                }
            });
            next.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onDelta(row.p.id, 1);
                }
            });
            row.value = Theme.mono(ctx, p.str, 12, Theme.ACCENT);
            row.value.setGravity(Gravity.CENTER);
            row.value.setMinWidth(Theme.dp(ctx, 110));
            top.addView(prev);
            top.addView(row.value);
            top.addView(next);
            card.addView(top);
        } else if (k.equals("binary")) {
            final TextView toggle = Theme.button(ctx, p.str, Theme.PANEL2, Theme.ACCENT);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onTrigger(row.p.id);
                }
            });
            row.value = toggle;
            top.addView(toggle);
            card.addView(top);
        } else if (k.equals("text")) {
            row.value = Theme.button(ctx, p.str.length() == 0 ? "EDITAR TEXTO" : p.str, Theme.PANEL2, Theme.ACCENT);
            row.value.setSingleLine(false);
            row.value.setMaxLines(4);
            row.value.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTextEditor(row); }
            });
            card.addView(top);
            card.addView(row.value, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (k.equals("file")) {
            row.value = Theme.button(ctx, p.str.length() == 0 ? "SELECIONAR ARQUIVO" : p.str, Theme.PANEL2, Theme.ACCENT);
            row.value.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cb.onFile(row.p.id, row.p.path); }
            });
            card.addView(top);
            card.addView(row.value, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (k.equals("trigger")) {
            TextView go = Theme.button(ctx, "DISPARAR", Theme.ACCENT_BG, Theme.ACCENT);
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onTrigger(row.p.id);
                }
            });
            top.addView(go);
            card.addView(top);
        } else {
            row.value = Theme.mono(ctx, p.str, 12, Theme.MUTED);
            top.addView(row.value);
            card.addView(top);
        }
        list.addView(card, clp);
        return row;
    }
}
