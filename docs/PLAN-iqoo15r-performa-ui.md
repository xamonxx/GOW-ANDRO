# Rencana: performa lebih kencang + stabil di iQOO 15R, dan UI launcher modern dengan tab Performa

## Konteks

> Pembaruan 18 Okt: ditambah **Bagian 1C (fitur cerdas: Smart FPS prediktif, laporan sesi + saran, rekomendasi otomatis, pemulihan crash, LSFG otomatis, backup save, sadar baterai)** dan **Bagian 1B**, yaitu penyesuaian dengan spek iQOO 15R dari laporan DevCheck (2 inti prime + 6 performa, RAM 12 GB, USB 2.0, Adreno 829, panel 144 Hz).

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

## Bagian 1B: Disesuaikan dengan spek iQOO 15R (laporan DevCheck 16 Okt)

Spek terukur:
- Snapdragon 8 Gen 5 (SM8845, 3 nm), 8 inti Oryon Gen 3: **2 prime hingga 3,80 GHz** dan **6 performa hingga 3,32 GHz**, governor `walt`.
- Adreno 829 (chip KGSL `0x44030a20`, Turnip gen8).
- **RAM 12 GB** LPDDR5X.
- UFS 256 GB (f2fs).
- Android 16, kernel 6.12 dengan page 4 KB.
- Baterai 7600 mAh.
- **USB Type-C 2.0**.
- Panel 144 Hz.

### P8. Inti prime untuk thread game, helper di inti performa
- Saat ini thread helper GPU bebas berjalan di mana saja. `vk_draw_prep.cpp` membuat 4 worker + scanner (`Available()/2`), `vk_pipeline_cache.cpp` membuat hingga 6 thread warm-up, dan `gow3_copy.cpp` membuat thread copy. Semuanya bisa merebut 2 inti prime dari thread utama game (FEX, bergantung kecepatan satu inti) dan dari thread GPU (`GpuCommandProcessor`, `VkRecorder`).
- `gpu/shim/gow3_threads.h`: tambah `PrimeCores()`, yang membaca `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` dan menandai inti dengan frekuensi tertinggi. Kalau tidak terbaca atau semua inti sama, hasilnya kosong dan **tidak ada perubahan**. Tambah juga `KeepOffPrimeCores()`, yang memakai `sched_setaffinity` untuk thread pemanggil ke inti non-prime (di HP ini 6 inti 3,32 GHz).
- Dipanggil di tempat helper sudah memanggil `Gow3Threads::MakeBackground()` (`vk_draw_prep.cpp:329/383`), di thread warm-up/compile `vk_pipeline_cache.cpp`, dan di thread copy `gow3_copy.cpp`.
- Thread kritis **tidak dipin**: governor `walt` tetap bebas menaruhnya di inti prime, yang sekarang kosong dari helper. Hasilnya lebih aman daripada pin keras.
- Key `cpu_placement` (default 1) → `run-gow3.sh` mengekspor `GOW3_CPU_PLACEMENT`. Log mencatat `CPU: prime cores 6-7, helpers on 0-5` atau `CPU: placement off/unavailable`.
- **Test:** fungsi murni pemilihan inti dari daftar frekuensi (misalnya `{3321,…,3801,3801}` → {6,7}) ditambahkan di `tests/test_graphics_settings.cpp` (sudah dijalankan di launcher-check).

### P9. Memanfaatkan RAM 12 GB
- `GOW3_DEVICE_MEM_MB` sudah diambil dari `/proc/meminfo`. Pada 12 GB, DMEM 7104 MiB untuk patch 720p aman.
- Kalau RAM ≥ 11 GB, `run-gow3.sh` menaikkan batas `GOW3_PIPELINE_CACHE`/warm-up dan antrean deferred readback (`deferred image readback … 64 copies, 64 MiB`) ke nilai desktop. Hanya key/env yang memang dibaca kode yang dipakai. Sebelum implementasi, saya verifikasi nama env-nya di `vk_presenter.cpp`/`vk_pipeline_cache.cpp`. Kalau ternyata tidak ada knob, poin ini dibatalkan, bukan dipalsukan.
- Di HP 8 GB, nilai default tetap dipakai.

