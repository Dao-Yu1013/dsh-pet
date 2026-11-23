param(
  [switch]$Fast,          # 快速包：不解密前端、不混淆，仅用于排障对比
  [string]$Version = "2.6.2",
  [int]$VersionCode = 106,
  [string]$OutputPath = "",
  [string]$SdkPath = "",
  [string]$JdkPath = "",
  [string]$KeystorePath = "",
  [string]$KeystorePass = "",
  [string]$KeyAlias = "dshpet",
  [string]$ExpectedCert = ""
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# 工具链：先看参数，再看环境变量，最后试仓库内的 .toolchain
if ([string]::IsNullOrWhiteSpace($SdkPath)) { $SdkPath = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($SdkPath)) { $SdkPath = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($SdkPath)) { $SdkPath = "$root\.toolchain\android-sdk" }
$sdk = $SdkPath
$bt = "$sdk\build-tools\36.0.0"
$jar = "$sdk\platforms\android-36\android.jar"
$ndk = "$sdk\ndk\28.2.13676358"
$r8jar = "$sdk\cmdline-tools\latest\lib\r8.jar"
foreach ($p in @($bt, $jar, $ndk, $r8jar)) {
  if (-not (Test-Path $p)) { throw "Android SDK 不完整，找不到：$p（用 -SdkPath 或 ANDROID_SDK_ROOT 指定 SDK 目录）" }
}
if ([string]::IsNullOrWhiteSpace($JdkPath)) { $JdkPath = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($JdkPath)) { $JdkPath = "$root\.toolchain\jdk-17" }
if (-not (Test-Path "$JdkPath\bin\javac.exe")) { throw "找不到 JDK 17：$JdkPath（用 -JdkPath 或 JAVA_HOME 指定）" }
$env:JAVA_HOME = $JdkPath

$mobileR8jar = "$root\app\libs\r8-8.0.34.jar"
# Live2D Cubism Core 是闭源组件，不随仓库分发。去 Live2D 官网下载 Cubism SDK，
# 把 CubismCore.jar 放到 app\libs\Live2DCubismCore.jar，JNI 库放到 app\src\main\jniLibs\arm64-v8a\。
$coreJar = "$root\app\libs\Live2DCubismCore.jar"
if (-not (Test-Path $coreJar)) { throw "缺少 Live2D Core：$coreJar（见 docs/构建.md）" }
$build = "$root\build"
$outDir = "$build\out"
New-Item -ItemType Directory -Force -Path "$build\gen", "$build\classes", "$build\dex", "$build\jni", "$outDir" | Out-Null

# ============================================================================
# 加固参数：密钥派生必须与 app/src/main/cpp/dshcore.cpp 完全一致，改一处要同时改两处
# ============================================================================
function Get-DshKey {
  $A = [System.Text.Encoding]::ASCII.GetBytes("DshP3t#26")
  $B = [System.Text.Encoding]::ASCII.GetBytes("s3cure!U")
  $C = [System.Text.Encoding]::ASCII.GetBytes("1qaz2wsx")
  $D = [System.Text.Encoding]::ASCII.GetBytes("Zx9!kLmN")
  $key = New-Object byte[] 32
  for ($i = 0; $i -lt 8; $i++) {
    $key[$i]      = (($A[$i] + $B[$i]) -band 0xFF) -bxor $C[$i]
    $key[$i + 8]  = (($B[$i] -bxor $C[$i]) + $D[$i]) -band 0xFF
    $key[$i + 16] = (($C[$i] + $D[$i]) -band 0xFF) -bxor $A[$i]
    $key[$i + 24] = (($D[$i] -bxor $A[$i]) + $B[$i]) -band 0xFF
  }
  return $key
}

function Protect-Bytes([byte[]]$plain, [byte[]]$key) {
  $aes = [System.Security.Cryptography.Aes]::Create()
  $aes.KeySize = 256; $aes.Mode = "CBC"; $aes.Padding = "PKCS7"; $aes.Key = $key
  $aes.GenerateIV()
  $enc = $aes.CreateEncryptor()
  $cipher = $enc.TransformFinalBlock($plain, 0, $plain.Length)
  $out = New-Object byte[] (16 + $cipher.Length)
  [Array]::Copy($aes.IV, 0, $out, 0, 16)
  [Array]::Copy($cipher, 0, $out, 16, $cipher.Length)
  return $out
}

# 定长字段一律按大端写：Android 侧 WebUI.readInt/readShort 按大端解析
function Write-IntBE($w, [int]$v) {
  $w.Write([System.BitConverter]::GetBytes([System.Net.IPAddress]::HostToNetworkOrder($v)))
}

function Write-ShortBE($w, [int]$v) {
  $w.Write([System.BitConverter]::GetBytes([System.Net.IPAddress]::HostToNetworkOrder([int16]$v)))
}

# 前端加密：把需要藏起来的文件打成 sec.bin（webp 动画不动，156MB 没必要加密）
function New-SecBin {
  $items = @("ui/index.html", "ui/app.js", "ui/app.css", "ui/memory-graph.js", "ui/vendor/3d-force-graph.min.js", "ui/vendor/three.min.js", "ui/vendor/oss-unreal-bloom-pass.js", "ui/vendor/oss-starfield-opening.js", "pet-config.json")
  $key = Get-DshKey
  $ms = New-Object System.IO.MemoryStream
  $w = New-Object System.IO.BinaryWriter($ms)
  $w.Write([System.Text.Encoding]::ASCII.GetBytes("DSH1"))
  Write-IntBE $w $items.Count
  foreach ($rel in $items) {
    $path = Join-Path "$root\app\src\main\assets" ($rel -replace '/', '\')
    if (-not (Test-Path $path)) { throw "缺少待加密文件：$rel" }
    $name = [System.Text.Encoding]::UTF8.GetBytes($rel)
    $plain = [System.IO.File]::ReadAllBytes($path)
    $data = Protect-Bytes $plain $key
    # 必须用三参重载，PowerShell 在单参重载下会选错类型导致密文写不进去
    Write-ShortBE $w $name.Length
    $w.Write([byte[]]$name, 0, $name.Length)
    Write-IntBE $w $data.Length
    $w.Write([byte[]]$data, 0, $data.Length)
    Write-Host ("  加密 {0}  ({1:N0} → {2:N0} 字节)" -f $rel, $plain.Length, $data.Length)
  }
  $w.Flush()
  $bytes = $ms.ToArray()
  $w.Dispose(); $ms.Dispose()
  [System.IO.File]::WriteAllBytes("$build\sec.bin", $bytes)
  if ($bytes.Length -lt 100000) { throw "sec.bin 体积异常（$($bytes.Length) 字节），加密步骤没有生效" }
  Write-Host ("sec.bin: {0:N0} 字节" -f $bytes.Length)
}

# 原生库：arm64-v8a + armeabi-v7a
function Build-Jni {
  $clang = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
  $sysroot = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot"
  $abi = @(
    @{ name = "arm64-v8a";   target = "aarch64-linux-android28" },
    @{ name = "armeabi-v7a"; target = "armv7a-linux-androideabi28" }
  )
  foreach ($a in $abi) {
    $dst = "$build\jni\lib\$($a.name)"
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    Write-Host ("== clang++ " + $a.name + " ==")
    # 头文件路径由 clang 按 --target 自动推断，不要手写 -I，否则 armv7 会串到 arm64 的 sysroot
    # -static-libstdc++：把 C++ 运行时静态链进 so。dshcore.cpp 本身只用 cstring/cstdio，
    # 不加这个会有 DT_NEEDED=libc++_shared.so，而包里不带它 → dlopen 失败 → 启动报「核心组件缺失」
    & $clang "--target=$($a.target)" -shared -fPIC -O2 -s -fvisibility=hidden -static-libstdc++ `
      -o "$dst\libdshcore.so" "$root\app\src\main\cpp\dshcore.cpp" 2>&1 |
      Where-Object { $_ -notmatch 'warning:' -and $_ -notmatch '^\s*$' }
    if ($LASTEXITCODE -ne 0) { throw "clang++ 编译失败：$($a.name)" }
    # 兜底自检：so 里不允许残留 libc++_shared.so 依赖
    $soBytes = [System.IO.File]::ReadAllBytes("$dst\libdshcore.so")
    $soText = [System.Text.Encoding]::ASCII.GetString($soBytes)
    if ($soText.Contains("libc++_shared.so")) {
      # 静态链接不可用时，把 NDK 自带的运行库一起打进包，保证 dlopen 能得到它
      $syslib = if ($a.name -eq "arm64-v8a") { "aarch64-linux-android" } else { "arm-linux-androideabi" }
      $libcpp = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\$syslib\libc++_shared.so"
      if (-not (Test-Path $libcpp)) { throw "so 依赖 libc++_shared.so，但 NDK 里也找不到：$libcpp" }
      Copy-Item $libcpp "$dst\libc++_shared.so" -Force
      Write-Host "  静态链接未生效，已一并打包 libc++_shared.so"
    }
    Write-Host ("  " + [math]::Round((Get-Item "$dst\libdshcore.so").Length / 1KB, 1) + " KB")
    # Live2D Core 的 JNI 库只有 arm64-v8a（没有 v7a）：有就带上，没有就让应用自己降级
    $coreSo = "$root\app\src\main\jniLibs\$($a.name)\libLive2DCubismCoreJNI.so"
    if (Test-Path $coreSo) {
      Copy-Item $coreSo "$dst\libLive2DCubismCoreJNI.so" -Force
      Write-Host ("  Live2D Core: " + [math]::Round((Get-Item $coreSo).Length / 1KB, 1) + " KB")
    } else {
      Write-Host "  Live2D Core: 该 ABI 没有 so，运行时降级"
    }
  }
}

# Build an on-device Java -> DEX toolchain. These assets are loaded only while
# compiling a user-requested native plugin; they are not needed for ordinary chat.
function Merge-CompilerResources([string]$dexJar, [string]$sourceJar) {
  $tmp = "$dexJar.merged"
  if (Test-Path $tmp) { Remove-Item $tmp -Force }
  $dexZip = [System.IO.Compression.ZipFile]::OpenRead($dexJar)
  $srcZip = [System.IO.Compression.ZipFile]::OpenRead($sourceJar)
  $outZip = [System.IO.Compression.ZipFile]::Open($tmp, "Create")
  try {
    $present = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $dexZip.Entries) {
      $upper = $entry.FullName.ToUpperInvariant()
      if ($entry.FullName.EndsWith(".class", [System.StringComparison]::OrdinalIgnoreCase) -or
          $upper -eq "META-INF/MANIFEST.MF" -or
          ($upper.StartsWith("META-INF/") -and $upper -match '\.(SF|RSA|DSA|EC)$')) { continue }
      if (-not $present.Add($entry.FullName)) { continue }
      $level = if ($entry.FullName -eq "classes.dex") {
        # Android's DexClassLoader can map a stored DEX directly and older ART
        # releases handle it more reliably than a compressed classes.dex entry.
        [System.IO.Compression.CompressionLevel]::NoCompression
      } else { [System.IO.Compression.CompressionLevel]::Optimal }
      $target = $outZip.CreateEntry($entry.FullName, $level)
      $input = $entry.Open(); $output = $target.Open()
      try { $input.CopyTo($output) } finally { $input.Close(); $output.Close() }
    }
    foreach ($entry in $srcZip.Entries) {
      $upper = $entry.FullName.ToUpperInvariant()
      # These signatures cover the original class-file JAR. They become invalid
      # when D8's classes.dex is inserted, so never ship stale JAR signatures.
      if ($entry.FullName.EndsWith(".class", [System.StringComparison]::OrdinalIgnoreCase) -or
          $upper -eq "META-INF/MANIFEST.MF" -or
          ($upper.StartsWith("META-INF/") -and $upper -match '\.(SF|RSA|DSA|EC)$')) { continue }
      if (-not $present.Add($entry.FullName)) { continue }
      $target = $outZip.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
      $input = $entry.Open(); $output = $target.Open()
      try { $input.CopyTo($output) } finally { $input.Close(); $output.Close() }
    }
  } finally {
    $srcZip.Dispose(); $dexZip.Dispose(); $outZip.Dispose()
  }
  Move-Item -LiteralPath $tmp -Destination $dexJar -Force
}

function Build-PluginCompilerAssets {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $dst = "$build\plugin-compiler"
  if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $dst | Out-Null
  $ecj = "$root\app\libs\ecj-3.37.0.jar"
  if (-not (Test-Path $mobileR8jar)) { throw "缺少 Android 9 兼容的手机端 D8 运行时：$mobileR8jar" }
  $sourceVersionShim = "$root\app\plugin-compiler-stubs\javax\lang\model\SourceVersion.java"
  $shimClasses = "$build\plugin-compiler-shim-classes"
  $shimJar = "$build\plugin-compiler-shim.jar"
  # Do not redistribute the installed Android SDK platform jar. This Apache-2.0
  # API stub is only a compile-time signature library for generated plugins.
  $androidApi = "$root\app\libs\android-4.1.1.4.jar"
  $coreApi = "$sdk\platforms\android-36\core-for-system-modules.jar"
  if (-not (Test-Path $ecj)) { throw "缺少手机内 Java 编译器：$ecj" }
  if (-not (Test-Path $sourceVersionShim)) { throw "缺少 Android ECJ 兼容层：$sourceVersionShim" }
  if (-not (Test-Path $androidApi)) { throw "缺少可随包分发的 Android API 编译桩：$androidApi" }
  if (-not (Test-Path $coreApi)) { throw "缺少 Java 核心类型编译桩：$coreApi" }
  Write-Host "== build on-device ECJ/D8 toolchain =="
  if (Test-Path $shimClasses) { Remove-Item $shimClasses -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $shimClasses | Out-Null
  & "$env:JAVA_HOME\bin\javac.exe" --release 8 -encoding UTF-8 -d $shimClasses $sourceVersionShim
  if ($LASTEXITCODE -ne 0) { throw "ECJ Android 兼容层编译失败" }
  if (Test-Path $shimJar) { Remove-Item $shimJar -Force }
  & "$env:JAVA_HOME\bin\jar.exe" --create --file $shimJar -C $shimClasses .
  if ($LASTEXITCODE -ne 0) { throw "ECJ Android 兼容层打包失败" }
  & "$bt\d8.bat" --lib $jar --min-api 28 --output "$dst\ecj.dex.jar" $ecj $shimJar
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$dst\ecj.dex.jar")) { throw "ECJ 转 DEX 失败" }
  Merge-CompilerResources "$dst\ecj.dex.jar" $ecj
  & "$bt\d8.bat" --lib $jar --min-api 28 --output "$dst\d8.dex.jar" $mobileR8jar
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$dst\d8.dex.jar")) { throw "D8 编译器转 DEX 失败" }
  Merge-CompilerResources "$dst\d8.dex.jar" $mobileR8jar
  Copy-Item "$root\app\libs\R8-8.0.34-NOTICE.txt" "$dst\R8-8.0.34-NOTICE.txt" -Force
  Copy-Item "$root\app\libs\R8-LICENSE-2.0.txt" "$dst\R8-LICENSE-2.0.txt" -Force
  Copy-Item $androidApi "$dst\android.jar" -Force
  Copy-Item $coreApi "$dst\core.jar" -Force
  Copy-Item "$root\app\libs\ANDROID-API-LICENSE-2.0.txt" "$dst\ANDROID-API-LICENSE-2.0.txt" -Force
  $apiNotice = @"
Android API signature stub bundled for the on-device Java-to-DEX plugin compiler.
Artifact: com.google.android:android:4.1.1.4 (Android API 16)
License: Apache License 2.0; see ANDROID-API-LICENSE-2.0.txt
Maven Central: https://central.sonatype.com/artifact/com.google.android/android/4.1.1.4
SHA-256: 84072541CBB711EFF89F7277100FF854929A446DBA7CEB1B195C340E0B4FD3CB
This JAR supplies compile-time API signatures only. Its classes are not packaged
into plugin DEX files and are not loaded as application implementations.
"@
  [System.IO.File]::WriteAllText("$dst\ANDROID-API-NOTICE.txt", $apiNotice, (New-Object System.Text.UTF8Encoding($false)))
  $apiDir = "$dst\api"
  New-Item -ItemType Directory -Force -Path "$apiDir\com\dshpet\android\plugin" | Out-Null
  Copy-Item "$build\classes\com\dshpet\android\plugin\*.class" "$apiDir\com\dshpet\android\plugin\" -Force
  & "$env:JAVA_HOME\bin\jar.exe" --create --file "$dst\plugin-api.jar" -C $apiDir .
  if ($LASTEXITCODE -ne 0) { throw "原生插件 API 打包失败" }
  Remove-Item $apiDir -Recurse -Force
  Write-Host ("  ECJ: {0:N1} MB; D8: {1:N1} MB; Android API signatures: {2:N1} MB" -f `
    ((Get-Item "$dst\ecj.dex.jar").Length / 1MB), ((Get-Item "$dst\d8.dex.jar").Length / 1MB), ((Get-Item "$dst\android.jar").Length / 1MB))
}

Write-Host "== aapt2 compile =="
& "$bt\aapt2.exe" compile --dir "$root\app\src\main\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link =="
# aapt2 的 --version-code 不会覆盖 manifest 里已写死的值，这里按参数生成临时 manifest，
# 保证 -Version / -VersionCode 永远是最终值
$mfSrc = Get-Content "$root\app\src\main\AndroidManifest.xml" -Raw
$mfDst = "$build\AndroidManifest.xml"
$mfSrc = $mfSrc -replace 'android:versionCode="[^"]*"', ('android:versionCode="' + $VersionCode + '"')
$mfSrc = $mfSrc -replace 'android:versionName="[^"]*"', ('android:versionName="' + $Version + '"')
[System.IO.File]::WriteAllText($mfDst, $mfSrc, (New-Object System.Text.UTF8Encoding($false)))
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $jar `
  --manifest $mfDst `
  --java "$build\gen" `
  --min-sdk-version 28 --target-sdk-version 35 `
  --version-code $VersionCode --version-name $Version `
  --auto-add-overlay "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac =="
$files = @(Get-ChildItem "$root\app\src\main\java", "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javacErr = "$build\javac.err"
# PowerShell 会把 javac 的“已过时 API”提示当成错误流；这里临时放松，避免 Stop 误判构建失败
$prevEA = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -nowarn --release 11 -classpath "$jar;$coreJar" -d "$build\classes" @files 2> $javacErr
$javacCode = $LASTEXITCODE
$ErrorActionPreference = $prevEA
if ($javacCode -ne 0) {
  if (Test-Path $javacErr) { Get-Content $javacErr | Select-Object -First 30 }
  throw "javac failed"
}

# 先打成一个 jar：d8 直接收 160 多个 class 路径会超出 Windows 命令行长度上限
$jarInput = "$build\classes.jar"
if (Test-Path $jarInput) { Remove-Item $jarInput -Force }
& "$env:JAVA_HOME\bin\jar.exe" --create --file $jarInput -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

if ($Fast) {
  Write-Host "== d8（快速包，不混淆） =="
  Remove-Item "$build\dex\*" -Force -ErrorAction SilentlyContinue
  & "$bt\d8.bat" --lib $jar --min-api 28 --output "$build\dex" $jarInput $coreJar
  if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
} else {
  Write-Host "== R8（混淆 + 收缩 + 优化） =="
  $r8out = "$build\r8"
  if (Test-Path $r8out) { Remove-Item $r8out -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $r8out | Out-Null
  & "$env:JAVA_HOME\bin\java.exe" -cp $r8jar com.android.tools.r8.R8 `
    "--release" "--lib" $jar "--min-api" 28 `
    "--pg-conf" "$root\app\proguard-rules.pro" `
    "--pg-map-output" "$r8out\mapping.txt" `
    "--output" $r8out `
    $jarInput $coreJar
  if ($LASTEXITCODE -ne 0) { throw "R8 failed" }
  Remove-Item "$build\dex\*" -Force -ErrorAction SilentlyContinue
  Copy-Item "$r8out\classes.dex" "$build\dex\classes.dex" -Force
  # mapping 按版本归档，线上崩溃栈靠它还原
  $mapDir = "$root\build\mapping"
  New-Item -ItemType Directory -Force -Path $mapDir | Out-Null
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  Copy-Item "$r8out\mapping.txt" "$mapDir\mapping-$Version-$stamp.txt" -Force
  Write-Host ("mapping: $mapDir\mapping-$Version-$stamp.txt")
}

Write-Host "== 前端加固 =="
if (-not $Fast) { New-SecBin } else { Write-Host "  跳过（快速包）" }
Build-Jni
Build-PluginCompilerAssets

Write-Host "== package =="
Copy-Item "$build\base.apk" "$build\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\unsigned.apk", "Update")
$oldAssets = @($zip.Entries | Where-Object { $_.FullName.StartsWith("assets/") -or $_.FullName.StartsWith("assets\") })
foreach ($e in $oldAssets) { $e.Delete() }

$assetRoot = (Resolve-Path "$root\app\src\main\assets").Path
# 加固包里排除掉已加密的九个文件；快速包原样放进去
$sealed = @("ui/index.html", "ui/app.js", "ui/app.css", "ui/memory-graph.js", "ui/vendor/3d-force-graph.min.js", "ui/vendor/three.min.js", "ui/vendor/oss-unreal-bloom-pass.js", "ui/vendor/oss-starfield-opening.js", "pet-config.json")
$assetFiles = @(Get-ChildItem $assetRoot -Recurse -File)
foreach ($f in $assetFiles) {
  $rel = $f.FullName.Substring($assetRoot.Length).TrimStart('\', '/').Replace('\', '/')
  if ((-not $Fast) -and ($sealed -contains $rel)) { continue }
  $entry = $zip.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::NoCompression)
  $outStream = $entry.Open()
  $inStream = [System.IO.File]::OpenRead($f.FullName)
  $inStream.CopyTo($outStream)
  $inStream.Close()
  $outStream.Close()
}
if (-not $Fast) {
  $sec = [System.IO.File]::ReadAllBytes("$build\sec.bin")
  $e2 = $zip.CreateEntry("assets/sec.bin", [System.IO.Compression.CompressionLevel]::NoCompression)
  $os2 = $e2.Open(); $os2.Write($sec, 0, $sec.Length); $os2.Close()
}
# 手机内 Java/DEX 编译器：独立放在私有 assets 子目录，运行时首次使用再复制。
foreach ($f in @(Get-ChildItem "$build\plugin-compiler" -File)) {
  $eCompiler = $zip.CreateEntry("assets/plugin-compiler/$($f.Name)", [System.IO.Compression.CompressionLevel]::NoCompression)
  $oc = $eCompiler.Open(); $bc = [System.IO.File]::ReadAllBytes($f.FullName); $oc.Write($bc, 0, $bc.Length); $oc.Close()
}
# 原生库：把 build\jni\lib\<abi>\ 下的所有 .so 都打进去
# （正常只有 libdshcore.so；若静态链接 C++ 运行时失败，这里会带上 libc++_shared.so）
foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
  foreach ($f in @(Get-ChildItem "$build\jni\lib\$abi" -Filter *.so -File)) {
    $e3 = $zip.CreateEntry("lib/$abi/$($f.Name)", [System.IO.Compression.CompressionLevel]::NoCompression)
    $os3 = $e3.Open(); $bytes = [System.IO.File]::ReadAllBytes($f.FullName); $os3.Write($bytes, 0, $bytes.Length); $os3.Close()
    Write-Host "  打包 lib/$abi/$($f.Name)"
  }
}
# classes.dex 也强制 STORED
$entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::NoCompression)
$out = $entry.Open()
$bytes = [System.IO.File]::ReadAllBytes("$build\dex\classes.dex")
$out.Write($bytes, 0, $bytes.Length)
$out.Close()
$zip.Dispose()
Write-Host ("assets 条目: " + $assetFiles.Count + "（加固时排除 " + $sealed.Count + " 个已加密文件）")

# ============================================================================
# 注意：manifest 里 extractNativeLibs 已改为 true，.so 允许 DEFLATE 压缩，
# 由系统在安装时解压到私有 lib 目录，因此这里不需要任何 ZIP 头补丁。
# （之前 2.5.0 装不上，就是 extractNativeLibs=false 却把 .so 压缩了）
# ============================================================================
Write-Host "== zipalign =="
& "$bt\zipalign.exe" -f -p 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = if (-not [string]::IsNullOrWhiteSpace($KeystorePath)) { $KeystorePath } else { $env:DSH_KEYSTORE_PATH }
if ([string]::IsNullOrWhiteSpace($ks)) { $ks = "$root\.signing\release.keystore" }
$pass = if (-not [string]::IsNullOrWhiteSpace($KeystorePass)) { $KeystorePass } else { $env:DSH_KEYSTORE_PASS }
if (-not (Test-Path $ks)) { throw "找不到签名 keystore：$ks（用 -KeystorePath 指定）" }
if ([string]::IsNullOrWhiteSpace($pass)) { throw "没有签名密码（用 -KeystorePass 或环境变量 DSH_KEYSTORE_PASS 提供）" }
$env:DSH_KEYSTORE_PASS = $pass
Write-Host "== apksigner =="
$suffix = if ($Fast) { "-fast" } else { "" }
if ([string]::IsNullOrWhiteSpace($OutputPath)) { $final = "$root\dist\dsh-pet-v$Version$suffix.apk" }
else { $final = [System.IO.Path]::GetFullPath($OutputPath) }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $final) | Out-Null
if (Test-Path $final) { throw "输出文件已存在，为保护旧 APK 不覆盖：$final" }
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias $KeyAlias `
  --ks-pass env:DSH_KEYSTORE_PASS --key-pass env:DSH_KEYSTORE_PASS `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --min-sdk-version 28 --max-sdk-version 9999 `
  --align-file-size `
  --out $final "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
# 注意：签名后不再跑 zipalign！apksigner 内部会处理好对齐，
# 签名后 zipalign 会破坏 v2/v3 签名块的位置关系

& "$bt\apksigner.bat" verify --verbose --print-certs $final | Select-Object -First 10
# 硬性自检：自建签名时必须把证书指纹同步进 dshcore.cpp 的 kCert，否则装上去会被 Secure 拦成“签名校验不通过”
if (-not [string]::IsNullOrWhiteSpace($ExpectedCert)) {
  $certOut = (& "$bt\apksigner.bat" verify --print-certs $final 2>&1 | Out-String)
  if ($certOut -notmatch [regex]::Escape($ExpectedCert)) {
    throw "签名自检失败：APK 证书与 dshcore.cpp 的 kCert 不一致，安装后会报『签名校验不通过』"
  }
} else {
  Write-Host "未提供 -ExpectedCert，跳过证书指纹自检（自建签名请同步修改 dshcore.cpp 的 kCert）"
}
Write-Host ("APK: " + $final + "  " + [math]::Round((Get-Item $final).Length / 1MB, 1) + " MB  " + $(if ($Fast) { "[快速包·未加固]" } else { "[加固包]" }))
