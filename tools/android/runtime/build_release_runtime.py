#!/usr/bin/env python3
"""Build the game-free Android runtime archive embedded in the release APK.

Inputs are the migrated Debian/FEX rootfs, Turnip from build_turnip.sh (in place of the
rootfs's own) and the local native ARM64 build.
Python packages are fetched from Debian trixie with Packages-index SHA-256 checks.
"""
import hashlib
import io
import json
import lzma
import os
import posixpath
import subprocess
import tarfile
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'tools/android/inputs'))

from fetch_arm64_sysroot import MIRROR, SUITE, extract_deb, fetch, parse_packages
from tools.android.runtime.validate_runtime_elf import ElfError, rewrite_interpreter, validate_tree
from tools.android.runtime.validate_runtime_archive import ArchiveError, validate_archive

SOURCE = Path(os.environ.get(
    'GOW3_RUNTIME_ROOTFS',
    str(ROOT / '.local-deps/android/migration/x/rootfs'))).resolve()
NATIVE = ROOT / 'out/arm64/bundle'
TURNIP = ROOT / 'out/arm64/turnip'
TURNIP_GEN8 = ROOT / 'out/arm64/turnip-gen8'
LSFG = ROOT / 'out/arm64/lsfg'  # build_lsfg.sh: the lsfg-vk 1.0 layer with our Turnip patches
LOCAL_TURNIP = ROOT / '.local-deps/android/turnip'
LOCAL_TURNIP_GEN8 = ROOT / '.local-deps/android/turnip-gen8'
LOCAL_LSFG = ROOT / '.local-deps/android/lsfg'
# The rootfs's Turnip, replaced by TURNIP's (rootfs-relative path: file there).
TURNIP_FILES = {
    'usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so': 'libvulkan_freedreno.so',
    'usr/share/vulkan/icd.d/freedreno_icd.aarch64.json': 'freedreno_icd.aarch64.json',
    'usr/share/drirc.d/00-mesa-defaults.conf': 'drirc.d/00-mesa-defaults.conf',
    'usr/share/drirc.d/00-turnip-defaults.conf': 'drirc.d/00-turnip-defaults.conf',
}
# The Adreno 8xx variant (build_turnip.sh gen8), beside it; run-gow3.sh picks one.
TURNIP_GEN8_FILES = {
    'usr/lib/aarch64-linux-gnu/libvulkan_freedreno_gen8.so': 'libvulkan_freedreno_gen8.so',
    'usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json': 'freedreno_gen8_icd.aarch64.json',
}
OUT = ROOT / 'out/release'
RUNTIME_LOADER = '/data/user/0/com.gow3.android/files/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1'
EXPLICIT_LOADER_ENTRYPOINTS = {
    'bin/sh',
    'usr/bin/FEX',
    'usr/bin/Xvfb',
    'usr/bin/python3.13',
    'usr/libexec/fxshmd',
}
PYTHON = ('python3.13-minimal', 'libpython3.13-minimal', 'libpython3.13-stdlib')
EXCLUDE_ROOTFS = ('dev', 'home', 'root', 'run', 'tmp', 'var', 'opt',
                  'usr/include', 'usr/share/man',
                  'etc/shadow', 'etc/shadow-', 'etc/gshadow', 'etc/gshadow-',
                  'etc/passwd', 'etc/passwd-', 'etc/group', 'etc/group-',
                  'etc/hostname', 'etc/hosts', 'etc/resolv.conf', 'etc/machine-id',
                  'etc/ssh')


