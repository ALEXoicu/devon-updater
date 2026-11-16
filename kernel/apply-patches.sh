#!/usr/bin/env bash
# usage: apply-patches.sh <kernel_src>
# Applies the SUSFS backport + hooks, adds SukiSU (builtin) and NoMount at pinned commits.
# Exits non-zero on any patch that does not apply cleanly (no fuzz).
# Optional env: SUKISU_MIRROR / NOMOUNT_MIRROR = local git dirs to clone from (faster).
set -euo pipefail
src=$(cd "$1" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=pins.env
. "$here/pins.env"
cd "$src"
git apply --check "$here/patches/kernel-tree.diff"
git apply "$here/patches/kernel-tree.diff"
cp -r "$here/patches/new-files/." "$src/"

rm -rf SukiSU-Ultra
git clone -q "${SUKISU_MIRROR:-$SUKISU_REPO}" SukiSU-Ultra
( cd SukiSU-Ultra
  git fetch -q "${SUKISU_MIRROR:-$SUKISU_REPO}" "$SUKISU_COMMIT" "$SUKISU_MAIN_COMMIT" 2>/dev/null || true
  git checkout -q -B builtin "$SUKISU_COMMIT"
  git branch -f main "$SUKISU_MAIN_COMMIT"
  [ ! -f .git/shallow ] || { echo "SukiSU clone is shallow" >&2; exit 1; }
  git apply "$here/patches/sukisu-local-fixes.diff"
  # version must not depend on GitHub API answers at build time
  sed -i -E 's/^(KSU_GITHUB_VER +:=).*/\1/; s/^(GITHUB_COMMITS +:=).*/\1/' kernel/Makefile
  grep -qE '^GITHUB_COMMITS +:=$' kernel/Makefile
  grep -qE '^KSU_GITHUB_VER +:=$' kernel/Makefile
  count=$(git rev-list --count main)
  ver=$((30000 + count - 4001))
  [ "$ver" = "$SUKISU_EXPECTED_VERSION" ] || { echo "KSU version would be $ver" >&2; exit 1; }
)
ln -sfn ../SukiSU-Ultra/kernel drivers/kernelsu

rm -rf NoMount
git clone -q "${NOMOUNT_MIRROR:-$NOMOUNT_REPO}" NoMount
git -C NoMount checkout -q "$NOMOUNT_COMMIT"
ln -sfn ../NoMount/kernel/src fs/nomount
echo "patches applied to $src"
