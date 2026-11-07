# Resizes Temporal's logo art (art\, drawn by the authors) into the mod list's logo and the Modrinth project icon.
# To change the logo, replace the art and run this script again. The 16x16 in-game icon is drawn by hand in Make-GuiArt.ps1.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Join-Path $PSScriptRoot '..'

function Resize-Logo([string]$from, [string]$to, [int]$size) {
    $source = [System.Drawing.Image]::FromFile((Resolve-Path (Join-Path $root $from)).Path)
    try {
        $logo = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [System.Drawing.Graphics]::FromImage($logo)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        # Without this the outermost pixels are blended with nothing and come out half transparent.
        $attributes = New-Object System.Drawing.Imaging.ImageAttributes
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.DrawImage($source, (New-Object System.Drawing.Rectangle 0, 0, $size, $size), 0, 0, $source.Width, $source.Height,
                [System.Drawing.GraphicsUnit]::Pixel, $attributes)
        $graphics.Dispose()
        $logo.Save((Join-Path $root $to), [System.Drawing.Imaging.ImageFormat]::Png)
        $logo.Dispose()
    } finally {
        $source.Dispose()
    }
    "wrote $to ($size x $size)"
}

# The mod list draws the logo on its own dark panel, so it gets the transparent art (drawn smoothly: logoBlur=true).
Resize-Logo 'art\temporal-logo.png' 'src\main\resources\temporal_logo.png' 256
# Modrinth also shows icons on white, where the mint alone almost disappears.
Resize-Logo 'art\temporal-logo-black.png' 'modrinth-icon.png' 512
