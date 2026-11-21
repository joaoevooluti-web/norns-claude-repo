package org.nornsmobile.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Pixel-level reference tests. These run on an Android runtime because ScreenSurface
 * uses the platform Bitmap/Canvas rasterizer (not the JVM android.jar stubs).
 */
@RunWith(AndroidJUnit4.class)
public final class ScreenSurfaceGoldenTest {
    @Test public void testGrayLevelGoldenPixelsAndRegionEncoding() {
        ScreenSurface screen = new ScreenSurface();
        screen.reset();
        screen.poke(0, 0, 15);
        screen.poke(1, 0, 8);
        screen.poke(2, 0, 0);
        assertEquals(15, screen.peek(0, 0));
        assertEquals(8, screen.peek(1, 0));
        assertEquals(0, screen.peek(2, 0));
        String row = screen.peekRegion(0, 0, 3, 1);
        assertEquals(3, row.length());
        assertEquals(15, (int) row.charAt(0));
        assertEquals(8, (int) row.charAt(1));
        assertEquals(0, (int) row.charAt(2));
    }

    @Test public void testFilledRectAndCanvasTranslationGoldenPixels() {
        ScreenSurface screen = new ScreenSurface();
        screen.reset();
        screen.setLevel(12);
        screen.rect(4, 5, 2, 2);
        screen.fill();
        assertEquals(12, screen.peek(4, 5));
        assertEquals(12, screen.peek(5, 6));
        assertEquals(0, screen.peek(3, 5));

        screen.reset();
        screen.save();
        screen.translate(10, 12);
        screen.poke(0, 0, 9);
        screen.restore();
        assertEquals(9, screen.peek(10, 12));
        assertEquals(0, screen.peek(0, 0));
    }

    @Test public void testRegionWriteClipsAtScreenEdgesWithoutWrapping() {
        ScreenSurface screen = new ScreenSurface();
        screen.reset();
        assertTrue(screen.pokeRegion(-1, 0, 3, 1, new String(new char[]{3, 7, 11})));
        assertEquals(7, screen.peek(0, 0));
        assertEquals(11, screen.peek(1, 0));
        assertEquals(0, screen.peek(2, 0));
    }
}
