#!/usr/bin/env python
"""把系统安装器弹出的确认框依次点掉。

用法：python tools/drive_install.py [轮数] [--serial emulator-5556]
    轮数默认 1；每轮 = 等「安装」→ 点 → 等「完成 / 打开」→ 点「完成」。

存在的理由：批量创建/批量更新时，系统弹窗是唯一的阻塞点（每份都要人点一次）。
实机验证脚本化之后，才能跑「一次建 6 个」这种回归。
"""
import re
import subprocess
import sys
import time

ADB = r"C:/Users/94591/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5556"

# 系统安装器在不同 ROM / 语言下的文案
INSTALL = ("安装", "Install", "更新", "Update", "仍然安装", "Install anyway")
DONE = ("完成", "Done", "Open", "打开")


def sh(*args, timeout=30):
    env = {"MSYS_NO_PATHCONV": "1"}
    import os
    e = dict(os.environ)
    e.update(env)
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True,
                          timeout=timeout, env=e).stdout


def nodes():
    for _ in range(3):
        out = sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "dumped" in out:
            break
        time.sleep(0.6)
    xml = sh("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        txt = re.search(r'text="([^"]*)"', t)
        bd = re.search(r'bounds="(\d+),(\d+),(\d+),(\d+)"', t)
        if txt and txt.group(1) and bd:
            x1, y1, x2, y2 = map(int, bd.groups())
            out.append((txt.group(1), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap_text(labels):
    for text, x, y in nodes():
        if text.strip() in labels:
            sh("shell", "input", "tap", str(x), str(y))
            return text
    return None


def main():
    rounds = 1
    for a in sys.argv[1:]:
        if a.isdigit():
            rounds = int(a)
    print(f"serial={SERIAL} rounds={rounds}")
    for i in range(rounds):
        hit = None
        for _ in range(60):
            hit = tap_text(INSTALL)
            if hit:
                print(f"[{i+1}] 点「{hit}」")
                break
            time.sleep(1)
        if not hit:
            print(f"[{i+1}] 没等到安装确认框")
            return 1
        time.sleep(2.5)
        done = None
        for _ in range(90):
            done = tap_text(DONE)
            if done:
                print(f"[{i+1}] 点「{done}」")
                break
            time.sleep(1)
        if not done:
            print(f"[{i+1}] 没等到「完成 / 打开」")
            return 1
        time.sleep(1.2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
