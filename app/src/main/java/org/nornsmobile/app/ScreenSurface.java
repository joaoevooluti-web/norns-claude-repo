package org.nornsmobile.app;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * A tela do norns (128x64, 16 níveis de cinza) desenhada com a API de caminho do cairo:
 * move/line/rect/circle/arc/curve + stroke/fill, texto, save/restore.
 * Renderiza num bitmap 4x maior (nitidez) e mantém um buffer "frontal" para a interface.
 * Todos os métodos de desenho devem ser chamados pela thread do Lua; drawFront() pela thread de UI.
 */
final class ScreenSurface {

    static final int W = 128;
    static final int H = 64;
    static final int SCALE = 4;
    // Aggregate script-owned image memory budget; prevents many valid images exhausting the process.
    private static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;

    private final Bitmap back;
    private final Bitmap front;
    private Canvas bc;
    private final Canvas fc;
    private final Object lock = new Object();
    private final Path path = new Path();
    private final Paint shape = new Paint();
    private final Paint textPaint = new Paint();
    private final RectF oval = new RectF();
    private final Rect srcRect = new Rect(0, 0, W * SCALE, H * SCALE);
    private final Typeface[] faces = new Typeface[11];

    private double cx;
    private double cy;
    private double subX;
    private double subY;
    private boolean hasPoint;
    private int level = 15;
    private double lineWidth = 1.0;
    private boolean aa = false;
    private int fontFace = 1;
    private float fontSize = 8f;
    private int saveDepth = 0;
    private volatile Runnable invalidator;

    static final class Image {
        final Bitmap bitmap;
        Image(Bitmap bitmap) { this.bitmap = bitmap; }
    }

    private static final class DrawState {
        final Canvas canvas;
        final Path path;
        final double cx, cy, subX, subY;
        final boolean hasPoint;
        final int saveDepth;
        final Image target;
        DrawState(Canvas canvas, Path path, double cx, double cy, double subX, double subY,
                  boolean hasPoint, int saveDepth, Image target) {
            this.canvas = canvas; this.path = path; this.cx = cx; this.cy = cy;
            this.subX = subX; this.subY = subY; this.hasPoint = hasPoint;
            this.saveDepth = saveDepth; this.target = target;
        }
    }
    private final java.util.ArrayDeque<DrawState> drawStates = new java.util.ArrayDeque<>();
    private final ArrayList<Image> images = new ArrayList<>();
    private long imageBytes = 0L;

    ScreenSurface() {
        back = Bitmap.createBitmap(W * SCALE, H * SCALE, Bitmap.Config.ARGB_8888);
        front = Bitmap.createBitmap(W * SCALE, H * SCALE, Bitmap.Config.ARGB_8888);
        bc = new Canvas(back);
        fc = new Canvas(front);
        bc.scale(SCALE, SCALE);
        faces[0] = Typeface.MONOSPACE; // Norns
        faces[1] = Typeface.create("sans-serif-condensed", Typeface.NORMAL); // ALEPH approximation
        faces[2] = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        faces[3] = Typeface.create("sans-serif-light", Typeface.NORMAL);
        faces[4] = Typeface.create("sans-serif", Typeface.NORMAL);
        faces[5] = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        faces[6] = Typeface.create("sans-serif", Typeface.BOLD);
        faces[7] = Typeface.create("sans-serif-black", Typeface.NORMAL);
        faces[8] = Typeface.create("sans-serif-thin", Typeface.ITALIC);
        faces[9] = Typeface.create("sans-serif-light", Typeface.ITALIC);
        faces[10] = Typeface.create("sans-serif", Typeface.ITALIC);
        textPaint.setAntiAlias(true);
        reset();
    }

    void setInvalidator(Runnable r) {
        invalidator = r;
    }

    /** Estado inicial para um novo script: tela preta, nível 15, linha 1, fonte 1 tamanho 8. */
    void reset() {
        // A script may stop while drawing into an image. Restore every nested canvas
        // before releasing script-owned image buffers so no stale Canvas retains them.
        while (!drawStates.isEmpty()) endDrawTo();
        for (Image image : images) {
            if (validImage(image)) image.bitmap.recycle();
        }
        images.clear();
        imageBytes = 0L;
        bc = new Canvas(back);
        bc.scale(SCALE, SCALE);
        while (saveDepth > 0) {
            bc.restore();
            saveDepth--;
        }
        path.reset();
        hasPoint = false;
        level = 15;
        lineWidth = 1.0;
        aa = false;
        fontFace = 1;
        fontSize = 8f;
        shape.setXfermode(null);
        textPaint.setXfermode(null);
        if (Build.VERSION.SDK_INT >= 29) {
            shape.setBlendMode(BlendMode.SRC_OVER);
            textPaint.setBlendMode(BlendMode.SRC_OVER);
        }
        shape.setStrokeCap(Paint.Cap.BUTT);
        shape.setStrokeJoin(Paint.Join.MITER);
        shape.setStrokeMiter(10f);
        clear();
        update();
    }

