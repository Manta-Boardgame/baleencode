# Keeps Windows from sleeping while a long agent goal runs. The display may still turn off.
# The request is tied to this process: it ends when the process is stopped, or when the app's server (ParentPid) exits.
param([int]$ParentPid)

Add-Type -Namespace BaleenCode -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'

# ES_CONTINUOUS (0x80000000) | ES_SYSTEM_REQUIRED (0x00000001)
[void][BaleenCode.Power]::SetThreadExecutionState([uint32]2147483649)

while (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) {
  Start-Sleep -Seconds 20
}
