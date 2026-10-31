#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从反编译源导出内置默认规则表。

来源（只读）：
  D:/apk_edit/auto_skip_jadx/sources/f2/v0.java          每包一条规则，值是手拼的 JSON 文本
  D:/apk_edit/auto_skip_apktool/res/values/strings.xml    R.string.X 的真实取值
  D:/apk_edit/auto_skip_apktool/res/values/arrays.xml     内置不生效名单 whitelist

产物：app/src/main/assets/default_rules.json

自检口径（不通过就非零退出，不允许“少几条也算过”）：
  1. put() 行数 == 规则条数，且每条都消费掉该行全部实参；
  2. 每个 key 必须能在 strings.xml 里解析出来，未知实参形式直接报错；
  3. disabled 条数 == arrays.xml 里 whitelist 的 <item> 数（写盘前再并入 FORCE_DISABLED）；
  4. 回放：把产物里的规则重新拼回旧版那种紧凑文本，与源码里的字面量逐字符比对。
"""
import json
import re
import sys
from xml.etree import ElementTree as ET

V0 = "D:/apk_edit/auto_skip_jadx/sources/f2/v0.java"
V0_SMALI = "D:/apk_edit/auto_skip_apktool/smali/f2/v0.smali"
STRINGS = "D:/apk_edit/auto_skip_apktool/res/values/strings.xml"
ARRAYS = "D:/apk_edit/auto_skip_apktool/res/values/arrays.xml"
OUT = "C:/Users/eagle/AndroidStudioProjects/auto_skip/app/src/main/assets/default_rules.json"

# 用户点名不处理的包：界面里没有需要点击的跳过按钮，直接从规则表挪进不生效名单
FORCE_DISABLED = ("com.tencent.mm", "com.eg.android.AlipayGphone")

NS = "{http://schemas.android.com/apk/res/android}"


def load_keys():
    tree = ET.parse(STRINGS)
    return {e.get("name"): (e.text or "") for e in tree.getroot() if e.tag == "string"}


def load_whitelist():
    tree = ET.parse(ARRAYS)
    for arr in tree.getroot():
        if arr.get("name") == "whitelist":
            return [i.text.strip() for i in arr if i.tag == "item" and i.text]
    raise SystemExit("arrays.xml 里没有 whitelist 数组")


def count_whitelist_items():
    """换一条解析路径（纯正则数原文），用来交叉验证 ElementTree 抓到的条数。"""
    raw = open(ARRAYS, encoding="utf-8").read()
    m = re.search(r'<string-array name="whitelist">.*?</string-array>', raw, re.S)
    if not m:
        raise SystemExit("arrays.xml 里找不到 whitelist 段")
    return len(re.findall(r"<item>[^<]+</item>", m.group(0)))


SMALI_CTOR = ".method public synthetic constructor <init>(Lhello/litiaotiao/app/MainActivity;)V"


def smali_rule_count():
    """
    外部锚点：dex 反出来的 smali 里，带 MainActivity 的那个构造函数中的 put 调用数。
    这是和 jadx 源码不同的一份产物，java 侧整行被吃掉只有靠它能发现。
    """
    raw = open(V0_SMALI, encoding="utf-8").read()
    idx = raw.find(SMALI_CTOR)
    if idx < 0:
        raise SystemExit("smali 里找不到规则构造函数，锚点无法建立: %s" % V0_SMALI)
    return raw[idx:].count("->put(")


def split_args(text):
    """按顶层逗号切分实参，字符串字面量里的逗号不算分隔符。"""
    args, buf, depth, in_str, esc = [], "", 0, False, False
    for ch in text:
        if in_str:
            buf += ch
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            buf += ch
        elif ch in "([{":
            depth += 1
            buf += ch
        elif ch in ")]}":
            depth -= 1
            buf += ch
        elif ch == "," and depth == 0:
            args.append(buf.strip())
            buf = ""
        else:
            buf += ch
    if buf.strip():
        args.append(buf.strip())
    return args


PLACEHOLDER = re.compile(r"%[sdb]")
PUT_LINE = re.compile(r'^\s*put\("([^"]+)",\s*mainActivity\.s\("(.*?)"\s*,(.*)\)\);\s*$')
BOOL_LINE = re.compile(r"^\s*Boolean (bool\d?) = Boolean\.(TRUE|FALSE);\s*$")
# 机型提示文案那一批：put("huawei", "...")，不是规则，允许不匹配 PUT_LINE
HINT_LINE = re.compile(r'^\s*put\("[^"]+",\s*".*"\);\s*$')


def parse_literal(arg, keys):
    """把一个实参转成 Python 值；返回 (值, 该值在旧版文本里的原样串)。"""
    m = re.match(r"^mainActivity\.getString\(R\.string\.(\w+)\)$", arg)
    if m:
        name = m.group(1)
        if name not in keys:
            raise SystemExit("R.string.%s 在 strings.xml 里找不到" % name)
        return keys[name], keys[name]
    m = re.match(r"^Boolean (bool\d?)$", arg)
    if m:
        raise SystemExit("变量 %s 未在此前声明" % m.group(1))
    if arg in ("true", "false"):
        return arg == "true", arg
    if re.match(r"^-?\d+$", arg):
        return int(arg), arg
    if len(arg) >= 2 and arg[0] == '"' and arg[-1] == '"':
        body = arg[1:-1]
        return decode_bracketed(body), body
    raise SystemExit("无法识别的实参: %r" % arg)


def decode_bracketed(body):
    """旧版把数组写成 [a,b] / [[0&1,1&1]] / [{id:x,action:y}]，这里还原成真正的 JSON。"""
    if body == "[]":
        return []
    if body.startswith("[[") and body.endswith("]]"):
        inner = body[2:-2]
        return [[t.strip() for t in group.split(",")] for group in inner.split("],[")]
    if body.startswith("[{") and body.endswith("}]"):
        objs = []
        for chunk in re.findall(r"\{([^{}]*)\}", body):
            o = {}
            for pair in chunk.split(","):
                k, _, v = pair.partition(":")
                o[k.strip()] = v.strip()
            objs.append(o)
        return objs
    items = [t.strip() for t in body[1:-1].split(",")]
    if all(re.match(r"^-?\d+$", t) for t in items):
        return [int(t) for t in items]
    return items


def canon(value):
    """把解析出来的值转成旧版文本里那种紧凑写法，用于回放比对。"""
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        if not value:
            return "[]"
        if isinstance(value[0], list):
            return "[" + ",".join("[" + ",".join(g) + "]" for g in value) + "]"
        if isinstance(value[0], dict):
            return "[" + ",".join("{" + ",".join("%s:%s" % kv for kv in o.items()) + "}" for o in value) + "]"
        return "[" + ",".join(str(v) for v in value) + "]"
    return str(value)


def main():
    keys = load_keys()
    text = open(V0, encoding="utf-8").read()
    lines = text.splitlines()

    booleans = {}
    rules = {}
    order = []
    put_total = 0
    hint_total = 0
    replays = []

    for line in lines:
        m = BOOL_LINE.match(line)
        if m:
            booleans[m.group(1)] = m.group(2) == "TRUE"
            continue
        if HINT_LINE.match(line):
            hint_total += 1
            continue
        m = PUT_LINE.match(line)
        if not m:
            continue
        pkg, fmt, rest = m.group(1), m.group(2), m.group(3)
        put_total += 1
        args = split_args(rest)
        placeholders = PLACEHOLDER.findall(fmt)
        if len(args) != len(placeholders):
            raise SystemExit("%s: 占位符 %d 个但实参 %d 个" % (pkg, len(placeholders), len(args)))
        if len(args) % 2 != 0:
            raise SystemExit("%s: 实参不是成对的 key/value" % pkg)
        rule = {}
        for i in range(0, len(args), 2):
            key, _ = parse_literal(args[i], keys)
            if args[i + 1] in booleans:
                value, value_raw = booleans[args[i + 1]], ("true" if booleans[args[i + 1]] else "false")
            else:
                value, value_raw = parse_literal(args[i + 1], keys)
            if not isinstance(key, str) or not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", key):
                raise SystemExit("%s: 第 %d 个实参不是字段名，得到 %r（key/value 位置可能互换了）" % (pkg, i, key))
            if key in rule:
                raise SystemExit("%s: 字段 %s 重复" % (pkg, key))
            rule[key] = value
            # 回放：还原出来的值必须和源码里的字面量一字不差
            if canon(value) != value_raw:
                raise SystemExit("%s: 回放不一致 %r vs %r" % (pkg, canon(value), value_raw))
        if pkg in rules:
            raise SystemExit("%s: 包名重复出现" % pkg)
        rules[pkg] = rule
        order.append(pkg)
        replays.append(pkg)

    # 构造函数里第一条 v0() 是机型提示文案，不是规则；它不带 mainActivity.s()，已不被 PUT_LINE 命中
    disabled = load_whitelist()
    raw_items = count_whitelist_items()
    if len(disabled) != raw_items:
        raise SystemExit("whitelist 条数不符: 解析 %d vs 原文 %d" % (len(disabled), raw_items))
    if len(set(disabled)) != len(disabled):
        raise SystemExit("whitelist 里有重复包名")
    if put_total != len(rules):
        raise SystemExit("put 行数 %d != 规则条数 %d" % (put_total, len(rules)))
    # 锚点：源码里每一处 put(" 都要被上面三类之一解释掉，防止某个行格式被静默跳过
    anchors = text.count('put("')
    if anchors != put_total + hint_total:
        raise SystemExit(
            "锚点对不上: 全文 put(\" 共 %d，规则 %d + 提示 %d = %d，漏 %d 行"
            % (anchors, put_total, hint_total, put_total + hint_total, anchors - put_total - hint_total)
        )
    print("锚点: put( 全文 %d = 规则 %d + 提示 %d" % (anchors, put_total, hint_total))
    # 外部锚点：smali（dex 反出，另一份产物）里同一个构造函数的 put 调用数必须等于规则数
    smali_put = smali_rule_count()
    if smali_put != put_total:
        raise SystemExit("smali 锚点对不上: smali put=%d vs java 规则=%d" % (smali_put, put_total))
    print("外部锚点: smali put=%d == java 规则=%d" % (smali_put, put_total))

    # 2026-10-24 用户定：微信、支付宝没有需要点击的东西。它们在 v0.java 里的内置条目
    # 只有 {"keywords":[]}——旧版靠空数组让关键词任务自我取消，但包本身仍算生效。
    # 复刻把它们整个挪进不生效名单：条目消失，也不会因为"没规则"退化成默认词
    # 跳过/skip 跑去点微信首页。
    dropped = []
    for pkg in FORCE_DISABLED:
        if pkg in rules and rules[pkg] != {"keywords": []}:
            raise SystemExit("%s 的内置条目不止空 keywords，先人工确认再删" % pkg)
        if pkg in rules:
            rules.pop(pkg)
            order.remove(pkg)
            dropped.append(pkg)
        if pkg not in disabled:
            disabled.append(pkg)

    # 同一形状的其它条目：空数组把关键词任务自我取消，又没有任何其它命中来源，
    # 留着只是在生效列表里多一行死条目。判据按字段算，不写死包名。
    for pkg in list(order):
        v = rules[pkg]
        if v.get("keywords") != []:
            continue
        if any(k in v for k in ("keywords_append", "texts_task2", "DOCC", "popup_rules")):
            continue
        if v.get("filter_young_popups") or (v.get("task_type", 1) & 2):
            continue
        rules.pop(pkg)
        order.remove(pkg)
        dropped.append(pkg)
        if pkg not in disabled:
            disabled.append(pkg)
    print("挪进不生效名单: %d 条 %s" % (len(dropped), "、".join(dropped)))

    payload = {"rules": {pkg: rules[pkg] for pkg in order}, "disabled": disabled}
    with open(OUT, "w", encoding="utf-8", newline="\n") as fp:
        json.dump(payload, fp, ensure_ascii=False, indent=1, sort_keys=False)
        fp.write("\n")

    field_count = {}
    for rule in rules.values():
        for k in rule:
            field_count[k] = field_count.get(k, 0) + 1
    print("规则条数: %d" % len(rules))
    print("不生效名单: %d" % len(disabled))
    print("字段分布: " + ", ".join("%s=%d" % kv for kv in sorted(field_count.items(), key=lambda x: -x[1])))
    print("回放校验: %d 条逐字符通过" % len(replays))

    # 产物再读回来，确认条数与内容都没在写盘环节丢掉
    back = json.load(open(OUT, encoding="utf-8"))
    if len(back["rules"]) != len(rules) or len(back["disabled"]) != len(disabled):
        raise SystemExit("产物读回条数不符")
    for pkg in order:
        if back["rules"][pkg] != rules[pkg]:
            raise SystemExit("产物读回内容不符: %s" % pkg)
    # 旧版 f2.x 的写入顺序是「先铺不生效名单，再用规则表覆盖」，所以两边都出现的包，
    # 最终值是规则本身。FORCE_DISABLED 挪走之后这个交集必须为 0；不为 0 说明要么名单被
    # 上游改过，要么某个包同时有规则和"不该有规则"的判定。
    overlap = [p for p in disabled if p in back["rules"]]
    if overlap:
        raise SystemExit("名单与规则表仍有交集: %s" % overlap)
    print("名单与规则表交集: 0")
    print("产物读回: OK -> %s" % OUT)


if __name__ == "__main__":
    sys.exit(main())
