#!/usr/bin/env python3
"""Check that vendor .ko modules will load against a freshly built kernel.

usage: modcheck.py <Module.symvers> <kernel.release> <dir-with-ko-files>

For each module: vermagic must start with kernel.release, and every
(symbol, crc) in its __versions section must match Module.symvers
(symbols exported by other vendor modules are resolved from those modules'
own exports and skipped here).
"""
import os
import struct
import sys


def elf_sections(data):
    assert data[:4] == b"\x7fELF" and data[4] == 2, "need ELF64"
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    secs = []
    for i in range(shnum):
        name, typ, flags, addr, off, size = struct.unpack_from("<IIQQQQ", data, shoff + i * shentsize)
        secs.append((name, off, size))
    _, stroff, _ = secs[shstrndx]
    out = {}
    for name, off, size in secs:
        end = data.index(b"\0", stroff + name)
        out[data[stroff + name:end].decode()] = data[off:off + size]
    return out


def main():
    symvers_path, release, moddir = sys.argv[1:4]
    kernel = {}
    for line in open(symvers_path):
        parts = line.split("\t")
        if len(parts) >= 2:
            kernel[parts[1]] = int(parts[0], 16)

    mods = sorted(f for f in os.listdir(moddir) if f.endswith(".ko"))
    bad = 0
    vendor_syms = set()
    parsed = {}
    for m in mods:
        secs = elf_sections(open(os.path.join(moddir, m), "rb").read())
        parsed[m] = secs
        # exported names of vendor modules live in __ksymtab_strings
        for s in secs.get("__ksymtab_strings", b"").split(b"\0"):
            if s:
                vendor_syms.add(s.decode())

    checked = 0
    for m in mods:
        secs = parsed[m]
        info = dict(
            kv.split("=", 1) for kv in secs.get(".modinfo", b"").decode(errors="replace").split("\0") if "=" in kv
        )
        vm = info.get("vermagic", "")
        problems = []
        if not vm.startswith(release + " "):
            problems.append(f"vermagic '{vm}'")
        vers = secs.get("__versions", b"")
        for i in range(0, len(vers), 64):
            crc, = struct.unpack_from("<Q", vers, i)
            name = vers[i + 8:i + 64].split(b"\0")[0].decode()
            checked += 1
            if name in kernel:
                if kernel[name] != crc:
                    problems.append(f"CRC mismatch {name}: module {crc:#010x} kernel {kernel[name]:#010x}")
            elif name not in vendor_syms:
                problems.append(f"symbol not exported by kernel: {name}")
        if problems:
            bad += 1
            print(f"FAIL {m}")
            for p in problems[:8]:
                print("     ", p)
            if len(problems) > 8:
                print(f"      ... and {len(problems) - 8} more")
    print(f"\n{len(mods)} modules, {checked} versioned symbol refs checked, {bad} modules with problems")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
