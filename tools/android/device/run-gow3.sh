#!/bin/sh
# Launch script for the God of War III port on Android (gow3_android).
#
# Runs inside the app sandbox on top of the fexdroid ARM64 rootfs. The app starts it as:
#   <rootfs>/bin/sh <files>/gow3port/run-gow3.sh game
#
# It is the Android twin of the desktop run.sh: same prepare -> patches -> probe flow, but the
# paths are the app's private directories, Python comes from the rootfs, and the display / input /
# audio / shared-memory bridges are the app's (see the display and input bridge classes).
set -eu
files=${FXD_FILES:-/data/user/0/com.gow3.android/files}
host="$files/rootfs"
base="$files/gow3port"
arch="$base/arm64"
loader="$host/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
probe="$arch/bin/gow3-probe"
export FXD_FILES="$files" FXD_ROOT="$host"
export PATH="$host/usr/bin:$host/bin:/system/bin"
export HOME="$files/home/gow3port"
export TMPDIR="$host/tmp" XDG_RUNTIME_DIR="$host/tmp"
# lib/ next to the probe holds libgow3gpu/libgow3cpu and the libraries the rootfs lacks.
export LD_LIBRARY_PATH="$arch/lib:$host/usr/lib/aarch64-linux-gnu:$host/usr/lib/aarch64-linux-gnu/pulseaudio:$host/usr/lib"

# The Adreno chip id KGSL reports (0x44... on Adreno 8xx) picks the Turnip build below.
chip_id=$("$loader" "$arch/bin/kgsl-chip-id" 2>/dev/null || true)
# Bionic's getprop must not see the rootfs (glibc) libraries.
prop() { LD_LIBRARY_PATH= /system/bin/getprop "$1" 2>/dev/null || true; }
# A device profile (gow3.<profile>.ini) seeds gow3.ini on the first launch; GOW3_DEVICE_PROFILE
# overrides the detection. iQOO 15R: Snapdragon 8 Gen 5 (SM8845), Adreno 829.
device_profile=${GOW3_DEVICE_PROFILE:-}
if [ -z "$device_profile" ]; then
    case $(printf '%s %s %s' "$(prop ro.vivo.market.name)" "$(prop ro.product.marketname)" \
            "$(prop ro.product.model)" | tr 'A-Z' 'a-z') in
        *iqoo*15r*|*iqoo*15\ r*) device_profile=iqoo15r ;;
    esac
fi
echo "Device: $(prop ro.product.manufacturer) $(prop ro.product.model) ($(prop ro.soc.model)), chip ${chip_id:-unknown}, profile ${device_profile:-default}"

# Turnip: Mesa main for Adreno 6xx/7xx, the turnip/gen8 build on Adreno 8xx (0x44..., e.g. the
# 8 Elite's 830, the 8 Gen 5's 829). GOW3_TURNIP=main|gen8 overrides the choice.
turnip=${GOW3_TURNIP:-}
if [ -z "$turnip" ]; then
    case $chip_id in
        0x44*) turnip=gen8 ;;
        *) turnip=main ;;
    esac
fi
icd="$host/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
[ "$turnip" = gen8 ] && [ -f "$host/usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json" ] &&
    icd="$host/usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json"
echo "Turnip: ${icd##*/}"
export VK_DRIVER_FILES="$icd"
export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
# The guest's x86-64 code runs under FEXCore; its glibc needs the unsupported-instruction tunables
# the desktop uses too.
export GLIBC_TUNABLES='glibc.cpu.hwcaps=-AVX2,-AVX,-AVX_Fast_Unaligned_Load,-AVX2_Usable,-AVX_Usable'

export GOW3_CONFIG="$base/gow3.ini"
# First launch only: the device profile when there is one, else the defaults. An existing
# gow3.ini holds the player's settings and is never replaced.
if [ ! -f "$GOW3_CONFIG" ]; then
    seed="$base/gow3.default.ini"
    [ -n "$device_profile" ] && [ -f "$base/gow3.$device_profile.ini" ] && seed="$base/gow3.$device_profile.ini"
    cp "$seed" "$GOW3_CONFIG"
    echo "Config: first launch, gow3.ini from ${seed##*/}"
fi
# One setting from gow3.ini, last value wins (the launcher writes whole-file on changes).
setting() { sed -n "s/^$1=//p" "$GOW3_CONFIG" 2>/dev/null | tail -n 1; }

