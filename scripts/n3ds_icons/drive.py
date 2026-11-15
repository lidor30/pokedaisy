import sys, os, subprocess, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pk6tool import make
DEV = ['adb', '-s', 'fdf3a74a']
DBG = '/sdcard/Android/data/com.pokedaisy.app/files/debug'
WORK = os.environ.get('N3DS_ICONS_WORK', '.')
TEMPLATE = open(os.path.join(WORK, 'template.bin'), 'rb').read()  # one party slot's 0xE8 stored bytes
def sh(cmd): return subprocess.run(DEV + ['shell', cmd], capture_output=True, text=True).stdout
def press(code, hold=0.12):
    sh('sendevent /dev/input/event7 1 %d 1; sendevent /dev/input/event7 0 0 0; sleep %s; sendevent /dev/input/event7 1 %d 0; sendevent /dev/input/event7 0 0 0' % (code, hold, code))
def run_cmd(lines, timeout=10):
    sh('rm -f %s/n3ds_cmd.done' % DBG)
    tmp = '/data/local/tmp/n3ds_cmd'
    p = subprocess.run(DEV + ['shell', 'cat > %s' % tmp], input='\n'.join(lines) + '\n', text=True)
    sh('cp %s %s/n3ds_cmd.tmp && chmod 666 %s/n3ds_cmd.tmp && mv %s/n3ds_cmd.tmp %s/n3ds_cmd' % (tmp, DBG, DBG, DBG, DBG))
    t = time.time()
    while time.time() - t < timeout:
        if 'n3ds_cmd.done' in sh('ls %s' % DBG): return True
        time.sleep(0.2)
    return False
def batch(name, entries):
    lines = []
    for slot, e in enumerate(entries):
        data = make(TEMPLATE, *e).hex()
        for base, stride in ((0x08CE1CE8, 0x1E4), (0x08C79DA8, 0x104)):
            lines.append('poke %x %s' % (base + slot * stride, data))
    assert run_cmd(lines)
    press(305); time.sleep(3.2)
    assert run_cmd(['shot ' + name])
    press(304); time.sleep(2.6)
if __name__ == '__main__':
    batch('cal', [(1,)] * 6)
