#!/usr/bin/env bash
# Android 蓝牙 HCI 报文采集一键脚本（无需 root；失败路径自动降级 bugreport）
#
# 用法（在连接了手机的机器上执行）：
#   ./collect_android_hci.sh start            # 开启 snoop 并重启蓝牙
#   ./collect_android_hci.sh pull 20261019    # 采集结束后导出并自动解帧
#
# 说明：部分 ROM 对 setprop/svc 有限制，脚本只告警不中断；
#       若 start 后 getprop 仍非 full，请在开发者选项里手动打开“蓝牙 HCI 日志”。
set -uo pipefail

CMD="${1:-}"
OUT_ROOT="${2:-}"
ADB="${ADB:-adb}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

die() { echo "ERROR: $*" >&2; exit 1; }
need_device() {
  "$ADB" get-state 2>/dev/null | grep -q device || die "没有可用的 adb 设备（adb devices 检查 USB 调试）"
}

do_start() {
  need_device
  echo "== 1/3 尝试写入 snoop 开关（部分 ROM 需手动开关，失败不影响后续）"
  "$ADB" shell settings put global bluetooth_hci_log 1 2>/dev/null || true
  "$ADB" shell setprop persist.bluetooth.btsnoopenable true 2>/dev/null || true
  "$ADB" shell setprop persist.bluetooth.btsnooplogmode full 2>/dev/null || true

  echo "== 2/3 重启蓝牙使日志配置生效"
  "$ADB" shell svc bluetooth disable 2>/dev/null || true
  sleep 2
  "$ADB" shell svc bluetooth enable 2>/dev/null || true
  sleep 3

  mode="$("$ADB" shell getprop persist.bluetooth.btsnooplogmode 2>/dev/null | tr -d '\r')"
  enabled="$("$ADB" shell getprop persist.bluetooth.btsnoopenable 2>/dev/null | tr -d '\r')"
  echo "   btsnooplogmode=$mode  btsnoopenable=$enabled"
  if [ "$mode" != "full" ] && [ "$enabled" != "true" ]; then
    echo "!! 未能通过 adb 开启。请手动：设置 → 开发者选项 → 蓝牙 HCI 日志 → 关开蓝牙"
  else
    echo "   snoop 已开启"
  fi
  echo
  echo "== 3/3 现在按 session.md 模板操作官方 APP；结束后执行："
  echo "   $0 pull $(date +%Y%m%d)"
}

do_pull() {
  need_device
  [ -n "$OUT_ROOT" ] || die "用法: $0 pull YYYYMMDD"
  OUT_DIR="$SCRIPT_DIR/../../captures/$OUT_ROOT"
  mkdir -p "$OUT_DIR"
  found=""

  echo "== 1/2 尝试直接导出 btsnoop"
  for p in \
    /data/misc/bluetooth/logs/btsnoop_hci.log \
    /data/misc/bluedroid/btsnoop_hci.log \
    /sdcard/Android/data/com.android.bluetooth/files/btsnoop_hci.log \
    /sdcard/btsnoop_hci.log
  do
    if "$ADB" shell ls "$p" >/dev/null 2>&1; then
      if "$ADB" pull "$p" "$OUT_DIR/btsnoop_hci.log" >/dev/null 2>&1; then
        found="$OUT_DIR/btsnoop_hci.log"; echo "   已导出: $p"; break
      fi
    fi
  done

  if [ -z "$found" ]; then
    echo "   直接路径不可读，降级为 bugreport（可能需 1-3 分钟）"
    "$ADB" bugreport "$OUT_DIR/bugreport.zip" >/dev/null || die "bugreport 失败"
    inner="$(unzip -Z1 "$OUT_DIR/bugreport.zip" 2>/dev/null | grep -m1 'btsnoop_hci\.log$' || true)"
    [ -n "$inner" ] || die "bugreport 中未找到 btsnoop_hci.log"
    unzip -p "$OUT_DIR/bugreport.zip" "$inner" > "$OUT_DIR/btsnoop_hci.log"
    found="$OUT_DIR/btsnoop_hci.log"
    echo "   已从 bugreport 提取: $inner"
  fi

  echo "== 2/2 离线解帧"
  if [ "$(wc -c < "$found")" -gt 0 ]; then
    bash "$SCRIPT_DIR/run_pipeline.sh" "$found" "$OUT_DIR"
  else
    die "btsnoop 为空——确认 started 时 snoop 已开启并在操作前关开过蓝牙"
  fi
}

case "$CMD" in
  start) do_start ;;
  pull)  do_pull ;;
  *) echo "用法: $0 {start|pull YYYYMMDD}"; exit 1 ;;
esac
