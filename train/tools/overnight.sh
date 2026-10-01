#!/bin/zsh
# One night on the Mac, start to finish, with nobody watching:
#   MUSDB18-HQ (finish + verify) -> its vocals -> Slakh songs as their stems
#   land -> train until about 07:15 -> evaluate -> export -> render a song.
# Everything under ~/synthsplit-data; progress in logs/overnight.log.
set -u
D=$HOME/synthsplit-data
T=${0:A:h:h}  # train/, wherever the repo is
PY="$T/.venv/bin/python"
L=$D/logs
RUN=$D/runs/first
MUSDB=$D/downloads/musdb18hq.zip
MUSDB_BYTES=22656664047
MUSDB_MD5=12d4f2ecd55245a4688754dd76363103
log() { echo "[$(date '+%a %T')] $*" >> $L/overnight.log; }
cd "$T" || exit 1
mkdir -p $L $RUN
log "start"

# 1. MUSDB18-HQ, whole and verified. Resume it if the first download died.
while [[ ! -f $MUSDB || $(stat -f %z $MUSDB) -ne $MUSDB_BYTES ]]; do
  if ! pgrep -qf "musdb18hq.zip/content"; then
    log "musdb download not running: resuming at $(stat -f %z $MUSDB 2>/dev/null || echo 0) bytes"
    curl -L -C - --retry 5 --retry-delay 10 -s -S -o $MUSDB 'https://zenodo.org/api/records/3338373/files/musdb18hq.zip/content' 2>>$L/musdb.log
  fi
  sleep 30
done
if [[ $(md5 -q $MUSDB) != $MUSDB_MD5 ]]; then log "MUSDB18-HQ checksum mismatch: stopping"; exit 1; fi
log "musdb18hq.zip verified"

# 2. Its vocals, and two test mixtures to listen to in the morning.
if [[ ! -f $D/musdb-vocals/index.json ]]; then
  $PY prepare/musdb_vocals.py --zip $MUSDB --out $D/musdb-vocals >> $L/musdb-vocals.log 2>&1 || { log "vocal extraction failed"; exit 1; }
fi
# Held-out songs (MUSDB test), never trained on: two with synths in them if the names are there.
$PY - "$MUSDB" "$D/listen" <<'PYEOF' >> $L/overnight.log 2>&1
import sys, zipfile, pathlib
z = zipfile.ZipFile(sys.argv[1]); out = pathlib.Path(sys.argv[2])
mixes = sorted(n for n in z.namelist() if "/test/" in "/" + n and n.endswith("/mixture.wav"))
wanted = [m for m in mixes if any(k in m for k in ("Skelpolu - Resurrection", "Ben Carrigan - We'll Talk About It All Tonight"))]
for name in (wanted + [m for m in mixes if m not in wanted])[:2]:
    song = out / pathlib.Path(name).parts[-2].replace(" ", "_")
    song.mkdir(parents=True, exist_ok=True)
    (song / "mixture.wav").write_bytes(z.read(name))
    print("listen:", song.name)
PYEOF
log "vocals ready ($(ls $D/musdb-vocals/*/*.npy | wc -l | tr -d ' ') stems)"

# 3. Slakh songs, taken as their stems finish arriving; returns once the download is done.
$PY prepare/slakh.py --slakh $D/slakh --vocals $D/musdb-vocals --out $D/corpus --workers 6 --watch >> $L/slakh-prepare.log 2>&1
log "slakh prepared: $(tail -1 $L/slakh-prepare.log) · download: $(cat $D/slakh/DOWNLOAD_DONE 2>/dev/null)"
log "disk free: $(df -h / | tail -1 | awk '{print $4}')"

# 4. Train for whatever is left of the night, ending about 07:15.
now=$(date +%s)
target=$(date -j -f "%Y-%m-%d %H:%M" "$(date +%Y-%m-%d) 07:15" +%s)
(( target <= now )) && target=$(( target + 86400 ))
hours=$(python3 -c "print(round(($target - $now) / 3600, 2))")
log "training for $hours h"
$PY train.py --data $D/corpus --out $RUN --hours $hours --workers 4 >> $L/train.log 2>&1 || log "training stopped early: see train.log"
log "training finished: $(tail -1 $L/train.log | cut -c1-300)"

# 5. Score it, export it, score the export, and render the two test songs.
$PY evaluate.py --data $D/corpus --run $RUN --out $RUN/eval.json >> $L/eval.log 2>&1
$PY export.py --run $RUN --out $RUN/synth-split.onnx >> $L/export.log 2>&1 || log "export failed: see export.log"
if [[ -f $RUN/synth-split.onnx ]]; then
  $PY evaluate.py --data $D/corpus --onnx $RUN/synth-split.onnx --out $RUN/eval-onnx.json >> $L/eval-onnx.log 2>&1
  for mixture in $D/listen/*/mixture.wav(N); do
    $PY tools/render_split.py $mixture --onnx $RUN/synth-split.onnx --out ${mixture:h} --seconds 90 >> $L/render.log 2>&1
  done
fi
log "all done · $(grep -E '^slakh' $L/eval.log | tail -1)"
