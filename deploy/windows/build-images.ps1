$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root

try {
    mvn -DskipTests package
    docker build -t stage3/crawler:desktop .\crawler-service
    docker build -t stage3/indexer:desktop .\indexing-service
    docker build -t stage3/search:desktop .\search-service
    docker build -t stage3/control:desktop .\control-module
    docker build -t stage3/benchmarks:desktop .\benchmarks
    Write-Host 'Imágenes Docker Desktop creadas correctamente.' -ForegroundColor Green
}
finally {
    Pop-Location
}
