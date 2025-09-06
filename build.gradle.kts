import org.gradle.internal.os.OperatingSystem

plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.0.14"  // 使用与 Xcup 相同版本
}

group = "com.example.bletest"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // JSON处理
    implementation("com.google.code.gson:gson:2.10.1")

    // 日志
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

javafx {
    version = "21.0.3"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.base", "javafx.graphics")
}

application {
    mainClass.set("com.example.bletest.BLETestApp")
    applicationName = "BLETestApp"
}

// 配置 JAR 任务
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Class-Path" to configurations.runtimeClasspath.get().joinToString(" ") { it.name }
        )
    }
}

// Python 相关配置
val pyWorkDir = layout.buildDirectory.dir("py")
val venvPython = pyWorkDir.map { it.file("venv/Scripts/python.exe") }

// 创建 Python venv
tasks.register<Exec>("pySetupVenv") {
    group = "python"
    description = "Create Python venv and bootstrap pip"
    workingDir = pyWorkDir.get().asFile
    doFirst { workingDir.mkdirs() }
    commandLine("cmd", "/c", """
        python -m venv venv --clear ^
        && venv\Scripts\python -m pip config set global.index-url https://pypi.org/simple ^
        && venv\Scripts\python -m pip install -U pip wheel setuptools
    """.trimIndent())
}

// 安装 Python 依赖
tasks.register<Exec>("pyInstallDeps") {
    group = "python"
    description = "Install Python dependencies"
    dependsOn("pySetupVenv")
    workingDir = pyWorkDir.get().asFile

    // 使用使用阿里云镜像
    commandLine(
        venvPython.get().asFile.absolutePath,
        "-m", "pip", "install",
        "--index-url", "https://mirrors.aliyun.com/pypi/simple/",
        "--trusted-host", "mirrors.aliyun.com",
        "bleak", "pyinstaller", "winsdk"
    )
}

// 构建 Python EXE
tasks.register<Exec>("pyBuild") {
    group = "python"
    description = "Build Python service as EXE"
    dependsOn("pyInstallDeps")
    workingDir = pyWorkDir.get().asFile

    val scriptPath = project.file("src/main/resources/ble_service.py").absolutePath
    val exeOutput = layout.buildDirectory.file("py/dist/ble_service.exe")

    inputs.file(scriptPath)
    outputs.file(exeOutput)

    commandLine(
        venvPython.get().asFile.absolutePath,
        "-m", "PyInstaller",
        "--onefile",
        "--noconsole",
        "--name", "ble_service",
        "--hidden-import", "winsdk.windows.devices.bluetooth",
        "--hidden-import", "winsdk.windows.devices.enumeration",
        "--hidden-import", "winsdk.windows.devices.bluetooth.genericattributeprofile",
        scriptPath
    )
}

