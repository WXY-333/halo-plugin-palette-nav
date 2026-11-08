@echo off
setlocal
cd /d %~dp0..

if not exist .tools\halo\halo.jar (
  echo Missing .tools\halo\halo.jar. Copy a Halo 2.27.x jar there first.
  exit /b 1
)

if "%HALO_JAVA_HOME%"=="" (
  if "%JAVA_HOME%"=="" (
    set "HALO_JAVA_HOME=D:\Develop\jdk\jdk-21"
  ) else (
    set "HALO_JAVA_HOME=%JAVA_HOME%"
  )
)

if not exist "%HALO_JAVA_HOME%\bin\java.exe" (
  echo Java executable not found: %HALO_JAVA_HOME%\bin\java.exe
  echo Set HALO_JAVA_HOME or JAVA_HOME to a JDK 21 installation.
  exit /b 1
)

for /f "tokens=3" %%v in ('"%HALO_JAVA_HOME%\bin\java.exe" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%~v"
for /f "tokens=1 delims=." %%m in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%m"
if "%JAVA_MAJOR%"=="" set "JAVA_MAJOR=0"
if %JAVA_MAJOR% LSS 21 (
  echo Halo 2.27 requires JDK 21+, but %HALO_JAVA_HOME% reports Java %JAVA_VERSION%.
  echo Set HALO_JAVA_HOME or JAVA_HOME to a JDK 21 installation.
  exit /b 1
)

set "JAVA_HOME=%HALO_JAVA_HOME%"
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -jar .tools\halo\halo.jar --spring.config.additional-location=optional:file:.tools\halo\
endlocal
