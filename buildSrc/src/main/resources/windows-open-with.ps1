param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$Extensions
)

$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $MsiPath).Path, 1)

function Invoke-RegistrySql([string]$Sql, [object[]]$Values) {
    $view = $database.OpenView($Sql)
    try {
        $record = $installer.CreateRecord($Values.Count)
        for ($index = 0; $index -lt $Values.Count; $index++) {
            $property = if ($Values[$index] -is [int]) { 'IntegerData' } else { 'StringData' }
            $record.GetType().InvokeMember($property, 'SetProperty', $null, $record,
                @(($index + 1), $Values[$index])) | Out-Null
        }
        $view.Execute($record)
    } finally {
        $view.Close()
    }
}

$view = $database.OpenView('SELECT * FROM Registry')
$view.Execute()
$rows = @()
while ($record = $view.Fetch()) {
    $rows += [pscustomobject]@{
        Id = $record.StringData(1)
        Root = $record.IntegerData(2)
        Key = $record.StringData(3)
        Name = $record.StringData(4)
        Value = $record.StringData(5)
        Component = $record.StringData(6)
    }
}
$view.Close()

foreach ($extension in $Extensions.Split(',')) {
    $key = '.' + $extension
    $candidates = @($rows | Where-Object {
        ($_.Key -eq $key -and $_.Name -eq '' -and $_.Value -like 'progid*') -or
        ($_.Key -eq "$key\OpenWithProgids" -and $_.Name -like 'progid*')
    })
    if ($candidates.Count -ne 1) {
        throw "Expected one jpackage association for $key; found $($candidates.Count)."
    }
    $association = $candidates[0]
    $progId = if ($association.Key -eq $key) { $association.Value } else { $association.Name }

    # Keep the existing component/key-path ownership so MSI repair and uninstall manage this value.
    Invoke-RegistrySql 'UPDATE Registry SET `Key` = ?, `Name` = ?, `Value` = ? WHERE `Registry` = ?' @(
        "$key\OpenWithProgids", $progId, '', $association.Id
    )
    $additionalValues = @(
        @("podaura_open_model_$extension", "$progId\shell\open", 'MultiSelectModel', 'Player'),
        @("podaura_open_name_$extension", "$progId\Application", 'ApplicationName', 'PodAura')
    )
    foreach ($value in $additionalValues) {
        if ($rows.Id -contains $value[0]) {
            Invoke-RegistrySql 'UPDATE Registry SET `Value` = ? WHERE `Registry` = ?' @($value[3], $value[0])
        } else {
            Invoke-RegistrySql 'INSERT INTO Registry (`Registry`, `Root`, `Key`, `Name`, `Value`, `Component_`) VALUES (?, ?, ?, ?, ?, ?)' @(
                $value[0], $association.Root, $value[1], $value[2], $value[3], $association.Component
            )
        }
    }
}
$database.Commit()
Write-Output 'Configured MSI Open With candidates and multi-file activation.'
