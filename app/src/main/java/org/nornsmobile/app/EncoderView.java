package org.nornsmobile.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Encoder virtual: arraste para cima/direita (+) ou para baixo/esquerda (−). */
final class EncoderView extends View {

    interface Listener {
        void onDelta(int d);
    }

    private final String label;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint notch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float stepPx;
    private float angle = -90f;
    private float acc = 0f;
    private float lastX;
    private float lastY;
    private boolean down = false;
    private Listener listener;

    EncoderView(Context c, String label) {
        super(c);
        this.label = label;
        stepPx = Theme.dp(c, 9);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Theme.PANEL2);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Theme.dp(c, 3));
        notch.setStyle(Paint.Style.STROKE);
        notch.setStrokeCap(Paint.Cap.ROUND);
        notch.setStrokeWidth(Theme.dp(c, 4));
        notch.setColor(Theme.ACCENT);
        text.setColor(Theme.MUTED);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Theme.dp(c, 14));
        text.setFakeBoldText(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = View.MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - Theme.dp(getContext(), 6);
        canvas.drawCircle(cx, cy, r, fill);
        ring.setColor(down ? Theme.ACCENT : Theme.LINE);
        canvas.drawCircle(cx, cy, r, ring);
        double a = Math.toRadians(angle);
        float x1 = cx + (float) Math.cos(a) * r * 0.35f;
        float y1 = cy + (float) Math.sin(a) * r * 0.35f;
        float x2 = cx + (float) Math.cos(a) * r * 0.85f;
        float y2 = cy + (float) Math.sin(a) * r * 0.85f;
        canvas.drawLine(x1, y1, x2, y2, notch);
        canvas.drawText(label, cx, cy + text.getTextSize() * 0.35f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            lastX = e.getX();
            lastY = e.getY();
            acc = 0f;
            down = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        } else if (a == MotionEvent.ACTION_MOVE) {
            float x = e.getX();
            float y = e.getY();
            acc += (lastY - y) + (x - lastX);
            lastX = x;
            lastY = y;
            int d = (int) (acc / stepPx);
            if (d != 0) {
                acc -= d * stepPx;
                angle += d * 14f;
                Listener l = listener;
                if (l != null) l.onDelta(d);
                invalidate();
            }
            return true;
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            down = false;
            invalidate();
            return true;
        }
        return true;
    }
}
