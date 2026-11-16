#!/usr/bin/env bash
# usage: extract-vendor-modules.sh <ota.zip> <outdir>   -> <outdir>/*.ko (vendor /lib/modules)
set -euo pipefail
ota=$1; out=$2
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
python3 -I "$here/payload_extract.py" "$ota" vendor "$tmp/vendor.img"
mkdir -p "$out"
if [ "$(head -c 1028 "$tmp/vendor.img" | tail -c 4 | od -An -tx4 | tr -d ' ')" = "e0f5e1e2" ]; then
  fsck.erofs --extract="$tmp/x" "$tmp/vendor.img" >/dev/null
  cp "$tmp"/x/lib/modules/*.ko "$out"/
else
  # ext4; debugfs complains about chown when not root, the data is still written
  debugfs -R "rdump /lib/modules $tmp" "$tmp/vendor.img" >/dev/null 2>&1 || true
  cp "$tmp"/modules/*.ko "$out"/
fi
n=$(ls "$out"/*.ko | wc -l)
[ "$n" -gt 0 ] || { echo "no vendor modules extracted" >&2; exit 1; }
echo "$n vendor modules -> $out"
