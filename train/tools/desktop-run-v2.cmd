@echo off
rem Spectra own-stems v2 run (train/STEMS.md): v1's recipe with the top-end fix (no stem owns a
rem frequency range), on the GPU, scored on v1's own held-out set (runs\v2\valid.pt is v1's copy).
rem Started by the scheduled task "Spectra stems v2" (22:30, 2026-11-25). Trains, restarts from the
rem last checkpoint after a crash (10 tries), renders the owner's 15 songs, builds the listening page
rem D:\stems-clean\listen-stems-v2-compare\index.html, then measures: lane_bands.txt (where each band
rem of the songs goes) and cues.txt (does it still need the top octave to hear drums).
rem Stop it: create D:\stems-clean\runs\v2\PAUSE (pauses) or end the python processes.
setlocal EnableDelayedExpansion
set OPENBLAS_NUM_THREADS=1
set PYTHONIOENCODING=utf-8
set PY=D:\synthsplit\spectra\train\.venv\Scripts\python.exe
set RUN=D:\stems-clean\runs\v2
set STEPS=40000
set LISTEN=D:\stems-clean\listen-stems-v2
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
%PY% tools\compare_listen.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --splitter hs-last --out %LISTEN%-compare --label v2 >> %RUN%\render.log 2>&1
%PY% tools\lane_bands.py %LISTEN% --json %RUN%\lane_bands.json > %RUN%\lane_bands.txt 2>&1
%PY% tools\probe_cues.py --root D:\stems-clean --ckpt %RUN%\best.pt --device cuda --json %RUN%\cues.json > %RUN%\cues.txt 2>&1
echo %date% %time% all done >> %RUN%\train.log
:end
endlocal
