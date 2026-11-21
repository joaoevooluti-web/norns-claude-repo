package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public class ScreenApiContractTest {
    @Test public void exportAndDisplayOperationsAreMappedToNornsScreenIndices() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("case 33:"));
        assertTrue(host.contains("screen.exportScreenshot(target)"));
        assertTrue(host.contains("case 34:"));
        assertTrue(host.contains("screen.displayPng(source, a.optdouble(2, 0), a.optdouble(3, 0))"));
        assertTrue(host.contains("String relative = \"data/\""));
    }

    @Test public void screenshotExportUsesScriptDataFolderAndSupportsNoScriptFallback() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("scriptName == null || scriptName.trim().isEmpty() ? \"\""));
        assertTrue(host.contains("fileName.toLowerCase(java.util.Locale.US).endsWith(\".png\")"));
        assertTrue(host.contains("dust.resolve(relative)"));
    }

    @Test public void androidScreenPowerHelpersAreExplicit() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("screen.ping = function() return true end"));
        assertTrue(boot.contains("screen.update_low_battery = function() return false end"));
    }

    @Test public void screenRasterOperationsExposeDeterministicGoldenPixelPrimitives() throws Exception {
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(surface.contains("static final int W = 128"));
        assertTrue(surface.contains("static final int H = 64"));
        assertTrue(surface.contains("int peek(double x, double y)"));
        assertTrue(surface.contains("void poke(double x, double y, int lvl)"));
        assertTrue(surface.contains("String peekRegion(int x, int y, int width, int height)"));
        assertTrue(surface.contains("boolean pokeRegion(int x, int y, int width, int height, String values)"));
        assertTrue(surface.contains("bc.drawRect(px, py, px + 1, py + 1, p)"));
        assertTrue(surface.contains("private static int color(int lvl)"));
        assertTrue(surface.contains("Math.max(0, Math.min(15, Math.round(gray * 15f / 255f)))"));
    }

    @Test public void imageBuffersCanBeFreedAndAreReleasedWhenTheScriptResets() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(host.contains("\"free_image\""));
        assertTrue(host.contains("case 43:"));
        assertTrue(host.contains("screen.freeImage(screenImage(a.arg(1)))"));
        assertTrue(surface.contains("boolean freeImage(Image image)"));
        assertTrue(surface.contains("image.bitmap.recycle()"));
        assertTrue(surface.contains("for (Image image : images)"));
        assertTrue(surface.contains("while (!drawStates.isEmpty()) endDrawTo()"));
    }

    @Test public void imageBuffersEnforceAggregateMemoryBudgetAndReleaseAccounting() throws Exception {
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(surface.contains("MAX_IMAGE_BYTES = 32L * 1024L * 1024L"));
        assertTrue(surface.contains("private long imageBytes = 0L"));
        assertTrue(surface.contains("private boolean trackImage(Image image)"));
        assertTrue(surface.contains("MAX_IMAGE_BYTES - imageBytes"));
        assertTrue(surface.contains("imageBytes += bytes"));
        assertTrue(surface.contains("imageBytes - image.bitmap.getByteCount()"));
        assertTrue(surface.contains("imageBytes = 0L"));
        assertTrue(surface.contains("long estimatedBytes"));
    }

    @Test public void pngDecoderHasInputSizeAndDimensionLimits() throws Exception {
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(surface.contains("16L * 1024L * 1024L"));
        assertTrue(surface.contains("bounds.outWidth > 4096 || bounds.outHeight > 4096"));
        assertTrue(surface.contains("Bitmap.CompressFormat.PNG"));
        assertTrue(surface.contains("W * SCALE + 128, H * SCALE + 128"));
    }

    @Test public void dustPathResolverRejectsPathsOutsidePrivateDustRoot() throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"), "norns-screen-path-test");
        Dust dust = new Dust(base);
        assertNotNull(dust.resolve("data/screenshot.png"));
        assertNull(dust.resolve("../../outside.png"));
        assertNull(dust.resolve(new File(base.getParentFile(), "outside.png").getAbsolutePath()));
    }


    @Test public void imageBufferAndRegionApisAreExposedWithBoundedAllocation() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(host.contains("\"load_png\", \"create_image\", \"display_image\", \"display_image_region\", \"draw_to\""));
        assertTrue(host.contains("case 38:"));
        assertTrue(host.contains("screen.loadPng(source)"));
        assertTrue(host.contains("case 39:"));
        assertTrue(host.contains("screen.createImage(a.checkint(1), a.checkint(2))"));
        assertTrue(host.contains("case 40:"));
        assertTrue(host.contains("case 41:"));
        assertTrue(host.contains("case 42:"));
        assertTrue(surface.contains("width > 4096 || height > 4096"));
        assertTrue(surface.contains("width * height > 16_777_216L"));
        assertTrue(surface.contains("beginDrawTo"));
        assertTrue(surface.contains("endDrawTo"));
    }

    @Test public void peekAndPokeUseNornsRectangularByteStringContract() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(host.contains("screen.peekRegion(x, y, width, height)"));
        assertTrue(host.contains("screen.pokeRegion(x, y, width, height, a.checkjstring(5))"));
        assertTrue(surface.contains("String peekRegion(int x, int y, int width, int height)"));
        assertTrue(surface.contains("boolean pokeRegion(int x, int y, int width, int height, String values)"));
        assertTrue(surface.contains("values.length() < width * height"));
    }

    @Test public void blendModeAcceptsNornsNumericAndStringValues() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(host.contains("case 5: name = \"overlay\";"));
        assertTrue(host.contains("case 7: name = \"lighten\";"));
        assertTrue(host.contains("case 8: name = \"color_dodge\";"));
        assertTrue(host.contains("case 13: name = \"exclusion\";"));
        assertTrue(surface.contains("PorterDuff.Mode.XOR"));
        assertTrue(surface.contains("BlendMode.COLOR_DODGE"));
        assertTrue(surface.contains("BlendMode.COLOR_BURN"));
        assertTrue(surface.contains("BlendMode.HARD_LIGHT"));
        assertTrue(surface.contains("BlendMode.SOFT_LIGHT"));
        assertTrue(surface.contains("BlendMode.DIFFERENCE"));
        assertTrue(surface.contains("BlendMode.EXCLUSION"));
        assertTrue(surface.contains("Build.VERSION.SDK_INT >= 29"));
        assertTrue(surface.contains("if (legacy == null) return false"));
        assertTrue(surface.contains("PorterDuff.Mode.OVERLAY"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring("app/".length()));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
@Test public void missingNornsTextTrimAndRotationFunctionsAreExposedToLua() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(host.contains("\"text_trim\", \"text_rotate\", \"text_center_rotate\""));
        assertTrue(host.contains("case 44:"));
        assertTrue(host.contains("screen.textTrim(a.checkjstring(1), a.checkdouble(2))"));
        assertTrue(host.contains("case 45:"));
        assertTrue(host.contains("screen.textRotate(a.checkdouble(1), a.checkdouble(2)"));
        assertTrue(host.contains("case 46:"));
        assertTrue(host.contains("screen.textCenterRotate(a.checkdouble(1), a.checkdouble(2)"));
        assertTrue(surface.contains("void textTrim(String value, double maxWidth)"));
        assertTrue(surface.contains("textPaint.measureText(text) > limit"));
        assertTrue(surface.contains("Double.isNaN(maxWidth) || maxWidth < 0.0"));
        assertTrue(surface.contains("void textRotate(double x, double y, String value, double degrees)"));
        assertTrue(surface.contains("void textCenterRotate(double x, double y, String value, double degrees)"));
        assertTrue(surface.contains("bc.rotate((float) degrees, (float) x, (float) y)"));
    }

