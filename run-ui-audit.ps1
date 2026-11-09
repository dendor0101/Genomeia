$ErrorActionPreference = 'Stop'
$cp = Get-Content lwjgl3/build/ui-audit-classpath.txt -Raw
$profiles = @(
 @(320,568,1,'ru'), @(360,640,1,'ru'), @(412,915,1,'ru'),
 @(640,360,1,'ru'), @(800,600,1,'ru'), @(720,1280,2,'ru'),
 @(360,640,1,'en'), @(360,640,1,'es'), @(360,640,1,'id')
)
foreach ($profile in $profiles) {
 $w,$h,$d,$lang = $profile
 $name = "${w}x${h}-d${d}-${lang}"
 $out = Join-Path (Get-Location) "build/ui-fixed/$name"
 New-Item -ItemType Directory -Force $out | Out-Null
 $rotate = ($w -eq 320)
 Push-Location $out
 try {
 & "$env:JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' "-Daudit.rotate=$rotate" "-Daudit.w=$w" "-Daudit.h=$h" "-Daudit.d=$d" "-Daudit.lang=$lang" "-Daudit.out=$out" -cp $cp io.github.some_example_name.lwjgl3.verification.UiAuditKt *> "$out/run.log"
 } finally { Pop-Location }
 "$name exit=$LASTEXITCODE" | Tee-Object -FilePath build/ui-fixed/matrix.log -Append
}
