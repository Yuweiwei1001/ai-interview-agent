# Verify knowledge chat API on port 8081 (ASCII only, PS5 compatible)
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8081'

function Login($u, $p) {
    $body = @{ username = $u; password = $p } | ConvertTo-Json
    $res = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType 'application/json' -Body $body
    return $res.data.accessToken
}

$t1 = Login 'testuser' 'test123456'
Write-Host "[1] login testuser OK, token len=$($t1.Length)"
$h1 = @{ Authorization = "Bearer $t1" }

# register a second user for isolation test (ignore error if exists)
try {
    $reg = @{ username = 'chatuser2'; password = 'test123456' } | ConvertTo-Json
    Invoke-RestMethod -Uri "$base/auth/register" -Method Post -ContentType 'application/json' -Body $reg | Out-Null
    Write-Host "[2] chatuser2 registered"
} catch { Write-Host "[2] chatuser2 register skipped (exists)" }
$t2 = Login 'chatuser2' 'test123456'
$h2 = @{ Authorization = "Bearer $t2" }

# create session
$s = Invoke-RestMethod -Uri "$base/api/chat/sessions" -Method Post -Headers $h1
$sessionId = $s.data.id
Write-Host "[3] session created id=$sessionId title=$($s.data.title)"

# list sessions
$lst = Invoke-RestMethod -Uri "$base/api/chat/sessions" -Method Get -Headers $h1
Write-Host "[4] testuser sessions count=$($lst.data.Count)"

# ask in-scope question (kb has Java concurrency doc) via curl SSE
# PS5 quirk: quotes inside -d args get stripped; use temp json file instead
# NOTE: doc content is Chinese, use Chinese question for reliable similarity >= 0.5
$tmp1 = Join-Path $env:TEMP 'chat-q1.json'
$q1json = @{ question = 'volatile 关键字的作用是什么' } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($tmp1, $q1json, (New-Object System.Text.UTF8Encoding($false)))
$out1 = & curl.exe -s -N -X POST "$base/api/chat/sessions/$sessionId/ask" -H "Authorization: Bearer $t1" -H "Content-Type: application/json" -d "@$tmp1"
$deltas = ($out1 | Select-String 'event:delta').Count
$hasSources = ($out1 | Select-String 'event:sources').Count -gt 0
$hasDone = ($out1 | Select-String 'event:done').Count -gt 0
Write-Host "[5] in-scope ask: delta-events=$deltas sources=$hasSources done=$hasDone"

# ask out-of-scope question
$tmp2 = Join-Path $env:TEMP 'chat-q2.json'
@{ question = 'what is the capital of France' } | ConvertTo-Json -Compress | Out-File -FilePath $tmp2 -Encoding ascii -NoNewline
$out2 = & curl.exe -s -N -X POST "$base/api/chat/sessions/$sessionId/ask" -H "Authorization: Bearer $t1" -H "Content-Type: application/json" -d "@$tmp2"
$hasRefusal = ($out2 | Select-String 'event:refusal').Count -gt 0
Write-Host "[6] out-of-scope ask: refusal=$hasRefusal"

# history persisted
$msgs = Invoke-RestMethod -Uri "$base/api/chat/sessions/$sessionId/messages" -Method Get -Headers $h1
Write-Host "[7] history: total=$($msgs.data.Count) roles=$(( $msgs.data | ForEach-Object { $_.role } ) -join ',')"
$lastAssistant = ($msgs.data | Where-Object { $_.role -eq 'assistant' } | Select-Object -Last 1)
Write-Host "[8] refusal msg persisted: $($lastAssistant.content.Substring(0, [Math]::Min(40, $lastAssistant.content.Length)))"

# isolation: user2 cannot access user1 session
# NOTE: BaseException returns HTTP 200 with body {code:404} per project convention,
# so we must check body code instead of HTTP status
$leaked = $true
try {
    $r = Invoke-RestMethod -Uri "$base/api/chat/sessions/$sessionId/messages" -Method Get -Headers $h2
    if ($r.code -ne 0) { $leaked = $false; $code = "body-code $($r.code)" } else { $code = 'LEAKED' }
} catch {
    $leaked = $false
    $code = "HTTP $($_.Exception.Response.StatusCode.value__)"
}
Write-Host "[9] isolation user2->user1 session: $code"

# user2 ask on user1 session: SSE endpoint returns Result JSON body with code on auth failure
$out3 = & curl.exe -s -X POST "$base/api/chat/sessions/$sessionId/ask" -H "Authorization: Bearer $t2" -H "Content-Type: application/json" -d "@$tmp1"
$denied = (@($out3 | Select-String '"code":404').Count -gt 0)
Write-Host "[10] isolation user2 ask denied: $denied"

# delete session
Invoke-RestMethod -Uri "$base/api/chat/sessions/$sessionId" -Method Delete -Headers $h1 | Out-Null
Write-Host "[11] session deleted"

# verify gone
$still = $true
try {
    $r2 = Invoke-RestMethod -Uri "$base/api/chat/sessions/$sessionId/messages" -Method Get -Headers $h1
    if ($r2.code -ne 0) { $still = $false; $code2 = "body-code $($r2.code)" } else { $code2 = 'STILL-THERE' }
} catch {
    $still = $false
    $code2 = "HTTP $($_.Exception.Response.StatusCode.value__)"
}
Write-Host "[12] after delete user1 access: $code2"
Write-Host 'ALL DONE'
