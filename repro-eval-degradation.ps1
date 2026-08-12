# 验证：LLM 服务不可用（欠费）时，正确的 twoSum 代码不应被误判为"未通过"
$ErrorActionPreference = 'Stop'
$base = "http://localhost:8081"

$loginResp = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"testuser","password":"test123456"}'
$token = $loginResp.data.accessToken
Write-Host "[*] 登录成功 (LLM 欠费环境)"

$goodCode = @'
import java.util.HashMap;
import java.util.Scanner;
public class Solution {
    public static int[] twoSum(int[] nums, int target) {
        HashMap<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            int need = target - nums[i];
            if (map.containsKey(need)) {
                return new int[]{map.get(need), i};
            }
            map.put(nums[i], i);
        }
        return new int[]{-1, -1};
    }
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[] nums = new int[n];
        for (int i = 0; i < n; i++) { nums[i] = sc.nextInt(); }
        int target = sc.nextInt();
        int[] res = twoSum(nums, target);
        System.out.println(res[0] + " " + res[1]);
        sc.close();
    }
}
'@

$job = Start-Job -ScriptBlock {
    param($token, $base, $goodCode)
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
    $sid = $null; $qCount = 0; $codeSubmitted = $false; $curEvent = ""; $curData = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $reader.EndOfStream -and $sw.Elapsed.TotalMinutes -lt 4) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $curEvent = $line.Substring(6).Trim(); $curData = ""; continue }
        if ($line.StartsWith("data:")) { $curData += $line.Substring(5).Trim(); continue }
        if ($line.Trim() -eq "" -and $curEvent -ne "") {
            $ts = (Get-Date).ToString("HH:mm:ss")
            $preview = if ($curData.Length -gt 50) { $curData.Substring(0, 50) + "..." } else { $curData }
            switch ($curEvent) {
                "CONNECTED" { $sid = $curData; Write-Output "[$ts] CONNECTED: $sid" }
                "QUESTION" {
                    $qCount++
                    Write-Output "[$ts] QUESTION#${qCount}: $preview"
                    $json = @{ answer = $longAnswer } | ConvertTo-Json -Compress
                    $c = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
                    $client.PostAsync("$base/api/interviews/$sid/answer", $c).Result | Out-Null
                    Write-Output "[$ts]   -> 已回答"
                }
                "FOLLOW_UP" {
                    Write-Output "[$ts] FOLLOW_UP: $preview"
                    $json = @{ answer = $longAnswer } | ConvertTo-Json -Compress
                    $c = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
                    $client.PostAsync("$base/api/interviews/$sid/answer", $c).Result | Out-Null
                }
                "WAITING_CODE" {
                    if (-not $codeSubmitted) {
                        $codeSubmitted = $true
                        Write-Output "[$ts] 编程题: $preview"
                        Write-Output "[$ts]   -> 提交【正确】twoSum 代码"
                        $body = @{ code = $goodCode; language = "java" } | ConvertTo-Json -Compress
                        $sc = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
                        $client.PostAsync("$base/api/coding/submit/$sid", $sc).Result | Out-Null
                        Write-Output "[$ts]   -> 已提交"
                    } else {
                        Write-Output "[$ts] 再次 WAITING_CODE(重试提示): $preview"
                    }
                }
                "CODE_SUBMITTED" { Write-Output "[$ts] CODE_SUBMITTED" }
                "REPORT_READY" { Write-Output "[$ts] REPORT_READY" }
                "COMPLETE" { Write-Output "[$ts] COMPLETE: $preview" }
                "ERROR" { Write-Output "[$ts] ERROR: $preview" }
            }
            $curEvent = ""
        }
    }
    Write-Output "SUMMARY: 问答题=$qCount 代码提交=$codeSubmitted"
} -ArgumentList $token, $base, $goodCode

$deadline = (Get-Date).AddMinutes(4)
while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    $out = Receive-Job $job
    if ($out) { $out | ForEach-Object { Write-Host $_ } }
}
Start-Sleep -Seconds 2
$out = Receive-Job $job
if ($out) { $out | ForEach-Object { Write-Host $_ } }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Host "验证结束"
