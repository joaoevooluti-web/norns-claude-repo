package org.nornsmobile.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;

import java.util.ArrayList;

/** Editor de código: fonte monoespaçada, números de linha, realce de Lua, desfazer/refazer. */
final class CodeEditText extends EditText {

    private static final int MAX_HIGHLIGHT_CHARS = 60000;
    private static final int MAX_UNDO_CHARS = 200000;
    private static final int MAX_UNDO_STEPS = 40;

    private static final int C_PLAIN = 0xFFE1E5E9;
    private static final int C_COMMENT = 0xFF6B7A8C;
    private static final int C_STRING = 0xFFC3E88D;
    private static final int C_NUMBER = 0xFFF78C6C;
    private static final int C_KEYWORD = 0xFFC792EA;
    private static final int C_API = 0xFF82AAFF;

    /** Span próprio para podermos removê-lo sem tocar em outros spans do texto. */
    private static final class Hl extends ForegroundColorSpan {
        Hl(int color) {
            super(color);
        }
    }

    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final Rect clip = new Rect();
    private int gutterDigits = 2;
    private int gutterWidth = 0;
    private boolean restoring = false;
    private Runnable changeListener;

    private final ArrayList<String> undoStack = new ArrayList<String>();
    private final ArrayList<String> redoStack = new ArrayList<String>();
    private String lastSnapshot = "";

    private final Runnable highlightTask = new Runnable() {
        @Override
        public void run() {
            rehighlight();
            updateGutterDigits();
        }
    };

    private final Runnable snapshotTask = new Runnable() {
        @Override
        public void run() {
            takeSnapshot();
        }
    };

