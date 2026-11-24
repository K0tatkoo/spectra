@echo off
rem Spectra own-stems v1 test run (train/STEMS.md): the v0 recipe on the fixed data, on the GPU.
rem Started by the scheduled task "Spectra stems v1 test" (22:30, 2026-11-24). Trains, restarts
rem from the last checkpoint after a crash (10 tries), then renders the owner's 15 songs and
rem builds the listening page D:\stems-clean\listen-v1-compare\index.html.
rem Stop it: create D:\stems-clean\runs\v1-test\PAUSE (pauses) or end the python processes.
setlocal EnableDelayedExpansion
set OPENBLAS_NUM_THREADS=1
set PY=D:\synthsplit\spectra\train\.venv\Scripts\python.exe
set RUN=D:\stems-clean\runs\v1-test
cd /d D:\stems-clean\spectra\train
if not exist %RUN% mkdir %RUN%
echo %date% %time% start >> %RUN%\train.log
set TRIES=0
:train
%PY% train_stems.py --root D:\stems-clean --out %RUN% --resume --preset small --steps 40000 --device cuda --threads 4 --workers 6 --save-minutes 10 >> %RUN%\train.log 2>&1
set CODE=!errorlevel!
if !CODE! equ 0 goto render
set /a TRIES+=1
echo %date% %time% trainer exited with !CODE!, try !TRIES! >> %RUN%\train.log
if !TRIES! geq 10 goto end
timeout /t 60 /nobreak > nul
goto train
:render
echo %date% %time% training done, rendering the songs >> %RUN%\train.log
for %%f in (D:\training_songs_hardstyle\*.mp3) do %PY% tools\separate.py --ckpt %RUN%\best.pt --out D:\stems-clean\listen-v1 --device cuda "%%f" >> %RUN%\render.log 2>&1
%PY% tools\compare_listen.py --ours D:\stems-clean\listen-v1 --app D:\synthsplit\hardstyle-renders --splitter hs-last --out D:\stems-clean\listen-v1-compare --label v1 >> %RUN%\render.log 2>&1
echo %date% %time% all done >> %RUN%\train.log
:end
endlocal
