package ro.cobrabm.fexdroid;

/** Smart FPS: decides, once a second, whether the game's frame limit should be capped at 30.
 *  Pure logic (no Android): GameActivity feeds it the thermal state, the 10 s headroom forecast
 *  and the displayed frame rate, and writes gow3port/thermal.cap from the answer.
 *  <ul>
 *  <li>SEVERE or hotter: cap at once (the safety net).</li>
 *  <li>Forecast headroom ≥ 0.95 for 15 s: cap before the phone throttles.</li>
 *  <li>Cool, but the game shows fewer than 50 FPS for 20 s against a limit above 30: an even 30
 *      looks smoother than 40-55 that keeps changing. Retried after 3 minutes.</li>
 *  <li>A thermal cap is released after at least 60 s, once the state is LIGHT or cooler and the
 *      forecast is below 0.75 (or unknown).</li>
 *  </ul> */
public final class SmartFps {
    public static final int CAP=30;
    static final int SEVERE=3, LIGHT=1;
    static final float FORECAST_HOT=0.95f, FORECAST_COOL=0.75f;
    static final long HOT_FOR_MS=15_000, LOW_FPS_FOR_MS=20_000, MIN_CAPPED_MS=60_000, FPS_RETRY_MS=180_000;
    static final int LOW_FPS=50;

    public enum Reason { NONE, SEVERE, FORECAST, LOW_FPS }

    private Reason reason=Reason.NONE;
    private long cappedAt, hotSince=-1, lowSince=-1;

    public boolean capped() { return reason!=Reason.NONE; }
    public Reason reason() { return reason; }

    /** Clears the state (a new game start). */
    public void reset() { reason=Reason.NONE; hotSince=-1; lowSince=-1; }

    /**
     * One sample. status: PowerManager thermal status (-1 unknown); forecast: getThermalHeadroom(10)
     * (NaN unknown); fps: displayed frames per second (-1 unknown); limit: the gow3.ini fps_limit
     * (0 unlimited). Returns whether the cap should be on after this sample.
     */
    public boolean update(long nowMs,int status,float forecast,int fps,int limit) {
        boolean hotForecast=!Float.isNaN(forecast) && forecast>=FORECAST_HOT;
        hotSince=hotForecast ? (hotSince<0 ? nowMs : hotSince) : -1;
        boolean capable=limit==0 || limit>CAP;
        boolean low=capable && fps>0 && fps<LOW_FPS && reason==Reason.NONE;
        lowSince=low ? (lowSince<0 ? nowMs : lowSince) : -1;

        if (status>=SEVERE) {
            if (reason!=Reason.SEVERE && reason!=Reason.FORECAST) cap(Reason.SEVERE,nowMs);
            return true;
        }
        if (!capable) { reset(); return false; }
        if (reason==Reason.NONE) {
            if (hotSince>=0 && nowMs-hotSince>=HOT_FOR_MS) cap(Reason.FORECAST,nowMs);
            else if (lowSince>=0 && nowMs-lowSince>=LOW_FPS_FOR_MS) cap(Reason.LOW_FPS,nowMs);
            return capped();
        }
        if (reason==Reason.LOW_FPS) {
            if (hotForecast) { reason=Reason.FORECAST; cappedAt=nowMs; }
            else if (nowMs-cappedAt>=FPS_RETRY_MS) reset();
            return capped();
        }
        boolean cool=status<=LIGHT && (Float.isNaN(forecast) || forecast<FORECAST_COOL);
        if (cool && nowMs-cappedAt>=MIN_CAPPED_MS) reset();
        return capped();
    }

    private void cap(Reason why,long nowMs) { reason=why; cappedAt=nowMs; lowSince=-1; }
}
