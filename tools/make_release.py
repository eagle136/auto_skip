#!/usr/bin/env python3
"""构建一个 release 包：每次运行都现生成随机 applicationId 与随机签名密钥。

用法：
    python tools/make_release.py                # 随机包名 + 随机密钥，产出到 releases/
    python tools/make_release.py --no-build     # 只生成密钥与包名，不调 gradle

为什么要随机：
    包名与签名指纹是该应用在任何设备上的身份。固定身份意味着可以被按身份识别、
    追溯、定向处理。每次发布换一个身份，各版本互不相干，也无法互相覆盖安装。

注意：
    随机包名 = 全新应用。装新版本后无障碍服务、Root 授权、规则配置全部要重新配，
    旧版本不会自动升级。这是这个设计的直接代价，不是 bug。
"""

from __future__ import annotations

import argparse
import os
import random
import re
import shutil
import string
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASES = ROOT / "releases"
SIGN_DIR = ROOT / "build" / "random-signing"

# 包名分段不能用 Java 关键字，首字符必须是字母
RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
    "null", "var", "record", "sealed", "permits", "yield",
}

LOWER = string.ascii_lowercase
ALNUM = string.ascii_lowercase + string.digits


def rand_segment(rng: random.Random, length: int) -> str:
    """生成一个可作包名分段的随机串：首字符字母，其余小写字母/数字，避开关键字。"""
    while True:
        head = rng.choice(LOWER)
        tail = "".join(rng.choice(ALNUM) for _ in range(length - 1))
        seg = head + tail
        if seg not in RESERVED:
            return seg


def rand_token(rng: random.Random, length: int) -> str:
    """密钥口令/别名用：纯字母数字，避开 shell 与 gradle 属性解析的特殊字符。"""
    return "".join(rng.choice(ALNUM) for _ in range(length))


def find_keytool() -> str:
    candidates = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool"))
    if os.name == "nt":
        candidates += [
            Path("C:/Program Files/Android/Android Studio/jbr/bin/keytool.exe"),
            Path("C:/Program Files/Java/jdk-21/bin/keytool.exe"),
        ]
    for c in candidates:
        if c.exists():
            return str(c)
    found = shutil.which("keytool")
    if found:
        return found
    sys.exit("找不到 keytool。装 JDK 17+，或设置 JAVA_HOME。")


def gradle_cmd() -> list[str]:
    if os.name == "nt":
        return ["cmd", "/c", str(ROOT / "gradlew.bat")]
    return [str(ROOT / "gradlew")]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-build", action="store_true", help="只生成身份，不构建")
    ap.add_argument("--seed", type=int, default=None, help="固定随机种子（调试用）")
    args = ap.parse_args()

    rng = random.Random(args.seed)

    # 1. 随机身份
    pkg = f"com.{rand_segment(rng, 8)}.{rand_segment(rng, 6)}"
    alias = rand_segment(rng, 8)
    store_pass = rand_token(rng, 28)
    key_pass = store_pass

    SIGN_DIR.mkdir(parents=True, exist_ok=True)
    for stale in SIGN_DIR.glob("*.jks"):
        stale.unlink()

    ks = SIGN_DIR / "release.jks"
    print(f"[1/4] 生成随机签名密钥 -> {ks.relative_to(ROOT)}")
    subprocess.run(
        [
            find_keytool(), "-genkeypair", "-noprompt",
            "-keystore", str(ks),
            "-storetype", "PKCS12",
            "-storepass", store_pass,
            "-keypass", key_pass,
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "3072",
            "-validity", "10000",
            "-dname", f"CN={alias}, OU=open, O=auto_skip, L=NA, ST=NA, C=CN",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.STDOUT,
    )

    print(f"[2/4] 随机包名 -> {pkg}")

    if args.no_build:
        print(f"      密钥库口令 {store_pass}")
        print(f"      别名 {alias} / 口令 {key_pass}")
        print("      已跳过构建（--no-build）")
        return 0

    # 2. 构建：applicationId 与签名全部走命令行属性，源码树不动
    print("[3/4] assembleRelease ...")
    cmd = gradle_cmd() + [
        "assembleRelease", "--quiet",
        f"-PappId={pkg}",
        f"-PksFile={ks}",
        f"-PksStorePass={store_pass}",
        f"-PksKeyAlias={alias}",
        f"-PksKeyPass={key_pass}",
    ]
    result = subprocess.run(cmd, cwd=ROOT)
    if result.returncode != 0:
        sys.exit("构建失败。完整日志：./gradlew assembleRelease 手动跑一次看报错。")

    apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.exists():
        sys.exit(f"构建成功了但没找到 {apk}")

    # 3. 落盘：releases/ 只保留最新一个，避免随机包名把目录堆满
    RELEASES.mkdir(exist_ok=True)
    for stale in RELEASES.glob("auto_skip-v*.apk"):
        stale.unlink()
    out = RELEASES / f"auto_skip-v{version_name()}-{pkg}.apk"
    shutil.copy2(apk, out)
    size_mb = out.stat().st_size / 1024 / 1024
    print(f"[4/4] {out.relative_to(ROOT)}  ({size_mb:.2f} MB)")
    print(f"      applicationId = {pkg}")
    print(f"      签名口令/别名见上方生成值（不入库，下次发布即换新）")
    return 0


def version_name() -> str:
    """从 app/build.gradle.kts 里读 versionName，避免两处写死。"""
    text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return m.group(1) if m else "0"


if __name__ == "__main__":
    raise SystemExit(main())