### P10. Game di USB 2.0: deteksi dan saran
- Folder game Anda ada di `/storage/FC000E65000E276C/...`. iQOO 15R tidak punya slot kartu, jadi itu volume USB (OTG) dengan **USB 2.0** (sekitar 30–40 MB/s, latensi tinggi). GoW3 men-streaming aset saat bermain, sehingga pembacaan lambat bisa menimbulkan patah-patah dan loading lama.
- Launcher memeriksa `StorageManager.getStorageVolume(folder).isRemovable`. Kalau removable, kartu Game di Beranda dan sub-tab Preset menampilkan peringatan "Game ada di penyimpanan USB, pindahkan ke memori internal (UFS) supaya loading lebih cepat". Isinya informasi saja, tidak memindahkan file.
- `run-gow3.sh` mencatat `Storage: game on <volume> (removable)` di log.

### P11. Eksperimen render GPU Adreno 829 (Diagnostik)
- Opsi "Mode render GPU": Otomatis (default) / GMEM / SYSMEM, dipetakan ke `TU_DEBUG=gmem|sysmem` (flag Turnip). Opsi ini hanya untuk uji A/B dengan log Frame stats dan diberi label eksperimental. Default tidak mengubah apa pun.
- Refresh panel 144 Hz: G1 dari build sebelumnya sudah memilih 120 Hz untuk 60 fps. Thermal Guard 30 fps juga rata di 120 Hz (4×).

### P12. Default iQOO 15R yang diperbarui
- `gow3-iqoo15r.ini` = preset **Seimbang** + `cpu_placement=1` + `thermal_guard=1` + `hud_monitor=1`, dan `effect_*` dihapus.
- Profil ini juga dipakai untuk chip `0x44030a20` (Adreno 829) kalau properti `ro.product.model=I2508` terbaca. Deteksi di `run-gow3.sh` ditambah model `I2508` (iQOO 15R), karena nama market belum tentu terbaca.

## Bagian 1C: Fitur cerdas (memutuskan dari data, bukan sekadar toggle)

Semua fitur ini memakai sampler satu sumber: `pollFrames()` di `GameActivity` (sudah berjalan setiap 1 detik) mencatat FPS layar (selisih `DisplayBridge.frames()`), suhu baterai, status termal, headroom prakiraan, dan cap aktif ke buffer ring. Tidak ada thread atau kode native baru.

### P13. Smart FPS (pengganti logika P1 berbasis status)
- **Prediktif:** memakai `getThermalHeadroom(10)` (prakiraan 10 detik ke depan, sudah dipakai di `logThermal`). Kalau prakiraan ≥ 0,95 selama 15 detik, cap 30 dipasang **sebelum** HP masuk SEVERE dan throttling. Status ≥ SEVERE tetap memicu cap langsung sebagai pengaman.
- **Stabilitas FPS:** kalau suhu aman tapi FPS layar rata-rata < 50 selama 20 detik (game tidak mampu mempertahankan 60), cap juga turun ke 30. 30 yang rata terasa lebih mulus daripada 40–55 yang naik turun di layar 120 Hz.
- **Pulih:** cap dilepas kalau headroom < 0,75, status ≤ LIGHT, dan minimal 60 detik sejak cap dipasang (histeresis, supaya tidak naik turun terus).
- Memakai mekanisme file `thermal.cap` dan native `ThermalLimit` dari P1 (tanpa perubahan native tambahan). Key `smart_fps` (default 1) menggantikan `thermal_guard`; nilai `thermal_guard` lama tetap dibaca.
- **Test:** logika keputusan dipisah sebagai kelas Java murni `SmartFps` (input sampel → cap) dengan `SmartFpsTest` (JVM): naik, turun, histeresis, dan kasus data kosong.

### P14. Laporan sesi + saran cerdas
- Saat game berhenti, sampler menulis `logs/session-last.json`: durasi, FPS rata-rata dan 1% terendah, persentase waktu ≥ 55 FPS, suhu maksimal, waktu dalam SEVERE/CRITICAL, jumlah cap Smart FPS, dan kode keluar.
- Beranda menampilkan kartu **"Sesi terakhir"** dengan ringkasan dan **saran yang bisa diterapkan sekali ketuk**. Mesin aturannya murni Kotlin, `data/Advisor.kt`, misalnya:
  - lama di CRITICAL → "Pakai preset Dingin & Stabil" [Terapkan];
  - FPS < 50 padahal suhu aman → "Turunkan resolusi ke 480p" [Terapkan];
  - LSFG multiplier × FPS > refresh panel → "Ganti ke x2" [Terapkan];
  - game di USB → "Pindahkan ke memori internal" (info);
  - crash berulang → lihat P16.
