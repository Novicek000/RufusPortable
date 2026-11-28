# Stan portu Rufusa

Celem jest zgodność funkcjonalna z Rufusem z rewizji 8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c. Interfejs Androida ma zachować Material 3 Expressive. Wydanie 1.1.0 wykorzystujące Ventoy nie spełnia tego celu.

## Odczyt i zapis

| Obszar | Stan |
| --- | --- |
| USB Bulk-Only / SCSI READ10/WRITE10 i READ16/WRITE16 | Istnieje, testy z symulowanym urządzeniem, także adresy ponad 2 TiB |
| Bezpośredni odczyt dokumentu Androida | Istnieje, bez kopii obrazu w aplikacji |
| Widoczna nazwa źródła również przy błędzie | Istnieje, testy API 26 i 35 |
| Zapis DD i SHA-256 zapisanych zakresów | Istnieje |
| ISO 9660 i Joliet | Czytnik drzewa i ekstentów, test rzeczywistego Alpine ISO |
| UDF | Czytnik partycji fizycznych, alokacji krótkiej/długiej/osadzonej i fragmentów, test rzeczywistego Windows 11 ISO |
| NTFS | Biblioteka pbatard/ntfs-3g i JNI dla czterech ABI w APK; tworzenie katalogów, zapis plików i natywne szybkie/pełne formatowanie przez adapter dyskowy sprawdzone; integracja instalatora w toku |
| UEFI:NTFS | Oryginalny obraz Rufusa w zasobach aplikacji; generator GPT/MBR odwzorowuje układ i typy partycji, wypakowywanie i łańcuch UEFI:NTFS zintegrowane, start Windows PE potwierdzony w QEMU |
| FAT16/FAT32/exFAT | Pełny silnik drzewa FAT16/FAT32 sprawdzony niezależnymi narzędziami i na Androidzie; integracja opcji i rozruchu w toku, exFAT wymaga pełnego drzewa |
| UDF, ReFS, ext2/ext3/ext4 jako system docelowy | Do implementacji |
| Wiele LUN | Wykrywanie GET_MAX_LUN, wybór jednostki i adresowanie CBW; testy symulowanego transportu |
| UAS | Do implementacji i testów |

## Rozruch i instalacja

| Funkcja oryginału | Stan |
| --- | --- |
| Standardowy instalator Windows wypakowany na NTFS | Ścieżka jest podłączona w aplikacji, pliki kopiowane i weryfikowane po ponownym montowaniu; WinPE uruchomiony w QEMU, pełna instalacja do sprawdzenia |
| Windows To Go | Aplikowanie WIM przez callbackowy backend NTFS jest zaimplementowane i sprawdzone; konfiguracja BCD, ESP/MSR i integracja trybu pozostają do wykonania |
| Personalizacja instalatora Windows i unattended XML | Konto, prywatność, region, BitLocker, wymagania Windows 11 i QoL podłączone do zapisu; instalacja bezobsługowa i wybór edycji podłączone; CA 2023, SkuSiPolicy i tryb S podłączone; instalacja systemu do weryfikacji |
| FreeDOS | Podstawowy start BIOS sprawdzony w QEMU |
| MS-DOS | Do implementacji wraz z odpowiednim źródłem plików systemowych |
| Syslinux 4/6, GRUB2, Grub4DOS, ReactOS | Do implementacji |
| Trwała przestrzeń Live Linux | Do implementacji |
| Pobieranie ISO, bootloaderów i danych DBX/SBAT | Do implementacji |
| Pełne opcje etykiet, klastrów, GPT/MBR, BIOS/UEFI, stare BIOS | Do porównania i uzupełnienia |
| Weryfikacja uszkodzonych bloków z wzorcami i liczbą przebiegów Rufusa | Zaimplementowane wzorce, identyfikatory bloków, raportowanie i ponawianie; testy symulowanych błędów, próby sprzętowe i eksport raportu do uzupełnienia |
| Tworzenie i odczyt VHD/VHDX oraz obrazów skompresowanych | Do implementacji |
| Logi, diagnostyka, zapisywanie obrazu urządzenia | Do implementacji |
| Sprawdzanie aktualizacji repozytorium Androida | Istnieje |

## Warunek wydania

Nowy release zgodności z Rufusem wymaga działających powyższych funkcji, przejścia testów regresji i porównania układów woluminów oraz rozruchu. Zakres testów sprzętowych jest raportowany wraz z wydaniem. Testy parserów ani samo zbudowanie APK nie potwierdzają zgodności całego portu.


## Budowanie części natywnej

Biblioteka NTFS jest przypięta jako podmoduł do d9dca657972d90a64801624a03376044316290b7. Inicjalizuj ją przez git submodule update --init. Skrypt scripts/build-ntfs.sh wymaga Android NDK r27c wskazanego w ANDROID_NDK_HOME oraz hostowych narzędzi autoconf, automake, libtool, m4, make i makr pkg-config/libgcrypt. Domyślnie buduje arm64-v8a. Pozostałe ABI można podać jako argumenty. Wynik powstaje w app/build/native, warstwa JNI i operacje urządzenia są dołączane do APK przez zadanie buildNativeNtfs.

