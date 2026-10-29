$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = [IO.Path]::GetFullPath((Join-Path $workspace '.local/models/bge-small-zh-v1.5'))
if (!$destination.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Model destination must remain inside the project'
}
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$revision = '75c43b069aac4d136ba6bc1122f995fedcfd2781'
$baseUri = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$revision"
$model = Join-Path $destination 'model_quantized.onnx'
$expectedModel = '15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc'
if (!(Test-Path -LiteralPath $model)) {
    $partial = Join-Path $destination 'model_quantized.onnx.download'
    Invoke-WebRequest -Uri "$baseUri/onnx/model_quantized.onnx" -OutFile $partial -TimeoutSec 180
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedModel) {
        throw 'Downloaded model checksum does not match the pinned release; the partial file was preserved'
    }
    Move-Item -LiteralPath $partial -Destination $model
}
if ((Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedModel) {
    throw 'Existing model checksum mismatch; existing files were preserved'
}
$vocabulary = Join-Path $destination 'vocab.txt'
if (!(Test-Path -LiteralPath $vocabulary)) {
    Invoke-WebRequest -Uri "$baseUri/vocab.txt" -OutFile $vocabulary -TimeoutSec 45
}
# Hugging Face's pinned Git object verifies vocabulary bytes, including newlines.
$bytes = [IO.File]::ReadAllBytes($vocabulary)
$header = [Text.Encoding]::UTF8.GetBytes("blob $($bytes.Length)`0")
$blob = [byte[]]::new($header.Length + $bytes.Length)
[Array]::Copy($header, 0, $blob, 0, $header.Length)
[Array]::Copy($bytes, 0, $blob, $header.Length, $bytes.Length)
$vocabObject = [Convert]::ToHexString([Security.Cryptography.SHA1]::HashData($blob)).ToLowerInvariant()
if ($vocabObject -ne 'ca4f9781030019ab9b253c6dcb8c7878b6dc87a5') {
    throw 'Vocabulary does not match the pinned model revision; existing files were preserved'
}
Write-Output 'RAG_MODEL_READY model=bge-small-zh-v1.5 bytes=24010842 license=MIT'
Write-Output ('RAG_VOCAB_SHA256=' + (Get-FileHash -LiteralPath $vocabulary -Algorithm SHA256).Hash.ToLowerInvariant())
