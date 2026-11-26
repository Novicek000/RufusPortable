# RufusPortable na Androida

Polskojęzyczna aplikacja do nagrywania obrazów dysków na pendrive przez USB OTG. Wymaga Androida 8.0 lub nowszego, telefonu z USB Host i pamięci masowej zgodnej z USB Bulk-Only Transport i SCSI (LUN 0). Działa bez roota.

[Pobierz podpisany APK z najnowszego wydania](https://github.com/Novicek000/RufusPortable/releases/latest)

## Nagrywanie obrazu

1. Wybierz rozpakowany plik IMG, RAW, BIN, DD lub hybrydowy ISO przeznaczony do nagrania sektorowego. Telefon potrzebuje wolnego miejsca na tymczasową kopię obrazu.
2. Podłącz pendrive przez OTG, wybierz go z listy i zezwól Androidowi na dostęp do USB. Zamknij pliki otwarte na tym nośniku.
3. Sprawdź nazwę pliku, identyfikator USB i pojemność nośnika. **Potwierdzenie zapisu powoduje utratę dotychczasowych danych na wybranym urządzeniu.**
4. Pozostaw aplikację otwartą i pendrive podłączony. Po zapisie aplikacja wysyła polecenie synchronizacji, odczytuje zapisany obszar i porównuje sumy SHA-256. Poczekaj na komunikat o zakończeniu. Po przerwaniu operacji powtórz zapis od początku.

Tryb sektorowy odpowiada nagrywaniu obrazu metodą DD. Rozruch zależy od zawartości obrazu i sprzętu docelowego. Tworzenie instalatora ze zwykłego ISO Windows wymaga innych operacji i wykracza poza funkcje tego wydania. Urządzenia wymagające UAS lub 16-bajtowych poleceń adresowania SCSI nie są obsługiwane.

## Aktualizacje

Aplikacja sprawdza najnowsze stabilne wydanie przy otwarciu oraz po naciśnięciu „Sprawdź aktualizacje”. Korzysta z publicznego API GitHuba dla tego repozytorium i porównuje numery wersji z tagów w formacie v1.2.3. Przycisk nowej wersji otwiera stronę wydania. Instalację APK zatwierdza użytkownik w Androidzie.

## Budowanie

Wymagane są JDK 17, Android SDK Platform 36 i Build Tools 35.0.0. Wskaż SDK przez ANDROID_HOME lub local.properties.

```sh
./gradlew testDebugUnitTest assembleRelease
```

Wynik kompilacji znajduje się w app/build/outputs/apk/release/. Do dystrybucji podpisz APK za pomocą apksigner. Aktualizacje wymagają tego samego identyfikatora aplikacji, tego samego certyfikatu i rosnącego versionCode.

## Pochodzenie i licencja

Niezależna aplikacja na Androida inspirowana projektem [Rufus](https://github.com/pbatard/rufus). Tabela rozpoznawania partycji MBR w app/src/main/res/raw/mbr_types.properties jest adaptacją [src/mbr_types.h](https://github.com/pbatard/rufus/blob/8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c/src/mbr_types.h), © 2020 Pete Batard i © 2006 Free Software Foundation, Inc. Interfejs Material 3 Expressive i obsługę zapisu przez Android USB Host zaimplementowano dla tego projektu. Kod aplikacji udostępniono na [GNU GPL w wersji 3 lub późniejszej](LICENSE).
