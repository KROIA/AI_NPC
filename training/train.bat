@echo off
rem Opens the training window (train_gui.py): Continue / New run / Pause, with the loss curve.
rem Each epoch saves latest.pt (try it live: /ainpc npc reload), the best one model.pt. Results in training\runs\<time>\.
rem Pause when val loss starts rising (overfitting); record more, /ainpc generateTrainingset, then Continue.
rem No args: all datasets of the data server. Otherwise args go to train.py: <folders/files...> [--epochs N] [--batch 16]
setlocal
rem The data server (friends record there). Old default: "%~dp0..\fabric\run\ainpc_datasets" (dev client).
set "DATASETS=%APPDATA%\.minecraft\server\1.21.1\fabric_AI\ainpc_datasets"
if "%~1"=="" (
  if not exist "%DATASETS%\features.json" (
    echo No training set in %DATASETS%. Run /ainpc generateTrainingset on that server first.
    pause
    exit /b 1
  )
  python -X utf8 "%~dp0train_gui.py" "%DATASETS%"
) else (
  python -X utf8 "%~dp0train_gui.py" %*
)
