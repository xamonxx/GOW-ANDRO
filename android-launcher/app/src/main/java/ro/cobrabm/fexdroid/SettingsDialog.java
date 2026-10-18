package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.gow3.android.R;

/** Graphics settings for the Thor, kept in gow3.ini (run-gow3.sh reads them at start: scene
 *  size, upscaler, the game's effect patches). Other lines and comments of the file are kept. */
final class SettingsDialog {
    /** Scene sizes of the presets at the Thor's 1280x720 output (run-gow3.sh). */
    private static final int[] PRESETS={R.string.dlg_preset_native,R.string.dlg_preset_quality,
        R.string.dlg_preset_balanced,R.string.dlg_preset_performance,R.string.dlg_preset_ultra};
    private static final String[][] EFFECTS={
        {"effect_chromatic_aberration","Chromatic aberration","1"},
        {"effect_dof","Depth of field","1"},
        {"effect_motion_blur","Motion blur","1"},
        {"effect_ssao","Ambient occlusion (SSAO)","1"},
        {"effect_game_aa","Game's own anti-aliasing","1"},
        {"effect_dynamic_shadows","Shadows from dynamic lights","1"},
        {"effect_ssr","Screen-space reflections (not in the original)","0"},
    };
    // FSR 4 (INT8) is not offered: ~470 ms a frame and a black scene on the Adreno 740 (run-gow3.sh).
    /** Game settings: the game patches run-gow3.sh applies at start (patch-parts), as upstream's
     *  System menu pages and launcher offer them: {key, title, note, default}. */
    private static final String[][] GAME_PATCHES={
        {"skip_network_choice","Skip the online/offline screen","Start straight at the main menu, offline (there is no PSN here)","1"},
        {"skip_intro","Skip startup intros","The logos and intro movie at start","0"},
        {"debug_camera","Free camera","Toggle it with Cross + L3","0"},
        {"debug_menu","Debug menu","The game's own debug menu; needs its font files","0"},
    };
    /** Cheats: code hooks of the game applied at start (build_gow3_patch_parts.py, run-gow3.sh).
     *  key MUST match the runtime's cheat_keys (gow3_settings.cpp): cheat_item is the singular one. */
    private static final String[][] CHEATS={
        {"cheat_health","Infinite health","Your HP refills to full every frame","0"},
        {"cheat_item","Infinite blood vials and items","Using a blood vial, bullet or other item leaves its count as it was "
            +"(items moved to storage or given away stay too)","0"},
    };
    private static final String[] UPSCALER_VALUES={"off","fsr3"};
    private static final int[] UPSCALER_NAMES={R.string.param_option_off_720p,R.string.param_option_fsr31};
    // auto: GameActivity picks the multiplier that fits the display (LsfgAuto), also when Smart FPS
    // caps the game at 30.
    private static final String[] LSFG_VALUES={"0","auto","2","3","4"};
    private static final int[] LSFG_NAMES={R.string.state_off,R.string.param_option_lsfg_auto,
        R.string.param_option_lsfg_2,R.string.param_option_lsfg_3,R.string.param_option_lsfg_4};
    // flow scale 取值域照 run-gow3.sh 的白名单（0.25|0.5|0.75|1|1.0）：此前缺 0.25 这一档，这里
    // 追加到末尾，默认值 "1.0" 的索引不变，writeLsfgProfile 原样写值、无需改动。
    private static final String[] LSFG_FLOW_VALUES={"1.0","0.75","0.5","0.25"};
    private static final int[] LSFG_FLOW_NAMES={R.string.param_option_flow_1,R.string.param_option_flow_075,
        R.string.param_option_flow_05,R.string.param_option_flow_025};
    // FPS 上限只给运行时 FPS_LIMITS 的取值（gow3_graphics.h 的 FpsLimits：30/60/120/240/0）。
    // 原来还提供 40/45，但 ParseFpsLimit 把不在表里的值一律映射成 0（不限制），等于"看着限帧、
    // 实际无限"，因此删除；取值域与 my-docs/gow3/LAUNCHER.md §3.2 及 Home 页 LaunchParams.kt 对齐。
    private static final String[] FPS_VALUES={"30","60","120","240","0"};
    private static final int[] FPS_NAMES={R.string.dlg_fps_30,R.string.param_option_60,R.string.param_option_120,
        R.string.param_option_240,R.string.param_option_unlimited};
    /** Snapdragon 8 Gen 2: three Cortex-A510, four Cortex-A715, one Cortex-X3. */
    private static final String[] CORE_NAMES={"Core 0 · efficiency A510 2.0 GHz","Core 1 · efficiency A510 2.0 GHz",
        "Core 2 · efficiency A510 2.0 GHz","Core 3 · performance A715 2.8 GHz","Core 4 · performance A715 2.8 GHz",
        "Core 5 · performance A715 2.8 GHz","Core 6 · performance A715 2.8 GHz","Core 7 · prime X3 3.2 GHz"};
    private static final String[] LOD_VALUES={"-2","0","1","2"};
    private static final String[] LOD_NAMES={"Highest","Game default","Lower","Lowest"};