# vk_swapchain.cpp 在 lsfg-vk 创建/present 失败时以这个码退出（kLsfgFailureExitCode）。两边必须一致。
lsfg_failure_code=46
# LSFG 自动回退时写这个标记，launcher（GameActivity.pollFrames）轮询到后会重新选 display mode，
# 避免回退到普通 Vulkan 后仍停留在 LSFG 倍率对应的高刷新率（4.1 复评）。
lsfg_fallback_marker="$base/lsfg-fallback.marker"

# Lossless Scaling frame generation (lsfg_multiplier 2-4): the lsfg-vk 1.0 layer with our Turnip
# patches (gow3port/arm64/lsfg, build_lsfg.sh) and Lossless.dll from the player's own Lossless
# Scaling (Steam), next to the game folder or in gow3port/. Its messages (lsfg-vk: ...) go to the
# game log. FSR 3.1 frame generation is not wired on this tree (shadps4 has no GOW3_FRAME_GEN
# consumer); LSFG is the only frame-generation path.
lsfg=$(setting lsfg_multiplier)
case $lsfg in 2|3|4) ;; *) lsfg= ;; esac
lsfg_layer="$base/arm64/lsfg"
# 层可用性 preflight：manifest 和 liblsfg-vk.so 缺一不可（RuntimeInstaller 正常会验证这两个文件，
# 但直接跑脚本 / 旧 runtime / 残缺 runtime 时仍可能出现「配置认为可用、layer 其实加载不了」，
# 见 4.2 复评）。launcher 侧 pickRefreshRate 必须用同一套规则判断是否真的可用。
if [ -n "$lsfg" ] && [ -f "$lsfg_layer/VkLayer_LS_frame_generation.json" ] && \
   [ -f "$lsfg_layer/liblsfg-vk.so" ]; then
    lsfg_dll=
    for dll in "${GOW3_GAME_DIR:+${GOW3_GAME_DIR%/*}/Lossless.dll}" "$base/Lossless.dll"; do
        [ -n "$dll" ] && [ -f "$dll" ] && { lsfg_dll=$dll; break; }
    done
    if [ -n "$lsfg_dll" ]; then
        flow=$(setting lsfg_flow_scale)
        case $flow in 0.25|0.5|0.75|1|1.0) ;; *) flow=1.0 ;; esac
        performance=$([ "$(setting lsfg_performance)" = 1 ] && echo true || echo false)
        # Config file mode: lsfg-vk watches it, so the app changes the multiplier (1: off), flow
        # scale and performance mode while the game runs (SettingsDialog.showLossless). The file
        # exists only while the layer is loaded; GOW3_LSFG: vk_swapchain.cpp / vk_presenter.cpp
        # check it and (re)coordinate with the layer's present hook. An implicit layer, so the
        # layer's own framegen instance skips it (DISABLE_LSFG). Present mode: FIFO paces the
        # generated frames (LSFG_PRESENT=mailbox|immediate to try).
        export GOW3_LSFG=1 LSFG_CONFIG="$base/lsfg-vk.toml"
        export VK_ADD_IMPLICIT_LAYER_PATH="$lsfg_layer"
        cat > "$LSFG_CONFIG.tmp" <<EOF
version = 1

[global]
dll = "$lsfg_dll"

[[game]]
exe = "gow3-probe"
multiplier = $lsfg
flow_scale = $flow
performance_mode = $performance
experimental_present_mode = "${LSFG_PRESENT:-fifo}"
EOF
        mv -f "$LSFG_CONFIG.tmp" "$LSFG_CONFIG"
        echo "LSFG: enabled (x$lsfg, flow $flow, $lsfg_dll); toml=$LSFG_CONFIG; layer=$lsfg_layer"
    else
        echo "LSFG: requested (lsfg_multiplier=$lsfg) but Lossless.dll was not found at either"
        echo "      ${GOW3_GAME_DIR:+${GOW3_GAME_DIR%/*}/Lossless.dll}${GOW3_GAME_DIR:+\\n      }$base/Lossless.dll"
        echo "      Drop the player's Lossless.dll into the launcher Home's FRAME GENERATION card,"
        echo "      or next to the game folder, then restart the game."
        lsfg=
    fi
elif [ -n "$lsfg" ]; then
    echo "LSFG: requested (lsfg_multiplier=$lsfg) but the runtime is missing the lsfg-vk layer:"
    [ -f "$lsfg_layer/VkLayer_LS_frame_generation.json" ] || echo "      $lsfg_layer/VkLayer_LS_frame_generation.json"
    [ -f "$lsfg_layer/liblsfg-vk.so" ] || echo "      $lsfg_layer/liblsfg-vk.so"
    echo "      This APK/runtime build does not include the lsfg-vk layer; reinstall the latest runtime."
    lsfg=
