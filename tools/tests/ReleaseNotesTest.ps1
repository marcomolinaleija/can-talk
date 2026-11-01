#Requires -Version 7
# Pure formatting regressions. Never execute the publisher or access GitHub here.
$ErrorActionPreference = 'Stop'
$publisher = Join-Path $PSScriptRoot '../../publicar-release.ps1'
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $publisher, [ref]$null, [ref]$errors)
if ($errors) { throw 'Publisher syntax is invalid.' }
$formatter = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Get-LegacyReleaseBody'
}, $false)
if (-not $formatter) { throw 'Legacy formatter not found.' }
. ([scriptblock]::Create($formatter.Extent.Text))

$cases = @(
    @{ Notes = "CAN Talk 1.0.4`n`n- Busca emojis en braille.`n- Se corrige un cierre.";
       Expected = @('Busca emojis en braille.', 'Se corrige un cierre.') },
    @{ Notes = "CAN Talk 1.0.4`r`n`r`n- Mejora del ecualizador de Samsung.`r`n- Se conservan los gestos.";
       Expected = @('Mejora del ecualizador de Samsung.', 'Se conservan los gestos.') },
    @{ Notes = "CAN Talk 1.0.4`n`nTexto sin viñetas.";
       Expected = @('CAN Talk 1.0.4') },
    @{ Notes = "CAN Talk 1.0.4`n`n- ¡Primera palabra intacta!`n`nMás información.";
       Expected = @('¡Primera palabra intacta!') }
)
foreach ($case in $cases) {
    $body = Get-LegacyReleaseBody $case.Notes 340 ('a' * 40)
    $lines = @($body -split '\r?\n')
    if ($lines[0] -ne 'build: 340') { throw 'Legacy build header differs.' }
    # Emulate 1.0.2: take only hash-prefixed bullet lines and remove the hash.
    $visible = @($lines | Where-Object { $_.StartsWith('- ') } | ForEach-Object {
        if ($_ -notmatch '^- aaaaaaa (.+)$') { throw 'Invalid legacy commit line.' }
        $Matches[1]
    })
    if (($visible -join "`n") -cne ($case.Expected -join "`n")) {
        throw 'Older clients would receive different or duplicated editorial text.'
    }
    if (-not $body.Contains(($case.Notes -replace '(?m)^- ', '• '))) {
        throw 'Complete editorial notes were lost.'
    }
}
Write-Output "Release note formatting: $($cases.Count) cases passed. No publication performed."
