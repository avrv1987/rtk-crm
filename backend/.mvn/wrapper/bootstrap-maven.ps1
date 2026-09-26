[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MavenHome
)

$ErrorActionPreference = 'Stop'
$distributionUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip'
$expectedSha512 = 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
$cacheDirectory = Split-Path -Parent $MavenHome
$archive = Join-Path $cacheDirectory 'apache-maven-3.9.16-bin.zip'

if (-not (Test-Path -LiteralPath (Join-Path $MavenHome 'bin\mvn.cmd'))) {
    New-Item -ItemType Directory -Force -Path $cacheDirectory | Out-Null
    Invoke-WebRequest -Uri $distributionUrl -OutFile $archive
    $sha512 = [System.Security.Cryptography.SHA512]::Create()
    $stream = [System.IO.File]::OpenRead($archive)
    try {
        $actualSha512 = ([System.BitConverter]::ToString($sha512.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $sha512.Dispose()
    }
    if ($actualSha512 -ne $expectedSha512) {
        throw 'Maven distribution checksum verification failed'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $cacheDirectory -Force
}
