# 把下载下来的分卷拼回一个完整的 apk。
# 用法：把本脚本和所有 .partNN 文件放在同一个目录，然后运行：
#   powershell -ExecutionPolicy Bypass -File .\join-apk-parts.ps1
# 默认输出 dsh-pet-v2.6.2-v108.apk

$ErrorActionPreference = 'Stop'

$outName = 'dsh-pet-v2.6.2-v108.apk'
$expectedSha256 = 'b7b4a4786edc73bb5c11b3996e1ce3334941719c92caec4819c69c3d6e94dec0'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$parts = Get-ChildItem -LiteralPath $here -Filter '*.apk.part*' | Sort-Object Name
if (-not $parts) { throw '当前目录下没找到 .apk.partXX 分卷文件。' }
if ($parts.Count -ne 10) { Write-Warning "分卷数量是 $($parts.Count)，预期 10 个，先确认是不是有一卷没下完。" }

$outPath = Join-Path $here $outName
$out = [IO.File]::Create($outPath)
try {
    foreach ($p in $parts) {
        Write-Host "合并 $($p.Name)  ($([math]::Round($p.Length/1MB,1)) MB)"
        $in = [IO.File]::OpenRead($p.FullName)
        try { $in.CopyTo($out) } finally { $in.Close() }
    }
} finally { $out.Close() }

$size = (Get-Item -LiteralPath $outPath).Length
Write-Host "输出：$outPath  $([math]::Round($size/1MB,1)) MB"

$hash = (Get-FileHash -LiteralPath $outPath -Algorithm SHA256).Hash.ToLower()
if ($hash -eq $expectedSha256) {
    Write-Host 'SHA-256 对上了，可以直接装。'
} else {
    Write-Warning "SHA-256 不一致：$hash"
    Write-Warning "多半是某一卷下坏了，重新下一遍出问题的那一卷。"
}
