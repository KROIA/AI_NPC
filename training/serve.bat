@echo off
rem Model server for the NPC. Args go to serve.py: [model.pt] [--port 5571] [--greedy]
rem No model given: the newest run under training\runs. Then in game: /ainpc npc start
python -X utf8 "%~dp0serve.py" %*
pause