    private final Activity activity;
    private final File file;
    private final Runnable restart;
    private final Map<String,String> values=new LinkedHashMap<>();
    private final List<String> lines=new ArrayList<>();

    SettingsDialog(Activity activity,File file,Runnable restart) {
        this.activity=activity; this.file=file; this.restart=restart;
    }

    private int dp(float n) { return Math.round(n*activity.getResources().getDisplayMetrics().density); }
    private String s(int id) { return activity.getString(id); }
    /** The labels of string resources, in order (RadioGroup choices). */
    private String[] names(int[] ids) {
        String[] out=new String[ids.length];
        for (int i=0;i<ids.length;++i) out[i]=s(ids[i]);
        return out;
    }
    private String get(String key,String fallback) { String v=values.get(key); return v==null || v.isEmpty() ? fallback : v; }

    private void load() {
        values.clear(); lines.clear();
        if (!file.isFile()) return;
        try (BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"))) {
            for (String line; (line=r.readLine())!=null;) {
                lines.add(line);
                int eq=line.indexOf('=');
                if (eq>0 && !line.startsWith("#")) values.put(line.substring(0,eq).trim(),line.substring(eq+1).trim());
            }
        } catch (Exception ex) { android.util.Log.e("God of War III","settings: "+ex); }
    }
    /** Changed keys replace their lines; new keys are appended. Written whole, then renamed. */
    private boolean save(Map<String,String> changed) {
        List<String> out=new ArrayList<>();
        Map<String,String> pending=new LinkedHashMap<>(changed);
        for (String line:lines) {
            int eq=line.indexOf('=');
            String key=eq>0 && !line.startsWith("#") ? line.substring(0,eq).trim() : null;
            if (key!=null && pending.containsKey(key)) out.add(key+"="+pending.remove(key));
            else out.add(line);
        }
        for (Map.Entry<String,String> e:pending.entrySet()) out.add(e.getKey()+"="+e.getValue());
        File temporary=new File(file.getPath()+".tmp");
        try (Writer w=new OutputStreamWriter(new FileOutputStream(temporary),"UTF-8")) {
            for (String line:out) w.write(line+"\n");
        } catch (Exception ex) { android.util.Log.e("God of War III","settings: "+ex); return false; }
        return temporary.renameTo(file);
    }

