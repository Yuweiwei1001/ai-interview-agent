# Verify pressure persona follow-up: pressure + weak answer -> aggressive follow-up expected
$ErrorActionPreference = 'Stop'
$base = "http://localhost:8081"
$outFile = "verify-pressure-followup-result.txt"
function Log($m) { $m | Out-File -FilePath $outFile -Encoding UTF8 -Append }
"" | Out-File -FilePath $outFile -Encoding UTF8

function Get-Json($uri, $headers = @{}, $method = "GET", $body = $null) {
    $req = [System.Net.HttpWebRequest]::Create($uri)
    $req.Method = $method
    foreach ($k in $headers.Keys) { $req.Headers.Add($k, $headers[$k]) }
    if ($body) {
        $req.ContentType = "application/json; charset=utf-8"
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    }
    $resp = $req.GetResponse()
    $ms = New-Object System.IO.MemoryStream
    $resp.GetResponseStream().CopyTo($ms)
    $resp.Close()
    $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
    return $text
}

# 1. login
$login = Get-Json "$base/auth/login" -method POST -body '{"username":"testuser","password":"test123456"}'
$token = ($login | ConvertFrom-Json).data.accessToken
Log "[1] login ok"
$H = @{ Authorization = "Bearer $token" }

# 2. resume & jd
$resumes = (Get-Json "$base/api/resumes" $H | ConvertFrom-Json).data
$jds = (Get-Json "$base/api/jds" $H | ConvertFrom-Json).data
$rid = $resumes[0].id; $jid = $jds[0].id
Log "[2] resumeId=$rid jdId=$jid"

# 3. fire /start async (persona=pressure), drop SSE response
$startBody = @{ resumeId = $rid; jdId = $jid; direction = "backend"; persona = "pressure"; durationMinutes = 30 } | ConvertTo-Json -Compress
$req = [System.Net.HttpWebRequest]::Create("$base/api/interviews/start")
$req.Method = "POST"; $req.ContentType = "application/json; charset=utf-8"; $req.Headers.Add("Authorization", "Bearer $token")
$req.Timeout = 5000
$bytes = [System.Text.Encoding]::UTF8.GetBytes($startBody)
$s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
$req.BeginGetResponse({ param($ar) try { $ar.AsyncState.EndGetResponse($ar) | Out-Null } catch {} }, $req) | Out-Null
Log "[3] start fired (persona=pressure)"

# 4. poll for NEW in_progress session (created after fire time, avoid stale ones)
$fireTime = Get-Date
$sid = $null
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    $sessions = (Get-Json "$base/api/interviews/sessions" $H | ConvertFrom-Json).data
    $cand = $sessions | Where-Object {
        $_.status -eq 'in_progress' -and $_.createdAt -and ([datetime]$_.createdAt) -ge $fireTime.AddSeconds(-10)
    } | Sort-Object -Property createdAt -Descending | Select-Object -First 1
    if ($cand) { $sid = $cand.id; break }
}
Log "[4] sessionId=$sid"
if (-not $sid) { Log "FAIL: no in_progress session"; exit 1 }

# 5. wait for first question then submit weak answer
Start-Sleep -Seconds 30
$weakBytes = [System.Text.Encoding]::UTF8.GetBytes('{"answer":"\u8fd9\u4e2a\u6211\u4e0d\u592a\u6e05\u695a\uff0c\u6ca1\u6df1\u5165\u4e86\u89e3\u8fc7\u3002"}')
$weak = [System.Text.Encoding]::UTF8.GetString($weakBytes)
$ansResp = Get-Json "$base/api/interviews/$sid/answer" $H -method POST -body $weak
Log "[5] weak answer submitted: $ansResp"

# 6. wait for evaluate + followup, check rounds
Start-Sleep -Seconds 35
$rounds = (Get-Json "$base/api/interviews/$sid/rounds" $H | ConvertFrom-Json).data
Log "[6] rounds count=$($rounds.Count)"
foreach ($r in $rounds) {
    $score = $null
    if ($r.evaluation) { try { $score = ($r.evaluation | ConvertFrom-Json).score } catch {} }
    $qtext = ""
    if ($r.question) { $qtext = $r.question.Substring(0, [Math]::Min(60, $r.question.Length)) }
    Log "    round#$($r.roundNumber) followup=$($r.followup) agent=$($r.agentName) score=$score q=$qtext"
}
$fu = $rounds | Where-Object { $_.followup -eq $true }
if ($fu) { Log "PASS: followup record exists" } else { Log "WARN: no followup round record yet" }

# 7. traces: followup op prompt should contain pressure tone marker
$traces = (Get-Json "$base/api/observability/traces?sessionId=$sid" $H | ConvertFrom-Json).data
$fuTraces = $traces | Where-Object { $_.operation -eq 'followup' }
Log "[7] followup trace count=$($fuTraces.Count)"
foreach ($t in $fuTraces) {
    $p = $t.promptExcerpt
    $tone = [char]0x538B + [char]0x8FEB + [char]0x5F0F
    $hasTone = $p -like "*$tone*"
    Log "    followup trace: hasPressureTone=$hasTone"
    Log "    completion=$($t.completionExcerpt)"
}

# 8. end interview
$endResp = Get-Json "$base/api/interviews/$sid/end" $H -method POST -body '{}'
Log "[8] end: $endResp"
Log "DONE"
