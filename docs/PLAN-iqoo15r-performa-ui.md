# Rencana: performa lebih kencang + stabil di iQOO 15R, dan UI launcher modern dengan tab Performa

## Konteks

Log terakhir (build-11, 18 Okt) menunjukkan semua perbaikan sebelumnya **aktif**: patch 720p `validated`, `kgsl: power constraint max … set`, `CPU: FEX profile fast`. Ada tiga masalah yang menghambat performa stabil, dan empat opsi launcher yang ternyata palsu:

1. **Suhu.** `android-thermal.log`: status termal naik 0 → **4 (CRITICAL)** dalam 5–6 menit di setiap sesi, headroom 1.1–1.2 (throttling aktif). Begitu throttling, FPS pasti turun dan tidak stabil.
2. **LSFG x3 dengan fps_limit 60** artinya target 180 Hz, padahal panel maksimal 144 Hz. Pacing jadi tidak rata, dan GPU yang sudah panas mendapat beban ekstra.
3. **Tidak ada data FPS.** Baris `Frame stats` hanya dicetak kalau `GOW3_FRAME_STATS=1` (`driver.cpp:312`), dan launcher belum punya opsi untuk itu.
4. **Opsi launcher yang tidak berfungsi:**
   - **Upscaler**: `launch_env.py:40` selalu memaksa `GOW3_UPSCALER=none` (log: `upscaler=none(android-safe-default)`).
   - **800p**: tidak ada "Resolution Patch - 800p" di `patches/God_of_War_III_Remastered.xml`.
   - **Offline start / Free camera**: tidak dibaca kode apa pun (`SettingsDialog.java:287` sendiri mencatatnya).
   - **Skip intros**: belum terhubung, padahal XML punya patch "Skip Intro".
   - `effect_*` di profil ini juga tidak dibaca siapa pun.

Keputusan dan permintaan Anda:
- **Seluruh UI berbahasa Indonesia** (default).
- **Thermal Guard otomatis** turun ke 30 saat panas, lalu naik lagi ke 60.
- **Tema gaming modern dengan aksen biru langit**.
- **Ikon launcher baru**.
- **Monitor suhu HP, GPU, RAM, dan lainnya**, di launcher dan di dalam game.

## Bagian 1: Performa dan stabilitas

### P1. Thermal Guard (otomatis 60 → 30 → 60)
- **Launcher** (`GameActivity.java`, memakai `watchThermal()`/`logThermal()` yang sudah ada): kalau `thermal_guard` ≠ 0 dan status ≥ 3 (SEVERE), tulis `gow3port/thermal.cap` berisi `30`. Kalau status ≤ 1 (LIGHT), hapus file itu. Ada histeresis, dan setiap perubahan dicatat di `android-thermal.log` (`Thermal guard: cap 30` / `released`).
- **Skrip** (`tools/android/device/run-gow3.sh`): `export GOW3_THERMAL_CAP_FILE="$base/thermal.cap"`. File sisa dari sesi sebelumnya dihapus saat start.
- **Native:**
  - `gpu/shim/gow3_graphics.h`: tambah fungsi murni `ThermalLimit(int present_limit, int cap)`. Kalau `cap > 0` dan limit 0 atau lebih besar dari cap, hasilnya `cap`.
  - `gpu/shim/gow3_settings.{h,cpp}`: tambah `int ThermalCap()` yang membaca file itu paling sering sekali per detik (`steady_clock`). Kalau file tidak ada, hasilnya 0.
  - `driver.cpp` `PresentThread`: lambda `current_limit` dibungkus `ThermalLimit(..., Gow3Settings::ThermalCap())`. Perubahan limit live sudah ditangani loop ini (cetak `VideoOut: frame limit N FPS`).
  - Batas 30 sudah termasuk `FpsLimits`, jadi tidak ada jalur sub-vblank baru (`immediate_flips` tetap off).
- **Test:** kasus `ThermalLimit` ditambahkan di `tests/test_graphics_settings.cpp`, yang ikut dijalankan di launcher-check.

### P2. Preset performa (`perf_preset`)
Data murni di `data/PerformancePresets.kt`: peta key ke nilai, ditulis lewat `Gow3Ini.write`. Kalau ada nilai yang menyimpang dari preset, statusnya "Kustom".

| Preset | Resolusi | FPS limit | Frames queued | GPU boost | Sustained | Thermal guard | LSFG |
|---|---|---|---|---|---|---|---|
| **Performa Maks** | 720p | 60 | 2 | on | off | on | off |
| **Seimbang (default iQOO 15R)** | 720p | 60 | 2 | on | on | on | off |
| **Dingin & Stabil** | 720p | 30 | 2 | off | on | on | x2 (jika DLL ada) → 60 di layar, panel 60 Hz |

