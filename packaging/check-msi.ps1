# Lists what matters in a Requestify MSI: its identity, Requestify's custom actions and where they run.
# Usage: powershell -NoProfile -File packaging\check-msi.ps1 build\installer\msi\Requestify-<version>.msi
param([Parameter(Mandatory = $true)][string]$Msi)

$path = (Resolve-Path $Msi).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$db = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($path, 0))

function Get-Rows([string]$sql, [int]$columns) {
    $view = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @($sql))
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
    while ($record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)) {
        (1..$columns | ForEach-Object { $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, $_) }) -join ' | '
    }
    $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null)
}

'== Property'
Get-Rows "SELECT Property, Value FROM Property WHERE Property='ProductName' OR Property='ProductVersion' OR Property='Manufacturer' OR Property='UpgradeCode' OR Property='ALLUSERS'" 2
'== CustomAction (Requestify)'
Get-Rows "SELECT Action, Type, Source, Target FROM CustomAction WHERE Action='RequestifyUninstallCleanup' OR Action='RequestifyLaunch'" 4
'== InstallExecuteSequence (Requestify, RemoveFiles)'
Get-Rows "SELECT Action, Condition, Sequence FROM InstallExecuteSequence WHERE Action='RequestifyUninstallCleanup' OR Action='RemoveFiles' OR Action='InstallInitialize' OR Action='RemoveExistingProducts' OR Action='InstallFinalize'" 3
'== InstallUISequence (Requestify, ExecuteAction)'
Get-Rows "SELECT Action, Condition, Sequence FROM InstallUISequence WHERE Action='RequestifyLaunch' OR Action='ExecuteAction'" 3
