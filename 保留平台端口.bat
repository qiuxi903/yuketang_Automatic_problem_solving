@echo off
REM ============================================================
REM  平台端口永久保留：防止 Hyper-V/winnat 动态保留吞掉端口
REM  端口被「保留区间」吞掉时没有任何进程可杀（这是系统行为），
REM  唯一根治方法：把平台端口提前永久排除保留，系统动态分配时自动避开。
REM  右键「以管理员身份运行」一次即可，永久生效。
REM ============================================================
chcp 65001 >nul
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo 需要管理员权限，正在请求 UAC 授权...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo 当前 TCP 排除端口范围（诊断信息）：
netsh int ipv4 show excludedportrange protocol=tcp
echo.

echo 停止 winnat 服务以释放动态保留...
net stop winnat

echo 永久保留平台端口（已保留过的会提示重复，忽略即可）：
netsh int ipv4 add excludedportrange protocol=tcp startport=25173 numberofports=1
netsh int ipv4 add excludedportrange protocol=tcp startport=28080 numberofports=1
netsh int ipv4 add excludedportrange protocol=tcp startport=28100 numberofports=1
netsh int ipv4 add excludedportrange protocol=tcp startport=28200 numberofports=1

echo 重新启动 winnat...
net start winnat

echo.
echo ============================================================
echo  完成！平台端口（25173 / 28080 / 28100 / 28200）已永久保留，
echo  重启电脑也不会被系统吞掉。现在可以关闭本窗口，
echo  回到启动器重新「停止再启动」一次，端口将回到标准值。
echo ============================================================
pause