    private TextView heading(LinearLayout box,String title) {
        TextView t=new TextView(activity); t.setText(title); t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setPadding(0,dp(14),0,dp(4));
        box.addView(t); return t;
    }
    private Switch toggle(LinearLayout box,String title,boolean on) {
        Switch s=new Switch(activity); s.setText(title); s.setChecked(on); s.setTextSize(15);
        s.setPadding(0,dp(6),0,dp(6)); box.addView(s); return s;
    }
    private RadioGroup choices(LinearLayout box,String[] names,int selected) {
        RadioGroup g=new RadioGroup(activity);
        for (int i=0;i<names.length;++i) {
            RadioButton b=new RadioButton(activity); b.setId(View.generateViewId()); b.setText(names[i]); b.setTextSize(15);
            g.addView(b); if (i==selected) g.check(b.getId());
        }
        box.addView(g); return g;
    }
    private static int indexOf(String[] values,String value,int fallback) {
        for (int i=0;i<values.length;++i) if (values[i].equals(value)) return i;
        return fallback;
    }
    /** A taskset list ("0-2,5", "3-7"); empty: every core. */
    private static boolean[] parseCpus(String list,int count) {
        boolean[] on=new boolean[count];
        if (list.trim().isEmpty()) { java.util.Arrays.fill(on,true); return on; }
        for (String part:list.split(",")) {
            try {
                String[] range=part.trim().split("-");
                int from=Integer.parseInt(range[0].trim()), to=range.length>1 ? Integer.parseInt(range[1].trim()) : from;
                for (int i=Math.max(0,from);i<=Math.min(count-1,to);++i) on[i]=true;
            } catch (NumberFormatException ignored) {}
        }
        boolean any=false; for (boolean b:on) any|=b;
        if (!any) java.util.Arrays.fill(on,true);
        return on;
    }
    private static String cpuList(Switch[] cores) {
        StringBuilder list=new StringBuilder(); boolean all=true;
        for (int i=0;i<cores.length;++i) {
            if (!cores[i].isChecked()) { all=false; continue; }
            if (list.length()>0) list.append(',');
            list.append(i);
        }
        return all ? "" : list.toString();
    }
    private static int checkedIndex(RadioGroup g) {
        for (int i=0;i<g.getChildCount();++i) if (((RadioButton)g.getChildAt(i)).isChecked()) return i;
        return -1;
    }