def checked_packages(dest):
    index = parse_packages(lzma.decompress(fetch(
        f'{MIRROR}/dists/{SUITE}/main/binary-arm64/Packages.xz')).decode())
    records = []
    for name in PYTHON:
        record = index[name]
        data = fetch(f"{MIRROR}/{record['Filename']}")
        if hashlib.sha256(data).hexdigest() != record['SHA256']:
            raise ValueError(f'Debian SHA-256 mismatch: {name}')
        extract_deb(data, dest)
        records.append(f"{name} {record['Version']} {record['SHA256']}")
    return records


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def scrub(member):
    relative = member.name.removeprefix('rootfs/').rstrip('/')
    if relative and any(relative == item or relative.startswith(item + '/')
                        for item in EXCLUDE_ROOTFS):
        return None
    if member.name.startswith('rootfs/') and relative in TURNIP_FILES:
        return None
    if member.isdev() or member.isfifo():
        return None
    if member.issym():
        if member.linkname.startswith(('/usr/share/man/', '/usr/share/doc/', '/usr/share/info/')):
            return None
        target = posixpath.normpath(posixpath.join(posixpath.dirname(member.name), member.linkname))
        if target.startswith(('rootfs/usr/share/man/', 'rootfs/usr/share/doc/',
                              'rootfs/usr/share/info/', 'rootfs/run/',
                              'rootfs/dev/', 'rootfs/proc/', 'rootfs/sys/')):
            return None
    if member.issym() and member.linkname.startswith('/'):
        target = member.linkname
        own_roots = (
            '/data/user/0/com.gow3.android/files/rootfs/',
            '/data/data/com.gow3.android/files/rootfs/',
        )
        own_root = next((root for root in own_roots if target.startswith(root)), None)
        if own_root:
            target = target[len(own_root):]
        elif 'com.gp.android' in target:
            raise SystemExit('Refusing legacy com.gp.android symlink: ' + target)
        elif target.startswith(('/system/', '/dev/', '/proc/', '/sys/', '/run/', '/mnt/', '/storage/')):
            return None
        else:
            target = target.lstrip('/')
        top, _, rest = target.partition('/')
        if top in ('bin', 'lib', 'sbin') and (SOURCE / top).is_symlink():
            target = 'usr/' + target
        member.linkname = posixpath.relpath('rootfs/' + target,
                                            posixpath.dirname(member.name))
    member.uid = member.gid = 0
    member.uname = member.gname = ''
    return member


def owned_by_root(member):
    member.uid = member.gid = 0
    member.uname = member.gname = ''
    return member


class PortableTarFile(tarfile.TarFile):
    """Keep symlinks, but materialize hard links as regular files.

    Android's toybox tar can fail to restore hard links in an app-private
    directory.  TarFile.dereference=True would avoid that, but it also follows
    symlinks before ``scrub`` can validate them.  Convert only hard-link
    entries after the normal lstat-based TarFile logic has identified them.
    """

    def gettarinfo(self, name=None, arcname=None, fileobj=None):
        member = super().gettarinfo(name, arcname, fileobj)
        if member is not None and member.islnk():
            if name is None:
                raise OSError('hard-link entry has no source path: %s' % member.name)
            member.type = tarfile.REGTYPE
            member.linkname = ''
            member.size = os.stat(name).st_size
        return member


def add_icd_manifest(tar, source, arcname, library_path):
    """Add an ICD JSON with a path valid inside this app's private runtime.

    Turnip artifacts extracted from Bloodborne-Android contain an absolute
    library_path for Bloodborne's package.  Keep the source artifact untouched,
    but normalize the copy embedded in gow3port to this application's path.
    """
    try:
        manifest = json.loads(Path(source).read_text())
        icd = manifest['ICD']
        if not isinstance(icd, dict):
            raise ValueError('ICD is not an object')
        icd['library_path'] = library_path
        payload = (json.dumps(manifest, indent=4) + '\n').encode()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise SystemExit(f'Invalid Vulkan ICD manifest {source}: {error}') from error
    info = tar.gettarinfo(str(source), arcname=arcname)
    info.size = len(payload)
    info.uid = info.gid = 0
    info.uname = info.gname = ''
    tar.addfile(info, io.BytesIO(payload))


def turnip_input(turnip, name):
    """Resolve artifacts from both built Turnip and APK-extracted layouts."""
    candidate = turnip / name
    if candidate.is_file():
        return candidate
    if name.startswith('drirc.d/'):
        flat = turnip / Path(name).name
        if flat.is_file():
            return flat
    return candidate


