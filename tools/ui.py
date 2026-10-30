"""界面结构辅助：把 uiautomator dump 的 XML 解析成「文字 / 描述 / 中心点」三列。

用途只有一个：拿到**真实分辨率**下的点击坐标。截图会被缩放，凭图估坐标必偏；
bounds 是设备像素，`wm size` 下 1344x2992 就是准的。

    python tools/ui.py tmp_ui.xml            # 列出可点击节点
    python tools/ui.py tmp_ui.xml 设置        # 只列含「设置」的节点
"""

import sys
import xml.etree.ElementTree as ET


def center(bounds: str):
    a, b = bounds.split("][")
    x1, y1 = (int(v) for v in a.strip("[]").split(","))
    x2, y2 = (int(v) for v in b.strip("[]").split(","))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    path = sys.argv[1]
    needle = sys.argv[2] if len(sys.argv) > 2 else None
    root = ET.parse(path).getroot()
    for node in root.iter("node"):
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        rid = node.get("resource-id", "").split("/")[-1]
        label = text or desc or rid
        if not label:
            continue
        if needle and needle not in label:
            continue
        clickable = node.get("clickable") == "true"
        x, y = center(node.get("bounds", "[0,0][0,0]"))
        print(f"{'*' if clickable else ' '} {label!r:50} {x:5d},{y:5d}  id={rid}")


if __name__ == "__main__":
    main()