- **Test:** `AdvisorTest` dengan beberapa contoh sesi menghasilkan saran yang tepat, dan saran tidak muncul tanpa data.

### P15. Rekomendasi otomatis saat pertama kali dibuka
- Saat belum ada `perf_preset`, launcher memilih preset dari perangkat: SoC (`Build.SOC_MODEL`), RAM (`MemoryInfo.totalMem`), refresh maksimal panel, dan dukungan sustained mode. SM8845 dengan 12 GB menghasilkan **Seimbang**; RAM < 10 GB atau SoC lain menghasilkan **Dingin & Stabil**.
- Alasan pemilihan ditampilkan ("Dipilih otomatis untuk Snapdragon 8 Gen 5 + 12 GB RAM"). Pemain bisa mengubahnya kapan saja.

### P16. Pemulihan cerdas saat crash
- Riwayat kode keluar disimpan di `gow3port/launch-history` (5 terakhir).
- Kalau game keluar tidak normal (bukan 0, bukan dihentikan pemain) **2 kali berturut-turut** dengan `cpu_mode=fast`, launcher menawarkan dialog "Game sering berhenti. Coba mode CPU Akurat?" [Ganti & mulai ulang]. Mode Fast mematikan emulasi TSO, yang bisa memicu race.
- Kode 4 (Vulkan gagal) memunculkan saran mengembalikan "Mode render GPU" ke Otomatis (P11). Kode 127 menampilkan bahwa library runtime tidak cocok (sudah ditampilkan baris log terakhirnya).
- Tidak ada perubahan otomatis tanpa persetujuan pemain.

### P17. Multiplier LSFG otomatis
- Opsi "Otomatis" di Frame Gen memilih multiplier terbesar sehingga `fps_cap × multiplier ≤ refresh maksimal panel` dan hasilnya kelipatan rata. Contohnya 60 → x2 (120 Hz), dan saat Smart FPS memasang cap 30 → x4 tetap 120 Hz.
- Saat cap berubah di tengah permainan, `lsfg-vk.toml` (dipantau live oleh lsfg-vk, lihat komentar di `run-gow3.sh`) diperbarui, sehingga gerak di layar tetap 120 Hz walaupun game turun ke 30.

### P18. Backup save otomatis sebelum bermain
- Sebelum setiap peluncuran, `SaveManager`/`SaveArchive` (sudah ada, dipakai kartu Save di Tentang) membuat cadangan otomatis dan menyimpan 5 terakhir.
- Tombol "Pulihkan" sudah ada di kartu Save.
- Kalau folder save kosong, langkah ini dilewati.

### P19. Sadar baterai
- Kalau baterai < 20% dan tidak mengecas, Beranda menyarankan preset Dingin.
- Kalau sedang mengecas dan suhu > 40 °C, Beranda menyarankan **bypass charging** (didukung iQOO 15R) atau melepas charger.
- Data dari `ACTION_BATTERY_CHANGED` (sumber yang sama dengan monitor P7).

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
- Empat push terpisah, masing-masing ditunggu sampai hijau:
  1. Bagian 1 + 1B backend + test (P1–P6, P8–P12);
  2. Monitor + HUD + sampler (P7);
  3. Fitur cerdas (P13–P19) dengan unit test logika murni (`SmartFps`, `Advisor`, rekomendasi preset);
  4. Tema, ikon, tab, dan bahasa.

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
5. Spek: log memuat `CPU: prime cores 6-7, helpers on 0-5` dan `Storage: game on … (removable)`. Bandingkan `Frame stats:` dengan `cpu_placement` on vs off, serta mode render Otomatis vs GMEM vs SYSMEM.
6. Cerdas:
   - `android-thermal.log` memuat `Smart FPS: cap 30 (forecast 0.96)` sebelum status mencapai 3, lalu `released`;
   - kartu "Sesi terakhir" muncul setelah keluar game, beserta saran yang bisa diterapkan;
   - dua crash berturut-turut dengan mode Fast memunculkan tawaran mode Akurat;
   - folder backup save bertambah setiap kali bermain (maksimal 5).
7. Monitor: di launcher, suhu °C dan RAM berubah setiap 2 detik. Di game, HUD menampilkan FPS, suhu, GPU, dan RAM. Data yang diblokir sistem tampil "—" dan tidak membuat crash.
