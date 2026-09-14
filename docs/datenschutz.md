# Datenschutzerklärung – Klarwelle

Stand: 14. September 2026

**Verantwortlicher:** Sebastian Müller · sebastian@masesoftware.de

Klarwelle ist ein Radio für Android-Head-Units und Tablets: DAB+ über einen USB-Empfänger,
Internetradio, amtliche Warnmeldungen (ASA) und – auf unterstützten Geräten – FM/AM.

## Das Wichtigste vorab

- Es gibt **kein Konto**, keine Registrierung, keine Werbung, keine Analyse- oder Tracking-Dienste.
- Die App sendet **keine Daten an den Entwickler**. Es gibt keinen eigenen Server.
- Ihr **Standort verlässt das Gerät nicht.** Er wird ausschließlich lokal in einen DAB-Standortcode
  umgerechnet, um Warnmeldungen für Ihre Gegend zu filtern.

## Berechtigungen und wofür sie dienen

| Berechtigung | Zweck | Pflicht? |
|---|---|---|
| Internet, Netzwerkstatus | Internetradio, Senderlogos, Sendersuche, Ortssuche | Für Internetfunktionen |
| Standort (genau/ungefähr) | Nur für „Standort per GPS mitführen": Warnmeldungen für den Ort, an dem sich das Fahrzeug gerade befindet | Nein – die App ist ohne Standort voll nutzbar |
| Über anderen Apps einblenden | Der schwebende Mini-Player, wenn eine andere App im Vordergrund ist | Nein – nur wenn Sie ihn einschalten |
| USB-Zugriff | Der DAB+-Empfänger | Für DAB+ |

## Welche Daten wohin gehen

Klarwelle spricht folgende Gegenstellen an. Jede erhält dabei technisch Ihre IP-Adresse; darüber
hinaus nur das Genannte.

| Dienst | Wann | Was wird übertragen |
|---|---|---|
| **Stream-Server der Sender** | Beim Hören eines Internetsenders oder beim Ausweichen ins Internet | Die Stream-Anfrage |
| **radio-browser.info** (offene Community-Datenbank) | Sendersuche; Suche nach der Internetadresse eines DAB+/FM-Senders für den Fallback | Ihr Suchbegriff bzw. der Sendername |
| **RadioDNS / RadioVIS** (Infrastruktur der Sender, über radiodns.org) | Wenn RadioDNS eingeschaltet ist: Logos, Live-Titel, Bilder, Internet-Adressen | Kennungen des empfangenen Senders (Frequenz, PI, Ensemble) |
| **Media Broadcast** | Nur beim Laden der DAB-Logos, wenn Sie es anstoßen | Ein Download, keine Nutzerdaten |
| **OpenStreetMap Nominatim** | Nur wenn Sie in den Einstellungen einen Ort suchen, um daraus einen Standortcode zu berechnen | Der eingegebene Ortsname |
| **api.ipify.org** | Nur wenn Sie im Info-Fenster ausdrücklich „öffentliche IP nachschlagen" antippen | Eine Anfrage; Antwort ist Ihre öffentliche IP-Adresse |

Die Geräte-Ortssuche kann zusätzlich den Geocoder des Android-Systems nutzen; dessen Datenschutz
richtet sich nach dem Anbieter Ihres Geräts (z. B. Google).

## Standort im Detail

Ist „Standort per GPS mitführen" eingeschaltet, liest die App die Position des Fahrzeugs, rechnet
sie **auf dem Gerät** in einen DAB-Standortcode um (ETSI TS 104 089, Anhang F – Zellen von etwa
einem Kilometer) und vergleicht eingehende Warnmeldungen damit. Gespeichert wird nur der aktuelle
Code, nicht die Position; nichts davon wird übertragen. Von Hand eingegebene Standortcodes werden
lokal in den Einstellungen gespeichert.

## Lokal gespeicherte Daten

Einstellungen, Senderliste, Speicherplätze, Logos und Standortcodes liegen im privaten Speicher
der App. **Nur wenn Sie es einschalten** (Einstellungen › System › „Diagnosedateien schreiben", standardmäßig
aus), schreibt die App Diagnosedateien (`klarwelle-*.txt`) in ihr eigenes Verzeichnis auf dem Gerät
und – falls eingesteckt – auf einen USB-Stick. Sie enthalten technische Ereignisse (Senderwechsel,
Empfang, Warnmeldungen, abgeleitete Standortcodes) und werden **nie automatisch übertragen**. Über
„Diagnosedateien löschen" entfernen Sie sie jederzeit; mit der App werden sie ebenfalls entfernt.
Unabhängig davon wird bei einem Absturz eine technische Fehlerbeschreibung (Stapelspur, ohne
Nutzerdaten) lokal abgelegt.

## Warnmeldungen (ASA)

Warnmeldungen werden über DAB+ empfangen und benötigen kein Netz. Klarwelle ist ein
**zusätzlicher** Empfangsweg und ersetzt keine amtlichen Warnmittel (Cell Broadcast, Warn-Apps,
Sirenen). Klarwelle ist nicht ASA-zertifiziert.

## Ihre Rechte

Da keine personenbezogenen Daten beim Verantwortlichen anfallen, gibt es dort nichts einzusehen
oder zu löschen. Für Fragen: sebastian@masesoftware.de. Beschwerden können Sie an eine
Datenschutzaufsichtsbehörde richten.

## Änderungen

Änderungen dieser Erklärung erscheinen an dieser Stelle mit neuem Datum.
