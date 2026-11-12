@echo off
REM ============================================================
REM  Reserve platform ports permanently.
REM  Windows Hyper-V / winnat dynamic port exclusion can swallow
REM  these ports on every reboot (no process to kill - system level).
REM  The only real fix: permanently exclude our ports so dynamic
REM  allocation always avoids them. Run once as Administrator.
REM ============================================================
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Admin rights required. Requesting UAC elevation...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo Current TCP excluded port ranges (diagnostic):
netsh int ipv4 show excludedportrange protocol=tcp
echo.

echo Stopping winnat to release dynamic reservations...
net stop winnat

echo Reserving platform ports permanently (duplicate errors are harmless):
netsh int ipv4 add excludedportrange protocol=tcp startport=25173 numberofports=1
netsh int ipv4 add excludedportrange protocol=tcp startport=28080 numberofports=1
netsh int ipv4 add excludedportrange protocol=tcp startport=28100 numberofports=1
netsh int ipv4 add excludedportrange protocol=tcp startport=28200 numberofports=1

echo Starting winnat...
net start winnat

echo.
echo ============================================================
echo  Done! Ports 25173 / 28080 / 28100 / 28200 are now
echo  permanently reserved. They survive reboots.
echo  Close this window and restart services from the launcher -
echo  all ports will come back on their standard values.
echo ============================================================
pause
