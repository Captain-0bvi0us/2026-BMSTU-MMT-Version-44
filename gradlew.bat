@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%"=="" @echo off
@set DIRNAME=%~dp0
@if "%DIRNAME%"=="" set DIRNAME=.
@set APP_BASE_NAME=%~n0
@set APP_HOME=%DIRNAME%
@for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
@set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
@set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
@set JAVA_EXE=java.exe
@%JAVA_EXE% -version >NUL 2>&1
@if "%ERRORLEVEL%" == "0" goto execute

@echo.
@echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
@echo.
@echo Please set the JAVA_HOME variable in your environment to match the
@echo location of your Java installation.
@goto fail

:execute
@set CMD_LINE_ARGS=%*
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

:end
@if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal
