# Klarwelle

**DAB+ Autoradio mit Warnfunktion** für Android-Head-Units und Tablets.

DAB+ über einen USB-Empfänger, Internetradio, amtliche Warnmeldungen (ASA nach ETSI TS 104 089)
und – auf HCT/Microntek-Geräten – FM/AM, in einer Oberfläche. Wird DAB+ zu schwach, wechselt
Klarwelle selbst: erst auf dasselbe Programm in einem anderen Ensemble, dann auf FM oder Internet,
und zurück, sobald DAB+ wieder da ist. Logos, Live-Titel und Internet-Adressen kommen über RadioDNS
direkt von den Sendern.

- **Store:** Google Play (Head Units und Tablets; Telefone sind bewusst ausgeschlossen)
- **Datenschutz:** [docs/datenschutz.md](docs/datenschutz.md) · [docs/privacy.md](docs/privacy.md)
- **Lizenz:** GPL-3.0 (siehe [LICENSE](LICENSE)); Drittkomponenten in [THIRD-PARTY.md](THIRD-PARTY.md)
- **Kontakt:** Sebastian Müller · sebastian@masesoftware.de

## Bauen

Android Studio oder Kommandozeile mit Android SDK, NDK r26 und Java 21:

```sh
./gradlew :app:assembleDebug        # Debug-APK, mit ASA-Simulation per adb (siehe MainActivity)
./gradlew :app:assembleRelease      # signierte Release-APK (Secrets in local.properties)
./gradlew :app:bundleRelease        # App Bundle für Play
./gradlew :app:testDebugUnitTest    # ~160 JVM-Tests inkl. Robolectric-Screenshots, ~5 s
```

`omri-lib/` und `radiodns-lib/` sind Arbeitskopien der LGPL-Bibliotheken hradio/omri-usb und
hradio/radiodns mit unseren Änderungen (u. a. FIG-0/15-Parser für ASA) — siehe THIRD-PARTY.md.

## Projekt

Zielgerät ist eine HCT/M.I.C. AV8V6 (PX6, RK3399, 1280×720) im VW Golf 5; die App läuft aber auf
jedem Android ab 5.0 mit USB-Host.

DAB+ und das ASA-Logo sind Marken von WorldDAB. Klarwelle ist nicht ASA-zertifiziert und ersetzt
keine amtlichen Warnmittel.
