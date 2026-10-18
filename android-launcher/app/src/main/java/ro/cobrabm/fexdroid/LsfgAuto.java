package ro.cobrabm.fexdroid;

/** lsfg_multiplier=auto: the largest Lossless Scaling multiplier whose output (the game's frame
 *  rate x multiplier) is one of the display's refresh rates, so generated frames are spaced
 *  evenly. 60 FPS on a 60/90/120/144 Hz panel: x2 (120 Hz); capped at 30 (Smart FPS): x4 (120 Hz).
 *  Pure Java. */
public final class LsfgAuto {
    private LsfgAuto() {}

    /** The multiplier (2-4) for [fps] frames per second on a display with [rates] Hz; when no rate
     *  matches, 2 if the panel is fast enough for it, else 0 (off). */
    public static int pick(int fps,float[] rates) {
        if (fps<=0 || rates==null || rates.length==0) return 0;
        float fastest=0;
        for (float rate : rates) fastest=Math.max(fastest,rate);
        for (int multiplier=4;multiplier>=2;multiplier--) {
            for (float rate : rates) if (Math.abs(rate-fps*multiplier)<1) return multiplier;
        }
        return fps*2<=fastest+1 ? 2 : 0;
    }
}
