package com.smartprompter.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * 极致轻量原生 Canvas 提词直绘引擎：
 * 零 RecyclerView / 零 TextView / 零 StaticLayout / onDraw 零对象分配。
 * 每帧仅绘制当前屏幕可见的几行文字，彻底消除长文本卡顿、CPU 飙高与渲染内存溢出。
 */
public class PrompterCanvasView extends View {

    public interface OnPrompterInteractionListener {
        void onSingleTap();
        void onLongPressOffset(int charOffset);
        void onUserScrollFinished();
        void onReachBottom();
    }

    private static class Run {
        final String text;
        final boolean isBold;
        final float width;

        Run(String text, boolean isBold, float width) {
            this.text = text;
            this.isBold = isBold;
            this.width = width;
        }
    }

    private static class LineItem {
        final int startOffset;
        final int endOffset;
        final List<Run> runs;
        final float totalWidth;
        final String plainText;

        LineItem(int startOffset, int endOffset, List<Run> runs, float totalWidth, String plainText) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.runs = runs;
            this.totalWidth = totalWidth;
            this.plainText = plainText;
        }
    }

    private final Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<LineItem> lines = new ArrayList<>();

    private String rawScript = "";
    private float fontSizeSp = 48f;
    private float lineSpacingMult = 1.15f;
    private boolean isAlignLeft = false;
    private boolean isMirror = false;
    private int highlightedIndex = 0;

    private float scrollY = 0f;
    private float maxScrollY = 0f;
    private float lineHeightPx = 80f;
    private float topPadPx = 0f;
    private float bottomPadPx = 0f;
    private float horizPadPx = 0f;
    private float baselineOffsetPx = 0f;

    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private OnPrompterInteractionListener interactionListener;

    public PrompterCanvasView(Context context) {
        this(context, null);
    }

    public PrompterCanvasView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PrompterCanvasView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        normalPaint.setTypeface(Typeface.DEFAULT);
        boldPaint.setTypeface(Typeface.DEFAULT_BOLD);
        setLayerType(LAYER_TYPE_NONE, null);

        float density = context.getResources().getDisplayMetrics().density;
        horizPadPx = 28f * density;
        topPadPx = 200f * density;
        bottomPadPx = 450f * density;

        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (interactionListener != null) {
                    interactionListener.onSingleTap();
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                float dy = isMirror ? -distanceY : distanceY;
                scrollByDelta(dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float vy = isMirror ? velocityY : -velocityY;
                scroller.fling(0, Math.round(scrollY), 0, Math.round(vy), 0, 0, 0, Math.round(maxScrollY));
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (interactionListener != null && !lines.isEmpty()) {
                    float touchY = isMirror ? (getHeight() - e.getY()) : e.getY();
                    int idx = (int) ((scrollY + touchY - topPadPx) / lineHeightPx);
                    idx = Math.max(0, Math.min(lines.size() - 1, idx));
                    interactionListener.onLongPressOffset(lines.get(idx).startOffset);
                }
            }
        });
    }

    public void setInteractionListener(OnPrompterInteractionListener listener) {
        this.interactionListener = listener;
    }

    public void setScript(String script) {
        this.rawScript = script == null ? "" : script;
        this.highlightedIndex = 0;
        this.scrollY = 0f;
        if (!scroller.isFinished()) scroller.abortAnimation();
        relayoutLines();
        invalidate();
    }

    public void setStyleParams(float fontSizeSp, float lineSpacingMult, boolean alignLeft, boolean mirror) {
        boolean needRelayout = (this.fontSizeSp != fontSizeSp) || (this.lineSpacingMult != lineSpacingMult);
        this.fontSizeSp = fontSizeSp;
        this.lineSpacingMult = lineSpacingMult;
        this.isAlignLeft = alignLeft;
        this.isMirror = mirror;
        if (needRelayout) {
            relayoutLines();
        }
        invalidate();
    }

    public void setHighlightedIndex(int index) {
        this.highlightedIndex = Math.max(0, index);
        invalidate();
    }

    public void scrollToCharOffset(int offset) {
        if (lines.isEmpty()) return;
        int lo = 0, hi = lines.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (offset < lines.get(mid).endOffset) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        float targetY = lo * lineHeightPx;
        scrollY = Math.max(0f, Math.min(maxScrollY, targetY));
        invalidate();
    }

    public int getFocusLineCharOffset() {
        if (lines.isEmpty()) return 0;
        float focusY = getHeight() * 0.28f;
        int idx = (int) ((scrollY + focusY - topPadPx) / lineHeightPx);
        idx = Math.max(0, Math.min(lines.size() - 1, idx));
        return lines.get(idx).startOffset;
    }

    public void resetToTop() {
        this.highlightedIndex = 0;
        this.scrollY = 0f;
        if (!scroller.isFinished()) scroller.abortAnimation();
        invalidate();
    }

    public boolean scrollByDelta(float dy) {
        if (maxScrollY <= 0f) return false;
        float next = scrollY + dy;
        if (next >= maxScrollY) {
            scrollY = maxScrollY;
            invalidate();
            if (dy > 0 && interactionListener != null) {
                interactionListener.onReachBottom();
            }
            return false;
        }
        scrollY = Math.max(0f, next);
        invalidate();
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && w > 0) {
            relayoutLines();
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = Math.max(0f, Math.min(maxScrollY, scroller.getCurrY()));
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = gestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && interactionListener != null) {
            interactionListener.onUserScrollFinished();
        }
        return handled || super.onTouchEvent(event);
    }

    private void relayoutLines() {
        lines.clear();
        float textPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, fontSizeSp, getResources().getDisplayMetrics());
        normalPaint.setTextSize(textPx);
        boldPaint.setTextSize(textPx);

        Paint.FontMetrics fm = normalPaint.getFontMetrics();
        float rawHeight = fm.descent - fm.ascent;
        lineHeightPx = Math.max(20f, rawHeight * lineSpacingMult);
        baselineOffsetPx = -fm.ascent;

        int viewW = getWidth();
        if (viewW <= 0) {
            viewW = getResources().getDisplayMetrics().widthPixels;
        }
        float maxLineW = Math.max(120f, viewW - horizPadPx * 2f);

        if (rawScript == null || rawScript.isEmpty()) {
            maxScrollY = 0f;
            return;
        }

        // 解析全文为带 bold 标记的字符流，同时保留原始偏移映射，只做一次 O(N) 线性扫描
        int len = rawScript.length();
        int i = 0;
        while (i < len) {
            int nl = rawScript.indexOf('\n', i);
            int segEnd = (nl == -1) ? len : nl;

            if (segEnd == i) {
                // 空行
                lines.add(new LineItem(i, Math.min(len, i + 1), new ArrayList<>(), 0f, ""));
                i = (nl == -1) ? len : nl + 1;
                continue;
            }

            // 将这一段按 **...** 拆成若干视觉片段，再按 maxLineW 断行
            List<Run> segRuns = parseRunsForSegment(rawScript, i, segEnd);
            wrapRunsIntoLines(segRuns, i, (nl == -1) ? len : nl + 1, maxLineW);
            i = (nl == -1) ? len : nl + 1;
        }

        float totalContentHeight = topPadPx + lines.size() * lineHeightPx + bottomPadPx;
        int viewH = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        maxScrollY = Math.max(0f, totalContentHeight - viewH);
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    private List<Run> parseRunsForSegment(String s, int start, int end) {
        List<Run> out = new ArrayList<>();
        int idx = start;
        while (idx < end) {
            int open = s.indexOf("**", idx);
            if (open < 0 || open >= end) {
                String part = s.substring(idx, end);
                out.add(new Run(part, false, normalPaint.measureText(part)));
                break;
            }
            if (open > idx) {
                String pre = s.substring(idx, open);
                out.add(new Run(pre, false, normalPaint.measureText(pre)));
            }
            int close = s.indexOf("**", open + 2);
            if (close < 0 || close >= end) {
                String rest = s.substring(open, end);
                out.add(new Run(rest, false, normalPaint.measureText(rest)));
                break;
            }
            String boldPart = s.substring(open + 2, close);
            if (!boldPart.isEmpty()) {
                out.add(new Run(boldPart, true, boldPaint.measureText(boldPart)));
            }
            idx = close + 2;
        }
        return out;
    }

    private void wrapRunsIntoLines(List<Run> segRuns, int segStartOffset, int segEndOffset, float maxLineW) {
        List<Run> curLineRuns = new ArrayList<>();
        StringBuilder curPlain = new StringBuilder();
        float curW = 0f;
        int curStartOff = segStartOffset;
        int charCursor = segStartOffset;

        for (Run r : segRuns) {
            Paint p = r.isBold ? boldPaint : normalPaint;
            String txt = r.text;
            int pos = 0;
            while (pos < txt.length()) {
                float avail = maxLineW - curW;
                if (avail <= 4f && !curLineRuns.isEmpty()) {
                    lines.add(new LineItem(curStartOff, charCursor, curLineRuns, curW, curPlain.toString()));
                    curLineRuns = new ArrayList<>();
                    curPlain.setLength(0);
                    curW = 0f;
                    curStartOff = charCursor;
                    avail = maxLineW;
                }
                int fit = p.breakText(txt, pos, txt.length(), true, avail, null);
                if (fit <= 0) fit = 1;
                String sub = txt.substring(pos, pos + fit);
                float subW = p.measureText(sub);
                curLineRuns.add(new Run(sub, r.isBold, subW));
                curPlain.append(sub);
                curW += subW;
                pos += fit;
                charCursor += fit;
            }
        }
        lines.add(new LineItem(curStartOff, segEndOffset, curLineRuns, curW, curPlain.toString()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lines.isEmpty()) return;

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        if (isMirror) {
            canvas.save();
            canvas.scale(-1f, -1f, viewW / 2f, viewH / 2f);
        }

        int firstLine = Math.max(0, (int) Math.floor((scrollY - topPadPx) / lineHeightPx) - 1);
        int lastLine = Math.min(lines.size() - 1, (int) Math.ceil((scrollY + viewH - topPadPx) / lineHeightPx) + 1);

        int yellow = Color.parseColor("#FACC15");
        int cyan = Color.parseColor("#22D3EE");

        for (int idx = firstLine; idx <= lastLine; idx++) {
            LineItem line = lines.get(idx);
            if (line.runs.isEmpty()) continue;

            float y = topPadPx + idx * lineHeightPx - scrollY + baselineOffsetPx;
            float x = isAlignLeft ? horizPadPx : Math.max(horizPadPx, (viewW - line.totalWidth) / 2f);

            boolean fullyRead = highlightedIndex >= line.endOffset;
            boolean fullyUnread = highlightedIndex <= line.startOffset;

            if (fullyRead || fullyUnread) {
                for (int r = 0; r < line.runs.size(); r++) {
                    Run run = line.runs.get(r);
                    Paint p = run.isBold ? boldPaint : normalPaint;
                    p.setColor(fullyRead ? yellow : (run.isBold ? cyan : Color.WHITE));
                    canvas.drawText(run.text, x, y, p);
                    x += run.width;
                }
            } else {
                // 部分已读（逐字高亮过渡）
                int localReadLimit = Math.max(0, highlightedIndex - line.startOffset);
                int drawnChars = 0;
                for (int r = 0; r < line.runs.size(); r++) {
                    Run run = line.runs.get(r);
                    Paint p = run.isBold ? boldPaint : normalPaint;
                    int rLen = run.text.length();
                    if (drawnChars + rLen <= localReadLimit) {
                        p.setColor(yellow);
                        canvas.drawText(run.text, x, y, p);
                    } else if (drawnChars >= localReadLimit) {
                        p.setColor(run.isBold ? cyan : Color.WHITE);
                        canvas.drawText(run.text, x, y, p);
                    } else {
                        int split = localReadLimit - drawnChars;
                        String readPart = run.text.substring(0, split);
                        String unreadPart = run.text.substring(split);
                        p.setColor(yellow);
                        canvas.drawText(readPart, x, y, p);
                        float rw = p.measureText(readPart);
                        p.setColor(run.isBold ? cyan : Color.WHITE);
                        canvas.drawText(unreadPart, x + rw, y, p);
                    }
                    x += run.width;
                    drawnChars += rLen;
                }
            }
        }

        if (isMirror) {
            canvas.restore();
        }
    }
}
