# 农心资料库采集脚本（Tier 1：官方行政性公开文件）
#
# 用法：
#   1) 把要采的页面地址按行写进 tools/harvest/urls.txt（# 开头为注释）
#   2) pwsh -File tools/harvest/fetch.ps1
#   产出：tools/harvest/staging/<序号>-<标题片段>.txt（含来源抬头），并打印汇总表
#
# 设计要点：
#   - 编码自动判定：先读页面声明的 charset，其次试 UTF-8，最后退回 GB18030
#     （政府网站编码不统一：广西是 UTF-8，很多站点是 GBK，这一步是踩过坑加的）
#   - 只采正文文本，不采图片；不抓取任何个人信息字段
#   - 暂存区与知识库分离：入库由人工确认后另行登记 sources.json
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$staging = Join-Path $root 'staging'
$urlFile = Join-Path $root 'urls.txt'
New-Item -ItemType Directory -Force $staging | Out-Null
if (-not (Test-Path $urlFile)) { throw "缺少 $urlFile（每行一个页面地址）" }

function Get-Html([string]$url) {
    $wc = New-Object System.Net.WebClient
    $wc.Headers.Add('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)')
    $bytes = $wc.DownloadData($url)
    $utf8 = [System.Text.Encoding]::UTF8.GetString($bytes)
    $declared = [regex]::Match($utf8, '(?i)charset\s*=\s*["'']?\s*([\w-]+)').Groups[1].Value
    if ($declared -match '(?i)gb|gbk|gb2312|gb18030') {
        return [System.Text.Encoding]::GetEncoding('GB18030').GetString($bytes)
    }
    # 没声明或声明 utf-8：用 UTF-8；若中文比例异常再退回 GB18030
    $cn = ([regex]::Matches($utf8, '[\u4e00-\u9fa5]')).Count
    if ($cn -lt 20 -and $bytes.Length -gt 2000) {
        return [System.Text.Encoding]::GetEncoding('GB18030').GetString($bytes)
    }
    return $utf8
}

function Get-Text([string]$html) {
    $body = $html -replace '(?s)<script.*?</script>', '' -replace '(?s)<style.*?</style>', '' `
        -replace '(?s)<!--.*?-->', '' -replace '(?i)<br\s*/?>', "`n" `
        -replace '(?i)</(p|div|li|h[1-6]|tr)>', "`n" -replace '<[^>]+>', ' ' `
        -replace '&nbsp;', ' ' -replace '&amp;', '&' -replace '&ldquo;|&rdquo;', '"' -replace '&mdash;', '—'
    ($body -split "`n" | ForEach-Object { ($_ -replace '\s+', ' ').Trim() } | Where-Object { $_.Length -gt 6 }) -join "`n"
}

$urls = Get-Content $urlFile -Encoding UTF8 | Where-Object { $_.Trim() -ne '' -and -not $_.Trim().StartsWith('#') }
$rows = @()
$i = 0
foreach ($url in $urls) {
    $i++
    $u = $url.Trim()
    try {
        $html = Get-Html $u
        $title = ([regex]::Match($html, '(?s)<title>(.*?)</title>')).Groups[1].Value.Trim()
        $text = Get-Text $html
        $slug = ($title -replace '[\\/:*?"<>|\s]', '-')
        if ($slug.Length -gt 40) { $slug = $slug.Substring(0, 40) }
        if ($slug -eq '') { $slug = "page-$i" }
        $out = Join-Path $staging ("{0:d2}-{1}.txt" -f $i, $slug)
        $header = "# 抓取来源：$u`n# 页面标题：$title`n# 抓取时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')`n" +
                  "# 用途：农心 Agent 资料库（行政性公开文件，入库需在 sources.json 登记机构/标题/日期/链接）`n"
        ($header + "`n" + $text) | Set-Content -Path $out -Encoding UTF8
        $rows += [pscustomobject]@{ 序号 = $i; 状态 = 'OK'; 字数 = $text.Length; 标题 = $title.Substring(0, [Math]::Min(34, $title.Length)) }
        Write-Output ("[{0}] OK  {1} 字  {2}" -f $i, $text.Length, $title)
    } catch {
        $rows += [pscustomobject]@{ 序号 = $i; 状态 = 'FAIL'; 字数 = 0; 标题 = $_.Exception.Message.Substring(0, [Math]::Min(34, $_.Exception.Message.Length)) }
        Write-Output ("[{0}] FAIL {1}" -f $i, $_.Exception.Message)
    }
}
Write-Output ''
Write-Output ("完成：{0} 篇成功 / {1} 篇失败；产物在 {2}" -f ($rows | Where-Object { $_.状态 -eq 'OK' }).Count, ($rows | Where-Object { $_.状态 -eq 'FAIL' }).Count, $staging)
