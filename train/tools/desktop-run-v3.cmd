@echo off
rem Spectra own-stems v3 run (train/STEMS.md): v2's recipe with the 808 and vocal fixes (a held
rem kick boom is bass after its punch, in the library as in the generated songs; clean and held
rem generated kicks; catalog vocals driven bright now and then), scored on v1's held-out set
rem (runs\v3\valid.pt is v1's copy). Started by the scheduled task "Spectra stems v3" (03:00,
rem 2026-11-27). Trains, restarts from the last checkpoint after a crash (10 tries), renders the
rem owner's 15 songs, builds D:\stems-clean\listen-stems-v3-compare\index.html, then measures:
rem lane_bands.txt (where each band of the songs goes), dips.txt (bass and vocal dips against the
rem app's lanes) and cues.txt (drums' top end, kicks alone, driven vocals).
rem Stop it: create D:\stems-clean\runs\v3\PAUSE (pauses) or end the python processes.
setlocal EnableDelayedExpansion
set OPENBLAS_NUM_THREADS=1
set PYTHONIOENCODING=utf-8
set PY=D:\synthsplit\spectra\train\.venv\Scripts\python.exe
set RUN=D:\stems-clean\runs\v3
set STEPS=40000
set LISTEN=D:\stems-clean\listen-stems-v3
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
%PY% tools\compare_listen.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --splitter hs-last --out %LISTEN%-compare --label v3 >> %RUN%\render.log 2>&1
%PY% tools\lane_bands.py %LISTEN% --json %RUN%\lane_bands.json > %RUN%\lane_bands.txt 2>&1
%PY% tools\lane_dips.py --ours %LISTEN% --app D:\synthsplit\hardstyle-renders --json %RUN%\dips.json > %RUN%\dips.txt 2>&1
%PY% tools\probe_cues.py --root D:\stems-clean --ckpt %RUN%\best.pt --device cuda --json %RUN%\cues.json > %RUN%\cues.txt 2>&1
echo %date% %time% all done >> %RUN%\train.log
:end
endlocal
