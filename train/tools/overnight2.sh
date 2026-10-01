#!/bin/zsh
# The second half of the night, after the first run stalled (see train/README.md):
# train on the relabelled corpus with dropout and EQ, until about 07:15, then
# score it next to the first run, export the best checkpoint, and render.
set -u
D=$HOME/synthsplit-data
T=${0:A:h:h}
PY="$T/.venv/bin/python"
L=$D/logs
RUN=$D/runs/second
log() { echo "[$(date '+%a %T')] $*" >> $L/overnight.log; }
cd "$T" || exit 1
mkdir -p $RUN
log "second run: relabelled corpus, dropout 0.2, weight decay 1e-3, EQ + more song mixing"
now=$(date +%s)
target=$(date -j -f "%Y-%m-%d %H:%M" "$(date +%Y-%m-%d) 07:15" +%s)
(( target <= now )) && target=$(( target + 86400 ))
hours=$(python3 -c "print(round(($target - $now) / 3600, 2))")
log "training for $hours h"
$PY train.py --data $D/corpus --out $RUN --hours $hours --workers 4 >> $L/train2.log 2>&1 || log "training stopped early: see train2.log"
log "training finished: $(grep '^final' $L/train2.log | tail -1 | cut -c1-300)"

ckpt=best.pt; [[ -f $RUN/best.pt ]] || ckpt=checkpoint.pt
$PY evaluate.py --data $D/corpus --run $RUN --ckpt $ckpt --out $RUN/eval.json >> $L/eval2.log 2>&1
$PY evaluate.py --data $D/corpus --run $D/runs/first-stalled --ckpt checkpoint.pt --out $D/runs/first-stalled/eval.json >> $L/eval-first.log 2>&1
$PY export.py --run $RUN --ckpt $ckpt --out $RUN/synth-split.onnx >> $L/export.log 2>&1 || log "export failed: see export.log"
if [[ -f $RUN/synth-split.onnx ]]; then
  $PY evaluate.py --data $D/corpus --onnx $RUN/synth-split.onnx --out $RUN/eval-onnx.json >> $L/eval-onnx.log 2>&1
  for mixture in $D/listen/*/mixture.wav(N); do
    $PY tools/render_split.py $mixture --onnx $RUN/synth-split.onnx --out ${mixture:h} --seconds 90 >> $L/render.log 2>&1
  done
fi
log "all done · second: $(grep -E '^slakh' $L/eval2.log | tail -1) · first: $(grep -E '^slakh' $L/eval-first.log | tail -1)"
