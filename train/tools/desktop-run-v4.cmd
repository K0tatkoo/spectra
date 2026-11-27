@echo off
rem Spectra own-stems v4 run (train/STEMS.md): v2's vocals (no brightened vocals, which broke v3's
rem synth), every drum's sub labelled bass from the hit (as the app's lanes have it), steady sub-bass
rem lines and clean/held kicks in the generated songs. It builds its own held-out set (v1's labels a
rem drum's sub drums), and is also scored on v1's (old_set.txt: vocals, synth and other compare with
rem v1-v3). Started by the scheduled task "Spectra stems v4" (03:00, 2026-11-28). Trains, restarts
rem from the last checkpoint after a crash (10 tries), renders the owner's 15 songs, builds
rem D:\stems-clean\listen-stems-v4-compare\index.html, then measures: lane_bands.txt, dips.txt
rem (bass and vocal dips against the app, and the bass at each kick) and cues.txt.
rem Stop it: create D:\stems-clean\runs\v4\PAUSE (pauses) or end the python processes.
setlocal EnableDelayedExpansion
set OPENBLAS_NUM_THREADS=1
set PYTHONIOENCODING=utf-8
set PY=D:\synthsplit\spectra\train\.venv\Scripts\python.exe
set RUN=D:\stems-clean\runs\v4
set STEPS=40000
set LISTEN=D:\stems-clean\listen-stems-v4
cd /d D:\stems-clean\spectra\train
if not exist %RUN% mkdir %RUN%
echo %date% %time% start >> %RUN%\train.log
set TRIES=0
:train
%PY% train_stems.py --root D:\stems-clean --out %RUN% --resume --preset small --steps %STEPS% --device cuda --threads 4 --workers 6 --save-minutes 10 >> %RUN%\train.log 2>&1
set CODE=!errorlevel!
if !CODE! equ 0 goto render
set /a TRIES+=1
echo %date% %time% trainer exited with !CODE!, try !TRIES! >> %RUN%\train.log
if !TRIES! geq 10 goto end
timeout /t 60 /nobreak > nul
goto train
:render
echo %date% %time% training done, rendering the songs >> %RUN%\train.log
for %%f in (D:\training_songs_hardstyle\*.mp3) do %PY% tools\separate.py --ckpt %RUN%\best.pt --out %LISTEN% --device cuda "%%f" >> %RUN%\render.log 2>&1
%PY% tools\compare_listen.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --splitter hs-last --out %LISTEN%-compare --label v4 >> %RUN%\render.log 2>&1
%PY% tools\score_on.py --ckpt %RUN%\best.pt --valid D:\stems-clean\runs\v1-test\valid.pt --device cuda > %RUN%\old_set.txt 2>&1
%PY% tools\lane_bands.py %LISTEN% --json %RUN%\lane_bands.json > %RUN%\lane_bands.txt 2>&1
%PY% tools\lane_dips.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --json %RUN%\dips.json > %RUN%\dips.txt 2>&1
%PY% tools\probe_cues.py --root D:\stems-clean --ckpt %RUN%\best.pt --device cuda --json %RUN%\cues.json > %RUN%\cues.txt 2>&1
echo %date% %time% all done >> %RUN%\train.log
:end
endlocal
