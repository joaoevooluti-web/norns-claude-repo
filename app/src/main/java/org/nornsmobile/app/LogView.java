package org.nornsmobile.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Aba LOG: mensagens do app e do script (print, erros). */
final class LogView extends LinearLayout implements AppLog.Listener {

    private final Context ctx;
    private final TextView text;
    private final ScrollView scroll;
    private boolean pending = false;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            pending = false;
            refresh();
        }
    };

    LogView(Context c) {
        super(c);
        this.ctx = c;
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setBackgroundColor(Theme.PANEL);
        head.setPadding(Theme.dp(c, 8), Theme.dp(c, 6), Theme.dp(c, 8), Theme.dp(c, 6));
        TextView title = Theme.label(c, "LOG", 14, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding(Theme.dp(c, 6), 0, 0, 0);
        head.addView(title, Theme.weight(1f));
        TextView copy = Theme.button(c, "COPIAR", Theme.PANEL2, Theme.TEXT);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        TextView clear = Theme.button(c, "LIMPAR", Theme.PANEL2, Theme.TEXT);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.clear();
                refresh();
            }
        });
        head.addView(copy, Theme.margin(Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, 0, Theme.dp(c, 6), 0));
        head.addView(clear, Theme.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(head, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        text = Theme.mono(c, "", 12, Theme.TEXT);
        text.setPadding(Theme.dp(c, 10), Theme.dp(c, 8), Theme.dp(c, 10), Theme.dp(c, 8));
        text.setTextIsSelectable(true);
        scroll = new ScrollView(c);
        scroll.addView(text, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppLog.addListener(this);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        AppLog.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onEntry(AppLog.Entry e) {
        if (pending) return;
        pending = true;
        postDelayed(refreshTask, 150);
    }

    void refresh() {
        List<AppLog.Entry> all = AppLog.snapshot();
        int from = Math.max(0, all.size() - 300);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = from; i < all.size(); i++) {
            AppLog.Entry e = all.get(i);
            int start = sb.length();
            sb.append(e.text).append('\n');
            if (e.error) {
                sb.setSpan(new ForegroundColorSpan(Theme.DANGER), start, sb.length() - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        text.setText(sb);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void copyAll() {
        List<AppLog.Entry> all = AppLog.snapshot();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) sb.append(all.get(i).text).append('\n');
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("norns-log", sb.toString()));
            Toast.makeText(ctx, "Log copiado", Toast.LENGTH_SHORT).show();
        }
    }
}
