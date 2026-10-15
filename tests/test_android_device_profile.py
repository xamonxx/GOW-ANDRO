import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/android/device/run-gow3.sh'


class DeviceProfileTests(unittest.TestCase):
    def test_device_profile_only_seeds_a_missing_gow3_ini(self):
        script = SCRIPT.read_text(encoding='utf-8')
        self.assertIn('if [ ! -f "$GOW3_CONFIG" ]; then', script)
        self.assertIn('seed="$base/gow3.$device_profile.ini"', script)
        # The broken form copied over the player's gow3.ini on every launch.
        self.assertNotIn('[ -f "$GOW3_CONFIG" ] || [ -f "$device_config" ] && cp', script)

    def test_runtime_environment_is_kept(self):
        script = SCRIPT.read_text(encoding='utf-8')
        manifest = json.loads((ROOT / 'tools/android/runtime/runtime-manifest.json').read_text(encoding='utf-8'))
        self.assertIn('/pulseaudio:', manifest['environment']['LD_LIBRARY_PATH'])
        self.assertIn('export LD_LIBRARY_PATH="$arch/lib:$host/usr/lib/aarch64-linux-gnu:'
                      '$host/usr/lib/aarch64-linux-gnu/pulseaudio:$host/usr/lib"', script)
        self.assertIn('export VK_DRIVER_FILES="$icd"', script)
        self.assertIn("export GLIBC_TUNABLES='glibc.cpu.hwcaps=-AVX2,", script)

    def test_display_follows_the_launcher_xvfb(self):
        script = SCRIPT.read_text(encoding='utf-8')
        self.assertIn('DISPLAY=${GOW3_DISPLAY:-:0}', script)
        activity = (ROOT / 'android-launcher/app/src/main/java/ro/cobrabm/fexdroid/GameActivity.java'
                    ).read_text(encoding='utf-8')
        self.assertIn('env.put("GOW3_DISPLAY",":"+xdisplay)', activity)
        self.assertIn('XInput.connect(xdisplay)', activity)

    def test_iqoo15r_profile_is_packaged_with_valid_values(self):
        assembler = (ROOT / 'tools/android/runtime/build_release_runtime.py').read_text(encoding='utf-8')
        self.assertIn("('tools/android/config/gow3-iqoo15r.ini', 'gow3.iqoo15r.ini')", assembler)
        for manifest in ('tools/android/runtime/runtime-manifest.json', 'tools/android/debug/runtime-manifest.json'):
            files = json.loads((ROOT / manifest).read_text(encoding='utf-8'))['required_paths']
            self.assertIn('gow3port/gow3.iqoo15r.ini', [entry['path'] for entry in files], manifest)
        raw = (ROOT / 'tools/android/config/gow3-iqoo15r.ini').read_bytes()
        self.assertNotIn(b'\r', raw)
        values = dict(line.split('=', 1) for line in raw.decode().splitlines()
                      if line and not line.startswith('#'))
        # The launcher's Xvfb is 1280x720; the runtime knows these resolution names only.
        self.assertEqual(values['output_res'], '1280x720')
        self.assertIn(values['render_resolution'], ('native', '480p', '720p', '800p', '1440p', '1800p', '4K'))
        self.assertIn(values['fps_limit'], ('30', '60', '120', '240', '0'))
        self.assertIn(values['engine_fps'], ('60', '120', '240'))

    def test_performance_defaults_match_the_launcher(self):
        # gow3-default.ini seeds gow3.ini; LaunchParams.kt shows the same values when a key is missing.
        params = (ROOT / 'android-launcher/app/src/main/java/com/gow3/android/data/LaunchParams.kt'
                  ).read_text(encoding='utf-8')
        for name in ('gow3-default.ini', 'gow3-iqoo15r.ini'):
            raw = (ROOT / 'tools/android/config' / name).read_text(encoding='utf-8')
            values = dict(line.split('=', 1) for line in raw.splitlines() if line and not line.startswith('#'))
            self.assertEqual((values['frames_queued'], values['gpu_boost'], values['cpu_mode']), ('2', '1', 'fast'), name)
        for key, default in (('frames_queued', '2'), ('gpu_boost', '1'), ('cpu_mode', 'fast'), ('sustained_perf', '0')):
            block = params[params.index(f'key = "{key}"'):]
            block = block[:block.index('LaunchParam(') if 'LaunchParam(' in block else len(block)]
            self.assertIn(f'defaultValue = "{default}"', block, key)

    def test_run_script_applies_gpu_boost_and_fex_profile(self):
        script = SCRIPT.read_text(encoding='utf-8')
        self.assertIn('export TU_KGSL_PWR_CONSTRAINT=max', script)
        self.assertIn('if [ "$(setting gpu_boost)" = 0 ]; then', script)
        self.assertIn('export FEX_APP_CONFIG="$fex_profile"', script)
        self.assertIn('fex_profile="$base/gow3-fex-compatible.json"', script)
        manifest = json.loads((ROOT / 'tools/android/runtime/runtime-manifest.json').read_text(encoding='utf-8'))
        self.assertIn('gow3port/gow3-fex-compatible.json', [entry['path'] for entry in manifest['required_paths']])
        profile = json.loads((ROOT / 'tools/android/config/gow3-fex-compatible.json').read_text(encoding='utf-8'))
        self.assertEqual(profile['Config']['TSOEnabled'], '0')

    def test_game_mode_config_is_declared(self):
        manifest = (ROOT / 'android-launcher/app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
        self.assertIn('android:name="android.game_mode_config"', manifest)
        config = (ROOT / 'android-launcher/app/src/main/res/xml/game_mode_config.xml').read_text(encoding='utf-8')
        self.assertIn('android:supportsPerformanceGameMode="true"', config)
        self.assertIn('android:allowGameFpsOverride="false"', config)


if __name__ == '__main__':
    unittest.main()
