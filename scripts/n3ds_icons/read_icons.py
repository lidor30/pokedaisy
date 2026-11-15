import sys, os, time, pickle, subprocess
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from romfs import RomFS
from gen6text import garc_files
import drive, match
WORK = os.environ.get('N3DS_ICONS_WORK', '.'); OUT = os.path.join(WORK, 'iconmap.tsv'); SHOTS = os.path.join(WORK, 'iconshots')
os.makedirs(SHOTS, exist_ok=True)
r = RomFS(sys.argv[1]); p = garc_files(r.read(*r.find('a/2/1/8')))
entries = []
for s in range(1, 722):
    ratio, fc = p[s][0x12], p[s][0x20]
    genders = ['n'] if ratio == 0xFF else ['f'] if ratio == 0xFE else ['m'] if ratio == 0 else ['m', 'f']
    for g in genders: entries.append((s, 0, g))
    for f in range(1, fc): entries.append((s, f, genders[0]))
done = set()
if os.path.exists(OUT):
    for line in open(OUT):
        a = line.split('\t'); done.add((int(a[0]), int(a[1]), a[2]))
todo = [e for e in entries if e not in done]
print('entries', len(entries), 'todo', len(todo), flush=True)
offs = pickle.load(open(os.path.join(WORK, 'offs.pkl'), 'rb'))[1:]
n = 0
while todo:
    chunk, todo = todo[:5], todo[5:]
    mons = [(1,)] + [(s, f, g == 'f', g == 'n') for s, f, g in chunk]
    name = 'b%04d' % n; n += 1
    drive.batch(name, mons)
    local = os.path.join(SHOTS, name + '.png')
    subprocess.run(drive.DEV + ['pull', drive.DBG + '/' + name + '.png', local], capture_output=True)
    subprocess.run(drive.DEV + ['shell', 'rm -f %s/%s.png' % (drive.DBG, name)], capture_output=True)
    shot = match.png(local)
    res = match.identify(shot, offs[:len(chunk)])
    with open(OUT, 'a') as f:
        for (s, fo, g), (k, e, e2, k2) in zip(chunk, res):
            f.write('%d\t%d\t%s\t%d\t%.1f\t%.1f\t%d\n' % (s, fo, g, k, e, e2, k2))
    print(name, [(c, k, e) for c, (k, e, _, _) in zip(chunk, res)], flush=True)
    if all(e > 25 for _, e, _, _ in res): print('lost the party screen, stopping', flush=True); break