@Test public void bootLuaDoesNotShadowNativeScreenTextFunctionsAndAdvertisesAllFonts() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("screen.font_face_count = 11"));
        assertTrue(boot.contains("\"Roboto Thin\", \"Roboto Light\", \"Roboto Regular\""));
        assertTrue(boot.contains("\"Roboto Medium\", \"Roboto Bold\", \"Roboto Black\""));
        assertFalse(boot.contains("function screen.text_trim("));
        assertFalse(boot.contains("function screen.text_rotate("));
        assertFalse(boot.contains("function screen.text_center_rotate("));
        assertTrue(boot.contains("text_trim/text_rotate/text_center_rotate are native screen operations"));
    }

@Test public void allDocumentedNornsFontFaceIndicesHaveStableMappings() throws Exception {
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(surface.contains("new Typeface[11]"));
        assertTrue(surface.contains("faces[0] = Typeface.MONOSPACE"));
        assertTrue(surface.contains("sans-serif-condensed"));
        assertTrue(surface.contains("sans-serif-thin"));
        assertTrue(surface.contains("sans-serif-light"));
        assertTrue(surface.contains("sans-serif-medium"));
        assertTrue(surface.contains("sans-serif-black"));
        assertTrue(surface.contains("Typeface.ITALIC"));
        assertTrue(surface.contains("fontFace = Math.max(1, Math.min(faces.length, f))"));
        assertTrue(surface.contains("Math.min(faces.length - 1, fontFace - 1)"));
    }

@Test public void imageRegionBoundsUseWideArithmeticBeforeConstructingAndroidRects() throws Exception {
        String surface = read("app/src/main/java/org/nornsmobile/app/ScreenSurface.java");
        assertTrue(surface.contains("(long) left + width > image.bitmap.getWidth()"));
        assertTrue(surface.contains("(long) top + height > image.bitmap.getHeight()"));
    }

}
