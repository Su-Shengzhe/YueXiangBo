#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$id=[Guid]::NewGuid().ToString('N')
$relative=".runtime/third-provider-$id"
$lab=New-Item -ItemType Directory -Path (Join-Path $root $relative)
$ref="third-test-$id.properties"
$credential=Join-Path "$root/deploy/secrets" $ref
$oldOptions=$env:JAVA_TOOL_OPTIONS
$restarted=$false
Push-Location $root
try{
 $env:THIRD_PROVIDER_PASS=[Guid]::NewGuid().ToString('N')
 $env:THIRD_PROVIDER_PFX=Join-Path $lab.FullName 'server.p12'
 $env:THIRD_PROVIDER_REF=$ref
 $keytool=Join-Path $env:JAVA_HOME 'bin/keytool.exe'
 & $keytool -genkeypair -alias localstub -keyalg RSA -keysize 2048 -dname CN=127.0.0.1 -ext SAN=IP:127.0.0.1 -validity 1 -storetype PKCS12 -keystore $env:THIRD_PROVIDER_PFX -storepass:env THIRD_PROVIDER_PASS
 if($LASTEXITCODE){throw 'Test certificate generation failed'}
 & $keytool -exportcert -alias localstub -keystore $env:THIRD_PROVIDER_PFX -storepass:env THIRD_PROVIDER_PASS -file "$lab/server.cer"
 if($LASTEXITCODE){throw 'Test certificate export failed'}
 Copy-Item -LiteralPath "$env:JAVA_HOME/lib/security/cacerts" -Destination "$lab/trust"
 & $keytool -importcert -noprompt -alias local-third -file "$lab/server.cer" -keystore "$lab/trust" -storepass changeit
 if($LASTEXITCODE){throw 'Test trust store creation failed'}
 Set-Content -LiteralPath $credential -Value 'api-key=local-third-test' -Encoding ascii
 $env:JAVA_TOOL_OPTIONS="$oldOptions -Djavax.net.ssl.trustStore=$relative/trust".Trim()
 $restarted=$true
 & "$PSScriptRoot/Stop-Business.ps1"
 & "$PSScriptRoot/Start-Business.ps1"
 & "$PSScriptRoot/Start-BusinessFrontend.ps1"
 $env:REUSE_BUSINESS_FRONTEND='true'
 & "$PSScriptRoot/Test-BusinessBrowser.ps1" -TestFilter 'third compatible supplier through the gateway page'
}finally{
 $env:JAVA_TOOL_OPTIONS=$oldOptions
 try{
  if($restarted){& "$PSScriptRoot/Stop-Business.ps1"; & "$PSScriptRoot/Start-Business.ps1"; & "$PSScriptRoot/Start-BusinessFrontend.ps1"}
 }finally{
  foreach($path in @($credential,"$lab/server.p12","$lab/server.cer","$lab/trust")){
   if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}
  }
  Remove-Item Env:THIRD_PROVIDER_PASS,Env:THIRD_PROVIDER_REF,Env:THIRD_PROVIDER_PFX -ErrorAction SilentlyContinue
  Pop-Location
 }
}
