#!/usr/bin/env python3
"""Remember the standalone Pixel_7_Pro window position on this X11 desktop."""
import fcntl, json, os, re, subprocess, time
from pathlib import Path

state_dir = Path.home() / '.local/state/planner-emulator'
state_dir.mkdir(parents=True, exist_ok=True)
lock = (state_dir / 'window.lock').open('w')
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit(0)
state_file = state_dir / 'window.json'
try:
    saved = json.loads(state_file.read_text())
except (OSError, ValueError):
    saved = None
seen = set()

def run(*args):
    return subprocess.run(args, capture_output=True, text=True, timeout=3).stdout

while True:
    try:
        current = set()
        for line in run('wmctrl', '-lpGx').splitlines():
            fields = line.split(None, 9)
            if len(fields) < 10 or not re.fullmatch(r'Android Emulator - Pixel_7_Pro:\d+', fields[9]):
                continue
            wid, pid = fields[0], fields[2]
            identity = (wid, pid)
            current.add(identity)
            if identity not in seen and saved is not None:
                run('wmctrl', '-ir', wid, '-e', f"0,{saved['x']},{saved['y']},-1,-1")
                # Read the new geometry next time; don't overwrite it with the startup position.
                continue
            match = re.search(r'=\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)',
                              run('xprop', '-id', wid, '_NET_FRAME_EXTENTS'))
            left, top = (int(match[1]), int(match[3])) if match else (0, 0)
            position = {'x': int(fields[3]) - left, 'y': int(fields[4]) - top}
            if position != saved:
                temp = state_file.with_suffix('.tmp')
                temp.write_text(json.dumps(position) + '\n')
                os.replace(temp, state_file)
                saved = position
        seen = current
    except (OSError, ValueError, subprocess.TimeoutExpired):
        pass
    time.sleep(1)
