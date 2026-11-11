#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/boot.sh" early </dev/null >/dev/null 2>&1 &
