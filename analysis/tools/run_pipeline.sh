#!/usr/bin/env bash
# Usage: run_pipeline.sh <btsnoop_hci.log> [output_dir]
# Full offline chain: btsnoop -> ATT notifications -> iWalk frame JSONL.
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")" && pwd)"
IN="$1"
OUT="${2:-$(dirname "$IN")}"
mkdir -p "$OUT"
python3 "$TOOLS/btsnoop_att.py" "$IN" > "$OUT/att_lines.txt"
python3 "$TOOLS/decode_capture.py" "$OUT/att_lines.txt" -o "$OUT/frames.jsonl"
if command -v tshark >/dev/null 2>&1; then
  tshark -r "$IN" -Y 'btatt.opcode==0x1b || btatt.opcode==0x1d' -T fields \
    -e frame.time_epoch -e btatt.value > "$OUT/notifications_tshark.csv" 2>/dev/null || true
fi
echo "== summary =="
tail -1 "$OUT/frames.jsonl"
echo "artifacts: $OUT/att_lines.txt $OUT/frames.jsonl ${OUT}/notifications_tshark.csv"
