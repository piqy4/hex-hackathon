$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $projectRoot -Parent
$destination = Join-Path $workspace 'llama.cpp'
$revision = 'cf659f240b232e15700b1721bbb43c1d973c52e9'
if (Test-Path -LiteralPath $destination) {
    $actual = & git -C $destination rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $revision) {
        throw "Existing llama.cpp is not pinned revision $revision. Keep your checkout and pass LLAMA_SOURCE_DIR to CMake, or move it manually."
    }
    $changes = & git -C $destination status --porcelain --untracked-files=no
    if ($changes) { throw 'Pinned llama.cpp has tracked modifications; review them before building.' }
} else {
    & git init $destination
    if ($LASTEXITCODE -ne 0) { throw 'git init failed' }
    & git -C $destination remote add origin https://github.com/ggml-org/llama.cpp.git
    & git -C $destination fetch --depth 1 origin $revision
    if ($LASTEXITCODE -ne 0) { throw 'git fetch failed' }
    & git -C $destination checkout --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw 'git checkout failed' }
}
Write-Output "llama.cpp pinned at $revision"
