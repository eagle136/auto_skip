# -*- coding: utf-8 -*-
"""回放校验：关键词/Texts2 两条任务在 default_rules.json 上的分支归属。

判据来自旧版反编译源码，逐条对齐：
  LttService.f 构造：getJSONArray(keywords) 成功（含空数组）=> 只用数组里的词；
                     抛异常 => keywords_append 全部 + 跳过/skip（非 zh_CN 再加 跳過）；
                     big_smart_pg 为真额外加 count_down/countdown；
                     词集为空 => c=true，任务自我取消。
  LttService.e 构造：optJSONArray(texts_task2) 为 null => 跳过 + id:skip；否则只取非空串。
  派发：task_type 缺省 1，bit1 起 f、bit2 起 e。

取证对象是打进 APK 的那份 assets/default_rules.json，不是仓库里的源文件。
按 zh_CN 回放：非 zh_CN 设备旧版会多一个「跳過」，不改变分支归属。
`--negctl` 用 6 个变异（含本次修掉的真实偏差）反证校验器逐项 FAIL、不崩栈。
"""
import json
import sys
import zipfile

APK = r"app/build/outputs/apk/release/app-release.apk"

DEFAULT_KEYWORDS_ZH_CN = ["跳过", "skip"]
DEFAULT_TEXTS2 = ["跳过", "id:skip"]

# v0.java 里有、但按用户要求不搬进规则表的 6 个包（空转条目：keywords 是空数组且无其它命中来源）
MOVED_OUT = [
    "com.tencent.mm", "com.eg.android.AlipayGphone",
    "io.legado.app.release", "io.legado.app.debug",
    "io.legado.play.release", "io.legado.play.debug",
]

MUTS = {
    "empty_fallback": "keywords 是空数组时也补默认词（本次修掉的真实偏差）",
    "no_defaults": "keywords 缺失时不补默认词",
    "ignore_keywords": "无视自带 keywords，一律走缺省分支",
    "smart_pg_dropped": "big_smart_pg 不生效",
    "texts2_empty_default": "texts_task2 是空数组时也补默认词",
    "texts2_keep_blank": "texts_task2 不过滤空串",
}


def keyword_words(rule, mut=None):
    """返回 (词表, 是否自我取消)。严格照旧版的 try/catch 分界。"""
    m = mut or ()
    words = []
    if rule.get("big_smart_pg") is True and "smart_pg_dropped" not in m:
        words += ["count_down", "countdown"]
    kw = rule.get("keywords")
    has_array = isinstance(kw, list)
    # 旧版只有「取不到数组」才退化；空数组照样取到了
    use_default = not has_array
    if "ignore_keywords" in m:
        use_default = True
    if "empty_fallback" in m and has_array and len(kw) == 0:
        use_default = True
    if use_default:
        ap = rule.get("keywords_append")
        if isinstance(ap, list):
            words += [str(x) for x in ap]
        if "no_defaults" not in m:
            words += DEFAULT_KEYWORDS_ZH_CN
    else:
        words += [str(x) for x in kw]
    seen = set()
    out = [w for w in words if not (w in seen or seen.add(w))]
    return out, len(out) == 0


def texts2_targets(rule, mut=None):
    m = mut or ()
    t = rule.get("texts_task2")
    if t is None:
        return list(DEFAULT_TEXTS2)
    if not isinstance(t, list):
        return list(DEFAULT_TEXTS2)
    if "texts2_empty_default" in m and len(t) == 0:
        return list(DEFAULT_TEXTS2)
    if "texts2_keep_blank" in m:
        return [str(x) for x in t]
    return [str(x) for x in t if str(x) != ""]


FIXTURES = [
    ({"big_smart_pg": True, "keywords_append": ["x"]},
     ["count_down", "countdown", "x", "跳过", "skip"], "kw"),
    ({"big_smart_pg": True}, ["count_down", "countdown", "跳过", "skip"], "kw"),
    ({"keywords": [" 只认这个 "]}, [" 只认这个 "], "kw"),
    ({"keywords": []}, [], "cancel"),
    ({"texts_task2": ["A", "", "B"]}, ["A", "B"], "t2"),
    ({"texts_task2": []}, [], "t2"),
]


def replay(rules, mut=()):
    """按旧版分界跑一遍全部规则，返回分类计数与逐包判定列表。"""
    stats = dict(keyword_defaults=0, keyword_exact=0, keyword_cancelled=0,
                 texts2_defaults=0, texts2_exact=0)
    cancelled_pkgs, exact_pkgs, smart_pkgs = [], [], []
    notes = []

    for pkg, rule in rules.items():
        task_type = rule.get("task_type", 1)
        if task_type & 1:
            words, self_cancel = keyword_words(rule, mut)
            kw = rule.get("keywords")
            if isinstance(kw, list) and kw:
                stats["keyword_exact"] += 1
                exact_pkgs.append(pkg)
                notes.append((all(w in words for w in kw), "%s 自带词全部保留" % pkg))
                notes.append(("跳过" not in words, "%s 自带词没被掺入默认词" % pkg))
            elif self_cancel:
                stats["keyword_cancelled"] += 1
                cancelled_pkgs.append(pkg)
            else:
                stats["keyword_defaults"] += 1
                notes.append((("跳过" in words) and ("skip" in words),
                              "%s 缺省分支补到默认词" % pkg))
                ap = rule.get("keywords_append")
                if isinstance(ap, list):
                    notes.append((all(w in words for w in ap),
                                  "%s 追加词全部保留" % pkg))
        if rule.get("big_smart_pg") is True:
            smart_pkgs.append(pkg)
        if task_type & 2:
            got = texts2_targets(rule, mut)
            notes.append((bool(got), "%s texts2 目标非空" % pkg))
            stats["texts2_defaults" if rule.get("texts_task2") is None else "texts2_exact"] += 1
    return stats, cancelled_pkgs, exact_pkgs, smart_pkgs, notes


