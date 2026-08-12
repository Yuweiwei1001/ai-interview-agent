# Verify fixed orchestration: technical -> project -> coding(last) + communication score
# 25min -> 5 questions (tech 2 + project 2 + coding 1)
param([string]$Base = "http://localhost:8080")
$ErrorActionPreference = 'Stop'

$loginResp = Invoke-RestMethod -Uri "$Base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
Write-Host "[*] login ok"

$badCode = @'
public class Solution {
    public static void main(String[] args) {
        System.out.println(0);
    }
}
'@

$job = Start-Job -ScriptBlock {
    param($token, $base, $badCode)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    $startJson = @{ direction = "Java后端"; persona = "neutral"; durationMinutes = 25 } | ConvertTo-Json -Compress
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/api/interviews/start")
    $req.Content = [System.Net.Http.StringContent]::new($startJson, [System.Text.Encoding]::UTF8, "application/json")
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $reader = [System.IO.StreamReader]::new($resp.Content.ReadAsStreamAsync().Result)
    $answer = "我从原理、实现和应用三个层面回答：原理上它通过核心机制解决关键问题，实现上要考虑并发安全与性能开销，实际项目中我结合业务场景做过优化，也踩过边界情况的坑，最后通过压测验证了方案的有效性。"
    $sid = $null; $curEvent = ""; $curData = ""
    $mainCount = 0; $submitCount = 0; $completed = $false
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    function Post-Answer($c, $b, $s, $text) {
        $json = @{ answer = $text } | ConvertTo-Json -Compress
        $ct = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $c.PostAsync("$b/api/interviews/$s/answer", $ct).Result | Out-Null
    }
    function Post-Code($c, $b, $s, $code) {
        $json = @{ code = $code; language = "java" } | ConvertTo-Json -Compress
        $ct = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $r = $c.PostAsync("$b/api/coding/submit/$s", $ct).Result
        Write-Output "  -> submit code status: $($r.StatusCode)"
    }

    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 14 -and -not $completed) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); continue }
        if ($line.StartsWith("data:")) { $curData += $line.Substring(5).Trim(); continue }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            switch ($curEvent) {
                "CONNECTED" { $sid = $curData; Write-Output "CONNECTED: $sid" }
                "QUESTION_DELTA" { }
                "QUESTION" {
                    $mainCount++
                    $preview = $curData.Substring(0, [Math]::Min(50, $curData.Length))
                    Write-Output "QUESTION #$mainCount : $preview"
                    Post-Answer $client $base $sid $answer
                }
                "FOLLOW_UP" {
                    $preview = $curData.Substring(0, [Math]::Min(50, $curData.Length))
                    Write-Output "FOLLOW_UP : $preview"
                    Post-Answer $client $base $sid $answer
                }
                "WAITING_CODE" {
                    Write-Output "WAITING_CODE (coding appears last): textQuestions=$mainCount"
                    $submitCount++
                    Post-Code $client $base $sid $badCode
                }
                "CODE_SUBMITTED" { Write-Output "CODE_SUBMITTED accepted" }
                "ERROR" { Write-Output "ERROR_EVENT: $curData" }
                "REPORT_READY" { Write-Output "REPORT_READY" }
                "COMPLETE" { Write-Output "COMPLETE"; $completed = $true }
            }
            $curEvent = ""
        }
    }

    if ($sid) {
        try {
            $rounds = (Invoke-RestMethod -Uri "$base/api/interviews/sessions/$sid/rounds" -Headers @{ Authorization = "Bearer $token" }).data
            $order = ($rounds | ForEach-Object { $_.agentName }) -join " -> "
            Write-Output "=== agent order: $order ==="
        } catch { Write-Output "fetch rounds failed: $_" }
        try {
            $report = (Invoke-RestMethod -Uri "$base/api/interviews/sessions/$sid/report" -Headers @{ Authorization = "Bearer $token" }).data
            Write-Output "=== dims: technical=$($report.dimensionScores.technical) project=$($report.dimensionScores.project) coding=$($report.dimensionScores.coding) communication=$($report.dimensionScores.communication) overall=$($report.overallScore) ==="
        } catch { Write-Output "fetch report failed: $_" }
    }
    Write-Output "=== done: completed=$completed submitCount=$submitCount ==="
} -ArgumentList $token, $Base, $badCode

$deadline = (Get-Date).AddMinutes(15)
while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $out = Receive-Job $job
    if ($out) { $out | ForEach-Object { Write-Host $_ } }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Host "script exited"
