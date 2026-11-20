param(
    [Parameter(Mandatory)] [string] $Archive,
    [string] $JdkHome = $env:JAVA_HOME,
    [int] $ExpectedJavaRelease
)

$ErrorActionPreference = 'Stop'
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$tempPath = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ("JFTP package test " + [guid]::NewGuid())))
$temp = New-Item -ItemType Directory -Path $tempPath

function Invoke-Launcher([string] $JavaHome, [string] $JavaPath) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $env:ComSpec
    $info.Arguments = '/d /c call "' + $launcher.FullName + '"'
    $info.WorkingDirectory = $temp.FullName
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['PATH'] = $JavaPath
    if ($JavaHome) {
        $info.EnvironmentVariables['JAVA_HOME'] = $JavaHome
    } else {
        $info.EnvironmentVariables.Remove('JAVA_HOME')
    }
    # Reach desktop startup deterministically. Native window verification is a separate check.
    $info.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = '-Djava.awt.headless=true "-Duser.home=' + $testHome + '"'
    $process = [Diagnostics.Process]::Start($info)
    try {
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(20000)) {
            $process.Kill()
            throw 'The packaged launcher did not exit in headless mode.'
        }
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $output.GetAwaiter().GetResult() + $errors.GetAwaiter().GetResult()
        }
    } finally {
        $process.Dispose()
    }
}

try {
    $testHome = (New-Item -ItemType Directory -Path (Join-Path $temp.FullName 'synthetic home')).FullName
    $extract = Join-Path $temp.FullName 'extracted package'
    Expand-Archive -LiteralPath $archivePath -DestinationPath $extract
    $launchers = @(Get-ChildItem -LiteralPath $extract -Recurse -File -Filter 'jftp.bat')
    if ($launchers.Count -ne 1) {
        throw "Expected one packaged Windows launcher, found $($launchers.Count)."
    }
    $launcher = $launchers[0]
    if ($ExpectedJavaRelease) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $jars = @(Get-ChildItem -LiteralPath $launcher.DirectoryName -File -Filter '*.jar')
        if ($jars.Count -ne 1) {
            throw "Expected one application JAR beside the Windows launcher, found $($jars.Count)."
        }
        $jar = [IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
        try {
            $entry = $jar.GetEntry('com/myjavaworld/jftp/JFTPApplication.class')
            if (-not $entry) {
                throw 'The packaged JAR is missing its desktop entry point.'
            }
            $reader = [IO.BinaryReader]::new($entry.Open())
            try {
                $header = $reader.ReadBytes(8)
                if ($header.Length -ne 8) {
                    throw 'The desktop entry point has a truncated class header.'
                }
                $majorVersion = ([int]$header[6] -shl 8) + $header[7]
                $expectedMajorVersion = $ExpectedJavaRelease + 44
                if ($majorVersion -ne $expectedMajorVersion) {
                    throw "Expected Java $ExpectedJavaRelease class version $expectedMajorVersion, found $majorVersion."
                }
            } finally {
                $reader.Dispose()
            }
        } finally {
            $jar.Dispose()
        }
    }
    if (-not $JdkHome) {
        $JdkHome = Split-Path -Parent (Split-Path -Parent (Get-Command java.exe -CommandType Application).Source)
    }
    $withJava = Join-Path $JdkHome 'bin'
    foreach ($selection in @('JAVA_HOME', 'PATH')) {
        $testHome = (New-Item -ItemType Directory -Path (Join-Path $temp.FullName "home for $selection")).FullName
        if ($selection -eq 'JAVA_HOME') {
            $started = Invoke-Launcher $JdkHome $temp.FullName
        } else {
            $started = Invoke-Launcher '' $withJava
        }
        if ($started.ExitCode -ne 1 -or $started.Output -notmatch 'java\.awt\.HeadlessException' -or
            -not (Test-Path -LiteralPath (Join-Path $testHome '.jftp/data/preferences.ser') -PathType Leaf)) {
            throw "The launcher did not reach Swing startup using $selection. $($started.Output)"
        }
    }
    $missing = Invoke-Launcher (Join-Path $temp.FullName 'missing JDK') $withJava
    if ($missing.ExitCode -ne 1 -or $missing.Output -notmatch 'Java was not found under JAVA_HOME') {
        throw "The launcher did not diagnose an invalid JAVA_HOME. $($missing.Output)"
    }
    $missing = Invoke-Launcher '' $temp.FullName
    if ($missing.ExitCode -ne 1 -or $missing.Output -notmatch 'Java was not found on PATH') {
        throw "The launcher did not diagnose missing Java on PATH. $($missing.Output)"
    }
    Write-Output 'Windows launcher reached Swing startup using JAVA_HOME and PATH and diagnosed missing Java in both cases.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $temp.FullName).Path
    if ($resolved -ne $tempPath) {
        throw "Unexpected temporary test directory: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
