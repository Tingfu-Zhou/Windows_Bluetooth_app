@echo off
echo ========================================
echo BLE Test Application - Startup Script
echo ========================================
echo.

:: Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not detected
    echo Please run install.bat first
    pause
    exit /b 1
)

:: Check Bleak
python -c "import bleak" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Bleak library not installed
    echo Installing now...
    pip install bleak
)

:: Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not detected
    echo Please install Java 17 or higher
    pause
    exit /b 1
)

echo Starting application...
echo.

:: Run application
if exist "BLETestApp-1.0.0.jar" (
    java -jar BLETestApp-1.0.0.jar
) else if exist "build\libs\BLETestApp-1.0.0.jar" (
    java -jar build\libs\BLETestApp-1.0.0.jar
) else (
    echo [ERROR] JAR file not found
    echo Please run: gradlew jar
    pause
    exit /b 1
)

pause