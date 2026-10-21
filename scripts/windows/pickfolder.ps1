# Opens the standard Windows folder picker and prints the chosen path (empty if cancelled)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$owner.StartPosition = 'CenterScreen'
$owner.Size = New-Object System.Drawing.Size(0, 0)
$dlg = New-Object System.Windows.Forms.FolderBrowserDialog
$dlg.Description = 'Choose the working folder for the assistant'
$dlg.ShowNewFolderButton = $true
$owner.Show()
if ($dlg.ShowDialog($owner) -eq 'OK') { Write-Output $dlg.SelectedPath }
[Environment]::Exit(0)
