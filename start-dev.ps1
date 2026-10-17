# 雨课堂自动化做题平台 · P0 本地一键启动（Windows PowerShell）
# 依赖：Python 3.10+、Node 22+、JDK 21、Maven（backend 需要）
# 用法：在项目根目录执行  powershell -ExecutionPolicy Bypass -File .\start-dev.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$course = $env:AGENT_COURSE

function Start-Svc($name, $dir, $cmd) {
    Write-Host ("[启动] {0}  ->  {1}" -f $name, $dir)
    Start-Process powershell -ArgumentList @(
        "-NoExit", "-Command",
        "`$host.UI.RawUI.WindowTitle='ykt-$name'; Set-Location '$dir'; $cmd"
    ) | Out-Null
}

Write-Host "=== 雨课堂自动化做题平台 P0 启动 ===" -ForegroundColor Cyan

# 统一用 python -m 调用 pip / uvicorn：机器上可能有多个 Python，
# 裸 uvicorn.exe / pip.exe 绑定的解释器不一定装有依赖，python -m 保证与 python 同环境
Start-Svc "matcher"   (Join-Path $root "matcher")   "python -m pip install -r requirements.txt; python -m uvicorn app:app --host 0.0.0.0 --port 8100"
Start-Svc "collector" (Join-Path $root "collector") "python -m pip install -r requirements.txt; python -m uvicorn app:app --host 0.0.0.0 --port 8200"
Start-Svc "backend"   (Join-Path $root "backend")   "mvn spring-boot:run"
Start-Svc "frontend"  (Join-Path $root "frontend")  "npm install; npm run dev"

$agentCmd = "python -m pip install -r requirements.txt; python -m playwright install chromium"
if ($course) { $agentCmd += "; `$env:AGENT_COURSE='$course'" }
$agentCmd += "; python main.py"
Start-Svc "agent" (Join-Path $root "agent") $agentCmd

Write-Host ""
Write-Host "已分别在新窗口启动：matcher(8100) collector(8200) backend(8080) frontend(5173) agent" -ForegroundColor Green
Write-Host "前端入口：http://localhost:5173" -ForegroundColor Green
if (-not $course) {
    Write-Host "提示：未设置 AGENT_COURSE，agent 将自动进入「我听的课」中的首门课程。" -ForegroundColor Yellow
}
