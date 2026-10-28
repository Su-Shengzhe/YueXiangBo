# Resolve project-relative files without generating keys or resetting a clock.
function Resolve-BusinessPaths {
    param([hashtable]$Config,[string]$Root,[switch]$RepairMovedPaths)
    $result=$Config.Clone()
    $defaults=@{JWT_PRIVATE_FILE='.runtime/business/secrets/private.pem';JWT_PUBLIC_FILE='.runtime/business/secrets/public.pem';BUSINESS_CLOCK_FILE='.runtime/business/clock.txt'}
    foreach($key in $defaults.Keys){
        $value=$Config[$key]
        if(!$value){throw "Missing configuration: $key"}
        $path=if([IO.Path]::IsPathRooted($value)){$value}else{Join-Path $Root $value}
        if(!(Test-Path -LiteralPath $path -PathType Leaf)){
            $candidate=Join-Path $Root $defaults[$key]
            if($RepairMovedPaths -and $value.Replace('\','/').EndsWith('/'+$defaults[$key]) -and (Test-Path -LiteralPath $candidate -PathType Leaf)){$path=$candidate}
            else {throw "Missing $key file. Restore the original file; for a moved default project path run Initialize-Business.ps1 -RepairMovedPaths. No key or clock was regenerated."}
        }
        $result[$key]=[IO.Path]::GetFullPath($path).Replace('\','/')
    }
    return $result
}
