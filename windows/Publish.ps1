param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'artifacts\release')
)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'src\Svalka.Desktop\Svalka.Desktop.csproj'
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
$build = Join-Path $PSScriptRoot 'artifacts\build'
$version = ([xml](Get-Content -LiteralPath $project -Raw)).Project.PropertyGroup.Version

function Publish-Svalka([string]$variant, [string]$name, [string]$installer) {
    $variantBuild = Join-Path $build $variant
    $publish = Join-Path $variantBuild 'publish'
    $publishArguments = @(
        'publish', $project, '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true',
        '--artifacts-path', (Join-Path $variantBuild 'artifacts'), '-o', $publish,
        '-p:PublishSingleFile=true', '-p:IncludeNativeLibrariesForSelfExtract=true',
        '-p:EnableCompressionInSingleFile=true', '-p:DebugType=none', "-p:SvalkaInstaller=$installer"
    )
    dotnet @publishArguments
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed for $variant ($LASTEXITCODE)." }
    Copy-Item -LiteralPath (Join-Path $publish 'Svalka.exe') -Destination (Join-Path $output $name) -Force
}

New-Item -ItemType Directory -Path $output -Force | Out-Null
Publish-Svalka 'portable' "Svalka-Windows-$version.exe" 'false'
Publish-Svalka 'setup' "Svalka-Windows-Setup-$version.exe" 'true'
