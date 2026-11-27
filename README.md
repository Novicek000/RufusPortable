# RufusPortable na Androida

Polskojęzyczna aplikacja do przygotowywania pendrive’ów przez USB OTG, dla Androida 8.0 i nowszego. Działa bez roota. Potrzebne są USB Host oraz nośnik USB Mass Storage w trybie Bulk-Only Transport z SCSI (LUN 0).

Ta gałąź zawiera rozwijany natywny port. Nowa ścieżka instalatora Windows nie została jeszcze opublikowana jako stabilne wydanie. Aktualny release 1.1.0 pochodzi z wcześniejszej implementacji. [Stan prac i pozostałe funkcje](PORT_STATUS.md).

## Instalator Windows 11

1. Wybierz rozpakowany plik ISO w systemowym selektorze. Nazwa wybranego pliku pozostaje widoczna również po błędzie. Podczas wyboru aplikacja odczytuje metadane i nagłówki obrazu. Korzysta z pliku bezpośrednio przez Android Storage Access Framework. Plik źródłowy musi znajdować się poza docelowym pendrivem.
2. Podłącz pendrive, wybierz urządzenie i zezwól na dostęp. W trybie plikowym aplikacja odczytuje drzewo ISO 9660/Joliet lub UDF, tworzy NTFS i kopiuje pliki instalatora. W „Opcjach zapisu” można wybrać GPT lub MBR, etykietę NTFS, rozmiar klastra oraz szybkie lub pełne formatowanie. Nośnik musi pomieścić pliki i struktury systemu plików. Pliki WIM większe niż 4 GiB są obsługiwane przez 64-bitowy odczyt fragmentów UDF i NTFS.
3. Sprawdź nazwę, identyfikator i pojemność celu. **Potwierdzenie zapisu powoduje utratę danych na wybranym urządzeniu.** Pozostaw aplikację otwartą. Przy włączonej weryfikacji aplikacja zamyka wolumin, ponownie otwiera zapisane pliki i porównuje ich SHA-256 z sumami obliczonymi podczas kopiowania.
4. Uruchom komputer z USB. Aplikacja wykorzystuje oryginalny obraz UEFI:NTFS i kod rozruchowy NTFS z Rufusa. Zgodność rozruchu zależy od obrazu i firmware komputera. [Dokumentacja UEFI:NTFS](https://github.com/pbatard/uefi-ntfs).

Podczas zapisu aplikacja kopiuje pliki bezpośrednio z wybranego obrazu porcjami po 128 KiB. Tryb plikowy wymaga źródła udostępniającego odczyt pozycyjny, co zapewniają lokalne dokumenty. Dostawca dokumentów Androida decyduje o własnym buforowaniu (dotyczy to m.in. chmur). Gdy dostawca nie zna rozmiaru, aplikacja liczy bajty przed wyświetleniem potwierdzenia zapisu.

## Tryby i opcje

| Tryb | Funkcje |
| --- | --- |
| Instalator Windows ISO | GPT lub MBR, NTFS z etykietą i wyborem klastra, UEFI:NTFS i kod rozruchowy z Rufusa, wypakowanie plików |
| DD | Zapis IMG, RAW, BIN, DD i hybrydowych ISO. Dla hybrydowego ISO metodę można zmienić w opcjach |
| FreeDOS 1.4 | Rozruch BIOS, MBR, FAT32, FreeCOM i jądro FreeDOS z zasobów Rufusa |
| Pusty FAT32 | Szybkie formatowanie MBR/FAT32 z własną etykietą |
| Kontrola | SHA-256 źródła, opcjonalna weryfikacja zapisu, opcjonalny pełny test bloków (jedno zerowanie i sprawdzenie odczytu), anulowanie operacji |
| Aktualizacje | Automatyczne i ręczne sprawdzanie stabilnych wydań z tego repozytorium |

Po przerwaniu zapisu ponów operację. Test bloków zajmuje czas proporcjonalny do całej pojemności nośnika. Tryby ISO, FAT32 i FreeDOS wymagają sektorów logicznych 512 B. Tryb DD obsługuje sektory od 512 B do 16 KiB i adresowanie SCSI READ/WRITE(10).

Windows To Go, konfiguracja odpowiedzi instalatora Windows, wypakowywanie instalacyjnych ISO Linux i trwałe partycje Live Linux oczekują na implementację. Bieżąca gałąź wymaga dalszej weryfikacji przed stabilnym wydaniem.

## Budowanie

Wymagane są JDK 17, Android SDK Platform 36, Build Tools 35.0.0 oraz Android NDK r27c (27.2.12479018). Część natywna jest budowana na hoście Linux x86_64. Wskaż SDK przez ANDROID_HOME lub local.properties, a NDK przez ANDROID_NDK_HOME. Narzędzia hosta obejmują autoconf, automake, libtool, m4, make, patch oraz makra pkg-config i libgcrypt (pakiety Debian autoconf automake libtool autotools-dev patch pkg-config libgcrypt20-dev).

Przed kompilacją wykonaj git submodule update --init. Gradle buduje biblioteki NTFS/WIM i JNI dla czterech ABI, a następnie dołącza je do APK. [Stan integracji natywnego portu](PORT_STATUS.md) opisuje funkcje oczekujące na implementację.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
```

APK powstaje w app/build/outputs/apk/release/. Do dystrybucji użyj apksigner. Aktualizacja wymaga tego samego identyfikatora aplikacji, certyfikatu i rosnącego versionCode.

## Pochodzenie

Kod aplikacji udostępniono na [GPL w wersji 3 lub późniejszej](LICENSE). Niezależny projekt wykorzystuje zasoby [Rufusa](https://github.com/pbatard/rufus/tree/8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c), w tym tabelę MBR (© 2020 Pete Batard, © 2006 Free Software Foundation, Inc.), kod rozruchowy FAT32/FreeDOS i MBR Syslinux oraz KERNEL.SYS i COMMAND.COM z FreeDOS. Pochodzenie składników FreeDOS opisuje [plik projektu Rufus](app/src/main/assets/freedos/readme.txt).

Obraz UEFI:NTFS oraz kod NTFS i Windows MBR w app/src/main/assets/rufus pochodzą bezpośrednio ze źródeł Rufusa. [Pochodzenie obrazu UEFI:NTFS](app/src/main/assets/rufus/readme.txt). Biblioteka NTFS i formatter pochodzą z przypiętego [podmodułu pbatard/ntfs-3g](third_party/ntfs-3g). Tabela Unicode up-case zachowana w assets/filesystems/upcase.bin pochodzi z woluminu utworzonego przez mkexfatfs 1.3.0.

Biblioteka WIM pochodzi z [podmodułu ebiggers/wimlib](third_party/wimlib), przypiętego do `4a34203cde54c317f0d277a4c64e19916547bf10` (wersja dołączona do referencyjnego Rufusa). Zasady licencjonowania opisuje [COPYING wimlib](third_party/wimlib/COPYING). Widok informacji o obrazach Windows korzysta z systemowego `StorageManager.openProxyFileDescriptor` i odczytuje ekstenty `install.wim`/`install.esd` bez zapisywania ich kopii w pamięci telefonu. Nie jest to jeszcze implementacja Windows To Go.

Test uszkodzonych bloków korzysta z zestawów wzorców i identyfikatorów bloków z [badblocks.c Rufusa](https://github.com/pbatard/rufus/blob/8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c/src/badblocks.c) i stałych rufus.h. Autorzy źródła: Remy Card, Theodore Ts'o, David Beattie i Pete Batard; źródło wywodzi się z programów fsck/mkfs Linusa Torvaldsa.

Generator plików odpowiedzi Windows pochodzi z funkcji CreateUnattendXml w `third_party/rufus/src/wue.c` (© 2022–2026 Pete Batard, GPL-3.0+). Wrappery setup w zasobach są oryginalnymi plikami Rufusa; ich opis i sumy SHA-256 znajdują się w [setup-readme.txt](app/src/main/assets/rufus/setup-readme.txt). Pełne źródło referencyjne jest przypięte w [third_party/rufus](third_party/rufus).
