@echo off
rem ===========================================================================
rem  hs - Windows launcher.
rem
rem  THIS FILE IS ASCII-ONLY ON PURPOSE. Do not put Korean or emoji in it.
rem  cmd.exe reads a .bat in the console's OEM code page, not UTF-8, so a
rem  UTF-8 Korean comment turns into bytes cmd then tries to RUN, and the
rem  script dies with "'..' is not recognized as an internal or external
rem  command" before it does anything. Measured 2026-09-16.
rem
rem  Everything this script would want to say in Korean is said by the Java
rem  program instead, which is UTF-8 all the way through.
rem  See: docs/reports/2026-09-16/troubleshooting/
rem       (see the report index for the exact file name)
rem
rem  Usage:  bin\hs list
rem          bin\hs show <id>
rem          bin\hs test <id> [--base <class>]
rem
rem  Needs Java 21 (hindsight-core compiles to 21).
rem ===========================================================================
setlocal

set "HS_JAR=%~dp0..\build\hs\hs.jar"

if not exist "%HS_JAR%" (
  echo hs.jar not found: %HS_JAR%
  echo.
  echo Build it first:  gradlew.bat :hindsight-core:hsJar
  exit /b 2
)

set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"

"%JAVA_BIN%" -version >nul 2>&1
if errorlevel 1 (
  echo No java found. Set JAVA_HOME or put java on PATH.
  exit /b 2
)

rem Do NOT try to parse `java -version` here. Tried it 2026-09-16 and cmd's
rem for/f + delayed expansion got it wrong silently: HS_MAJOR came out empty,
rem the guard fell through, and Java 17 reached the jar anyway. A guard that
rem quietly does nothing is worse than no guard.
rem So: just run it, and explain afterwards if it failed.
"%JAVA_BIN%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%HS_JAR%" %*
set "HS_CODE=%ERRORLEVEL%"

rem Exit code 1 from the JVM itself (not from hs, which uses 0/1/2 for its own
rem reasons) is what an UnsupportedClassVersionError looks like from out here.
rem We cannot tell them apart without capturing output, so we only add a hint,
rem never replace the real error above.
if %HS_CODE% NEQ 0 (
  echo.
  echo [hs] If the error above says "class file version", this java is too old.
  echo      hindsight-core compiles to Java 21.
  echo      java in use: %JAVA_BIN%
  echo      Java 21 this repo already downloaded:
  echo        %USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2
)
exit /b %HS_CODE%

