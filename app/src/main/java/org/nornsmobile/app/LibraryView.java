package org.nornsmobile.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Aba SCRIPTS: navegador de arquivos do dust (code/data/audio), com importar, criar e exportar. */
final class LibraryView extends LinearLayout {

    interface Callbacks {
        void onOpenFile(File f);

        void onRunScript(File luaFile);

        void onImport(File targetDir);

        void onExport(File src);

        void onRemoved(File f);
    }

    private final Context ctx;
    private final Dust dust;
    private final Callbacks cb;
    private final TextView pathLabel;
    private final TextView upBtn;
    private final LinearLayout list;
    private File current;

    LibraryView(Context c, Dust dust, Callbacks cb) {
        super(c);
        this.ctx = c;
        this.dust = dust;
        this.cb = cb;
        this.current = dust.code;
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(VERTICAL);
        head.setBackgroundColor(Theme.PANEL);
        head.setPadding(Theme.dp(c, 10), Theme.dp(c, 8), Theme.dp(c, 10), Theme.dp(c, 8));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        upBtn = Theme.button(c, "⬆", Theme.PANEL2, Theme.TEXT);
        upBtn.setMinWidth(Theme.dp(c, 44));
        upBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        top.addView(upBtn, Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pathLabel = Theme.mono(c, "", 13, Theme.TEXT);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathLabel.setPadding(Theme.dp(c, 10), 0, 0, 0);
        top.addView(pathLabel, Theme.weight(1f));
        head.addView(top, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout acts = new LinearLayout(c);
        acts.setOrientation(HORIZONTAL);
        TextView imp = Theme.button(c, "IMPORTAR", Theme.PANEL2, Theme.TEXT);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LibraryView.this.cb.onImport(current);
            }
        });
        TextView add = Theme.button(c, "+ NOVO", Theme.ACCENT_BG, Theme.ACCENT);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewMenu();
            }
        });
        TextView exp = Theme.button(c, "EXPORTAR", Theme.PANEL2, Theme.TEXT);
        exp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LibraryView.this.cb.onExport(current);
            }
        });
        int m = Theme.dp(c, 3);
        acts.addView(imp, Theme.margin(Theme.weight(1f), 0, 0, m, 0));
        acts.addView(add, Theme.margin(Theme.weight(1f), m, 0, m, 0));
        acts.addView(exp, Theme.margin(Theme.weight(1f), m, 0, 0, 0));
        head.addView(acts, Theme.margin(Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Theme.dp(c, 8), 0, 0));
        addView(head, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(Theme.dp(c, 8), Theme.dp(c, 6), Theme.dp(c, 8), Theme.dp(c, 24));
        ScrollView sv = new ScrollView(c);
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refresh();
    }

    File getCurrent() {
        return current;
    }

    boolean canGoUp() {
        return !current.equals(dust.root);
    }

    void goUp() {
        if (!canGoUp()) return;
        File p = current.getParentFile();
        if (p != null && dust.isInside(p)) navigate(p);
    }

    void navigate(File dir) {
        if (dir == null || !dir.isDirectory() || !dust.isInside(dir)) return;
        current = dir;
        refresh();
    }

    void refresh() {
        if (!current.isDirectory()) current = dust.code;
        pathLabel.setText(dust.display(current));
        upBtn.setAlpha(canGoUp() ? 1f : 0.35f);
        list.removeAllViews();
        List<File> files = dust.list(current);
        if (files.isEmpty()) {
            TextView t = Theme.label(ctx, "Pasta vazia.\nUse IMPORTAR (ZIP ou arquivos) ou + NOVO.", 14, Theme.MUTED);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 40), Theme.dp(ctx, 16), 0);
            list.addView(t, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        // Separa scripts que exigem engines indisponíveis: vão para o fim, esmaecidos.
        List<File> usable = new ArrayList<File>();
        List<File> blocked = new ArrayList<File>();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (f.isDirectory() && Dust.isScriptDir(f) && LuaHost.unsupportedEngine(Dust.mainScript(f)) != null) blocked.add(f);
            else usable.add(f);
        }
        for (int i = 0; i < usable.size(); i++) {
            list.addView(buildRow(usable.get(i)), Theme.margin(
                    Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, Theme.dp(ctx, 4), 0, 0));
        }
        if (!blocked.isEmpty()) {
            TextView sep = Theme.label(ctx, "INDISPONÍVEIS NESTE APP (engine ausente)", 11, Theme.MUTED);
            sep.setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 16), 0, Theme.dp(ctx, 4));
            list.addView(sep, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < blocked.size(); i++) {
                View r = buildRow(blocked.get(i));
                r.setAlpha(0.45f);
                list.addView(r, Theme.margin(
                        Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                        0, Theme.dp(ctx, 4), 0, 0));
            }
        }
    }

    private static String sizeText(File f) {
        long n = f.length();
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0));
    }

    private View buildRow(final File f) {
        final boolean dir = f.isDirectory();
        final boolean script = dir && Dust.isScriptDir(f);
        String ext = Dust.extension(f.getName());

        String icon;
        String sub;
        if (script) {
            icon = "🎼";
            String missing = LuaHost.unsupportedEngine(Dust.mainScript(f));
            sub = missing == null ? "script" : "script · engine " + missing + " indisponível";
        } else if (dir) {
            icon = "📁";
            String[] kids = f.list();
            sub = (kids == null ? 0 : kids.length) + " itens";
        } else if (ext.equals("wav") || ext.equals("aif") || ext.equals("aiff") || ext.equals("flac")
                || ext.equals("ogg") || ext.equals("mp3")) {
            icon = "🔊";
            sub = sizeText(f);
        } else if (ext.equals("lua")) {
            icon = "📜";
            sub = sizeText(f);
        } else {
            icon = "📄";
            sub = sizeText(f);
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 8), Theme.dp(ctx, 8), Theme.dp(ctx, 8));
        row.setBackground(Theme.pressable(Theme.PANEL, Theme.PANEL2, Theme.dp(ctx, 10)));
        row.setMinimumHeight(Theme.dp(ctx, 56));

        TextView ic = Theme.label(ctx, icon, 20, Theme.TEXT);
        row.addView(ic, Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        texts.setPadding(Theme.dp(ctx, 12), 0, Theme.dp(ctx, 4), 0);
        TextView name = Theme.label(ctx, f.getName(), 14, Theme.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        if (script) name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subT = Theme.label(ctx, sub, 11, Theme.MUTED);
        texts.addView(name);
        texts.addView(subT);
        row.addView(texts, Theme.weight(1f));

        if (script) {
            TextView play = Theme.button(ctx, "▶", Theme.ACCENT_BG, Theme.ACCENT);
            play.setMinWidth(Theme.dp(ctx, 48));
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onRunScript(Dust.mainScript(f));
                }
            });
            row.addView(play, Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dir) {
                    navigate(f);
                } else if (Dust.looksLikeText(f)) {
                    cb.onOpenFile(f);
                } else {
                    Toast.makeText(ctx, "Este tipo de arquivo não abre no editor.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showItemMenu(f);
                return true;
            }
        });
        return row;
    }

    // ------------------------------------------------------------------ menus

    private void showItemMenu(final File f) {
        final boolean dir = f.isDirectory();
        final boolean runnable = (dir && Dust.isScriptDir(f)) || (!dir && f.getName().endsWith(".lua"));
        final boolean editable = !dir && Dust.looksLikeText(f);
        final List<String> labels = new ArrayList<String>();
        final List<Integer> ids = new ArrayList<Integer>();
        if (runnable) {
            labels.add("▶ Executar");
            ids.add(0);
        }
        if (editable) {
            labels.add("Abrir no editor");
            ids.add(1);
        }
        labels.add("Renomear…");
        ids.add(2);
        labels.add("Exportar como ZIP");
        ids.add(3);
        labels.add("Excluir…");
        ids.add(4);
        new AlertDialog.Builder(ctx)
                .setTitle(f.getName())
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int id = ids.get(which);
                        if (id == 0) {
                            cb.onRunScript(dir ? Dust.mainScript(f) : f);
                        } else if (id == 1) {
                            cb.onOpenFile(f);
                        } else if (id == 2) {
                            askRename(f);
                        } else if (id == 3) {
                            cb.onExport(f);
                        } else if (id == 4) {
                            askDelete(f);
                        }
                    }
                })
                .show();
    }

    private void showNewMenu() {
        final String[] items = {"Novo script (com modelo)", "Novo arquivo", "Nova pasta"};
        new AlertDialog.Builder(ctx)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) askNew("Novo script", "nome_do_script", 0);
                        else if (which == 1) askNew("Novo arquivo", "arquivo.lua", 1);
                        else askNew("Nova pasta", "pasta", 2);
                    }
                })
                .show();
    }

    private void askNew(String title, String hint, final int kind) {
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint(hint);
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Criar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String raw = input.getText().toString().trim();
                        if (raw.length() == 0) return;
                        try {
                            if (kind == 0) {
                                File f = dust.createScript(raw);
                                navigate(f.getParentFile());
                                cb.onOpenFile(f);
                            } else if (kind == 1) {
                                File f = Dust.uniqueFile(current, Dust.sanitizeName(raw));
                                Dust.writeText(f, "");
                                refresh();
                                cb.onOpenFile(f);
                            } else {
                                File f = Dust.uniqueFile(current, Dust.sanitizeName(raw));
                                if (!f.mkdirs()) throw new IOException("não foi possível criar a pasta");
                                refresh();
                            }
                        } catch (IOException e) {
                            Toast.makeText(ctx, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askRename(final File f) {
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(f.getName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(ctx)
                .setTitle("Renomear")
                .setView(input)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String nn = Dust.sanitizeName(input.getText().toString().trim());
                        File target = new File(f.getParentFile(), nn);
                        if (nn.equals(f.getName())) return;
                        if (target.exists()) {
                            Toast.makeText(ctx, "Já existe um item com esse nome.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        boolean wasScript = f.isDirectory() && Dust.isScriptDir(f);
                        String oldName = f.getName();
                        if (f.renameTo(target)) {
                            if (wasScript) {
                                File inner = new File(target, oldName + ".lua");
                                if (inner.isFile()) inner.renameTo(new File(target, nn + ".lua"));
                            }
                            cb.onRemoved(f);
                            refresh();
                        } else {
                            Toast.makeText(ctx, "Não foi possível renomear.", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askDelete(final File f) {
        new AlertDialog.Builder(ctx)
                .setTitle("Excluir")
                .setMessage("Excluir \"" + f.getName() + "\"" + (f.isDirectory() ? " e tudo dentro dela" : "") + "? Isso não pode ser desfeito.")
                .setPositiveButton("Excluir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Dust.deleteRecursive(f);
                        cb.onRemoved(f);
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
