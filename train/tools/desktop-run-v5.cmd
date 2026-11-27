@echo off
rem Spectra own-stems v5 run (train/STEMS.md): v4's data recipe unchanged, with the base model
rem (about twice small's size) trained three times as long (120,000 steps, ~21 h), to see how much
rem capacity helps the tinny vocals and the leaks into synth the owner heard in v4. Scored on v4's
rem held-out set (runs\v5\valid.pt is v4's copy) and on v1's (old_set.txt). Started at once on
rem 2026-11-27 by the task "Spectra stems v5" (the desktop is free for the weekend). Trains, restarts
rem from the last checkpoint after a crash (10 tries), renders the owner's 15 songs, builds
rem D:\stems-clean\listen-stems-v5-compare\index.html, then measures: lane_bands.txt, dips.txt
rem (bass and vocal dips against the app, and the bass at each kick) and cues.txt.
rem Stop it: create D:\stems-clean\runs\v5\PAUSE (pauses) or end the python processes.
setlocal EnableDelayedExpansion
set OPENBLAS_NUM_THREADS=1
set PYTHONIOENCODING=utf-8
set PY=D:\synthsplit\spectra\train\.venv\Scripts\python.exe
set RUN=D:\stems-clean\runs\v5
set STEPS=120000
set LISTEN=D:\stems-clean\listen-stems-v5
cd /d D:\stems-clean\spectra\train
if not exist %RUN% mkdir %RUN%
echo %date% %time% start >> %RUN%\train.log
set TRIES=0
:train
%PY% train_stems.py --root D:\stems-clean --out %RUN% --resume --preset base --steps %STEPS% --device cuda --threads 4 --workers 6 --save-minutes 10 >> %RUN%\train.log 2>&1
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
%PY% tools\compare_listen.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --splitter hs-last --out %LISTEN%-compare --label v5 >> %RUN%\render.log 2>&1
%PY% tools\score_on.py --ckpt %RUN%\best.pt --valid D:\stems-clean\runs\v1-test\valid.pt --device cuda > %RUN%\old_set.txt 2>&1
%PY% tools\lane_bands.py %LISTEN% --json %RUN%\lane_bands.json > %RUN%\lane_bands.txt 2>&1
%PY% tools\lane_dips.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --json %RUN%\dips.json > %RUN%\dips.txt 2>&1
%PY% tools\probe_cues.py --root D:\stems-clean --ckpt %RUN%\best.pt --device cuda --json %RUN%\cues.json > %RUN%\cues.txt 2>&1
echo %date% %time% all done >> %RUN%\train.log
:end
endlocal
