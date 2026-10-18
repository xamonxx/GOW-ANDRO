"""The LSFG launchability contract must hold across all three launch layers.

The frame-generation multiplier is evaluated in three places: the shell preflight
(run-gow3.sh), the native LSFG failure path (vk_swapchain.cpp), and the Android
launcher's display-mode choice (GameActivity). They have to agree, otherwise the
device either runs a high refresh rate the game never reaches, or silently drops
the layer the script enabled.

These tests need neither a device nor game content: they pin the contract itself
(the shared rules, the exit code, and the one-shot fallback guard) as source-level
assertions, matching the style of tests/test_android_build_inputs.py.
"""

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/android/device/run-gow3.sh'
VK_SWAPCHAIN = ROOT / 'gpu/shadps4/video_core/renderer_vulkan/vk_swapchain.cpp'
VK_PRESENTER = ROOT / 'gpu/shadps4/video_core/renderer_vulkan/vk_presenter.cpp'
GAME_ACTIVITY = (ROOT / 'android-launcher/app/src/main/java/ro/cobrabm/fexdroid'
                 '/GameActivity.java')

# The manifest and library the layer needs; the shell, the launcher's preflight and
# the runtime installer all look for exactly this pair.
LAYER_MANIFEST = 'VkLayer_LS_frame_generation.json'
LAYER_LIBRARY = 'liblsfg-vk.so'
# A marker run-gow3.sh writes when it drops frame generation, and the launcher consumes.
FALLBACK_MARKER = 'lsfg-fallback.marker'


def _read(path):
    return path.read_text()


def _strip_cpp_comments(text):
    """Drop C++ comments so an assertion cannot be satisfied (or failed) by prose."""
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)


class LsfgPreflightTests(unittest.TestCase):
    """Every layer must gate on the same inputs before claiming LSFG is on."""

    def test_shell_gates_on_multiplier_manifest_and_library(self):
        script = _read(SCRIPT)
        # Only 2/3/4 enable the layer; anything else (0, 1, garbage) falls through to off.
        self.assertRegex(script, r'case \$lsfg in\s*2\|3\|4\)')
        # The manifest and the .so are both required, not just the manifest.
        self.assertIn(LAYER_MANIFEST, script)
        self.assertIn(LAYER_LIBRARY, script)
        self.assertRegex(script, r'\[ -f "\$lsfg_layer/' + LAYER_MANIFEST + r'" \]')
        self.assertRegex(script, r'\[ -f "\$lsfg_layer/' + LAYER_LIBRARY + r'" \]')

    def test_shell_and_launcher_check_both_dll_locations_in_the_same_order(self):
        script = _read(SCRIPT)
        activity = _read(GAME_ACTIVITY)
        # run-gow3.sh: next to the game folder first, then the app-private gow3port copy.
        dll_loop = re.search(r'for dll in "(.*?)"; do', script)
        self.assertIsNotNone(dll_loop, 'run-gow3.sh must probe both DLL locations')
        shell_locations = dll_loop.group(1)
        self.assertIn('${GOW3_GAME_DIR%/*}/Lossless.dll', shell_locations)
        self.assertIn('$base/Lossless.dll', shell_locations)
        # The launcher mirrors the same order: game-dir parent first, then BASE.
        self.assertIn('new File(parent,"Lossless.dll")', activity)
        self.assertIn('new File(BASE,"Lossless.dll")', activity)
        self.assertLess(activity.index('new File(parent,"Lossless.dll")'),
                        activity.index('new File(BASE,"Lossless.dll")'),
                        'the game-folder DLL must be preferred, matching the script')

    def test_launcher_only_accepts_the_multipliers_the_script_accepts(self):
        activity = _read(GAME_ACTIVITY)
        # lsfgMultiplier() resolves gow3.ini: 2-4 as set, "auto" through LsfgAuto (2-4 or 0).
        resolve = activity[activity.index('private int lsfgMultiplier(int fps)'):
                           activity.index('private float[] displayRates()')]
        for multiplier in ('2', '3', '4'):
            self.assertIn(f'"{multiplier}".equals(value)', resolve)
        self.assertIn('"auto".equals(value) ? LsfgAuto.pick(', resolve)
        # 0/1 and garbage must be rejected by the launcher's launchability check.
        preflight = activity[activity.index('private boolean lsfgLaunchable()'):
                             activity.index('private void pickRefreshRate()')]
        self.assertIn('lsfgMultiplier(currentFpsCap())==0', preflight)
        self.assertNotIn('"1".equals(value)', resolve)
        self.assertNotIn('"0".equals(value)', resolve)
        # The script takes the launcher's resolved multiplier for "auto".
        self.assertIn('lsfg=${GOW3_LSFG_MULTIPLIER:-$(setting lsfg_multiplier)}', _read(SCRIPT))

    def test_launcher_requires_both_layer_files(self):
        activity = _read(GAME_ACTIVITY)
        preflight = activity[activity.index('private boolean lsfgLaunchable()'):
                             activity.index('private void pickRefreshRate()')]
        self.assertIn(LAYER_MANIFEST, preflight)
        self.assertIn(LAYER_LIBRARY, preflight)

    def test_config_file_mode_rewrite_is_atomic_and_stale_state_is_cleared(self):
        script = _read(SCRIPT)
        # The layer watches the config, so a partial write would be read as a broken profile.
        self.assertIn('cat > "$LSFG_CONFIG.tmp"', script)
        self.assertIn('mv -f "$LSFG_CONFIG.tmp" "$LSFG_CONFIG"', script)
        # Falling back must not leave a TOML or exported state behind for the next launch.
        self.assertIn('rm -f "$base/lsfg-vk.toml"', script)
        self.assertIn('unset GOW3_LSFG LSFG_CONFIG', script)


