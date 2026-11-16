#!/usr/bin/env bash
# usage: repack.sh <stock_boot.img> <Image> <out.img>   (env MAGISKBOOT, default: magiskboot in PATH)
# Replaces only the kernel; verifies kernel == Image and ramdisk == stock ramdisk afterwards.
set -euo pipefail
mb=${MAGISKBOOT:-magiskboot}
stock=$(realpath "$1"); img=$(realpath "$2"); out=$(realpath -m "$3")
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
( cd "$t" && mkdir a b && cd a && "$mb" unpack "$stock" >/dev/null 2>&1 && cp ramdisk.cpio ../stock-ramdisk.cpio && cp "$img" kernel && "$mb" repack "$stock" "$out" >/dev/null 2>&1 )
( cd "$t/b" && "$mb" unpack "$out" >/dev/null 2>&1 && cmp -s kernel "$img" && cmp -s ramdisk.cpio ../stock-ramdisk.cpio ) || { echo "repack verification failed" >&2; exit 1; }
[ "$(stat -c %s "$out")" -le "$(stat -c %s "$stock")" ] || { echo "repacked image larger than stock" >&2; exit 1; }
echo "repacked -> $out"
