package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** God of War III-only Android player. Keeps the game surface alive beneath its sliding drawer. */
public final class GameActivity extends Activity {
    /** The app's files (application id com.gow3.android), its Linux rootfs and gow3port runtime.
     *  Rootfs programs are launched through the in-tree loader when their PT_INTERP slot cannot
     *  hold the app-private path. */
    private String FILES, ROOT, BASE;
    /** The game (e.g. CUSA01740, with eboot.bin) in shared storage, chosen by the player. */
    private String gameDir;
    /** An optional update folder (e.g. CUSA01740-patch): laid over the game at start. */
    private String updateDir;
    private static final int REQUEST_FOLDER=1, REQUEST_STORAGE=2;
    private SurfaceHolder pendingHolder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout screen;
    private SurfaceView surface;
    private View scrim, handle;
    private LinearLayout drawer;
    private LinearLayout drawerItems;
    private TextView status;
    private View resume;
    private LinearLayout gameSettingsItem, cheatsItem, graphicsItem, losslessItem, controllerItem;
    private TouchControllerOverlay controllerOverlay;
    private PadBridge pad;
    private AudioBridge audio;
    private Map<String,String> iniCache;
    private long iniCacheModified;
    private volatile Process xvfb, game;
    private volatile int shmid=-1;
    /** The X display Xvfb listens on (startXvfb): not always :0, since X servers also bind the
     *  abstract socket "@/tmp/.X11-unix/X<n>" that every app on the device shares. */
    private volatile int xdisplay;
    private static final int MAX_DISPLAY=9, XVFB_TIMEOUT_S=30;
    private volatile boolean destroyed;
    /** run-gow3.sh 报告 LSFG 运行时失败并自动回退后置位：刷新率要退回「无 LSFG」的目标，避免
     *  停在 LSFG 倍率对应的高刷新率上空跑（4.1 复评）。 */
    private volatile boolean lsfgFallbackOff;
    private boolean drawerOpen, displayOnly, startingX, hadError, swipeConsumed;
    private float touchX, touchY;
    private boolean edgeGesture, imeOpen, imePolling;
    private final AtomicBoolean imeAnswered=new AtomicBoolean();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FILES=getFilesDir().getAbsolutePath();
        ROOT=FILES+"/rootfs"; BASE=FILES+"/gow3port";
        gameDir=getSharedPreferences("gow3",MODE_PRIVATE).getString("game_dir",null);
        updateDir=getSharedPreferences("gow3",MODE_PRIVATE).getString("update_dir",null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT>=28) {
            WindowManager.LayoutParams attrs=getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        displayOnly="x".equals(getIntent().getStringExtra("action"));
        pad=new PadBridge(new File(BASE,"android-pad.state"));
        audio=new AudioBridge(new File(BASE,"audio.fifo"));
        buildScreen();
        immersive();
        sustainedPerformance();
        watchThermal();
    }

