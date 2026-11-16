import importlib.util
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
K = os.path.dirname(HERE)
FIX = os.path.join(HERE, "fixtures")
STOCK_BOOT = os.path.expanduser("~/Downloads/boot-24.img")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


find_pending = load("find_pending", os.path.join(K, "ci", "find-pending.py"))
make_manifest = load("make_manifest", os.path.join(K, "ci", "make-manifest.py"))
kinfo = load("kinfo", os.path.join(K, "kinfo.py"))
API = json.load(open(os.path.join(FIX, "api.json")))


def fake_ko(path, vermagic, versions):
    """Minimal ELF64 with .modinfo, __versions, .shstrtab sections."""
    modinfo = f"vermagic={vermagic}\0".encode()
    vers = b"".join(struct.pack("<Q", crc) + name.encode().ljust(56, b"\0") for name, crc in versions)
    names = b"\0.modinfo\0__versions\0.shstrtab\0"
    blobs = [modinfo, vers, names]
    off = 64
    offs = []
    for b in blobs:
        offs.append(off)
        off += len(b)
    shoff = off
    hdr = bytearray(64)
    hdr[:5] = b"\x7fELF\x02"
    struct.pack_into("<Q", hdr, 0x28, shoff)
    struct.pack_into("<HHH", hdr, 0x3A, 64, 4, 3)
    sh = bytes(64)
    for nameoff, o, b in zip((1, 10, 21), offs, blobs):
        sh += struct.pack("<IIQQQQ", nameoff, 1, 0, 0, o, len(b)).ljust(64, b"\0")
    open(path, "wb").write(bytes(hdr) + b"".join(blobs) + sh)


class FindPending(unittest.TestCase):
    def test_newest_unreleased(self):
        self.assertEqual(find_pending.pending(API, 24, {"k-20261107"}), "20261114")

    def test_already_released(self):
        self.assertIsNone(find_pending.pending(API, 24, {"k-20261114"}))

    def test_skips_other_major(self):
        self.assertIsNone(find_pending.pending(API, 25, set()))


class Manifest(unittest.TestCase):
    def test_schema(self):
        pins = make_manifest.read_pins(os.path.join(K, "pins.env"))
        m = make_manifest.build_manifest(API, "20261114", "ab" * 32, "https://x/boot.img", "1da2d0861244", pins)
        self.assertEqual(m["android"], 17)
        self.assertEqual(m["version"], "24.0")
        self.assertEqual(m["ota_size"], 1142515976)
        self.assertEqual(m["ota_sha256"], "19e2ab81f7618e41c295ee6540b7a0b11fd4904061459a949dffd8f1340bfd13")
        self.assertTrue(m["ota_url"].endswith("lineage-24.0-20261114-nightly-devon-signed.zip"))
        self.assertEqual(m["sukisu"], 30000)
        self.assertEqual(set(m), {"device", "date", "version", "android", "ota_url", "ota_sha256", "ota_size",
                                  "kernel_hash", "boot_url", "boot_sha256", "sukisu", "susfs", "nomount"})


@unittest.skipUnless(os.path.exists(STOCK_BOOT), "stock boot image not present")
class Kinfo(unittest.TestCase):
    def test_hash(self):
        self.assertEqual(kinfo.commit_hash(kinfo.kernel_bytes(STOCK_BOOT)), "986c913a0783")

    def test_release(self):
        self.assertEqual(kinfo.release(kinfo.kernel_bytes(STOCK_BOOT)), "4.19.325-cip136-st20-perf-g986c913a0783")

    def test_config_has_modversions(self):
        self.assertIn(b"CONFIG_MODVERSIONS=y", kinfo.config(kinfo.kernel_bytes(STOCK_BOOT)))


class Modcheck(unittest.TestCase):
    REL = "4.19.325-cip136-st20-perf-g986c913a0783"

    def run_check(self, vermagic, crc):
        d = tempfile.mkdtemp()
        sym = os.path.join(d, "Module.symvers")
        open(sym, "w").write("0x12345678\tprintk\tvmlinux\tEXPORT_SYMBOL\n")
        os.mkdir(os.path.join(d, "ko"))
        fake_ko(os.path.join(d, "ko", "a.ko"), vermagic, [("printk", crc)])
        return subprocess.run([sys.executable, "-I", os.path.join(K, "modcheck.py"), sym, self.REL,
                               os.path.join(d, "ko")], capture_output=True, text=True)

    def test_passes_matching(self):
        r = self.run_check(self.REL + " SMP preempt mod_unload modversions aarch64", 0x12345678)
        self.assertEqual(r.returncode, 0, r.stdout)

    def test_detects_bad_vermagic(self):
        r = self.run_check("4.19.325-cip136-st20-perf-g000000000000 SMP", 0x12345678)
        self.assertEqual(r.returncode, 1)
        self.assertIn("vermagic", r.stdout)

    def test_detects_crc_mismatch(self):
        r = self.run_check(self.REL + " SMP", 0xDEADBEEF)
        self.assertEqual(r.returncode, 1)
        self.assertIn("CRC mismatch printk", r.stdout)


class ApplyPatches(unittest.TestCase):
    def test_fails_on_conflicting_tree(self):
        d = tempfile.mkdtemp()
        subprocess.run(["git", "init", "-q", d], check=True)
        os.makedirs(os.path.join(d, "fs"))
        open(os.path.join(d, "fs", "exec.c"), "w").write("int nothing_here;\n")
        r = subprocess.run([os.path.join(K, "apply-patches.sh"), d], capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
