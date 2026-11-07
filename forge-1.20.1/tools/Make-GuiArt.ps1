param(
    # Also writes an 8x zoomed contact sheet of every sprite here (for checking the art).
    [string]$Preview = ''
)
# Builds Temporal's GUI sprites from the hand-drawn pixel grids below (one character per pixel, colours in $palette)
# into src/main/resources/assets/temporal/textures/gui/sprites, plus a nine-slice .mcmeta for sprites that stretch.
# To change the art, edit a grid and run this script again.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$out = Join-Path $PSScriptRoot '..\src\main\resources\assets\temporal\textures\gui\sprites'
New-Item -ItemType Directory -Force $out | Out-Null

# AARRGGBB. Keys are case-sensitive.
$palette = New-Object 'System.Collections.Generic.Dictionary[char,string]'
@{
    [char]'.' = '00000000'  # transparent
    [char]'K' = 'FF05080E'  # outline
    [char]'F' = 'EC0F1624'  # panel fill (lets a little of the blurred world through)
    [char]'H' = 'FF26354D'  # bevel light
    [char]'S' = 'FF0A0F19'  # bevel shadow
    [char]'C' = 'FF3FC6D9'  # cyan accent
    [char]'c' = 'FF237F91'  # cyan, dim
    [char]'B' = 'FF1A2537'  # button face
    [char]'T' = 'FF2E415C'  # button top light
    [char]'R' = 'FF111926'  # button lower shade
    [char]'b' = 'FF22324A'  # button face, hovered
    [char]'t' = 'FF4A6C93'  # button top light, hovered
    [char]'d' = 'FF141B27'  # disabled face
    [char]'e' = 'FF1C2636'  # disabled top light
    [char]'f' = 'FF0B0F17'  # disabled outline
    [char]'I' = 'E0080D16'  # inset fill
    [char]'L' = 'FF1E2A3D'  # inset light edge
    [char]'V' = 'FF070B12'  # slider / switch track
    [char]'G' = 'FF141D2C'  # tab face
    [char]'W' = 'FFE8F1FA'  # white
    [char]'w' = 'FFC9D6E4'  # light grey
    [char]'g' = 'FF8FA0B5'  # grey-blue
    [char]'Y' = 'FFD9A441'  # gold
    [char]'y' = 'FF8A6420'  # dark gold
    [char]'A' = 'FF9BE9F2'  # glass, light
    [char]'a' = 'FF5BBFD0'  # glass
    [char]'Z' = 'FFF2C46B'  # sand
    [char]'M' = 'FF08FAC1'  # Temporal's mint (the logo)
    [char]'0' = 'FF000000'  # event horizon
    [char]'1' = 'FFFFF6E0'  # accretion: white hot
    [char]'2' = 'FFFFD27A'  # accretion: gold
    [char]'3' = 'FFFF9F3D'  # accretion: orange
    [char]'4' = 'FFC2561E'  # accretion: deep orange
    [char]'5' = 'FF6E2A10'  # accretion: embers
}.GetEnumerator() | ForEach-Object { $palette[$_.Key] = $_.Value }

$sprites = [System.Collections.Generic.List[object]]::new()