def run_checks(rules, disabled, mut=(), quiet=False):
    result = []

    def check(name, cond, detail=""):
        result.append((bool(cond), name, detail))

    stats, cancelled_pkgs, exact_pkgs, smart_pkgs, notes = replay(rules, mut)
    for passed, name in notes:
        check(name, passed)

    check("规则总数=155（v0 的 161 条里，6 条空转条目按用户要求挪进不生效名单）",
          len(rules) == 155, "实际 %d" % len(rules))
    check("不生效名单=113（whitelist 108 + 微信/支付宝 + legado 4 个变体，去重后）",
          len(disabled) == 113, "实际 %d" % len(disabled))
    check("微信/支付宝/legado 既不在规则表也不重复出现在名单",
          not any(p in rules for p in MOVED_OUT)
          and all(disabled.count(p) == 1 for p in MOVED_OUT),
          str([p for p in MOVED_OUT if disabled.count(p) != 1 or p in rules]))
    for k in ("keyword_defaults", "keyword_exact", "texts2_defaults"):
        check("%s 非零" % k, stats[k] > 0, "%d" % stats[k])
    check("表内已无空 keywords 条目", cancelled_pkgs == [] and stats["keyword_cancelled"] == 0,
          str(cancelled_pkgs))
    check("自带关键词的 3 个包", exact_pkgs == ["com.fusionmedia.investing",
                                                "com.crypto.currency",
                                                "periodtracker.pregnancy.ovulationtracker"],
          str(exact_pkgs))
    check("没有任何规则带 big_smart_pg", not smart_pkgs, str(smart_pkgs))
    check("task_type 带 bit2 的规则数=8",
          sum(1 for r in rules.values() if r.get("task_type", 1) & 2) == 8)
    check("task_type 带 bit1 的规则数=147",
          sum(1 for r in rules.values() if r.get("task_type", 1) & 1) == 147)
    running = sum(1 for r in rules.values() if r.get("task_type", 1) & 1)
    check("三条 keywords 分支覆盖全部起关键词任务的规则",
          stats["keyword_defaults"] + stats["keyword_exact"] + stats["keyword_cancelled"] == running,
          "%d+%d+%d vs %d" % (stats["keyword_defaults"], stats["keyword_exact"],
                              stats["keyword_cancelled"], running))
    check("is_user_check 一条都没有（宽松匹配器覆盖全部）",
          not any("is_user_check" in r for r in rules.values()))
    check("8 条 bit2 规则都不带 texts_task2（v0.java 里也没写过这个键）",
          stats["texts2_exact"] == 0 and "texts_task2" not in json.dumps(rules),
          "defaults=%d exact=%d" % (stats["texts2_defaults"], stats["texts2_exact"]))

    for rule, want, kind in FIXTURES:
        label = "合成 %s" % json.dumps(rule, ensure_ascii=False)
        if kind == "kw":
            words, cancel = keyword_words(rule, mut)
            check(label, words == want and not cancel, str(words))
        elif kind == "cancel":
            check(label, keyword_words(rule, mut)[1], "自我取消位")
        else:
            check(label, texts2_targets(rule, mut) == want, str(texts2_targets(rule, mut)))

    ok = sum(1 for p, _, _ in result if p)
    fail = len(result) - ok
    if not quiet:
        for passed, name, detail in result:
            if not passed:
                print("FAIL %s %s" % (name, detail))
        print("检查 %d 项：通过 %d，失败 %d" % (len(result), ok, fail))
    return ok, fail, [n for p, n, _ in result if not p]


def main():
    with zipfile.ZipFile(APK) as z:
        data = json.loads(z.read("assets/default_rules.json").decode("utf-8"))
    rules, disabled = data["rules"], data["disabled"]

    if "--negctl" not in sys.argv:
        _, fail, _ = run_checks(rules, disabled)
        return 0 if fail == 0 else 1

    ok, fail, _ = run_checks(rules, disabled, quiet=True)
    print("基线：checks=%d ok=%d fail=%d" % (ok + fail, ok, fail))
    if fail:
        print("基线不干净，变异无从对照")
        return 1
    caught = 0
    for mut, desc in MUTS.items():
        _, f, names = run_checks(rules, disabled, mut=(mut,), quiet=True)
        if f == 0:
            print("漏网   %-20s %s" % (mut, desc))
        else:
            caught += 1
            print("抓到   %-20s 失败 %d 项，首个：%s" % (mut, f, names[0]))
    print("变异 %d 个：抓到 %d，漏 %d" % (len(MUTS), caught, len(MUTS) - caught))
    return 0 if caught == len(MUTS) else 1


if __name__ == "__main__":
    sys.exit(main())
