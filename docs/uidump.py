import re, sys, subprocess, os
os.chdir(r"D:\app\KonkerX")
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
subprocess.run(["adb", "pull", "/sdcard/ui.xml", "ui.xml"], capture_output=True)
x = open("ui.xml", encoding="utf-8").read()
for m in re.finditer(r"<node[^>]*>", x):
    s = m.group(0)
    d = re.search(r'content-desc="([^"]*)"', s)
    t = re.search(r' text="([^"]*)"', s)
    b = re.search(r'bounds="([^"]+)"', s)
    label = (d.group(1) if d else "") or (t.group(1) if t else "")
    if label.strip():
        print(f"{label.strip()[:40]:42s} {b.group(1)}")
