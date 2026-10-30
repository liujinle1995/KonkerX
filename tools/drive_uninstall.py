#!/usr/bin/env python
"""把系统弹出的卸载确认框依次点掉。

用法：python tools/drive_uninstall.py [轮数] [--serial emulator-5556]

存在的理由：「清除全部分身」是**排队**提交的 —— 提交一个、等用户点完、再提交下一个。
这条链路只能实测：单元测试模拟不了 PIA 的界面，而它恰恰是唯一会卡住的地方。
跑一遍就能看到系统到底弹了几次、道具是不是真的把「下一个」接上了。
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:/Users/94591/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5556"

CONFIRM = ("Uninstall", "卸载", "确定")


def sh(*args, timeout=30):
    e = dict(os.environ)
    e["MSYS_NO_PATHCONV"] = "1"
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True,
                          timeout=timeout, env=e).stdout


def nodes():
    for _ in range(3):
        if "dumped" in sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"):
            break
        time.sleep(0.6)
    xml = sh("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        tag = m.group(0)
        text = re.search(r'text="([^"]*)"', tag)
        desc = re.search(r'content-desc="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        clickable = 'clickable="true"' in tag
        if not bounds:
            continue
        x1, y1, x2, y2 = (int(v) for v in bounds.groups())
        out.append(((text.group(1) if text else "") or (desc.group(1) if desc else ""),
                    clickable, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def confirm_button():
    for label, clickable, x, y in nodes():
        if clickable and label in CONFIRM:
            return x, y
    return None


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 1
    for i in range(rounds):
        hit = None
        deadline = time.time() + 25
        while time.time() < deadline:
            hit = confirm_button()
            if hit:
                break
            time.sleep(0.7)
        if not hit:
            print(f"round {i + 1}: 没等到卸载框")
            break
        sh("shell", "input", "tap", str(hit[0]), str(hit[1]))
        print(f"round {i + 1}: tapped {hit}")
        time.sleep(1.2)


if __name__ == "__main__":
    main()
