#!/bin/bash

# init.sh — 用 ModMultiVersionTool 从 origin 模板重新生成各 1.x 版本目录
#
# 【26.x 保护（2026-09-06 落地，踩坑教训）】
# ModMultiVersionTool 源码实证（src/main/kotlin/Main.kt copyLoaderVersionFile）：
# 它会把 fabric/origin 下所有文件复制到 fabric/ 下**所有** fabric-* 版本目录，
# 排除条件只有目录名 != "origin"，无版本排除机制 → 26.x（独立 mojmap 项目）会被
# yarn 模板覆盖污染（gradle 配置、源码全部被毁，编译必挂）。
#   - CI 侧：build.yml 用 matrix.sh 的 independent 标记对 26.x 跳过 init.sh（安全）
#   - 本地侧：本脚本在跑工具前把 fabric/fabric-26.* 临时移出 fabric/，跑完移回
#     （python shutil.move 走 Windows MoveFileEx / POSIX rename，比 Git Bash mv 可靠；
#     try/finally 保证工具报错或中断也能恢复；移出失败则 fail-fast 中止，绝不静默污染）
# 注意：java -jar 不接受任何未知参数（会把参数当空气直接执行全量复制），勿传参。

file=$(find tool -name "ModMultiVersionTool*.jar" | head -n 1)

if [ -z "$file" ]; then
    echo "can't find ModMultiVersionTool.jar"
    exit 1
fi

python3 - "$file" <<'EOF'
import os, shutil, subprocess, sys

jar = sys.argv[1]
root = 'fabric'
targets = sorted(
    os.path.join(root, d) for d in os.listdir(root)
    if os.path.isdir(os.path.join(root, d)) and d.startswith('fabric-26.')
)

moved = []
try:
    for p in targets:
        tmp = p + '.tmp-moved'
        shutil.move(p, tmp)
        moved.append((tmp, p))
except Exception as e:
    for tmp, dst in moved:
        if os.path.isdir(tmp) and not os.path.isdir(dst):
            shutil.move(tmp, dst)
    print(f'[init.sh] 无法临时移出 26.x 目录，已中止（原因: {e}）')
    print('[init.sh] 请关闭占用 26.x 目录的程序（如 IDEA）后重试')
    sys.exit(1)

try:
    ret = subprocess.call(['java', '-jar', jar])
    if ret != 0:
        sys.exit(ret)
finally:
    for tmp, dst in moved:
        if os.path.isdir(tmp) and not os.path.isdir(dst):
            shutil.move(tmp, dst)
EOF
