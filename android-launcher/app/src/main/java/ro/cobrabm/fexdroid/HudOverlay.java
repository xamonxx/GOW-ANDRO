package ro.cobrabm.fexdroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** The in-game performance monitor: a few short lines (FPS, temperatures, GPU, memory) in a
 *  translucent panel over the game. It never takes touches: the game and the on-screen
 *  controller under it keep receiving them. */
final class HudOverlay extends View {
    static final int ACCENT=0xff38bdf8, TEXT=0xffe6f1ff, OK=0xff34d399, WARN=0xfffbbf24, HOT=0xfff87171;
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG), panel=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds=new RectF();
    private final float pad, lineHeight, radius;
    private String[] lines=new String[0];
    private int[] colors=new int[0];

    HudOverlay(Context context) {
        super(context);
        float density=context.getResources().getDisplayMetrics().density;
        text.setTextSize(11*density);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));
        panel.setColor(0xb0070b12);
        pad=6*density; radius=8*density;
        Paint.FontMetrics metrics=text.getFontMetrics();
        lineHeight=metrics.descent-metrics.ascent+2*density;
        setClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** New readings: one color per line (HudOverlay.OK/WARN/HOT/TEXT/ACCENT). */
    void show(String[] lines,int[] colors) {
        boolean resize=lines.length!=this.lines.length;
        this.lines=lines; this.colors=colors;
        if (resize) requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        float width=0;
        for (String line : lines) width=Math.max(width,text.measureText(line));
        // A stable width (the widest line of "8" digits) keeps the panel from jittering.
        width=Math.max(width,text.measureText("FPS 888 · 88.8°C"));
        setMeasuredDimension((int)Math.ceil(width+2*pad),(int)Math.ceil(lines.length*lineHeight+2*pad));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (lines.length==0) return;
        bounds.set(0,0,getWidth(),getHeight());
        canvas.drawRoundRect(bounds,radius,radius,panel);
        float baseline=pad-text.getFontMetrics().ascent;
        for (int i=0;i<lines.length;i++) {
            text.setColor(i<colors.length ? colors[i] : TEXT);
            canvas.drawText(lines[i],pad,baseline+i*lineHeight,text);
        }
    }
}
