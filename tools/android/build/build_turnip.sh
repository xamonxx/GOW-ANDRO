#!/usr/bin/env bash
# Turnip (Mesa's Adreno Vulkan driver) for the Thor's Debian trixie arm64 rootfs, KGSL only,
# cross-compiled with Clang, in one of two variants (README in tools/android/mesa-patches):
#   main: Mesa at the pinned commit + mesa-patches/*.patch -> out/arm64/turnip
#         (libvulkan_freedreno.so, freedreno_icd.aarch64.json, drirc.d/)
#   gen8: the turnip/gen8 branch (Adreno 8xx) + mesa-patches/gen8/*.patch (upstream KGSL fixes it
#         predates) + mesa-patches/*.patch -> out/arm64/turnip-gen8 (libvulkan_freedreno_gen8.so,
#         freedreno_gen8_icd.aarch64.json); run-gow3.sh picks it on Adreno 8xx.
# build_release_runtime.py ships both.
set -euo pipefail
cd -- "$(dirname -- "$0")/../../.."
die() { echo "build_turnip: $*" >&2; exit 1; }
# Per-stage progress (same style as build_android_runtime.sh). ninja output stays in build.log
# to avoid the 600+ line compile scroll; only the stage name and elapsed time surface.
step=0; total=6; _t0=0
begin()    { step=$((step + 1)); _t0=$(date +%s); printf '\n==> [%d/%d] %s\n' "$step" "$total" "$*"; }
end_stage(){ local sec=$(( $(date +%s) - _t0 )); printf '    %s (%dm%02ds)\n' "$*" "$((sec / 60))" "$((sec % 60))"; }
if [[ -n ${GOW3_LLVM_PREFIX:-} && -d $GOW3_LLVM_PREFIX/bin ]]; then
    export PATH="$GOW3_LLVM_PREFIX/bin:$PATH"
fi
if [[ -n ${GOW3_LLD_PREFIX:-} && -d $GOW3_LLD_PREFIX/bin ]]; then
    export PATH="$GOW3_LLD_PREFIX/bin:$PATH"
fi
# Mesa's ir3_parser needs Bison >= 3.8 (-Wcounterexamples) and a recent Flex. macOS ships
# bison 2.3, so prefer Homebrew's keg-only bison/flex when installed.
for tool in bison flex; do
    for prefix in /opt/homebrew/opt/$tool /usr/local/opt/$tool; do
        [[ -d $prefix/bin ]] && { export PATH="$prefix/bin:$PATH"; break; }
    done
done
command -v bison >/dev/null || die 'bison is required (brew install bison)'
command -v flex >/dev/null || die 'flex is required (brew install flex)'
if ! bison -Wcounterexamples -V >/dev/null 2>&1; then
    die "bison is too old ($(bison --version 2>&1 | head -1)); need >= 3.8 (brew install bison)"
fi
command -v git >/dev/null || die 'git is required'
command -v clang >/dev/null || die 'clang is required'
command -v clang++ >/dev/null || die 'clang++ is required'
for tool in llvm-ar llvm-strip; do
    command -v "$tool" >/dev/null || die "$tool is required"
done
if command -v readelf >/dev/null; then
    readelf_cmd=readelf
elif command -v llvm-readelf >/dev/null; then
    readelf_cmd=llvm-readelf
else
    die 'readelf or llvm-readelf is required'
fi
variant=${1:-main}
case $variant in
    main)
        repo=https://gitlab.freedesktop.org/mesa/mesa.git
        MESA_COMMIT=${MESA_COMMIT:-35b085c4c067f198ce0b6437d94678e47b9c645f}
        suffix= ;;
    gen8)
        repo=https://github.com/whitebelyash/mesa-unified.git
        MESA_COMMIT=${MESA_COMMIT:-9c7e022677dfa3abb356c2b6732cbd2e25783d01}
        suffix=_gen8 ;;
    *) echo 'usage: build_turnip.sh [main|gen8]' >&2; exit 2 ;;
