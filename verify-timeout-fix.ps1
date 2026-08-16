# verify-timeout-fix.ps1 - 验证单题超时自动结束面试（answer-timeout 配置为 1 分钟）
param([string]$Base = "http://localhost:8081")
$ErrorActionPreference = 'Stop'
$base = $Base

# 1. login
$loginResp = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
$headers = @{ Authorization = "Bearer $token" }
Write-Host "[1] login ok"

# 2. SSE job: start interview, receive Q1, DO NOT answer, wait for ANSWER_TIMEOUT/COMPLETE
$job = Start-Job -ScriptBlock {
    param($token, $base)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(6)

    $startJson = @{ direction = "Java"; persona = "neutral"; durationMinutes = 5 } | ConvertTo-Json -Compress
    $startContent = [System.Net.Http.StringContent]::new($startJson, [System.Text.Encoding]::UTF8, "application/json")
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/api/interviews/start")
    $req.Content = $startContent
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $reader = [System.IO.StreamReader]::new($resp.Content.ReadAsStreamAsync().Result)

    $sessionId = $null; $gotQuestion = $false; $gotTimeout = $false; $gotComplete = $false; $pings = 0
    $curEvent = ""; $curData = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 5) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith(":")) { $pings++; continue }  # 心跳注释帧
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); $curData = ""; continue }
        if ($line.StartsWith("data:")) { $curData += $line.Substring(5).Trim(); continue }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            switch ($curEvent) {
                "CONNECTED" { $sessionId = $curData; Write-Output "CONNECTED: $sessionId" }
                "QUESTION"  { $gotQuestion = $true; Write-Output "Q1 received, NOT answering (waiting timeout)" }
                "ANSWER_TIMEOUT" { $gotTimeout = $true; Write-Output "ANSWER_TIMEOUT received: $curData" }
                "REPORT_READY"   { Write-Output "REPORT_READY received" }
                "COMPLETE"  { $gotComplete = $true; Write-Output "COMPLETE received: $curData" }
                "ERROR"     { Write-Output "ERROR: $curData" }
            }
            $curEvent = ""
            if ($gotComplete) { break }
        }
    }
    Write-Output "HEARTBEAT pings=$pings"
    Write-Output "SUMMARY sessionId=$sessionId question=$gotQuestion timeout=$gotTimeout complete=$gotComplete"
} -ArgumentList $token, $base

$jobLines = @()
$deadline = (Get-Date).AddMinutes(5)
while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $out = Receive-Job $job
    if ($out) { $jobLines += $out; $out | ForEach-Object { Write-Host $_ } }
    # 提前退出：COMPLETE 已收到
    if ($jobLines -match "COMPLETE received") { Start-Sleep -Seconds 3; break }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $jobLines += $out; $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue

# 3. verify session status + late answer rejection
$summary = $jobLines | Select-String "sessionId=(\S+)" | Select-Object -Last 1
if (-not $summary) { Write-Host "VERIFY FAILED: no sessionId"; exit 1 }
$sid = $summary.Matches[0].Groups[1].Value
$session = Invoke-RestMethod -Uri "$base/api/interviews/sessions/$sid" -Headers $headers
Write-Host "session status: $($session.data.status)"

# 迟到回答：应被后端拒绝（无等待中的问题），会话状态不变
Invoke-RestMethod -Uri "$base/api/interviews/$sid/answer" -Method Post -Headers $headers -ContentType "application/json" -Body '{"answer":"late answer after timeout"}' | Out-Null
Start-Sleep -Seconds 2
$session2 = Invoke-RestMethod -Uri "$base/api/interviews/sessions/$sid" -Headers $headers
Write-Host "status after late answer: $($session2.data.status)"

Write-Host "`n===== verification ====="
$ok = ($jobLines -match "timeout=True") -and ($jobLines -match "complete=True") -and ($session.data.status -eq 'interrupted') -and ($session2.data.status -eq 'interrupted')
$hb = $jobLines | Select-String "pings=(\d+)"
if ($hb -and [int]$hb.Matches[0].Groups[1].Value -gt 0) { Write-Host "heartbeat: OK ($($hb.Matches[0].Groups[1].Value) pings)" } else { Write-Host "heartbeat: WARN no ping observed (timeout may fire before first 15s tick)" }
if ($ok) { Write-Host "VERIFY PASS" } else { Write-Host "VERIFY FAILED" }
