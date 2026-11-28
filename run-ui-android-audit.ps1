$ErrorActionPreference = 'Stop'
$cp = Get-Content lwjgl3/build/ui-audit-classpath.txt -Raw
$profiles = @(
 @(360,640,1,'ru','false'), @(720,1280,2,'ru','false'), @(1080,1920,3,'ru','false'),
 @(1280,720,2,'ru','false'), @(720,1280,2,'es','false'), @(720,1280,2,'id','false'), @(720,1280,2,'en','false'),
 @(720,1280,2,'ru','true'), @(1280,720,2,'ru','true'), @(360,640,1,'ru','true')
)
foreach ($profile in $profiles) {
 $w,$h,$d,$lang,$details = $profile
 $name = "android-${w}x${h}-d${d}-${lang}"
 if($details -eq 'true') { $name += '-details' }
 $out = Join-Path (Get-Location) "build/ui-fixed/$name"
 New-Item -ItemType Directory -Force $out | Out-Null
 $rotate = ($details -eq 'false' -and $lang -eq 'ru' -and $w -eq 720)
 Push-Location $out
 try {
 & "$env:JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' "-Daudit.rotate=$rotate" '-Daudit.android=true' "-Daudit.details=$details" "-Daudit.w=$w" "-Daudit.h=$h" "-Daudit.d=$d" "-Daudit.lang=$lang" "-Daudit.out=$out" -cp $cp io.github.some_example_name.lwjgl3.verification.UiAuditKt *> "$out/run.log"
 } finally { Pop-Location }
 "$name exit=$LASTEXITCODE" | Tee-Object -FilePath build/ui-fixed/android-matrix.log -Append
}