    void show() {
        load();
        LinearLayout box=new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),dp(4),dp(24),dp(8));

        // 升采样入口只提供 off / fsr3。运行时的 UPSCALERS 还有 fsr4、fsr411、taa、dlss
        // （gow3_settings.cpp 的 UpscalerName，见 my-docs/gow3/LAUNCHER.md §3.2），此处刻意不给：
        // FSR 4 / 4.1.1 在 Adreno 740 上约 470ms 一帧且黑屏（run-gow3.sh 实测）；taa 需要 native
        // guest 目标的实时路径、dlss 需要 gow3_dlss.dll/nvngx_dlss.dll，都是桌面 N 卡路径，在本
        // 移植上不成立。要加新升采样器前，先确认它在这套 Vulkan/Turnip 上真能出画面。
        heading(box,s(R.string.dlg_upscaling));
        final RadioGroup upscalers=choices(box,names(UPSCALER_NAMES),indexOf(UPSCALER_VALUES,get("upscaler","fsr3"),1));
        heading(box,s(R.string.dlg_render_resolution));
        int preset=0;
        try { preset=Math.max(0,Math.min(4,Integer.parseInt(get("preset","3")))); } catch (NumberFormatException ignored) { preset=3; }
        final RadioGroup presets=choices(box,names(PRESETS),preset);
        // FSR 3.1 frame generation (frame_generation=1) is not wired: shadps4 has no
        // GOW3_FRAME_GEN consumer and no FSR 3.1 frame-gen patch on this tree, so the
        // option would have been a no-op. LSFG is the only frame-generation path here;
        // tune its multiplier / flow scale / performance mode from the in-game drawer.
        final TextView presetNote=new TextView(activity); presetNote.setTextSize(13); presetNote.setAlpha(0.7f);
        box.addView(presetNote);
        RadioGroup.OnCheckedChangeListener upscaling=(g,id) -> {
            int upscaler=checkedIndex(upscalers);
            boolean on=upscaler!=0;
            for (int i=0;i<presets.getChildCount();++i) presets.getChildAt(i).setEnabled(on);
            presetNote.setText(on ? R.string.dlg_preset_note_on : R.string.dlg_preset_note_off);
        };
        upscalers.setOnCheckedChangeListener(upscaling); upscaling.onCheckedChanged(upscalers,0);

        heading(box,s(R.string.dlg_image));
        final Switch sharpen=toggle(box,s(R.string.dlg_sharpening),"1".equals(get("sharpen","0")));
        final TextView sharpLabel=new TextView(activity); sharpLabel.setTextSize(14); box.addView(sharpLabel);
        final SeekBar sharpness=new SeekBar(activity); sharpness.setMax(100);
        float initial=0.5f;
        try { initial=Float.parseFloat(get("sharpness","0.5")); } catch (NumberFormatException ignored) {}
        sharpness.setProgress(Math.round(Math.max(0,Math.min(1,initial))*100));
        SeekBar.OnSeekBarChangeListener sharpText=new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s,int p,boolean user) { sharpLabel.setText(activity.getString(R.string.dlg_sharpness_fmt,p)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
        sharpness.setOnSeekBarChangeListener(sharpText); sharpText.onProgressChanged(sharpness,sharpness.getProgress(),false);
        box.addView(sharpness);
        sharpen.setOnCheckedChangeListener((b,on) -> sharpness.setEnabled(on)); sharpness.setEnabled(sharpen.isChecked());
        final Switch fps=toggle(box,s(R.string.dlg_show_fps),"1".equals(get("show_fps","0")));

        // FPS 上限入口：取值域必须与运行时 FPS_LIMITS（gow3_graphics.h 的 FpsLimits：30/60/120/
        // 240/0）完全一致——ParseFpsLimit 把表外的值（例如旧版提供的 40/45）一律当成 0（不限制），
        // 给出这类选项等于"看着限帧、实际无限"，所以已删除。另外 120/240 还依赖引擎帧率补丁
        // （gow3.ini 的 engine_fps），本弹窗不写 engine_fps，实际帧率仍受 PresentLimit 的引擎
        // 速率约束；Home 页 LaunchParams.kt 的 fps_limit 同此。
        heading(box,s(R.string.param_fps_limit));
        final RadioGroup fpsLimit=choices(box,names(FPS_NAMES),indexOf(FPS_VALUES,get("fps_limit","30"),0));
        TextView fpsNote=new TextView(activity); fpsNote.setTextSize(13); fpsNote.setAlpha(0.7f);
        fpsNote.setText(R.string.dlg_fps_note);
        box.addView(fpsNote);

        /* 以下三段已注释掉：写入 gow3.ini 的键在本仓库的 fexdroid 启动链路里没有消费方，勾选后
         * 游戏不会有任何变化，对玩家是误导，等接通再放开（不要直接删，接通时整段取消注释即可）。
         *
         * CPU 核心 → thor_cpus（taskset 列表格式）：run-gow3.sh → patches.py --extra / launch_env.py
         * → probe 都不读它，也没有对应的 GOW3_* 环境变量；消费 taskset 的是桌面端 tools/restart.sh
         * 的 $CPUS。
        heading(box,"CPU cores the game may use");
        final Switch[] cores=new Switch[CORE_NAMES.length];
        boolean[] allowed=parseCpus(get("thor_cpus",""),CORE_NAMES.length);
        for (int i=0;i<cores.length;++i) cores[i]=toggle(box,CORE_NAMES[i],allowed[i]);
        CompoundButton.OnCheckedChangeListener keepOne=(b,on) -> {
            if (on) return;
            for (Switch c:cores) if (c.isChecked()) return;
            b.setChecked(true); // at least one core
        };
        for (Switch c:cores) c.setOnCheckedChangeListener(keepOne);
         *
         * 特效 → effect_*、模型细节 → model_lod：启动链路选补丁是按补丁名经 patches.py --extra /
         * patches.json（run.sh、run-gow3.sh），既不读这些 ini 键，gow3_settings.cpp 也不解析；
         * my-docs/gow3/LAUNCHER.md §3.2 的 GOW3 launcher 键表同样不含它们（只在 Bloodborne 参考
         * 配置 bbport-ini.md 出现）。预编译它们的 Thor 路径 build_gow3_patch_parts.py 不被
         * run-gow3.sh 使用。
        heading(box,"Game effects");
        final Switch[] effects=new Switch[EFFECTS.length];
        for (int i=0;i<EFFECTS.length;++i) effects[i]=toggle(box,EFFECTS[i][1],"1".equals(get(EFFECTS[i][0],EFFECTS[i][2])));

        heading(box,"Model detail");
        int lod=1;
        for (int i=0;i<LOD_VALUES.length;++i) if (LOD_VALUES[i].equals(get("model_lod","0")) lod=i;
        final RadioGroup lods=choices(box,LOD_NAMES,lod);
         */

        ScrollView scroll=new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle(R.string.game_graphics_settings).setView(scroll)
            .setNegativeButton(R.string.dialog_cancel,null)
            .setPositiveButton(R.string.dlg_apply_restart,(d,w) -> {
                Map<String,String> changed=new LinkedHashMap<>();
                changed.put("upscaler",UPSCALER_VALUES[Math.max(0,checkedIndex(upscalers))]);
                changed.put("fps_limit",FPS_VALUES[Math.max(0,checkedIndex(fpsLimit))]);
                changed.put("preset",String.valueOf(Math.max(0,checkedIndex(presets))));
                changed.put("sharpen",sharpen.isChecked() ? "1" : "0");
                changed.put("sharpness",String.format(Locale.ROOT,"%.2f",sharpness.getProgress()/100f));
                changed.put("show_fps",fps.isChecked() ? "1" : "0");
                // thor_cpus / effect_* / model_lod 随上面三段一起注释掉了，这里也不写。
                apply(changed);
            }).show();
    }

    /** Saves the changed keys and restarts the game when any differs from the file. */
    private void apply(Map<String,String> changed) {
        boolean same=true;
        for (Map.Entry<String,String> e:changed.entrySet()) if (!e.getValue().equals(values.get(e.getKey()))) same=false;
        if (same) return;
        if (save(changed)) restart.run();
        else new AlertDialog.Builder(activity).setMessage(R.string.settings_save_failed).setPositiveButton(R.string.dialog_ok,null).show();
    }

    /* showGame() 已注释掉（入口也随之在 GameActivity 里隐藏）：这四个 ini 键
     * （skip_network_choice / skip_intro / debug_camera / debug_menu）在本仓库的启动链路里没有
     * 消费方——run-gow3.sh 与 run.sh 都是按补丁名经 patches.py --extra / patches.json 选补丁，
     * gow3_settings.cpp 也不解析它们；预编译它们的 Thor 路径 build_gow3_patch_parts.py 不被
     * run-gow3.sh 使用。开关写进 gow3.ini 后游戏不会有任何变化，属于误导入口。
     * 等启动链路把这些键翻译成补丁名（或接入 patches.json）后再放开。
    void showGame() {
        load();
        LinearLayout box=new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),dp(4),dp(24),dp(8));
        final Switch[] toggles=new Switch[GAME_PATCHES.length];
        for (int i=0;i<GAME_PATCHES.length;++i) {
            toggles[i]=toggle(box,GAME_PATCHES[i][1],"1".equals(get(GAME_PATCHES[i][0],GAME_PATCHES[i][3])));
            TextView note=new TextView(activity); note.setTextSize(12); note.setAlpha(0.65f);
            note.setText(GAME_PATCHES[i][2]); note.setPadding(0,0,0,dp(6));
            box.addView(note);
        }
        TextView restartNote=new TextView(activity); restartNote.setTextSize(13); restartNote.setAlpha(0.7f);
        restartNote.setText("These are patches to the game: Apply restarts it (unsaved progress is lost).");
        restartNote.setPadding(0,dp(8),0,0);
        box.addView(restartNote);
        ScrollView scroll=new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("Game settings").setView(scroll)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Apply",(d,w) -> {
                Map<String,String> changed=new LinkedHashMap<>();
                for (int i=0;i<GAME_PATCHES.length;++i) changed.put(GAME_PATCHES[i][0],toggles[i].isChecked() ? "1" : "0");
                apply(changed);
            }).show();
    }
     */

    /* showCheats() 与 writeCheatState() 已注释掉（入口也随之在 GameActivity 里隐藏）：作弊在
     * Android 上根本不生效，属于误导入口。原因是 hook 根本没装上——
     * src/probe.c 的 install_cheats() 被 `#if defined(GUEST_CPU_NATIVE)` 包着，而
     * GUEST_CPU_NATIVE 只在 `__x86_64__` 时定义（src/guest_cpu.h:12）。Android 是 aarch64 交叉
     * 编译（tools/android/build/aarch64-toolchain.cmake 的 CMAKE_C_COMPILER_TARGET），x86-64
     * guest 跑在 FEXCore JIT 上，所以 probe.c 那段连调用带定义一起被编译掉了，源码注释写得很
     * 直接："Under the JIT ... the game runs without cheats, the health bar and the orb
     * multipliers"。gow3.ini 里的 cheat_health / cheat_item 会被 gow3_settings.cpp 读进
     * Gow3Settings::cheats[]，但没有任何 hook 去读这些 flag，等于写了没人用。
     * 附带：writeCheatState 写的 cheats.state 在本仓库也没有读取方（GOW3_CHEATS_FILE 只出现在
     * 注释里），所以界面上"运行中即时生效"那句本来也不成立。
     * 要接回来的前提是 probe.c 接上 JIT 的 hook 路径（guest_cpu_hook，注释标为 not yet wired）。
    void showCheats() {
        load();
        LinearLayout box=new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),dp(4),dp(24),dp(8));
        final Switch[] toggles=new Switch[CHEATS.length];
        for (int i=0;i<CHEATS.length;++i) {
            toggles[i]=toggle(box,CHEATS[i][1],"1".equals(get(CHEATS[i][0],CHEATS[i][3])));
            TextView note=new TextView(activity); note.setTextSize(12); note.setAlpha(0.65f);
            note.setText(CHEATS[i][2]); note.setPadding(0,0,0,dp(6));
            box.addView(note);
        }
        TextView restartNote=new TextView(activity); restartNote.setTextSize(13); restartNote.setAlpha(0.7f);
        restartNote.setText("For God of War III 1.09 offline play. They switch at once, while you play.");
        restartNote.setPadding(0,dp(8),0,0);
        box.addView(restartNote);
        ScrollView scroll=new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("Cheats").setView(scroll)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Apply",(d,w) -> {
                Map<String,String> changed=new LinkedHashMap<>();
                for (int i=0;i<CHEATS.length;++i) changed.put(CHEATS[i][0],toggles[i].isChecked() ? "1" : "0");
                // The running game follows cheats.state (probe.c reads it): no restart.
                boolean ok=save(changed) && writeCheatState(new File(file.getParentFile(),"cheats.state"),
                    toggles[0].isChecked(),toggles[1].isChecked());
                android.widget.Toast.makeText(activity,ok ? "Cheats applied" : "The cheats could not be saved",
                    android.widget.Toast.LENGTH_SHORT).show();
            }).show();
    }

    // "10": infinite health on, items off (run-gow3.sh's GOW3_CHEATS_FILE), written whole then renamed.
    private static boolean writeCheatState(File state,boolean health,boolean items) {
        File temporary=new File(state.getPath()+".tmp");
        try (Writer w=new OutputStreamWriter(new FileOutputStream(temporary),"UTF-8")) {
            w.write((health ? "1" : "0")+(items ? "1" : "0")+"\n");
        } catch (Exception ex) { android.util.Log.e("God of War III","cheats: "+ex); return false; }
        return temporary.renameTo(state);
    }
     */

    /** Lossless Scaling frame generation. While the game runs with the lsfg-vk layer loaded
     *  (run-gow3.sh wrote lsfg-vk.toml), changes go to that file and apply at once (lsfg-vk watches
     *  it; off is multiplier 1). Turning it on when the layer was not loaded restarts the game.
     *  flow scale 已按 run-gow3.sh 的白名单补齐 0.25 档（见 LSFG_FLOW_VALUES）。这是本移植唯一的
     *  帧生成路径：FSR 3.1 帧生成（frame_generation=1）没有接通，shadps4 无 GOW3_FRAME_GEN 消费者，
     *  所以这里不要再加一个"FSR 帧生成"开关。 */
    void showLossless(java.util.function.BooleanSupplier gameRunning,Runnable onLiveApplied,
                      java.util.function.IntSupplier autoMultiplier) {
        load();
        final File live=new File(file.getParentFile(),"lsfg-vk.toml");
        LinearLayout box=new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),dp(4),dp(24),dp(8));
        heading(box,s(R.string.dlg_frames));
        final RadioGroup lsfg=choices(box,names(LSFG_NAMES),indexOf(LSFG_VALUES,get("lsfg_multiplier","0"),0));
        heading(box,s(R.string.param_lsfg_flow_scale));
        final RadioGroup lsfgFlow=choices(box,names(LSFG_FLOW_NAMES),indexOf(LSFG_FLOW_VALUES,get("lsfg_flow_scale","1.0"),0));
        final Switch lsfgPerformance=toggle(box,s(R.string.dlg_performance_mode),"1".equals(get("lsfg_performance","0")));
        TextView lsfgNote=new TextView(activity); lsfgNote.setTextSize(13); lsfgNote.setAlpha(0.7f);
        lsfgNote.setText(R.string.dlg_lsfg_note);
        box.addView(lsfgNote);
        RadioGroup.OnCheckedChangeListener lossless=(g,id) -> {
            boolean on=checkedIndex(lsfg)>0;
            for (int i=0;i<lsfgFlow.getChildCount();++i) lsfgFlow.getChildAt(i).setEnabled(on);
            lsfgPerformance.setEnabled(on);
        };
        lsfg.setOnCheckedChangeListener(lossless); lossless.onCheckedChanged(lsfg,0);

        ScrollView scroll=new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle(R.string.dlg_lsfg_title).setView(scroll)
            .setNegativeButton(R.string.dialog_cancel,null)
            .setPositiveButton(R.string.dlg_apply,(d,w) -> {
                Map<String,String> changed=new LinkedHashMap<>();
                String multiplier=LSFG_VALUES[Math.max(0,checkedIndex(lsfg))];
                changed.put("lsfg_multiplier",multiplier);
                changed.put("lsfg_flow_scale",LSFG_FLOW_VALUES[Math.max(0,checkedIndex(lsfgFlow))]);
                changed.put("lsfg_performance",lsfgPerformance.isChecked() ? "1" : "0");
                if (gameRunning.getAsBoolean() && live.isFile()) {
                    String frames="0".equals(multiplier) ? "1"
                        : "auto".equals(multiplier) ? String.valueOf(Math.max(1,autoMultiplier.getAsInt())) : multiplier;
                    boolean ok=save(changed) && writeLsfgProfile(live,frames,
                        changed.get("lsfg_flow_scale"),lsfgPerformance.isChecked());
                    android.widget.Toast.makeText(activity,ok ? R.string.dlg_lsfg_applied
                        : R.string.dlg_lsfg_failed,android.widget.Toast.LENGTH_SHORT).show();
                    // layer 已按新倍率热更新 toml；这里同步重选设备显示模式，否则层参数和刷新率会
                    // 分叉（4.3 复评）。
                    if (ok && onLiveApplied!=null) onLiveApplied.run();
                } else if (!"0".equals(multiplier) && gameRunning.getAsBoolean()) {
                    apply(changed); // the layer loads at start only
                } else if (!save(changed)) {
                    new AlertDialog.Builder(activity).setMessage(R.string.settings_save_failed).setPositiveButton(R.string.dialog_ok,null).show();
                }
            }).show();
    }

    /** Rewrites the profile values of the running layer's config (written whole, then renamed:
     *  lsfg-vk reloads on the rename). */
    static boolean writeLsfgProfile(File config,String multiplier,String flowScale,boolean performance) {
        List<String> out=new ArrayList<>();
        try (BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(config),"UTF-8"))) {
            for (String line; (line=r.readLine())!=null;) {
                String t=line.trim();
                if (t.startsWith("multiplier")) line="multiplier = "+multiplier;
                else if (t.startsWith("flow_scale")) line="flow_scale = "+flowScale;
                else if (t.startsWith("performance_mode")) line="performance_mode = "+performance;
                out.add(line);
            }
        } catch (Exception ex) { android.util.Log.e("God of War III","lsfg config: "+ex); return false; }
        File temporary=new File(config.getPath()+".tmp");
        try (Writer w=new OutputStreamWriter(new FileOutputStream(temporary),"UTF-8")) {
            for (String line:out) w.write(line+"\n");
        } catch (Exception ex) { android.util.Log.e("God of War III","lsfg config: "+ex); return false; }
        return temporary.renameTo(config);
    }
}
