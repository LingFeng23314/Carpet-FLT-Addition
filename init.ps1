# init.ps1 — 用 ModMultiVersionTool 从 origin 模板重新生成各 1.x 版本目录
# 与 init.sh 同逻辑（26.x 保护），PowerShell 版给本地 Windows 使用。
#
# 【26.x 保护（2026-09-06 落地，踩坑教训）】
# ModMultiVersionTool 会把 fabric/origin 下所有文件复制到 fabric/ 下所有 fabric-* 目录
# （源码实证：copyLoaderVersionFile 只排除 origin，无版本排除机制）→ 26.x（独立 mojmap
# 项目）会被 yarn 模板污染。CI 用 matrix.sh 的 independent 标记跳过 init.sh；本脚本在跑
# 工具前把 fabric/fabric-26.* 临时移出 fabric/，跑完移回（try/finally 保证中断也能恢复；
# 移出失败 fail-fast 中止）。注意 java -jar 不接受未知参数（会当空气直接全量复制）。

$file = Get-ChildItem -Path "tool" -Filter "ModMultiVersionTool*.jar" | Select-Object -First 1

if (-not $file) {
    Write-Output "can't find ModMultiVersionTool.jar"
    exit 1
}

$moved = @()
try {
    Get-ChildItem -Path "fabric" -Directory -Filter "fabric-26.*" | ForEach-Object {
        $tmp = "$($_.FullName).tmp-moved"
        Move-Item -LiteralPath $_.FullName -Destination $tmp
        $script:moved += ,@($tmp, $_.FullName)
    }
}
catch {
    foreach ($pair in $moved) {
        if (Test-Path -LiteralPath $pair[0]) { Move-Item -LiteralPath $pair[0] -Destination $pair[1] }
    }
    Write-Output "[init.ps1] 无法临时移出 26.x 目录，已中止（原因: $($_.Exception.Message)）"
    Write-Output "[init.ps1] 请关闭占用 26.x 目录的程序（如 IDEA）后重试"
    exit 1
}

try {
    java -jar $file.FullName
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    foreach ($pair in $moved) {
        if ((Test-Path -LiteralPath $pair[0]) -and -not (Test-Path -LiteralPath $pair[1])) {
            Move-Item -LiteralPath $pair[0] -Destination $pair[1]
        }
    }
}
