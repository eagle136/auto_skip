#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
负控：把导出脚本的输入逐个改坏，确认 export_default_rules.py 以「带信息的 FAIL」而不是 traceback 失败。
每类都必须是退出码非 0；任何一类反而通过，说明校验器形同虚设。
"""
import io
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
V0_SRC = "D:/apk_edit/auto_skip_jadx/sources/f2/v0.java"
STR_SRC = "D:/apk_edit/auto_skip_apktool/res/values/strings.xml"
ARR_SRC = "D:/apk_edit/auto_skip_apktool/res/values/arrays.xml"
TOOL = os.path.join(ROOT, "tools", "export_default_rules.py")
WORK = os.path.join(ROOT, "build", "negctl")

v0 = io.open(V0_SRC, encoding="utf-8").read()
strings = io.open(STR_SRC, encoding="utf-8").read()
arrays = io.open(ARR_SRC, encoding="utf-8").read()

TAPTAP = 'mainActivity.getString(R.string.delay), 512)'
CASES = [
    # 值写成带空格的数组：还原后与源码字面量不再一字不差，回放必须抓到
    ("value-mismatch", v0.replace('mainActivity.getString(R.string.node_whitelist), "[skipGroup]"', 'mainActivity.getString(R.string.node_whitelist), "[ skipGroup ]"'), strings, arrays),
    # 一整行规则被吃掉：java 侧自洽，只有 smali 外部锚点能发现
    ("dropped-line", v0.replace('        put("com.jsmcc", mainActivity.s("{%s:%d}", mainActivity.getString(R.string.task_type), 2));\n', ''), strings, arrays),
    # %d 位置给了字符串字面量：类型不对，回放必须抓到
    ("type-mismatch", v0.replace(TAPTAP, 'mainActivity.getString(R.string.delay), "512")'), strings, arrays),
    # key 资源名不存在：必须报「找不到」而不是当空串
    ("unknown-key", v0.replace("R.string.click_way", "R.string.click_way_typo"), strings, arrays),
    # 出现没见过的实参形式：必须显式失败，不许静默跳过
    ("bad-arg", v0.replace("mainActivity.getString(R.string.max_desc_length), 0", "mainActivity.getString(R.string.max_desc_length), SOMEVAR"), strings, arrays),
    # key 与 value 位置互换：占位符仍能配对，但 key 校验必须拦住
    ("swapped-args", v0.replace('mainActivity.getString(R.string.node_whitelist), "[skipGroup]"', '"[skipGroup]", mainActivity.getString(R.string.node_whitelist)'), strings, arrays),
    # 不生效名单里出现重复包名：读回阶段会重复覆盖，条数照样对得上，必须由去重断言拦住
    ("duplicate-disabled", v0, strings, arrays.replace("        <item>com.spotify.music</item>", "        <item>com.spotify.music</item>\n        <item>com.spotify.music</item>")),
    # whitelist 段落整个被删：load_whitelist 必须报错而不是给空名单
    ("missing-array", v0, strings, arrays.replace('name="whitelist"', 'name="whitelist_typo"')),
]


def run(name, src_v0, src_strings, src_arrays):
    d = os.path.join(WORK, name)
    os.makedirs(d, exist_ok=True)
    io.open(os.path.join(d, "v0.java"), "w", encoding="utf-8", newline="\n").write(src_v0)
    io.open(os.path.join(d, "strings.xml"), "w", encoding="utf-8", newline="\n").write(src_strings)
    io.open(os.path.join(d, "arrays.xml"), "w", encoding="utf-8", newline="\n").write(src_arrays)
    tool = io.open(TOOL, encoding="utf-8").read()
    tool = tool.replace('V0 = "D:/apk_edit/auto_skip_jadx/sources/f2/v0.java"', 'V0 = "%s/v0.java"' % d.replace("\\", "/"))
    tool = tool.replace('STRINGS = "D:/apk_edit/auto_skip_apktool/res/values/strings.xml"', 'STRINGS = "%s/strings.xml"' % d.replace("\\", "/"))
    tool = tool.replace('ARRAYS = "D:/apk_edit/auto_skip_apktool/res/values/arrays.xml"', 'ARRAYS = "%s/arrays.xml"' % d.replace("\\", "/"))
    tool = tool.replace(
        'OUT = "C:/Users/eagle/AndroidStudioProjects/auto_skip/app/src/main/assets/default_rules.json"',
        'OUT = "%s/out.json"' % d.replace("\\", "/"),
    )
    p = os.path.join(d, "tool.py")
    io.open(p, "w", encoding="utf-8", newline="\n").write(tool)
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    r = subprocess.run([sys.executable, p], capture_output=True, env=env)
    err = (r.stderr or b"").decode("utf-8", "replace")
    out = (r.stdout or b"").decode("utf-8", "replace")
    lines = [l for l in (err.strip().splitlines() + out.strip().splitlines()) if l.strip()]
    return r.returncode, ("Traceback" in err), (lines[-1] if lines else "")


def main():
    if os.path.isdir(WORK):
        import shutil

        shutil.rmtree(WORK)
    ok = 0
    bad = 0
    for name, a, b, c in CASES:
        code, crashed, tail = run(name, a, b, c)
        if code == 0:
            print("%-22s NOT-CAUGHT exit=0  <-- 校验器漏了这类破坏" % name)
            bad += 1
        elif crashed:
            print("%-22s FAIL-BY-TRACEBACK <-- 该给信息不是给栈" % name)
            bad += 1
        else:
            print("%-22s caught: %s" % (name, tail[:110]))
            ok += 1
    print("checks=%d caught=%d not_caught=%d" % (len(CASES), ok, bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