function Add-Sprite([string]$name, [string[]]$rows, $border = $null) {
    $width = $rows[0].Length
    for ($y = 0; $y -lt $rows.Count; $y++) {
        if ($rows[$y].Length -ne $width) { throw "$name row $y is $($rows[$y].Length) wide, expected $width" }
        foreach ($ch in $rows[$y].ToCharArray()) {
            if (-not $palette.ContainsKey($ch)) { throw "$name row $y uses '$ch', which isn't in the palette" }
        }
    }
    $bitmap = New-Object System.Drawing.Bitmap $width, $rows.Count, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $rows.Count; $y++) {
        for ($x = 0; $x -lt $width; $x++) {
            $hex = $palette[$rows[$y][$x]]
            $c = [System.Drawing.Color]::FromArgb([Convert]::ToInt32($hex.Substring(0, 2), 16), [Convert]::ToInt32($hex.Substring(2, 2), 16),
                    [Convert]::ToInt32($hex.Substring(4, 2), 16), [Convert]::ToInt32($hex.Substring(6, 2), 16))
            $bitmap.SetPixel($x, $y, $c)
        }
    }
    $path = Join-Path $out "$name.png"
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $meta = "$path.mcmeta"
    if ($null -ne $border) {
        $json = '{ "gui": { "scaling": { "type": "nine_slice", "width": ' + $width + ', "height": ' + $rows.Count + ', "border": ' + $border + ' } } }'
        [IO.File]::WriteAllText($meta, $json + "`n")
    } elseif (Test-Path $meta) {
        Remove-Item $meta
    }
    $sprites.Add([pscustomobject]@{ Name = $name; Bitmap = $bitmap })
}

# ---- window panel: dark glass with cyan corner brackets (nine-slice, 4 px border) ----
Add-Sprite 'panel' @(
    'KKKKKKKKKKKKKKKK'
    'KCCCHHHHHHHHCCCK'
    'KCFFFFFFFFFFFFCK'
    'KCFFFFFFFFFFFFCK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KHFFFFFFFFFFFFSK'
    'KCFFFFFFFFFFFFCK'
    'KCFFFFFFFFFFFFCK'
    'KCCCSSSSSSSSCCCK'
    'KKKKKKKKKKKKKKKK'
) 4

# ---- recessed area (option rows, the frame generation viewport) ----
Add-Sprite 'inset' @(
    'SSSSSSSSSSSSSSSL'
    'SKKKKKKKKKKKKKKL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'SKIIIIIIIIIIIIIL'
    'LLLLLLLLLLLLLLLL'
) 2

# ---- buttons ----
Add-Sprite 'button' @(
    'KKKKKKKKKKKKKKKK'
    'KTTTTTTTTTTTTTTK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KTBBBBBBBBBBBBRK'
    'KRRRRRRRRRRRRRRK'
    'KRRRRRRRRRRRRRRK'
    'KKKKKKKKKKKKKKKK'
) 3
Add-Sprite 'button_highlighted' @(
    'CCCCCCCCCCCCCCCC'
    'CttttttttttttttC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CtbbbbbbbbbbbbRC'
    'CRRRRRRRRRRRRRRC'
    'CRRRRRRRRRRRRRRC'
    'CCCCCCCCCCCCCCCC'
) 3
Add-Sprite 'button_disabled' @(
    'ffffffffffffffff'
    'feeeeeeeeeeeeeef'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fedddddddddddddf'
    'fddddddddddddddf'
    'fddddddddddddddf'
    'ffffffffffffffff'
) 3

# ---- tabs: flat, the selected one lit with a cyan underline ----
Add-Sprite 'tab' @(
    'KKKKKKKKKKKKKKKK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KGGGGGGGGGGGGGGK'
    'KKKKKKKKKKKKKKKK'
) 3
Add-Sprite 'tab_highlighted' @(
    'KKKKKKKKKKKKKKKK'
    'KttttttttttttttK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KbbbbbbbbbbbbbbK'
    'KccccccccccccccK'
    'KKKKKKKKKKKKKKKK'
) 3
Add-Sprite 'tab_selected' @(
    'KKKKKKKKKKKKKKKK'
    'KTTTTTTTTTTTTTTK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KBBBBBBBBBBBBBBK'
    'KCCCCCCCCCCCCCCK'
    'KCCCCCCCCCCCCCCK'
    'KKKKKKKKKKKKKKKK'
) 3

