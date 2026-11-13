#!/usr/bin/env python3
"""Talks to the dev build's Wi-Fi link (ctr/devlink.c). See README.md's "The dev build".

  pdlink.py find                          listen for a console's beacon, print its address
  pdlink.py [-a IP] ping | stat | log | leave | restart
  pdlink.py [-a IP] put LOCAL [REMOTE]    a file into sdmc:/pokedaisy-dev/ (REMOTE "self" = the dev build)
  pdlink.py [-a IP] get REMOTE LOCAL
  pdlink.py [-a IP] ls [REMOTE] | rm REMOTE
  pdlink.py [-a IP] launch FILE           open a game from the list
  pdlink.py [-a IP] press KEYS [FRAMES]   KEYS: a+b+up ... (libctru's bits)
  pdlink.py [-a IP] tap X Y [FRAMES] | drag X0 Y0 X1 Y1 [FRAMES]
  pdlink.py [-a IP] opt SCREEN ASPECT     set them while playing (-1 leaves one alone; not saved)
  pdlink.py [-a IP] wild SPECIES LEVEL    a scripted wild battle, on the field of a GBA game
  pdlink.py [-a IP] teach SLOT INDEX MOVE a party Pokemon's move (the game's SetMonData), in RAM until the game saves
  pdlink.py [-a IP] shot OUT.png          both screens, stacked as the console shows them
  pdlink.py [-a IP] push BUILD.3dsx       the new build, then restart into it

The address comes from -a, $PDLINK_ADDR or build/pdlink.addr (written by `find`).
"""
import os
import socket
import struct
import sys
import zlib

PORT = 17498
BEACON_PORT = 17499
ADDR_FILE = os.path.join(os.path.dirname(__file__), "..", "build", "pdlink.addr")
KEYS = {"a": 1 << 0, "b": 1 << 1, "select": 1 << 2, "start": 1 << 3, "right": 1 << 4, "left": 1 << 5, "up": 1 << 6,
        "down": 1 << 7, "r": 1 << 8, "l": 1 << 9, "x": 1 << 10, "y": 1 << 11, "zl": 1 << 14, "zr": 1 << 15}


def find(timeout=30):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", BEACON_PORT))
    s.settimeout(timeout)
    data, peer = s.recvfrom(128)
    if not data.startswith(b"pokedaisy-dev"):
        raise SystemExit("not a PokeDaisy beacon")
    os.makedirs(os.path.dirname(ADDR_FILE), exist_ok=True)
    with open(ADDR_FILE, "w") as f:
        f.write(peer[0])
    return peer[0]


class Link:
    def __init__(self, addr):
        self.s = socket.create_connection((addr, PORT), timeout=20)
        self.s.settimeout(900)  # sendall counts the whole send: a ROM takes minutes over the 3DS's Wi-Fi
        self.f = self.s.makefile("rb")

    def call(self, line, payload=b""):
        self.s.sendall(line.encode() + b"\n" + payload)
        head = self.f.readline().decode().strip()
        if not head.startswith("OK"):
            raise SystemExit(head or "the link closed")
        return self.f.read(int(head.split()[1]))


def png(path, w, h, rgb):
    rows = b"".join(b"\0" + rgb[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) +
                chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def screen(raw, w, into, stride, x0, y0):
    """A framebuffer (columns of 240, bottom up, BGR) into the picture."""
    for x in range(w):
        col = raw[x * 720:(x + 1) * 720]
        for y in range(240):
            p = (239 - y) * 3
            at = ((y0 + y) * stride + x0 + x) * 3
            into[at:at + 3] = bytes((col[p + 2], col[p + 1], col[p]))


def shot(link, out):
    raw = link.call("shot")
    picture = bytearray(400 * 480 * 3)
    screen(raw[:400 * 720], 400, picture, 400, 0, 0)
    screen(raw[400 * 720:], 320, picture, 400, 40, 240)
    png(out, 400, 480, bytes(picture))


def main():
    args = sys.argv[1:]
    addr = os.environ.get("PDLINK_ADDR")
    if args and args[0] == "-a":
        addr = args[1]
        args = args[2:]
    if not args:
        raise SystemExit(__doc__)
    cmd, rest = args[0], args[1:]
    if cmd == "find":
        print(find())
        return
    if not addr and os.path.exists(ADDR_FILE):
        addr = open(ADDR_FILE).read().strip()
    if not addr:
        raise SystemExit("no address: run find, or give -a IP")
    link = Link(addr)
    if cmd in ("ping", "stat", "log", "leave", "restart"):
        sys.stdout.write(link.call(cmd).decode(errors="replace"))
    elif cmd == "put":
        data = open(rest[0], "rb").read()
        name = rest[1] if len(rest) > 1 else os.path.basename(rest[0])
        link.call(f"put {len(data)} {name}", data)
    elif cmd == "push":
        data = open(rest[0], "rb").read()
        link.call(f"put {len(data)} self", data)
        link.call("restart")
    elif cmd == "get":
        with open(rest[1], "wb") as f:
            f.write(link.call("get " + rest[0]))
    elif cmd == "ls":
        sys.stdout.write(link.call("ls " + rest[0] if rest else "ls").decode(errors="replace"))
    elif cmd in ("rm", "launch"):
        link.call(f"{cmd} {rest[0]}")
    elif cmd == "press":
        bits = sum(KEYS[k] for k in rest[0].lower().split("+"))
        link.call(f"press {bits:x} {rest[1] if len(rest) > 1 else 6}")
    elif cmd == "tap":
        link.call(f"touch {rest[0]} {rest[1]} {rest[2] if len(rest) > 2 else 6}")
    elif cmd == "drag":
        link.call(f"drag {rest[0]} {rest[1]} {rest[2]} {rest[3]} {rest[4] if len(rest) > 4 else 30}")
    elif cmd == "opt":
        link.call(f"opt {rest[0]} {rest[1]}")
    elif cmd == "wild":
        link.call(f"wild {rest[0]} {rest[1]}")
    elif cmd == "teach":
        link.call(f"teach {rest[0]} {rest[1]} {rest[2]}")
    elif cmd == "shot":
        shot(link, rest[0])
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
