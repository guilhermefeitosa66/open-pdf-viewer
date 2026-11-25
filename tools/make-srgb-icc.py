#!/usr/bin/env python3
"""Writes a small sRGB ICC v2 display profile (original work, Apache-2.0) used as the PDF/A
output intent. Run once; the result is committed as app/src/main/assets/icc/sRGB.icc."""
import struct
import sys


def s15(v):
    return struct.pack(">i", round(v * 65536))


def pad4(b):
    return b + b"\0" * (-len(b) % 4)


def xyz(x, y, z):
    return b"XYZ " + b"\0" * 4 + s15(x) + s15(y) + s15(z)


def curve():
    vals = []
    for i in range(1024):
        c = i / 1023
        lin = c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
        vals.append(round(lin * 65535))
    return b"curv" + b"\0" * 4 + struct.pack(">I", len(vals)) + b"".join(struct.pack(">H", v) for v in vals)


def desc(text):
    a = text.encode("ascii") + b"\0"
    return (b"desc" + b"\0" * 4 + struct.pack(">I", len(a)) + a
            + struct.pack(">II", 0, 0) + struct.pack(">HB", 0, 0) + b"\0" * 67)


def textt(text):
    return b"text" + b"\0" * 4 + text.encode("ascii") + b"\0"


trc = curve()
tags = [
    (b"desc", desc("sRGB IEC61966-2.1 (Open PDF Viewer)")),
    (b"cprt", textt("No copyright, use freely")),
    (b"wtpt", xyz(0.9642, 1.0, 0.8249)),
    # sRGB primaries adapted to D50 (Bradford).
    (b"rXYZ", xyz(0.4361, 0.2225, 0.0139)),
    (b"gXYZ", xyz(0.3851, 0.7169, 0.0971)),
    (b"bXYZ", xyz(0.1431, 0.0606, 0.7141)),
    (b"rTRC", trc), (b"gTRC", trc), (b"bTRC", trc),
]

table_size = 4 + 12 * len(tags)
offset = 128 + table_size
entries = b""
data = b""
placed = {}
for sig, body in tags:
    if id(body) in placed:  # the three curves share one copy
        off, size = placed[id(body)]
    else:
        off, size = offset + len(data), len(body)
        placed[id(body)] = (off, size)
        data += pad4(body)
    entries += sig + struct.pack(">II", off, size)

size = offset + len(data)
header = (struct.pack(">I", size) + b"\0" * 4 + struct.pack(">I", 0x02100000) + b"mntr" + b"RGB " + b"XYZ "
          + struct.pack(">6H", 2026, 1, 1, 0, 0, 0) + b"acsp" + b"\0" * 4 + struct.pack(">I", 0)
          + b"\0" * 4 + b"\0" * 4 + b"\0" * 8 + struct.pack(">I", 0)
          + s15(0.9642) + s15(1.0) + s15(0.8249) + b"\0" * 4 + b"\0" * 16 + b"\0" * 28)
assert len(header) == 128, len(header)
out = header + struct.pack(">I", len(tags)) + entries + data
open(sys.argv[1] if len(sys.argv) > 1 else "sRGB.icc", "wb").write(out)
print(len(out), "bytes")
