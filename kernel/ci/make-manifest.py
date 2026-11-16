#!/usr/bin/env python3
"""Write manifest.json for a kernel release.

usage: make-manifest.py <api.json> <date YYYYMMDD> <boot.img> <boot_url> <kernel_hash> <out.json>
"""
import hashlib
import json
import sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def build_manifest(api, date, boot_sha, boot_url, kernel_hash, pins):
    b = next(b for b in api if b["date"].replace("-", "") == date)
    ota = next(f for f in b["files"] if f["filename"].endswith("-signed.zip"))
    major = int(b["version"].split(".")[0])
    return {
        "device": "devon",
        "date": date,
        "version": b["version"],
        "android": major - 7,
        "ota_url": ota["url"],
        "ota_sha256": ota["sha256"],
        "ota_size": int(ota["size"]),
        "kernel_hash": kernel_hash,
        "boot_url": boot_url,
        "boot_sha256": boot_sha,
        "sukisu": int(pins["SUKISU_EXPECTED_VERSION"]),
        "susfs": pins["SUSFS_VERSION"],
        "nomount": pins["NOMOUNT_VERSION"],
    }


def read_pins(path):
    out = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k] = v
    return out


def main():
    api_path, date, boot, boot_url, khash, out = sys.argv[1:7]
    import os
    pins = read_pins(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pins.env"))
    m = build_manifest(json.load(open(api_path)), date, sha256(boot), boot_url, khash, pins)
    json.dump(m, open(out, "w"), indent=1)
    print(json.dumps(m, indent=1))


if __name__ == "__main__":
    main()
