#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
KERNEL=${1:-$ROOT/linux-6.8-main/arch/x86/boot/bzImage}
OUT=${2:-$ROOT/docs/validation/kernel-affinity-2026-10-30/current}
mkdir -p "$OUT"
OUT=$(realpath "$OUT")
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD"/{bin,proc,sys,dev}
gcc -O2 -static -Wall -Wextra -Werror "$ROOT/linux-6.8-main/boot_test_scripts/yat_affinity_test.c" -o "$BUILD/bin/test"
cp /bin/busybox "$BUILD/bin/busybox"
cat > "$BUILD/init" <<'INIT'
#!/bin/busybox sh
/bin/busybox mount -t proc proc /proc
/bin/busybox mount -t sysfs sysfs /sys
/bin/busybox mount -t devtmpfs devtmpfs /dev
/bin/busybox mount -t debugfs debugfs /sys/kernel/debug
/bin/test
rc=$?
echo "YAT-AFFINITY-EXIT=$rc"
/bin/busybox poweroff -f
INIT
chmod +x "$BUILD/init"
(cd "$BUILD" && find . | cpio -o -H newc 2>/dev/null | gzip > "$OUT/initramfs.gz")
sha256sum "$KERNEL" > "$OUT/kernel.sha256"
set +e
timeout 120 qemu-system-x86_64 -accel tcg -m 1024 -smp 4 -kernel "$KERNEL" -initrd "$OUT/initramfs.gz" -append 'console=ttyS0 rdinit=/init panic=1' -nographic -no-reboot > "$OUT/serial.log" 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ]; then echo "QEMU failed: $rc"; exit 1; fi
if grep -Eq 'BUG:|Kernel panic|Oops:|KASAN:|UBSAN:|possible circular locking|general protection fault' "$OUT/serial.log"; then echo 'Kernel splat'; exit 1; fi
grep -E 'PHASE|YAT-AFFINITY' "$OUT/serial.log"
grep -q 'YAT-AFFINITY-EXIT=0' "$OUT/serial.log"