class LsfgFailureContractTests(unittest.TestCase):
    """A frame-generation failure is reported once and recovered from once."""

    def test_native_exit_code_matches_the_script(self):
        native = _read(VK_SWAPCHAIN)
        code = re.search(r'constexpr int kLsfgFailureExitCode = (\d+);', native)
        self.assertIsNotNone(code, 'the native side must keep one named exit code')
        script_code = re.search(r'lsfg_failure_code=(\d+)', _read(SCRIPT))
        self.assertIsNotNone(script_code)
        self.assertEqual(int(code.group(1)), int(script_code.group(1)))
        # Both create and present failures must exit through the same code, and must not
        # unwind into an invalid layer state or race the log flush.
        self.assertEqual(native.count('std::_Exit(kLsfgFailureExitCode)'), 2,
                         'create and present must both exit via the dedicated code')
        self.assertEqual(native.count('std::fflush(nullptr)'), 2)

    def test_native_retry_guard_allows_at_most_one_retry(self):
        script = _read(SCRIPT)
        # The guard must be reset once and flipped before the retry, so a crash loop
        # cannot turn into an endless LSFG on/off restart cycle.
        self.assertRegex(script, r'lsfg_retry=0')
        self.assertRegex(script, r'\[ "\$lsfg_retry" = 0 \]')
        self.assertLess(script.index('lsfg_retry=1'), script.index('continue'))

    def test_only_the_dedicated_exit_code_triggers_the_fallback(self):
        script = _read(SCRIPT)
        self.assertRegex(script, r'if \[ "\$rc" = "\$lsfg_failure_code" \]')
        # A normal game crash keeps its own exit code and must not be read as LSFG failure.
        retry_condition = script[script.index('if [ "$rc" = "$lsfg_failure_code" ]'):
                                 script.index('lsfg_retry=1')]
        self.assertIn('${GOW3_LSFG:-}', retry_condition,
                      'the fallback must only run when the layer was actually enabled')

    def test_fallback_writes_a_marker_the_launcher_reads(self):
        script = _read(SCRIPT)
        activity = _read(GAME_ACTIVITY)
        self.assertIn(f': > "$lsfg_fallback_marker"', script)
        self.assertRegex(script, r'lsfg_fallback_marker="\$base/' + FALLBACK_MARKER + r'"')
        # The launcher consumes the marker in its frame poll and re-picks the display mode.
        self.assertIn(FALLBACK_MARKER, activity)
        self.assertIn('new File(BASE,"%s").delete()' % FALLBACK_MARKER, activity,
                      'a new launch must clear the previous marker')
        poll = activity[activity.index('private void pollFrames()'):
                        activity.index('private void pollFrames()') + 700]
        self.assertIn('lsfgFallbackOff', poll)
        self.assertIn('pickRefreshRate()', poll)

    def test_present_path_does_not_wait_for_gpu_completion(self):
        code = _strip_cpp_comments(_read(VK_PRESENTER))
        # Flush() already issues vkQueueSubmit on this thread, so a per-frame
        # WaitSubmitted() would stall presentation for no correctness gain. The prose
        # around it still names WaitSubmitted, so only executable code is checked.
        waits = re.findall(r'\.\s*WaitSubmitted\s*\(', code)
        self.assertEqual(waits, [], 'present must not wait for GPU completion')
        self.assertTrue(re.search(r'\bpresent_scheduler\.Flush\(info\)', code),
                        'the present submission must still be flushed before Present()')
        # The one-shot log must be a thread-safe magic static, not a mutable global.
        self.assertRegex(code, r'static const bool \w+ = \[\] \{')

    def test_live_multiplier_change_re_picks_the_display_mode(self):
        activity = _read(GAME_ACTIVITY)
        dialog = (ROOT / 'android-launcher/app/src/main/java/ro/cobrabm/fexdroid'
                  '/SettingsDialog.java').read_text()
        # Applying while the game runs rewrites the layer config; the launcher must
        # follow with a display-mode refresh instead of diverging from the multiplier.
        self.assertIn('this::pickRefreshRate', activity)
        self.assertIn('onLiveApplied', dialog)
        self.assertRegex(dialog, r'if \(ok && onLiveApplied!=null\) onLiveApplied\.run\(\);')

    def test_drawer_loses_scaling_entry_is_disabled_without_the_player_dll(self):
        """Without Lossless.dll lsfg-vk cannot load, so the entry must not be clickable."""
        activity = _read(GAME_ACTIVITY)
        refresh = activity[activity.index('private void refreshDrawer()'):
                           activity.index('private void openDrawer()')]
        # The gate is re-evaluated every time the drawer opens, so importing the DLL on the
        # Home page re-enables it without a restart of the drawer.
        self.assertIn('hasFrameGenDll()', refresh)
        self.assertRegex(refresh, r'setRowEnabled\(losslessItem,\s*dllPresent\)')
        # Disabling has to cover click and focus, otherwise a gamepad/keyboard can still
        # land on the row and fire the action.
        helper = activity[activity.index('private static void setRowEnabled('):
                          activity.index('private void refreshDrawer()')]
        self.assertIn('setClickable', helper)
        self.assertIn('setFocusable', helper)
        # The row's action lives in its tag so the listener survives being disabled and
        # re-enabled; nothing here may provision the DLL itself.
        self.assertIn('row.setTag(action)', activity)
        self.assertNotIn('losslessDll', activity,
                         'DLL resolution/provisioning stays in LosslessDllManager')


if __name__ == '__main__':
    unittest.main()