    /** gow3.ini sustained_perf=1: clocks the phone can hold through a long session (a lower peak,
     *  no drop once it is hot), on phones that support it. */
    private void sustainedPerformance() {
        if (!"1".equals(setting("sustained_perf"))) return;
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        boolean supported=power!=null && power.isSustainedPerformanceModeSupported();
        if (supported) getWindow().setSustainedPerformanceMode(true);
        logThermal("sustained performance "+(supported ? "on" : "unsupported on this phone"));
    }
    /** The phone's thermal state while playing (logs/android-thermal.log): a frame rate that drops
     *  after a few minutes is the SoC throttling, not the game. Status changes, and the headroom
     *  every 30 s (1.0: throttling starts). */
    private Object thermalListener;
    private final Runnable thermalTick=new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            logThermal("tick");
            android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
            if (power!=null) thermalGuard(power.getCurrentThermalStatus());
            main.postDelayed(this,30000);
        }
    };
    private void watchThermal() {
        if (Build.VERSION.SDK_INT<29) return;
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if (power==null) return;
        android.os.PowerManager.OnThermalStatusChangedListener listener=status -> {
            logThermal("status changed");
            thermalGuard(status);
        };
        power.addThermalStatusListener(listener);
        thermalListener=listener;
        main.postDelayed(thermalTick,30000);
    }
    /** Thermal guard (gow3.ini thermal_guard, default on), on the main thread: the game's frame limit
     *  drops to 30 while the phone is SEVERE or hotter and comes back once it is LIGHT or cooler.
     *  gow3port/thermal.cap is read live by the present thread (run-gow3.sh GOW3_THERMAL_CAP_FILE). */
    private boolean thermalCapped;
    private static final int THERMAL_CAP_ON=3 /* SEVERE */, THERMAL_CAP_OFF=1 /* LIGHT */;
    private void thermalGuard(int status) {
        boolean enabled=!"0".equals(setting("thermal_guard"));
        if (thermalCapped && (!enabled || status<=THERMAL_CAP_OFF)) setThermalCap(false);
        else if (!thermalCapped && enabled && status>=THERMAL_CAP_ON) setThermalCap(true);
    }
    private void setThermalCap(boolean on) {
        // Never creates gow3port: before the first install there is no game to cap.
        if (!new File(BASE).isDirectory()) return;
        File cap=new File(BASE,"thermal.cap");
        try {
            if (on) { try (PrintWriter out=new PrintWriter(cap)) { out.println("30"); } }
            else if (cap.exists() && !cap.delete()) throw new java.io.IOException("cannot delete");
            thermalCapped=on;
            logThermal(on ? "guard: frame limit capped at 30" : "guard: cap released");
        } catch (Exception ex) {
            logThermal("guard: cannot update "+cap+": "+ex.getMessage());
        }
    }
    /** Before each start: no cap left from an earlier session, then the current state decides. */
    private void resetThermalGuard() {
        new File(BASE,"thermal.cap").delete();
        thermalCapped=false;
        if (Build.VERSION.SDK_INT<29) return;
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if (power!=null) thermalGuard(power.getCurrentThermalStatus());
    }
    private void logThermal(String event) {
        String state="";
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if (power!=null && Build.VERSION.SDK_INT>=29) {
            state=" status="+power.getCurrentThermalStatus();
            if (Build.VERSION.SDK_INT>=30)
                state+=String.format(Locale.ROOT," headroom(10s)=%.2f",power.getThermalHeadroom(10));
        }
        String line=String.format(Locale.ROOT,"%tT Thermal: %s%s",System.currentTimeMillis(),event,state);
        android.util.Log.i("God of War III",line);
        // The runtime's own logs directory only: creating gow3port here could race the first
        // install, which renames its staged gow3port into place.
        File logs=new File(BASE,"logs");
        if (!logs.isDirectory()) return;
        try (PrintWriter out=new PrintWriter(new java.io.FileWriter(new File(logs,"android-thermal.log"),true))) {
            out.println(line);
        } catch (Exception ignored) {}
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private FrameLayout.LayoutParams frame(int width,int height,int gravity) {
        return new FrameLayout.LayoutParams(width,height,gravity);
    }
    private void fitSurface(int width,int height) {
        if (width<=0 || height<=0) return;
        int w=width, h=height;
        if ((long)w*9>(long)h*16) w=h*16/9; else h=w*9/16;
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if (p.width==w && p.height==h) return;
        p.width=w; p.height=h;
        // Not during the layout pass that reported the size.
        surface.post(() -> surface.setLayoutParams(p));
    }

    private void buildScreen() {
        screen=new FrameLayout(this); screen.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        screen.addView(surface,frame(-1,-1,Gravity.CENTER));
        // The game is 16:9: the largest 16:9 area of the screen, black bars around it (a foldable's
        // near-square inner screen, a tall cover screen). Refitted when folding or rotating.
        screen.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitSurface(r-l,b-t));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                if (shmid>=0) attach(h);
                else if (!hasGame()) {
                    // MainActivity owns the first-run game-folder check. Reaching this state means
                    // the launcher was bypassed or the selected folder was removed while away.
                    pendingHolder=h;
                    error("Game folder is unavailable. Return to the launcher and choose it again.");
                }
                else if (!startingX) { startingX=true; worker.execute(() -> startX(h)); }
            }
            @Override public void surfaceChanged(SurfaceHolder h,int format,int w,int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { DisplayBridge.stop(); }
        });
        surface.setOnTouchListener((v,e) -> {
            if (shmid<0 || drawerOpen) return true;
            XInput.moveTo(Math.round(e.getX()*1280/Math.max(1,v.getWidth())),
                          Math.round(e.getY()*720/Math.max(1,v.getHeight())));
            if (e.getActionMasked()==MotionEvent.ACTION_DOWN) XInput.button(1,true);
            if (e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL)
                XInput.button(1,false);
            return true;
        });
        status=text("Starting God of War III…",18,0xffc6ad76); status.setGravity(Gravity.CENTER);
        screen.addView(status,frame(-1,-1,Gravity.CENTER));
        status.setClickable(false);

        controllerOverlay=new TouchControllerOverlay(this,pad,"1".equals(setting("overlay_mouse")));
        controllerOverlay.setVisibility(controllerEnabled() ? View.VISIBLE : View.GONE);
        screen.addView(controllerOverlay,frame(-1,-1,Gravity.CENTER));

        hud=new HudOverlay(this);
        hud.setVisibility(hudEnabled() ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams hudAt=frame(-2,-2,Gravity.TOP|Gravity.LEFT);
        hudAt.setMargins(dp(12),dp(10),0,0);
        screen.addView(hud,hudAt);

        handle=new View(this);
        GradientDrawable grip=new GradientDrawable(); grip.setColor(0x80c6ad76); grip.setCornerRadius(dp(4));
        handle.setBackground(grip); handle.setContentDescription("Open game menu");
        screen.addView(handle,frame(dp(4),dp(44),Gravity.LEFT|Gravity.CENTER_VERTICAL));
        handle.setOnClickListener(v -> openDrawer());

        scrim=new View(this); scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE); scrim.setOnClickListener(v -> closeDrawer());
        screen.addView(scrim,frame(-1,-1,Gravity.FILL));

        drawer=new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff1b1d24,0xff121318});
        drawer.setBackground(panel);
        drawer.setElevation(dp(16)); drawer.setVisibility(View.GONE);
        drawerItems=new LinearLayout(this); drawerItems.setOrientation(LinearLayout.VERTICAL);
        drawerItems.setPadding(dp(14),dp(20),dp(14),dp(16));
        android.widget.ScrollView drawerScroll=new android.widget.ScrollView(this);
        drawerScroll.setFillViewport(false);
        drawerScroll.addView(drawerItems);
        // A thin gold edge on the game side.
        LinearLayout body=new LinearLayout(this);
        body.addView(drawerScroll,new LinearLayout.LayoutParams(0,-1,1));
        View edge=new View(this); edge.setBackgroundColor(0x55c6ad76);
        body.addView(edge,new LinearLayout.LayoutParams(dp(1),-1));
        drawer.addView(body,new LinearLayout.LayoutParams(-1,0,1));

        TextView hint=text("Swipe left or press A to return",12,0xff8e9097);
        hint.setPadding(dp(10),dp(2),0,dp(6));
        drawerItems.addView(hint,new LinearLayout.LayoutParams(-1,-2));

        section("Game");
        resume=menuItem("ic_play","Resume game",null,() -> closeDrawer());
        /* Game settings 与 Cheats 两个入口已隐藏（对应 SettingsDialog.showGame()/showCheats() 也已
         * 注释掉）：它们写的 ini 键在本仓库的启动链路里没有消费方，作弊更是根本没装 hook——Android
         * 走 FEXCore JIT，probe.c 的 install_cheats() 被 `#if defined(GUEST_CPU_NATIVE)` 编译掉了。
         * 留着入口只会让玩家以为改了有用。接通后把下面两段取消注释即可。
        gameSettingsItem=menuItem("ic_settings","Game settings","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(BASE,"gow3.ini"),this::restartGame).showGame();
        });
        cheatsItem=menuItem("ic_cheats","Cheats","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(BASE,"gow3.ini"),this::restartGame).showCheats();
        });
         */
        menuItem("ic_refresh","Restart game","Unsaved progress is lost",() -> new AlertDialog.Builder(this)
            .setTitle("Restart God of War III?").setMessage("Any unsaved progress will be lost.")
            .setNegativeButton("Cancel",null).setPositiveButton("Restart",(dialog,which) -> restartGame()).show());

        section("Graphics");
        // Graphics 入口只保留运行时真正消费的那几项：upscaler / preset / sharpen / sharpness /
        // show_fps / fps_limit。原来的 CPU 核心（thor_cpus）、特效（effect_*）、模型细节
        // （model_lod）在本仓库启动链路没有消费方，已在 SettingsDialog.show() 里注释掉。
        // fps_limit 的摘要取值域与 SettingsDialog.FPS_VALUES 和运行时 FPS_LIMITS
        // （30/60/120/240/0）对齐。
        graphicsItem=menuItem("ic_tune","Graphics settings","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(BASE,"gow3.ini"),this::restartGame).show();
        });
        // Tuning only. Supplying Lossless.dll is the launcher Home page's job (com.gow3.android);
        // the drawer's row reports the multiplier, never where the DLL came from. LSFG 是唯一帧生成
        // 路径，FSR 3.1 帧生成未接通，不要在这里再加 FSR 帧生成入口。
        losslessItem=menuItem("ic_frames","Lossless Scaling","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(BASE,"gow3.ini"),this::restartGame)
                // 运行中改倍率时 layer 会热更新 toml，但设备显示模式不会自动跟着变：让对话框在
                // 生效后回调这里重选 display mode（4.3 复评）。
                .showLossless(() -> { Process p=game; return p!=null && p.isAlive(); }, this::pickRefreshRate);
        });

        hudItem=menuItem("ic_tune",getString(com.gow3.android.R.string.hud_menu_title),"",() -> {
            boolean enabled=!hudEnabled();
            writeSetting("hud_monitor",enabled ? "1" : "0");
            hud.setVisibility(enabled ? View.VISIBLE : View.GONE);
            closeDrawer();
        });

        section("Controls");
        controllerItem=menuItem("ic_gamepad","On-screen controller","",() -> {
            boolean enabled=!controllerEnabled();
            getSharedPreferences("gow3",MODE_PRIVATE).edit().putBoolean("controller_overlay",enabled).apply();
            controllerOverlay.release();
            closeDrawer();
        });
        menuItem("ic_edit","Edit controller layout","Drag and resize the buttons",() -> {
            getSharedPreferences("gow3",MODE_PRIVATE).edit().putBoolean("controller_overlay",true).apply();
            closeDrawer();
            controllerOverlay.edit(null);
        });
        menuItem("ic_restore","Reset controller layout",null,() -> {
            controllerOverlay.resetLayout();
            closeDrawer();
        });

        menuItem("ic_exit","Quit game",null,() -> new AlertDialog.Builder(this)
            .setTitle("Quit God of War III?").setMessage("Any unsaved progress will be lost.")
            .setNegativeButton("Cancel",null).setPositiveButton("Quit",(dialog,which) -> finish()).show());

        // The version, pinned under the scrolling menu.
        String version="";
        try { version=" "+getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch (Exception ignored) {}
        TextView about=text("God of War III for Android"+version,12,0xff8e9097);
        about.setPadding(dp(24),dp(8),dp(14),dp(14));
        drawer.addView(about,new LinearLayout.LayoutParams(-1,-2));
        screen.addView(drawer,frame(dp(320),-1,Gravity.LEFT));
        setContentView(screen);
    }

    private void restartGame() {
        closeDrawer(); status.setText("Restarting God of War III…"); status.setVisibility(View.VISIBLE);
        worker.execute(() -> { stopGame(); startGame(); });
    }

    /** A small gold heading above a group of menu rows. */
    private void section(String name) {
        TextView t=text(name.toUpperCase(java.util.Locale.ROOT),11,0xffc6ad76);
        t.setLetterSpacing(0.15f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10),dp(16),0,dp(6));
        drawerItems.addView(t,new LinearLayout.LayoutParams(-1,-2));
    }

    /** A menu row: icon, title and an optional second line (null: none; "" filled by refreshDrawer).
     *  Focusable for the gamepad, with a gold highlight when focused or pressed. */
    private LinearLayout menuItem(String icon,String title,String detail,Runnable action) {
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10),dp(8),dp(10),dp(8));
        row.setFocusable(true); row.setClickable(true); row.setTag(action); row.setOnClickListener(v -> ((Runnable)row.getTag()).run());
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable lit=new GradientDrawable(); lit.setColor(0x33c6ad76); lit.setCornerRadius(dp(10));
        lit.setStroke(dp(1),0x99c6ad76);
        GradientDrawable pressed=new GradientDrawable(); pressed.setColor(0x44c6ad76); pressed.setCornerRadius(dp(10));
        states.addState(new int[]{android.R.attr.state_pressed},pressed);
        states.addState(new int[]{android.R.attr.state_focused},lit);
        states.addState(new int[]{},new android.graphics.drawable.ColorDrawable(0));
        row.setBackground(states);

        android.widget.ImageView image=new android.widget.ImageView(this);
        int id=getResources().getIdentifier(icon,"drawable",getPackageName());
        if (id!=0) image.setImageResource(id);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(24),dp(24)); ip.rightMargin=dp(14);
        row.addView(image,ip);

        LinearLayout texts=new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(title,15,0xffe8dfc9);
        texts.addView(name);
        if (detail!=null) {
            TextView second=text(detail,12,0xff8e9097); second.setTag("detail");
            texts.addView(second);
        }
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(2);
        row.setMinimumHeight(dp(48));
        drawerItems.addView(row,lp); return row;
    }
    private static void setDetail(LinearLayout row,String value) {
        TextView detail=row==null ? null : (TextView)row.findViewWithTag("detail");
        if (detail!=null) detail.setText(value);
    }
    /** 抽屉里的一行不可用时：不可点击、不可获焦（手柄/键盘导航会跳过它），并整体变灰。
     *  action 存在 row 的 tag 里，监听器保持挂着——setClickable(false) 会让点击不再分发，
     *  这样重新启用时不必再重挂一次监听器。 */
    private static void setRowEnabled(LinearLayout row,boolean on) {
        if (row==null) return;
        row.setClickable(on);
        row.setFocusable(on);
        row.setAlpha(on ? 1.0f : 0.4f);
    }
    /** The rows' second lines: the current settings, read when the drawer opens. */
    private void refreshDrawer() {
        /* gameSettingsItem / cheatsItem 的摘要随上面两个入口一起注释掉了（接回来时一起放开）。
        java.util.List<String> game=new java.util.ArrayList<>();
        if (!"0".equals(setting("skip_network_choice"))) game.add("Offline start");
        if ("1".equals(setting("skip_intro"))) game.add("no intros");
        if ("1".equals(setting("debug_camera"))) game.add("free camera");
        if ("1".equals(setting("debug_menu"))) game.add("debug menu");
        setDetail(gameSettingsItem,game.isEmpty() ? "Online/offline screen shown" : String.join(" · ",game));
        java.util.List<String> cheats=new java.util.ArrayList<>();
        if ("1".equals(setting("cheat_health"))) cheats.add("infinite health");
        if ("1".equals(setting("cheat_item"))) cheats.add("infinite items");
        setDetail(cheatsItem,cheats.isEmpty() ? "Off" : String.join(" · ",cheats));
         */
        String upscaler="off".equals(setting("upscaler")) ? "No upscaling" : "FSR 3.1";
        String limit=setting("fps_limit");
        // 取值域与 SettingsDialog.FPS_VALUES 一致：只有 30/60/120/240 会按原值显示，0 是"不限制"，
        // 其余（键缺失、旧版遗留的 40/45）运行时 ParseFpsLimit 都当 0，所以回退显示 30。
        setDetail(graphicsItem,upscaler+" · "+("0".equals(limit) ? "no FPS limit"
            : (limit==null || !limit.matches("30|60|120|240") ? "30" : limit)+" FPS limit"));
        String lsfg=setting("lsfg_multiplier");
        boolean lsfgOn="2".equals(lsfg) || "3".equals(lsfg) || "4".equals(lsfg);
        // 没导入 Lossless.dll 就开不了插帧：lsfg-vk 只加载玩家自带的 DLL，run-gow3.sh 的
        //  preflight 找不到它时会静默不开 layer（只剩 30 FPS），玩家看不出是自己漏了导入。
        //  所以直接把这一行置灰不可点，并说明去 Home 页导入，而不是让它"看着能开却不生效"。
        boolean dllPresent=hasFrameGenDll();
        setRowEnabled(losslessItem,dllPresent);
        setDetail(losslessItem,!dllPresent ? "Import Lossless.dll on the Home page first"
            : lsfgOn ? lsfg+"× frames · flow "
            +(setting("lsfg_flow_scale")==null ? "1.0" : setting("lsfg_flow_scale")) : "Off");
        setDetail(controllerItem,controllerEnabled() ? "On" : "Off");
        setDetail(hudItem,hudEnabled() ? "On" : "Off");
    }

    // ---- Performance monitor (HUD): FPS, temperatures, GPU, memory ----
    private HudOverlay hud;
    private LinearLayout hudItem;
    private final DeviceStats deviceStats=DeviceStats.system();
    private long lastFrames=-1, lastDirectFrames=-1, lastFramesAt;
    private int displayFps=-1, monitorTick;
    /** gow3.ini hud_monitor, default on. */
    private boolean hudEnabled() { return !"0".equals(setting("hud_monitor")); }
    /** Every second (pollFrames): the displayed frame rate from the display bridge's counters; every
     *  2 s the HUD's other readings. A reading the phone denies shows as a dash. */
    private void sampleMonitor() {
        long now=SystemClock.elapsedRealtime(), frames=DisplayBridge.frames(), direct=DisplayBridge.directFrames();
        if (lastFrames>=0 && now>lastFramesAt)
            displayFps=(int)Math.round(Math.max(frames-lastFrames,direct-lastDirectFrames)*1000.0/(now-lastFramesAt));
        lastFrames=frames; lastDirectFrames=direct; lastFramesAt=now;
        if (hud==null || hud.getVisibility()!=View.VISIBLE || (monitorTick++%2)!=0) return;

        android.content.Intent battery=registerReceiver(null,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        int tenths=battery==null ? Integer.MIN_VALUE : battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
        float batteryC=tenths==Integer.MIN_VALUE ? Float.NaN : tenths/10f;
        int status=-1;
        if (Build.VERSION.SDK_INT>=29) {
            android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
            if (power!=null) status=power.getCurrentThermalStatus();
        }
        android.app.ActivityManager activities=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
        if (activities!=null) activities.getMemoryInfo(memory);
        int gpuBusy=deviceStats.gpuBusyPercent(), gpuMhz=deviceStats.gpuMhz();
        float socC=deviceStats.socTempC();
        long gameMb=deviceStats.rssMb(deviceStats.findPid("gow3-probe"));

        String fps=displayFps<0 ? "—" : String.valueOf(displayFps);
        String[] lines={
            "FPS "+fps+(thermalCapped ? "  · "+getString(com.gow3.android.R.string.hud_capped) : ""),
            getString(com.gow3.android.R.string.hud_temp)+" "+celsius(batteryC)+"  SoC "+celsius(socC),
            "GPU "+(gpuBusy<0 ? "—" : gpuBusy+"%")+"  "+(gpuMhz<0 ? "" : gpuMhz+" MHz"),
            "RAM "+gigabytes(gameMb)+" "+getString(com.gow3.android.R.string.hud_game)+"  "
                +gigabytes((memory.totalMem-memory.availMem)>>20)+"/"+gigabytes(memory.totalMem>>20),
        };
        int fpsColor=displayFps<0 ? HudOverlay.TEXT : displayFps>=55 || (thermalCapped && displayFps>=28)
            ? HudOverlay.OK : displayFps>=28 ? HudOverlay.WARN : HudOverlay.HOT;
        int tempColor=status<0 ? HudOverlay.TEXT : status<=1 ? HudOverlay.OK : status==2 ? HudOverlay.WARN : HudOverlay.HOT;
        hud.show(lines,new int[]{fpsColor,tempColor,HudOverlay.ACCENT,HudOverlay.TEXT});
    }
    private static String celsius(float c) {
        return Float.isNaN(c) ? "—" : String.format(Locale.ROOT,"%.0f°C",c);
    }
    private static String gigabytes(long mb) {
        return mb<0 ? "—" : String.format(Locale.ROOT,"%.1fG",mb/1024f);
    }
    /** One gow3.ini key, rewritten in place or appended, other lines kept (the launcher's rules). */
    private void writeSetting(String key,String value) {
        File ini=new File(BASE,"gow3.ini"), temporary=new File(BASE,"gow3.ini.tmp");
        try {
            java.util.List<String> lines=ini.isFile() ? Files.readAllLines(ini.toPath(),StandardCharsets.UTF_8)
                                                       : new ArrayList<>();
            boolean replaced=false;
            for (int i=0;i<lines.size();i++) {
                String line=lines.get(i);
                int eq=line.indexOf('=');
                if (eq>0 && !line.startsWith("#") && line.substring(0,eq).trim().equals(key)) {
                    lines.set(i,key+"="+value); replaced=true;
                }
            }
            if (!replaced) lines.add(key+"="+value);
            Files.write(temporary.toPath(),(String.join("\n",lines)+"\n").getBytes(StandardCharsets.UTF_8));
            if (!temporary.renameTo(ini)) throw new java.io.IOException("cannot replace "+ini);
        } catch (Exception ex) {
            temporary.delete();
            android.util.Log.w("God of War III","Cannot save "+key+": "+ex);
        }
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen=true; controllerOverlay.release(); pad.reset(); XInput.button(1,false);
        refreshDrawer();
        controllerOverlay.setVisibility(View.GONE);
        handle.setVisibility(View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.setAlpha(0); scrim.setVisibility(View.VISIBLE); scrim.animate().alpha(1).setDuration(200).start();
        drawer.setTranslationX(-dp(320)); drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(220).start(); resume.requestFocus();
    }
    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen=false; pad.reset();
        controllerOverlay.setVisibility(controllerEnabled() ? View.VISIBLE : View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.animate().alpha(0).setDuration(180).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        drawer.animate().translationX(-dp(320)).setDuration(200)
            .withEndAction(() -> { drawer.setVisibility(View.GONE); handle.setVisibility(View.VISIBLE); surface.requestFocus(); }).start();
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c=getWindow().getInsetsController();
            if (c!=null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive(); else if (pad!=null) pad.reset();
    }
    @Override public void onBackPressed() { if (drawerOpen) closeDrawer(); else openDrawer(); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            swipeConsumed=false;
            touchX=e.getX(); touchY=e.getY();
            edgeGesture=!drawerOpen && touchX<dp(28);
        }
        if (swipeConsumed) return true;
        if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
            float dx=e.getX()-touchX, dy=e.getY()-touchY;
            if (edgeGesture && dx>dp(40) && Math.abs(dy)<dp(90)) {
                openDrawer(); edgeGesture=false; swipeConsumed=true; return true;
            }
            if (drawerOpen && dx< -dp(70) && Math.abs(dy)<dp(90)) {
                MotionEvent cancel=MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel); cancel.recycle();
                closeDrawer(); swipeConsumed=true; return true;
            }
        }
        if (edgeGesture) {
            if (e.getActionMasked()==MotionEvent.ACTION_UP) { edgeGesture=false; openDrawer(); }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code=e.getKeyCode();
        if (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction()==KeyEvent.ACTION_UP) onBackPressed(); return true;
        }
        if (drawerOpen) {
            if (code==KeyEvent.KEYCODE_BUTTON_B || e.getScanCode()==305) {
                if (e.getAction()==KeyEvent.ACTION_UP) closeDrawer(); return true;
            }
            if (code==KeyEvent.KEYCODE_BUTTON_A || e.getScanCode()==304) {
                if (e.getAction()==KeyEvent.ACTION_UP && getCurrentFocus()!=null) getCurrentFocus().performClick();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (pad.key(e)) return true;
        if (shmid>=0 && e.getScanCode()>0 && e.getScanCode()+8<=255) {
            XInput.key(e.getScanCode()+8,e.getAction()==KeyEvent.ACTION_DOWN); return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (drawerOpen && e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return true;
        return pad.motion(e) || super.dispatchGenericMotionEvent(e);
    }
    private boolean controllerEnabled() {
        return getSharedPreferences("gow3",MODE_PRIVATE).getBoolean("controller_overlay",false);
    }
    @Override protected void onPause() {
        if (controllerOverlay!=null) controllerOverlay.release();
        if (pad!=null) pad.reset();
        if (audio!=null) audio.pause();
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (audio!=null) audio.resume(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Deterministic UI checks over ADB, using the same handlers as the actual controls.
        String action=intent.getStringExtra("ui_action");
        if ("open-menu".equals(action)) openDrawer();
        if ("close-menu".equals(action)) closeDrawer();
    }

    private Process process(String... args) throws java.io.IOException { return builder(args).start(); }
    private ProcessBuilder builder(String... args) {
        ProcessBuilder b=new ProcessBuilder(args).redirectErrorStream(true);
        Map<String,String> env=b.environment(); env.clear();
        String target=args.length>1 && args[0].equals(ROOT+"/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1") ? args[1] : args[0];
        env.put("PATH",ROOT+"/usr/bin:"+ROOT+"/bin:/system/bin");
        env.put("HOME",FILES+"/home/gow3port"); env.put("LANG","C.UTF-8");
        env.put("TMPDIR",ROOT+"/tmp"); env.put("XDG_RUNTIME_DIR",ROOT+"/tmp");
        env.put("FXD_FILES",FILES); env.put("FXD_ROOT",ROOT); env.put("GOW3_ANDROID_INPUT","1");
        // Keep native port libraries and rootfs libraries visible to the explicit rootfs loader.
        env.put("LD_LIBRARY_PATH",BASE+"/arm64/lib:"+ROOT+"/usr/lib/aarch64-linux-gnu:"+ROOT+"/usr/lib");
        // Its libc emulates SysV shared memory through fxshmd (started below).
        env.put("FXD_SHM_SOCKET",ROOT+"/tmp/.fxshm/sock");
        if (gameDir!=null) env.put("GOW3_GAME_DIR",gameDir);
        if (updateDir!=null) env.put("GOW3_PATCH_DIR",updateDir);
        if (target.endsWith("/Xvfb")) env.put("LD_PRELOAD",ROOT+"/usr/lib/fexdroid/libfxpath.so");
        env.put("LIBGL_DRIVERS_PATH",ROOT+"/usr/lib/aarch64-linux-gnu/dri");
        // The display Xvfb got (startX): run-gow3.sh exports it as DISPLAY.
        env.put("GOW3_DISPLAY",":"+xdisplay);
        return b;
    }
    private static long pageSize() {
        try { return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE); }
        catch (Throwable ex) { return 4096; }
    }
    /** Kills this app's runtime processes an earlier session left running (the app process died
     *  without onDestroy, its Xvfb and game kept going): they hold the X display. fxshmd stays, it
     *  is shared and reused. */
    private void killStaleRuntime() {
        File[] entries=new File("/proc").listFiles();
        if (entries==null) return;
        int self=android.os.Process.myPid(), uid=android.os.Process.myUid();
        ArrayList<File> killed=new ArrayList<>();
        for (File entry : entries) {
            int pid;
            try { pid=Integer.parseInt(entry.getName()); } catch (NumberFormatException ex) { continue; }
            if (pid==self) continue;
            try {
                if (android.system.Os.stat(entry.getPath()).st_uid!=uid) continue;
                String cmd=new String(Files.readAllBytes(new File(entry,"cmdline").toPath()),StandardCharsets.UTF_8)
                    .replace('\0',' ').trim();
                if (!cmd.contains(FILES+"/") || cmd.contains("/fxshmd")) continue;
                android.util.Log.w("God of War III","Stopping a runtime process of an earlier session: "+pid+" "+cmd);
                android.os.Process.killProcess(pid);
                killed.add(entry);
            } catch (Exception ignored) {}
        }
        for (int i=0; i<30 && killed.stream().anyMatch(File::exists); i++) SystemClock.sleep(100);
    }
    /** fxshmd, the SysV shared memory of Xvfb's frame and the display bridge. Its first process
     *  exits once the socket listens (the daemon stays); one already running is reused. */
    private void startShm() throws Exception {
        File log=new File(BASE,"logs/android-fxshmd.log");
        Process shmd=builder(ROOT+"/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            ROOT+"/usr/libexec/fxshmd","--dir",ROOT+"/tmp/.fxshm").redirectOutput(log).start();
        if (!shmd.waitFor(10,TimeUnit.SECONDS)) {
            shmd.destroyForcibly();
            throw new java.io.IOException("The shared memory daemon (fxshmd) did not start within 10 s.");
        }
        if (shmd.exitValue()!=0 || !new File(ROOT,"tmp/.fxshm/sock").exists()) {
            String output=log.isFile() ? new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8).trim() : "";
            throw new java.io.IOException("The shared memory daemon (fxshmd) failed (exit "+shmd.exitValue()+")"
                +(output.isEmpty() ? "." : ":\n"+output));
        }
    }
    /** Xvfb's output: to logcat and logs/android-x11.log, its tail kept for the error message,
     *  and the shmid of its ready line. Keeps draining while Xvfb runs. */
    private static final class XvfbWatch implements Runnable {
        private static final Pattern READY=Pattern.compile("screen 0 shmid (\\d+)");
        final Process process;
        final PrintWriter log;
        final CountDownLatch done=new CountDownLatch(1);
        private final ArrayDeque<String> tail=new ArrayDeque<>();
        volatile int shmid=-1;
        XvfbWatch(Process process,PrintWriter log) { this.process=process; this.log=log; }
        @Override public void run() {
            try (BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line=r.readLine())!=null;) {
                    android.util.Log.i("God of War III-X11",line);
                    log.println(line);
                    if (shmid>=0) continue;
                    synchronized (tail) { tail.add(line); if (tail.size()>12) tail.removeFirst(); }
                    Matcher m=READY.matcher(line);
                    if (m.find()) { shmid=Integer.parseInt(m.group(1)); done.countDown(); }
                }
            } catch (Exception ignored) {
            } finally { done.countDown(); }
        }
        String tail() { synchronized (tail) { return String.join("\n",tail); } }
        /** Another X server holds the display: its lock, or its socket (the abstract one is shared by
         *  every app on the device, so another app's X server on :0 counts). */
        boolean displayTaken() {
            String t=tail().toLowerCase(Locale.ROOT);
            return t.contains("already active") || t.contains("already running")
                || t.contains("address already in use") || t.contains("cannot establish any listening sockets");
        }
    }
    /** Starts Xvfb on the first free display from :0 and attaches the display bridge to it. */
    private void startXvfb(SurfaceHolder holder) throws Exception {
        PrintWriter log=new PrintWriter(new java.io.FileOutputStream(new File(BASE,"logs/android-x11.log")),true);
        log.println("Device: "+Build.MANUFACTURER+" "+Build.MODEL+" ("+Build.DEVICE+"), Android "+Build.VERSION.RELEASE
            +" (API "+Build.VERSION.SDK_INT+")"+(Build.VERSION.SDK_INT>=31 ? ", SoC "+Build.SOC_MODEL : "")
            +", page size "+pageSize());
        for (int display=0; display<=MAX_DISPLAY && !destroyed; display++) {
            new File(ROOT,"tmp/.X"+display+"-lock").delete(); new File(ROOT,"tmp/.X11-unix/X"+display).delete();
            log.println("--- Xvfb :"+display);
            Process x=process(ROOT+"/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                ROOT+"/usr/bin/Xvfb",":"+display,"-screen","0","1280x720x24",
                "-shmem","-ac","-nolisten","tcp","-pn");
            XvfbWatch watch=new XvfbWatch(x,log);
            Thread reader=new Thread(watch,"X11-log"); reader.setDaemon(true); reader.start();
            if (!watch.done.await(XVFB_TIMEOUT_S,TimeUnit.SECONDS)) {
                x.destroyForcibly();
                throw new java.io.IOException("The game display could not start: Xvfb :"+display+" did not answer within "
                    +XVFB_TIMEOUT_S+" s. Log: gow3port/logs/android-x11.log");
            }
            if (watch.shmid>=0) {
                xdisplay=display; xvfb=x; shmid=watch.shmid;
                android.util.Log.i("God of War III","Xvfb ready on :"+display+", shmid "+shmid);
                main.post(() -> attach(holder));
                return;
            }
            x.destroyForcibly();
            if (!watch.displayTaken()) {
                String code=x.waitFor(2,TimeUnit.SECONDS) ? String.valueOf(x.exitValue()) : "?";
                throw new java.io.IOException("The game display could not start (Xvfb :"+display+" exited, code "+code
                    +"). Last Xvfb output:\n"+watch.tail()+"\nFull log: gow3port/logs/android-x11.log");
            }
            android.util.Log.w("God of War III","X display :"+display+" is taken by another X server; trying :"+(display+1));
        }
        if (destroyed) return;
        throw new java.io.IOException("The game display could not start: X displays :0-:"+MAX_DISPLAY
            +" are all held by other X servers. Close other Linux/emulator apps (Termux:X11, fexdroid, Winlator…) and retry.");
    }
    private void startX(SurfaceHolder holder) {
        try {
            // FEX and the rootfs (fexdroid's) run on 4 KB pages only.
            if (pageSize()!=4096)
                throw new java.io.IOException("This device uses "+pageSize()/1024+" KB memory pages; the God of War III"
                    +" runtime (FEX, Linux rootfs) needs 4 KB pages.");
            // Before the installer may replace the rootfs they run from.
            killStaleRuntime();
            boolean installed=RuntimeInstaller.ensure(this, new File(FILES), message -> main.post(() -> status.setText(message)));
            android.util.Log.i("God of War III","runtime installed="+installed);
            if (audio!=null) audio.start();
            if (audio==null || !audio.awaitReady(5000))
                throw new java.io.IOException("Android audio bridge did not become ready; see logcat for details.");
            if (!new File(ROOT,"usr/bin/Xvfb").canExecute() ||
                    !new File(BASE,"arm64/bin/gow3-probe").canExecute() ||
                    !new File(BASE,"run-gow3.sh").isFile())
                throw new java.io.IOException("The God of War III runtime is missing. Install the runtime bundle.");
            new File(FILES,"home/gow3port").mkdirs();
            try (PrintWriter passwd=new PrintWriter(new File(ROOT,"etc/passwd"));
                 PrintWriter group=new PrintWriter(new File(ROOT,"etc/group"))) {
                int uid=android.os.Process.myUid();
                passwd.println("root:x:0:0:root:/root:/bin/sh");
                passwd.println("user:x:"+uid+":"+uid+":God of War III:"+FILES+"/home/gow3port:"+ROOT+"/bin/sh");
                group.println("root:x:0:"); group.println("user:x:"+uid+":");
            }
            new File(BASE,"logs").mkdirs();
            startShm();
            startXvfb(holder);
        } catch (Exception ex) {
            // A later surface (leaving and returning) tries again.
            main.post(() -> startingX=false);
            error(ex instanceof java.io.IOException ? ex.getMessage() : ex.toString());
        }
    }
    private void attach(SurfaceHolder holder) {
        if (destroyed || !holder.getSurface().isValid()) return;
        String result=DisplayBridge.start(holder.getSurface(),ROOT+"/tmp/.fxshm/sock",shmid,60,ROOT+"/tmp/fxpresent.sock");
        android.util.Log.i("God of War III",result);
        if (!result.startsWith("bridge running:")) { error(result); return; }
        XInput.connect(xdisplay);
        if (!displayOnly && game==null) worker.execute(() -> startGame());
        pollFrames();
        if (!imePolling) { imePolling=true; pollIme(); }
    }
    // ---- Game folder: the extracted retail game in shared storage ----
    /** Known retail folder names; the selected folder is still validated by eboot.bin. */
    private static final String[] SERIALS={"CUSA01623","CUSA01715","CUSA01720","CUSA01740","CUSA00900","CUSA00207","CUSA00208","CUSA00299",
        "CUSA01363","CUSA03179","CUSA03014","CUSA03023"};
    private static boolean isGame(File dir) { return dir!=null && new File(dir,"eboot.bin").isFile(); }
    private boolean hasGame() {
        if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return gameDir!=null && isGame(new File(gameDir));
    }
    /** Explains, asks for storage access when needed, then opens the folder picker. */
    private void askGameFolder(boolean change) {
        if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_STORAGE);
            return;
        }
        status.setText(change ? "Choose the God of War III game folder." :
            "Choose your God of War III game folder (e.g. CUSA01740, the folder with eboot.bin).");
        status.setVisibility(View.VISIBLE);
        new AlertDialog.Builder(this).setTitle("God of War III game folder")
            .setMessage("Select the extracted game folder (e.g. CUSA01740 or CUSA00900, which contains eboot.bin) or the folder "
                +"that holds it, in your phone's storage."+(gameDir!=null ? "\n\nCurrent: "+gameDir : ""))
            .setPositiveButton("Choose folder",(d,w) -> {
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { startActivityForResult(pick,REQUEST_FOLDER); }
                catch (Exception ex) { error("No folder picker: "+ex); }
            })
            .setNegativeButton(change ? "Cancel" : "Quit",(d,w) -> { if (!change) finish(); })
            .setCancelable(change).show();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code!=REQUEST_STORAGE) return;
        if (results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (hasGame()) startPending(); else askGameFolder(false);
        } else error("God of War III needs storage access to read the game folder.");
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code!=REQUEST_FOLDER) return;
        if (result!=RESULT_OK || data==null || data.getData()==null) { if (!hasGame()) askGameFolder(false); return; }
        File dir=folderOf(data.getData());
        if (dir!=null && !isGame(dir))
            for (String serial : SERIALS) if (isGame(new File(dir,serial))) { dir=new File(dir,serial); break; }
        if (!isGame(dir)) {
            new AlertDialog.Builder(this).setTitle("Not a God of War III folder")
                .setMessage((dir==null ? "That location" : dir.getPath())+" has no eboot.bin.")
                .setPositiveButton("Choose again",(d,w) -> askGameFolder(gameDir!=null)).show();
            return;
        }
        boolean changed=gameDir!=null && !gameDir.equals(dir.getPath());
        gameDir=dir.getPath();
        getSharedPreferences("gow3",MODE_PRIVATE).edit().putString("game_dir",gameDir).apply();
        status.setText("Starting God of War III…");
        if (changed && game!=null) restartGame(); else startPending();
    }
    /** A storage folder of the picker (tree document "primary:Path" or "XXXX-XXXX:Path") as a path. */
    private static File folderOf(android.net.Uri tree) {
        try {
            String id=android.provider.DocumentsContract.getTreeDocumentId(tree);
            int colon=id.indexOf(':');
            String volume=colon<0 ? id : id.substring(0,colon), path=colon<0 ? "" : id.substring(colon+1);
            File base="primary".equalsIgnoreCase(volume) ? android.os.Environment.getExternalStorageDirectory()
                                                         : new File("/storage/"+volume);
            return path.isEmpty() ? base : new File(base,path);
        } catch (Exception ex) { return null; }
    }
    private void startPending() {
        SurfaceHolder h=pendingHolder!=null ? pendingHolder : surface.getHolder();
        pendingHolder=null;
        if (h.getSurface()!=null && h.getSurface().isValid() && !startingX && shmid<0) {
            startingX=true; worker.execute(() -> startX(h));
        }
    }

    private void pollFrames() {
        if (destroyed) return;
        if (!hadError && DisplayBridge.directFrames()>0) status.setVisibility(View.GONE);
        sampleMonitor();
        // run-gow3.sh 在 LSFG 运行时失败、自动回退到普通 Vulkan 时会写这个标记：这时刷新率要退回
        // 「无 LSFG」的目标，否则会停在 LSFG 倍率对应的高刷上空跑（4.1 复评）。
        if (!lsfgFallbackOff && new File(BASE,"lsfg-fallback.marker").isFile()) {
            lsfgFallbackOff=true;
            pickRefreshRate();
        }
        main.postDelayed(this::pollFrames,1000);
    }

    /** The game's text dialog (the character name, GOW3_IME_FILE in run-gow3.sh): shown here with the
     *  touch keyboard. The request holds the maximum length, the title and the current text. */
    private void pollIme() {
        if (destroyed) return;
        File request=new File(BASE,"ime.request");
        if (!imeOpen && request.isFile()) {
            try (BufferedReader r=new BufferedReader(new InputStreamReader(new java.io.FileInputStream(request),"UTF-8"))) {
                int max=Integer.parseInt(r.readLine().trim());
                String title=r.readLine(), initial=r.readLine();
                request.delete();
                showIme(Math.max(1,max),title==null ? "" : title,initial==null ? "" : initial);
            } catch (Exception ex) { android.util.Log.e("God of War III","text request: "+ex); request.delete(); }
        }
        main.postDelayed(this::pollIme,250);
    }
    private void showIme(int max,String title,String initial) {
        imeOpen=true; imeAnswered.set(false); pad.reset(); closeDrawer();
        EditText field=new EditText(this);
        field.setSingleLine(true); field.setText(initial); field.setSelection(field.getText().length());
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        FrameLayout box=new FrameLayout(this); box.setPadding(dp(20),dp(8),dp(20),0); box.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title.isEmpty() ? "Enter text" : title)
            .setView(box)
            .setPositiveButton("OK",(d,w) -> answerIme(true,field.getText().toString()))
            .setNegativeButton("Cancel",(d,w) -> answerIme(false,""))
            .setOnCancelListener(d -> answerIme(false,"")).create();
        field.setOnEditorActionListener((v,action,event) -> {
            if (action!=EditorInfo.IME_ACTION_DONE) return false;
            dialog.dismiss(); answerIme(true,field.getText().toString()); return true;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.setOnDismissListener(d -> { imeOpen=false; immersive(); surface.requestFocus(); });
        dialog.show(); field.requestFocus();
    }
    private void answerIme(boolean ok,String value) {
        if (!imeAnswered.compareAndSet(false,true)) return;
        File temporary=new File(BASE,"ime.request.result.tmp"), result=new File(BASE,"ime.request.result");
        try (java.io.Writer w=new java.io.OutputStreamWriter(new java.io.FileOutputStream(temporary),"UTF-8")) {
            w.write((ok ? "ok" : "cancel")+"\n"+value.replace('\n',' ')+"\n");
        } catch (Exception ex) { android.util.Log.e("God of War III","text answer: "+ex); return; }
        if (!temporary.renameTo(result)) android.util.Log.e("God of War III","text answer: rename failed");
    }
    /** The last value of a gow3.ini setting (as run-gow3.sh reads it), or null. */
    private String setting(String key) {
        File ini = new File(BASE, "gow3.ini");
        long mod = ini.lastModified();
        if (iniCache == null || mod != iniCacheModified) {
            Map<String,String> map = new java.util.LinkedHashMap<>();
            try (BufferedReader r = new BufferedReader(new java.io.FileReader(ini))) {
                for (String line; (line = r.readLine()) != null;) {
                    int eq = line.indexOf('=');
                    if (eq > 0) map.put(line.substring(0, eq), line.substring(eq + 1).trim());
                }
            } catch (Exception ignored) {}
            iniCache = map;
            iniCacheModified = mod;
        }
        return iniCache.get(key);
    }
    /** Frame generation (Lossless Scaling only) presents 2-4 frames per game frame. Lossless
     *  Scaling presents them back to back and FIFO spaces them, evenly only when the display runs at
     *  FPS limit x frames: that mode when the display has it (30x2: 60 Hz, 30x4, 30x3, 60x2: 120 Hz),
     *  else the fastest one (at the system's 60 Hz generated frames were dropped). Without frame
     *  generation, a mode that is a whole multiple of the FPS limit (nearest 120 Hz), so every
     *  frame stays on screen equally long. FSR 3.1 frame generation is not wired on this tree
     *  (shadps4 has no GOW3_FRAME_GEN consumer), so the multiplier is LSFG-only. */
    /** LSFG 是否**真的**会在本次启动启用——与 run-gow3.sh 的 preflight 用同一套规则：倍率 2-4、
     *  运行时的 layer（manifest + liblsfg-vk.so）都在、玩家的 Lossless.dll 在两处查找位置之一。
     *  刷新率必须依此判断，而不是只看 gow3.ini 的 lsfg_multiplier（4.2 复评）。 */
    private boolean lsfgLaunchable() {
        String lsfg=setting("lsfg_multiplier");
        if (!"2".equals(lsfg) && !"3".equals(lsfg) && !"4".equals(lsfg)) return false;
        if (!new File(BASE,"arm64/lsfg/VkLayer_LS_frame_generation.json").isFile()) return false;
        if (!new File(BASE,"arm64/lsfg/liblsfg-vk.so").isFile()) return false;
        return hasFrameGenDll();
    }
    /** 玩家是否已经导入 Lossless.dll——只是给菜单项/刷新率用的只读闸门，不导入、不删除、
     *  也不持有 DLL 状态（那些都在 Home 页的 LosslessDllManager 里）。查找顺序与运行时一致：
     *  游戏目录旁的同名副本优先，其次应用私有的 gow3port 副本（LosslessDllManager.appCopy()
     *  写入的位置），和 run-gow3.sh 的 preflight 是同一套规则。这里不查第三个位置，因为
     *  run-gow3.sh 也不查。 */
    private boolean hasFrameGenDll() {
        File parent=gameDir!=null ? new File(gameDir).getParentFile() : null;
        if (parent!=null && new File(parent,"Lossless.dll").isFile()) return true;
        return new File(BASE,"Lossless.dll").isFile();
    }
    private void pickRefreshRate() {
        String lsfg=setting("lsfg_multiplier");
        boolean lsfgOn=lsfgLaunchable() && !lsfgFallbackOff;
        int frames=lsfgOn ? Integer.parseInt(lsfg) : 1;
        String limit=setting("fps_limit"); // 同 SettingsDialog.FPS_VALUES / 运行时 FPS_LIMITS
        final float target=frames*("30".equals(limit) || "60".equals(limit) || "120".equals(limit)
            || "240".equals(limit) ? Integer.parseInt(limit) : "0".equals(limit) ? 1000 : 30);
        main.post(() -> {
            if (destroyed) return;
            android.view.Display display=Build.VERSION.SDK_INT>=30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            android.view.Display.Mode current=display.getMode();
            int id=0, fastest=0, even=0; float best=0, evenRate=0;
            for (android.view.Display.Mode mode:display.getSupportedModes()) {
                if (mode.getPhysicalWidth()!=current.getPhysicalWidth() || mode.getPhysicalHeight()!=current.getPhysicalHeight()) continue;
                float rate=mode.getRefreshRate();
                if (Math.abs(rate-target)<1) id=mode.getModeId();
                if (rate>best) { best=rate; fastest=mode.getModeId(); }
                // A whole multiple of the frame rate, the one nearest 120 Hz: 60 FPS on a 144 Hz
                // panel shows frames for 2 and 3 refreshes in turn, which judders at a steady 60.
                int multiple=Math.round(rate/target);
                if (multiple>=1 && Math.abs(rate-multiple*target)<1
                        && (even==0 || Math.abs(rate-120)<Math.abs(evenRate-120))) { even=mode.getModeId(); evenRate=rate; }
            }
            // Frame generation: FPS limit x frames, else the fastest mode. Without it: the even
            // multiple, the fastest mode for an unlimited frame rate, else the system's choice (0).
            if (frames>1) { if (id==0) id=fastest; }
            else id="0".equals(limit) ? fastest : even;
            WindowManager.LayoutParams attrs=getWindow().getAttributes();
            if (attrs.preferredDisplayModeId!=id) { attrs.preferredDisplayModeId=id; getWindow().setAttributes(attrs); }
        });
    }
    private void startGame() {
        if (destroyed) return;
        hadError=false;
        // 清掉上一轮的回退标记，并重置「LSFG 已被回退关闭」状态；刷新率按当前 launchability 选。
        lsfgFallbackOff=false;
        new File(BASE,"lsfg-fallback.marker").delete();
        pickRefreshRate();
        main.post(this::resetThermalGuard);
        new File(BASE,"ime.request").delete(); new File(BASE,"ime.request.result").delete();
        try {
            Process launched=process(ROOT+"/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                ROOT+"/bin/sh",BASE+"/run-gow3.sh","game"); game=launched;
            Thread log=new Thread(() -> {
                try (BufferedReader r=new BufferedReader(new InputStreamReader(launched.getInputStream()));
                     PrintWriter out=new PrintWriter(new File(BASE,"logs/android-game.log"))) {
                    // The last lines go into the error message: the cause is usually there.
                    ArrayDeque<String> tail=new ArrayDeque<>();
                    String line;
                    while ((line=r.readLine())!=null) {
                        out.println(line); out.flush();
                        tail.add(line); if (tail.size()>8) tail.removeFirst();
                    }
                    int code=launched.waitFor();
                    if (game==launched) {
                        game=null;
                        String last=tail.isEmpty() ? "" : "\n\n"+String.join("\n",tail);
                        // run-gow3.sh exits 4 when Vulkan/Turnip cannot initialize: the driver
                        // failure must not be reported as validated texture integrity.
                        if (code==4) error("Graphics initialization failed (Vulkan/Turnip). See logs/android-game.log."+last);
                        // 127: the shell found no such command, or the loader could not load a library.
                        else if (code==127) error("Game stopped (127): a runtime program or library is missing."
                            +" See logs/android-game.log."+last);
                        else error("Game stopped ("+code+"). Open the menu to restart."+last);
                    }
                } catch (Exception ex) {
                    // A restart (settings, cheats) kills this process on purpose: its reader is
                    // interrupted then, after the next game started. Only the current game's errors show.
                    if (!destroyed && game==launched) error(ex.toString());
                }
            },"God of War III-log"); log.setDaemon(true); log.start();
        } catch (Exception ex) { error(ex.toString()); }
    }
    private void error(String message) {
        hadError=true;
        android.util.Log.e("God of War III",message);
        main.post(() -> { if (!destroyed) { status.setText(message); status.setVisibility(View.VISIBLE); } });
    }
    private void stopGame() {
        Process p=game; game=null;
        if (p!=null) { p.destroyForcibly(); try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
    @Override protected void onDestroy() {
        destroyed=true; pad.reset(); audio.stop(); main.removeCallbacksAndMessages(null);
        if (thermalListener!=null && Build.VERSION.SDK_INT>=29) {
            android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
            if (power!=null) power.removeThermalStatusListener((android.os.PowerManager.OnThermalStatusChangedListener)thermalListener);
        }
        DisplayBridge.stop(); stopGame(); if (xvfb!=null) xvfb.destroyForcibly();
        worker.shutdownNow(); super.onDestroy();
    }
}
