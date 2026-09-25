#!/usr/bin/env python3
"""校验 FLTSettings 中每条 @Rule 规则在 lang 文件中都有 .name / .desc 键。

背景：carpet 的 ParsedRule 在解析规则时要求 lang 提供 carpet.rule.<规则名>.name/.desc，
缺失会在启动时抛 NullPointerException（No language key provided for ...）直接崩游戏。
改规则名/加规则时最容易漏改 lang，本脚本用于提交前自检。

用法：python scripts/check_rule_lang.py
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 自动扫描 fabric/ 下所有版本目录（1.x + 26.x），排除 origin（无 FLTSettings）。
VERSIONS = sorted(
    [d for d in os.listdir(os.path.join(ROOT, "fabric"))
     if d.startswith("fabric-") and d != "fabric-origin"],
    key=lambda s: [int(x) for x in s[len("fabric-"):].split(".")],
)
LANGS = ["zh_cn", "en_us"]

failed = False
for ver in VERSIONS:
    settings = os.path.join(
        ROOT, "fabric", ver, "src/main/java/com/flt/carpetaddition/settings/FLTSettings.java")
    if not os.path.isfile(settings):
        print(f"[skip] {ver}: FLTSettings.java not found")
        continue
    src = open(settings, encoding="utf-8").read()
    # 匹配规则字段：@Rule(...) 后面（可隔条件块注释 // IF / // END IF / //# 等）紧跟 public static 字段。
    # 用 DOTALL 让 . 匹配换行，非贪婪到最近的 public static 字段声明。
    rules = re.findall(
        r"@Rule\(.*?\)\s*?(?://[^\n]*\n\s*)*?public\s+static\s+\S+\s+(\w+)\s*=",
        src, re.DOTALL)

    for lang in LANGS:
        p = os.path.join(
            ROOT, "fabric", ver,
            "src/main/resources/assets/carpet-flt-addition/lang", lang + ".json")
        data = json.load(open(p, encoding="utf-8"))
        missing = [
            f"carpet.rule.{rule}{suffix}"
            for rule in rules
            for suffix in (".name", ".desc")
            if f"carpet.rule.{rule}{suffix}" not in data
        ]
        if missing:
            failed = True
            print(f"[FAIL] {ver} / {lang}: {len(rules)} rules, missing {missing}")
        else:
            print(f"[ OK ] {ver} / {lang}: {len(rules)} rules, all keys present")

sys.exit(1 if failed else 0)
