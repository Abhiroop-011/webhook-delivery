# Run against the local demo. This briefly stops and kills its delivery workers.
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
$api = 'http://127.0.0.1:8080'
$warehouse = 'http://127.0.0.1:8081'
$originalMode = $null

function Set-ReceiverMode([string]$Mode) {
    Invoke-RestMethod "$warehouse/demo/mode/$Mode" -Method Post -ContentType 'application/json' -Body '{}' | Out-Null
}

function Wait-Event([string]$Id, [string]$State, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $history = Invoke-RestMethod "$api/events/$Id"
        if ($history.event.state -eq $State) { return $history }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Event $Id did not reach $State within $Seconds seconds. Last state was $($history.event.state)."
}

try {
    $originalMode = (Invoke-RestMethod "$warehouse/demo").mode
    docker compose stop worker
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the workers.' }
    Set-ReceiverMode 'DELAY_FIRST_ACK'
    $key = [Guid]::NewGuid().ToString()
    $body = @{ destination = 'warehouse'; eventType = 'order.created'; payload = @{ orderId = "crash-$key" } } | ConvertTo-Json
    $accepted = Invoke-RestMethod "$api/events" -Method Post -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $body
    docker compose start worker
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the workers.' }
    $claimed = Wait-Event $accepted.id 'DELIVERING'
    docker compose kill -s SIGKILL worker
    if ($LASTEXITCODE -ne 0) { throw 'Could not kill the workers.' }
    docker compose start worker
    if ($LASTEXITCODE -ne 0) { throw 'Could not restart the workers.' }
    $finished = Wait-Event $accepted.id 'DELIVERED'
    $recovered = @($finished.attempts | Where-Object { $_.detail -eq 'Lease expired before the sender recorded a result' })
    if ($recovered.Count -eq 0) { throw 'The request finished before the kill. Run this check again with a fresh event.' }
    $receipt = Invoke-RestMethod "$warehouse/demo/orders/$($accepted.id)"
    if (!$receipt.saved) { throw 'The warehouse receipt is missing.' }
    $report = @{ utc = [DateTime]::UtcNow.ToString('o'); history = $finished; warehouseReceipt = $receipt; method = 'SIGKILL of both Compose workers after observing DELIVERING, followed by restart' }
    [void][IO.Directory]::CreateDirectory((Join-Path (Get-Location) 'docs/measurements'))
    $reportPath = Join-Path (Get-Location) 'docs/measurements/worker-crash.json'
    [IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    Write-Host "PASS: worker crash recovered event $($accepted.id); warehouse receipt verified."
    Write-Host "Saved $reportPath"
} finally {
    try {
        if ($null -ne $originalMode) {
            docker compose start worker
            Set-ReceiverMode $originalMode
        }
    } finally {
        Pop-Location
    }
}
