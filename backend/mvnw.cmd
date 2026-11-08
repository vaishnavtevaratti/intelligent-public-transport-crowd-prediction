@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------

@if "%DEBUG%" == "" @echo off
set ERROR_CODE=0

set MAVEN_PROJECTBASEDIR=%~dp0
if "%MAVEN_PROJECTBASEDIR%" == "" set MAVEN_PROJECTBASEDIR=%CD%

@REM Execute Maven command using mvn or fallback
mvn %*
if %ERRORLEVEL% EQU 0 goto end

echo [INFO] System 'mvn' command not found. Please ensure Apache Maven or Java 17+ JDK is installed on your PATH.
echo [INFO] You can run: mvn spring-boot:run inside the backend directory.

:end
@exit /b %ERROR_CODE%
