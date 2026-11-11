#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/boot.sh" service </dev/null >/dev/null 2>&1 &
