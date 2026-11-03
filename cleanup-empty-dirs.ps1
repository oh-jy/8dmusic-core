$workspace = (Resolve-Path 'D:\temp\tools\8dmusic-kt').Path
$targets = @(
  'D:\temp\tools\8dmusic-kt\app\src\main\java\j5i\spatial8d\app',
  'D:\temp\tools\8dmusic-kt\app\src\main\main\res\values',
  'D:\temp\tools\8dmusic-kt\app\src\main\res\res\values',
  'D:\temp\tools\8dmusic-kt\core\src\main\main\kotlin\j5i\spatial8d\core'
)

$removed = 0
foreach ($t in $targets) {
  if (-not (Test-Path -LiteralPath $t)) {
    Write-Host "SKIP missing: $t"
    continue
  }
  $resolved = (Resolve-Path -LiteralPath $t).Path
  if (-not $resolved.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Path outside workspace: $resolved"
  }
  $hasContent = @(Get-ChildItem -LiteralPath $resolved -Force | Select-Object -First 1).Count -gt 0
  if ($hasContent) {
    Write-Host "SKIP not empty: $resolved"
    continue
  }
  Remove-Item -LiteralPath $resolved -Force
  Write-Host "REMOVED: $resolved"
  $removed++
}

Write-Host "Done. Removed $removed empty dirs."