// 准备分发目录（类似 Xcup 的方式）
tasks.create<Copy>("prepareDistribution") {
    dependsOn("build", "pyBuild")

    val distDir = layout.buildDirectory.dir("dist/BLETestApp")

    // 复制主 JAR
    from(tasks.jar)
    into(distDir)

    // 复制所有依赖（包括 JavaFX）
    from(configurations.runtimeClasspath) {
        into("lib")
    }

    // 复制 Python EXE
    from(layout.buildDirectory.file("py/dist/ble_service.exe")) {
        into(".")
    }

    // 复制 Python 源文件（Java 程序需要）
    from("src/main/resources") {
        include("ble_service.py")
        into(".")
    }

    // 复制其他资源文件
    from("src/main/resources") {
        include("**/*")
        exclude("ble_service.py")  // 已经复制到根目录
        into("resources")
    }

    // 创建启动脚本
    doLast {
        val scriptDir = distDir.get().asFile

        // Windows 批处理文件
        val batFile = File(scriptDir, "BLETestApp.bat")
        batFile.writeText("""
@echo off
chcp 65001 > nul
set JAVA_HOME=%JAVA_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

REM 设置当前目录为库搜索路径
set PATH=%~dp0;%~dp0\lib;%PATH%

REM 获取 JavaFX 路径
set MODULE_PATH=lib
set CLASS_PATH=BLETestApp-${version}.jar;lib/*

REM 设置编码
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8

java --module-path "%MODULE_PATH%" --add-modules javafx.controls,javafx.fxml,javafx.base,javafx.graphics --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -Dprism.order=sw -Djavafx.animation.framerate=60 -Xmx2g -cp "%CLASS_PATH%" com.example.bletest.BLETestApp %*
        """.trimIndent())

        // 创建 VBS 脚本（无窗口启动）
        val vbsFile = File(scriptDir, "BLETestApp.vbs")
        vbsFile.writeText("""
            Set WshShell = CreateObject("WScript.Shell")
            WshShell.Run chr(34) & "BLETestApp.bat" & Chr(34), 0
            Set WshShell = Nothing
        """.trimIndent())

        // 创建调试脚本
        val debugFile = File(scriptDir, "BLETestApp-debug.bat")
        debugFile.writeText("""
@echo off
chcp 65001 > nul
echo ===== BLETestApp Debug Mode =====
echo.
echo Checking JavaFX modules in lib directory...
dir lib\javafx*.jar
echo.
echo Checking Python service...
if exist ble_service.exe (
    echo Found: ble_service.exe
) else (
    echo Warning: ble_service.exe not found!
)
echo.
echo Starting application...
echo.

set PATH=%~dp0;%~dp0\lib;C:\Windows\System32;%PATH%
set MODULE_PATH=lib
set CLASS_PATH=BLETestApp-${version}.jar;lib/*

REM Fix Chinese system encoding
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8

java --module-path "%MODULE_PATH%" --add-modules javafx.controls,javafx.fxml,javafx.base,javafx.graphics --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -Dprism.order=sw -Djavafx.animation.framerate=60 -Xmx2g -cp "%CLASS_PATH%" com.example.bletest.BLETestApp

echo.
pause
        """.trimIndent())
    }
}