def validate_elf_inputs(overlay):
    """Reject stale ELF paths and require an explicit strategy for short PT_INTERP slots."""
    reject = ('com.gp.android', '/Users/', '/home/')
    try:
        validate_tree(SOURCE, RUNTIME_LOADER, reject=reject,
                      explicit_paths=EXPLICIT_LOADER_ENTRYPOINTS,
                      allow_no_interp={'usr/bin/ld.so'})
        validate_tree(overlay, RUNTIME_LOADER, reject=reject,
                      explicit_paths={'usr/bin/python3.13'})
    except ElfError as error:
        raise SystemExit('Invalid runtime ELF: %s' % error) from error


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=('release', 'debug'), default='release')
    args = parser.parse_args()
    debug = args.profile == 'debug'
    turnip = LOCAL_TURNIP if debug else TURNIP
    turnip_gen8 = LOCAL_TURNIP_GEN8 if debug else TURNIP_GEN8
    lsfg = LOCAL_LSFG if debug else LSFG
    OUT = ROOT / ('out/debug' if debug else 'out/release')
    manifest_path = ROOT / ('tools/android/debug/runtime-manifest.json' if debug
                            else 'tools/android/runtime/runtime-manifest.json')
    missing = []
    if not (SOURCE / 'usr/bin/FEX').is_file():
        missing.append('migrated rootfs: usr/bin/FEX')
    if not debug and not (SOURCE / 'usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so').is_file():
        missing.append('migrated rootfs: usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so')
    if not (NATIVE / 'bin/gow3-probe').is_file():
        missing.append('ARM64 bundle: bin/gow3-probe (run build_arm64_bundle.py)')
    if not (NATIVE / 'lib/libgow3cpu.so').is_file():
        missing.append('ARM64 bundle: lib/libgow3cpu.so (run build_arm64_bundle.py)')
    missing.extend('main Turnip: ' + name for name in TURNIP_FILES.values()
                   if not turnip_input(turnip, name).is_file())
    missing.extend('Gen8 Turnip: ' + name for name in TURNIP_GEN8_FILES.values()
                   if not turnip_input(turnip_gen8, name).is_file())
    if not (lsfg / 'liblsfg-vk.so').is_file():
        missing.append('LSFG: liblsfg-vk.so (prepare .local-deps/android/lsfg or run build_lsfg.sh)')
    if not (lsfg / 'VkLayer_LS_frame_generation.json').is_file():
        missing.append('LSFG: VkLayer_LS_frame_generation.json (prepare .local-deps/android/lsfg or run build_lsfg.sh)')
    if missing:
        raise SystemExit('Missing Android runtime inputs:\n  ' + '\n  '.join(missing))
    OUT.mkdir(exist_ok=True)
    overlay = OUT / 'python-overlay'
    overlay.mkdir(exist_ok=True)
    records = checked_packages(overlay)
    python = overlay / 'usr/bin/python3.13'
    try:
        rewrite_interpreter(python, RUNTIME_LOADER)
    except ElfError as error:
        # Debian's normal PT_INTERP slot is shorter than the app-private path.
        # run-gow3.sh invokes the rootfs loader explicitly in this case.
        print('Python PT_INTERP uses explicit loader: %s' % error)
    validate_elf_inputs(overlay)
    archive = OUT / 'runtime.tar.gz'
    # Android's toybox tar cannot reliably create hard links inside an app-private
    # directory (for example Debian's usr/bin/perl5.40.1 -> perl).  Store each
    # hard-linked file as an independent regular file; PortableTarFile keeps
    # symbolic links intact so scrub() can still validate them.
    with PortableTarFile.open(archive, 'w:gz', compresslevel=6,
                              format=tarfile.PAX_FORMAT, dereference=False) as tar:
        tar.add(SOURCE, arcname='rootfs', filter=scrub)
        # Overlay Debian Python after the base rootfs. Its standard library is needed to
        # link the user's game files at first launch, never shipped in the APK.
        for child in sorted(overlay.iterdir()):
            tar.add(child, arcname='rootfs/' + child.name, filter=scrub)
        for relative, name in TURNIP_FILES.items():
            arcname = 'rootfs/' + relative
            source = turnip_input(turnip, name)
            if relative.endswith('.json'):
                add_icd_manifest(
                    tar, source, arcname,
                    RUNTIME_LOADER.replace('/ld-linux-aarch64.so.1',
                                           '/libvulkan_freedreno.so'))
            else:
                tar.add(source, arcname=arcname, filter=owned_by_root)
        for relative, name in TURNIP_GEN8_FILES.items():
            arcname = 'rootfs/' + relative
            source = turnip_input(turnip_gen8, name)
            if relative.endswith('.json'):
                add_icd_manifest(
                    tar, source, arcname,
                    RUNTIME_LOADER.replace('/ld-linux-aarch64.so.1',
                                           '/libvulkan_freedreno_gen8.so'))
            else:
                tar.add(source, arcname=arcname, filter=owned_by_root)
        for name, mode in (('rootfs/tmp', 0o1777), ('rootfs/tmp/.X11-unix', 0o1777),
                           ('rootfs/run', 0o755), ('rootfs/home', 0o755),
                           ('rootfs/var', 0o755),
                           ('rootfs/var/cache', 0o755), ('rootfs/var/log', 0o755)):
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE
            info.mode = mode
            tar.addfile(info)
        for directory in ('bin', 'lib', 'patch-parts'):
            tar.add(NATIVE / directory, arcname='gow3port/arm64/' + directory)
        tar.add(lsfg, arcname='gow3port/arm64/lsfg', filter=owned_by_root)
        # The runtime scripts import one another (and patches.py/launch_env.py are
        # invoked directly by run-gow3.sh). Keep the whole script directory in the
        # payload so adding a helper cannot silently produce an incomplete APK.
        for origin in sorted((ROOT / 'scripts').glob('*.py')):
            tar.add(origin, arcname='gow3port/scripts/' + origin.name)
        tar.add(ROOT / 'tools/android/inputs/prepare_game.py',
                arcname='gow3port/scripts/prepare_game.py')
        if (ROOT / 'patches').is_dir():
            tar.add(ROOT / 'patches', arcname='gow3port/patches')
        for source, name in (
            ('tools/android/device/run-gow3.sh', 'run-gow3.sh'),
            ('tools/android/config/gow3-fex-compatible.json', 'gow3-fex-compatible.json'),
            ('tools/android/config/gow3-gamecontrollerdb.txt', 'gow3-gamecontrollerdb.txt'),
            ('tools/android/config/gow3-default.ini', 'gow3.default.ini'),
            ('tools/android/config/gow3-iqoo15r.ini', 'gow3.iqoo15r.ini'),
        ):
            tar.add(ROOT / source, arcname='gow3port/' + name)
        tar.add(manifest_path, arcname='runtime-manifest.json',
                filter=owned_by_root)
        for name in ('gow3port/data', 'gow3port/logs', 'gow3port/user', 'home', 'home/gow3port'):
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            tar.addfile(info)
    digest = file_sha256(archive)
    (OUT / 'runtime.sha256').write_text(digest + '\n')
    (OUT / 'runtime.python-packages.txt').write_text('\n'.join(records) + '\n')
    with tarfile.open(archive) as tar:
        names = tar.getnames()
    banned = ('boot-linked.bin', 'eboot.bin', 'eboot.elf', 'game/', 'save/', 'Lossless.dll')
    if any(any(word in name for word in banned) for name in names):
        archive.unlink()
        raise SystemExit('Refusing to package a game-derived file')
    try:
        validate_archive(archive, OUT / 'runtime.sha256', manifest_path)
    except ArchiveError as error:
        archive.unlink()
        (OUT / 'runtime.sha256').unlink(missing_ok=True)
        raise SystemExit('Runtime archive validation failed: %s' % error) from error
    print(f'Runtime: {archive} ({archive.stat().st_size:,} bytes), sha256 {digest}')


if __name__ == '__main__':
    main()
