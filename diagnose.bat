@echo off
echo ========================================
echo BLETestApp Installation Diagnostics
echo ========================================
echo.

echo Current Directory:
cd
echo.

echo Checking for Java:
where java 2>nul
if %errorlevel% neq 0 (
    echo ERROR: Java not found in PATH
) else (
    java -version 2>&1
)
echo.

echo JAVA_HOME:
echo %JAVA_HOME%
echo.

echo Checking installed location:
set INSTALL_DIR=%ProgramFiles%\BLETestApp
if exist "%INSTALL_DIR%" (
    echo Found installation at: %INSTALL_DIR%
    echo.
    echo Contents:
    dir "%INSTALL_DIR%" /b
    echo.
    echo Checking app folder:
    if exist "%INSTALL_DIR%\app" (
        dir "%INSTALL_DIR%\app" /b
    )
    echo.
    echo Checking runtime folder:
    if exist "%INSTALL_DIR%\runtime" (
        echo Runtime found
        if exist "%INSTALL_DIR%\runtime\bin\java.exe" (
            echo Java runtime included
            "%INSTALL_DIR%\runtime\bin\java.exe" -version 2>&1
        )
    )
) else (
    echo Installation not found at default location
    echo Trying alternate location...
    set INSTALL_DIR=%LocalAppData%\BLETestApp
    if exist "!INSTALL_DIR!" (
        echo Found at: !INSTALL_DIR!
    )
)
echo.

echo Checking for required files:
if exist "%INSTALL_DIR%\BLETestApp.exe" echo - Main EXE: Found
if exist "%INSTALL_DIR%\app\BLETestApp-1.0.0.jar" echo - Main JAR: Found
if exist "%INSTALL_DIR%\app\ble_service.exe" echo - Python Service: Found
if exist "%INSTALL_DIR%\app\ble_service.py" echo - Python Script: Found
echo.

echo Attempting to run with console:
cd /d "%INSTALL_DIR%"
if exist "BLETestApp.exe" (
    echo Starting BLETestApp.exe...
    start /wait cmd /k "BLETestApp.exe & echo. & echo Exit Code: %errorlevel% & pause"
) else (
    echo ERROR: BLETestApp.exe not found
)

pause