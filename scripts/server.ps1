param(
    [ValidateSet('Prepare','Start')][string]$Action = 'Prepare',
    [switch]$AcceptEula
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serverPath = Join-Path $projectRoot 'run/server'
$jarPath = Join-Path $serverPath 'paper.jar'
$expectedHash = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
New-Item -ItemType Directory -Force -Path $serverPath | Out-Null
if (!(Test-Path -LiteralPath $jarPath)) {
    $headers = @{ 'User-Agent' = 'CloudStorage-LocalDev/0.1 (https://github.com/PaperMC/Paper)' }
    Invoke-WebRequest -Headers $headers -Uri "https://fill-data.papermc.io/v1/objects/$expectedHash/paper-1.21.1-133.jar" -OutFile $jarPath
}
if ((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'Paper SHA-256 mismatch' }
if ($AcceptEula) { Set-Content -LiteralPath (Join-Path $serverPath 'eula.txt') -Value "# User explicitly accepted https://aka.ms/MinecraftEULA`neula=true" -Encoding utf8 }
$properties = Join-Path $serverPath 'server.properties'
if (!(Test-Path -LiteralPath $properties)) {
@'
server-ip=127.0.0.1
server-port=25565
online-mode=true
enable-rcon=false
enable-query=false
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=8
gamemode=survival
difficulty=peaceful
motd=CloudStorage Paper 1.21.1 Local Test
level-type=minecraft\:flat
generate-structures=false
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
'@ | Set-Content -LiteralPath $properties -Encoding utf8
}
if ($Action -eq 'Prepare') { Write-Output "Prepared Paper 1.21.1 build 133 at $serverPath"; exit 0 }
if (!(Test-Path (Join-Path $serverPath 'eula.txt')) -or !(Select-String -LiteralPath (Join-Path $serverPath 'eula.txt') -Pattern '^eula=true$' -Quiet)) { throw 'Accept the Minecraft EULA explicitly with pixi run server-prepare -AcceptEula' }
$pluginJar = Join-Path $projectRoot 'target/cloud-storage-0.2.3.jar'
if (!(Test-Path -LiteralPath $pluginJar)) { throw 'Run pixi run build first' }
New-Item -ItemType Directory -Force (Join-Path $serverPath 'plugins') | Out-Null
Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $serverPath 'plugins/CloudStorage.jar') -Force
Push-Location $serverPath
try {
    & java '-Xms512M' '-Xmx2G' '-jar' $jarPath '--nogui'
    $serverExitCode = $LASTEXITCODE
    & python (Join-Path $PSScriptRoot 'checkpoint.py') (Join-Path $serverPath 'plugins/CloudStorage/storage.sqlite') $serverExitCode
    exit $serverExitCode
}
finally { Pop-Location }
