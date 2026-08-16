# verify-trace-span.ps1 - single-round verification for trace_id / retrieval span / eval writeback
param([string]$Base = "http://localhost:8081")
$ErrorActionPreference = 'Stop'
$base = $Base

# 1. login
$loginResp = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
$headers = @{ Authorization = "Bearer $token" }
Write-Host "[1] login ok"

# 2. pick a knowledge base (retrieval span needs one)
$kbId = $null
try {
    $kbs = Invoke-RestMethod -Uri "$base/api/knowledge-bases" -Headers $headers
    if ($kbs.data -and $kbs.data.Count -gt 0) { $kbId = $kbs.data[0].id }
} catch { Write-Host "kb list failed: $($_.Exception.Message)" }
Write-Host "[2] knowledgeBaseId = $kbId"

# 3. SSE job: start interview (with kb), answer Q1, wait for Q2 (round1 evaluated), then end
$job = Start-Job -ScriptBlock {
    param($token, $base, $kbId)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(8)

    function Post-Json($url, $obj) {
        $json = $obj | ConvertTo-Json -Compress
        $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $client.PostAsync($url, $content).Result | Out-Null
    }

    $answer = "HashMap uses array+bucket structure. Put computes hash to locate bucket, handles collision with linked list which turns into red-black tree when size >= 8. Resize doubles capacity at load factor 0.75. In concurrent scenario we should use ConcurrentHashMap instead."

    $startObj = @{ direction = "Java"; persona = "neutral"; durationMinutes = 5 }
    if ($kbId) { $startObj.knowledgeBaseId = $kbId }
    $startJson = $startObj | ConvertTo-Json -Compress
    $startContent = [System.Net.Http.StringContent]::new($startJson, [System.Text.Encoding]::UTF8, "application/json")
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/api/interviews/start")
    $req.Content = $startContent
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $reader = [System.IO.StreamReader]::new($resp.Content.ReadAsStreamAsync().Result)

    $sessionId = $null; $questionCount = 0
    $curEvent = ""; $curData = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 7) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); $curData = ""; continue }
        if ($line.StartsWith("data:")) {
            if ($curData.Length -gt 0) { $curData += " " }
            $curData += $line.Substring(5).Trim(); continue
        }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            switch ($curEvent) {
                "CONNECTED" { $sessionId = $curData; Write-Output "CONNECTED: $sessionId" }
                "QUESTION" {
                    $questionCount++
                    if ($questionCount -eq 1) {
                        Write-Output "Q1 received, answering"
                        Post-Json "$base/api/interviews/$sessionId/answer" @{ answer = $answer }
                    } else {
                        Write-Output "Q2 received -> round1 evaluated, ending interview"
                        Post-Json "$base/api/interviews/$sessionId/end" @{}
                        Write-Output "SUMMARY sessionId=$sessionId questions=$questionCount"
                        break
                    }
                }
                "FOLLOW_UP" {
                    Write-Output "FOLLOW_UP received, answering"
                    Post-Json "$base/api/interviews/$sessionId/answer" @{ answer = $answer }
                }
                "ERROR" { Write-Output "ERROR: $curData" }
            }
            $curEvent = ""
            if ($questionCount -ge 2) { break }
        }
    }
    if ($questionCount -lt 2) { Write-Output "SUMMARY sessionId=$sessionId questions=$questionCount TIMEOUT_OR_INCOMPLETE" }
} -ArgumentList $token, $base, $kbId

$deadline = (Get-Date).AddMinutes(8)
$jobLines = @()
while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $out = Receive-Job $job
    if ($out) { $jobLines += $out; $out | ForEach-Object { Write-Host $_ } }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $jobLines += $out; $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue

# 4. verify llm_trace rows via observability API
$summary = $jobLines | Select-String "sessionId=(\S+)" | Select-Object -Last 1
if (-not $summary) { Write-Host "VERIFY FAILED: no sessionId"; exit 1 }
$sid = $summary.Matches[0].Groups[1].Value
Start-Sleep -Seconds 3   # allow async writer to flush
$traces = (Invoke-RestMethod -Uri "$base/api/observability/traces?sessionId=$sid" -Headers $headers).data
Write-Host "`n===== trace verification (session=$sid) ====="
Write-Host ("total rows: " + $traces.Count)
$withTraceId = @($traces | Where-Object { $_.traceId })
$retrievals = @($traces | Where-Object { $_.kind -eq 'retrieval' })
$withScore = @($traces | Where-Object { $null -ne $_.evalScore })
Write-Host ("rows with traceId: " + $withTraceId.Count)
Write-Host ("retrieval spans:  " + $retrievals.Count)
Write-Host ("rows with evalScore: " + $withScore.Count)
$groups = $withTraceId | Group-Object traceId
foreach ($g in $groups) {
    $agents = ($g.Group | ForEach-Object { $_.agent }) -join ','
    $score = ($g.Group | Where-Object { $null -ne $_.evalScore } | Select-Object -First 1).evalScore
    Write-Host ("round " + $g.Name + " -> [" + $agents + "] evalScore=" + $score)
}
$ok = ($withTraceId.Count -ge 2) -and ($withScore.Count -ge 1)
if ($kbId -and $retrievals.Count -eq 0) { Write-Host "WARN: kb mounted but no retrieval span (kb may have no vectors)" }
if ($ok) { Write-Host "VERIFY PASS" } else { Write-Host "VERIFY FAILED" }
