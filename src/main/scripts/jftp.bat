@echo off
setlocal
if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\java.exe" (
        >&2 echo Java was not found under JAVA_HOME: "%JAVA_HOME%"
        exit /b 1
    )
    set "JFTP_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    for %%I in (java.exe) do set "JFTP_JAVA=%%~$PATH:I"
    if not defined JFTP_JAVA (
        >&2 echo Java was not found on PATH. Set JAVA_HOME to the JDK directory.
        exit /b 1
    )
)
"%JFTP_JAVA%" -jar "%~dp0${project.build.finalName}.jar"
exit /b %ERRORLEVEL%
