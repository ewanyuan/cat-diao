$ErrorActionPreference = 'Stop'
# Nest receiver port. The rule is by port and Private/LocalSubnet, not by python.exe.
$port = 8793
$ruleName = '猫叼小窝'
$command = "New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Private -RemoteAddress LocalSubnet"

function Test-CatDiaoAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

$existing = $null
try {
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
} catch {
    $existing = $null
}
if (-not $existing) {
    $admin = $false
    try { $admin = Test-CatDiaoAdmin } catch { $admin = $false }
    if (-not $admin) {
        Write-Output '猫叼小窝的防火墙规则还没建。请用管理员 PowerShell 运行下面这一行（规则按端口，不按 python.exe）：'
        Write-Output $command
    } else {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Private -RemoteAddress LocalSubnet | Out-Null
        Write-Output '已添加专用网络、仅局域网的入站规则。'
    }
} else {
    Write-Output '防火墙规则已存在。'
}
try {
    $profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue)
    foreach ($profile in $profiles) {
        if ($profile.NetworkCategory -eq 'Public') {
            Write-Output ("网络「" + $profile.Name + "」现在是公用网络。请在 Windows 设置里改成专用网络，手机才能连上猫叼小窝。")
        }
    }
} catch {
    Write-Output '未能读取当前网络类型。若手机连不上，请把 Wi-Fi 设为专用网络。'
}
