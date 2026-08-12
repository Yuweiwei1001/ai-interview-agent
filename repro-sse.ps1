# 复现脚本：模拟 CodingRoomView 的真实行为
# 1) GET /stream 重连 SSE
# 2) POST /api/coding/submit 提交代码
# 3) 观察 /stream 上是否收到 CODE_SUBMITTED / WAITING_CODE / QUESTION / REPORT_READY / COMPLETE
param([string]$Base = "http://localhost:5173", [string]$Sid = "a544020b-dbc7-4ae7-a7a7-edfa9bd6af20")
$ErrorActionPreference = 'Stop'
$base = $Base
$sid = $Sid

$loginResp = Invoke-RestMethod -Uri "http://localhost:8080/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
Write-Host "[*] 登录成功, sid=$sid"

$job = Start-Job -ScriptBlock {
    param($token, $base, $sid)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $token")
    $client.Timeout = [TimeSpan]::FromMinutes(3)
    try {
        $resp = $client.GetAsync("$base/api/interviews/$sid/stream", [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
        Write-Output ("[{0}] /stream HTTP {1} Content-Type={2}" -f (Get-Date).ToString("HH:mm:ss.fff"), [int]$resp.StatusCode, $resp.Content.Headers.ContentType.MediaType)
        $stream = $resp.Content.ReadAsStreamAsync().Result
        $reader = [System.IO.StreamReader]::new($stream)
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.Trim() -ne "") {
                $preview = $line
                if ($preview.Length -gt 120) { $preview = $preview.Substring(0, 120) + "..." }
                Write-Output ("[{0}] << {1}" -f (Get-Date).ToString("HH:mm:ss.fff"), $preview)
            }
        }
        Write-Output ("[{0}] ** 流已结束（reader.EndOfStream）**" -f (Get-Date).ToString("HH:mm:ss.fff"))
    } catch {
        Write-Output ("[{0}] ** /stream 异常: {1} **" -f (Get-Date).ToString("HH:mm:ss.fff"), $_.Exception.Message)
    }
} -ArgumentList $token, $base, $sid

Start-Sleep -Seconds 3
$out = Receive-Job $job; if ($out) { $out | ForEach-Object { Write-Host $_ } }

$code = @'
import java.util.*;
public class Solution {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) { System.out.println(sc.nextLine()); }
    }
}
'@
$body = @{ code = $code; language = "java" } | ConvertTo-Json -Compress
try {
    $submitResp = Invoke-RestMethod -Uri "$base/api/coding/submit/$sid" -Method Post -ContentType "application/json" -Headers @{ Authorization = "Bearer $token" } -Body $body
    Write-Host ("[{0}] >> 代码已提交: code={1} msg={2}" -f (Get-Date).ToString("HH:mm:ss.fff"), $submitResp.code, $submitResp.msg)
} catch {
    Write-Host "提交失败: $($_.Exception.Message)"
}

$deadline = (Get-Date).AddSeconds(100)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $out = Receive-Job $job
    if ($out) { $out | ForEach-Object { Write-Host $_ } }
    if ($job.State -ne 'Running') { break }
}
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Host "复现结束"
