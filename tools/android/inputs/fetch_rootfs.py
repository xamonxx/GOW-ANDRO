#!/usr/bin/env python3
"""Download the fexdroid legacy APK and install its ARM64 FEX rootfs.

The Android runtime rootfs is the ``assets/rootfs-arm64.tar`` payload from the
official fexdroid legacy APK. It is not a plain Debian rootfs and must not be
replaced with a generic Debian image.
"""
import argparse
import hashlib
import os
import posixpath
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path
from urllib.error import HTTPError, URLError


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_APK_URL = (
    'https://github.com/cobrabm12/fexdroid/releases/download/'
    'apk-latest/fexdroid-legacy.apk')
DEFAULT_OUT = ROOT / '.local-deps/android/migration/x/rootfs'
DEFAULT_APK = ROOT / '.local-deps/android/fexdroid-legacy.apk'
# Reviewed apk-latest asset used to create the current Gow3 migration input.
DEFAULT_SHA256 = '21bed34092f698e39e77138589ebdab8d3498577ffc4fdc3bfef1c711b50d3bf'
OLD_ROOT = '/data/data/ro.cobrabm.fexdroid/files/rootfs'
NEW_ROOT = '/data/user/0/com.gow3.android/files/rootfs'


def migrate_interpreters(rootfs):
    """Rewrite fexdroid's app-private ELF loader path for gow3.android."""
    import sys
    sys.path.insert(0, str(ROOT))
    from tools.android.runtime.validate_runtime_elf import ElfError, inspect, rewrite_interpreter

    rewritten = 0
    for path in sorted(rootfs.rglob('*')):
        # An absolute symlink resolves on the build host: never inspect or rewrite through it.
        if path.is_symlink() or not path.is_file():
            continue
        try:
            info = inspect(path)
        except ElfError:
            continue
        if info and info['interpreter'] and info['interpreter'].startswith(OLD_ROOT):
            target = NEW_ROOT + info['interpreter'][len(OLD_ROOT):]
            try:
                rewrite_interpreter(path, target)
            except ElfError as error:
                raise SystemExit(f'cannot migrate ELF interpreter {path}: {error}') from error
            rewritten += 1
    return rewritten


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, destination, attempts=4):
    # GitHub's release CDN intermittently answers 5xx for the redirected asset; retry with
    # backoff so a transient failure does not fail the whole build. Each attempt re-requests
    # the redirect and therefore gets a fresh signed asset URL.
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'gow3port-rootfs-fetch/1'})
            with urllib.request.urlopen(request, timeout=120) as response, destination.open('wb') as stream:
                shutil.copyfileobj(response, stream, length=1024 * 1024)
            return
        except HTTPError as error:
            if error.code < 500 or attempt == attempts:
                raise
        except (URLError, TimeoutError):
            if attempt == attempts:
                raise
        delay = 2 ** (attempt + 1)
        print(f'Download attempt {attempt}/{attempts} failed; retrying in {delay}s...', flush=True)
        time.sleep(delay)


def safe_tar_members(archive):
    members = archive.getmembers()
    for member in members:
        path = Path(member.name)
        if path.is_absolute() or '..' in path.parts:
            raise SystemExit(f'unsafe rootfs archive path: {member.name}')
        if member.issym() or member.islnk():
            target = Path(member.linkname)
            allowed_absolute = ('/bin/', '/dev/', '/etc/', '/home/', '/lib/', '/opt/',
                                '/proc/', '/root/', '/run/', '/sbin/', '/sys/', '/tmp/',
                                '/usr/', '/var/')
            resolved = posixpath.normpath(posixpath.join(
                posixpath.dirname(member.name), member.linkname))
            escapes_archive = resolved == '..' or resolved.startswith('../')
            if escapes_archive or (target.is_absolute() and not any(
                    target.as_posix() == root.rstrip('/') or
                    target.as_posix().startswith(root) for root in allowed_absolute)):
                raise SystemExit(f'unsafe rootfs link: {member.name} -> {member.linkname}')
    return members


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default=os.environ.get('GOW3_FEXDROID_APK_URL') or DEFAULT_APK_URL,
                        help='fexdroid legacy APK URL')
    parser.add_argument('--sha256', default=os.environ.get('GOW3_FEXDROID_APK_SHA256') or
                        DEFAULT_SHA256, help='expected APK SHA-256')
    parser.add_argument('--apk', type=Path, help='use a local APK instead of downloading')
    parser.add_argument('--out', type=Path, default=DEFAULT_OUT)
    parser.add_argument('--force', action='store_true', help='replace an existing rootfs')
    args = parser.parse_args(argv)

    args.out = args.out.resolve()
    if args.out.exists() and not args.force:
        if (args.out / 'usr/bin/FEX').is_file() and (args.out / 'usr/share/fex-emu').is_dir():
            print(f'FEX rootfs already exists: {args.out}')
            return 0
        parser.error(f'output exists but is incomplete; use --force: {args.out}')

    ROOT.joinpath('.local-deps').mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='gow3-fexdroid-', dir=ROOT / '.local-deps') as work:
        work = Path(work)
        apk = args.apk.resolve() if args.apk else DEFAULT_APK
        if args.apk:
            if not apk.is_file():
                parser.error(f'local APK does not exist: {apk}')
            actual = sha256(apk)
            if actual.lower() != args.sha256.lower():
                raise SystemExit(
                    f'fexdroid APK SHA-256 mismatch: expected {args.sha256}, got {actual}')
        elif not apk.is_file() or sha256(apk).lower() != args.sha256.lower():
            print(f'Downloading fexdroid legacy APK: {args.url}', flush=True)
            download(args.url, work / 'fexdroid-legacy.apk')
            actual = sha256(work / 'fexdroid-legacy.apk')
            if actual.lower() != args.sha256.lower():
                raise SystemExit(
                    f'fexdroid APK SHA-256 mismatch: expected {args.sha256}, got {actual}')
            apk.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(work / 'fexdroid-legacy.apk', apk)

        with zipfile.ZipFile(apk) as package:
            asset = 'assets/rootfs-arm64.tar'
            if asset not in package.namelist():
                raise SystemExit(f'fexdroid APK is missing {asset}')
            tar_path = work / 'rootfs-arm64.tar'
            with package.open(asset) as source, tar_path.open('wb') as destination:
                shutil.copyfileobj(source, destination, length=1024 * 1024)

        staging = work / 'rootfs'
        staging.mkdir()
        with tarfile.open(tar_path, 'r:') as archive:
            safe_tar_members(archive)
            # Python 3.12+ defaults extractall() to the strict data_filter, which rejects
            # symlinks to absolute paths. Debian rootfs legitimately contains them (e.g.
            # /etc/alsa/conf.d/*.conf -> /usr/share/alsa/alsa.conf.d/...), and every member
            # was already validated by safe_tar_members() above, so disable the filter.
            if sys.version_info >= (3, 12):
                archive.extractall(staging, filter='fully_trusted')
            else:
                archive.extractall(staging)
        if not (staging / 'usr/bin/FEX').is_file():
            raise SystemExit('rootfs-arm64.tar is missing usr/bin/FEX')
        if not (staging / 'usr/share/fex-emu').is_dir():
            raise SystemExit('rootfs-arm64.tar is missing usr/share/fex-emu')
        rewritten = migrate_interpreters(staging)
        if args.out.exists():
            shutil.rmtree(args.out)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staging), str(args.out))
    print(f'FEX rootfs ready: {args.out} (rewrote {rewritten} ELF interpreters)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
