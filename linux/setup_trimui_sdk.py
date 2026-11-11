"""Download the exact NextUI/TrimUI build inputs used for the TrimUI port.

No elevated install and no changes outside DEST. Python 3.12+, curl and xz required.
"""
from pathlib import Path
import hashlib, os, shutil, subprocess, sys, tarfile
root = Path(sys.argv[1]).resolve()
root.mkdir(parents=True, exist_ok=True)
archives = [
    ('toolchain.tar.xz', 'LoveRetro/gcc-arm-8.3-aarch64-tg5040', 350728970,
     'd4ac4ef1968668f93f625f2b226c69d74739ae58ff640d2ce3aaae6b4d1b93d3', 'cross'),
    ('sdk.tgz', 'trimui/toolchain_sdk_smartpro', 144208594,
     'b6b615d03204e9d9bb1a91c31de0c3402434f6b8fc780743fa88a5f1da6f3c79', 'sdk'),
]
def writable(member, path):
    # SDK programs contain absolute target-root links; keep them inside DEST.
    if member.issym() and member.linkname.startswith('/'):
        member = member.replace(linkname=os.path.relpath(member.linkname.lstrip('/'), os.path.dirname(member.name) or '.'))
    elif member.islnk() and member.linkname.startswith('/'):
        member = member.replace(linkname=member.linkname.lstrip('/'))
    if member.issym():
        # Ubuntu 22.04's Python 3.10.12 resolves a relative link from the wrong folder and refuses the toolchain's
        # (lib/bfd-plugins/liblto_plugin.so -> ../../libexec/...): check the link here, the rest in data_filter.
        target = os.path.normpath(os.path.join(os.path.dirname(member.name), member.linkname))
        if os.path.isabs(target) or target == '..' or target.startswith('../'):
            raise tarfile.LinkOutsideDestinationError(member, target)
        checked = tarfile.data_filter(member.replace(linkname=os.path.basename(member.name)), path)
        member = checked.replace(linkname=member.linkname) if checked else None
    else:
        member = tarfile.data_filter(member, path)
    if member:
        member.uid = member.gid = os.getuid()
        if member.mode is not None:
            member.mode |= 0o700
    return member
for name, repo, asset, sha, dest in archives:
    archive = root / name
    if not archive.exists():
        temporary = archive.with_suffix('.download')
        subprocess.run(['curl', '-fLsS', '--retry', '3', '-H', 'Accept: application/octet-stream',
                        f'https://api.github.com/repos/{repo}/releases/assets/{asset}', '-o', str(temporary)], check=True)
        temporary.rename(archive)
    digest = hashlib.sha256()
    with archive.open('rb') as f:  # file_digest needs Python 3.11; ubuntu-22.04 has 3.10
        for chunk in iter(lambda: f.read(1 << 20), b''):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != sha:
        raise SystemExit(f'Archive checksum mismatch: {name}')
    with tarfile.open(archive) as t:
        t.extractall(root / dest, filter=writable)
toolchain = root / 'cross/x86_64-aarch64-nextui-linux-gnu'
sysroot = toolchain / 'aarch64-nextui-linux-gnu/libc/usr'
# Keep GCC's libm/libdl linker scripts. Overlay the SDK's headers and libraries.
def copy_tree(source, target):
    target.mkdir(parents=True, exist_ok=True)
    for p in source.iterdir():
        q = target / p.name
        if p.is_symlink():
            if q.exists() or q.is_symlink():
                continue
            q.symlink_to(os.readlink(p))
        elif p.is_dir():
            copy_tree(p, q)
        else:
            shutil.copy2(p, q)
copy_tree(root / 'sdk/usr', sysroot)
print(toolchain)
