# Drittkomponenten

Klarwelle steht unter der GPL-3.0 (siehe `LICENSE`). Sie enthält folgende Komponenten anderer
Urheber. Für die LGPL-Bibliotheken gilt: der Quellcode — einschließlich unserer Änderungen —
liegt in diesem Repository (`omri-lib/`, `radiodns-lib/`), sodass sie ausgetauscht werden können.

| Komponente | Zweck | Lizenz | Quelle |
|---|---|---|---|
| hradio/omri-usb (Arbeitskopie `omri-lib/`) | DAB+-Empfang über USB-Stick; **von uns erweitert** um einen FIG-0/15-Parser für ASA-Warnmeldungen und um entfernte IP-Radio-Teile | LGPL 2.1 | github.com/hradio/omri-usb |
| ebu/OpenMobileRadioInterface | OMRI-API | LGPL 2.1 | github.com/ebu/OpenMobileRadioInterface |
| hradio/radiodns (+ minidns) (Arbeitskopie `radiodns-lib/`) | RadioDNS-Auflösung | LGPL 2.1 | github.com/hradio/radiodns |
| AndroidX / Jetpack Compose / media3 (ExoPlayer) | UI, Wiedergabe | Apache 2.0 | android.googlesource.com |
| Kotlin, kotlinx.coroutines | Sprache, Nebenläufigkeit | Apache 2.0 | kotlinlang.org |
| LSPosed HiddenApiBypass | Zugriff auf den Fahrzeug-Tuner der HCT/Microntek-Geräte | Apache 2.0 | github.com/LSPosed/AndroidHiddenApiBypass |

## Dienste, die die App anspricht

Keine Bibliotheken, aber Gegenstellen im Netz — siehe `docs/datenschutz.md`:
radio-browser.info (Sendersuche), RadioDNS/RadioVIS der jeweiligen Sender, Media Broadcast
(DAB-Logos), OpenStreetMap Nominatim (Ortssuche für Standortcodes), die Stream-Server der Sender.

## Marken

DAB+ und das ASA-Logo sind Marken von WorldDAB. Klarwelle verwendet die Wörter beschreibend und
zeigt keines der Logos. Klarwelle ist nicht ASA-zertifiziert.