elif [ -z "$lsfg" ] && grep -q '^lsfg_multiplier=' "$GOW3_CONFIG" 2>/dev/null; then
    echo "LSFG: disabled (lsfg_multiplier=0 in gow3.ini)"
else
    echo "LSFG: not requested (no lsfg_multiplier in gow3.ini)"
fi
[ -n "$lsfg" ] || rm -f "$base/lsfg-vk.toml"

# Bridges the app provides (it sets GOW3_ANDROID_INPUT=1 when they are up).
if [ "${GOW3_ANDROID_INPUT:-0}" = 1 ]; then
    export GOW3_PAD_FILE="$base/android-pad.state" GOW3_PAD_QUIET=1
    # Text the game asks for (the character name): the app's touch keyboard.
    export GOW3_IME_FILE="$base/ime.request"
    # Sound: SDL's disk driver mixes into the FIFO the app plays. Only when the app made it: a
    # FIFO nobody reads would stall the game's audio. Its reads pace the output.
    if [ -p "$base/audio.fifo" ] && [ -f "$base/audio.fifo.ready" ]; then
        export SDL_AUDIO_DRIVER=disk SDL_AUDIO_DISK_OUTPUT_FILE="$base/audio.fifo"
        export SDL_AUDIO_DISK_TIMESCALE=0 SDL_AUDIO_DEVICE_SAMPLE_FRAMES=512
    else
        echo "Android audio bridge is not ready (expected $base/audio.fifo.ready)" >&2
        exit 12
    fi
fi

# The launcher's Xvfb is not always on :0 (another app's X server can hold it).
export SDL_VIDEODRIVER=x11 DISPLAY=${GOW3_DISPLAY:-:0}
export MESA_VK_WSI_DEBUG=sw MESA_VK_WSI_PRESENT_MODE=relaxed
# The display bridge hands frames over directly through this socket (the ordinary X11 path can
# show black on Android).
export FEXDROID_PRESENT="$host/tmp/fxpresent.sock"
export SDL_JOYSTICK_HIDAPI=0
export SDL_GAMECONTROLLERCONFIG_FILE="$base/gow3-gamecontrollerdb.txt"
export PULSE_SERVER="unix:$host/tmp/pulse/native" PULSE_LATENCY_MSEC=60

python="$host/usr/bin/python3.13"
[ -x "$loader" ] || { echo "No rootfs loader in the runtime" >&2; exit 2; }
[ -x "$python" ] || { echo "No Python in the runtime; cannot prepare the game files" >&2; exit 2; }
out="$base/out"
mkdir -p "$out" "$base/user"
export PYTHONPATH="$base/scripts"

case ${1:-game} in
    vulkan)
        # GPU check without the game (needs the app's display bridge / a Vulkan device).
        exec "$loader" "$probe" --vulkan-only
        ;;
    cpu|check)
        # Offline checks on an already prepared image: no GPU involved.
        [ -f "$out/boot-linked.bin" ] || { echo "Prepare the game first (run 'game')." >&2; exit 2; }
        game=${GOW3_GAME_DIR:-$base/game}
        if [ "$1" = cpu ]; then exec "$loader" "$probe" "$out/boot-linked.bin" --cpu-only --app0 "$game"; fi
        exec "$loader" "$probe" "$out/boot-linked.bin" --app0 "$game" --check-imports
        ;;
esac

# GOW3_GAME_DIR: the game's folder (eboot.bin, sce_module, sce_sys). Default: gow3port/game.
game=${GOW3_GAME_DIR:-$base/game}
[ -f "$game/eboot.bin" ] || { echo "Game missing: $game (no eboot.bin)" >&2; exit 2; }