Unit test Kotlin (`PerformancePresetsTest`): setiap preset hanya memakai key dan nilai yang memang dibaca runtime; deteksi "Kustom" benar.

### P3. Skip Intro yang berfungsi
`scripts/launch_env.py`: kalau `skip_intro=1` dan statusnya `validated`, tambahkan patch "Skip Intro" (ada di XML). Test baru di `tests/test_launch_env.py`.

### P4. Diagnostik terukur
- `frame_stats=1` → `run-gow3.sh` mengekspor `GOW3_FRAME_STATS=1`, sehingga `Frame stats:` tercatat setiap 5 detik.
- Toggle HUD di launcher memakai key yang sudah dibaca `gow3_settings.cpp`: `show_fps`, `overlay_frametime`, `overlay_gpu`, `overlay_cpu`.

### P5. LSFG yang masuk akal
Pilihan multiplier memperhitungkan panel. Kalau `fps_limit × multiplier` melebihi refresh maksimum layar, launcher menampilkan peringatan dan menyarankan x2. Preset Dingin memakai 30 × 2.

### P6. Bersihkan opsi palsu
- Di UI Android: hapus Upscaler, 800p, Offline start, dan Free camera. Tambahkan 480p sebagai opsi "ringan".
- Hapus `effect_*` dari `gow3-default.ini` dan `gow3-iqoo15r.ini`.
- `gow3-iqoo15r.ini` diubah ke preset Seimbang.

### P7. Monitor perangkat (suhu, GPU, RAM, FPS)
Pembaca data baru di Java, `ro/cobrabm/fexdroid/DeviceStats.java`. Path file bisa disuntikkan supaya bisa diuji, dan dipakai oleh launcher (Kotlin) maupun game. Setiap sumber gagal dengan anggun dan tampil "—" kalau diblokir SELinux:

| Data | Sumber (tanpa root) |
|---|---|
| Suhu HP (°C) | `ACTION_BATTERY_CHANGED` → `EXTRA_TEMPERATURE` (selalu tersedia); zona `/sys/class/thermal/thermal_zone*/temp` (CPU/GPU) kalau bisa dibaca |
| Status termal + headroom | `PowerManager.getCurrentThermalStatus()` / `getThermalHeadroom()` (sudah dipakai di `logThermal`) |
| Beban & clock GPU | `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`, `…/devfreq/cur_freq` (atau `gpuclk`), kalau bisa dibaca |
| RAM HP | `ActivityManager.MemoryInfo` (terpakai / total) |
| RAM game | `VmRSS` dari `/proc/<pid>/status` milik proses game (uid sama) |
| FPS layar | Selisih `DisplayBridge.frames()` per detik (`pollFrames()` sudah berjalan setiap 1 detik) |

- **Di launcher:** kartu "Monitor Perangkat" di tab Performa diperbarui setiap 2 detik, dan chip ringkas (suhu, RAM) ada di Beranda.
- **Di dalam game:** HUD Android baru (`HudOverlay.java`, View di atas SurfaceView seperti `TouchControllerOverlay`). Isinya FPS, suhu °C dengan warna sesuai status, GPU %/MHz, RAM game/HP, dan lencana "Thermal Guard aktif". Bisa dinyalakan/dimatikan (`hud_monitor`, default on) dari launcher dan dari laci menu in-game. Ini tidak mengubah kode native.
- **Test:** `DeviceStatsTest` (JVM) memakai file sysfs/proc palsu, termasuk kasus file tidak terbaca.

## Bagian 2: UI/UX modern

### Tema gaming biru langit dan ikon baru
- `LauncherColors` diganti palet gelap-biru: latar `#070B12`/`#0B1220`, permukaan `#111A2B`, aksen biru langit `#38BDF8` (sorotan `#7DD3FC`, tekan `#0EA5E9`), teks `#E6F1FF`/`#8A9BB4`. Status memakai hijau `#34D399`, kuning `#FBBF24`, dan merah `#F87171`.
- Wallpaper tetap dipakai, dengan gradien biru gelap di atasnya. Kartu memakai border biru tipis dan efek glow pada elemen aktif.
- Warna in-game (`GameActivity` laci menu dan teks status, `TouchControllerOverlay`) disamakan ke aksen biru.
- **Ikon:** adaptive icon vector baru (`ic_launcher_background.xml`: navy dengan glow biru radial; `ic_launcher_foreground.xml`: simbol Ω bergaya dengan bilah dan "III", biru langit; lapisan monochrome untuk themed icon). `AndroidManifest.xml` diubah ke `@mipmap/ic_launcher` + `roundIcon`, dan `drawable/icon.xml` diselaraskan.

