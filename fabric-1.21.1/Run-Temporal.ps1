param(
    [ValidateSet('runClient', 'build')]
    [string]$Task = 'runClient'
)

$ErrorActionPreference = 'Stop'
try {
    $wrapper = Join-Path $PSScriptRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) {
        throw 'Keep this launcher inside the Temporal project folder.'
    }

    $candidates = [Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaCommand.Source)))
    }
    $searchRoots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        (Join-Path $env:USERPROFILE '.gradle\jdks'),
        (Join-Path $env:USERPROFILE '.mcreator\gradle\jdks'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Java')
    )
    foreach ($directory in $searchRoots) {
        if (Test-Path -LiteralPath $directory) {
            foreach ($jdk in Get-ChildItem -LiteralPath $directory -Directory) {
                $candidates.Add($jdk.FullName)
            }
        }
    }
    $selectedJdk = $null
    foreach ($candidate in $candidates | Select-Object -Unique) {
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path -LiteralPath $release) -and
            ([IO.File]::ReadAllText($release) -match '(?m)^JAVA_VERSION="25(?:\.|[+"]|$)')) {
            $selectedJdk = $candidate
            break
        }
    }
    if (-not $selectedJdk) {
        throw 'Install a Java 25 JDK (Fabric Loom runs Gradle on it), or set JAVA_HOME to one, then try again.'
    }
    $env:JAVA_HOME = $selectedJdk

    # Reuse the existing dependency cache on this computer when available.
    $existingCache = Join-Path $env:USERPROFILE '.mcreator\gradle'
    if (-not $env:GRADLE_USER_HOME -and
        (Test-Path -LiteralPath (Join-Path $existingCache 'wrapper\dists'))) {
        $env:GRADLE_USER_HOME = $existingCache
    }

    Write-Host 'Temporal - Minecraft 1.21.1 / Fabric'
    Write-Host "Java 25 (for Gradle): $selectedJdk"
    Write-Host "Task: $Task"

    Push-Location -LiteralPath $PSScriptRoot
    try {
        & $wrapper --no-daemon $Task
        $result = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($result -eq 0 -and $Task -eq 'build') {
        Write-Host "Built mod file: $PSScriptRoot\build\libs"
    }
    exit $result
} catch {
    Write-Host "Could not run the project: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
