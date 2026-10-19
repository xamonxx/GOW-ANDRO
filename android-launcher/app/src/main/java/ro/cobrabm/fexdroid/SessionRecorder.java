package ro.cobrabm.fexdroid;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One play session's numbers, sampled once a second by GameActivity, written as
 *  logs/session-last.json when the game stops (the launcher's "last session" card reads it), and
 *  the launch history (the last exit codes) that crash recovery looks at. Pure Java. */
public final class SessionRecorder {
    /** History code of a game the player stopped (restart, quit): not a crash. */
    public static final int STOPPED=-1;
    private static final int MAX_FPS=240;

    private final int[] fpsHistogram=new int[MAX_FPS+1];
    private long startMs=-1;
    private long fpsSum;
    private int fpsSamples, seconds, severeSeconds, caps, fpsLimit, lsfg;
    private float maxBatteryC=Float.NaN;

    public void start(long nowMs,int fpsLimit,int lsfg) {
        java.util.Arrays.fill(fpsHistogram,0);
        startMs=nowMs; fpsSum=0; fpsSamples=0; seconds=0; severeSeconds=0; caps=0;
        maxBatteryC=Float.NaN; this.fpsLimit=fpsLimit; this.lsfg=lsfg;
    }

    public boolean running() { return startMs>=0; }

    /** One second of play: fps -1 when unknown (not presenting yet), NaN temperature unknown. */
    public void sample(int fps,float batteryC,int thermalStatus) {
        if (startMs<0) return;
        seconds++;
        if (fps>0) {
            int clamped=Math.min(fps,MAX_FPS);
            fpsHistogram[clamped]++; fpsSum+=clamped; fpsSamples++;
        }
        if (!Float.isNaN(batteryC) && (Float.isNaN(maxBatteryC) || batteryC>maxBatteryC)) maxBatteryC=batteryC;
        if (thermalStatus>=3) severeSeconds++;
    }

    /** Smart FPS put the cap on. */
    public void capped() { if (startMs>=0) caps++; }

    /** The frame rate the slowest 1% of the sampled seconds reached; -1 without samples. */
    int lowOnePercent() {
        if (fpsSamples==0) return -1;
        int rank=Math.max(1,(int)Math.ceil(fpsSamples*0.01)), seen=0;
        for (int fps=0;fps<=MAX_FPS;fps++) {
            seen+=fpsHistogram[fps];
            if (seen>=rank) return fps;
        }
        return -1;
    }

    /** The session as JSON (numbers only, flat: the launcher parses it without a JSON library). */
    public String json(long nowMs,int exitCode,boolean removableStorage) {
        int above=0;
        for (int fps=55;fps<=MAX_FPS;fps++) above+=fpsHistogram[fps];
        return String.format(Locale.ROOT,
            "{\"duration_s\": %d, \"avg_fps\": %.1f, \"low1_fps\": %d, \"pct_55\": %d, "
                +"\"max_battery_c\": %.1f, \"severe_s\": %d, \"caps\": %d, \"exit_code\": %d, "
                +"\"fps_limit\": %d, \"lsfg\": %d, \"removable\": %d}\n",
            startMs<0 ? 0 : (nowMs-startMs)/1000, fpsSamples==0 ? -1.0 : (double)fpsSum/fpsSamples,
            lowOnePercent(), fpsSamples==0 ? -1 : above*100/fpsSamples,
            Float.isNaN(maxBatteryC) ? -1f : maxBatteryC, severeSeconds, caps, exitCode, fpsLimit, lsfg,
            removableStorage ? 1 : 0);
    }

    /** Seconds sampled so far. */
    public int seconds() { return seconds; }

    /** Writes the session so far as a player stop, the session going on: swiping the launcher away
     *  from the recent apps kills it without onDestroy, and finish() never runs then. */
    public void checkpoint(File file,long nowMs,boolean removableStorage) {
        if (startMs>=0) write(file,json(nowMs,STOPPED,removableStorage));
    }

    /** Ends the session; its JSON is written to [file] (best effort). */
    public void finish(File file,long nowMs,int exitCode,boolean removableStorage) {
        if (startMs<0) return;
        write(file,json(nowMs,exitCode,removableStorage));
        startMs=-1;
    }

    /** Written whole, then renamed: the launcher never reads half a file. */
    private static void write(File file,String text) {
        try {
            File temporary=new File(file.getPath()+".tmp");
            Files.write(temporary.toPath(),text.getBytes(StandardCharsets.UTF_8));
            if (!temporary.renameTo(file)) temporary.delete();
        } catch (Exception ignored) {}
    }

    /** Appends an exit code to the launch history, keeping the last [keep]. */
    public static void appendHistory(File file,int exitCode,int keep) {
        List<Integer> codes=history(file);
        codes.add(exitCode);
        while (codes.size()>keep) codes.remove(0);
        StringBuilder text=new StringBuilder();
        for (int code : codes) text.append(code).append('\n');
        try { Files.write(file.toPath(),text.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    /** The launch history, oldest first; empty when absent. */
    public static List<Integer> history(File file) {
        List<Integer> codes=new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)) {
                try { codes.add(Integer.parseInt(line.trim())); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return codes;
    }

    /** Exit codes that point at the game itself, not the driver (4: Vulkan, 127: runtime) or the
     *  player (STOPPED) or a clean exit (0). */
    public static boolean crashed(int exitCode) {
        return exitCode!=0 && exitCode!=STOPPED && exitCode!=4 && exitCode!=127;
    }

    /** The last two launches both crashed. */
    public static boolean crashedTwice(List<Integer> history) {
        int n=history.size();
        return n>=2 && crashed(history.get(n-1)) && crashed(history.get(n-2));
    }
}
