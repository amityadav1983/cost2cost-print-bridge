@echo off

setlocal

set DIR=%~dp0
set WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
set WRAPPER_SHARED_JAR=%DIR%gradle\wrapper\gradle-wrapper-shared.jar
set CLASSPATH=%WRAPPER_JAR%;%WRAPPER_SHARED_JAR%

if not exist "%WRAPPER_JAR%" (
    echo Missing Gradle wrapper jar(s) under %DIR%gradle\wrapper
    echo Restore the wrapper JAR before building.
    exit /b 1
)

if not exist "%WRAPPER_SHARED_JAR%" (
    echo Missing Gradle wrapper jar(s) under %DIR%gradle\wrapper
    echo Restore the wrapper JAR before building.
    exit /b 1
)

if "%JAVA_HOME%"=="" (
    set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JAVA_HOME is not set to a valid JDK. Set JAVA_HOME to a JDK 17+ installation.
    exit /b 1
)

"%JAVA_HOME%\bin\java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