# GOW3_PATCH_DIR: an update folder (eboot.bin, sce_sys) laid over the game at start. The merged
# read-only view (scripts/game_update.py) is what prepare/patches/probe use; both dumps stay intact.
update_view=
if [ -n "${GOW3_PATCH_DIR:-}" ]; then
    [ -f "$GOW3_PATCH_DIR/eboot.bin" ] || { echo "Update missing: $GOW3_PATCH_DIR (no eboot.bin)" >&2; exit 2; }
    if ! update_view=$(PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/game_update.py" view "$game" "$GOW3_PATCH_DIR" "$out"); then
        echo "Game update: cannot combine the game and its update (see above)" >&2
        exit 2
    fi
    game=$update_view
    echo "Game and update combined from $GOW3_PATCH_DIR" >&2
    trap 'if [ -n "${update_view:-}" ]; then PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/game_update.py" remove "$update_view" 2>/dev/null || true; fi' EXIT
fi

# A reproducible capture: the tester names it (GOW3_CAPTURE_ID) and the display-boundary marker
# is written outside the repository/APK so frame captures can be matched to a run without ever
# bundling game content. The launcher reads this marker to expose the capture directory.
if [ -n "${GOW3_CAPTURE_ID:-}" ]; then
    capture_dir=${GOW3_CAPTURE_DIR:-$files/gow3port/captures/$GOW3_CAPTURE_ID}
    export GOW3_CAPTURE_DIR="$capture_dir"
    mkdir -p "$capture_dir"
    echo "Capture marker: $capture_dir (capture_id=$GOW3_CAPTURE_ID)"
fi

# Vulkan/Turnip must initialize before the game claims texture integrity: a driver failure here
# must reach the launcher/log and must not be reported as validated. The self-check is the same
# probe --vulkan-only the launcher's "vulkan" mode runs. We capture stderr so that lsfg-vk's
# own init messages ("lsfg-vk: ...") are visible in the game log when the check fails.
#
# The smoke test (src/vulkan_smoke.c) calls vkCreateDevice with pNext=NULL, which the lsfg-vk
# device layer rejects ("No layer creation info found in pNext chain" -> VK_ERROR_INITIALIZATION_FAILED).
# That throw is not a driver failure; the real probe --vulkan-only check is meaningful only
# against the bare driver, not the LSFG layer. Set DISABLE_LSFG=1 in the child only (the layer's
# disable_environment) so the smoke can validate Turnip without lsfg-vk on its pNext.
vulkan_status=unknown
vulkan_log="$out/.vulkan-check.log"
if DISABLE_LSFG=1 "$loader" "$probe" --vulkan-only >"$vulkan_log" 2>&1; then
    vulkan_status=ok
    rm -f "$vulkan_log"
else
    vulkan_status=failed
    echo "Graphics: Vulkan/Turnip initialization FAILED (turnip=${turnip}, icd=${icd##*/}); texture integrity is NOT validated" >&2
    # Surface the last 40 lines of the Vulkan check (typically lsfg-vk's "lsfg-vk: ..." messages
    # or the driver stack) so the player can see what the layer did. We never write the game's
    # own texture assets here; only Vulkan / lsfg-vk / driver output.
    if [ -f "$vulkan_log" ]; then
        echo "Vulkan check output (last 40 lines):" >&2
        tail -n 40 "$vulkan_log" >&2 || true
        mv -f "$vulkan_log" "$out/vulkan-check-last.log"
    fi
fi
export GOW3_VULKAN_STATUS="$vulkan_status"
# A driver failure is fatal: the game cannot present, and the log must say so distinctly. Write a
# diagnostic record first so the failure is part of the evidence, not just an exit code.
if [ "$vulkan_status" != ok ]; then
    mkdir -p "$out"
    printf '%s\n' "{\"title_id\":\"${GOW3_GAME_TITLE:-unknown}\",\"app_version\":\"${GOW3_GAME_VERSION:-unknown}\",\"eboot_sha256\":\"${GOW3_EBOOT_SHA256:-unavailable}\",\"supported\":false,\"reason\":\"${GOW3_PATCH_STATUS:-unknown}\",\"vulkan_status\":\"failed\"}" > "$out/texture_integrity.json"
    echo "Graphics: aborting launch; see the runtime log for the driver error (log: $base/logs/android-game.log)" >&2
    exit 4
fi

# Prepare the runtime image from the player's own files (cached: prepare.py checks the sources).
PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/prepare.py" "$game" --out "$out"
PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/link_libc.py" "$game" --out "$out"
PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/link_modules.py" "$game" --out "$out"
PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/content_profile.py" "$game" --out "$out" --sku "${GOW3_CONTENT_SKU:-full}"
# Graphics options, patch selection and the direct memory (DMEM)/VBlank the selected patches need.
# GOW3_DEVICE_MEM_MB lets launch_env.py gate the patches by the device's RAM (insufficient-memory-budget).
mem_kb=$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null || true)
[ -n "$mem_kb" ] && [ "$mem_kb" -gt 0 ] && export GOW3_DEVICE_MEM_MB=${GOW3_DEVICE_MEM_MB:-$((mem_kb/1024))}
eval "$(PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/launch_env.py" --game "$game" --config "$GOW3_CONFIG" --state "$out/graphics_state.json" --shell)"
# The retail pool is 5056 MiB; the resolution/texture patches ask for more (7104+ for the default
# set). On a phone RAM is the binding constraint: lower the patch set / resolution if the app is
# killed. See docs/ANDROID_PORT.md.
export GOW3_DMEM_MB=${GOW3_DMEM_MB:-0}; [ "${GOW3_DMEM_MB:-0}" -ge 5056 ] 2>/dev/null || export GOW3_DMEM_MB=7104
export GOW3_VBLANK_HZ=${GOW3_VBLANK_HZ:-0}; [ "${GOW3_VBLANK_HZ:-0}" -ge 1 ] 2>/dev/null || export GOW3_VBLANK_HZ=60
echo "Texture integrity: title=${GOW3_GAME_TITLE:-unknown} version=${GOW3_GAME_VERSION:-unknown} patch=${GOW3_PATCH_STATUS:-unknown} upscaler=${GOW3_UPSCALER:-none}(${GOW3_UPSCALER_SOURCE:-unknown}) readback_linear=${GOW3_READBACK_LINEAR:-1}(${GOW3_READBACK_LINEAR_SOURCE:-unknown}) deferred=${GOW3_DEFERRED_READBACK:-unknown} stale=${GOW3_STALE_READBACK:-unknown} dmem=${GOW3_DMEM_MB}MiB vblank=${GOW3_VBLANK_HZ}Hz turnip=${turnip}"
profile_file="$out/texture-profile.json"
profile_supported=false
[ "${GOW3_PATCH_STATUS:-}" = validated ] && profile_supported=true
printf '%s\n' "{\"title_id\":\"${GOW3_GAME_TITLE:-unknown}\",\"app_version\":\"${GOW3_GAME_VERSION:-unknown}\",\"eboot_sha256\":\"${GOW3_EBOOT_SHA256:-unavailable}\",\"supported\":${profile_supported},\"reason\":\"${GOW3_PATCH_STATUS:-unknown}\"}" > "$profile_file"
PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/texture_diagnostics.py" --out "$out/texture_integrity.json" --profile "$profile_file" --turnip "${turnip}" --dmem "${GOW3_DMEM_MB}" --vblank "${GOW3_VBLANK_HZ}" --vulkan-status "${vulkan_status}"
PYTHONHOME="$host/usr" "$loader" "$python" "$base/scripts/patches.py" --out "$out" --extra "${GOW3_PATCHES:-}" \
    --game-dir "$game" --patches-dir "${GOW3_PATCHES_DIR:-$base/patches}" \
    --patches-config "${GOW3_PATCHES_CONFIG:-$base/patches.json}"

