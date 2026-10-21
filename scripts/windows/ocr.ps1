# Read text from an image using the built-in Windows OCR engine (no internet connection needed).
# Japanese is preferred when installed; otherwise the user's profile languages are used.
param([Parameter(Mandatory = $true)][string]$Path)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, $resultType) {
    $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($op))
    $task.Wait(60000) | Out-Null
    $task.Result
}

[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime] | Out-Null

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$lang = New-Object Windows.Globalization.Language 'ja'
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($lang)
if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
if ($null -eq $engine) { throw 'No OCR language is installed on this PC.' }

# Refuse absurd dimensions ("decompression bombs") and scale large images down to what the OCR engine accepts
$w = $decoder.PixelWidth; $h = $decoder.PixelHeight
if ($w -le 0 -or $h -le 0 -or ([double]$w * $h) -gt 400000000) { throw 'The image dimensions are invalid or too large.' }
$max = [Windows.Media.Ocr.OcrEngine]::MaxImageDimension
if ($w -gt $max -or $h -gt $max) {
    $scale = [Math]::Min($max / $w, $max / $h)
    $t = New-Object Windows.Graphics.Imaging.BitmapTransform
    $t.ScaledWidth = [uint32][Math]::Floor($w * $scale)
    $t.ScaledHeight = [uint32][Math]::Floor($h * $scale)
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync($decoder.BitmapPixelFormat, $decoder.BitmapAlphaMode, $t,
        [Windows.Graphics.Imaging.ExifOrientationMode]::RespectExifOrientation,
        [Windows.Graphics.Imaging.ColorManagementMode]::DoNotColorManage)) ([Windows.Graphics.Imaging.SoftwareBitmap])
} else {
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
}

$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

$sb = New-Object System.Text.StringBuilder
foreach ($line in $result.Lines) { [void]$sb.AppendLine($line.Text) }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::Out.Write($sb.ToString())

# WinRT keeps PowerShell alive otherwise
[Environment]::Exit(0)
