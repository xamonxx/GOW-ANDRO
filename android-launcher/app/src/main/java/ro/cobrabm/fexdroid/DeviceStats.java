package ro.cobrabm.fexdroid;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Device readings for the monitor (launcher card, in-game HUD) from sysfs and procfs. Every
 *  source can be missing or denied by SELinux on a given phone: a reading then reports "unknown"
 *  (-1 or NaN) and never throws. The roots are injectable for host JVM tests. */
public final class DeviceStats {
    private final File sys, proc;
    private int gamePid=-1;

    public DeviceStats(File sysRoot, File procRoot) { sys=sysRoot; proc=procRoot; }
    public static DeviceStats system() { return new DeviceStats(new File("/sys"), new File("/proc")); }

    /** The file's first line, trimmed; null when it cannot be read. */
    static String read(File file) {
        try {
            byte[] bytes=Files.readAllBytes(file.toPath());
            String text=new String(bytes, StandardCharsets.UTF_8);
            int end=text.indexOf('\n');
            return (end<0 ? text : text.substring(0,end)).trim();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Adreno load in percent (kgsl gpu_busy_percentage "42 %", or gpubusy "busy total"); -1 unknown. */
    public int gpuBusyPercent() {
        File kgsl=new File(sys,"class/kgsl/kgsl-3d0");
        String percent=read(new File(kgsl,"gpu_busy_percentage"));
        if (percent!=null) {
            try { return Math.max(0,Math.min(100,Integer.parseInt(percent.replace("%","").trim()))); }
            catch (NumberFormatException ignored) {}
        }
        String busy=read(new File(kgsl,"gpubusy"));
        if (busy!=null) {
            String[] parts=busy.trim().split("\\s+");
            try {
                if (parts.length>=2) {
                    long used=Long.parseLong(parts[0]), total=Long.parseLong(parts[1]);
                    if (total>0) return (int)Math.max(0,Math.min(100,used*100/total));
                }
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** The GPU clock in MHz (devfreq cur_freq or gpuclk, in Hz); -1 unknown. */
    public int gpuMhz() {
        File kgsl=new File(sys,"class/kgsl/kgsl-3d0");
        for (String name : new String[]{"devfreq/cur_freq","gpuclk"}) {
            String hz=read(new File(kgsl,name));
            if (hz==null) continue;
            try {
                long value=Long.parseLong(hz);
                if (value>0) return (int)(value/1_000_000);
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** The hottest readable CPU/GPU thermal zone in °C (millidegrees in sysfs); NaN unknown. */
    public float socTempC() {
        File[] zones=new File(sys,"class/thermal").listFiles();
        if (zones==null) return Float.NaN;
        float hottest=Float.NaN;
        for (File zone : zones) {
            if (!zone.getName().startsWith("thermal_zone")) continue;
            String type=read(new File(zone,"type"));
            if (type==null) continue;
            String kind=type.toLowerCase(java.util.Locale.ROOT);
            if (!kind.contains("cpu") && !kind.contains("gpu") && !kind.contains("soc")) continue;
            String temp=read(new File(zone,"temp"));
            if (temp==null) continue;
            try {
                float c=Long.parseLong(temp)/1000f;
                // Some zones report -273 or a raw ADC value: keep plausible readings only.
                if (c>0 && c<150 && (Float.isNaN(hottest) || c>hottest)) hottest=c;
            } catch (NumberFormatException ignored) {}
        }
        return hottest;
    }

    /** Resident memory of a process in MB (VmRSS); -1 unknown. */
    public long rssMb(int pid) {
        if (pid<=0) return -1;
        try {
            for (String line : Files.readAllLines(new File(proc,pid+"/status").toPath(), StandardCharsets.UTF_8)) {
                if (!line.startsWith("VmRSS:")) continue;
                String[] parts=line.substring(6).trim().split("\\s+");
                return Long.parseLong(parts[0])/1024;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** The process whose command line contains [needle] (the game: "gow3-probe"), cached while it
     *  lives; -1 when none. */
    public int findPid(String needle) {
        if (gamePid>0 && new File(proc,String.valueOf(gamePid)).isDirectory()) return gamePid;
        gamePid=-1;
        File[] entries=proc.listFiles();
        if (entries==null) return -1;
        for (File entry : entries) {
            int pid;
            try { pid=Integer.parseInt(entry.getName()); } catch (NumberFormatException ex) { continue; }
            try {
                String cmd=new String(Files.readAllBytes(new File(entry,"cmdline").toPath()),StandardCharsets.UTF_8)
                    .replace('\0',' ');
                if (cmd.contains(needle)) { gamePid=pid; return pid; }
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