    CodeEditText(Context c) {
        super(c);
        setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        setTextColor(C_PLAIN);
        setBackgroundColor(Theme.BG);
        setGravity(Gravity.TOP | Gravity.START);
        setHorizontallyScrolling(false);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        setLineSpacing(0f, 1.1f);
        setHighlightColor(0x663E6FB0);
        setCursorVisible(true);

        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setColor(Theme.MUTED);
        gutterPaint.setTextAlign(Paint.Align.RIGHT);
        dividerPaint.setColor(Theme.LINE);
        applyPadding();

        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (restoring) return;
                removeCallbacks(highlightTask);
                postDelayed(highlightTask, 350);
                removeCallbacks(snapshotTask);
                postDelayed(snapshotTask, 700);
                Runnable l = changeListener;
                if (l != null) l.run();
            }
        });
    }

    void setOnChange(Runnable r) {
        changeListener = r;
    }

    private void applyPadding() {
        gutterPaint.setTextSize(getTextSize() * 0.85f);
        float cw = gutterPaint.measureText("0");
        gutterWidth = Math.round(cw * gutterDigits) + Theme.dp(getContext(), 10);
        setPadding(gutterWidth + Theme.dp(getContext(), 8), Theme.dp(getContext(), 8),
                Theme.dp(getContext(), 8), Theme.dp(getContext(), 48));
    }

    private void updateGutterDigits() {
        Editable e = getText();
        int lines = 1;
        for (int i = 0; i < e.length(); i++) if (e.charAt(i) == '\n') lines++;
        int digits = Math.max(2, String.valueOf(lines).length());
        if (digits != gutterDigits) {
            gutterDigits = digits;
            applyPadding();
        }
    }

    // ------------------------------------------------------------------ carregar texto

    void setCode(String text) {
        removeCallbacks(highlightTask);
        removeCallbacks(snapshotTask);
        restoring = true;
        setText(text);
        restoring = false;
        undoStack.clear();
        redoStack.clear();
        lastSnapshot = text.length() <= MAX_UNDO_CHARS ? text : "";
        setSelection(0);
        updateGutterDigits();
        rehighlight();
    }

    String getCode() {
        return getText().toString();
    }

    // ------------------------------------------------------------------ realce

    private static int colorFor(int type) {
        switch (type) {
            case LuaHighlighter.COMMENT:
                return C_COMMENT;
            case LuaHighlighter.STRING:
                return C_STRING;
            case LuaHighlighter.NUMBER:
                return C_NUMBER;
            case LuaHighlighter.KEYWORD:
                return C_KEYWORD;
            case LuaHighlighter.API:
                return C_API;
            default:
                return C_PLAIN;
        }
    }

    void rehighlight() {
        Editable e = getText();
        Hl[] old = e.getSpans(0, e.length(), Hl.class);
        for (int i = 0; i < old.length; i++) e.removeSpan(old[i]);
        if (e.length() > MAX_HIGHLIGHT_CHARS) return;
        LuaHighlighter.Tokens t = LuaHighlighter.scan(e);
        for (int i = 0; i < t.n; i++) {
            e.setSpan(new Hl(colorFor(t.data[3 * i + 2])), t.data[3 * i], t.data[3 * i + 1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ------------------------------------------------------------------ desfazer / refazer

    private void takeSnapshot() {
        if (getText().length() > MAX_UNDO_CHARS) return;
        String now = getText().toString();
        if (now.equals(lastSnapshot)) return;
        undoStack.add(lastSnapshot);
        while (undoStack.size() > MAX_UNDO_STEPS) undoStack.remove(0);
        lastSnapshot = now;
        redoStack.clear();
    }

    private void restoreText(String s) {
        int pos = Math.min(getSelectionStart() < 0 ? 0 : getSelectionStart(), s.length());
        restoring = true;
        setText(s);
        restoring = false;
        setSelection(pos);
        rehighlight();
        updateGutterDigits();
        Runnable l = changeListener;
        if (l != null) l.run();
    }

    boolean undo() {
        removeCallbacks(snapshotTask);
        takeSnapshot();
        if (undoStack.isEmpty()) return false;
        redoStack.add(lastSnapshot);
        lastSnapshot = undoStack.remove(undoStack.size() - 1);
        restoreText(lastSnapshot);
        return true;
    }

    boolean redo() {
        if (redoStack.isEmpty()) return false;
        undoStack.add(lastSnapshot);
        lastSnapshot = redoStack.remove(redoStack.size() - 1);
        restoreText(lastSnapshot);
        return true;
    }

    // ------------------------------------------------------------------ edição

    void insertText(String s, int cursorBack) {
        Editable e = getText();
        int a = Math.max(0, getSelectionStart());
        int b = Math.max(0, getSelectionEnd());
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        e.replace(lo, hi, s);
        setSelection(Math.max(0, lo + s.length() - cursorBack));
    }

    /** Posiciona o cursor no início da linha (1-based). Retorna o deslocamento no texto. */
    int gotoLine(int line) {
        Editable e = getText();
        int off = 0;
        int cur = 1;
        while (cur < line && off < e.length()) {
            int nl = -1;
            for (int i = off; i < e.length(); i++) {
                if (e.charAt(i) == '\n') {
                    nl = i;
                    break;
                }
            }
            if (nl < 0) break;
            off = nl + 1;
            cur++;
        }
        setSelection(off);
        return off;
    }

    /** Procura a próxima ocorrência a partir do cursor (volta ao início se necessário). */
    int findNext(String q) {
        if (q == null || q.length() == 0) return -1;
        String text = getText().toString();
        int from = Math.max(0, getSelectionEnd());
        int idx = text.indexOf(q, from);
        if (idx < 0) idx = text.indexOf(q);
        if (idx >= 0) setSelection(idx, idx + q.length());
        return idx;
    }

    /** Coordenada Y (dentro deste view) do início da linha do deslocamento dado. */
    int yForOffset(int offset) {
        Layout l = getLayout();
        if (l == null) return 0;
        int line = l.getLineForOffset(Math.max(0, Math.min(offset, getText().length())));
        return l.getLineTop(line) + getExtendedPaddingTop();
    }

    // ------------------------------------------------------------------ números de linha

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            drawGutter(canvas);
        } catch (RuntimeException e) {
            // o editor continua funcionando mesmo sem os números
        }
        super.onDraw(canvas);
    }

    private void drawGutter(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) return;
        if (!canvas.getClipBounds(clip)) return;
        int off = getExtendedPaddingTop();
        int lineCount = layout.getLineCount();
        if (lineCount == 0) return;
        int first = layout.getLineForVertical(Math.max(0, clip.top - off));
        int last = layout.getLineForVertical(Math.max(0, clip.bottom - off));
        if (last >= lineCount) last = lineCount - 1;
        Editable text = getText();
        int start = layout.getLineStart(first);
        int para = 1;
        for (int i = 0; i < start && i < text.length(); i++) if (text.charAt(i) == '\n') para++;
        float x = gutterWidth;
        for (int line = first; line <= last; line++) {
            int ls = layout.getLineStart(line);
            boolean startsParagraph = ls == 0 || text.charAt(ls - 1) == '\n';
            if (line > first && startsParagraph) para++;
            if (startsParagraph) {
                canvas.drawText(String.valueOf(para), x, layout.getLineBaseline(line) + off, gutterPaint);
            }
        }
        canvas.drawRect(gutterWidth + Theme.dp(getContext(), 3), clip.top,
                gutterWidth + Theme.dp(getContext(), 3) + 1, clip.bottom, dividerPaint);
    }
}
