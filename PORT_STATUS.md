# Stan portu Rufusa

Celem jest zgodność funkcjonalna z Rufusem z rewizji 8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c. Interfejs Androida ma zachować Material 3 Expressive. Wydanie 1.1.0 wykorzystujące Ventoy nie spełnia tego celu.

## Odczyt i zapis

| Obszar | Stan |
| --- | --- |
| USB Bulk-Only / SCSI READ10 i WRITE10 | Istnieje, testy z symulowanym urządzeniem |
| Bezpośredni odczyt dokumentu Androida | Istnieje, bez kopii obrazu w aplikacji |
| Widoczna nazwa źródła również przy błędzie | Istnieje, testy API 26 i 35 |
| Zapis DD i SHA-256 zapisanych zakresów | Istnieje |
| ISO 9660 i Joliet | Czytnik drzewa i ekstentów, test rzeczywistego Alpine ISO |
| UDF | Czytnik partycji fizycznych, alokacji krótkiej/długiej/osadzonej i fragmentów, test rzeczywistego Windows 11 ISO |
| NTFS | Biblioteka pbatard/ntfs-3g skompilowana dla Androida ARM64, ARMv7, x86 i x86_64, integracja USB i formatowania w toku |
| UEFI:NTFS | Zasób źródłowego Rufusa dostępny, integracja oczekuje na zapis systemu plików |
| FAT16/FAT32/exFAT | Istnieją podstawowe generatory FAT32/exFAT, potrzebna pełna obsługa drzewa plików i zgodność opcji |
| UDF, ReFS, ext2/ext3/ext4 jako system docelowy | Do implementacji |
| READ16/WRITE16, nośniki >2 TiB, UAS, wiele LUN | Do implementacji i testów |

## Rozruch i instalacja

| Funkcja oryginału | Stan |
| --- | --- |
| Standardowy instalator Windows wypakowany na NTFS | Do integracji odczytu UDF, NTFS i UEFI:NTFS |
| Windows To Go | Do implementacji operacji WIM, boot configuration i partycji ESP/MSR |
| Personalizacja instalatora Windows i unattended XML | Do implementacji |
| FreeDOS | Podstawowy start BIOS sprawdzony w QEMU |
| MS-DOS | Do implementacji wraz z odpowiednim źródłem plików systemowych |
| Syslinux 4/6, GRUB2, Grub4DOS, ReactOS | Do implementacji |
| Trwała przestrzeń Live Linux | Do implementacji |
| Pobieranie ISO, bootloaderów i danych DBX/SBAT | Do implementacji |
| Pełne opcje etykiet, klastrów, GPT/MBR, BIOS/UEFI, stare BIOS | Do porównania i uzupełnienia |
| Weryfikacja uszkodzonych bloków z wzorcami i liczbą przebiegów Rufusa | Istnieje jedynie podstawowy test zerowania i odczytu |
| Tworzenie i odczyt VHD/VHDX oraz obrazów skompresowanych | Do implementacji |
| Logi, diagnostyka, zapisywanie obrazu urządzenia | Do implementacji |
| Sprawdzanie aktualizacji repozytorium Androida | Istnieje |

## Warunek wydania

Nowy release zgodności z Rufusem wymaga działających powyższych funkcji, przejścia testów regresji, porównania układów woluminów i rozruchu oraz prób na fizycznym Androidzie z USB OTG. Testy parserów ani samo zbudowanie APK nie potwierdzają zgodności całego portu.


## Budowanie części natywnej

Biblioteka NTFS jest przypięta jako podmoduł do d9dca657972d90a64801624a03376044316290b7. Inicjalizuj ją przez git submodule update --init. Skrypt scripts/build-ntfs.sh wymaga Android NDK r27c wskazanego w ANDROID_NDK_HOME oraz hostowych narzędzi autoconf, automake, libtool, m4, make i makr pkg-config/libgcrypt. Domyślnie buduje arm64-v8a. Pozostałe ABI można podać jako argumenty. Wynik powstaje w app/build/native, a połączenie z APK wymaga jeszcze warstwy JNI i operacji urządzenia.

UDF sprawdzono na oficjalnym Windows 11 Enterprise Evaluation o wielkości 8 225 329 152 bajtów. Skan zwrócił 1072 wpisy, w tym sources/install.wim o rozmiarze 7 333 902 541 bajtów w 7 ekstentach. Pobrano jedynie metadane do testu odczytu, bez wypakowania całego obrazu.
