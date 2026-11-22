package org.nornsmobile.app;

import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Aba NORNS: a tela de 128x64, 3 encoders (E1-E3) e 3 teclas (K1-K3). */
final class NornsView extends LinearLayout {

    interface Callbacks {
        void onRun();
        void onStop();
        void onTest();
        void onKey(int n, int z);
        void onEnc(int n, int d);
    }

    private final Context ctx;
    private final Callbacks cb;
    private final TextView status;
    private final TextView runBtn;
    private final NornsInputDispatcher input;
    private final LinearLayout keyRow;

    NornsView(Context c, ScreenSurface surface, Callbacks callbacks) {
        super(c);
        this.ctx = c;
        this.cb = callbacks;
        this.input = new NornsInputDispatcher(new NornsInputDispatcher.Sink() {
            @Override public void onKey(int n, int z) { NornsView.this.cb.onKey(n, z); }
            @Override public void onEnc(int n, int d) { NornsView.this.cb.onEnc(n, d); }
        });
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG);
        setPadding(Theme.dp(c, 10), Theme.dp(c, 8), Theme.dp(c, 10), Theme.dp(c, 8));

        status = Theme.mono(c, "nenhum script em execução", 12, Theme.MUTED);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(Theme.dp(c, 2), 0, 0, Theme.dp(c, 6));
        addView(status, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        NornsScreenView screen = new NornsScreenView(c, surface);
        addView(screen, Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(HORIZONTAL);
        runBtn = Theme.button(c, "▶ RODAR", Theme.ACCENT_BG, Theme.ACCENT);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.onRun(); }
        });
        TextView stop = Theme.button(c, "■ PARAR", Theme.PANEL2, Theme.TEXT);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.onStop(); }
        });
        TextView test = Theme.button(c, "♪ TESTE", Theme.PANEL2, Theme.TEXT);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.onTest(); }
        });
        int m = Theme.dp(c, 3);
        actions.addView(runBtn, Theme.margin(Theme.weight(1f), 0, 0, m, 0));
        actions.addView(stop, Theme.margin(Theme.weight(1f), m, 0, m, 0));
        actions.addView(test, Theme.margin(Theme.weight(1f), m, 0, 0, 0));
        addView(actions, Theme.margin(Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Theme.dp(c, 10), 0, 0));

        // Tamanho da fonte do display (ajuda quando textos se sobrepõem).
        final android.content.SharedPreferences fprefs = c.getSharedPreferences("norns_mobile", Context.MODE_PRIVATE);
        surface.setTextScale(fprefs.getFloat("screen_font_scale", 1f));
        LinearLayout fontRow = new LinearLayout(c);
        fontRow.setOrientation(HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView smaller = Theme.button(c, "A−", Theme.PANEL2, Theme.TEXT);
        TextView bigger = Theme.button(c, "A+", Theme.PANEL2, Theme.TEXT);
        final TextView fontLabel = Theme.mono(c, "fonte " + Math.round(surface.getTextScale() * 100) + "%", 12, Theme.MUTED);
        fontLabel.setGravity(Gravity.CENTER);
        View.OnClickListener fontClick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                float f = surface.getTextScale() + (((TextView) v).getText().toString().equals("A+") ? 0.1f : -0.1f);
                surface.setTextScale(f);
                f = surface.getTextScale();
                fprefs.edit().putFloat("screen_font_scale", f).apply();
                fontLabel.setText("fonte " + Math.round(f * 100) + "%");
            }
        };
        smaller.setOnClickListener(fontClick);
        bigger.setOnClickListener(fontClick);
        fontRow.addView(smaller, Theme.weight(1f));
        fontRow.addView(fontLabel, Theme.weight(2f));
        fontRow.addView(bigger, Theme.weight(1f));
        addView(fontRow, Theme.margin(Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Theme.dp(c, 8), 0, 0));


        encs.setOrientation(HORIZONTAL);
        encs.setGravity(Gravity.CENTER);
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            EncoderView ev = new EncoderView(c, "E" + i);
            ev.setListener(new EncoderView.Listener() {
                @Override public void onDelta(int d) { input.encoderDelta(n, d); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Theme.dp(c, 8), 0, Theme.dp(c, 8), 0);
            encs.addView(ev, lp);
        }
        addView(encs, Theme.margin(Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                Theme.dp(c, 18), Theme.dp(c, 12), Theme.dp(c, 18), 0));

        keyRow = new LinearLayout(c);
        keyRow.setOrientation(HORIZONTAL);
        for (int i = 1; i <= 3; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Theme.dp(c, 68), 1f);
            lp.setMargins(Theme.dp(c, 4), 0, Theme.dp(c, 4), 0);
            keyRow.addView(keyButton(i), lp);
        }
        addView(keyRow, Theme.margin(Theme.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Theme.dp(c, 12), 0, 0));
    }

    private TextView keyButton(final int n) {
        TextView b = Theme.button(ctx, "K" + n, Theme.PANEL2, Theme.TEXT);
        b.setTextSize(17);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    v.setTag(Integer.valueOf(e.getPointerId(0)));
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    input.keyDown(n);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    v.setTag(null);
                    input.keyUp(n);
                } else if (a == MotionEvent.ACTION_POINTER_UP
                        && v.getTag() instanceof Integer
                        && ((Integer) v.getTag()).intValue() == e.getPointerId(e.getActionIndex())) {
                    v.setPressed(false);
                    v.setTag(null);
                    input.keyUp(n);
                }
                return true;
            }
        });
        return b;
    }

    /** Libera teclas virtuais antes de parar/trocar script ou perder a tela. */
    void releaseInputs() {
        input.releaseAll();
        for (int i = 0; i < keyRow.getChildCount(); i++) {
            View key = keyRow.getChildAt(i);
            key.setPressed(false);
            key.setTag(null);
        }
    }

    @Override protected void onDetachedFromWindow() {
        releaseInputs();
        super.onDetachedFromWindow();
    }

    void setRunning(boolean running, String name) {
        if (running) {
            status.setText("● " + name);
            status.setTextColor(Theme.ACCENT);
            runBtn.setText("↻ REINICIAR");
        } else {
            status.setText("nenhum script em execução");
            status.setTextColor(Theme.MUTED);
            runBtn.setText("▶ RODAR");
        }
    }
}
