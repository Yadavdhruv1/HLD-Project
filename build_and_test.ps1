# Standalone Build and Test Runner for Search Typeahead System

$MavenVersion = "3.9.6"
$MavenDir = Join-Path $PSScriptRoot ".maven"
$MvnPath = Join-Path $MavenDir "apache-maven-$MavenVersion\bin\mvn.cmd"

if (-not (Test-Path $MvnPath)) {
    Write-Host "Local Maven not found. Downloading Apache Maven $MavenVersion..."
    New-Item -ItemType Directory -Force -Path $MavenDir | Out-Null
    $ZipPath = Join-Path $MavenDir "maven.zip"
    $Url = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    
    # Speed up download by disabling progress bar
    $oldProgressPreference = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $ZipPath
    } finally {
        $ProgressPreference = $oldProgressPreference
    }

    
    Write-Host "Extracting Maven..."
    # Extract zip file
    Expand-Archive -Path $ZipPath -DestinationPath $MavenDir -Force
    
    # Cleanup zip file
    Remove-Item $ZipPath -Force
    Write-Host "Maven installed locally under .maven/"
}

Write-Host "Building and running unit/integration tests..."
$BackendDir = Join-Path $PSScriptRoot "backend"
Set-Location -Path $BackendDir

# Execute test goal
& $MvnPath clean test
