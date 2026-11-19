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
 * 原生 Canvas 直绘提词引擎（v57 增强版）：
 * 1. 零 RecyclerView / 零 TextView，彻底避免长文本内存异常与卡顿
 * 2. AI 跟读平滑颜色渐变 + 平滑行滚动动画
 * 3. 精确返回当前屏幕可见字符区间，限制 AI 跟读只匹配屏幕内未读台词
 * 4. 自动识别 Markdown # 标题，支持顶部进度条实时同步与按标题快速跳转
 */
public class PrompterCanvasView extends View {

    public interface OnPrompterInteractionListener {
        void onSingleTap();
        void onLongPressOffset(int charOffset);
        void onUserScrollFinished();
        void onReachBottom();
        void onScrollProgress(float progress01, String currentChapterTitle);
    }

    public static class Chapter {
        public final String title;
        public final int charOffset;
        public final float scrollY;

        public Chapter(String title, int charOffset, float scrollY) {
            this.title = title;
            this.charOffset = charOffset;
            this.scrollY = scrollY;
        }
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
        final boolean isHeading;

        LineItem(int startOffset, int endOffset, List<Run> runs, float totalWidth, boolean isHeading) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.runs = runs;
            this.totalWidth = totalWidth;
            this.isHeading = isHeading;
        }
    }

    private final Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<LineItem> lines = new ArrayList<>();
    private final List<Chapter> chapters = new ArrayList<>();

    private String rawScript = "";
    private float fontSizeSp = 48f;
    private float lineSpacingMult = 1.15f;
    private boolean isAlignLeft = false;
    private boolean isMirror = false;

    private int targetHighlightedIndex = 0;
    private float animatedHighlightIndex = 0f;

    private float scrollY = 0f;
    private float scrollSubPixelRemainder = 0f;
    private int lastNotifiedPermille = -1;
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
        this.targetHighlightedIndex = 0;
        this.animatedHighlightIndex = 0f;
        this.scrollY = 0f;
        this.scrollSubPixelRemainder = 0f;
        this.lastNotifiedPermille = -1;
        if (!scroller.isFinished()) scroller.abortAnimation();
        relayoutLines();
        notifyProgress();
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

    public void setHighlightedIndex(int index, boolean smooth) {
        this.targetHighlightedIndex = Math.max(0, index);
        if (!smooth) {
            this.animatedHighlightIndex = this.targetHighlightedIndex;
        }
        invalidate();
    }

    public void smoothScrollToCharOffset(int offset) {
        if (lines.isEmpty()) return;
        int lineIdx = findLineForOffset(offset);
        float targetY = Math.max(0f, Math.min(maxScrollY, lineIdx * lineHeightPx));
        int startY = Math.round(scrollY);
        int dy = Math.round(targetY - scrollY);
        if (Math.abs(dy) < 2) {
            scrollY = targetY;
            notifyProgress();
            invalidate();
            return;
        }
        if (!scroller.isFinished()) scroller.abortAnimation();
        scroller.startScroll(0, startY, 0, dy, 320);
        postInvalidateOnAnimation();
    }

    public int[] getVisibleCharRange() {
        if (lines.isEmpty()) return new int[]{0, 0};
        int viewH = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        int first = Math.max(0, (int) Math.floor((scrollY - topPadPx) / lineHeightPx));
        int last = Math.min(lines.size() - 1, (int) Math.ceil((scrollY + viewH - topPadPx) / lineHeightPx));
        if (first > last) first = last;
        return new int[]{lines.get(first).startOffset, lines.get(last).endOffset};
    }

    public int getFocusLineCharOffset() {
        if (lines.isEmpty()) return 0;
        float focusY = getHeight() * 0.28f;
        int idx = (int) ((scrollY + focusY - topPadPx) / lineHeightPx);
        idx = Math.max(0, Math.min(lines.size() - 1, idx));
        return lines.get(idx).startOffset;
    }

    public float getScrollYPos() {
        return scrollY;
    }

    public void restoreState(float savedScrollY, int savedHighlightIdx) {
        this.scrollY = Math.max(0f, Math.min(maxScrollY, savedScrollY));
        this.targetHighlightedIndex = Math.max(0, savedHighlightIdx);
        this.animatedHighlightIndex = this.targetHighlightedIndex;
        notifyProgress();
        invalidate();
    }

    public List<Chapter> getChapters() {
        return chapters;
    }

    public String getChapterTitleAtProgress(float progress01) {
        if (chapters.isEmpty()) return "";
        float targetY = Math.max(0f, Math.min(maxScrollY, progress01 * maxScrollY));
        String title = "";
        for (Chapter ch : chapters) {
            if (ch.scrollY <= targetY + lineHeightPx * 0.5f) {
                title = ch.title;
            } else {
                break;
            }
        }
        return title;
    }

    public void seekToProgress(float progress01, boolean snapToNearestChapter) {
        float targetY = Math.max(0f, Math.min(maxScrollY, progress01 * maxScrollY));
        if (snapToNearestChapter && !chapters.isEmpty() && maxScrollY > 0f) {
            float snapThreshold = Math.max(lineHeightPx * 2f, maxScrollY * 0.035f);
            Chapter best = null;
            float bestDist = Float.MAX_VALUE;
            for (Chapter ch : chapters) {
                float d = Math.abs(ch.scrollY - targetY);
                if (d < bestDist && d <= snapThreshold) {
                    bestDist = d;
                    best = ch;
                }
            }
            if (best != null) {
                targetY = Math.max(0f, Math.min(maxScrollY, best.scrollY));
            }
        }
        if (!scroller.isFinished()) scroller.abortAnimation();
        scrollY = targetY;
        notifyProgress();
        invalidate();
    }

    public void resetToTop() {
        this.targetHighlightedIndex = 0;
        this.animatedHighlightIndex = 0f;
        this.scrollY = 0f;
        this.scrollSubPixelRemainder = 0f;
        this.lastNotifiedPermille = -1;
        if (!scroller.isFinished()) scroller.abortAnimation();
        notifyProgress();
        invalidate();
    }

    public boolean scrollByDelta(float dy) {
        if (maxScrollY <= 0f) {
            if (dy > 0 && interactionListener != null) {
                interactionListener.onReachBottom();
            }
            return false;
        }
        scrollSubPixelRemainder += dy;
        int intDy = (int) scrollSubPixelRemainder;
        if (intDy == 0) {
            return true;
        }
        scrollSubPixelRemainder -= intDy;

        float next = scrollY + intDy;
        if (next >= maxScrollY) {
            scrollY = Math.round(maxScrollY);
            scrollSubPixelRemainder = 0f;
            notifyProgress();
            invalidate();
            if (intDy > 0 && interactionListener != null) {
                interactionListener.onReachBottom();
            }
            return false;
        }
        scrollY = Math.max(0f, Math.round(next));
        notifyProgress();
        invalidate();
        return true;
    }

    private int findLineForOffset(int offset) {
        int lo = 0, hi = lines.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (offset < lines.get(mid).endOffset) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private void notifyProgress() {
        if (interactionListener == null) return;
        float p = (maxScrollY > 0f) ? (scrollY / maxScrollY) : 0f;
        p = Math.max(0f, Math.min(1f, p));
        int permille = Math.round(p * 1000f);
        if (permille == lastNotifiedPermille) return;
        lastNotifiedPermille = permille;
        interactionListener.onScrollProgress(p, getChapterTitleAtProgress(p));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && w > 0) {
            float prevRatio = (maxScrollY > 0f) ? (scrollY / maxScrollY) : 0f;
            relayoutLines();
            if (prevRatio > 0f && maxScrollY > 0f) {
                scrollY = Math.max(0f, Math.min(maxScrollY, prevRatio * maxScrollY));
            }
            notifyProgress();
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = Math.max(0f, Math.min(maxScrollY, scroller.getCurrY()));
            notifyProgress();
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
        chapters.clear();
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

        int len = rawScript.length();
        int i = 0;
        while (i < len) {
            int nl = rawScript.indexOf('\n', i);
            int segEnd = (nl == -1) ? len : nl;

            if (segEnd == i) {
                lines.add(new LineItem(i, Math.min(len, i + 1), new ArrayList<>(), 0f, false));
                i = (nl == -1) ? len : nl + 1;
                continue;
            }

            // 检测 Markdown 标题 (# 标题)
            int contentStart = i;
            boolean isHeading = false;
            int hashCount = 0;
            while (contentStart + hashCount < segEnd && rawScript.charAt(contentStart + hashCount) == '#') {
                hashCount++;
            }
            if (hashCount >= 1 && hashCount <= 6 && contentStart + hashCount < segEnd
                    && rawScript.charAt(contentStart + hashCount) == ' ') {
                isHeading = true;
                String headingTitle = rawScript.substring(contentStart + hashCount + 1, segEnd)
                        .replace("**", "").trim();
                float chapterY = lines.size() * lineHeightPx;
                chapters.add(new Chapter(headingTitle, i, chapterY));
                contentStart = contentStart + hashCount + 1;
            }

            List<Run> segRuns = parseRunsForSegment(rawScript, contentStart, segEnd, isHeading);
            wrapRunsIntoLines(segRuns, i, (nl == -1) ? len : nl + 1, maxLineW, isHeading);
            i = (nl == -1) ? len : nl + 1;
        }

        float totalContentHeight = topPadPx + lines.size() * lineHeightPx + bottomPadPx;
        int viewH = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        maxScrollY = Math.max(0f, totalContentHeight - viewH);
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    private List<Run> parseRunsForSegment(String s, int start, int end, boolean forceBold) {
        List<Run> out = new ArrayList<>();
        int idx = start;
        while (idx < end) {
            int open = s.indexOf("**", idx);
            if (open < 0 || open >= end) {
                String part = s.substring(idx, end);
                Paint p = forceBold ? boldPaint : normalPaint;
                out.add(new Run(part, forceBold, p.measureText(part)));
                break;
            }
            if (open > idx) {
                String pre = s.substring(idx, open);
                Paint p = forceBold ? boldPaint : normalPaint;
                out.add(new Run(pre, forceBold, p.measureText(pre)));
            }
            int close = s.indexOf("**", open + 2);
            if (close < 0 || close >= end) {
                String rest = s.substring(open, end);
                Paint p = forceBold ? boldPaint : normalPaint;
                out.add(new Run(rest, forceBold, p.measureText(rest)));
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

    private void wrapRunsIntoLines(List<Run> segRuns, int segStartOffset, int segEndOffset, float maxLineW, boolean isHeading) {
        List<Run> curLineRuns = new ArrayList<>();
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
                    lines.add(new LineItem(curStartOff, charCursor, curLineRuns, curW, isHeading));
                    curLineRuns = new ArrayList<>();
                    curW = 0f;
                    curStartOff = charCursor;
                    avail = maxLineW;
                }
                int fit = p.breakText(txt, pos, txt.length(), true, avail, null);
                if (fit <= 0) fit = 1;
                String sub = txt.substring(pos, pos + fit);
                float subW = p.measureText(sub);
                curLineRuns.add(new Run(sub, r.isBold, subW));
                curW += subW;
                pos += fit;
                charCursor += fit;
            }
        }
        lines.add(new LineItem(curStartOff, segEndOffset, curLineRuns, curW, isHeading));
    }

    private int blendColor(int cFrom, int cTo, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(cFrom) + (Color.alpha(cTo) - Color.alpha(cFrom)) * t);
        int r = Math.round(Color.red(cFrom) + (Color.red(cTo) - Color.red(cFrom)) * t);
        int g = Math.round(Color.green(cFrom) + (Color.green(cTo) - Color.green(cFrom)) * t);
        int b = Math.round(Color.blue(cFrom) + (Color.blue(cTo) - Color.blue(cFrom)) * t);
        return Color.argb(a, r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lines.isEmpty()) return;

        // 平滑推进动画游标（让 KTV 变色流畅过渡而不是生硬跳变）
        boolean needMoreAnim = false;
        float diff = targetHighlightedIndex - animatedHighlightIndex;
        if (Math.abs(diff) > 0.08f) {
            animatedHighlightIndex += diff * 0.28f;
            needMoreAnim = true;
        } else {
            animatedHighlightIndex = targetHighlightedIndex;
        }

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
        int headingColor = Color.parseColor("#38BDF8");

        for (int idx = firstLine; idx <= lastLine; idx++) {
            LineItem line = lines.get(idx);
            if (line.runs.isEmpty()) continue;

            float y = topPadPx + idx * lineHeightPx - scrollY + baselineOffsetPx;
            float x = isAlignLeft ? horizPadPx : Math.max(horizPadPx, (viewW - line.totalWidth) / 2f);

            boolean fullyRead = animatedHighlightIndex >= line.endOffset;
            boolean fullyUnread = animatedHighlightIndex <= line.startOffset;

            if (fullyRead || fullyUnread) {
                for (int r = 0; r < line.runs.size(); r++) {
                    Run run = line.runs.get(r);
                    Paint p = run.isBold ? boldPaint : normalPaint;
                    int unreadCol = line.isHeading ? headingColor : (run.isBold ? cyan : Color.WHITE);
                    p.setColor(fullyRead ? yellow : unreadCol);
                    canvas.drawText(run.text, x, y, p);
                    x += run.width;
                }
            } else {
                float localLimit = Math.max(0f, animatedHighlightIndex - line.startOffset);
                int wholeRead = (int) Math.floor(localLimit);
                float edgeFrac = localLimit - wholeRead;
                int drawnChars = 0;
                for (int r = 0; r < line.runs.size(); r++) {
                    Run run = line.runs.get(r);
                    Paint p = run.isBold ? boldPaint : normalPaint;
                    int unreadCol = line.isHeading ? headingColor : (run.isBold ? cyan : Color.WHITE);
                    int rLen = run.text.length();
                    if (drawnChars + rLen <= wholeRead) {
                        p.setColor(yellow);
                        canvas.drawText(run.text, x, y, p);
                    } else if (drawnChars > wholeRead) {
                        p.setColor(unreadCol);
                        canvas.drawText(run.text, x, y, p);
                    } else {
                        int split = Math.max(0, wholeRead - drawnChars);
                        if (split > 0) {
                            String readPart = run.text.substring(0, split);
                            p.setColor(yellow);
                            canvas.drawText(readPart, x, y, p);
                            x += p.measureText(readPart);
                        }
                        if (split < rLen) {
                            String edgeChar = run.text.substring(split, split + 1);
                            p.setColor(blendColor(unreadCol, yellow, edgeFrac));
                            canvas.drawText(edgeChar, x, y, p);
                            x += p.measureText(edgeChar);
                            if (split + 1 < rLen) {
                                String rest = run.text.substring(split + 1);
                                p.setColor(unreadCol);
                                canvas.drawText(rest, x, y, p);
                            }
                        }
                        drawnChars += rLen;
                        continue;
                    }
                    x += run.width;
                    drawnChars += rLen;
                }
            }
        }

        if (isMirror) {
            canvas.restore();
        }
        if (needMoreAnim) {
            postInvalidateOnAnimation();
        }
    }
}
