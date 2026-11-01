"""Pass an external, short-lived invitation to the local emulator without logging it."""
from pathlib import Path
import shlex
import subprocess

adb = Path.home()/'.local/android-sdk/platform-tools/adb'
invitation = Path.home()/'.local/mapp-test.invite'
link = invitation.read_text().strip()
subprocess.run([str(adb), 'shell', 'am start -a android.intent.action.VIEW -d ' + shlex.quote(link) + ' local.mapp'],
               stdout=subprocess.DEVNULL, check=True)
invitation.unlink()
print('Pairing link delivered to emulator')
