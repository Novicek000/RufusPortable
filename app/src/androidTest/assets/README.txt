These small fixtures contain only self-generated dummy data, with no Windows binaries.
The ISO is NOT bootable and cannot install an operating system.
Generate them with scripts/generate-android-fixtures.sh after building the host WIM tool.
Copy metadata.wim and windows-test.iso from app/build/native/android-fixtures here to refresh them.
WIM capture timestamps may vary; the tests compare metadata and contents rather than whole-file hashes.
