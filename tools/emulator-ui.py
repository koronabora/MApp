"""Small UI inspection helper for the local Debian Android emulator."""
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

adb = str(Path.home()/'.local/android-sdk/platform-tools/adb')


def run(*args):
    return subprocess.check_output([adb, '-s', 'emulator-5554', *args], text=True)


def nodes():
    run('shell', 'uiautomator', 'dump', '/sdcard/mapp-ui.xml')
    return ET.fromstring(run('shell', 'cat', '/sdcard/mapp-ui.xml')).iter('node')


action = sys.argv[1]
if action == 'click':
    query = sys.argv[2]
    deadline = time.monotonic()+12
    while time.monotonic()<deadline:
        found = next((n for n in nodes() if n.get('text') == query or n.get('content-desc') == query), None)
        if found is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', found.get('bounds')))
            run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            print('Clicked:', query)
            break
        time.sleep(.2)
    else:
        raise SystemExit('UI element not found: ' + query)
elif action == 'input':
    run('shell', 'input', 'text', sys.argv[2])
elif action == 'back':
    run('shell', 'input', 'keyevent', '4')
else:
    for node in nodes():
        if node.get('text') or node.get('content-desc'):
            print(node.get('text') or node.get('content-desc'), node.get('bounds'))
