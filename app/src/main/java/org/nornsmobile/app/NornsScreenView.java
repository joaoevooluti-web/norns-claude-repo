package org.nornsmobile.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Mostra a tela do norns (proporção 2:1) na largura disponível. */
final class NornsScreenView extends View {

    private final ScreenSurface surface;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    NornsScreenView(Context c, ScreenSurface surface) {
        super(c);
        this.surface = surface;
        setBackgroundColor(0xFF000000);
        surface.setInvalidator(new Runnable() {
            @Override
            public void run() {
                postInvalidate();
            }
        });
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = View.MeasureSpec.getSize(widthSpec);
        int h = View.MeasureSpec.getSize(heightSpec);
        int hw = w / 2;
        if (h > 0 && hw > h) {
            hw = h;
            w = hw * 2;
        }
        setMeasuredDimension(w, hw);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dst.set(0f, 0f, getWidth(), getHeight());
        surface.drawFront(canvas, dst, paint);
    }
}
