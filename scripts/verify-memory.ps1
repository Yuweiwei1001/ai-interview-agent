# Verify memory pipeline: LLM knowledge point extraction + weak point injection (2nd session)
# Session usage: run once -> check knowledge_point rows; run again -> check backend log "长期记忆注入出题"
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
    $answer = "HashMap底层是数组加链表加红黑树，put时先算hash定位桶，冲突时挂链表，链表长度超过8且数组长度达到64时转红黑树；扩容是容量翻倍，负载因子0.75，JDK8扩容时高低位拆分不需要rehash；并发场景用ConcurrentHashMap，JDK8用CAS加synchronized锁桶头节点，size用baseCount加CounterCell分散计数。"
    $sid = $null; $curEvent = ""; $curData = ""
    $mainCount = 0; $completed = $false
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    function Post-Answer($c, $b, $s, $text) {
        $json = @{ answer = $text } | ConvertTo-Json -Compress
        $ct = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $c.PostAsync("$b/api/interviews/$s/answer", $ct).Result | Out-Null
    }
    function Post-Code($c, $b, $s, $code) {
        $json = @{ code = $code; language = "java" } | ConvertTo-Json -Compress
        $ct = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $c.PostAsync("$b/api/coding/submit/$s", $ct).Result | Out-Null
    }

    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 14 -and -not $completed) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); continue }
        if ($line.StartsWith("data:")) { $curData += $line.Substring(5).Trim(); continue }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            switch ($curEvent) {
                "CONNECTED" { $sid = $curData; Write-Output "CONNECTED: $sid" }
                "QUESTION" {
                    $mainCount++
                    Write-Output "QUESTION #$mainCount"
                    Post-Answer $client $base $sid $answer
                }
                "FOLLOW_UP" {
                    Write-Output "FOLLOW_UP"
                    Post-Answer $client $base $sid $answer
                }
                "WAITING_CODE" {
                    Write-Output "WAITING_CODE"
                    Post-Code $client $base $sid $badCode
                }
                "COMPLETE" { Write-Output "COMPLETE"; $completed = $true }
            }
            $curEvent = ""
        }
    }
    Write-Output "=== done: completed=$completed sid=$sid ==="
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
