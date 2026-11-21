package org.nornsmobile.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/** Aba EDITOR: abre, edita e salva arquivos de texto (Lua, etc.). */
final class EditorView extends LinearLayout {

    interface Callbacks {
        void onRun(File file);
    }

    private final Context ctx;
    private final Dust dust;
    private final Callbacks cb;
    private final TextView title;
    private final TextView hint;
    private final ScrollView scroll;
    private final CodeEditText editor;
    private final LinearLayout bar;
    private final HorizontalScrollView barScroll;
    private File file;
    private boolean dirty = false;

    EditorView(Context c, Dust dust, Callbacks cb) {
        super(c);
        this.ctx = c;
        this.dust = dust;
        this.cb = cb;
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Theme.dp(c, 12), Theme.dp(c, 6), Theme.dp(c, 8), Theme.dp(c, 6));
        head.setBackgroundColor(Theme.PANEL);

        title = Theme.mono(c, "nenhum arquivo", 12, Theme.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(title, Theme.weight(1f));

        TextView save = Theme.button(c, "SALVAR", Theme.PANEL2, Theme.TEXT);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveNow(true);
            }
        });
        head.addView(save, Theme.margin(Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                Theme.dp(c, 6), 0, 0, 0));

        TextView run = Theme.button(c, "▶ RODAR", Theme.ACCENT_BG, Theme.ACCENT);
        run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCurrent();
            }
        });
        head.addView(run, Theme.margin(Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                Theme.dp(c, 6), 0, 0, 0));

        TextView more = Theme.button(c, "⋯", Theme.PANEL2, Theme.TEXT);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        head.addView(more, Theme.margin(Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                Theme.dp(c, 6), 0, 0, 0));
        addView(head, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hint = Theme.label(c, "Abra um arquivo na aba SCRIPTS para editar.", 14, Theme.MUTED);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(Theme.dp(c, 24), Theme.dp(c, 40), Theme.dp(c, 24), 0);
        addView(hint, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new CodeEditText(c);
        editor.setOnChange(new Runnable() {
            @Override
            public void run() {
                if (!dirty) {
                    dirty = true;
                    updateTitle();
                }
            }
        });
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.addView(editor, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setVisibility(GONE);
        addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bar = buildSymbolBar();
        barScroll = new HorizontalScrollView(c);
        barScroll.setBackgroundColor(Theme.PANEL);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.addView(bar);
        barScroll.setVisibility(GONE);
        addView(barScroll, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout buildSymbolBar() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setPadding(Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4));
        // {texto mostrado, texto inserido, quantos caracteres voltar o cursor}
        String[][] keys = {
                {"⇥", "  ", "0"}, {"(", "()", "1"}, {")", ")", "0"}, {"[", "[]", "1"}, {"]", "]", "0"},
                {"{", "{}", "1"}, {"}", "}", "0"}, {"\"", "\"\"", "1"}, {"'", "''", "1"}, {"=", " = ", "0"},
                {"==", " == ", "0"}, {"~=", " ~= ", "0"}, {".", ".", "0"}, {":", ":", "0"}, {",", ", ", "0"},
                {"#", "#", "0"}, {"-", "-", "0"}, {"--", "-- ", "0"}, {"_", "_", "0"}, {"+", " + ", "0"},
                {"*", " * ", "0"}, {"/", " / ", "0"}, {"%", "%", "0"}, {"<", " < ", "0"}, {">", " > ", "0"},
                {"..", " .. ", "0"}, {"end", "end", "0"}, {"then", " then", "0"}, {"do", " do", "0"},
                {"function", "function ", "0"}, {"local", "local ", "0"}, {"if", "if ", "0"},
                {"for", "for ", "0"}, {"return", "return ", "0"}
        };
        for (int i = 0; i < keys.length; i++) {
            final String ins = keys[i][1];
            final int back = Integer.parseInt(keys[i][2]);
            TextView b = Theme.button(ctx, keys[i][0], Theme.PANEL2, Theme.TEXT);
            b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            b.setMinHeight(Theme.dp(ctx, 38));
            b.setMinWidth(Theme.dp(ctx, 38));
            b.setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 4), Theme.dp(ctx, 8), Theme.dp(ctx, 4));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editor.insertText(ins, back);
                }
            });
            row.addView(b, Theme.margin(Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    Theme.dp(ctx, 2), 0, Theme.dp(ctx, 2), 0));
        }
        return row;
    }

    // ------------------------------------------------------------------ arquivo

    File getFile() {
        return file;
    }

    boolean hasUnsavedChanges() {
        return dirty;
    }

    void openFile(File f) {
        if (file != null && dirty) saveNow(false);
        try {
            String text = Dust.readText(f, Dust.MAX_TEXT_BYTES);
            file = f;
            editor.setCode(text);
            dirty = false;
            hint.setVisibility(GONE);
            scroll.setVisibility(VISIBLE);
            barScroll.setVisibility(VISIBLE);
            scroll.scrollTo(0, 0);
            updateTitle();
        } catch (IOException e) {
            Toast.makeText(ctx, "Não foi possível abrir: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void saveNow(boolean toast) {
        if (file == null) return;
        try {
            Dust.writeText(file, editor.getCode());
            dirty = false;
            updateTitle();
            if (toast) Toast.makeText(ctx, "Salvo", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(ctx, "Erro ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void saveIfDirty() {
        if (file != null && dirty) saveNow(false);
    }

    /** Chamado quando o arquivo aberto foi apagado ou renomeado na biblioteca. */
    void closeIfInside(File removed) {
        if (file == null) return;
        String p = file.getPath();
        String r = removed.getPath();
        if (p.equals(r) || p.startsWith(r + File.separator)) {
            file = null;
            dirty = false;
            editor.setCode("");
            scroll.setVisibility(GONE);
            barScroll.setVisibility(GONE);
            hint.setVisibility(VISIBLE);
            updateTitle();
        }
    }

    private void updateTitle() {
        if (file == null) {
            title.setText("nenhum arquivo");
        } else {
            title.setText((dirty ? "● " : "") + dust.display(file));
        }
    }

    private void runCurrent() {
        if (file == null) {
            Toast.makeText(ctx, "Nenhum arquivo aberto.", Toast.LENGTH_SHORT).show();
            return;
        }
        saveNow(false);
        if (!file.getName().endsWith(".lua")) {
            Toast.makeText(ctx, "Só arquivos .lua podem ser executados.", Toast.LENGTH_SHORT).show();
            return;
        }
        cb.onRun(file);
    }

    // ------------------------------------------------------------------ menu ⋯

    private void showMenu() {
        if (file == null) return;
        final String[] items = {"Buscar…", "Ir para a linha…", "Desfazer", "Refazer"};
        new AlertDialog.Builder(ctx)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) askFind();
                        else if (which == 1) askLine();
                        else if (which == 2) {
                            if (!editor.undo()) Toast.makeText(ctx, "Nada para desfazer", Toast.LENGTH_SHORT).show();
                        } else if (which == 3) {
                            if (!editor.redo()) Toast.makeText(ctx, "Nada para refazer", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void askFind() {
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint("texto a procurar");
        new AlertDialog.Builder(ctx)
                .setTitle("Buscar")
                .setView(input)
                .setPositiveButton("Buscar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int idx = editor.findNext(input.getText().toString());
                        if (idx < 0) {
                            Toast.makeText(ctx, "Não encontrado", Toast.LENGTH_SHORT).show();
                        } else {
                            editor.requestFocus();
                            scrollTo(idx);
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askLine() {
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("número da linha");
        new AlertDialog.Builder(ctx)
                .setTitle("Ir para a linha")
                .setView(input)
                .setPositiveButton("Ir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            int n = Integer.parseInt(input.getText().toString().trim());
                            int off = editor.gotoLine(Math.max(1, n));
                            editor.requestFocus();
                            scrollTo(off);
                        } catch (NumberFormatException e) {
                            Toast.makeText(ctx, "Número inválido", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void scrollTo(final int offset) {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int y = editor.yForOffset(offset) - Theme.dp(ctx, 80);
                scroll.scrollTo(0, Math.max(0, y));
            }
        });
    }
}
