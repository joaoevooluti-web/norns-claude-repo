package org.nornsmobile.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cores e pequenas fábricas de widgets (a interface é construída por código, sem XML de layout). */
final class Theme {

    static final int BG = 0xFF0B0D10;
    static final int PANEL = 0xFF161A1F;
    static final int PANEL2 = 0xFF1F252C;
    static final int LINE = 0xFF2A323B;
    static final int TEXT = 0xFFE6EAEE;
    static final int MUTED = 0xFF8D98A3;
    static final int ACCENT = 0xFFB8F0A0;
    static final int ACCENT_BG = 0xFF24402A;
    static final int DANGER = 0xFFFF6B6B;
    static final int WARN = 0xFFFFC857;

    private Theme() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable box(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static StateListDrawable pressable(int normal, int pressed, float radiusPx) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed}, box(pressed, radiusPx));
        sd.addState(new int[]{}, box(normal, radiusPx));
        return sd;
    }

    static TextView label(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView mono(Context c, String text, float sp, int color) {
        TextView t = label(c, text, sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** "Botão" feito com TextView (evita as dimensões mínimas e estilos do Button do Material). */
    static TextView button(Context c, String text, int bg, int fg) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(false);
        int h = dp(c, 12);
        int v = dp(c, 9);
        b.setPadding(h, v, h, v);
        b.setMinHeight(dp(c, 40));
        b.setBackground(pressable(bg, lighten(bg), dp(c, 8)));
        return b;
    }

    static int lighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 28);
        int b = Math.min(255, (color & 0xFF) + 28);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margin(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }
}
