# Repomix JFTP

[Pakiet XML](jftp.xml) zawiera 182 pliki z `src/main/java`, `pom.xml` i `src/main/assembly/binary.xml` z rewizji `14e62ceba4e371c2a0b955604b10f065f46f4f7d`. Zachowuje pełne implementacje i komentarze. Zasoby aplikacji, JavaHelp i kod zależności Maven są poza zakresem.

Repomix 1.18.1 raportuje **231 240 tokenów** w kodowaniu `o200k_base`. Plik ma 907 017 bajtów. Do wyszukania ścieżek użyj [repomapy](../repo-maps/repo-map.rust.md), a z pakietu otwieraj sekcje potrzebne do danego zadania.

Sprawdzono poprawność XML, listę plików, sumy SHA-256 oryginałów przed i po pakowaniu oraz zgodność każdej sekcji tekstowej ze źródłem po ujednoliceniu końców linii i pominięciu znaków końca linii na granicach sekcji. [Metryki i sumy pakietu](artifacts/verification.json) · [Sumy plików źródłowych](artifacts/source-sha256.json)

## Odtworzenie

Uruchom z katalogu głównego repozytorium w PowerShell. [Konfiguracja](artifacts/repomix.config.json) zapisuje zakres, format i kodowanie tokenów.

```powershell
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ('jftp-repomix-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
npx.cmd --yes repomix@1.18.1 . --config docs/repomix/artifacts/repomix.config.json --output (Join-Path $stage 'jftp.xml')
```

Po zmianie źródeł zweryfikuj nowy pakiet i zaktualizuj metryki oraz sumy przed zastąpieniem plików w repozytorium.

## Wersja skompresowana bez komentarzy

[Pakiet skompresowany](jftp.compressed.xml) obejmuje te same 184 pliki. Repomix 1.18.1 raportuje **99 239 tokenów** w kodowaniu `o200k_base`, o **57,08% mniej** niż w pełnym pakiecie. Kompresja strukturalna pomija fragmenty kodu. Na przykład w `JFTPPreferences.getLicenseAgreedForVersion()` znika instrukcja `return "";` z gałęzi sprawdzającej `null`.

Sprawdzono poprawność XML, listę plików, sumy źródeł i brak komentarzy w spakowanych sekcjach. [Konfiguracja kompresji](artifacts/repomix.compressed.config.json) włącza usuwanie komentarzy i uruchamia [filtr jednego bloku Javadoc](artifacts/jftp-preferences-comment-filter.mjs), który Repomix pominął. [Metryki i sumy wersji skompresowanej](artifacts/verification.compressed.json)

Aby odtworzyć tę wersję, użyj tego samego katalogu tymczasowego co powyżej i uruchom z katalogu głównego repozytorium:

```powershell
npx.cmd --yes repomix@1.18.1 . --config docs/repomix/artifacts/repomix.compressed.config.json --output (Join-Path $stage 'jftp.compressed.xml')
```
