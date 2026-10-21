# Desktop-app control for the Agent's "Desktop" mode, built on Windows UI Automation (the accessibility API that
# screen readers use). It works on controls (buttons, text boxes ...) of ONE window, found by part of its title;
# no mouse or keyboard events are synthesised.
#   -Cmd snapshot                      -> list the controls of the window
#   -Cmd act -Path 0/3/2 -Action click|settext|focus|scroll_down|scroll_up [-TextB64 ..] [-ExpectB64 ..]
#   -Cmd shot                          -> screenshot of the window (JPEG, base64)
# Title, text and expectations arrive base64-encoded so no quoting problems or injection are possible.
param(
  [string]$Cmd, [string]$TitleB64 = '', [string]$Path = '', [string]$Action = '', [string]$TextB64 = '',
  [string]$ExpectB64 = '', [int]$Amount = 3, [int]$MaxNodes = 400, [int]$MaxW = 1280
)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

function Dec([string]$b) { if ($b) { [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b)) } else { '' } }
$Title = Dec $TitleB64
$UIA = [System.Windows.Automation.AutomationElement]
$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

function Find-Window {
  if (-not $Title) { throw 'No target window was given.' }
  $kids = $UIA::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
  foreach ($k in $kids) {
    $n = $k.Current.Name
    if ($n -and $n.IndexOf($Title, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { return $k }
  }
  throw "No window with '$Title' in its title was found. Is the app open?"
}

function Pattern-Names($el) {
  @($el.GetSupportedPatterns() | ForEach-Object { $_.ProgrammaticName -replace 'PatternIdentifiers\.Pattern$', '' })
}

function Type-Name($el) { $el.Current.ControlType.ProgrammaticName -replace '^ControlType\.', '' }

function Out-Json($o) { Write-Output ($o | ConvertTo-Json -Compress -Depth 5) }

function Get-ByPath($win, [string]$p) {
  if ($p -notmatch '^\d+(/\d+)*$') { throw 'Bad element path.' }
  $el = $win
  foreach ($idx in $p.Split('/')) {
    $c = $walker.GetFirstChild($el)
    for ($k = 0; $k -lt [int]$idx -and $null -ne $c; $k++) { $c = $walker.GetNextSibling($c) }
    if ($null -eq $c) { throw 'That control is gone. Take a new snapshot.' }
    $el = $c
  }
  return $el
}

try {
  $win = Find-Window
  switch ($Cmd) {
    'snapshot' {
      $script:items = New-Object System.Collections.ArrayList
      $script:cut = $false
      function Walk($el, [string]$path, [int]$depth) {
        if ($depth -gt 14) { return }
        $i = 0
        $c = $walker.GetFirstChild($el)
        while ($null -ne $c) {
          if ($script:items.Count -ge $MaxNodes) { $script:cut = $true; return }
          $p = if ($path -eq '') { "$i" } else { "$path/$i" }
          $cur = $c.Current
          if (-not $cur.IsOffscreen) {
            $name = [string]$cur.Name
            $pats = Pattern-Names $c
            $interactive = ($pats -contains 'Invoke') -or ($pats -contains 'Value') -or ($pats -contains 'Toggle') -or ($pats -contains 'SelectionItem') -or ($pats -contains 'ExpandCollapse') -or ($pats -contains 'Scroll')
            $type = Type-Name $c
            if ($name -or $interactive) {
              $value = ''
              if ($pats -contains 'Value') {
                if ($cur.IsPassword) { $value = '(hidden)' }
                else { $vp = $null; if ($c.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$vp)) { $value = [string]$vp.Current.Value } }
              }
              if ($name.Length -gt 90) { $name = $name.Substring(0, 90) }
              if ($value.Length -gt 120) { $value = $value.Substring(0, 120) }
              [void]$script:items.Add([pscustomobject]@{ path = $p; type = $type; name = $name; value = $value; enabled = $cur.IsEnabled; password = [bool]$cur.IsPassword; pats = $pats -join ',' })
            }
            Walk $c $p ($depth + 1)
          }
          $c = $walker.GetNextSibling($c)
          $i++
        }
      }
      Walk $win '' 0
      $r = $win.Current.BoundingRectangle
      Out-Json ([pscustomobject]@{ window = [string]$win.Current.Name; w = [int]$r.Width; h = [int]$r.Height; cut = $script:cut; items = @($script:items) })
    }
    'act' {
      $el = Get-ByPath $win $Path
      if ($ExpectB64) {
        $exp = (Dec $ExpectB64).Split('|', 2)
        if ((Type-Name $el) -ne $exp[0] -or ([string]$el.Current.Name) -ne $exp[1]) { throw 'The window changed since the snapshot. Take a new snapshot.' }
      }
      $p = $null
      switch ($Action) {
        'click' {
          if ($el.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$p)) { $p.Invoke() }
          elseif ($el.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$p)) { $p.Toggle() }
          elseif ($el.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$p)) { $p.Select() }
          elseif ($el.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$p)) {
            if ($p.Current.ExpandCollapseState -eq 'Collapsed') { $p.Expand() } else { $p.Collapse() }
          }
          else { throw 'This control cannot be clicked through accessibility.' }
        }
        'settext' {
          if (-not $el.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$p)) { throw 'This control does not accept text through accessibility.' }
          if ($p.Current.IsReadOnly) { throw 'This control is read-only.' }
          $p.SetValue((Dec $TextB64))
        }
        'focus' { $el.SetFocus() }
        { $_ -in 'scroll_down', 'scroll_up' } {
          if (-not $el.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$p)) { throw 'This control cannot be scrolled.' }
          $dir = if ($Action -eq 'scroll_down') { [System.Windows.Automation.ScrollAmount]::LargeIncrement } else { [System.Windows.Automation.ScrollAmount]::LargeDecrement }
          for ($k = 0; $k -lt [Math]::Min([Math]::Max($Amount, 1), 20); $k++) { $p.Scroll([System.Windows.Automation.ScrollAmount]::NoAmount, $dir) }
        }
        default { throw "Unknown action: $Action" }
      }
      Out-Json @{ ok = $true }
    }
    'shot' {
      Add-Type -AssemblyName System.Drawing
      try { $win.SetFocus() } catch {}
      Start-Sleep -Milliseconds 300
      $r = $win.Current.BoundingRectangle
      if ($r.Width -lt 50 -or $r.Height -lt 50) { throw 'The window is too small or minimised.' }
      $bmp = New-Object System.Drawing.Bitmap ([int]$r.Width), ([int]$r.Height)
      $g = [System.Drawing.Graphics]::FromImage($bmp)
      $g.CopyFromScreen([int]$r.X, [int]$r.Y, 0, 0, $bmp.Size)
      $g.Dispose()
      $scale = [Math]::Min(1.0, $MaxW / $r.Width)
      $nw = [int][Math]::Round($r.Width * $scale); $nh = [int][Math]::Round($r.Height * $scale)
      $out = New-Object System.Drawing.Bitmap $nw, $nh
      $g2 = [System.Drawing.Graphics]::FromImage($out)
      $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $g2.DrawImage($bmp, 0, 0, $nw, $nh)
      $g2.Dispose(); $bmp.Dispose()
      $enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
      $ep = New-Object System.Drawing.Imaging.EncoderParameters 1
      $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]75)
      $ms = New-Object System.IO.MemoryStream
      $out.Save($ms, $enc, $ep); $out.Dispose()
      Out-Json @{ w = $nw; h = $nh; b64 = [Convert]::ToBase64String($ms.ToArray()) }
    }
    default { throw "Unknown command: $Cmd" }
  }
} catch {
  Write-Output (@{ error = $_.Exception.Message } | ConvertTo-Json -Compress)
}
[Environment]::Exit(0)
