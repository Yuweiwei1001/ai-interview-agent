# 验证：统计 QUESTION_DELTA 事件数量，实证流式输出是否生效
param([string]$Base = "http://localhost:8080")
$ErrorActionPreference = 'Stop'

$loginResp = Invoke-RestMethod -Uri "$Base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
Write-Host "[*] 登录成功, base=$Base"

$job = Start-Job -ScriptBlock {
    param($token, $base)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(5)
    $startJson = @{ direction = "Java后端"; persona = "gentle"; durationMinutes = 15 } | ConvertTo-Json -Compress
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/api/interviews/start")
    $req.Content = [System.Net.Http.StringContent]::new($startJson, [System.Text.Encoding]::UTF8, "application/json")
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $reader = [System.IO.StreamReader]::new($resp.Content.ReadAsStreamAsync().Result)
    $longAnswer = "这是一段足够长的面试回答。我从原理、实现和应用场景三个层面进行了分析：首先原理上涉及核心机制的设计权衡；其次实现上要考虑并发安全和性能开销；最后在真实项目中我结合业务场景做过针对性优化，效果良好。"
    $sid = $null; $qCount = 0; $curEvent = ""; $curData = ""
    $deltaCount = 0; $questionCount = 0; $answered = $false
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 4) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); continue }
        if ($line.StartsWith("data:")) { $curData += $line.Substring(5).Trim(); continue }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            switch ($curEvent) {
                "CONNECTED" { $sid = $curData; Write-Output "CONNECTED: $sid" }
                "QUESTION_DELTA" { $deltaCount++; if ($deltaCount -eq 1 -or $deltaCount % 10 -eq 0) { Write-Output "  delta#${deltaCount}: $($curData.Substring(0, [Math]::Min(30, $curData.Length)))" } }
                "QUESTION" {
                    $questionCount++
                    $preview = $curData.Substring(0, [Math]::Min(40, $curData.Length))
                    Write-Output "QUESTION#$questionCount (delta累计=$deltaCount): $preview"
                    if ($questionCount -eq 1 -and -not $answered) {
                        $answered = $true
                        $json = @{ answer = $longAnswer } | ConvertTo-Json -Compress
                        $c = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
                        $client.PostAsync("$base/api/interviews/$sid/answer", $c).Result | Out-Null
                        Write-Output "  -> 已回答第1题，等待第2题流式..."
                    } elseif ($questionCount -ge 2) {
                        Write-Output "=== 验证完成: 第$questionCount题 QUESTION_DELTA 总计数=$deltaCount ==="
                        $client.PostAsync("$base/api/interviews/$sid/end", $null).Result | Out-Null
                        $sw.Stop()
                        break
                    }
                }
                "THINKING" { Write-Output "THINKING" }
            }
            $curEvent = ""
        }
    }
} -ArgumentList $token, $base

$deadline = (Get-Date).AddMinutes(4)
while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    $out = Receive-Job $job
    if ($out) { $out | ForEach-Object { Write-Host $_ } }
    if ($out -match "验证完成") { break }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Host "验证结束"
