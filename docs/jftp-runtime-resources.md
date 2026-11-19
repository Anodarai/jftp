# Zasoby i uruchamianie JFTP

Inwentarz plików aplikacji według `git ls-files` dla rewizji `a15ac14`. Maven kopiuje pięć katalogów zasobów do JAR-a. Ścieżki w tabeli pokazują także przykładowe pliki potrzebne do prześledzenia startu i działania interfejsu. [Konfiguracja Maven](../pom.xml#L84)

| Katalog | Pliki | Rola i przykład |
| --- | ---: | --- |
| `src/main/resources` | 57 | Bazowe pakiety tekstów interfejsu, m.in. [JFTP.properties](../src/main/resources/com/myjavaworld/jftp/JFTP.properties). |
| `src/main/resources_de` | 57 | Pakiety niemieckie, m.in. [JFTP_de.properties](../src/main/resources_de/com/myjavaworld/jftp/JFTP_de.properties). |
| `src/main/resources_zh_TW` | 58 | Pakiety chińskie tradycyjne, m.in. [JFTP_zh_TW.properties](../src/main/resources_zh_TW/com/myjavaworld/jftp/JFTP_zh_TW.properties). |
| `src/main/images` | 50 | Ikony okna i kontrolek, m.in. [jftp16.gif](../src/main/images/com/myjavaworld/jftp/jftp16.gif) i [connect16.gif](../src/main/images/com/myjavaworld/jftp/connect16.gif). |
| `src/main/help` | 155 | Deskryptory JavaHelp, strony pomocy i ich zasoby. [helpSet.xml](../src/main/help/helpset/helpSet.xml) wskazuje [map.xml](../src/main/help/helpset/map.xml), która wiąże identyfikatory z dokumentami, np. [stroną startową](../src/main/help/helpset/content/index.html). |
| `src/main/assembly` | 1 | [Deskryptor assembly](../src/main/assembly/binary.xml) definiuje układ dystrybucji. |
| `src/main/scripts` | 2 | Launchery [Windows](../src/main/scripts/jftp.bat) i [Unix](../src/main/scripts/jftp.sh). Assembly poddaje je filtrowaniu. |

## Powiązania w kodzie

- `JFTPApplication` tworzy główne okno. Konstruktor `JFTP` pobiera pakiet `com.myjavaworld.jftp.JFTP` i ikonę `jftp16.gif`, a `newSession()` pobiera tekst `text.notConnected`. [Start](../src/main/java/com/myjavaworld/jftp/JFTPApplication.java#L55) · [Okno i pierwsza sesja](../src/main/java/com/myjavaworld/jftp/JFTP.java#L119)
- `JFTP.prepareMenuBar()` tworzy `HelpMenu`, którego konstruktor przygotowuje pozycje menu. Pierwsza z nich wywołuje `JFTPHelp2.getInstance()`. Inicjalizacja singletonu ładuje deskryptor `helpset/helpSet.xml` z classpath i tworzy `HelpBroker` już podczas budowy głównego okna. [Menu główne](../src/main/java/com/myjavaworld/jftp/JFTP.java#L458) · [Menu pomocy](../src/main/java/com/myjavaworld/jftp/HelpMenu.java#L53) · [Inicjalizacja JavaHelp](../src/main/java/com/myjavaworld/jftp/JFTPHelp2.java#L40)
- Maven wpisuje `com.myjavaworld.jftp.JFTPApplication` jako klasę startową JAR-a i używa prefiksu `lib/` dla zależności w manifeście. Assembly kieruje JAR-y projektu do katalogu głównego dystrybucji, a zależności do `lib/`. Oba launchery wskazują na JAR aplikacji w `lib/`. Zgodność tych ścieżek wymaga sprawdzenia na gotowej paczce. [Manifest](../pom.xml#L117) · [Układ dystrybucji](../src/main/assembly/binary.xml#L12)
- POM deklaruje `ftpapi:3.0.0` i `javahelp:2.0.05`. `FTPSession` tworzy klienta FTP i parser list katalogów przez refleksję na podstawie nazw klas. Źródła zewnętrznych implementacji są poza repozytorium JFTP. [Zależności](../pom.xml#L71) · [Ładowanie klienta i parsera](../src/main/java/com/myjavaworld/jftp/FTPSession.java#L624)

Do wyszukania deklaracji w Javie służy [RepoMap](repo-maps/repo-map.rust.md). [Pełny Repomix](repomix/jftp.xml) zawiera kod Java, POM, deskryptor assembly, launchery i ten indeks. Oryginały tekstów interfejsu, ikon i pomocy są dostępne pod ścieżkami z tabeli.
