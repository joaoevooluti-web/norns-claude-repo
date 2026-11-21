package org.nornsmobile.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercita os controles touch reais da aba NORNS em runtime Android. */
@RunWith(AndroidJUnit4.class)
public class NornsInputDispatcherInstrumentedTest {
    @Test public void testTouchKeysAndEncoderReachNornsCallbacks() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int[] keyDown = {0};
        final int[] keyUp = {0};
        final int[] encoderTotal = {0};
        final NornsView[] view = new NornsView[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                view[0] = new NornsView(context, new ScreenSurface(), new NornsView.Callbacks() {
                    @Override public void onRun() {}
                    @Override public void onStop() {}
                    @Override public void onTest() {}
                    @Override public void onKey(int n, int z) {
                        if (n == 1 && z == 1) keyDown[0]++;
                        if (n == 1 && z == 0) keyUp[0]++;
                    }
                    @Override public void onEnc(int n, int d) {
                        if (n == 2) encoderTotal[0] += d;
                    }
                });
                view[0].measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST));
                view[0].layout(0, 0, view[0].getMeasuredWidth(), view[0].getMeasuredHeight());
            }
        });

        final NornsView target = view[0];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                LinearLayout keyRow = (LinearLayout) target.getChildAt(4);
                View key1 = keyRow.getChildAt(0);
                LinearLayout encoderRow = (LinearLayout) target.getChildAt(3);
                View encoder2 = encoderRow.getChildAt(1);
                long now = System.currentTimeMillis();

                dispatchAndRecycle(key1, MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_DOWN, 20, 20, 0));
                dispatchAndRecycle(key1, MotionEvent.obtain(now, now + 20,
                        MotionEvent.ACTION_CANCEL, 20, 20, 0));

                dispatchAndRecycle(encoder2, MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_DOWN, 50, 50, 0));
                dispatchAndRecycle(encoder2, MotionEvent.obtain(now, now + 20,
                        MotionEvent.ACTION_MOVE, 180, 50, 0));
                dispatchAndRecycle(encoder2, MotionEvent.obtain(now, now + 40,
                        MotionEvent.ACTION_UP, 180, 50, 0));
                target.releaseInputs();
            }
        });

        assertEquals("touch down reaches key(1,1)", 1, keyDown[0]);
        assertEquals("cancel always releases key(1,0)", 1, keyUp[0]);
        assertTrue("encoder drag produces positive integer deltas", encoderTotal[0] > 0);
    }

    private static void dispatchAndRecycle(View target, MotionEvent event) {
        try {
            target.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
