#!/usr/bin/env python3
"""Read facts from an Android boot.img (header v3/v4) kernel.

usage: kinfo.py banner <boot.img>        -> 'Linux version ...' line
       kinfo.py release <boot.img>       -> kernel release, e.g. 4.19.325-cip136-st20-perf-g986c913a0783
       kinfo.py hash <boot.img>          -> 12-hex kernel commit from the release suffix
       kinfo.py config <boot.img> <out>  -> writes the embedded IKCONFIG
"""
import gzip
import re
import struct
import sys
import zlib


def kernel_bytes(path):
    data = open(path, "rb").read()
    if data[:8] != b"ANDROID!":
        raise SystemExit(f"{path}: not an Android boot image")
    kernel_size, = struct.unpack_from("<I", data, 8)
    header_version, = struct.unpack_from("<I", data, 40)
    if header_version < 3:
        raise SystemExit(f"{path}: boot header v{header_version} not supported")
    k = data[4096:4096 + kernel_size]
    if k[:2] == b"\x1f\x8b":
        k = gzip.decompress(k)
    return k


def banner(k):
    m = re.search(rb"Linux version [0-9][^\n\0]+", k)
    if not m:
        raise SystemExit("no kernel banner found")
    return m.group(0).decode(errors="replace")


def release(k):
    return re.match(r"Linux version (\S+)", banner(k)).group(1)


def commit_hash(k):
    m = re.search(r"-g([0-9a-f]{12})$", release(k))
    if not m:
        raise SystemExit(f"release {release(k)!r} has no -g<12hex> suffix")
    return m.group(1)


def config(k):
    s = k.find(b"IKCFG_ST")
    e = k.find(b"IKCFG_ED", s)
    if s < 0 or e < 0:
        raise SystemExit("no IKCONFIG in kernel")
    return zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(k[s + 8:e])


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    cmd, path = argv[1], argv[2]
    k = kernel_bytes(path)
    if cmd == "banner":
        print(banner(k))
    elif cmd == "release":
        print(release(k))
    elif cmd == "hash":
        print(commit_hash(k))
    elif cmd == "config":
        open(argv[3], "wb").write(config(k))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
