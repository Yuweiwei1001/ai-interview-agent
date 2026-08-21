# 定向测试：验证"下一题持久化"——SSE 全程断开，题目应能从 /sessions/{id} 轮询恢复
$ErrorActionPreference = 'Stop'
$base = "http://localhost:8081"

$loginResp = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
$headers = @{ Authorization = "Bearer $token" }
Write-Host "[*] 登录成功"

# 启动面试（流式读取，收到第2题后停止作答）
$job = Start-Job -ScriptBlock {
    param($token, $base)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(5)
    $startJson = @{ direction = "Java后端"; persona = "neutral"; durationMinutes = 15 } | ConvertTo-Json -Compress
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/api/interviews/start")
    $req.Content = [System.Net.Http.StringContent]::new($startJson, [System.Text.Encoding]::UTF8, "application/json")
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $reader = [System.IO.StreamReader]::new($resp.Content.ReadAsStreamAsync().Result)
    $longAnswer = "这是一段足够长的面试回答。我从原理、实现和应用场景三个层面进行了分析：首先原理上涉及核心机制的设计权衡；其次实现上要考虑并发安全和性能开销；最后在真实项目中我结合业务场景做过针对性优化，效果良好。"
    $sid = $null; $qCount = 0; $curEvent = ""; $curData = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 4) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); $curData = ""; continue }
        if ($line.StartsWith("data:")) { $curData += $line.Substring(5).Trim(); continue }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            $ts = (Get-Date).ToString("HH:mm:ss")
            switch ($curEvent) {
                "CONNECTED" { $sid = $curData; Write-Output "[$ts] CONNECTED: $sid" }
                "QUESTION" {
                    $qCount++
                    $preview = $curData.Substring(0, [Math]::Min(40, $curData.Length))
                    Write-Output "[$ts] QUESTION#${qCount}: $preview"
                    if ($qCount -eq 1) {
                        # 第1题正常作答
                        $json = @{ answer = $longAnswer } | ConvertTo-Json -Compress
                        $c = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
                        $client.PostAsync("$base/api/interviews/$sid/answer", $c).Result | Out-Null
                        Write-Output "[$ts]   -> 已回答第1题"
                    } else {
                        # 第2题：故意不回答，模拟 SSE 断流后前端轮询恢复
                        Write-Output "[$ts]   -> 第2题不回答（模拟SSE断流），等待轮询恢复"
                        Start-Sleep -Seconds 3
                        $resp2 = $client.GetAsync("$base/api/interviews/sessions/$sid").Result
                        $body2 = $resp2.Content.ReadAsStringAsync().Result
                        Write-Output "[$ts] REST-SESSION: $body2"
                        # 结束面试释放资源
                        $client.PostAsync("$base/api/interviews/$sid/end", $null).Result | Out-Null
                        Write-Output "[$ts]   -> 已结束面试释放资源"
                        $sw.Stop()
                        break
                    }
                }
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
    if ($out -match "REST-SESSION") { break }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Host "定向测试结束"
