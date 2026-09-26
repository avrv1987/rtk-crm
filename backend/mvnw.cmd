@echo off
setlocal
set "MAVEN_VERSION=3.9.16"
set "MAVEN_USER_HOME=%~dp0.maven-wrapper-cache"
set "MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%"
set "MAVEN_PROJECT_BASEDIR=%~dp0."
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0.mvn\wrapper\bootstrap-maven.ps1" -MavenHome "%MAVEN_HOME%"
  if errorlevel 1 exit /b %errorlevel%
)
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Maven %MAVEN_VERSION% was not prepared at %MAVEN_HOME%.
  exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECT_BASEDIR%" %*
