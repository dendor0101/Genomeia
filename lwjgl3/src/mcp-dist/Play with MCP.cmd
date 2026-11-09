@echo off
cd /d "%~dp0"
java -jar "%~dp0Genomeia.jar" --enable-mcp
if errorlevel 1 pause
