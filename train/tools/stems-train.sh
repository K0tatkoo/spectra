#!/bin/bash
# Runs the own-stems trainer (train_stems.py) on the home server for days, at about half load.
#
#   tools/stems-train.sh start    # in the background: waits for the data (FETCH_DONE), then trains
#   tools/stems-train.sh status   # step, speed, ETA, last validation, fans, power and CPU temperatures
#   tools/stems-train.sh pause    # keeps everything in memory but stops computing (quiet at once)
#   tools/stems-train.sh resume
#   tools/stems-train.sh stop     # saves a checkpoint and ends; `start` carries on from it
#
# Half load: the trainer is pinned to CPUS, default 0-5,12-17 = 6 of the R620's 12 physical
# cores with both their hyperthreads (12 of 24 threads), at nice 19 and idle I/O priority,
# so the sites and game servers always come first. Measured 2026-11-16 (STEMS.md): this
# 182 W / ~7,900 RPM / 17 s a step; 0-11 (every core, one thread) 196 W / ~8,000 RPM / 12.7 s;
# 0-3,12-15 with STEMS_THREADS=6 STEMS_WORKERS=2: 168 W / ~6,000 RPM / 23 s.
# A crash restarts it from the last checkpoint (up to 20 times in a row); `start` from
# the crontab's @reboot line brings it back after a reboot. Logs: <run>/train.log.
set -u
# Settings that should outlast a reboot (STEMS_CPUS=…, STEMS_THREADS=…) go in this file.
[ -f "$HOME/stems-clean/train.env" ] && . "$HOME/stems-clean/train.env"
R=${STEMS_ROOT:-$HOME/stems-clean}
RUN=${STEMS_RUN:-$R/runs/v0}
CPUS=${STEMS_CPUS:-0-5,12-17}
THREADS=${STEMS_THREADS:-9}
WORKERS=${STEMS_WORKERS:-3}
PRESET=${STEMS_PRESET:-small}
STEPS=${STEMS_STEPS:-40000}
WAIT_FOR=${STEMS_WAIT_FOR:-$R/FETCH_DONE}  # trains only once this file exists (the fetch's last act)
EXTRA=${STEMS_EXTRA:-}                     # more train_stems.py arguments, word-split
PY=$R/venv/bin/python
TRAIN=$R/spectra/train
PIDFILE=$RUN/loop.pid

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

loop() {
  echo $$ > "$PIDFILE"
  fails=0
  while :; do
    if [ ! -f "$WAIT_FOR" ]; then
      echo "$(date -u +%FT%TZ) waiting for the data ($WAIT_FOR)"
      sleep 300
      continue
    fi
    echo "$(date -u +%FT%TZ) start (cpus $CPUS, $THREADS threads + $WORKERS workers, $PRESET, $STEPS steps)"
    cd "$TRAIN" || exit 1
    OMP_WAIT_POLICY=PASSIVE taskset -c "$CPUS" nice -n 19 ionice -c3 "$PY" train_stems.py \
      --root "$R" --out "$RUN" --resume --preset "$PRESET" --steps "$STEPS" \
      --threads "$THREADS" --workers "$WORKERS" $EXTRA &
    echo $! > "$RUN/trainer.pid"
    wait $!
    code=$?
    rm -f "$RUN/trainer.pid"
    echo "$(date -u +%FT%TZ) trainer exited with $code"
    [ $code -eq 0 ] && break           # finished, or stopped on purpose (it saved first)
    fails=$((fails + 1))
    [ $fails -ge 20 ] && { echo "20 failures in a row: giving up"; break; }
    sleep 120
  done
  rm -f "$PIDFILE"
}

case "${1:-status}" in
  start)
    mkdir -p "$RUN"
    if running; then echo "already running (loop $(cat "$PIDFILE"))"; exit 0; fi
    # Run from a copy: a `git pull` must not change the script under a running bash.
    cp "$0" "$RUN/.loop.sh"
    STEMS_ROOT=$R STEMS_RUN=$RUN STEMS_CPUS=$CPUS STEMS_THREADS=$THREADS STEMS_WORKERS=$WORKERS \
      STEMS_PRESET=$PRESET STEMS_STEPS=$STEPS STEMS_WAIT_FOR=$WAIT_FOR STEMS_EXTRA=$EXTRA setsid nohup bash "$RUN/.loop.sh" _loop >> "$RUN/train.log" 2>&1 < /dev/null &
    sleep 1
    echo "started; log: $RUN/train.log"
    ;;
  _loop) loop ;;
  stop)
    rm -f "$RUN/PAUSE"
    if [ -f "$RUN/trainer.pid" ]; then kill -CONT "$(cat "$RUN/trainer.pid")" 2>/dev/null; kill -TERM "$(cat "$RUN/trainer.pid")"; fi
    for _ in $(seq 60); do [ -f "$RUN/trainer.pid" ] || break; sleep 2; done
    running && kill "$(cat "$PIDFILE")" 2>/dev/null
    rm -f "$PIDFILE"
    echo "stopped"
    ;;
  pause) touch "$RUN/PAUSE"; echo "pausing after the current step (a minute at most)" ;;
  resume) rm -f "$RUN/PAUSE"; echo "resuming" ;;
  status)
    if running; then echo "running (loop $(cat "$PIDFILE"))"; else echo "not running"; fi
    [ -f "$RUN/PAUSE" ] && echo "PAUSED"
    [ -f "$R/FETCH_DONE" ] || echo "data: still fetching ($(tail -1 "$R/logs/fetch.log" 2>/dev/null))"
    [ -f "$RUN/status.json" ] && cat "$RUN/status.json"
    [ -f "$RUN/metrics.jsonl" ] && { grep '"valid"' "$RUN/metrics.jsonl" | tail -1; }
    sudo -n ipmitool sdr elist 2>/dev/null | grep -E "Pwr Consumption|^Temp |Fan1A|Fan4A|Fan7B" | awk -F'|' '{printf "%s:%s  ", $1, $5} END {print ""}'
    ;;
  *) echo "usage: $0 start|status|pause|resume|stop"; exit 2 ;;
esac
