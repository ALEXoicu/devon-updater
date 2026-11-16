#!/usr/bin/env python3
"""Extract one partition image from a full A/B OTA (zip with payload.bin, or bare payload.bin).

usage: payload_extract.py <ota.zip|payload.bin> <partition> <out.img>

Supports full-payload operations only (REPLACE, REPLACE_BZ, REPLACE_XZ, ZERO, DISCARD,
REPLACE_ZSTD if Python has compression.zstd). Verifies the partition sha256 from the manifest.
"""
import bz2
import hashlib
import lzma
import struct
import sys
import zipfile


def varint(b, i):
    v = s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return v, i


def fields(b):
    """Yield (field_number, value) for a protobuf message; value is int or bytes."""
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(b, i)
        elif wt == 2:
            n, i = varint(b, i)
            v, i = b[i:i + n], i + n
        elif wt == 1:
            v, i = struct.unpack_from("<Q", b, i)[0], i + 8
        elif wt == 5:
            v, i = struct.unpack_from("<I", b, i)[0], i + 4
        else:
            raise ValueError(f"wire type {wt}")
        yield num, v


def payload_offset(path):
    with open(path, "rb") as f:
        if f.read(4) == b"CrAU":
            return 0
    with zipfile.ZipFile(path) as z:
        info = z.getinfo("payload.bin")
        if info.compress_type != zipfile.ZIP_STORED:
            raise SystemExit("payload.bin is compressed in zip")
    with open(path, "rb") as f:
        f.seek(info.header_offset)
        h = f.read(30)
        n, m = struct.unpack_from("<HH", h, 26)
        return info.header_offset + 30 + n + m


def decompressor(op_type):
    if op_type == 0:
        return lambda d: d
    if op_type == 1:
        return bz2.decompress
    if op_type == 8:
        return lzma.decompress
    if op_type == 14:
        from compression import zstd  # Python >= 3.14
        return zstd.decompress
    return None


def main():
    src, want, out = sys.argv[1:4]
    base = payload_offset(src)
    f = open(src, "rb")
    f.seek(base)
    hdr = f.read(24)
    magic, version, msize = hdr[:4], *struct.unpack(">QQ", hdr[4:20])
    if magic != b"CrAU" or version != 2:
        raise SystemExit(f"bad payload header {magic!r} v{version}")
    sigsize, = struct.unpack(">I", hdr[20:24])
    manifest = f.read(msize)
    data_start = base + 24 + msize + sigsize
    block = 4096
    parts = []
    for num, v in fields(manifest):
        if num == 3:
            block = v
        elif num == 13:
            parts.append(v)
    for p in parts:
        name, ops, size, digest = None, [], None, None
        for num, v in fields(p):
            if num == 1:
                name = v.decode()
            elif num == 8:
                ops.append(v)
            elif num == 7:
                for n2, v2 in fields(v):
                    if n2 == 1:
                        size = v2
                    elif n2 == 2:
                        digest = v2
        if name != want:
            continue
        with open(out, "wb") as o:
            if size:
                o.truncate(size)
            for op in ops:
                typ, off, length, ext = 0, 0, 0, []
                for num, v in fields(op):
                    if num == 1:
                        typ = v
                    elif num == 2:
                        off = v
                    elif num == 3:
                        length = v
                    elif num == 6:
                        e = dict(fields(v))
                        ext.append((e.get(1, 0), e.get(2, 0)))
                if typ in (6, 7):  # ZERO / DISCARD: file was truncated (sparse zeros)
                    continue
                dec = decompressor(typ)
                if dec is None:
                    raise SystemExit(f"{name}: unsupported op type {typ} (not a full OTA?)")
                f.seek(data_start + off)
                data = dec(f.read(length))
                pos = 0
                for start, nblk in ext:
                    n = nblk * block
                    o.seek(start * block)
                    o.write(data[pos:pos + n])
                    pos += n
        if digest:
            h = hashlib.sha256()
            with open(out, "rb") as r:
                for chunk in iter(lambda: r.read(1 << 20), b""):
                    h.update(chunk)
            if h.digest() != digest:
                raise SystemExit(f"{name}: sha256 mismatch after extraction")
        print(f"{name}: {size} bytes, sha256 ok")
        return
    raise SystemExit(f"partition {want!r} not in payload ({len(parts)} partitions)")


if __name__ == "__main__":
    main()
