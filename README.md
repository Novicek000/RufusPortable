# RufusPortable na Androida

Polskojęzyczna aplikacja do przygotowywania pendrive’ów przez USB OTG, dla Androida 8.0 i nowszego. Działa bez roota. Potrzebne są USB Host oraz nośnik USB Mass Storage w trybie Bulk-Only Transport z SCSI (LUN 0).

[Pobierz podpisany APK z najnowszego wydania](https://github.com/Novicek000/RufusPortable/releases/latest)

## Instalator Windows 11

1. Wybierz rozpakowany plik ISO w systemowym selektorze. Nazwa wybranego pliku pozostaje widoczna również po błędzie. Podczas wyboru aplikacja odczytuje metadane i nagłówki obrazu. Korzysta z pliku bezpośrednio przez Android Storage Access Framework. Plik źródłowy musi znajdować się poza docelowym pendrivem.
2. Podłącz pendrive, wybierz urządzenie i zezwól na dostęp. Dla zwykłego Windows ISO aplikacja stosuje Ventoy. W „Opcjach zapisu” można wybrać GPT lub MBR, etykietę i klaster exFAT. Nośnik musi pomieścić cały plik oraz struktury partycji i rozruchu. Pliki ISO większe niż 4 GiB są obsługiwane.
3. Sprawdź nazwę, identyfikator i pojemność celu. **Potwierdzenie zapisu powoduje utratę danych na wybranym urządzeniu.** Pozostaw aplikację otwartą. Przy włączonej weryfikacji aplikacja odczytuje zapisane zakresy i porównuje ich SHA-256.
4. Uruchom komputer z USB, wybierz ISO w menu Ventoy i uruchom instalator. Zgodność zależy od obrazu i firmware komputera. Przy Secure Boot obowiązują [zasady zaufania opisane przez Ventoy](https://www.ventoy.net/en/doc_secure.html).

Podczas zapisu aplikacja przesyła ISO na exFAT porcjami po 128 KiB. Dostawca dokumentów Androida decyduje o własnym buforowaniu (dotyczy to m.in. chmur). Gdy dostawca nie zna rozmiaru, aplikacja liczy bajty przed wyświetleniem potwierdzenia zapisu.

## Tryby i opcje

| Tryb | Funkcje |
| --- | --- |
| Windows/Linux ISO przez Ventoy 1.1.18 | GPT lub MBR, partycja danych exFAT z etykietą i klastrem 32, 128 lub 256 KiB, partycja rozruchowa VTOYEFI |
| DD | Zapis IMG, RAW, BIN, DD i hybrydowych ISO. Dla hybrydowego ISO metodę można zmienić w opcjach |
| FreeDOS 1.4 | Rozruch BIOS, MBR, FAT32, FreeCOM i jądro FreeDOS z zasobów Rufusa |
| Pusty FAT32 | Szybkie formatowanie MBR/FAT32 z własną etykietą |
| Kontrola | SHA-256 źródła, opcjonalna weryfikacja zapisu, opcjonalny pełny test bloków (jedno zerowanie i sprawdzenie odczytu), anulowanie operacji |
| Aktualizacje | Automatyczne i ręczne sprawdzanie stabilnych wydań z tego repozytorium |

Po przerwaniu zapisu ponów operację. Test bloków zajmuje czas proporcjonalny do całej pojemności nośnika. Tryby ISO, FAT32 i FreeDOS wymagają sektorów logicznych 512 B. Tryb DD obsługuje sektory od 512 B do 16 KiB i adresowanie SCSI READ/WRITE(10).

Obsługa Windows ISO opiera się na uruchamianiu ISO przez Ventoy. Windows To Go, instalacja przez wypakowanie ISO na NTFS/UEFI:NTFS, konfiguracja odpowiedzi instalatora Windows i tworzenie trwałych partycji dla Live Linux wykraczają poza zakres tej wersji.

## Budowanie

Wymagane są JDK 17, Android SDK Platform 36 i Build Tools 35.0.0. Wskaż SDK przez ANDROID_HOME lub local.properties.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
```

APK powstaje w app/build/outputs/apk/release/. Do dystrybucji użyj apksigner. Aktualizacja wymaga tego samego identyfikatora aplikacji, certyfikatu i rosnącego versionCode.

## Pochodzenie

Kod aplikacji udostępniono na [GPL w wersji 3 lub późniejszej](LICENSE). Niezależny projekt wykorzystuje zasoby [Rufusa](https://github.com/pbatard/rufus/tree/8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c), w tym tabelę MBR (© 2020 Pete Batard, © 2006 Free Software Foundation, Inc.), kod rozruchowy FAT32/FreeDOS i MBR Syslinux oraz KERNEL.SYS i COMMAND.COM z FreeDOS. Pochodzenie składników FreeDOS opisuje [plik projektu Rufus](app/src/main/assets/freedos/readme.txt).

Obrazy rozruchowe Ventoy pochodzą z [oficjalnego wydania 1.1.18](https://github.com/ventoy/Ventoy/releases/tag/v1.1.18). Archiwum Linux sprawdzono za pomocą opublikowanego SHA-256. Zawartość boot/boot.img, boot/core.img.xz i ventoy/ventoy.disk.img.xz jest dostarczana w APK jako boot.bin, core.gz i efi.gz. Tabelę Unicode up-case odczytano z woluminu utworzonego przez mkexfatfs dołączony do tego wydania. [Kod źródłowy i licencje składników Ventoy](https://github.com/ventoy/Ventoy/tree/v1.1.18).