// 使用 jpackage 创建安装包
tasks.create("createInstaller") {
    dependsOn("prepareDistribution")

    doLast {
        val distDir = layout.buildDirectory.dir("dist/BLETestApp").get().asFile
        val outputDir = layout.buildDirectory.dir("installer").get().asFile
        outputDir.mkdirs()

        // 尝试多种方式查找 jpackage
        val jpackageExe = if (OperatingSystem.current().isWindows) "jpackage.exe" else "jpackage"

        // 方式1：从 JAVA_HOME 环境变量
        var jpackagePath = System.getenv("JAVA_HOME")?.let { "$it/bin/$jpackageExe" }

        // 方式2：从系统属性 java.home
        if (jpackagePath == null || !File(jpackagePath).exists()) {
            jpackagePath = "${System.getProperty("java.home")}/bin/$jpackageExe"
        }

        // 方式3：尝试直接使用 jpackage（依赖 PATH）
        if (!File(jpackagePath).exists()) {
            // 检查 jpackage 是否在 PATH 中
            val checkProcess = ProcessBuilder("where", "jpackage")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val exitCode = checkProcess.waitFor()
            if (exitCode == 0) {
                jpackagePath = "jpackage"  // 直接使用 PATH 中的 jpackage
                println("Using jpackage from PATH")
            } else {
                // 方式4：尝试常见的 JDK 安装路径
                val commonPaths = listOf(
                    "C:/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot/bin/jpackage.exe",
                    "C:/Program Files/Java/jdk-21/bin/jpackage.exe",
                    "C:/Program Files/OpenJDK/jdk-21/bin/jpackage.exe"
                )

                jpackagePath = commonPaths.find { File(it).exists() }

                if (jpackagePath == null) {
                    throw GradleException("""
                        jpackage not found. Please ensure:
                        1. You're using JDK 14+ (you have JDK 21, which is good)
                        2. Set JAVA_HOME environment variable to your JDK directory
                        3. Or configure Gradle to use the correct JDK
                        
                        Current java.home: ${System.getProperty("java.home")}
                        JAVA_HOME env: ${System.getenv("JAVA_HOME") ?: "not set"}
                        
                        You can also manually specify the JDK path in gradle.properties:
                        org.gradle.java.home=C:/Path/To/Your/JDK
                    """.trimIndent())
                }
            }
        }

        println("Using jpackage at: $jpackagePath")

        // 构建 jpackage 命令
        val command = mutableListOf(
            jpackagePath,
            "--type", "msi",
            "--name", "BLETestApp",
            "--app-version", version.toString(),
            "--vendor", "Example Company",
            "--description", "BLE Test Application with Python Backend",
            "--input", distDir.absolutePath,
            "--main-jar", "BLETestApp-${version}.jar",
            "--main-class", "com.example.bletest.BLETestApp",
            "--module-path", distDir.resolve("lib").absolutePath,
            "--add-modules", "javafx.controls,javafx.fxml,javafx.base,javafx.graphics,java.logging",
            "--dest", outputDir.absolutePath,
            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser",
            "--win-menu-group", "BLE Test",
            "--win-console"  // 保留控制台用于调试
        )

        // 添加额外的应用内容（Python文件）
        val pythonExe = distDir.resolve("ble_service.exe")
        val pythonScript = distDir.resolve("ble_service.py")

        if (pythonExe.exists()) {
            command.addAll(listOf("--app-content", pythonExe.absolutePath))
        }
        if (pythonScript.exists()) {
            command.addAll(listOf("--app-content", pythonScript.absolutePath))
        }

        // 添加 resources 目录中的其他资源
        val resourcesDir = distDir.resolve("resources")
        if (resourcesDir.exists() && resourcesDir.isDirectory()) {
            resourcesDir.walkTopDown().forEach { file ->
                if (file.isFile()) {
                    command.addAll(listOf("--app-content", file.absolutePath))
                }
            }
        }

        // 添加图标（如果存在）
        val iconFile = file("src/main/resources/icon.ico")
        if (iconFile.exists()) {
            command.addAll(listOf("--icon", iconFile.absolutePath))
        }

        // 添加 JVM 参数
        command.addAll(listOf(
            "--java-options", "-Xmx2g",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dsun.jnu.encoding=UTF-8",
            "--java-options", "--add-modules=javafx.controls,javafx.fxml,java.logging",
            "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--java-options", "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--java-options", "-Dprism.order=sw"
        ))

        println("Executing: ${command.joinToString(" ")}")

        // 执行命令
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val processExitCode = process.waitFor()
        if (processExitCode != 0) {
            throw GradleException("jpackage failed with exit code $processExitCode")
        }

        println("Installer created successfully in: $outputDir")
    }
}

// 清理任务
tasks.create<Delete>("cleanDist") {
    delete(layout.buildDirectory.dir("dist"))
    delete(layout.buildDirectory.dir("installer"))
    delete(layout.buildDirectory.dir("py"))
}

tasks.test {
    useJUnitPlatform()
}

// 运行任务配置
tasks.named<JavaExec>("run") {
    doFirst {
        println("========================================")
        println("BLE测试应用 - Windows版")
        println("========================================")

        // 检查 Python
        try {
            val process = ProcessBuilder("python", "--version").start()
            val version = process.inputStream.bufferedReader().readText()
            println("检测到: $version")
        } catch (e: Exception) {
            println("警告: 未检测到Python，请确保已安装")
        }
    }

    // 添加 JVM 参数
    jvmArgs = listOf(
        "--module-path", "build/libs",
        "--add-modules", "javafx.controls,javafx.fxml",
        "-Dprism.order=sw",
        "-Djavafx.animation.framerate=60",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8"
    )
}