UDF sprawdzono na oficjalnym Windows 11 Enterprise Evaluation o wielkości 8 225 329 152 bajtów. Skan zwrócił 1072 wpisy, w tym sources/install.wim o rozmiarze 7 333 902 541 bajtów w 7 ekstentach. Pobrano jedynie metadane do testu odczytu, bez wypakowania całego obrazu.

## Weryfikacja adaptera NTFS

Adapter natywny udostępnia montowanie przygotowanego woluminu, tworzenie katalogów UTF-16, tworzenie i zapis plików oraz zamykanie i synchronizację. JNI przekazuje operacje do interfejsu BlockDevice. UsbDisk udostępnia taki interfejs dla wybranego zakresu partycji, z odczytem i zachowaniem sąsiednich danych przy zapisie niepełnego sektora.

Skrypt scripts/test-native-ntfs.sh wykonuje testy C i Java/JNI na rzeczywistych woluminach NTFS w plikach. Wymaga hostowego GCC, JDK, mkntfs i ntfscat (pakiet ntfs-3g), Python 3 i narzędzi autotools. Niezależny odczyt ntfscat sprawdza zapisane treści. JVM działa z -Xcheck:jni. Zweryfikowano obsługę wyjątków z callbacków, ochronę uchwytów przy odmontowaniu, zamknięcie pozostawionych plików i ponowne montowanie po błędzie odczytu. Biblioteki Androida są linkowane z --no-undefined oraz obsługą wyrównania stron 16 KiB.

Testy te dotyczą adaptera i jego połączenia z Javą. Interfejs zapisu instalatora korzysta z natywnego formattera, kopiowania plików i UEFI:NTFS. Pozostaje pełna weryfikacja instalacji i pozostałych funkcji z tabeli.


## Formatter i układ instalatora Windows

Formatter mkntfs z przypiętego źródła jest kompilowany do biblioteki JNI. Wykorzystuje adapter I/O partycji. Wywołania są serializowane, stan formattera jest czyszczony po każdej próbie, a błędy zapisu i synchronizacji są przekazywane do Javy. Testy obejmują szybkie i pełne formatowanie, ponowienie po błędzie, polskie znaki w etykiecie oraz sprawdzenie zawartości utworzonych woluminów niezależnym ntfscat. ntfsinfo potwierdziło wersję NTFS 3.1, sektory 512 B, klastry 4096 B i etykietę RUFUS_żółć.

WindowsLayout odwzorowuje rozmieszczenie dwóch partycji z drive.c Rufusa, w tym wyrównanie do ścieżki i klastra. UEFI:NTFS w GPT ma typ Microsoft Basic Data i atrybut No Drive Letter. Testy kontrolują oba schematy, CRC nagłówków GPT, oryginalny kod MBR oraz identyczną zawartość obrazu UEFI:NTFS. Obrazy w app/src/main/assets/rufus pochodzą z res/uefi i src/ms-sys/inc źródłowej rewizji Rufusa. Nowa ścieżka instalacyjna jest dostępna przez przycisk zapisu w trybie plikowym.


## Wypakowanie i rozruch Windows PE

Bieżąca gałąź wykorzystuje natywną ścieżkę NTFS w interfejsie Androida. Kod i obrazy rozruchowe Ventoya zostały usunięte. Formatowanie NTFS, kopiowanie katalogów i plików z wybranego dokumentu oraz instalowanie kodu rozruchowego Rufusa są połączone. Weryfikacja po ponownym otwarciu woluminu sprawdza długości i SHA-256 plików. Testy obejmują także wykrywanie uszkodzonego odczytu. Testy planu uwzględniają install.wim większy niż 4 GiB, brakujące katalogi, duplikaty nazw i ścieżki wychodzące poza wolumin.

Test rozruchu użył 1071 wpisów i 885 781 214 bajtów plików oficjalnego Windows 11 Enterprise Evaluation, odczytanych bezpośrednio z UDF. Dane zapisano przez Java/JNI/NTFS do obrazu dysku i zweryfikowano SHA-256. UEFI:NTFS 2.8 znalazł partycję NTFS, załadował sterownik NTFS 1.9 i uruchomił Microsoft Windows Boot Manager. Windows PE doszedł do interfejsu Windows 11 Setup w QEMU/OVMF.

Ze względu na miejsce w środowisku testowy obraz rozruchowy pomijał sources/install.wim. Setup pokazał ekran ładowania sterownika. Ten test potwierdza łańcuch rozruchu i start WinPE. Pełna instalacja systemu i pozostałe opcje Rufusa wymagają dalszej weryfikacji. Skopiowanie całego install.wim potwierdził następny test CI opisany poniżej.


## Pełny obraz Windows w CI

