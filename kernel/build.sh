#!/usr/bin/env bash
# usage: build.sh <kernel_src> <stock_boot.img>
# Builds out/arch/arm64/boot/Image matching the stock nightly kernel's release string and config.
# Env: JOBS (default nproc), LLVM_BIN (dir with clang/ld.lld; default: from PATH)
set -euo pipefail
src=$(cd "$1" && pwd); boot=$(realpath "$2")
here=$(cd "$(dirname "$0")" && pwd)
. "$here/pins.env"
hash=$(python3 -I "$here/kinfo.py" hash "$boot")
head=$(git -C "$src" rev-parse HEAD)
[ "${head:0:12}" = "$hash" ] || { echo "source HEAD $head != nightly kernel $hash" >&2; exit 1; }
if [ -n "${LLVM_BIN:-}" ]; then export PATH="$LLVM_BIN:$PATH"; fi
cd "$src"
mkdir -p out
python3 -I "$here/kinfo.py" config "$boot" out/.config
sed -i '/^CONFIG_LOCALVERSION=/d;/CONFIG_LOCALVERSION_AUTO/d' out/.config
cat "$here/config.fragment" >> out/.config
echo "CONFIG_LOCALVERSION=\"-perf-g$hash\"" >> out/.config
M=(make ARCH=arm64 LLVM=1 LLVM_IAS=1 O=out LOCALVERSION= KBUILD_BUILD_USER=devon-updater KBUILD_BUILD_HOST=ci)
"${M[@]}" olddefconfig >/dev/null
while read -r line; do
  case "$line" in
    "# CONFIG_"*" is not set") opt=${line#\# }; opt=${opt%% *}; ! grep -q "^$opt=" out/.config || { echo "config: $opt got enabled" >&2; exit 1; } ;;
    CONFIG_*) grep -qx "$line" out/.config || { echo "config: missing $line" >&2; exit 1; } ;;
  esac
done < "$here/config.fragment"
rel=$("${M[@]}" -s kernelrelease)
[ "$rel" = "$KERNEL_RELEASE_PREFIX$hash" ] || { echo "kernelrelease $rel" >&2; exit 1; }
"${M[@]}" -j"${JOBS:-$(nproc)}" Image 2>&1 | tee out/build.log | grep -E 'version: |error:|Image  is ready' || true
grep -q "version: $SUKISU_EXPECTED_VERSION " out/build.log || { echo "KSU version not $SUKISU_EXPECTED_VERSION" >&2; exit 1; }
[ -f out/arch/arm64/boot/Image ] && [ -f out/Module.symvers ] || { echo "build failed" >&2; exit 1; }
echo "built $rel"
