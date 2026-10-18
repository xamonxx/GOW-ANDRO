import json
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'tools/android'


class AndroidBuildInputTests(unittest.TestCase):
    def test_bridge_provenance_matches_allow_list(self):
        provenance = json.loads((ANDROID / 'bridges/provenance.json').read_text())
        self.assertEqual(provenance['abi'], 'arm64-v8a')
        self.assertEqual(provenance['source_revision'],
                         '2b03ec73c3507b640fba1942fb8d99b43b88945a')
        self.assertEqual({entry['archive_path'] for entry in provenance['files']}, {
            'lib/arm64-v8a/libfxdisplay.so', 'lib/arm64-v8a/libfxio.so'})
        for entry in provenance['files']:
            path = ROOT / entry['output_path']
            self.assertTrue(path.is_file(), path)
            self.assertEqual(path.stat().st_size, entry['size'])

    def test_debug_manifest_reuses_local_graphics_artifacts(self):
        manifest = json.loads((ANDROID / 'debug/runtime-manifest.json').read_text())
        self.assertEqual(manifest['profile'], 'debug')
        self.assertIn('rootfs/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json',
                      manifest['turnip_variants'])
        self.assertIn('rootfs/usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json',
                      manifest['turnip_variants'])
        self.assertTrue(any('lsfg' in entry['path'] for entry in manifest['required_paths']))

    def test_scripted_patch_inputs_exist_and_are_game_free(self):
        paths = [
            ANDROID / 'mesa-patches/README.md',
            ANDROID / 'mesa-patches/gen8',
            ANDROID / 'lsfg-patches',
        ]
        for path in paths:
            self.assertTrue(path.exists(), path)
        patch_files = list((ANDROID / 'mesa-patches').rglob('*.patch'))
        patch_files += list((ANDROID / 'lsfg-patches').glob('*.patch'))
        self.assertGreater(len(patch_files), 0)
        forbidden = ('eboot.bin', 'sce_sys', 'sce_module', 'Lossless.dll')
        for path in patch_files:
            text = path.read_text(errors='replace')
            self.assertFalse(any(word in text for word in forbidden), path)

    def test_runtime_manifest_keeps_generated_driver_contract(self):
        manifest = json.loads((ANDROID / 'runtime/runtime-manifest.json').read_text())
        self.assertIn('rootfs/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json',
                      manifest['turnip_variants'])
        self.assertIn('rootfs/usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json',
                      manifest['turnip_variants'])
        self.assertEqual(manifest['port_arch_root'], 'gow3port/arm64')

    def test_release_assembler_reports_all_missing_generated_inputs(self):
        result = subprocess.run(
            ['python3', 'tools/android/build_release_runtime.py'],
            cwd=ROOT, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        output = result.stdout + result.stderr
        self.assertIn('Missing Android runtime inputs:', output)
        self.assertIn('Gen8 Turnip:', output)
        self.assertIn('LSFG:', output)

    def test_player_lossless_dll_stays_out_of_every_build_product(self):
        """The launcher never ships Lossless.dll; players import their own (Home page)."""
        assembler = (ANDROID / 'runtime/build_release_runtime.py').read_text()
        self.assertIn('Lossless.dll', assembler)
        banned = assembler.split('banned', 1)[1]
        self.assertIn('Lossless.dll', banned, 'Lossless.dll must stay in the payload ban list')

        this_file = Path(__file__).read_text()
        self.assertIn("'Lossless.dll'", this_file, 'Lossless.dll must stay in the patch-input ban list')

    def test_game_activity_no_longer_provisions_lossless_dll(self):
        """DLL provisioning moved to the launcher Home page; LSFG tuning stayed in the drawer."""
        game_activity = (
            ROOT / 'android-launcher/app/src/main/java/ro/cobrabm/fexdroid/GameActivity.java'
        ).read_text()
        self.assertNotIn('steamItem', game_activity)
        self.assertNotIn('Get Lossless.dll from Steam', game_activity)
        self.assertNotIn('SteamDllDialog', game_activity)
        self.assertNotIn('losslessDll', game_activity, 'resolution lives only in LosslessDllManager')
        # The LSFG tuning dialog must keep a caller, or frame generation becomes untunable in game.
        self.assertIn('showLossless', game_activity)

    def test_launcher_provides_frame_generation_from_home(self):
        launcher = ROOT / 'android-launcher/app/src/main/java/com/gow3/android'
        screen = (launcher / 'ui/LauncherScreen.kt').read_text()
        # Frame generation lives in the Performance tab's Frame Gen sub-tab now.
        performance = (launcher / 'feature/performance/PerformanceScreen.kt').read_text(encoding='utf-8')
        section = (launcher / 'feature/home/FrameGenerationSection.kt').read_text(encoding='utf-8')
        card = (launcher / 'feature/home/FrameGenerationCard.kt').read_text(encoding='utf-8')
        manager = (launcher / 'data/LosslessDllManager.kt').read_text(encoding='utf-8')
        self.assertIn('PerformanceScreen', screen)
        self.assertIn('FrameGenerationSection(', performance)
        self.assertIn('FrameGenerationCard(', section)
        # The card renders the two provisioning actions through string resources, so assert the
        # resource keys in the Kotlin (not the literal text, which lives in res/values) — otherwise
        # a resource-based UI fails the test even though it is correct (4.5 复评).
        self.assertIn('R.string.frame_gen_import_dll', card)
        self.assertIn('R.string.frame_gen_get_from_steam', card)
        resources = (ROOT / 'android-launcher/app/src/main/res/values/strings.xml').read_text()
        self.assertIn('IMPORT .DLL', resources)
        self.assertIn('GET FROM STEAM', resources)
        # The runtime's fallback location, quoted verbatim: drifting it breaks run-gow3.sh.
        self.assertIn('"gow3port/$FILE_NAME"', manager)
        self.assertIn('Lossless.dll', manager)
        # Cross-package access from com.gow3.android needs the class to be public in both source sets.
        for steam in (
            ROOT / 'android-launcher/app/src/steamStub/java/ro/cobrabm/fexdroid/SteamDllDialog.java',
            ROOT / 'android-launcher/app/src/steam/java/ro/cobrabm/fexdroid/SteamDllDialog.java',
        ):
            self.assertTrue(steam.is_file(), steam)
            self.assertIn('public final class SteamDllDialog', steam.read_text())

    def test_steam_download_gated_on_fetched_libraries(self):
        """The real JavaSteam implementation is compiled only when steam-libs/jars.txt exists."""
        gradle = (ROOT / 'android-launcher/app/build.gradle.kts').read_text()
        self.assertIn('steam-libs', gradle)
        self.assertIn('jars.txt', gradle)
        self.assertIn('src/steam/java', gradle)
        self.assertIn('src/steamStub/java', gradle)
        # The fetcher that produces steam-libs/jars.txt must stay reachable and pinned to Lossless Scaling.
        fetcher = (ANDROID / 'inputs/fetch_steam_libs.sh').read_text()
        self.assertIn('javasteam', fetcher)
        self.assertIn('993090', fetcher)
        # The Home page callback lets a finished download refresh the card without re-entering.
        main_activity = (
            ROOT / 'android-launcher/app/src/main/java/com/gow3/android/MainActivity.kt'
        ).read_text()
        self.assertIn('onGetFromSteam', main_activity)
