# Files the four public mwa-gauntlet issues (M3-M6) on solana-mobile/mobile-wallet-adapter
# from your own GitHub account with gh. Safe to run twice: issues you already filed with the
# same title are skipped.
$ErrorActionPreference = 'Stop'
$repo = 'solana-mobile/mobile-wallet-adapter'
$base = 'https://raw.githubusercontent.com/let-the-dreamers-rise/mwa-gauntlet/ops/ops/issues'
gh auth status | Out-Null
$tmp = Join-Path $env:TEMP ('mwa-issues-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
$list = Invoke-RestMethod "$base/issues.json"
$mine = gh issue list -R $repo --author '@me' --state all --limit 200 --json title,url | ConvertFrom-Json
foreach ($i in $list) {
  $existing = $mine | Where-Object { $_.title -eq $i.title }
  if ($existing) { Write-Host ("SKIP (already filed): " + $existing.url); continue }
  $file = Join-Path $tmp ($i.key + '.md')
  Invoke-WebRequest "$base/$($i.key).md" -OutFile $file -UseBasicParsing
  $url = gh issue create -R $repo --title $i.title --body-file $file
  Write-Host ("FILED " + $i.key + ": " + $url)
  Start-Sleep -Seconds 3
}
Remove-Item -Recurse -Force $tmp
Write-Host 'Done. Paste the FILED lines back to Claude.'
