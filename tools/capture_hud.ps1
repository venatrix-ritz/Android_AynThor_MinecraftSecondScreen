<#
.SYNOPSIS
    Drives the companion app through HUD scenarios and screenshots the result.

.DESCRIPTION
    Runs hud_sim.py in place of the real mod, restarts the app so it reconnects,
    and pulls a screenshot of the second display for each scenario. Requires
    `adb reverse tcp:48291 tcp:48291` to be active so the app's connection to
    127.0.0.1:48291 lands on this machine.

    Minecraft must NOT be running -- the real mod binds the same port on device.

.EXAMPLE
    adb reverse tcp:48291 tcp:48291
    .\capture_hud.ps1 -Jar .\mc_client.jar -Scenarios absorption,drowning -OutDir .\shots

.EXAMPLE
    # A container scenario has to be shot on the Items tab; the app starts on HUD.
    .\capture_hud.ps1 -Jar .\mc_client.jar -Scenarios stonecutter,loom -Tab Items -OutDir .\shots
#>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string[]]$Scenarios,
    [string]$OutDir = ".",
    [string]$Adb = "adb",
    # SurfaceFlinger display id of the Thor's bottom screen, for screencap. Find
    # yours with `adb shell dumpsys SurfaceFlinger --display-id`. Note screencap
    # needs this *physical* id -- passing the logical one returns a 0-byte file --
    # while `input` needs the logical id. They are not interchangeable.
    [string]$DisplayId = "4630946482288158084",
    # Logical display id, for tapping. `input -d 4 tap X Y`.
    [int]$LogicalDisplayId = 4,
    # Which tab to switch to before capturing. The app always starts on HUD, so a
    # container scenario captured without this is a screenshot of the map.
    [ValidateSet("HUD", "Chat", "Items", "Input", "Settings")]
    [string]$Tab = "HUD",
    [string]$Package = "com.exojosh.minecraftsecondscreen"
)

$ErrorActionPreference = "Stop"
$simPath = Join-Path $PSScriptRoot "hud_sim.py"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Where the tab strip's buttons are, derived from the display rather than
# hardcoded, so this works on hardware that isn't the Thor. The numbers mirror
# SecondScreenApp: a 44dp strip at the bottom holding five equal-weight buttons
# with 4dp of horizontal padding and 4dp between them.
$TAB_ORDER = @("HUD", "Chat", "Items", "Input", "Settings")
$TAB_STRIP_DP = 44
$TAB_PAD_DP = 4
$TAB_GAP_DP = 4

function Get-TabTapPoint([string]$name) {
    $sizeText = (& $Adb shell wm size -d $LogicalDisplayId) -join " "
    $densityText = (& $Adb shell wm density -d $LogicalDisplayId) -join " "
    if ($sizeText -notmatch '(\d+)x(\d+)') { throw "could not read display size: $sizeText" }
    # `wm size` reports the panel's native orientation; the Presentation renders
    # landscape, so the wider of the two is the width whichever way round it comes.
    $a = [int]$Matches[1]; $b = [int]$Matches[2]
    $width = [Math]::Max($a, $b); $height = [Math]::Min($a, $b)
    if ($densityText -notmatch '(\d+)') { throw "could not read density: $densityText" }
    $scale = [int]$Matches[1] / 160.0

    $pad = $TAB_PAD_DP * $scale
    $gap = $TAB_GAP_DP * $scale
    $strip = $TAB_STRIP_DP * $scale
    $button = ($width - 2 * $pad - 4 * $gap) / 5
    $index = $TAB_ORDER.IndexOf($name)
    if ($index -lt 0) { throw "unknown tab $name" }

    $x = $pad + $index * ($button + $gap) + $button / 2
    $y = $height - $strip / 2
    return @([int]$x, [int]$y)
}

foreach ($scenario in $Scenarios) {
    Get-Process python -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 800

    # -u so the log isn't buffered away when stdout isn't a TTY.
    #
    # Each path is quoted *inside* the argument list rather than relying on
    # Start-Process to do it. It doesn't: an unquoted path containing a space
    # arrives at python split across argv, and the failure is
    # "can't open file 'E:\Documents\IntelliJ\Minecraft'" -- which looks like a
    # missing file rather than a quoting bug, and leaves the app showing its
    # port-conflict banner because nothing ever answered on 48291.
    Start-Process -FilePath "python" `
        -ArgumentList @("-u", "`"$simPath`"", "`"$Jar`"", $scenario) `
        -RedirectStandardOutput (Join-Path $OutDir "sim_$scenario.log") `
        -RedirectStandardError (Join-Path $OutDir "sim_$scenario.err") `
        -PassThru -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 4

    & $Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 800
    & $Adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 8

    if ($Tab -ne "HUD") {
        $point = Get-TabTapPoint $Tab
        & $Adb shell input -d $LogicalDisplayId tap $point[0] $point[1] | Out-Null
        # Long enough for the panel to lay out and for the first round of
        # on-demand icon requests to come back over the socket -- a shot taken
        # immediately catches empty cells and reads as broken icon delivery.
        Start-Sleep -Seconds 4
    }

    & $Adb shell "screencap -p -d $DisplayId /sdcard/hud_capture.png" | Out-Null
    & $Adb pull /sdcard/hud_capture.png (Join-Path $OutDir "hud_$scenario.png") | Out-Null
    & $Adb shell rm /sdcard/hud_capture.png | Out-Null
    Write-Host "captured $scenario"
}

Get-Process python -ErrorAction SilentlyContinue | Stop-Process -Force
