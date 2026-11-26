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
| NTFS | Biblioteka pbatard/ntfs-3g i JNI dla czterech ABI w APK; tworzenie katalogów, zapis plików i natywne szybkie/pełne formatowanie przez adapter dyskowy sprawdzone; integracja instalatora w toku |
| UEFI:NTFS | Oryginalny obraz Rufusa w zasobach aplikacji; generator GPT/MBR odwzorowuje układ i typy partycji, integracja rozruchu oczekuje na pełną ścieżkę kopiowania |
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

Biblioteka NTFS jest przypięta jako podmoduł do d9dca657972d90a64801624a03376044316290b7. Inicjalizuj ją przez git submodule update --init. Skrypt scripts/build-ntfs.sh wymaga Android NDK r27c wskazanego w ANDROID_NDK_HOME oraz hostowych narzędzi autoconf, automake, libtool, m4, make i makr pkg-config/libgcrypt. Domyślnie buduje arm64-v8a. Pozostałe ABI można podać jako argumenty. Wynik powstaje w app/build/native, warstwa JNI i operacje urządzenia są dołączane do APK przez zadanie buildNativeNtfs.

UDF sprawdzono na oficjalnym Windows 11 Enterprise Evaluation o wielkości 8 225 329 152 bajtów. Skan zwrócił 1072 wpisy, w tym sources/install.wim o rozmiarze 7 333 902 541 bajtów w 7 ekstentach. Pobrano jedynie metadane do testu odczytu, bez wypakowania całego obrazu.

## Weryfikacja adaptera NTFS

Adapter natywny udostępnia montowanie przygotowanego woluminu, tworzenie katalogów UTF-16, tworzenie i zapis plików oraz zamykanie i synchronizację. JNI przekazuje operacje do interfejsu BlockDevice. UsbDisk udostępnia taki interfejs dla wybranego zakresu partycji, z odczytem i zachowaniem sąsiednich danych przy zapisie niepełnego sektora.

Skrypt scripts/test-native-ntfs.sh wykonuje testy C i Java/JNI na rzeczywistych woluminach NTFS w plikach. Wymaga hostowego GCC, JDK, mkntfs i ntfscat (pakiet ntfs-3g), Python 3 i narzędzi autotools. Niezależny odczyt ntfscat sprawdza zapisane treści. JVM działa z -Xcheck:jni. Zweryfikowano obsługę wyjątków z callbacków, ochronę uchwytów przy odmontowaniu, zamknięcie pozostawionych plików i ponowne montowanie po błędzie odczytu. Biblioteki Androida są linkowane z --no-undefined oraz obsługą wyrównania stron 16 KiB.

Testy te dotyczą adaptera i jego połączenia z Javą. Interfejs zapisu instalatora wymaga jeszcze połączenia formatowania, przygotowania partycji i całej operacji wypakowywania z UEFI:NTFS.


## Formatter i układ instalatora Windows

Formatter mkntfs z przypiętego źródła jest kompilowany do biblioteki JNI. Wykorzystuje adapter I/O partycji. Wywołania są serializowane, stan formattera jest czyszczony po każdej próbie, a błędy zapisu i synchronizacji są przekazywane do Javy. Testy obejmują szybkie i pełne formatowanie, ponowienie po błędzie, polskie znaki w etykiecie oraz sprawdzenie zawartości utworzonych woluminów niezależnym ntfscat. ntfsinfo potwierdziło wersję NTFS 3.1, sektory 512 B, klastry 4096 B i etykietę RUFUS_żółć.

WindowsLayout odwzorowuje rozmieszczenie dwóch partycji z drive.c Rufusa, w tym wyrównanie do ścieżki i klastra. UEFI:NTFS w GPT ma typ Microsoft Basic Data i atrybut No Drive Letter. Testy kontrolują oba schematy, CRC nagłówków GPT, oryginalny kod MBR oraz identyczną zawartość obrazu UEFI:NTFS. Obrazy w app/src/main/assets/rufus pochodzą z res/uefi i src/ms-sys/inc źródłowej rewizji Rufusa. Nowa ścieżka instalacyjna oczekuje jeszcze na integrację w interfejsie aplikacji.
