#!/system/bin/sh
# Single-process, single-port IPv4/IPv6 TPROXY controller.
set -u
umask 077
fail(){ echo "error: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail 'run as root'
script=$0;case "$script" in */*) ;; *) [ -f "$script" ] || script=$(command -v "$script");; esac
BASE=$(CDPATH= cd -P "$(dirname "$script")" && pwd -P) || fail 'cannot resolve script'
BIN=${APPCAPTURE_BIN:-"$BASE/appcapture"};DIR=${APPCAPTURE_DATA_DIR:-"$BASE/data"};RUN=$DIR/run-single;LOCK=$DIR/.capture-lock
I4=${APPCAPTURE_IPTABLES:-$(command -v iptables 2>/dev/null || :)};I6=${APPCAPTURE_IP6TABLES:-$(command -v ip6tables 2>/dev/null || :)}
R4=${APPCAPTURE_RESTORE4:-$(command -v iptables-restore 2>/dev/null || :)};R6=${APPCAPTURE_RESTORE6:-$(command -v ip6tables-restore 2>/dev/null || :)};IP=${APPCAPTURE_IP:-$(command -v ip 2>/dev/null || :)}
OUT=TPC_SINGLE_OUT;PRE=TPC_SINGLE_PRE
MCP=$DIR/mcp
ROUTE=${TPCAPTURE_ROUTE_MARK:-0x20000000};BYPASS=${TPCAPTURE_BYPASS_MARK:-0x80000000};TABLE_SET=${TPCAPTURE_ROUTE_TABLE+x};PREF_SET=${TPCAPTURE_ROUTE_PREF+x};TABLE=${TPCAPTURE_ROUTE_TABLE:-38832};PREF=${TPCAPTURE_ROUTE_PREF:-9000};PORT=${TPCAPTURE_ENTRY_PORT:-19001}
CA_MODE=${TPCAPTURE_CA_MODE:-emudhra};UDP_MODE=${TPCAPTURE_UDP:-0}
case "$CA_MODE" in emudhra|self);;*)fail 'invalid CA mode';;esac
case "$UDP_MODE" in 0|1);;*)fail 'invalid UDP mode';;esac
case "$TABLE:$PREF:$PORT" in *[!0-9:]*|::*|*:)fail 'invalid routing parameters';;esac
case "$ROUTE:$BYPASS" in *[!0-9a-fA-FxX:]*)fail 'invalid marks';;esac
mkdir -p -m 700 "$RUN" || fail 'cannot create state directory'
tool_available(){ [ -n "$1" ] || return 1;case "$1" in */*) [ -x "$1" ];; *) command -v "$1" >/dev/null 2>&1;; esac; }
require_tool(){ label=$1;tool=$2;tool_available "$tool" || fail "找不到或无法执行 $label（PATH=$PATH）"; }
need_tools(){ require_tool iptables "$I4";require_tool ip6tables "$I6";require_tool ip "$IP"; }
need_cleanup_tools(){ require_tool iptables "$I4";require_tool ip6tables "$I6";require_tool ip "$IP"; }
choose_resources(){
 rules4=$("$IP" -4 rule show)||return 1;rules6=$("$IP" -6 rule show)||return 1;n=0
 while printf '%s\n%s\n' "$rules4" "$rules6" | grep -qE "^[[:space:]]*$PREF:";do [ -z "$PREF_SET" ]||{ echo '策略路由优先级已被占用' >&2;return 1;};PREF=$((PREF+1));n=$((n+1));[ "$n" -lt 100 ]||{ echo '没有可用策略路由优先级' >&2;return 1;};done
 n=0
 while :;do routes4=$("$IP" -4 route show table "$TABLE" 2>/dev/null||:);routes6=$("$IP" -6 route show table "$TABLE" 2>/dev/null||:);[ -z "$routes4$routes6" ]&&break;[ -z "$TABLE_SET" ]||{ echo '策略路由表已被占用' >&2;return 1;};TABLE=$((TABLE+1));n=$((n+1));[ "$n" -lt 100 ]||{ echo '没有可用策略路由表' >&2;return 1;};done
}
preflight(){
 need_tools
 if "$I4" -t mangle -n -L TPC_QUIC_OUT >/dev/null 2>&1;then fail '请先停止旧独立 QUIC 后端';fi
 choose_resources||fail '无法选择策略路由资源'
 echo 'TPROXY 控制工具和路由资源可用（不代表内核和流量路径已验证）'
}
lock(){
 if ! mkdir -m 700 "$LOCK" 2>/dev/null;then
  [ -f "$LOCK/owner.pid" ] || fail "操作锁未知或仍在使用，请在设置中修复残留锁: $LOCK"
  IFS= read -r owner < "$LOCK/owner.pid" || owner='';case "$owner" in ''|*[!0-9]*)fail 'invalid operation lock';;esac
  state=$(awk '{print $3}' "/proc/$owner/stat" 2>/dev/null || :)
  if kill -0 "$owner" 2>/dev/null && [ "$state" != Z ] && [ "$state" != X ];then fail "已有启停任务执行中 (PID $owner)";fi
  rm -f "$LOCK/owner.pid" "$LOCK/targets" "$LOCK/pids" "$LOCK/packages" "$LOCK/packages.tmp" "$LOCK/newtargets" "$LOCK/oldtargets" "$LOCK/rules4" "$LOCK/rules6"
  rmdir "$LOCK" 2>/dev/null || fail 'stale lock contains unknown files';mkdir -m 700 "$LOCK" || fail 'operation lock busy'
 fi
 printf '%s\n' "$$" > "$LOCK/owner.pid"
 trap 'rm -f "$LOCK/owner.pid" "$LOCK/targets" "$LOCK/pids" "$LOCK/packages" "$LOCK/packages.tmp" "$LOCK/newtargets" "$LOCK/oldtargets" "$LOCK/rules4" "$LOCK/rules6"; rmdir "$LOCK" 2>/dev/null || :' EXIT
 trap 'exit 1' HUP INT TERM
}
valid_daemon(){
 [ -f "$RUN/pid" ] || return 1;IFS= read -r daemonPid < "$RUN/pid" || return 1;case "$daemonPid" in ''|*[!0-9]*)return 1;;esac
 [ -r "/proc/$daemonPid/cmdline" ] || return 1;line=$(tr '\000' ' ' < "/proc/$daemonPid/cmdline");case "$line" in "$BIN serve-single "*)return 0;;*)return 1;;esac
}
reset_after_reboot(){
 [ -f "$RUN/config" ] && [ -f "$RUN/boot-id" ] || return 0
 IFS= read -r oldBoot < "$RUN/boot-id" || return 0
 IFS= read -r nowBoot < /proc/sys/kernel/random/boot_id || return 0
 [ -n "$oldBoot" ] && [ -n "$nowBoot" ] && [ "$oldBoot" != "$nowBoot" ] || return 0
 if valid_daemon;then return 0;fi
 for tool in "$I4" "$I6";do if "$tool" -t mangle -n -L "$OUT" >/dev/null 2>&1 || "$tool" -t mangle -n -L "$PRE" >/dev/null 2>&1;then echo '重启后仍发现 TPCapture 规则，请先清理' >&2;return 1;fi;done
 rm -f "$RUN/pid" "$RUN/config" "$RUN/targets" "$RUN/targets.new" "$RUN/route4" "$RUN/route6" "$RUN/rule4" "$RUN/rule6" "$RUN/boot-id"
}
resolve(){
 target=$1;case "$target" in
  uidpkg:*) pair=${target#uidpkg:};uid=${pair%%:*};target=${pair#*:};case "$uid" in ''|*[!0-9]*)return 1;;esac;case "$target" in ''|*[!a-zA-Z0-9._]*)return 1;;esac;;
  uid:*) uid=${target#uid:};case "$uid" in ''|*[!0-9]*)return 1;;esac;;
  *)case "$target" in ''|*[!a-zA-Z0-9._]*)return 1;;esac
    if [ ! -f "$LOCK/packages" ];then cmd package list packages -U --user 0 > "$LOCK/packages.tmp" || return 1;mv "$LOCK/packages.tmp" "$LOCK/packages" || return 1;fi
    uid=$(awk -v p="package:$target" '$1 == p {sub(/^uid:/,"",$2);print $2}' "$LOCK/packages");case "$uid" in ''|*[!0-9]*)return 1;;esac;;
 esac
 [ "$uid" -le 2147483647 ] 2>/dev/null
}
prepare_targets(){
 : > "$LOCK/newtargets";failed=0
 for item in "$@";do if resolve "$item";then if ! grep -q "^$uid " "$LOCK/newtargets";then printf '%s %s\n' "$uid" "$target" >> "$LOCK/newtargets";fi;else echo "无法解析目标: $item" >&2;failed=1;fi;done
 [ "$failed" = 0 ] && [ -s "$LOCK/newtargets" ]
}
load_config(){
 [ -f "$RUN/config" ] || return 1
 ROUTE=$(sed -n '1p' "$RUN/config");BYPASS=$(sed -n '2p' "$RUN/config");TABLE=$(sed -n '3p' "$RUN/config");PREF=$(sed -n '4p' "$RUN/config");PORT=$(sed -n '5p' "$RUN/config");ACTIVE_CA=$(sed -n '6p' "$RUN/config");ACTIVE_UDP=$(sed -n '7p' "$RUN/config")
}
make_output_rules(){
 family=$1;targets=$2;udp=$3;file=$4
 { echo '*mangle';echo "-F $OUT";echo "-A $OUT -m mark --mark $BYPASS/$BYPASS -j RETURN"
   if [ "$family" = 4 ];then echo "-A $OUT -d 127.0.0.0/8 -j RETURN";echo "-A $OUT -d 224.0.0.0/4 -j RETURN";else echo "-A $OUT -d ::1/128 -j RETURN";echo "-A $OUT -d ff00::/8 -j RETURN";fi
   while IFS=' ' read -r id name;do [ -n "$id" ] || continue
     echo "-A $OUT -p tcp --dport 80 -m owner --uid-owner $id -j MARK --set-xmark $ROUTE/$ROUTE"
     echo "-A $OUT -p tcp --dport 443 -m owner --uid-owner $id -j MARK --set-xmark $ROUTE/$ROUTE"
     if [ "$udp" = 1 ];then echo "-A $OUT -p udp --dport 443 -m owner --uid-owner $id -j MARK --set-xmark $ROUTE/$ROUTE";fi
   done < "$targets";echo COMMIT; } > "$file"
}
output_hooks_absent(){
 rules4=$("$I4" -t mangle -S OUTPUT 2>/dev/null) || return 1
 rules6=$("$I6" -t mangle -S OUTPUT 2>/dev/null) || return 1
 ! printf '%s\n%s\n' "$rules4" "$rules6" | grep -qE "(^|[[:space:]])-j[[:space:]]+$OUT([[:space:]]|$)"
}
apply_direct(){
 tool=$1;family=$2;directTargets=$3;directUDP=$4
 "$tool" -t mangle -F "$OUT" || return 1
 "$tool" -t mangle -A "$OUT" -m mark --mark "$BYPASS/$BYPASS" -j RETURN || return 1
 if [ "$family" = 4 ];then
  "$tool" -t mangle -A "$OUT" -d 127.0.0.0/8 -j RETURN || return 1
  "$tool" -t mangle -A "$OUT" -d 224.0.0.0/4 -j RETURN || return 1
 else
  "$tool" -t mangle -A "$OUT" -d ::1/128 -j RETURN || return 1
  "$tool" -t mangle -A "$OUT" -d ff00::/8 -j RETURN || return 1
 fi
 while IFS=' ' read -r id name;do [ -n "$id" ] || continue
  "$tool" -t mangle -A "$OUT" -p tcp --dport 80 -m owner --uid-owner "$id" -j MARK --set-xmark "$ROUTE/$ROUTE" || return 1
  "$tool" -t mangle -A "$OUT" -p tcp --dport 443 -m owner --uid-owner "$id" -j MARK --set-xmark "$ROUTE/$ROUTE" || return 1
  if [ "$directUDP" = 1 ];then "$tool" -t mangle -A "$OUT" -p udp --dport 443 -m owner --uid-owner "$id" -j MARK --set-xmark "$ROUTE/$ROUTE" || return 1;fi
 done < "$directTargets"
}
apply_targets(){
 new=$1;udp=$2;old=$3;oldUDP=$4
 if tool_available "$R4" && tool_available "$R6";then
  make_output_rules 4 "$new" "$udp" "$LOCK/rules4";make_output_rules 6 "$new" "$udp" "$LOCK/rules6"
  if "$R4" --noflush < "$LOCK/rules4";then
   if "$R6" --noflush < "$LOCK/rules6";then return 0;fi
   if ! output_hooks_absent;then
    if [ -f "$old" ];then make_output_rules 4 "$old" "$oldUDP" "$LOCK/rules4";"$R4" --noflush < "$LOCK/rules4" || echo 'IPv4 回滚失败，请停止后端并清理规则' >&2;fi
    return 1
   fi
  elif ! output_hooks_absent;then return 1;fi
 fi
 if ! output_hooks_absent;then echo '缺少可用的 iptables-restore，无法原子重载；请停止后重新启动' >&2;return 1;fi
 echo 'restore 工具不可用，启动时逐条配置规则' >&2
 apply_direct "$I4" 4 "$new" "$udp" && apply_direct "$I6" 6 "$new" "$udp"
}
cleanup_network(){
 ok=0
 for tool in "$I4" "$I6";do
  while "$tool" -t mangle -C OUTPUT -j "$OUT" 2>/dev/null;do "$tool" -t mangle -D OUTPUT -j "$OUT" || { ok=1;break;};done
  while "$tool" -t mangle -C PREROUTING -i lo -j "$PRE" 2>/dev/null;do "$tool" -t mangle -D PREROUTING -i lo -j "$PRE" || { ok=1;break;};done
  for chain in "$OUT" "$PRE";do if "$tool" -t mangle -n -L "$chain" >/dev/null 2>&1;then "$tool" -t mangle -F "$chain" && "$tool" -t mangle -X "$chain" || ok=1;fi;done
 done
 [ "$ok" = 0 ] || return 1
 if [ -f "$RUN/rule4" ];then "$IP" -4 rule del pref "$PREF" fwmark "$ROUTE/$ROUTE" table "$TABLE" && rm -f "$RUN/rule4" || ok=1;fi
 if [ -f "$RUN/rule6" ];then "$IP" -6 rule del pref "$PREF" fwmark "$ROUTE/$ROUTE" table "$TABLE" && rm -f "$RUN/rule6" || ok=1;fi
 if [ -f "$RUN/route4" ];then "$IP" -4 route del local 0.0.0.0/0 dev lo table "$TABLE" && rm -f "$RUN/route4" || ok=1;fi
 if [ -f "$RUN/route6" ];then "$IP" -6 route del local ::/0 dev lo table "$TABLE" && rm -f "$RUN/route6" || ok=1;fi
 [ "$ok" = 0 ]
}
stop_daemon(){
 [ ! -f "$RUN/config" ] || load_config || return 1
 cleanup_network || { echo '规则未完全清理，保留守护进程' >&2;return 1;}
 if valid_daemon;then kill "$daemonPid" 2>/dev/null || :;n=0;while kill -0 "$daemonPid" 2>/dev/null;do st=$(awk '{print $3}' "/proc/$daemonPid/stat" 2>/dev/null || :);[ "$st" = Z ]&&break;[ "$n" -lt 100 ]||{ echo '守护进程未退出' >&2;return 1;};sleep 0.1;n=$((n+1));done;fi
 rm -f "$RUN/pid" "$RUN/config" "$RUN/targets" "$RUN/targets.new" "$RUN/boot-id";echo '单进程后端已停止'
}
activate(){
 desired=$1;[ -x "$BIN" ] || { echo 'backend missing' >&2;return 1;};[ "$PORT" -ge 1024 ]&&[ "$PORT" -le 65535 ] || return 1
 if valid_daemon;then echo '后端已运行，请使用重载' >&2;return 1;fi
 if [ -f "$RUN/config" ]||[ -f "$RUN/rule4" ]||[ -f "$RUN/rule6" ]||[ -f "$RUN/route4" ]||[ -f "$RUN/route6" ];then echo '发现残留状态，请先清理' >&2;return 1;fi
 for tool in "$I4" "$I6";do if "$tool" -t mangle -n -L "$OUT" >/dev/null 2>&1||"$tool" -t mangle -n -L "$PRE" >/dev/null 2>&1;then echo '发现残留规则链，请先清理' >&2;return 1;fi;done
 if "$I4" -t nat -C OUTPUT -j TPCAPTURE_V2 2>/dev/null;then echo '请先停止旧版 TCP 后端' >&2;return 1;fi
 if "$I4" -t mangle -n -L TPC_QUIC_OUT >/dev/null 2>&1;then echo '请先停止独立 QUIC 后端' >&2;return 1;fi
 choose_resources || return 1
 printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n' "$ROUTE" "$BYPASS" "$TABLE" "$PREF" "$PORT" "$CA_MODE" "$UDP_MODE" > "$RUN/config"
 if IFS= read -r bootID < /proc/sys/kernel/random/boot_id;then printf '%s\n' "$bootID" > "$RUN/boot-id";fi
 : > "$RUN/daemon.log";"$BIN" serve-single -dir "$DIR" -port "$PORT" -ca-mode "$CA_MODE" -mark "$BYPASS" -route-mark "$ROUTE" >> "$RUN/daemon.log" 2>&1 </dev/null & daemonPid=$!;echo "$daemonPid" > "$RUN/pid"
 ready=0;n=0;while [ "$n" -lt 100 ];do if ! kill -0 "$daemonPid" 2>/dev/null;then break;fi;if grep -q 'READY mode=tproxy-single' "$RUN/daemon.log";then ready=1;break;fi;sleep 0.1;n=$((n+1));done
 if [ "$ready" != 1 ];then kill "$daemonPid" 2>/dev/null||:;cat "$RUN/daemon.log" >&2;rm -f "$RUN/pid" "$RUN/config" "$RUN/boot-id";return 1;fi
 good=1
 for tool in "$I4" "$I6";do "$tool" -t mangle -N "$OUT" && "$tool" -t mangle -N "$PRE" || { good=0;break;};done
 if [ "$good" = 1 ];then
  for tuple in "4:$I4:127.0.0.1" "6:$I6:::1";do family=${tuple%%:*};rest=${tuple#*:};tool=${rest%%:*};address=${rest#*:}
   for dport in 80 443;do "$tool" -t mangle -A "$PRE" -p tcp --dport "$dport" -m mark --mark "$ROUTE/$ROUTE" -j TPROXY --on-ip "$address" --on-port "$PORT" --tproxy-mark "$ROUTE/$ROUTE" || { good=0;break;};done
   [ "$good" = 1 ]||break
   "$tool" -t mangle -A "$PRE" -p udp --dport 443 -m mark --mark "$ROUTE/$ROUTE" -j TPROXY --on-ip "$address" --on-port "$PORT" --tproxy-mark "$ROUTE/$ROUTE" || { good=0;break;}
  done
 fi
 if [ "$good" = 1 ];then apply_targets "$desired" "$UDP_MODE" /dev/null 0 || good=0;fi
 if [ "$good" = 1 ];then "$IP" -4 route add local 0.0.0.0/0 dev lo table "$TABLE" && : > "$RUN/route4" || good=0;fi
 if [ "$good" = 1 ];then "$IP" -6 route add local ::/0 dev lo table "$TABLE" && : > "$RUN/route6" || good=0;fi
 if [ "$good" = 1 ];then "$IP" -4 rule add pref "$PREF" fwmark "$ROUTE/$ROUTE" table "$TABLE" && : > "$RUN/rule4" || good=0;fi
 if [ "$good" = 1 ];then "$IP" -6 rule add pref "$PREF" fwmark "$ROUTE/$ROUTE" table "$TABLE" && : > "$RUN/rule6" || good=0;fi
 if [ "$good" = 1 ];then for tool in "$I4" "$I6";do "$tool" -t mangle -I PREROUTING 1 -i lo -j "$PRE" && "$tool" -t mangle -I OUTPUT 1 -j "$OUT" || { good=0;break;};done;fi
 if [ "$good" != 1 ];then if cleanup_network;then kill "$daemonPid" 2>/dev/null||:;rm -f "$RUN/pid" "$RUN/config";else echo '规则回滚不完整，保留守护进程，请使用清理' >&2;fi;return 1;fi
 if ! cp "$desired" "$RUN/targets.new" || ! mv -f "$RUN/targets.new" "$RUN/targets";then
  if cleanup_network;then kill "$daemonPid" 2>/dev/null||:;rm -f "$RUN/pid" "$RUN/config" "$RUN/targets.new" "$RUN/boot-id";else echo '保存状态失败且规则清理不完整，保留守护进程' >&2;fi
  return 1
 fi
 echo '单入口守护进程已启动'
}
valid_mcp(){
 [ -f "$MCP/server.pid" ] || return 1;IFS= read -r mcpPid < "$MCP/server.pid" || return 1;case "$mcpPid" in ''|*[!0-9]*)return 1;;esac
 [ -r "/proc/$mcpPid/cmdline" ] || return 1;line=$(tr '\000' ' ' < "/proc/$mcpPid/cmdline");case "$line" in "$BIN mcp-serve "*)return 0;;*)return 1;;esac
}
mcp_lock(){
 mkdir -p -m 700 "$MCP"||fail '无法创建 MCP 目录'
 if ! mkdir -m 700 "$MCP/.lock" 2>/dev/null;then
  [ -f "$MCP/.lock/pid" ]||fail 'MCP 操作锁状态未知'
  IFS= read -r owner < "$MCP/.lock/pid"||owner='';case "$owner" in ''|*[!0-9]*)fail 'MCP 操作锁无效';;esac
  state=$(awk '{print $3}' "/proc/$owner/stat" 2>/dev/null||:);if kill -0 "$owner" 2>/dev/null&&[ "$state" != Z ]&&[ "$state" != X ];then fail 'MCP 操作正在进行';fi
  rm -f "$MCP/.lock/pid";rmdir "$MCP/.lock" 2>/dev/null||fail 'MCP 残留锁无法清理';mkdir -m 700 "$MCP/.lock"||fail 'MCP 操作锁正在使用'
 fi
 printf '%s\n' "$$" > "$MCP/.lock/pid";trap 'rm -f "$MCP/.lock/pid";rmdir "$MCP/.lock" 2>/dev/null||:' EXIT;trap 'exit 1' HUP INT TERM
}
case "${1:-}" in
 mcp-start)
  [ -x "$BIN" ]||fail 'backend missing';port=${2:-18765};host=${3:-127.0.0.1};case "$host" in 127.0.0.1|0.0.0.0) ;; *) fail 'invalid MCP host';; esac;case "$port" in ''|*[!0-9]*)fail 'invalid MCP port';;esac;[ "$port" -ge 1024 ]&&[ "$port" -le 65535 ]||fail 'invalid MCP port';mcp_lock
  if valid_mcp;then saved='';[ ! -f "$MCP/port" ]||IFS= read -r saved < "$MCP/port"||:;[ "$saved" = "$port" ]||fail 'MCP 已在其他端口运行，请先停止';echo "MCP_RUNNING port=$port host=$host pid=$mcpPid";exit 0;fi
  rm -f "$MCP/server.pid";: > "$MCP/server.log";"$BIN" mcp-serve -dir "$DIR" -listen "$host:$port" >> "$MCP/server.log" 2>&1 </dev/null & mcpPid=$!;printf '%s\n' "$mcpPid" > "$MCP/server.pid";printf '%s\n' "$port" > "$MCP/port";ready=0;n=0
  while [ "$n" -lt 100 ];do if ! kill -0 "$mcpPid" 2>/dev/null;then break;fi;if grep -q 'MCP_READY listen=' "$MCP/server.log";then ready=1;break;fi;sleep 0.1;n=$((n+1));done
  if [ "$ready" != 1 ];then kill "$mcpPid" 2>/dev/null||:;cat "$MCP/server.log" >&2;rm -f "$MCP/server.pid" "$MCP/port";fail 'MCP 启动失败';fi;echo "MCP_RUNNING port=$port host=$host pid=$mcpPid";;
 mcp-stop)
  mcp_lock;if valid_mcp;then kill "$mcpPid" 2>/dev/null||:;n=0;while kill -0 "$mcpPid" 2>/dev/null;do state=$(awk '{print $3}' "/proc/$mcpPid/stat" 2>/dev/null||:);[ "$state" = Z ]&&break;[ "$n" -lt 100 ]||fail 'MCP 进程未退出';sleep 0.1;n=$((n+1));done;fi;rm -f "$MCP/server.pid" "$MCP/port";echo 'MCP_STOPPED';;
 mcp-status)
  if valid_mcp;then port='';[ ! -f "$MCP/port" ]||IFS= read -r port < "$MCP/port"||:;echo "MCP_RUNNING port=$port pid=$mcpPid";else echo 'MCP_STOPPED';fi;;
 mcp-token) [ -x "$BIN" ]||fail 'backend missing';"$BIN" mcp-token -dir "$DIR";;
 rewrite-get|rewrite-set|rewrite-check) [ -x "$BIN" ]||fail 'backend missing';"$BIN" "$@" -dir "$DIR";;
 start|reload|new-session-start)
  need_tools;action=$1;shift;[ "$#" -gt 0 ]||fail 'choose targets';lock;reset_after_reboot||fail '无法清理上次启动的状态';prepare_targets "$@"||fail '目标解析失败'
  cp "$LOCK/newtargets" "$LOCK/targets"
  if [ "$action" = reload ] && valid_daemon;then
   load_config||fail '运行状态缺失';cp "$RUN/targets" "$LOCK/oldtargets" || fail '目标状态缺失';oldUDP=$ACTIVE_UDP
   if [ "$UDP_MODE" = 1 ] && "$I4" -t mangle -n -L TPC_QUIC_OUT >/dev/null 2>&1;then fail '请先停止旧独立 QUIC 后端';fi
   if [ "$ACTIVE_CA" = "$CA_MODE" ];then
    apply_targets "$LOCK/targets" "$UDP_MODE" "$LOCK/oldtargets" "$oldUDP" || fail '规则更新失败，已尝试回滚';if ! cp "$LOCK/targets" "$RUN/targets.new" || ! mv -f "$RUN/targets.new" "$RUN/targets";then apply_targets "$LOCK/oldtargets" "$oldUDP" /dev/null 0 || echo '规则回滚失败' >&2;rm -f "$RUN/targets.new";fail '无法保存目标';fi;printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n' "$ROUTE" "$BYPASS" "$TABLE" "$PREF" "$PORT" "$ACTIVE_CA" "$UDP_MODE" > "$RUN/config";echo '目标已重载，守护进程未重启';exit 0
   fi
  fi
  hadOld=0;oldCA='';oldUDP=0
  if [ "$action" != start ] && [ -f "$RUN/config" ];then
    load_config||fail '运行状态缺失';oldCA=$ACTIVE_CA;oldUDP=$ACTIVE_UDP
    if [ -f "$RUN/targets" ];then cp "$RUN/targets" "$LOCK/oldtargets" || fail '无法保存旧目标';hadOld=1;fi
    stop_daemon||fail '无法停止旧守护进程'
  fi
  if [ "$action" = new-session-start ];then
    if ! "$BIN" archive-db -dir "$DIR";then
      if [ "$hadOld" = 1 ];then wantedCA=$CA_MODE;wantedUDP=$UDP_MODE;CA_MODE=$oldCA;UDP_MODE=$oldUDP;activate "$LOCK/oldtargets" || echo '旧目标恢复失败' >&2;CA_MODE=$wantedCA;UDP_MODE=$wantedUDP;fi
      fail '数据库归档失败'
    fi
  fi
  activate "$LOCK/targets" || fail '单入口后端启动失败';;
 stop|cleanup) need_cleanup_tools;lock;reset_after_reboot||fail '无法清理上次启动的状态';stop_daemon || fail '停止未完成';if IFS= read -r bootID < /proc/sys/kernel/random/boot_id;then printf '%s\n' "$bootID" > "$DIR/.autostart-stopped-boot";fi;;
 preflight) preflight;;
 ca-info) [ -x "$BIN" ]||fail 'backend missing';"$BIN" ca-info -dir "$DIR/ca";;
 version) [ -x "$BIN" ]||fail 'backend missing';"$BIN" version;;
 status)
  if valid_daemon;then count=$(wc -l < "$RUN/targets");echo "running count=$count";mode=emudhra;udp=0;if load_config;then mode=$ACTIVE_CA;udp=$ACTIVE_UDP;fi;while IFS=' ' read -r id name;do [ -n "$id" ]||continue;echo "UID=$id PID=$daemonPid target=$name";echo "CERT_UID=$id MODE=$mode";done < "$RUN/targets";echo "UDP_ENABLED=$udp";else echo 'proxy not running';fi
  "$I4" -t mangle -n -L "$OUT" -v 2>/dev/null || echo 'capture chain absent';;
 databases) [ -x "$BIN" ]||fail 'backend missing';"$BIN" databases -dir "$DIR";;
 list-json) [ -x "$BIN" ]||fail 'backend missing';shift;"$BIN" list-json -dir "$DIR" "$@";;
 list) [ -x "$BIN" ]||fail 'backend missing';shift;"$BIN" list -dir "$DIR" "$@";;
 show) [ -x "$BIN" ]||fail 'backend missing';"$BIN" show -dir "$DIR" -id "${2:-0}";;
 events) [ -x "$BIN" ]||fail 'backend missing';"$BIN" events -dir "$DIR";;
 doctor) [ -x "$BIN" ]||fail 'backend missing';shift;"$BIN" doctor "$@";;
 *)echo "usage: $0 start|reload|new-session-start TARGET... | stop | cleanup | status";exit 2;;
esac
