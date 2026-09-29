param(
    [string]$ApiUrl = 'http://127.0.0.1:8080',
    [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = 'Stop'

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

$login = Invoke-RestMethod -Method Post -Uri "$ApiUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{
    email = 'customer@fulfillment.local'
    password = 'LocalCustomerPassword!'
} | ConvertTo-Json)

$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$products = Invoke-RestMethod -Uri "$ApiUrl/api/v1/products" -Headers $headers
if ($products.Count -lt 1) { throw 'The seeded catalog is empty.' }
$productId = $products[0].id

$key = 'e2e-' + [Guid]::NewGuid().ToString('N')
$request = @{
    items = @(@{ productId = $productId; quantity = 1 })
    deliveryAddress = @{
        street = 'Rua E2E'; number = '100'; district = 'Centro'; city = 'Sao Paulo'
        state = 'SP'; postalCode = '01001000'; country = 'BR'
    }
} | ConvertTo-Json -Depth 8

$createHeaders = $headers.Clone()
$createHeaders['Idempotency-Key'] = $key
$created = Invoke-WebRequest -Method Post -Uri "$ApiUrl/api/v1/orders" -Headers $createHeaders -ContentType 'application/json' -Body $request
Assert-Equal 201 $created.StatusCode 'Order creation status'
$order = $created.Content | ConvertFrom-Json

$replay = Invoke-WebRequest -Method Post -Uri "$ApiUrl/api/v1/orders" -Headers $createHeaders -ContentType 'application/json' -Body $request
Assert-Equal 201 $replay.StatusCode 'Idempotent replay status'
Assert-Equal 'true' $replay.Headers['Idempotent-Replayed'][0] 'Idempotent replay header'
Assert-Equal $order.id (($replay.Content | ConvertFrom-Json).id) 'Idempotent replay order'

$different = $request | ConvertFrom-Json
$different.items[0].quantity = 2
try {
    Invoke-WebRequest -Method Post -Uri "$ApiUrl/api/v1/orders" -Headers $createHeaders -ContentType 'application/json' -Body ($different | ConvertTo-Json -Depth 8) | Out-Null
    throw 'A reused idempotency key with another payload was accepted.'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 422) { throw }
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 1
    $current = Invoke-RestMethod -Uri "$ApiUrl/api/v1/orders/$($order.id)" -Headers $headers
} until ($current.status -eq 'Delivered' -or [DateTimeOffset]::UtcNow -ge $deadline)

Assert-Equal 'Delivered' $current.status 'Order terminal state'
if (-not $current.paymentId -or -not $current.deliveryId) { throw 'The completed order lacks payment or delivery identifiers.' }

[pscustomobject]@{
    status = 'PASS'
    orderId = $current.id
    orderNumber = $current.number
    finalState = $current.status
    paymentId = $current.paymentId
    deliveryId = $current.deliveryId
    idempotencyKey = $key
} | ConvertTo-Json
