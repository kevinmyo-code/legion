# Generate the pantry test receipt image.
#
# Why this exists
# ---------------
# Pantry's macro guardrail (ticket 09 resolution section 2, TREATMENT B) only
# renders once a receipt exists in the database, and there was no way to get
# one without photographing a real receipt. That left the single decision
# CLAUDE.md section 4 rule 5 makes non-negotiable - estimates physically
# separated from receipt facts - built, reviewed, and never seen on a screen.
#
# The reconciliation gate (PantryReceiptAgent) requires the receipt's own
# printed grand total to equal the sum of its line items EXACTLY. So this
# receipt deliberately carries NO TAX LINE: a tax line is not an item, so it
# would make the printed total exceed the item sum and quarantine the whole
# document. That is the gate working correctly, but it is not what this
# fixture is for.
#
# PowerShell + System.Drawing rather than the Python used for the ledger
# fixture, because pantry ingests a PHOTO (vision), not a born-digital PDF,
# and rendering text to a raster needs a font engine. Windows-only, which is
# acceptable for a test fixture generator that is run by hand.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tools/make_pantry_fixture.ps1

Add-Type -AssemblyName System.Drawing

# Repo root is the current directory when run as documented, so no dependence
# on $PSScriptRoot - it is empty when the script is dot-sourced or piped,
# which is how it has to be invoked on a machine whose execution policy
# refuses unsigned script FILES.
$repoRoot = (Get-Location).Path
$outPath = Join-Path $repoRoot 'app\src\test\resources\pantry_fixtures\synthetic_receipt.png'
New-Item -ItemType Directory -Force -Path (Split-Path $outPath -Parent) | Out-Null

# name, price in cents. The printed total is summed from these, never typed -
# a fixture whose own total is a typo would test the opposite of what it is for.
$itemNames  = @('ORGANIC WHOLE MILK 1 GAL', 'CHICKEN BREAST BONELESS', 'BANANAS ORGANIC 2LB', 'SOURDOUGH BREAD LOAF', 'BABY SPINACH 16OZ')
$itemCents  = @(649, 1287, 198, 399, 449)
$totalCents = 0
foreach ($c in $itemCents) { $totalCents += $c }

function Format-Money([int]$cents) { '{0}.{1:D2}' -f [math]::Floor($cents / 100), ($cents % 100) }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('      TRADER JOES #452')
$lines.Add('       SAN JOSE  CA')
$lines.Add('')
$lines.Add('  04/18/2026        14:22')
$lines.Add('--------------------------')
$lines.Add('')
for ($i = 0; $i -lt $itemNames.Count; $i++) {
    # Left-aligned name, right-aligned price, 26-column receipt tape.
    $price = Format-Money $itemCents[$i]
    $pad = 26 - $itemNames[$i].Length - $price.Length
    if ($pad -lt 1) { $pad = 1 }
    $lines.Add($itemNames[$i] + (' ' * $pad) + $price)
}
$lines.Add('')
$lines.Add('--------------------------')
$total = Format-Money $totalCents
$lines.Add('TOTAL' + (' ' * (21 - $total.Length)) + $total)
$lines.Add('')
$lines.Add('   ITEMS SOLD: ' + $itemNames.Count)
$lines.Add('')
$lines.Add('  THANK YOU FOR SHOPPING')

$font = New-Object System.Drawing.Font('Consolas', 20, [System.Drawing.FontStyle]::Regular)
$lineHeight = 30
$width = 520
$height = ($lines.Count * $lineHeight) + 80

$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$g = [System.Drawing.Graphics]::FromImage($bitmap)
$g.Clear([System.Drawing.Color]::White)
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::Black)

$y = 30
foreach ($line in $lines) {
    if ($line -ne '') { $g.DrawString($line, $font, $brush, 24, $y) }
    $y += $lineHeight
}

$g.Dispose()
$bitmap.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()

Write-Output "wrote $outPath"
Write-Output "printed total $(Format-Money $totalCents) from $($itemNames.Count) items - this is what must reconcile"