cd "$base"
echo "God of War III: starting (DMEM ${GOW3_DMEM_MB} MiB, vblank ${GOW3_VBLANK_HZ} Hz)"
# LSFG 失败的一次性回退（OpenSpec harden-gow3-lsfg-integration 任务 2.3）：vk_swapchain 在 lsfg-vk
# 创建/present 失败时以 lsfg_failure_code 退出。当时若 LSFG 是启用的，就清掉 LSFG 状态、设
# DISABLE_LSFG=1 后重试一次普通 Vulkan。guard（lsfg_retry）保证最多重试一次：绝不会出现重启循环，
# 普通的游戏崩溃也不会被当成 LSFG 故障（专用退出码才触发）。
lsfg_retry=0
while :; do
    rc=0
    "$loader" "$probe" "$out/boot-linked.bin" \
        --content-profile "$out/content.bin" --patches "$out/patches.bin" \
        --app0 "$game" --user "$base/user" --timeout "${GOW3_TIMEOUT:-0}" || rc=$?
    if [ "$rc" = "$lsfg_failure_code" ] && [ -n "${GOW3_LSFG:-}" ] && [ "$lsfg_retry" = 0 ]; then
        lsfg_retry=1
        echo "Lossless Scaling: frame generation failed (exit $lsfg_failure_code); retrying once without it" >&2
        unset GOW3_LSFG LSFG_CONFIG
        export DISABLE_LSFG=1
        rm -f "$base/lsfg-vk.toml"
        # 标记 LSFG 已自动关闭：launcher 轮询到后会按 LSFG off 重选 display mode（4.1 复评）。
        : > "$lsfg_fallback_marker"
        continue
    fi
    break
done
# When an update view was created, its removal runs in the EXIT trap (the dumps stay untouched).
exit $rc
