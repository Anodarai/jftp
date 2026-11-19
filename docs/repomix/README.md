# Repomix JFTP

[Pełny pakiet XML](jftp.xml) zawiera 187 plików. Obejmuje 182 pliki Java, `pom.xml`, `src/main/assembly/binary.xml` i oba launchery z rewizji `a15ac14` oraz nowy [indeks zasobów i uruchamiania](../jftp-runtime-resources.md). Zachowuje pełne implementacje i komentarze. Indeks wskazuje oryginalne zasoby, grafiki i pliki JavaHelp. Kod zależności Maven jest poza zakresem.

Repomix 1.18.1 raportuje **232 466 tokenów** w kodowaniu `o200k_base`. Plik ma 910 959 bajtów. Do wyszukania ścieżek użyj [repomapy](../repo-maps/repo-map.rust.md), a z pakietu otwieraj sekcje potrzebne do danego zadania.

Sprawdzono poprawność XML, listę plików, sumy SHA-256 oryginałów przed i po pakowaniu oraz zgodność każdej sekcji tekstowej ze źródłem po ujednoliceniu końców linii i pominięciu znaków końca linii na granicach sekcji. [Metryki i sumy pakietu](artifacts/verification.json) · [Sumy plików źródłowych](artifacts/source-sha256.full.json)

## Odtworzenie

Uruchom z katalogu głównego repozytorium w PowerShell. [Konfiguracja](artifacts/repomix.config.json) zapisuje zakres, format i kodowanie tokenów.

```powershell
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ('jftp-repomix-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
npx.cmd --yes repomix@1.18.1 . --config docs/repomix/artifacts/repomix.config.json --output (Join-Path $stage 'jftp.xml')
```

Po zmianie źródeł zweryfikuj nowy pakiet i zaktualizuj metryki oraz sumy przed zastąpieniem plików w repozytorium.

## Wersja skompresowana bez komentarzy

[Pakiet skompresowany](jftp.compressed.xml) obejmuje 184 pliki z rewizji `14e62ce`: kod Java, `pom.xml` i deskryptor assembly. Repomix 1.18.1 raportuje **99 239 tokenów** w kodowaniu `o200k_base`, o **57,08% mniej** niż w pełnym pakiecie o tym samym zakresie z tej rewizji. Kompresja strukturalna pomija fragmenty kodu. Na przykład w `JFTPPreferences.getLicenseAgreedForVersion()` znika instrukcja `return "";` z gałęzi sprawdzającej `null`. [Sumy plików źródłowych tej wersji](artifacts/source-sha256.json)

Sprawdzono poprawność XML, listę plików, sumy źródeł i brak komentarzy w spakowanych sekcjach. [Konfiguracja kompresji](artifacts/repomix.compressed.config.json) włącza usuwanie komentarzy i uruchamia [filtr jednego bloku Javadoc](artifacts/jftp-preferences-comment-filter.mjs), który Repomix pominął. [Metryki i sumy wersji skompresowanej](artifacts/verification.compressed.json)

Aby odtworzyć tę wersję, użyj tego samego katalogu tymczasowego co powyżej i uruchom z katalogu głównego repozytorium:

```powershell
npx.cmd --yes repomix@1.18.1 . --config docs/repomix/artifacts/repomix.compressed.config.json --output (Join-Path $stage 'jftp.compressed.xml')
```
