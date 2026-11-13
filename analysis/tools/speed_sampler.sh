#!/usr/bin/env bash
# 官方 App 速度读数采集：手机端分段录屏 + 设备端墙钟标记，全程不需要电脑陪跑。
#
# 背景（E70）：官方 App 是 Cocos2d 渲染，uiautomator 抓不到速度文本；
# 而 screenrecord + OCR 是唯一能同时拿到「读数 + 墙钟」的路。墙钟标记写在
# 设备端（date +%s%3N），读数与报文对齐不再依赖录屏 creation_time。
#
# 用法（手机用 USB 或 `adb tcpip 5555` + `adb connect <ip>` 连上电脑）：
#   speed_sampler.sh start             # 清场并启动录屏循环（最多 6×170 s ≈ 17 分钟）
#   speed_sampler.sh status
#   speed_sampler.sh stop              # 提前收尾（SIGINT 让当前段正常落盘）
#   speed_sampler.sh pull 20261113_speed_01
#                                      # 拉回 + OCR 成 speed_observations.csv
#
# 采集口令（与 ../procedure_speed_calibration.md §2 一致）：
#   静止 20–30 s → 稳速 20–30 s → 再停 20–30 s → 换个速度重复 20–30 s → 最后静止 10 s。
#   第二段骑行（repeat）开始的那一刻记一下手机时间，pull 后用 --repeat-from 标注。
set -uo pipefail

CMD="${1:-}"
NAME="${2:-}"
ADB="${ADB:-adb}"
# 多设备时用 ADB_TARGET 指定序列号：ADB_TARGET="-s 192.168.1.200:5555" speed_sampler.sh start
ADB_TARGET="${ADB_TARGET:-}"
DEVDIR="/sdcard/iwalk_speed"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

die() { echo "ERROR: $*" >&2; exit 1; }
run_adb() { $ADB $ADB_TARGET "$@"; }
need_device() { run_adb get-state 2>/dev/null | grep -q device || die "没有可用的 adb 设备（多设备时设 ADB_TARGET='-s <序列号>'）"; }

LOOP_SH='i=0
while [ $i -lt 6 ]; do
  if [ -f /sdcard/iwalk_speed/stop ]; then break; fi
  i=$((i+1))
  echo "REC $i start $(date +%s%3N)" >> /sdcard/iwalk_speed/markers.txt
  screenrecord --time-limit 170 /sdcard/iwalk_speed/rec_$i.mp4
  echo "REC $i end $(date +%s%3N)" >> /sdcard/iwalk_speed/markers.txt
done
echo "LOOP end $(date +%s%3N)" >> /sdcard/iwalk_speed/markers.txt'

do_start() {
  need_device
  run_adb shell mkdir -p "$DEVDIR" || die "建目录失败"
  run_adb shell "rm -f $DEVDIR/stop $DEVDIR/rec_*.mp4 $DEVDIR/markers.txt $DEVDIR/loop.log"
  mkdir -p "$SCRIPT_DIR/../../captures"
  {
    echo "host_epoch_ms=$(date +%s%3N)"
    echo "device_epoch_ms=$(run_adb shell date +%s%3N | tr -d '\r')"
    echo "注：读数墙钟与 --hci-t1 都用设备钟；两者同源，差值不影响互相咬合。"
  } > "$SCRIPT_DIR/../../captures/_clock_base.txt"
  printf '%s\n' "$LOOP_SH" | run_adb shell "cat > $DEVDIR/loop.sh" || die "推脚本失败"
  run_adb shell "nohup sh $DEVDIR/loop.sh >$DEVDIR/loop.log 2>&1 &" || die "启动失败"
  sleep 3
  do_status
  echo
  echo "现在：官方 App 连车 → 开始四段骑行。全程不需要电脑， riding 时可以拔线"
  echo "（前提：已经 adb tcpip 5555 并 connect；否则录屏会随 adb 断开被杀）。"
  echo "结束后：$0 stop && $0 pull <目录名>"
}

do_status() {
  need_device
  echo "== 设备端状态"
  run_adb shell "ls -l $DEVDIR 2>/dev/null" | grep -v '^total'
  pid=$(run_adb shell pidof screenrecord 2>/dev/null | tr -d '\r')
  [ -n "$pid" ] && echo "screenrecord 运行中 (pid $pid)" || echo "screenrecord 未在运行"
  run_adb shell "cat $DEVDIR/markers.txt 2>/dev/null"
}

do_stop() {
  need_device
  run_adb shell "touch $DEVDIR/stop"
  pid=$(run_adb shell pidof screenrecord 2>/dev/null | tr -d '\r')
  if [ -n "$pid" ]; then
    run_adb shell "kill -2 $pid"      # SIGINT：screenrecord 正常收尾当前文件
    echo "已发 SIGINT 给当前段，等待落盘…"
    sleep 3
  fi
  do_status
}

do_pull() {
  need_device
  [ -n "$NAME" ] || die "用法: $0 pull YYYYMMDD_speed_01"
  OUT="$SCRIPT_DIR/../../captures/$NAME"
  mkdir -p "$OUT"
  run_adb pull "$DEVDIR" "$OUT/device_pull" >/dev/null || die "pull 失败"
  echo "已拉到 $OUT/device_pull"

  VIDEOS=$(ls "$OUT"/device_pull/rec_*.mp4 2>/dev/null || true)
  [ -n "$VIDEOS" ] || die "device_pull 里没有 rec_*.mp4"
  # shellcheck disable=SC2086
  PYTHONDONTWRITEBYTECODE=1 python3 "$SCRIPT_DIR/readings_from_video.py" \
    --markers "$OUT/device_pull/markers.txt" \
    --csv "$OUT/speed_observations.csv" $VIDEOS || die "OCR 失败；先 --probe 肉眼看几帧"
  echo
  echo "下一步（真读数才继续，别拿演示数据跑）："
  echo "  PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/speed_calibration.py \\"
  echo "    --btsnoop <手机 HCI 日志> --observations captures/$NAME/speed_observations.csv \\"
  echo "    --hci-t1 <pull 前 adb shell date 的时刻> \\"
  echo "    --base-calibration downloads/iwk1779887-calibration-partial-v1.json \\"
  echo "    --emit downloads/iwk1779887-speed-v1.json"
}

case "$CMD" in
  start)  do_start ;;
  status) do_status ;;
  stop)   do_stop ;;
  pull)   do_pull ;;
  *) echo "用法: $0 {start|status|stop|pull YYYYMMDD_speed_01}"; exit 1 ;;
esac