esac
shopt -s nullglob
common_patches=(tools/android/mesa-patches/*.patch)
gen8_patches=(tools/android/mesa-patches/gen8/*.patch)
[[ ${#common_patches[@]} -gt 0 ]] || die "$variant: no common Mesa patches under tools/android/mesa-patches"
if [[ $variant == gen8 && ${#gen8_patches[@]} -eq 0 ]]; then
    die 'gen8: no Gen8 Mesa patches under tools/android/mesa-patches/gen8'
fi
if [[ $variant == gen8 ]]; then
    patches=("${gen8_patches[@]}" "${common_patches[@]}")
else
    patches=("${common_patches[@]}")
fi
rootfs=/data/user/0/com.gow3.android/files/rootfs
deps=$PWD/.local-deps/android
sysroot=$deps/mesa-sysroot
src=$deps/src/mesa${suffix/_/-}
build=$PWD/out/arm64/turnip${suffix/_/-}-build
out=$PWD/out/arm64/turnip${suffix/_/-}

# Only libraries the rootfs has: no xcb-keysyms (it would add a NEEDED the Thor lacks).
if [[ ! -f $sysroot/packages.json ]]; then
    python3 tools/android/inputs/fetch_arm64_sysroot.py --out "$sysroot" --packages \
        libc6-dev libstdc++-14-dev libgcc-14-dev linux-libc-dev zlib1g-dev libzstd-dev \
        libexpat1-dev libxcb1-dev libx11-xcb-dev libxcb-xfixes0-dev libxcb-randr0-dev \
        libxcb-dri3-dev libxcb-present-dev libxcb-sync-dev libxcb-shm0-dev libxshmfence-dev \
        libx11-dev libxrandr-dev libdrm-dev
fi
rm -f "$sysroot/usr/lib/aarch64-linux-gnu/pkgconfig/xcb-keysyms.pc"

# Mesa's build tools (meson, mako) in a local virtual environment, not the host's Python.
venv=$deps/mesa-venv
if [[ ! -x $venv/bin/meson ]]; then
    command -v python3 >/dev/null || die 'python3 is required to create the Mesa build environment'
    python3 -m venv "$venv"
    "$venv/bin/python" -m pip install -q meson mako pyyaml packaging
fi
export PATH="$venv/bin:$PATH"
command -v meson >/dev/null || die "$variant: meson is unavailable"
command -v ninja >/dev/null || die "$variant: ninja is unavailable"

# A pristine tree at the pinned commit, then the patches.
if [[ ! -d $src/.git ]]; then
    git init -q "$src"
    git -C "$src" remote add origin "$repo"
fi
begin "Mesa commit $MESA_COMMIT ($repo)"
if git -C "$src" cat-file -e "$MESA_COMMIT^{commit}" 2>/dev/null; then
    echo "Using cached Mesa commit $MESA_COMMIT for $variant"
    end_stage "cached: skipped fetch"
else
    git -C "$src" fetch -q --depth 1 origin "$MESA_COMMIT" ||
        die "$variant: cannot fetch pinned Mesa commit $MESA_COMMIT from $repo"
    end_stage "fetched: $MESA_COMMIT"
fi
begin "apply ${#patches[@]} patch"
git -C "$src" checkout -q --force "$MESA_COMMIT"
git -C "$src" clean -q -fdx
for patch in "${patches[@]}"; do
    [[ -f $patch ]] || die "$variant: missing patch $patch"
    git -C "$src" apply --check "$PWD/$patch" ||
        die "$variant: patch does not apply: $patch"
    git -C "$src" apply "$PWD/$patch"
done
end_stage "patches applied"

# cpu 'aarch64': the manifest is then freedreno_icd.aarch64.json with an absolute library_path.
cross=$build.cross
mkdir -p "$(dirname "$cross")"
# Optional ccache: when installed, it caches the Turnip cross-compile across runs and finally
# consumes the ~/.cache/ccache path CI already restores (see .github/workflows/android.yml).
# CCACHE_BASEDIR keeps cache keys stable when the absolute source path differs between CI
# runners and local checkouts. With ccache absent the cross-file shape is unchanged, so the
# build is byte-identical to today.
if command -v ccache >/dev/null 2>&1; then
    export CCACHE_BASEDIR=${CCACHE_BASEDIR:-$PWD}
    c_entry="['ccache', 'clang', '--target=aarch64-linux-gnu', '--sysroot=$sysroot']"
    cpp_entry="['ccache', 'clang++', '--target=aarch64-linux-gnu', '--sysroot=$sysroot']"
else
    c_entry="['clang', '--target=aarch64-linux-gnu', '--sysroot=$sysroot']"
    cpp_entry="['clang++', '--target=aarch64-linux-gnu', '--sysroot=$sysroot']"
fi
# ARM64 ISA tuning: GOW3_ARM64_CPU (an exact -mcpu) or GOW3_ARM64_ARCH added to compile flags.
arch_flag=
if [[ -n ${GOW3_ARM64_CPU:-} ]]; then
    arch_flag=", '-mcpu=${GOW3_ARM64_CPU}'"
elif [[ -n ${GOW3_ARM64_ARCH:-} ]]; then
    arch_flag=", '-march=${GOW3_ARM64_ARCH}'"
fi
cat > "$cross" <<EOF
[binaries]
c = $c_entry
cpp = $cpp_entry
c_ld = 'lld'
cpp_ld = 'lld'
ar = 'llvm-ar'
strip = 'llvm-strip'
pkg-config = 'pkg-config'
[built-in options]
c_args = ['-O2'$arch_flag]
cpp_args = ['-O2'$arch_flag]
[properties]
sys_root = '$sysroot'
pkg_config_libdir = ['$sysroot/usr/lib/aarch64-linux-gnu/pkgconfig', '$sysroot/usr/share/pkgconfig']
[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
# Meson handles incrementality; build dir is preserved across runs.
reconfigure=()
[[ -d "$build" ]] && reconfigure=(--reconfigure)
begin "meson setup"
meson setup "${reconfigure[@]}" "$build" "$src" --cross-file "$cross" --prefix="$rootfs/usr" \
    --libdir=lib/aarch64-linux-gnu --wrap-mode=nofallback --buildtype=release -Db_ndebug=true \
    -Dplatforms=x11 -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dgallium-drivers= \
    -Dopengl=false -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dglx=disabled -Dgbm=disabled \
    -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dzstd=enabled -Dexpat=enabled -Dvulkan-layers= -Dtools= -Dbuild-tests=false \
    > "$build.log" 2>&1 || { tail -40 "$build.log" >&2; exit 1; }
end_stage "configured $(grep -cE '^\[[0-9]+/[0-9]+\]' "$build.log" || true) targets"
begin "ninja (Turnip cross-compile)"
ninja -C "$build" >> "$build.log" 2>&1 || { tail -40 "$build.log" >&2; exit 1; }
end_stage "compiled"
begin "meson install"
DESTDIR="$build/install" meson install -C "$build" --no-rebuild >> "$build.log" 2>&1
end_stage "installed"

begin "strip + install artifacts"
installed=$build/install$rootfs/usr
mkdir -p "$out"
library=libvulkan_freedreno$suffix.so
manifest=freedreno${suffix}_icd.aarch64.json
llvm-strip --strip-debug -o "$out/$library" \
    "$installed/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
sed "s|/libvulkan_freedreno.so\"|/$library\"|" \
    "$installed/share/vulkan/icd.d/freedreno_icd.aarch64.json" > "$out/$manifest"
grep -q "/$library\"" "$out/$manifest" || { echo "Manifest does not name $library" >&2; exit 1; }
cp -r "$installed/share/drirc.d" "$out/drirc.d"
if "$readelf_cmd" -d "$out/$library" | grep -q keysyms; then
    echo "$library needs libxcb-keysyms, which the rootfs lacks" >&2
    exit 1
fi
[[ -s $out/$library ]] || die "$variant: missing driver output $out/$library"
[[ -s $out/$manifest ]] || die "$variant: missing ICD manifest $out/$manifest"
end_stage "outputs: $out/$library, $out/$manifest"
echo "Turnip $variant: $out (Mesa $(git -C "$src" rev-parse --short HEAD) + ${#patches[@]} patches)"
