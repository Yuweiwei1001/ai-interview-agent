# Check rounds/traces for existing session then end it
$ErrorActionPreference = 'Stop'
$base = "http://localhost:8081"
$sid = "c1201e02-1f8e-4ec1-a50c-51ec7a329966"
$outFile = "verify-pressure-followup-result.txt"
function Log($m) { $m | Out-File -FilePath $outFile -Encoding UTF8 -Append }

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
    return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

$login = Get-Json "$base/auth/login" -method POST -body '{"username":"testuser","password":"test123456"}'
$token = ($login | ConvertFrom-Json).data.accessToken
$H = @{ Authorization = "Bearer $token" }

# rounds (correct path: /sessions/{id}/rounds)
$rounds = (Get-Json "$base/api/interviews/sessions/$sid/rounds" $H | ConvertFrom-Json).data
Log "[6] rounds count=$($rounds.Count)"
foreach ($r in $rounds) {
    $score = $null
    $fuq = ""
    if ($r.evaluation) {
        try {
            $ev = $r.evaluation | ConvertFrom-Json
            $score = $ev.score
            if ($ev.followUp) { $fuq = $ev.followUp }
        } catch {}
    }
    $qtext = ""
    if ($r.question) { $qtext = $r.question.Substring(0, [Math]::Min(80, $r.question.Length)) }
    Log "    round#$($r.roundNumber) followup=$($r.followup) agent=$($r.agentName) score=$score q=$qtext"
    if ($fuq) { Log "      -> generatedFollowUp=$fuq" }
}
$fu = $rounds | Where-Object { $_.followup -eq $true }
if ($fu) { Log "PASS: followup record exists" } else { Log "WARN: no followup round record" }

# traces
$traces = (Get-Json "$base/api/observability/traces?sessionId=$sid" $H | ConvertFrom-Json).data
$fuTraces = $traces | Where-Object { $_.operation -eq 'followup' }
Log "[7] followup trace count=$($fuTraces.Count)"
$tone = [string]([char]0x538B) + [string]([char]0x8FEB) + [string]([char]0x5F0F)
foreach ($t in $fuTraces) {
    $hasTone = $t.promptExcerpt -like "*$tone*"
    Log "    followup trace: hasPressureTone=$hasTone"
    Log "    completion=$($t.completionExcerpt)"
}

# end
$endResp = Get-Json "$base/api/interviews/$sid/end" $H -method POST -body '{}'
Log "[8] end: $endResp"
Log "DONE"