### Bahasa Indonesia di seluruh UI
- `res/values-in/strings.xml` memuat semua string. `LanguageManager` mendapat `ID = "in"`, yang menjadi **default** kalau belum ada pilihan. English dan Mandarin tetap bisa dipilih di Tentang.
- Teks UI yang masih hard-coded di `GameActivity.java`, `SettingsDialog.java`, dan `RuntimeInstaller` (pesan ke pemain, item laci menu, dialog folder) dipindah ke string resource dan diterjemahkan. Baris log teknis tetap berbahasa Inggris supaya diagnosis tidak berubah.

- **Navigasi bawah:** Beranda · **Performa** · Tentang (`ui/LauncherScreen.kt`, `LauncherTab`).
- **Beranda** (`feature/home/HomeScreen.kt`): judul, tombol MAINKAN, kartu Game (folder game/update), dan strip status berisi chip preset aktif, suhu saat ini, dan resolusi/FPS. `LaunchParameters` dipindah dari Beranda.
- **Tab Performa** (baru, `feature/performance/`): `TabRow` Material3 dengan 5 sub-tab.
  1. **Preset**: tiga kartu preset dengan deskripsi singkat, dan panel perangkat (SoC, refresh rate yang tersedia, status suhu dari `PowerManager`).
  2. **Grafis**: resolusi, FPS limit, engine FPS, VSync, frames queued.
  3. **Sistem**: GPU boost, mode CPU, sustained performance, Thermal Guard.
  4. **Frame Gen**: kartu `FrameGenerationCard` yang ada, plus multiplier, flow scale, performance mode, dan peringatan refresh dari P5.
  5. **Diagnostik**: HUD FPS/frametime/GPU/CPU dan log Frame stats.
- **Komponen bersama** di `ui/components/`: `SectionCard`, `SettingSwitch`, `SettingSegmented`, `SettingDropdown`, `PresetCard`, `StatusChip`. Memakai palet `LauncherColors` baru (gelap-biru langit). Tidak ada dependensi baru (tanpa material-icons-extended), jadi build tetap aman.
- **Diagnostik** (sub-tab kelima) juga berisi kartu Monitor Perangkat dari P7.
- Test Python baru: setiap string di `values/` harus ada di `values-in/` dan `values-zh-rCN/`.

## Gerbang kualitas (sama seperti sebelumnya, ditambah yang baru)

- Edit kode memakai tool Edit; `bash -n`/`sh -n` untuk skrip.
- Test C++ lokal dengan `python -m ziglang c++` sebelum push.
- Test Python lokal dijalankan sebelum push.
- launcher-check di CI (assembleRelease + testDebugUnitTest + C++ + Python) harus hijau sebelum build native jalan.
- Tiga push terpisah, masing-masing ditunggu sampai hijau:
  1. Bagian 1 backend + test (P1–P6);
  2. Monitor + HUD (P7);
  3. Tema, ikon, tab, dan bahasa.

  APK diserahkan hanya kalau build penuh dan publish hijau.
- Risiko native dijaga kecil: Thermal Guard hanya mengubah nilai limit yang sudah didukung (30), dibaca paling sering sekali per detik, dan kalau file tidak ada perilakunya sama persis dengan sekarang.

## Verifikasi di HP

1. Di `android-game.log`:
   - `GPU: KGSL power constraint …` sesuai preset;
   - `Frame stats:` setiap 5 detik (kalau diaktifkan);
   - saat panas muncul `VideoOut: frame limit 30 FPS`, lalu kembali `60` setelah dingin.
2. Di `android-thermal.log`: `Thermal guard: cap 30` / `released`, dan status tidak lagi bertahan di 4.
3. Patch "Skip Intro" tercantum di baris `Patches:` kalau Skip intros on.
4. UI: tiga tab bawah, lima sub-tab Performa, preset mengubah nilai dan label "Kustom" muncul saat diubah manual, seluruh teks berbahasa Indonesia secara default, aksen biru langit, ikon baru di layar utama HP.
5. Monitor: di launcher, suhu °C dan RAM berubah setiap 2 detik. Di game, HUD menampilkan FPS, suhu, GPU, dan RAM. Data yang diblokir sistem tampil "—" dan tidak membuat crash.
