# R8 の設定。Room / WorkManager / Media3 / OkHttp はライブラリ側の規則で足りる。

# クラッシュ時のスタックトレースを読めるように行番号を残す(クラス名は難読化しない)
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# OkHttp が任意で参照するクラス(存在しなくてよい)
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