# ---- slider: recessed track (nine-slice) and a pill handle with a cyan grip ----
Add-Sprite 'slider_track' @(
    'KKKKKKKKKKKKKKKK'
    'KSSSSSSSSSSSSSSK'
    'KSVVVVVVVVVVVVLK'
    'KSVVVVVVVVVVVVLK'
    'KSVVVVVVVVVVVVLK'
    'KSVVVVVVVVVVVVLK'
    'KLLLLLLLLLLLLLLK'
    'KKKKKKKKKKKKKKKK'
) 2
Add-Sprite 'slider_handle' @(
    '.KKKKK.'
    'KWWWWWK'
    'KWwwwgK'
    'KWwwwgK'
    'KWwwwgK'
    'KWwCwgK'
    'KWwCwgK'
    'KWwCwgK'
    'KWwCwgK'
    'KWwwwgK'
    'KWwwwgK'
    'KWwwwgK'
    'KgggggK'
    '.KKKKK.'
)
Add-Sprite 'slider_handle_highlighted' @(
    '.CCCCC.'
    'CWWWWWC'
    'CWWWWwC'
    'CWWWWwC'
    'CWWWWwC'
    'CWWCWwC'
    'CWWCWwC'
    'CWWCWwC'
    'CWWCWwC'
    'CWWWWwC'
    'CWWWWwC'
    'CWWWWwC'
    'CwwwwwC'
    '.CCCCC.'
)

# ---- on/off switch ----
Add-Sprite 'toggle_off' @(
    '..KKKKKKKKKKKKKKKKKKKK..'
    '.KVVVVVVVVVVVVVVVVVVVVK.'
    'KVWWWWWWWWWWVVVVVVVVVVVK'
    'KVWwwwwwwwwgVVVVVVVVVVVK'
    'KVWwwwwwwwwgVVVVVVVVVVVK'
    'KVWwwwwwwwwgVVVVVggVVVVK'
    'KVWwwwwwwwwgVVVVVggVVVVK'
    'KVWwwwwwwwwgVVVVVVVVVVVK'
    'KVWwwwwwwwwgVVVVVVVVVVVK'
    'KVggggggggggVVVVVVVVVVVK'
    '.KVVVVVVVVVVVVVVVVVVVVK.'
    '..KKKKKKKKKKKKKKKKKKKK..'
)
Add-Sprite 'toggle_off_highlighted' @(
    '..CCCCCCCCCCCCCCCCCCCC..'
    '.CVVVVVVVVVVVVVVVVVVVVC.'
    'CVWWWWWWWWWWVVVVVVVVVVVC'
    'CVWWWWWWWWWwVVVVVVVVVVVC'
    'CVWWWWWWWWWwVVVVVVVVVVVC'
    'CVWWWWWWWWWwVVVVVggVVVVC'
    'CVWWWWWWWWWwVVVVVggVVVVC'
    'CVWWWWWWWWWwVVVVVVVVVVVC'
    'CVWWWWWWWWWwVVVVVVVVVVVC'
    'CVwwwwwwwwwwVVVVVVVVVVVC'
    '.CVVVVVVVVVVVVVVVVVVVVC.'
    '..CCCCCCCCCCCCCCCCCCCC..'
)
Add-Sprite 'toggle_on' @(
    '..KKKKKKKKKKKKKKKKKKKK..'
    '.KccccccccccccccccccccK.'
    'KcccccccccccWWWWWWWWWWcK'
    'KcccccccccccWWwwwwwwwgcK'
    'KccccWccccccWwwwwwwwwgcK'
    'KccccWccccccWwwwwwwwwgcK'
    'KccccWccccccWwwwwwwwwgcK'
    'KccccWccccccWwwwwwwwwgcK'
    'KcccccccccccWwwwwwwwwgcK'
    'KcccccccccccggggggggggcK'
    '.KccccccccccccccccccccK.'
    '..KKKKKKKKKKKKKKKKKKKK..'
)
Add-Sprite 'toggle_on_highlighted' @(
    '..CCCCCCCCCCCCCCCCCCCC..'
    '.CccccccccccccccccccccC.'
    'CcccccccccccWWWWWWWWWWcC'
    'CcccccccccccWWWWWWWWWwcC'
    'CccccWccccccWWWWWWWWWwcC'
    'CccccWccccccWWWWWWWWWwcC'
    'CccccWccccccWWWWWWWWWwcC'
    'CccccWccccccWWWWWWWWWwcC'
    'CcccccccccccWWWWWWWWWwcC'
    'CcccccccccccwwwwwwwwwwcC'
    '.CccccccccccccccccccccC.'
    '..CCCCCCCCCCCCCCCCCCCC..'
)

