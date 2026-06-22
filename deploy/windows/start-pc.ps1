param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('pc1', 'pc2', 'pc3', 'pc4', 'pc5')]
    [string]$Pc
)

$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot "$Pc.compose.yml"

docker compose -f $compose up -d
docker compose -f $compose ps
