Get-ChildItem -Path "src" -Filter "*.java" -Recurse | ForEach-Object {
    $lines = Get-Content $_.FullName
    $changed = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $newLine = $lines[$i] -replace '(^\s*for\s*\([^:]*\w)\s+:', '$1:'
        if ($newLine -ne $lines[$i]) {
            Write-Host "$($_.FullName):$($i + 1)"
            Write-Host "  - $($lines[$i].TrimStart())"
            Write-Host "  + $($newLine.TrimStart())"
            $lines[$i] = $newLine
            $changed = $true
        }
    }
    if ($changed) {
        Set-Content -Path $_.FullName -Value $lines
    }
}
Write-Host "Done."
