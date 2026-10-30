param(
    [Parameter(Position=0)]
    [ValidateSet('Verify','LoadImages','BuildImages','Prepare','Start','Status','Stop','VerifyRuntime','EnableRag')]
    [string]$Action = 'Verify',
    [ValidateSet('supportflow-lab','supportflow-delivery-smoke')]
    [string]$ProjectName = 'supportflow-lab',
    [ValidateRange(1024,65535)][int]$FrontendPort = 18090,
    [ValidateRange(1024,65535)][int]$ManagementPort = 18091,
    [ValidateRange(1024,65535)][int]$PrometheusPort = 19090
)
$ErrorActionPreference = 'Stop'
$bundleRoot = [IO.Path]::GetFullPath($PSScriptRoot)
if (@($FrontendPort, $ManagementPort, $PrometheusPort | Select-Object -Unique).Count -ne 3) { throw 'Loopback ports must be distinct' }
if ($ProjectName -eq 'supportflow-lab' -and ($FrontendPort -ne 18090 -or $ManagementPort -ne 18091 -or $PrometheusPort -ne 19090)) {
    throw 'Use the separate supportflow-delivery-smoke project for alternate loopback ports'
}
Set-Location -LiteralPath $bundleRoot

function Assert-LocalPath([string]$Relative) {
    if ([IO.Path]::IsPathRooted($Relative) -or $Relative.Contains('\') -or $Relative.Contains(':') -or
        @($Relative.Split('/') | Where-Object { $_ -eq '..' -or $_ -eq '.' -or $_ -eq '' }).Count -gt 0) {
        throw 'Unsafe bundle path'
    }
    $current = $bundleRoot
    foreach ($part in $Relative.Split('/')) {
        $current = Join-Path $current $part
        if ((Test-Path -LiteralPath $current) -and ((Get-Item -Force -LiteralPath $current).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Bundle paths must not contain symbolic links or junctions'
        }
    }
    return $current
}
function Invoke-Docker([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker command failed; inspect its output without sharing credentials' }
}
$manifestFile = Assert-LocalPath 'manifest.json'
$manifest = Get-Content -Raw -LiteralPath $manifestFile | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.application -ne 'supportflow-lab' -or $manifest.database -ne 'supportflow_lab') {
    throw 'Unsupported delivery manifest'
}
foreach ($entry in $manifest.files) {
    $file = Assert-LocalPath $entry.path
    if (!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -ne $entry.size -or
        (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
        throw ('Delivery checksum mismatch: ' + $entry.path)
    }
}
$imageEnvironment = @{}
foreach ($line in [IO.File]::ReadAllLines((Assert-LocalPath '.env'))) {
    if ($line -notmatch '^(SUPPORTFLOW_(BACKEND|FRONTEND)_IMAGE)=([A-Za-z0-9./:_-]+)$') { throw 'Invalid delivered image environment' }
    $imageEnvironment[$Matches[1]] = $Matches[3]
}
if ($imageEnvironment.Count -ne 2) { throw 'Missing application image tags' }
$backendImage = $imageEnvironment['SUPPORTFLOW_BACKEND_IMAGE']
$env:SUPPORTFLOW_BACKEND_IMAGE = $backendImage
$env:SUPPORTFLOW_FRONTEND_IMAGE = $imageEnvironment['SUPPORTFLOW_FRONTEND_IMAGE']
$env:SUPPORTFLOW_FRONTEND_PORT = [string]$FrontendPort
$env:SUPPORTFLOW_MANAGEMENT_PORT = [string]$ManagementPort
$env:SUPPORTFLOW_PROMETHEUS_PORT = [string]$PrometheusPort
$composeArguments = @('compose', '--project-name', $ProjectName, '--env-file', (Join-Path $bundleRoot '.env'), '-f', (Join-Path $bundleRoot 'deploy/compose.yml'))
if (Test-Path -LiteralPath (Assert-LocalPath '.local/rag-enabled')) {
    $composeArguments += @('-f', (Join-Path $bundleRoot 'deploy/compose.rag.yml'))
}
function Assert-Images {
    foreach ($tag in $manifest.images.requiredTags) {
        $actual = & docker image inspect --format '{{.Id}}' $tag
        if ($LASTEXITCODE -ne 0) { throw ('Required image unavailable; run LoadImages or BuildImages: ' + $tag) }
        foreach ($identity in $manifest.images.identities) {
            if ($identity.tag -eq $tag -and $actual.Trim() -ne $identity.id) { throw ('Loaded image ID differs from the bundle: ' + $tag) }
        }
    }
}
switch ($Action) {
    'Verify' { Write-Output 'DELIVERY_CONTENTS_VERIFIED (integrity check; not a publisher signature)' }
    'LoadImages' {
        if (!$manifest.images.included -or $manifest.images.archive -ne 'supportflow-images.tar.gz') { throw 'This artifact bundle has no saved images; BuildImages requires internet access' }
        Invoke-Docker @('load', '--input', (Assert-LocalPath 'supportflow-images.tar.gz'))
        Assert-Images
    }
    'BuildImages' {
        if ($manifest.images.included) { throw 'Use LoadImages for this image bundle so the recorded image IDs are preserved' }
        Invoke-Docker @('build', '--platform', 'linux/amd64', '-f', 'deploy/backend.Dockerfile', '-t', $backendImage, '.')
        Invoke-Docker @('build', '--platform', 'linux/amd64', '-f', 'deploy/frontend.Dockerfile', '-t', $imageEnvironment['SUPPORTFLOW_FRONTEND_IMAGE'], '.')
        Invoke-Docker @('pull', '--platform', 'linux/amd64', 'mysql:8.0.45')
        Invoke-Docker @('pull', '--platform', 'linux/amd64', 'prom/prometheus:v3.2.1')
        Assert-Images
    }
    'Prepare' {
        Assert-Images
        $null = Assert-LocalPath '.local/deployment-lab'
        $privateRoot = Assert-LocalPath '.local'
        if (!(Test-Path -LiteralPath $privateRoot)) { New-Item -ItemType Directory -Path $privateRoot | Out-Null }
        $prepareUser = '0:0'
        if ($env:OS -ne 'Windows_NT') {
            $hostUid = & id -u
            if ($LASTEXITCODE -ne 0) { throw 'Cannot determine installation directory owner' }
            $hostGid = & id -g
            if ($LASTEXITCODE -ne 0) { throw 'Cannot determine installation directory group' }
            $prepareUser = "$($hostUid.Trim()):$($hostGid.Trim())"
            & chmod 700 $privateRoot
            if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the local credential parent directory' }
        }
        Invoke-Docker @('run', '--rm', '--network', 'none', '--user', $prepareUser, '--entrypoint', 'java', '--mount', "type=bind,src=$privateRoot,dst=/bundle/.local", '--workdir', '/bundle', $backendImage,
            '-Dloader.main=dev.supportflow.deployment.LabDeploymentTool', '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', 'prepare')
        Write-Output 'Credentials remain in .local/deployment-lab/login.txt; do not copy this directory into artifacts.'
    }
    'Start' {
        Assert-Images
        foreach ($name in @('lab_root_password','lab_app_password','lab_migrator_password','lab_buyer_hash','lab_merchant_hash','lab_ops_hash','lab_ops_password')) {
            if (!(Test-Path -LiteralPath (Assert-LocalPath ('.local/deployment-lab/' + $name)) -PathType Leaf)) { throw 'Run Prepare before Start' }
        }
        Invoke-Docker ($composeArguments + @('config', '--quiet'))
        Invoke-Docker ($composeArguments + @('up', '-d', '--pull', 'never'))
        Invoke-Docker ($composeArguments + @('ps', '-a'))
    }
    'Status' { Invoke-Docker ($composeArguments + @('ps', '-a')) }
    'Stop' { Invoke-Docker ($composeArguments + @('stop')) }
    'VerifyRuntime' {
        Invoke-Docker ($composeArguments + @('exec', '-T', 'backend', 'java', '-Dloader.main=dev.supportflow.deployment.LabDeploymentTool', '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', 'verify'))
    }
    'EnableRag' {
        foreach ($name in @('model_quantized.onnx','vocab.txt')) {
            if (!(Test-Path -LiteralPath (Assert-LocalPath ('.local/models/bge-small-zh-v1.5/' + $name)) -PathType Leaf)) {
                throw 'Download or copy the pinned model files first; RAG was not enabled'
            }
        }
        & (Join-Path $bundleRoot 'scripts/setup-rag-model.ps1')
        if (!$?) { throw 'Model verification failed; RAG was not enabled' }
        $marker = Assert-LocalPath '.local/rag-enabled'
        if (!(Test-Path -LiteralPath $marker)) { [IO.File]::WriteAllText($marker, 'Pinned RAG weights verified locally.') }
        Assert-Images
        if ($composeArguments -notcontains (Join-Path $bundleRoot 'deploy/compose.rag.yml')) { $composeArguments += @('-f', (Join-Path $bundleRoot 'deploy/compose.rag.yml')) }
        Invoke-Docker ($composeArguments + @('up', '-d', '--pull', 'never', 'backend', 'frontend', 'prometheus'))
    }
}
