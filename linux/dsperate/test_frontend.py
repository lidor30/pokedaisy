"""Run the actual launcher/backend with an original cart; inspect its PNG output."""
from pathlib import Path
import os
import struct
import subprocess
import sys
import tempfile
import zlib

rom = str(Path(sys.argv[1]).resolve())
command = sys.argv[2:]  # optional QEMU prefix followed by the actual executable
with tempfile.TemporaryDirectory(prefix='pd-ds-frontend-') as directory:
    env = dict(os.environ, SDL_VIDEODRIVER='offscreen' if len(command) > 1 else 'dummy',
               SDL_AUDIODRIVER='dummy', SDL_RENDER_DRIVER='software')
    image = Path(directory) / 'actual.png'
    result = subprocess.run(command + ['--data', directory, '--frames', '5',
                            '--shot', str(image), rom], env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0, result.stderr
    assert 'DSperate DSPT' in result.stderr and 'core frames 5' in result.stderr, result.stderr
    png = image.read_bytes()
    assert png[:8] == b'\x89PNG\r\n\x1a\n'
    width, height = struct.unpack_from('>II', png, 16)
    compressed, offset = bytearray(), 8
    while offset < len(png):
        size = struct.unpack_from('>I', png, offset)[0]
        if png[offset+4:offset+8] == b'IDAT':
            compressed.extend(png[offset+8:offset+8+size])
        offset += size + 12
    raw = zlib.decompress(compressed)
    stride = width * 3 + 1
    assert all(raw[y*stride] == 0 for y in range(height))  # pd_png's filter
    pixel = raw[(height//2)*stride + 1 + (width//2)*3:][:3]
    assert pixel == b'\0\xfb\0', pixel
    # The actual companion opens but correctly refuses this unsupported cart.
    env['POCKET_DS_COMPANION_AT'] = '2'
    result = subprocess.run(command + ['--data', directory, '--frames', '5', rom],
                            env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0 and 'core frames 2' in result.stderr, result.stderr
    env.pop('POCKET_DS_COMPANION_AT')
    # Enter the shared quick menu through the actual L2 host path, select its
    # companion tile and prove emulation stays paused throughout both overlays.
    env['POCKET_DS_HOST_KEYS'] = '2:4096:1,4:1:1'
    result = subprocess.run(command + ['--data', directory, '--frames', '8', rom],
                            env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0 and 'core frames 2' in result.stderr, result.stderr
    # A missing manual state stays disabled, with the menu still paused.
    env['POCKET_DS_HOST_KEYS'] = '2:4096:1,4:128:1,6:1:1'
    result = subprocess.run(command + ['--data', directory, '--frames', '10', rom],
                            env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0 and 'core frames 2' in result.stderr, result.stderr
    # RIGHT selects SAVE STATE; it uses the existing DS state slot.
    env['POCKET_DS_HOST_KEYS'] = '2:4096:1,4:16:1,6:1:1'
    result = subprocess.run(command + ['--data', directory, '--frames', '10', rom],
                            env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0, result.stderr
    assert (Path(directory) / 'states' / (Path(rom).name + '.dsperate.ss')).stat().st_size > 1000
    # DOWN selects LOAD STATE: restore the actual state saved at core frame 2.
    env['POCKET_DS_HOST_KEYS'] = '2:4096:1,4:128:1,6:1:1'
    result = subprocess.run(command + ['--data', directory, '--frames', '10', rom],
                            env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0 and 'manual state loaded at core frame 2' in result.stderr, result.stderr
    # LEFT selects LEAVE GAME, returning without advancing a guest frame.
    env['POCKET_DS_HOST_KEYS'] = '2:4096:1,4:32:1,6:1:1'
    result = subprocess.run(command + ['--data', directory, '--frames', '10', rom],
                            env=env, capture_output=True, text=True, timeout=70)
    assert result.returncode == 0 and 'core frames 2' in result.stderr, result.stderr
    env.pop('POCKET_DS_HOST_KEYS')
    env['POCKET_DS_COMPANION_AT'] = '2'
    print('PASS shared quick menu: companion, save state, leave game and paused core')
    # Real process restarts: default-off, persisted opt-in, separate resume slot,
    # failed deserialization recovery, explicit bypass and newer-save precedence.
    env.pop('POCKET_DS_COMPANION_AT')
    env.pop('POCKET_DS_RESUME', None)
    root = Path(directory)
    resume = root / 'states' / (Path(rom).name + '.dsperate.resume.ss')
    manual = root / 'states' / (Path(rom).name + '.dsperate.ss')
    assert not resume.exists()
    manual.write_bytes(b'manual-slot-must-not-change')
    (root / 'ds-settings.ini').write_text('bottom=0\nresume=1\nunknown=1\n')

    def run(frames=2):
        result = subprocess.run(command + ['--data', directory, '--frames', str(frames), rom],
                                env=env, capture_output=True, text=True, timeout=70)
        assert result.returncode == 0, result.stderr
        return result.stderr

    assert 'resume captured at core frame 5' in run(5)
    assert resume.stat().st_size > 1000
    log = run()
    assert 'auto-resumed at core frame 5' in log and 'resume captured at core frame 7' in log, log
    assert manual.read_bytes() == b'manual-slot-must-not-change'
    before = resume.read_bytes()
    env['POCKET_DS_RESUME'] = '0'
    log = run()
    assert 'auto-resumed' not in log and 'core frames 2' in log, log
    assert resume.read_bytes() == before
    env.pop('POCKET_DS_RESUME')
    for bad in (b'', b'DSST' + b'\0' * 28, before[:len(before)//2]):
        resume.write_bytes(bad)
        log = run()
        assert 'resume ignored:' in log and 'core frames 2' in log, log
    # A battery save supplied after the resume file takes precedence.
    battery = root / 'saves' / (Path(rom).name + '.sav')
    battery.write_bytes(b'\xff' * 65536)
    now = resume.stat().st_mtime_ns
    os.utime(battery, ns=(now + 2_000_000_000, now + 2_000_000_000))
    log = run()
    assert 'resume skipped: battery save is newer' in log and 'core frames 2' in log, log
print('PASS restart/resume, corrupt-state recovery, bypass and newer-save precedence')
print('PASS real DS launcher, diagnostic display screenshot and companion pause')