# ---- arrows for options that cycle through values ----
Add-Sprite 'arrow_left' @(
    '...W'
    '..WW'
    '.WWW'
    'WWWW'
    '.WWW'
    '..WW'
    '...W'
)
Add-Sprite 'arrow_right' @(
    'W...'
    'WW..'
    'WWW.'
    'WWWW'
    'WWW.'
    'WW..'
    'W...'
)

# ---- Temporal's icon: the authors' logo (art\temporal-logo.png, a T in a frame with a play button) at 16x16 ----
# The full-size logo for the mod list and Modrinth comes from the art itself (Make-Logo.ps1).
Add-Sprite 'icon' @(
    '.............M..'
    '..MMMMMMMMMM.MM.'
    '.MMMMMMMMMMM.MMM'
    'MMM..........MM.'
    'MM...........M..'
    'MM..MMMMMMMM....'
    'MM..MMMMMMMM..MM'
    'MM.....MM.....MM'
    'MM.....MM.....MM'
    'MM.....MM.....MM'
    'MM.....MM.....MM'
    'MM.....MM.....MM'
    'MM.....MM.....MM'
    'MMM..........MMM'
    '.MMMMMMMMMMMMMM.'
    '..MMMMMMMMMMMM..'
)

# ---- 6x frame generation: a black hole with its accretion disk seen edge-on, Interstellar-style ----
Add-Sprite 'black_hole' @(
    '.................'
    '.................'
    '.....5444445.....'
    '....443323344....'
    '...43221112234...'
    '...32110001123...'
    '..5421000001245..'
    '..5410000000145..'
    '54332211111223345'
    '.544332222233445.'
    '..5431000001345..'
    '...43110001134...'
    '...54321112345...'
    '....554333455....'
    '......55555......'
    '.................'
    '.................'
)

# ---- the frame around the 6x viewport: warm gold, open in the middle (nine-slice) ----
Add-Sprite 'viewport_frame' @(
    '.33333333333333.'
    '3222222222222223'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '32............23'
    '3222222222222223'
    '.33333333333333.'
) 3

if ($Preview) {
    $scale = 8
    $pad = 12
    $sheetWidth = 0
    $sheetHeight = $pad
    foreach ($s in $sprites) {
        $sheetWidth = [Math]::Max($sheetWidth, $s.Bitmap.Width * $scale + 2 * $pad + 220)
        $sheetHeight += $s.Bitmap.Height * $scale + $pad
    }
    $sheet = New-Object System.Drawing.Bitmap $sheetWidth, $sheetHeight
    $g = [System.Drawing.Graphics]::FromImage($sheet)
    $g.Clear([System.Drawing.Color]::FromArgb(255, 40, 44, 52))
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $font = New-Object System.Drawing.Font 'Consolas', 11
    $y = $pad
    foreach ($s in $sprites) {
        $g.DrawImage($s.Bitmap, 220, $y, $s.Bitmap.Width * $scale, $s.Bitmap.Height * $scale)
        $g.DrawString($s.Name, $font, [System.Drawing.Brushes]::White, 8, $y)
        $y += $s.Bitmap.Height * $scale + $pad
    }
    $g.Dispose()
    $sheet.Save($Preview, [System.Drawing.Imaging.ImageFormat]::Png)
    $sheet.Dispose()
}
foreach ($s in $sprites) { $s.Bitmap.Dispose() }
"wrote $($sprites.Count) sprites to $((Resolve-Path $out).Path)"