[Przebieg GitHub Actions 45854103458](https://github.com/Novicek000/RufusPortable/actions/runs/45854103458) zakończył się sukcesem dla rewizji 82c6371. Test odczytał oficjalne ISO o rozmiarze 8 225 329 152 bajtów i utworzył 1072 wpisy. Zapisano 8 219 683 755 bajtów plików na NTFS, w tym sources/install.wim o długości 7 333 902 541 bajtów w 7 fragmentach UDF. Po ponownym zamontowaniu woluminu zgadzały się długości i SHA-256 wszystkich plików. Metadane partycji również przeszły porównanie.

Test używa ograniczonej do 8 MiB pamięci podręcznej zakresów HTTP, obrazu dysku w środowisku runnera i natywnej biblioteki hosta z tego samego źródła co Android. Biblioteki Androida dla czterech ABI są odrębnie kompilowane do APK. Kopiowanie i odczyt testowe nie stanowią wykonania instalacji Windows na komputerze ani próby na fizycznym telefonie.

Kompilacja źródeł NTFS używa jawnie C11. Poprawka w scripts/patches/ntfs-3g zachowuje kwalifikator const dla wyniku wyszukiwania w stałym łańcuchu. Jest nakładana na kopię źródła w katalogu build, a przypięty podmoduł pozostaje źródłem referencyjnym.

## Biblioteka WIM

Podmoduł `third_party/wimlib` jest przypięty do `4a34203cde54c317f0d277a4c64e19916547bf10`, czyli wersji 1.14.4-3-g4a34203c dołączonej do referencyjnego Rufusa. `scripts/build-wim.sh` buduje bibliotekę i JNI dla czterech ABI Androida. Dla API 26–27 używa ograniczonego adaptera wyszukiwania plików zasobów, ponieważ systemowy glob jest dostępny dopiero od API 28. Test hosta `scripts/test-native-wim.sh` sprawdza metadane wielu obrazów, UTF-8 (w tym emoji), architekturę i nieprawidłowy nagłówek oraz zachowanie adaptera glob.

Przycisk „Obrazy Windows w ISO” odczytuje nazwę, architekturę i numer kompilacji z `sources/install.wim` albo `sources/install.esd`. `ProxyImageFile` przedstawia logiczny plik z ekstentów ISO/UDF jako deskryptor AppFuse Androida. Biblioteka wykonuje odczyt losowy bez pełnej kopii ISO lub WIM w telefonie. Ten etap nie wdraża jeszcze aplikowania obrazu Windows To Go ani modyfikacji WIM.

Na emulatorze Android 8.0 / API 26 (x86) oba testy instrumentacyjne `WimProxyTest` przeszły. Pierwszy odczytuje własny mały WIM z dwóch ekstentów, z których drugi leży za granicą 4 GiB w rzadkim pliku testowym, sprawdza nazwy Unicode, architekturę i kompilację oraz ponawia odczyt. Drugi sprawdza propagowanie błędu uszkodzonego źródła. Test ujawnił ograniczenie AppFuse: ponowne otwarcie wirtualnego inode przez ścieżkę nie działa. Poprawka `0002-android-proxy-descriptor.patch` duplikuje już otwarty deskryptor i zachowuje ścieżkę deskryptora zamiast ponownie otwierać inode. Nie wymaga uprawnień root w aplikacji. Kompilacja czterech ABI, 25 testów Java i lint przeszły. Testy API 26/35 są też zdefiniowane w `.github/workflows/android-wim-test.yml`; samo istnienie workflow nie potwierdza ich wykonania.

## Adresy SCSI 64-bit

`UsbDisk` przechodzi z READ CAPACITY(10) do READ CAPACITY(16), gdy nośnik zwraca granicę 32-bitową. Wybiera READ/WRITE(16), gdy zakres transferu przekracza tę granicę, także jeśli jego początek jeszcze mieści się w 32 bitach. Geometria przekraczająca zakres adresów bajtowych Javy jest odrzucana przed zapisem. Testy emulowanego transportu sprawdzają wysokie adresy, zachowanie sąsiednich bajtów przy niepełnym sektorze, DD z SHA-256 przez granicę LBA oraz przepełnienie pojemności. Wszystkie 28 testów Java i lint przeszły. Nie wykonano próby na fizycznym nośniku powyżej 2 TiB; obsługa adresowania transportu nie potwierdza pełnej zgodności wszystkich generatorów systemów plików z takimi pojemnościami.

## Jednostki logiczne USB

Po przyznaniu dostępu aplikacja odczytuje GET_MAX_LUN. Dla wielojednostkowych czytników pokazuje wybór jednostki, a wybraną LUN podaje w metadanych nośnika i potwierdzeniu zapisu. Wszystkie CBW, w tym sprawdzenie pojemności, odczyt, zapis i synchronizacja, zawierają tę LUN. Brak obsługi GET_MAX_LUN (stall) pozostawia jednostkę 0 zgodnie z Bulk-Only. Nieprawidłowa lub niedostępna jednostka jest odrzucana przed komendami SCSI. Testy symulują czterojednostkowy czytnik, wybór LUN 2, zniknięcie jednostki, błędną odpowiedź i stall. Wszystkie 31 testów Java, lint i budowanie APK przeszły; nie wykonano testu fizycznego czytnika z wieloma LUN.

## Potwierdzenie WIM w Android CI

[GitHub Actions 46027043283](https://github.com/Novicek000/RufusPortable/actions/runs/46027043283), rewizja c3dec7c: oba zadania zakończyły się sukcesem. Na API 26 / x86 i API 35 / x86_64 przeszły po dwa testy Androida, w tym odczyt ekstentu powyżej 4 GiB. Na runnerach przeszły też kompilacja APK z czterema ABI, testy Java, lint oraz testy hostowe WIM. Ten wynik dotyczy rewizji c3dec7c; późniejsze funkcje mają osobno opisane testy.

## Test uszkodzonych bloków

`BadBlocks` zastępuje zerowanie sekwencjami z badblocks.c/rufus.h: jeden przebieg 0x55, dwa 0x55/0xaa, cztery SLC 0x00/0xff/0x55/0xaa, MLC 0x00/0xff/0x33/0xcc lub TLC 0x00/0xff/0x1c71c7/0xe38e38. Identyfikatory 64-bit w pierwszym przebiegu pozwalają wykryć aliasowanie pojemności. Tak jak źródło test używa bloków 512 KiB, pomija końcową niepełną jednostkę i kończy przy 256 uszkodzonych blokach. Bufory grupują do 64 bloków, z ograniczeniem pamięci stosownym do sterty Androida. Nieudany transfer grupy jest ponawiany osobno dla każdego bloku, błędy zapisu/odczytu/różnic są klasyfikowane i deduplikowane.

Opcje pozwalają wybrać zestaw przebiegów i wykrywanie fałszywej pojemności. Wynik z błędami daje wybór ponowienia testu, kontynuacji lub przerwania. Anulowanie operacji przerywa także zadanie wykonawcze. Testy obejmują wszystkie zestawy, wzorzec TLC, fałszywą pojemność, odzyskanie po błędzie grupowego transferu, błędy trwałe, deduplikację, limit i anulowanie. Testy UI API 26/35 sprawdzają zachowanie ustawień po odtworzeniu Activity. Eksport listy bloków i diagnostyka fizycznych awarii USB pozostają do uzupełnienia.

## Modyfikowanie WIM na nośniku docelowym

NTFS udostępnia teraz losowy odczyt/zapis istniejącego pliku, jego rozmiar, zmianę długości i synchronizację. Testy JNI obejmują rozszerzenie, obcięcie, zerowanie rozszerzonych zakresów, błędy synchronizacji i zamknięcie wraz z woluminem. Zawartość sprawdził niezależny ntfscat. `ProxyNtfsFile` udostępnia taki plik jako zapisywalny deskryptor AppFuse. Poprawki wimlib duplikują deskryptor i weryfikują jego uprawnienia. Początkowy tryb dopisywania zasobów został zastąpiony opisaną poniżej przebudową w miejscu, zgodną z Rufusem. Dla formatów wymagających przebudowy biblioteka zwraca błąd zamiast tworzyć pełną kopię WIM poza nośnikiem.

JNI dodaje wskazany plik do wybranego indeksu WIM, wypakowuje plik i weryfikuje integralność zasobów. Test hosta wykrywa uszkodzenie danych wewnątrz WIM. Cztery testy instrumentacyjne API 26 / x86 przeszły: odczyt fragmentów źródła, błąd źródła, wygenerowanie odpowiedzi instalatora i aktualizacja WIM w natywnym NTFS z odczytem po ponownym montowaniu oraz błąd synchronizacji nośnika. Nie wykonano jeszcze instalacji Windows z tą personalizacją.

Generator `unattend.c` przenosi funkcję CreateUnattendXml z przypiętego wue.c. Odczyty ustawień Windows zastępują jawne parametry, a wynik powstaje w pamięci. `test-unattend-reference.sh` kompiluje niezmienioną funkcję referencyjną z kontrolowanymi wejściami systemowymi i porównuje wyniki dla 32 768 kombinacji architektury/flag. Wyniki są identyczne. Testy Java dodatkowo sprawdzają strukturę XML, konta, Unicode, regionalizację i nieprawidłowe parametry.

Podmoduł `third_party/rufus` przypina pełne źródło referencyjne do 8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c. Oryginalne podpisane wrappery setup_x64.exe i setup_arm64.exe wyodrębniono z oficjalnego Rufusa v4.16 po rozpakowaniu UPX. Ich SHA-256 są identyczne z wartościami podanymi w res/setup/readme.txt źródła referencyjnego. Skrypt `extract-rufus-setup.py` umożliwia powtórzenie ekstrakcji i sprawdza te sumy. Wrappery mają zostać podłączone do personalizacji nośnika; samo dołączenie zasobów nie jest jej zakończeniem.

## Personalizacja w ścieżce zapisu Androida

Aplikacja odczytuje profil źródłowego boot.wim i pokazuje opcje konta lokalnego, konta Microsoft, prywatności, regionu, BitLockera, wymagań Windows 11 i usprawnień Rufusa. Zachowuje ustawienia po odtworzeniu Activity. Wykorzystuje mapowanie stref czasowych Unicode CLDR 47, z licencją w assets/windows. Dla obrazu zawierającego własny sources/$OEM$/$$/Panther/unattend.xml używa dostarczonej konfiguracji, tak jak oryginał.

`WindowsCustomization` przed formatowaniem planuje dodatkowy plik OEM albo zmianę boot.wim. Przy pomijaniu wymagań zachowuje appraiserres.dll jako appraiserres.bak, tworzy pusty zamiennik i dla Windows 11 24H2+ zachowuje oryginalny setup.exe jako setup.dll oraz dodaje oryginalny wrapper odpowiedniej architektury. Pliki planu przechodzą zwykłe kopiowanie i weryfikację NTFS. Wariant z windowsPE dodaje odpowiedź do odpowiedniego indeksu boot.wim bez jego kopiowania na telefon. Po ponownym montowaniu sprawdza zasoby WIM i treść pliku odpowiedzi. W telefonie powstają tylko mały wygenerowany XML i jego kontrolny odczyt, usuwane po operacji.

Aktualizacja WIM korzysta z WIMLIB_WRITE_FLAG_UNSAFE_COMPACT jak ApplyWindowsCustomization w Rufusie, aby ograniczyć fragmentację istotną dla niektórych firmware. AppFuse nie udostępnia truncate: JNI zwraca końcową długość z out_fd, a właściciel pliku NTFS wykonuje zmianę długości i synchronizację. Warstwa JNI używa tych samych 64-bitowych offsetów co wimlib; zgodność wymusza asercja kompilacji. Test 32-bitowego Androida wykrył i pozwolił poprawić wcześniejszą różnicę ustawień. Pliki pipable, podzielone i oznaczone jako tylko do odczytu są odrzucane przed zapisem w trybie wymagającym tej modyfikacji.

51 testów Java, lint i budowanie APK przeszły. Sześć testów na emulatorze API 26 / x86 obejmuje modyfikację i integralność WIM, błąd synchronizacji, źródło powyżej 4 GiB, NTFS na rzadkim nośniku 64 GiB z rzeczywistymi operacjami powyżej 4 GiB oraz interfejs otwierający własny testowy ISO i wybierający ustawienia przez dostępność Androida. Testowy ISO nie zawiera Windows i nie potwierdza rozruchu ani instalacji systemu. Próba instalacji z personalizacją pozostaje do wykonania.

[CI 46036252453](https://github.com/Novicek000/RufusPortable/actions/runs/46036252453) potwierdziło wcześniejszą rewizję 0d91d43 na API 26 i 35, po cztery testy instrumentacyjne. Późniejsza integracja dialogu i przebudowa w miejscu mają oddzielną weryfikację opisaną powyżej; nowy przebieg CI będzie odnosił się do nowego commita.

## Odzyskiwanie transportu Bulk-Only

Obsługa USB wykonuje reset klasy Bulk-Only i czyści halt obu endpointów po błędzie transportu. Ponawia komendę najwyżej raz. REQUEST SENSE rozróżnia ochronę zapisu, brak nośnika, błędy sprzętu oraz przejściowe Unit Attention i Aborted Command. Krótkie odpowiedzi sense i READ CAPACITY(16) są akceptowane tylko z poprawnym residue i wymaganymi polami. Alternatywny interfejs Bulk-Only jest aktywowany przed komendami; nie stanowi to implementacji UAS. 58 testów Java i lint przeszły, w tym przerwany zapis, błędna faza, nieudany reset i ograniczenie ponowień. Testy transportu używają emulowanego urządzenia, próby fizycznych awarii pozostają do wykonania.

[CI 46043061271](https://github.com/Novicek000/RufusPortable/actions/runs/46043061271) potwierdziło personalizację z rewizji 902e5f5: po sześć testów instrumentacyjnych na API 26 i 35, budowanie czterech ABI, 51 testów Java, lint i testy natywne, w tym 32 768 porównań generatora z oryginałem.

## Instalacja bezobsługowa i edycje Windows

Profil instalatora odczytuje metadane install.wim lub install.esd przez bezpośredni AppFuse. Wybór zachowuje indeks WIM; nazwa edycji stosuje DISPLAYNAME, następnie DESCRIPTION i nazwę zastępczą, tak jak GetEditions. JNI przyjmuje listę właściwości zamiast sztywnego rekordu. UTF-8 pozostaje zachowane.

Dla Windows 11 opcja bezobsługowa pojawia się dopiero po włączeniu konta lokalnego, regionalizacji i pomijania pytań o zbieranie danych. Wyłączenie któregoś warunku odznacza ją. Przed zastosowaniem trzy oświadczenia wymagają osobnego zaznaczenia, zgodnie z SelectionDialog oryginału. Końcowe potwierdzenie USB przypomina o automatycznym wymazaniu dysku 0 komputera. Wybór edycji i ustawienia przetrwają odtworzenie Activity. XML nadal pochodzi z porównanego generatora Rufusa; test sprawdza indeks edycji i cel disk 0 / partition 3.

60 testów Java, lint, kompilacja czterech ABI, testy hostowe WIM oraz 32 768 porównań XML przeszły. Sześć testów na API 26 / x86 przeszło, w tym obsługa syntetycznego ISO, rzeczywisty natywny odczyt WIM i wybór edycji ARM64 przez dostępność Androida wraz z trzema potwierdzeniami. Nie jest to jeszcze test automatycznej instalacji Windows.

## Windows CA 2023 i pozostałe opcje instalatora

Opcje CA 2023 i SkuSiPolicy pojawiają się od kompilacji 26200, jak w źródle Rufusa. Tryb S jest opisany jako zaawansowany, z informacją o możliwości utrzymania blokady po reinstalacji. SkuSiPolicy i tryb S korzystają z oryginalnego generatora, bez zmiany jego warunków emisji sekcji XML.

Przed formatowaniem opcja CA 2023 wypakowuje wyłącznie katalogi Windows/Boot/EFI_EX i Fonts_EX z wybranego indeksu boot.wim do osobnego katalogu roboczego. Nie kopiuje całego ISO ani WIM na telefon. Plan zastępuje pierwszy pasujący plik EFI w kolejności architektur Rufusa, opcjonalny bootmgr.efi i czcionki z usuniętym przyrostkiem _EX. Zachowuje wielkość liter istniejących katalogów. Te pliki uczestniczą w zwykłym kopiowaniu i sprawdzeniu SHA-256 na NTFS. Katalog roboczy jest usuwany także przy błędzie. Sam boot.wim jest przebudowywany w miejscu na nośniku z UNSAFE_COMPACT również wtedy, gdy nie dodaje się odpowiedzi windowsPE.

66 testów Java, lint, budowanie czterech ABI, natywne testy WIM i porównanie generatora przeszły. Siedem testów na API 26 / x86 przeszło. Nowy test przechodzi od własnego ISO przez AppFuse, wypakowanie zasobów EFI, plan podmiany, instalację NTFS i kompaktowanie docelowego WIM do weryfikacji po ponownym montowaniu. Używa własnych danych testowych; nie dowodzi rozruchu Microsoft Secure Boot CA 2023 na fizycznym komputerze.

[CI 46045827499](https://github.com/Novicek000/RufusPortable/actions/runs/46045827499) potwierdziło wcześniejszą rewizję a21f401 na API 26 i 35, w tym wybór edycji i potwierdzenia instalacji bezobsługowej.

## Próba pełnej instalacji w CI — przygotowanie

Workflow windows-install-test.yml przygotowuje kompletny nośnik z oficjalnego ISO i uruchamia odizolowaną maszynę QEMU/KVM z nowym plikiem dysku docelowego. Hostowy test używa tego samego generatora XML, wrapperów, planu plików i NTFS. Wyłącznie test dodaje do FirstLogonCommands polecenie zapisania informacji o zainstalowanym systemie i wyłączenia VM. Po zatrzymaniu VM test ma odczytać wynik z NTFS i sprawdzić konto, wersję, region, strefę oraz IMAGE_STATE_COMPLETE.

Przygotowanie sondy sprawdzono lokalnie na własnym małym WIM: aktualizacja JNI, poprawny XML, długość zastąpionego boot.wim i wrapper setup.exe. Samo przygotowanie workflow nie dowodzi udanej instalacji. Wynik uruchomienia będzie zapisany osobno. Test hosta przechowuje pojedynczy roboczy boot.wim, podczas gdy ścieżka Androida modyfikuje go bezpośrednio na NTFS przez AppFuse.

Sprzątanie katalogów roboczych usuwa także zagnieżdżone zasoby EFI i czcionek. Przechodzenie drzewa nie podąża za dowiązaniami symbolicznymi. Testy obu wersji API sprawdzają usunięcie zagnieżdżonych plików i zachowanie pliku poza katalogiem wskazywanego przez dowiązanie. Łącznie 68 testów Java i lint przeszły.

## Aplikowanie WIM przez backend NTFS

WimMetadata.apply uruchamia oryginalny backend ntfs-3g biblioteki wimlib. Poprawka 0004 zmienia wyłącznie wybór montowania/odmontowania dla wewnętrznego celu callbackowego. Algorytmy przywracania danych i metadanych pozostają kodem wimlib. Ten backend montuje własny wolumin przez rufus_disk, bez systemowego mount, roota ani kopiowania całego WIM. JNI współdzieli adapter wywołań BlockDevice z dotychczasową biblioteką NTFS. Montowanie tworzy też pamięci podręczne NTFS używane w natywnym ntfs_mount. Biblioteki statyczne są ukryte przed eksportem symboli obu JNI.

Wimlib jest budowany z obsługą NTFS dla czterech ABI. Bionic dostarcza ffs, a kompilacja uwzględnia tę deklarację w nagłówkach zgodności libntfs. Build hosta buduje i sprawdza także rdzeń NTFS. Postęp rozróżnia liczbę plików i bajty; wyjątki zapisu, synchronizacji i anulowania są przekazywane do Javy, a powtórna operacja zwalnia i odtwarza stan callbackowego celu.

Test hosta aplikuje własny WIM na NTFS 128 MiB i 64 GiB, sprawdza treść Unicode, duże adresy, postęp, nieprawidłowy indeks bez zapisu, anulowanie, błędy dysku i ponowienie. Niezależne ntfsinfo i ntfscat potwierdzają tożsamość dowiązań oraz treść nazwanego strumienia. Dodatkowy test C porównuje dokładne bajty deskryptora ACL z własną próbką testową wimlib, atrybuty, trzy znaczniki czasu z dokładnością 100 ns i tag dowiązania symbolicznego.

68 testów Java, lint, budowanie APK z czterema ABI i dziewięć testów Androida API 26 / x86 przeszły. Nowy test Androida aplikuje WIM z dwóch ekstentów źródła, z drugim powyżej 4 GiB, na rzadki cel 64 GiB i porównuje treść i SHA-256 odczytanych plików po ponownym zamontowaniu. Drugi sprawdza anulowanie i ponowienie. To jest silnik aplikowania obrazu; nie stanowi jeszcze uruchamialnego trybu Windows To Go w interfejsie.

[CI 46072993307](https://github.com/Novicek000/RufusPortable/actions/runs/46072993307) potwierdziło wcześniejszą rewizję 5f13fee na API 26 i 35, w tym podmianę zasobów CA 2023 i kompaktowanie WIM.

## FAT16/FAT32 i partycja EFI

FatVolume planuje pełne drzewo przed zapisem: sprawdza pojemność, rozmiary plików, katalogi nadrzędne, nazwy i kolizje; tworzy unikalne aliasy 8.3, wpisy VFAT i wieloklastrowe katalogi. Obsługuje sektory 512–4096 B, klastry do 64 KiB, dwie tablice FAT, backup i FSInfo FAT32, puste etykiety EFI oraz szybkie/pełne formatowanie. Nie przyjmuje plików większych niż 4 GiB minus jeden bajt. Katalogi i tablice zapisuje porcjami do 128 KiB, a pliki strumieniowo. Po synchronizacji weryfikuje SHA-256 metadanych i danych.

Test hosta utworzył siedem wariantów, w tym FAT16/FAT32 dla sektorów 512 B i 4 KiB, puste FAT32, pełne zerowanie FAT16 oraz daty przy lokalizacji tajskiej. Wszystkie przeszły fsck.fat bez napraw. W czterech wariantach PyFat odczytał dokładne nazwy i SHA-256 wszystkich 1710 plików, w tym 1700 aliasów ze wspólnym początkiem, maksymalną nazwę 255 jednostek UTF-16, nazwę długości 13 i parę surogatów rozdzieloną między wpisy VFAT. Mtools potwierdził dane; jego ograniczenie znaków spoza BMP sprawdzane jest osobno od dokładnych nazw odczytanych przez PyFat. Testy wykrywają uszkodzone metadane, dane i skrócone źródło oraz anulowanie przed formatowaniem.

68 testów Java, lint, budowanie APK i dziesięć testów Androida API 26 / x86 przeszły. Nowy test kopiuje dane z natywnego NTFS po aplikowaniu WIM do FAT32 z sektorami 4 KiB i pustą etykietą. Wyeksportowany rzadki obraz 512 MiB z emulatora przeszedł ponownie fsck.fat oraz niezależne porównanie nazw Unicode i SHA-256 obu plików. Są to własne niebootowalne dane testowe. Integracja tego silnika z wyborem systemu plików i pełny rozruch Windows To Go nadal pozostają do wykonania.

[CI 46075286618](https://github.com/Novicek000/RufusPortable/actions/runs/46075286618) potwierdziło wcześniejszą rewizję 97f34fc na Androidzie API 26 i 35, w tym aplikowanie WIM przez NTFS i anulowanie.

## Rejestr offline dla konfiguracji rozruchu

Podmoduł hivex jest przypięty do v1.3.24 / 7996d79cf910758fcb29554e83f44c94560f170d. RegistryHive udostępnia odczyt i zmianę wartości, listę podkluczy, usuwanie, jawny zapis i zamknięcie. Źródło pozostaje niezmienione przy zapisie do innego pliku. Hivex parsuje i zapisuje komórki, a port zawiera małe adaptery odczytu/zapisu i konwersji UTF-8 / UTF-16LE / Latin-1 dla API 26–27. Nie włącza heurystyk zapisu uszkodzonych rejestrów. Biblioteka dla czterech ABI jest dołączona do APK.

Adapter kodowania porównano z libc iconv dla każdego skalaru Unicode, Latin-1 oraz błędnych i niepełnych sekwencji. Testy JNI sprawdzają DWORD, Unicode w nazwach wartości i danych, dane binarne z zerami, zastępowanie bez kasowania innych wartości, rozrost o 1000 kluczy, usuwanie, tryb tylko do odczytu, nieudany commit i zamknięcie. Niezależny parser rejestru z wimlib odczytał i potwierdził zapisany wynik. Ze względu na algorytm haszowania nowych nazw lh w hivex 1.3.24 wrapper tworzy tylko klucze ASCII używane przez konfigurację Rufusa; istniejące klucze Unicode i wartości Unicode są zachowane.

68 testów Java, lint, budowanie APK i dwanaście testów Androida API 26 / x86 przeszły. Dwa nowe testy obejmują zapis do ścieżki Unicode, ponowne otwarcie, wartości 64-bitowe, tryb tylko do odczytu i zamknięcie. Jest to warstwa edycji rejestru; konfiguracja BCD i pełna integracja Windows To Go nadal są w toku.

Próba pełnej instalacji Windows [CI 46073326965](https://github.com/Novicek000/RufusPortable/actions/runs/46073326965) utworzyła i zweryfikowała cały spersonalizowany nośnik, uruchomiła WinPE i przeszła bez pytań do ekranu instalowania. Zrzuty pokazują postęp od 11% po dwóch minutach do 51% po 44 minutach. Limit 45 minut zakończył VM przed ukończeniem. Nie jest to potwierdzenie udanej instalacji; kolejna próba ma dłuższy czas i pomiary aktywności dysków.

## Natywna konfiguracja Windows To Go

`WindowsToGoLayout` odwzorowuje `CreatePartition`: GPT z ESP 260 MiB z przodu, MSR 128 MiB i głównym NTFS oraz wyrównaniem ścieżka/klaster. Metadane mają oba nagłówki i tablice GPT, także przy sektorach 1024, 2048 i 4096 B. MBR obsługuje aktywny NTFS i opcjonalny oryginalny UEFI:NTFS przy sektorach 512 B. To są układy trybu Windows To Go; wcześniejsza ścieżka instalatora zachowuje swój sprawdzony generator.

`WindowsBootConfiguration` edytuje BCD-Template pochodzący z wybranego Windows: kopiuje obiekt loadera i jego dziedziczenie, ustawia rzeczywiste identyfikatory partycji GPT lub sygnaturę/offset MBR, ścieżki BIOS/UEFI, domyślny wpis i kolejność oraz wyłącza recovery. Usuwa instrukcje szablonu i tryb WinPE. Format urządzenia w REG_BINARY różni się od struktur WMI; jego układ porównano z [opisem rzeczywistych danych BCD](https://github.com/a1ive/grub/issues/18), a identyfikatory elementów z dokumentacją Microsoft BCD i ntbcd.h projektu System Informer. Testy nie zastępują rozruchu menedżera Microsoft.

`WindowsToGoBootFiles` czyta pliki z już zastosowanego NTFS, bez kopii ISO/WIM. Natywna enumeracja katalogów zwraca nazwy UTF-16, rozmiary i informację o reparse; pomija aliasy DOS i nie podąża za reparse. Pliki EFI/PCAT, awaryjny boot*.efi, czcionki i zasoby trafiają do EFI lub NTFS. Tryb CA 2023 korzysta z katalogów _EX. W pamięci roboczej pojawiają się tylko ograniczone rozmiarem pliki rejestru. Domyślny ControlSet z SYSTEM otrzymuje SanPolicy=4 po wybraniu dysków offline. Odpowiedź trafia do Windows/Panther; odpowiedź istniejąca w obrazie ma pierwszeństwo. Zapisane pliki i zmodyfikowany SYSTEM są sprawdzane SHA-256.

Testy hosta potwierdziły pliki BIOS+UEFI, ESP 512/4096 B, CA 2023, SanPolicy wybranego ControlSet, zachowanie innego ControlSet i istniejącej odpowiedzi, przerwanie oraz usuwanie katalogu roboczego. fsck.fat zgłasza poprawne oba ESP, niezależny PyFatFS potwierdza dokładną zawartość bootloadera, fallback, czcionek i zasobów oraz obecność BCD. Skrypt `test-wintogo-boot.sh` wymaga wcześniejszych testów natywnych NTFS i hivex oraz zależności z requirements-fat-test.txt.

`WindowsToGoInstaller` łączy formatowanie, aplikowanie WIM i konfigurację. Integracja wyboru trybu w interfejsie i pełny rozruch pozostają do potwierdzenia. Workflow `windows-to-go-test.yml` przygotowuje pełny Windows z oficjalnego ISO i uruchamia USB w QEMU/KVM aż do sondy pierwszego logowania. Hostowy WIM tego testu zastępuje deskryptor AppFuse Androida; nie jest mechanizmem kopiowania WIM w aplikacji. Dopóki próba nie przejdzie, Windows To Go nie jest oznaczony jako ukończony.

## Wynik próby instalacji Windows i sonda CI

[Przebieg 46079143579](https://github.com/Novicek000/RufusPortable/actions/runs/46079143579) przygotował i zweryfikował cały nośnik, a Windows Setup zakończył kopiowanie, utworzył docelowy menedżer rozruchu i uruchomił pulpit konta RufusTest (screen-30). Późniejsze zrzuty pokazują żądanie zmiany hasła, zgodne z poleceniem oryginalnego generatora. Przebieg zakończył limit 120 minut, ponieważ nie otrzymano wyniku sondy i wyłączenia VM. Potwierdza to dojście do pulpitu, ale nie spełnia jeszcze automatycznych asercji systemu, języka, strefy i stanu instalacji.

Sonda CI używa teraz krótkiego polecenia `powershell -File` i osobnego, wyłącznie testowego skryptu dostarczanego przez OEM. Numer polecenia następuje po istniejących poleceniach Rufusa. Skrypt zapisuje transkrypcję i błąd. Odczyt logów Panther, UnattendGC, użytej odpowiedzi i sondy wykonywany jest także po błędzie lub przekroczeniu czasu, po zatrzymaniu QEMU. Nie zmienia to generatora odpowiedzi aplikacji ani jego 32 768 porównań z oryginałem.

72 testy Java, lint, budowanie czterech ABI oraz 13 testów instrumentacyjnych API 26/x86 przeszły dla warstwy Windows To Go i enumeracji NTFS. Pełny Windows To Go oraz integracja jego wyboru w interfejsie nadal pozostają w toku.
