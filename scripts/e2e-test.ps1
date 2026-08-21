# E2E 面试流程测试（单 Job 流式处理：读到事件立即响应，无文件中转）
param([string]$Base = "http://localhost:8080")
$ErrorActionPreference = 'Stop'
$base = $Base

# 1. 登录
$loginResp = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
Write-Host "[1] 登录成功"

# 2. 单 Job：SSE 流式读取 + 自动答题/交代码
$job = Start-Job -ScriptBlock {
    param($token, $base)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(14)

    function Post-Json($url, $obj) {
        $json = $obj | ConvertTo-Json -Compress
        $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $client.PostAsync($url, $content).Result | Out-Null
    }

    $longAnswer = "这是一段足够长的面试回答。我从原理、实现和应用场景三个层面进行了分析：首先原理上涉及核心机制的设计权衡；其次实现上要考虑并发安全和性能开销；最后在真实项目中我结合业务场景做过针对性优化，效果良好。"
    $badCode = @'
import java.util.*;
public class Solution {
    public static void main(String[] args) {
        System.out.println("0");
    }
}
'@
    $retryCode = @'
import java.util.*;
public class Solution {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) { System.out.println(sc.nextLine()); }
    }
}
'@

    $startJson = @{ direction = "Java后端"; persona = "neutral"; durationMinutes = 15 } | ConvertTo-Json -Compress
    $startContent = [System.Net.Http.StringContent]::new($startJson, [System.Text.Encoding]::UTF8, "application/json")
    # SSE 流永不结束，必须 ResponseHeadersRead 立即返回（PostAsync 默认等全部内容会永久阻塞）
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/api/interviews/start")
    $req.Content = $startContent
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $stream = $resp.Content.ReadAsStreamAsync().Result
    $reader = [System.IO.StreamReader]::new($stream)

    $sessionId = $null; $codeSubmitCount = 0; $questionCount = 0; $followUpCount = 0
    $curEvent = ""; $curData = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 12) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); $curData = ""; continue }
        if ($line.StartsWith("data:")) {
            if ($curData.Length -gt 0) { $curData += " " }
            $curData += $line.Substring(5).Trim(); continue
        }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            $ts = (Get-Date).ToString("HH:mm:ss")
            $preview = $curData.Substring(0, [Math]::Min(70, $curData.Length))
            switch ($curEvent) {
                "CONNECTED" { $sessionId = $curData; Write-Output "[$ts] CONNECTED: $sessionId" }
                "THINKING"  { Write-Output "[$ts] THINKING" }
                "QUESTION" {
                    $questionCount++
                    Write-Output "[$ts] 问答题#${questionCount}: $preview"
                    Post-Json "$base/api/interviews/$sessionId/answer" @{ answer = $longAnswer }
                    Write-Output "[$ts]   -> 已提交回答"
                }
                "FOLLOW_UP" {
                    $followUpCount++
                    Write-Output "[$ts] 追问(预期0次!): $preview"
                    Post-Json "$base/api/interviews/$sessionId/answer" @{ answer = $longAnswer }
                }
                "WAITING_CODE" {
                    $codeSubmitCount++
                    if ($codeSubmitCount -eq 1) {
                        Write-Output "[$ts] 编程题: $preview"
                        Write-Output "[$ts]   -> 提交错误代码（预期:低分->重试提示）"
                        Post-Json "$base/api/coding/submit/$sessionId" @{ code = $badCode; language = "java" }
                    } else {
                        Write-Output "[$ts] 重试提示: $preview"
                        Write-Output "[$ts]   -> 重新提交代码（预期:评估后继续流程）"
                        Post-Json "$base/api/coding/submit/$sessionId" @{ code = $retryCode; language = "java" }
                    }
                }
                "CODE_SUBMITTED" { Write-Output "[$ts] CODE_SUBMITTED" }
                "REPORT_READY"   { Write-Output "[$ts] REPORT_READY" }
                "COMPLETE"       { Write-Output "[$ts] COMPLETE: $curData" }
                "ERROR"          { Write-Output "[$ts] ERROR: $curData" }
            }
            $curEvent = ""
            if ($curEvent -eq "COMPLETE") { break }
        }
    }
    Write-Output "SUMMARY sessionId=$sessionId 问答题=$questionCount 追问=$followUpCount 代码提交=$codeSubmitCount"
} -ArgumentList $token, $base

# 3. 增量输出 Job 日志
$deadline = (Get-Date).AddMinutes(13)
while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $out = Receive-Job $job
    if ($out) { $out | ForEach-Object { Write-Host $_ } }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue

# 4. 拉报告验证
$summary = $out | Select-String "SUMMARY sessionId=(\S+)" | Select-Object -Last 1
if ($summary -and $summary.Matches[0].Groups[1].Value -ne "null") {
    $sid = $summary.Matches[0].Groups[1].Value
    Write-Host "`n===== 最终验证 ====="
    $session = Invoke-RestMethod -Uri "$base/api/interviews/sessions/$sid" -Headers @{ Authorization = "Bearer $token" }
    Write-Host "会话状态: $($session.data.status) | 总分: $($session.data.overallScore)"
    try {
        $report = Invoke-RestMethod -Uri "$base/api/interviews/sessions/$sid/report" -Headers @{ Authorization = "Bearer $token" }
        Write-Host "报告总分: $($report.data.overallScore) | 逐题反馈数: $($report.data.perQuestionFeedback.Count)"
    } catch { Write-Host "报告获取: $($_.Exception.Message)" }
}
Write-Host "测试结束"