    private static int color(int lvl) {
        int l = lvl < 0 ? 0 : (lvl > 15 ? 15 : lvl);
        int v = Math.round(255f * l / 15f);
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    // ------------------------------------------------------------------ estado

    void clear() {
        bc.drawColor(Color.BLACK);
    }

    void update() {
        synchronized (lock) {
            fc.drawBitmap(back, 0f, 0f, null);
        }
        Runnable r = invalidator;
        if (r != null) r.run();
    }

    void setLevel(int l) {
        level = l < 0 ? 0 : (l > 15 ? 15 : l);
    }

    void setAa(boolean on) {
        aa = on;
    }

    void setLineWidth(double w) {
        lineWidth = w < 0 ? 0 : w;
    }

    void setLineCap(String s) {
        if ("round".equals(s)) shape.setStrokeCap(Paint.Cap.ROUND);
        else if ("square".equals(s)) shape.setStrokeCap(Paint.Cap.SQUARE);
        else shape.setStrokeCap(Paint.Cap.BUTT);
    }

    void setLineJoin(String s) {
        if ("round".equals(s)) shape.setStrokeJoin(Paint.Join.ROUND);
        else if ("bevel".equals(s)) shape.setStrokeJoin(Paint.Join.BEVEL);
        else shape.setStrokeJoin(Paint.Join.MITER);
    }

    void setMiterLimit(double m) {
        shape.setStrokeMiter((float) (m < 1 ? 1 : m));
    }

    /** Norns blend operators use BlendMode on Android 10+; legacy PorterDuff fallback below API 29. */
    boolean setBlendMode(String mode) {
        String m = mode == null ? "over" : mode.toLowerCase(java.util.Locale.US);
        BlendMode modern;
        PorterDuff.Mode legacy = null;
        switch (m) {
            case "over": case "normal": case "source_over":
                modern = BlendMode.SRC_OVER; legacy = PorterDuff.Mode.SRC_OVER; break;
            case "xor":
                modern = BlendMode.XOR; legacy = PorterDuff.Mode.XOR; break;
            case "add": case "plus":
                modern = BlendMode.PLUS; legacy = PorterDuff.Mode.ADD; break;
            case "multiply":
                modern = BlendMode.MULTIPLY; legacy = PorterDuff.Mode.MULTIPLY; break;
            case "screen":
                modern = BlendMode.SCREEN; legacy = PorterDuff.Mode.SCREEN; break;
            case "overlay":
                modern = BlendMode.OVERLAY; legacy = PorterDuff.Mode.OVERLAY; break;
            case "darken":
                modern = BlendMode.DARKEN; legacy = PorterDuff.Mode.DARKEN; break;
            case "lighten":
                modern = BlendMode.LIGHTEN; legacy = PorterDuff.Mode.LIGHTEN; break;
            case "color_dodge": case "dodge":
                modern = BlendMode.COLOR_DODGE; break;
            case "color_burn": case "burn":
                modern = BlendMode.COLOR_BURN; break;
            case "hard_light":
                modern = BlendMode.HARD_LIGHT; break;
            case "soft_light":
                modern = BlendMode.SOFT_LIGHT; break;
            case "difference":
                modern = BlendMode.DIFFERENCE; break;
            case "exclusion":
                modern = BlendMode.EXCLUSION; break;
            case "replace": case "src":
                modern = BlendMode.SRC; legacy = PorterDuff.Mode.SRC; break;
            default:
                return false;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            shape.setXfermode(null);
            textPaint.setXfermode(null);
            shape.setBlendMode(modern);
            textPaint.setBlendMode(modern);
            return true;
        }
        if (legacy == null) return false;
        shape.setXfermode(new PorterDuffXfermode(legacy));
        textPaint.setXfermode(new PorterDuffXfermode(legacy));
        return true;
    }

    void setFontFace(int f) {
        fontFace = Math.max(1, Math.min(faces.length, f));
    }

    void setFontSize(double s) {
        fontSize = (float) (s < 1 ? 1 : s);
    }

    void save() {
        bc.save();
        saveDepth++;
    }

    void restore() {
        if (saveDepth > 0) {
            bc.restore();
            saveDepth--;
        }
    }

    void translate(double x, double y) {
        bc.translate((float) x, (float) y);
    }

    void rotate(double radians) {
        bc.rotate((float) Math.toDegrees(radians));
    }

    // ------------------------------------------------------------------ caminho

    void move(double x, double y) {
        path.moveTo((float) x, (float) y);
        cx = x;
        cy = y;
        subX = x;
        subY = y;
        hasPoint = true;
    }

    void line(double x, double y) {
        if (!hasPoint) {
            move(x, y);
            return;
        }
        path.lineTo((float) x, (float) y);
        cx = x;
        cy = y;
    }

    void moveRel(double dx, double dy) {
        move(cx + dx, cy + dy);
    }

    void lineRel(double dx, double dy) {
        line(cx + dx, cy + dy);
    }

    void curve(double x1, double y1, double x2, double y2, double x3, double y3) {
        if (!hasPoint) move(x1, y1);
        path.cubicTo((float) x1, (float) y1, (float) x2, (float) y2, (float) x3, (float) y3);
        cx = x3;
        cy = y3;
    }

    void curveRel(double dx1, double dy1, double dx2, double dy2, double dx3, double dy3) {
        curve(cx + dx1, cy + dy1, cx + dx2, cy + dy2, cx + dx3, cy + dy3);
    }

    void arc(double x, double y, double r, double a1, double a2) {
        double end = a2;
        while (end < a1) end += 2.0 * Math.PI;
        double sweep = Math.toDegrees(end - a1);
        if (sweep >= 359.99) {
            path.addCircle((float) x, (float) y, (float) r, Path.Direction.CW);
            cx = x + r * Math.cos(a1);
            cy = y + r * Math.sin(a1);
        } else {
            oval.set((float) (x - r), (float) (y - r), (float) (x + r), (float) (y + r));
            path.arcTo(oval, (float) Math.toDegrees(a1), (float) sweep, false);
            cx = x + r * Math.cos(end);
            cy = y + r * Math.sin(end);
        }
        hasPoint = true;
    }

    void circle(double x, double y, double r) {
        path.addCircle((float) x, (float) y, (float) r, Path.Direction.CW);
        cx = x + r;
        cy = y;
        subX = cx;
        subY = cy;
        hasPoint = true;
    }

    void rect(double x, double y, double w, double h) {
        float l = (float) Math.min(x, x + w);
        float t = (float) Math.min(y, y + h);
        float r = (float) Math.max(x, x + w);
        float b = (float) Math.max(y, y + h);
        path.addRect(l, t, r, b, Path.Direction.CW);
        cx = x;
        cy = y;
        subX = x;
        subY = y;
        hasPoint = true;
    }

    void pixel(double x, double y) {
        rect(x, y, 1.0, 1.0);
    }

    /** Escreve um pixel imediatamente, sem exigir stroke()/fill(). */
    void poke(double x, double y, int lvl) {
        Paint p = new Paint();
        p.setColor(color(lvl));
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(false);
        bc.drawRect((float) x, (float) y, (float) x + 1f, (float) y + 1f, p);
    }

    /** Lê o nível de cinza do pixel lógico (0-15) no buffer de desenho. */
    int peek(double x, double y) {
        if (!finite(x) || !finite(y)) return 0;
        int px = (int) Math.floor(x * SCALE + SCALE / 2f);
        int py = (int) Math.floor(y * SCALE + SCALE / 2f);
        if (px < 0 || py < 0 || px >= back.getWidth() || py >= back.getHeight()) return 0;
        int c = back.getPixel(px, py);
        int gray = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
        return Math.max(0, Math.min(15, Math.round(gray * 15f / 255f)));
    }

    void close() {
        if (!hasPoint) return;
        path.close();
        cx = subX;
        cy = subY;
    }

    void stroke() {
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth((float) lineWidth);
        shape.setColor(color(level));
        shape.setAntiAlias(aa);
        bc.drawPath(path, shape);
        path.reset();
        hasPoint = false;
    }

    void fill() {
        shape.setStyle(Paint.Style.FILL);
        shape.setColor(color(level));
        shape.setAntiAlias(aa);
        bc.drawPath(path, shape);
        path.reset();
        hasPoint = false;
    }

    // ------------------------------------------------------------------ texto

    private void prepareText() {
        textPaint.setColor(color(level));
        textPaint.setTextSize(fontSize * textScale);
        int idx = Math.max(0, Math.min(faces.length - 1, fontFace - 1));
        textPaint.setTypeface(faces[idx]);
    }

    /** Escala da fonte do display (0,6–1,2), ajustável pelo usuário na aba NORNS. */
    private volatile float textScale = 1f;

    void setTextScale(float scale) {
        textScale = Math.max(0.6f, Math.min(1.2f, scale));
    }

    float getTextScale() { return textScale; }

    double textExtents(String s) {
        prepareText();
        return textPaint.measureText(s);
    }

    void text(String s, int align) {
        prepareText();
        float w = textPaint.measureText(s);
        float x = (float) cx;
        if (align == 1) x -= w;
        else if (align == 2) x -= w / 2f;
        bc.drawText(s, x, (float) cy, textPaint);
        if (align == 0) cx += w;
    }
    /** Draw left-aligned text, trimming trailing code points until it fits the requested width. */
    void textTrim(String value, double maxWidth) {
        prepareText();
        String text = value == null ? "" : value;
        float limit = Double.isNaN(maxWidth) || maxWidth < 0.0
                ? 0f : (float) Math.min(Float.MAX_VALUE, maxWidth);
        while (!text.isEmpty() && textPaint.measureText(text) > limit) {
            int end = text.offsetByCodePoints(text.length(), -1);
            text = text.substring(0, end);
        }
        float width = textPaint.measureText(text);
        bc.drawText(text, (float) cx, (float) cy, textPaint);
        cx += width;
    }

    /** Draw left-aligned text at an explicit position, rotating around its baseline origin. */
    void textRotate(double x, double y, String value, double degrees) {
        if (!finite(x) || !finite(y) || !finite(degrees)) return;
        prepareText();
        bc.save();
        try {
            bc.rotate((float) degrees, (float) x, (float) y);
            bc.drawText(value == null ? "" : value, (float) x, (float) y, textPaint);
        } finally {
            bc.restore();
        }
    }

    /** Draw centered text at an explicit position, rotating around the center baseline. */
    void textCenterRotate(double x, double y, String value, double degrees) {
        if (!finite(x) || !finite(y) || !finite(degrees)) return;
        prepareText();
        String text = value == null ? "" : value;
        float width = textPaint.measureText(text);
        bc.save();
        try {
            bc.rotate((float) degrees, (float) x, (float) y);
            bc.drawText(text, (float) x - width / 2f, (float) y, textPaint);
        } finally {
            bc.restore();
        }
    }

    double currentX() {
        return cx;
    }

    double currentY() {
        return cy;
    }

    /** Export norns-compatible screenshot: 4x framebuffer with a 64px black border. */
    boolean exportScreenshot(File target) {
        if (target == null) return false;
        Bitmap export = null;
        FileOutputStream output = null;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            export = Bitmap.createBitmap(W * SCALE + 128, H * SCALE + 128, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(export);
            canvas.drawColor(Color.BLACK);
            synchronized (lock) {
                canvas.drawBitmap(front, 64f, 64f, null);
            }
            output = new FileOutputStream(target);
            if (!export.compress(Bitmap.CompressFormat.PNG, 100, output)) return false;
            output.flush();
            return target.isFile() && target.length() > 0;
        } catch (IOException | RuntimeException error) {
            AppLog.e("Screen export_screenshot: " + error.getMessage());
            return false;
        } finally {
            Dust.closeQuietly(output);
            if (export != null && !export.isRecycled()) export.recycle();
        }
    }

    /** Load a bounded PNG into a reusable logical-pixel image buffer. */
    Image loadPng(File source) {
        if (source == null || !source.isFile() || source.length() <= 0
                || source.length() > 16L * 1024L * 1024L) return null;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1
                    || bounds.outWidth > 4096 || bounds.outHeight > 4096
                    || (long) bounds.outWidth * bounds.outHeight > 16_777_216L) return null;
            long estimatedBytes = (long) bounds.outWidth * bounds.outHeight * 4L;
            if (estimatedBytes > MAX_IMAGE_BYTES - imageBytes) return null;
            bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
            if (bitmap == null) return null;
            Image image = new Image(bitmap);
            return trackImage(image) ? image : null;
        } catch (RuntimeException error) {
            AppLog.e("Screen load_png: " + error.getMessage());
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return null;
        }
    }

    /** Create an image buffer in norns logical pixels (not the 4x screen backing scale). */
    Image createImage(int width, int height) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096
                || (long) width * height > 16_777_216L
                || (long) width * height * 4L > MAX_IMAGE_BYTES - imageBytes) return null;
        try {
            Image image = new Image(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
            return trackImage(image) ? image : null;
        } catch (RuntimeException error) {
            AppLog.e("Screen create_image: " + error.getMessage());
            return null;
        }
    }

    /** Register an image only when its actual allocation fits the script-wide budget. */
    private boolean trackImage(Image image) {
        if (!validImage(image)) return false;
        long bytes = image.bitmap.getByteCount();
        if (bytes <= 0L || bytes > MAX_IMAGE_BYTES - imageBytes) {
            image.bitmap.recycle();
            return false;
        }
        images.add(image);
        imageBytes += bytes;
        return true;
    }

    /** Release an image buffer explicitly; active draw_to targets cannot be freed. */
    boolean freeImage(Image image) {
        if (!validImage(image) || !images.contains(image)) return false;
        for (DrawState state : drawStates) if (state.target == image) return false;
        images.remove(image);
        imageBytes = Math.max(0L, imageBytes - image.bitmap.getByteCount());
        image.bitmap.recycle();
        return true;
    }

    boolean displayImage(Image image, double x, double y) {
        if (!validImage(image) || !finite(x) || !finite(y)) return false;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        bc.drawBitmap(image.bitmap, (float) x, (float) y, p);
        return true;
    }

    boolean displayImageRegion(Image image, int left, int top, int width, int height,
                               double x, double y) {
        if (!validImage(image) || !finite(x) || !finite(y)
                || left < 0 || top < 0 || width <= 0 || height <= 0
                || (long) left + width > image.bitmap.getWidth()
                || (long) top + height > image.bitmap.getHeight()) return false;
        Rect source = new Rect(left, top, left + width, top + height);
        RectF destination = new RectF((float) x, (float) y,
                (float) (x + width), (float) (y + height));
        bc.drawBitmap(image.bitmap, source, destination, new Paint(Paint.FILTER_BITMAP_FLAG));
        return true;
    }

    boolean beginDrawTo(Image image) {
        if (!validImage(image) || drawStates.size() >= 8) return false;
        drawStates.push(new DrawState(bc, new Path(path), cx, cy, subX, subY, hasPoint, saveDepth, image));
        bc = new Canvas(image.bitmap);
        path.reset();
        cx = cy = subX = subY = 0;
        hasPoint = false;
        saveDepth = 0;
        return true;
    }

    void endDrawTo() {
        if (drawStates.isEmpty()) return;
        while (saveDepth > 0) { bc.restore(); saveDepth--; }
        DrawState state = drawStates.pop();
        bc = state.canvas;
        path.set(state.path);
        cx = state.cx; cy = state.cy; subX = state.subX; subY = state.subY;
        hasPoint = state.hasPoint;
        saveDepth = state.saveDepth;
    }

    String peekRegion(int x, int y, int width, int height) {
        int w = Math.max(1, Math.min(W, width));
        int h = Math.max(1, Math.min(H, height));
        StringBuilder data = new StringBuilder(w * h);
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int px = x + col, py = y + row;
                data.append((char) (px < 0 || py < 0 || px >= W || py >= H ? 0 : peek(px, py)));
            }
        }
        return data.toString();
    }

    boolean pokeRegion(int x, int y, int width, int height, String values) {
        if (width < 1 || height < 1 || width > W || height > H
                || (long) width * height > W * H || values == null
                || values.length() < width * height) return false;
        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(false);
        int index = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++, index++) {
                int px = x + col, py = y + row;
                if (px < 0 || py < 0 || px >= W || py >= H) continue;
                p.setColor(color(values.charAt(index)));
                bc.drawRect(px, py, px + 1, py + 1, p);
            }
        }
        return true;
    }

    private static boolean validImage(Image image) {
        return image != null && image.bitmap != null && !image.bitmap.isRecycled();
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** Draw a PNG into the 128x64 back buffer at norns pixel coordinates. */
    boolean displayPng(File source, double x, double y) {
        if (source == null || !source.isFile() || source.length() <= 0
                || source.length() > 16L * 1024L * 1024L
                || Double.isNaN(x) || Double.isNaN(y)
                || Double.isInfinite(x) || Double.isInfinite(y)) return false;
        Bitmap image = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1
                    || bounds.outWidth > 4096 || bounds.outHeight > 4096) return false;
            image = BitmapFactory.decodeFile(source.getAbsolutePath());
            if (image == null) return false;
            Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            bc.drawBitmap(image, (float) x, (float) y, imagePaint);
            return true;
        } catch (RuntimeException error) {
            AppLog.e("Screen display_png: " + error.getMessage());
            return false;
        } finally {
            if (image != null && !image.isRecycled()) image.recycle();
        }
    }

    // ------------------------------------------------------------------ interface

    void drawFront(Canvas c, RectF dst, Paint p) {
        synchronized (lock) {
            c.drawBitmap(front, srcRect, dst, p);
        }
    }
}
