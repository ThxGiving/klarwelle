#!/usr/bin/env python3
"""Store-Eintrag in acht Sprachen. Prueft die Play-Limits (Titel 30, Kurz 80, Lang 4000) und
schreibt je Sprache eine Markdown-Datei. Texte hier aendern, dann ausfuehren."""
import os
D=os.path.dirname(os.path.abspath(__file__))
T={
'de':('Klarwelle: DAB+ Autoradio','DAB+ per USB-Stick, amtliche Warnmeldungen (ASA), Internetradio, echte Logos.','''Klarwelle macht aus einer Android-Head-Unit ein Radio, das nicht abreißt.

WARNMELDUNGEN, AUCH WENN SIE INTERNETRADIO HÖREN
Klarwelle unterstützt ASA (Automatic Safety Alert, ETSI TS 104 089). Warnungen kommen über DAB+ und brauchen kein Mobilfunknetz. Sie werden für Ihren Standort gefiltert – per GPS, das mit dem Fahrzeug mitwandert, oder für feste Orte wie Zuhause, deren Code Sie eingeben oder per Ortssuche berechnen lassen. Der DAB+-Empfänger überwacht das Warn-Ensemble auch, während Sie einen anderen Sender hören.

SENDER SO, WIE DER SENDER SIE MEINT
Über RadioDNS holt Klarwelle die offiziellen Logos direkt vom Anbieter, zeigt Live-Titel und Bilder zum laufenden Programm und findet den Internetstream zu einem DAB+-Sender – damit der Wechsel ins Internet klappt, wenn der Empfang wegbricht.

EMPFANG, DER NICHT ABREISST
Wird DAB+ zu schwach, wechselt Klarwelle selbstständig: zuerst auf dasselbe Programm in einem anderen Ensemble, dann auf FM oder Internet – in der Reihenfolge, die Sie festlegen – und zurück, sobald DAB+ wieder da ist.

WAS SONST NOCH DRIN IST
• DAB+ über USB-Empfänger, Ensemble-Scan, DLS-Text und Slideshow
• Internetradio mit Suche in der offenen radio-browser-Datenbank, eigene Streams
• Sechs Speicherplätze pro Band, Lenkrad- und Mediatasten, Mini-Player über anderen Apps
• Aufgeräumte Oberfläche im Stil moderner Werksradios, Tag- und Nachtmodus, acht Sprachen

WAS SIE WISSEN SOLLTEN
• DAB+ und Warnmeldungen benötigen einen USB-DAB-Empfänger. Ohne ihn ist Klarwelle ein Internetradio.
• FM/AM funktioniert nur auf unterstützten Head-Units (Microntek/HCT).
• ASA befindet sich im Aufbau; derzeit laufen Testaussendungen. Klarwelle ist ein zusätzlicher Empfangsweg und ersetzt keine amtlichen Warnmittel. Klarwelle ist nicht ASA-zertifiziert.
• Kein Konto, keine Werbung, kein Tracking. Ihr Standort verlässt das Gerät nicht.

Klarwelle ist freie Software (GPL-3.0). Quellcode: github.com/ThxGiving/klarwelle
DAB+ und das ASA-Logo sind Marken von WorldDAB.'''),
'en':('Klarwelle: DAB+ Car Radio','DAB+ via USB stick, official emergency alerts (ASA), internet radio, real logos.','''Klarwelle turns an Android head unit into a radio that doesn't drop out.

EMERGENCY ALERTS, EVEN WHILE YOU LISTEN TO INTERNET RADIO
Klarwelle supports ASA (Automatic Safety Alert, ETSI TS 104 089). Alerts arrive over DAB+ and need no mobile network. They are filtered for your location – by GPS that follows the car, or for fixed places like home, whose code you enter or compute by place search. The DAB+ receiver keeps monitoring the alert ensemble even while you listen to something else.

STATIONS THE WAY THE BROADCASTER MEANS THEM
Through RadioDNS, Klarwelle fetches the official logos straight from the broadcaster, shows live titles and images for the running programme, and finds the internet stream of a DAB+ station – so the switch to the internet works when reception fails.

RECEPTION THAT DOESN'T DROP OUT
When DAB+ gets too weak, Klarwelle switches on its own: first to the same programme in another ensemble, then to FM or internet – in the order you choose – and back as soon as DAB+ returns.

WHAT ELSE IS IN
• DAB+ via USB receiver, ensemble scan, DLS text and slideshow
• Internet radio with search in the open radio-browser database, custom streams
• Six presets per band, steering-wheel and media keys, mini-player over other apps
• Clean interface in the style of modern factory radios, day and night mode, eight languages

GOOD TO KNOW
• DAB+ and alerts need a USB DAB receiver. Without one, Klarwelle is an internet radio.
• FM/AM only works on supported head units (Microntek/HCT).
• ASA is still being rolled out; test transmissions are on air today. Klarwelle is an additional channel and does not replace official warning means. Klarwelle is not ASA certified.
• No account, no ads, no tracking. Your location never leaves the device.

Klarwelle is free software (GPL-3.0). Source: github.com/ThxGiving/klarwelle
DAB+ and the ASA logo are trademarks of WorldDAB.'''),
}
# Die uebrigen Sprachen folgen derselben Gliederung; Uebersetzungen aus dem Deutschen.
T['es']=('Klarwelle: Radio DAB+ coche','DAB+ por USB, alertas oficiales (ASA), radio por internet, logotipos reales.','''Klarwelle convierte una unidad Android en una radio que no se corta.

ALERTAS DE EMERGENCIA, INCLUSO ESCUCHANDO RADIO POR INTERNET
Klarwelle admite ASA (Automatic Safety Alert, ETSI TS 104 089). Las alertas llegan por DAB+ y no necesitan red movil. Se filtran por su ubicacion: por GPS que sigue al vehiculo, o por lugares fijos como su casa, cuyo codigo introduce o calcula buscando el lugar. El receptor DAB+ vigila el conjunto de alertas aunque escuche otra cosa.

EMISORAS TAL COMO LAS CONCIBE LA EMISORA
Con RadioDNS, Klarwelle obtiene los logotipos oficiales del propio emisor, muestra titulos e imagenes en directo y encuentra el stream de internet de una emisora DAB+ para poder cambiar a internet cuando falla la recepcion.

RECEPCION QUE NO SE CORTA
Si DAB+ se debilita, Klarwelle cambia sola: primero al mismo programa en otro conjunto, luego a FM o internet, en el orden que elija, y vuelve en cuanto DAB+ regresa.

ADEMAS
• DAB+ por receptor USB, exploracion de conjuntos, texto DLS y slideshow
• Radio por internet con busqueda en la base abierta radio-browser, streams propios
• Seis presintonias por banda, teclas del volante y multimedia, minirreproductor sobre otras apps
• Interfaz limpia al estilo de las radios de fabrica, modo dia y noche, ocho idiomas

CONVIENE SABER
• DAB+ y las alertas requieren un receptor DAB por USB. Sin el, Klarwelle es una radio por internet.
• FM/AM solo funciona en unidades compatibles (Microntek/HCT).
• ASA esta en despliegue; hoy hay emisiones de prueba. Klarwelle es un canal adicional y no sustituye los medios de aviso oficiales. Klarwelle no esta certificada ASA.
• Sin cuenta, sin publicidad, sin rastreo. Su ubicacion no sale del dispositivo.

Klarwelle es software libre (GPL-3.0). Codigo: github.com/ThxGiving/klarwelle
DAB+ y el logotipo ASA son marcas de WorldDAB.''')
T['fr']=('Klarwelle : autoradio DAB+','DAB+ par cle USB, alertes officielles (ASA), webradio, vrais logos.','''Klarwelle transforme un autoradio Android en une radio qui ne decroche pas.

ALERTES D'URGENCE, MEME EN ECOUTANT LA WEBRADIO
Klarwelle prend en charge ASA (Automatic Safety Alert, ETSI TS 104 089). Les alertes arrivent par DAB+ et ne necessitent aucun reseau mobile. Elles sont filtrees pour votre position : par GPS qui suit le vehicule, ou pour des lieux fixes comme votre domicile, dont vous saisissez ou calculez le code. Le recepteur DAB+ surveille l'ensemble d'alerte meme quand vous ecoutez autre chose.

LES STATIONS TELLES QUE LE DIFFUSEUR LES CONCOIT
Via RadioDNS, Klarwelle recupere les logos officiels directement chez le diffuseur, affiche titres et images en direct et trouve le flux internet d'une station DAB+ – pour que le passage a internet fonctionne quand la reception faiblit.

UNE RECEPTION QUI NE DECROCHE PAS
Quand le DAB+ faiblit, Klarwelle bascule seule : d'abord vers le meme programme dans un autre ensemble, puis vers la FM ou internet – dans l'ordre que vous choisissez – et revient des que le DAB+ est de retour.

ET AUSSI
• DAB+ par recepteur USB, recherche d'ensembles, texte DLS et diaporama
• Webradio avec recherche dans la base ouverte radio-browser, flux personnalises
• Six presets par bande, commandes au volant et touches media, mini-lecteur au-dessus des autres apps
• Interface epuree dans le style des autoradios d'origine, mode jour/nuit, huit langues

A SAVOIR
• Le DAB+ et les alertes necessitent un recepteur DAB USB. Sans lui, Klarwelle est une webradio.
• La FM/AM ne fonctionne que sur les unites compatibles (Microntek/HCT).
• ASA est en cours de deploiement ; des emissions de test sont diffusees. Klarwelle est un canal supplementaire et ne remplace pas les moyens d'alerte officiels. Klarwelle n'est pas certifiee ASA.
• Pas de compte, pas de publicite, pas de suivi. Votre position ne quitte pas l'appareil.

Klarwelle est un logiciel libre (GPL-3.0). Code source : github.com/ThxGiving/klarwelle
DAB+ et le logo ASA sont des marques de WorldDAB.''')
T['it']=('Klarwelle: autoradio DAB+','DAB+ via USB, allerte ufficiali (ASA), radio via internet, loghi veri.','''Klarwelle trasforma un'unita Android in una radio che non si interrompe.

ALLERTE DI EMERGENZA, ANCHE MENTRE ASCOLTI LA RADIO VIA INTERNET
Klarwelle supporta ASA (Automatic Safety Alert, ETSI TS 104 089). Le allerte arrivano via DAB+ e non richiedono rete mobile. Vengono filtrate per la tua posizione: tramite GPS che segue il veicolo, oppure per luoghi fissi come casa, il cui codice inserisci o calcoli cercando il luogo. Il ricevitore DAB+ sorveglia l'ensemble di allerta anche mentre ascolti altro.

LE EMITTENTI COME LE INTENDE L'EMITTENTE
Tramite RadioDNS, Klarwelle scarica i loghi ufficiali direttamente dall'emittente, mostra titoli e immagini in diretta e trova lo stream internet di una stazione DAB+, cosi il passaggio a internet funziona quando la ricezione cede.

RICEZIONE CHE NON SI INTERROMPE
Se il DAB+ si indebolisce, Klarwelle passa da sola: prima allo stesso programma in un altro ensemble, poi a FM o internet – nell'ordine che scegli – e torna indietro appena il DAB+ ritorna.

E INOLTRE
• DAB+ tramite ricevitore USB, scansione degli ensemble, testo DLS e slideshow
• Radio via internet con ricerca nel database aperto radio-browser, stream personalizzati
• Sei preselezioni per banda, tasti al volante e multimediali, mini-player sopra le altre app
• Interfaccia pulita nello stile delle autoradio di serie, modalita giorno e notte, otto lingue

DA SAPERE
• DAB+ e allerte richiedono un ricevitore DAB USB. Senza, Klarwelle e una radio via internet.
• FM/AM funziona solo su unita supportate (Microntek/HCT).
• ASA e in fase di introduzione; oggi sono in onda trasmissioni di prova. Klarwelle e un canale aggiuntivo e non sostituisce i mezzi di allerta ufficiali. Klarwelle non e certificata ASA.
• Nessun account, nessuna pubblicita, nessun tracciamento. La tua posizione non lascia il dispositivo.

Klarwelle e software libero (GPL-3.0). Codice: github.com/ThxGiving/klarwelle
DAB+ e il logo ASA sono marchi di WorldDAB.''')
T['nl']=('Klarwelle: DAB+ autoradio',"DAB+ via USB-stick, officiele waarschuwingen (ASA), internetradio, echte logo's.",'''Klarwelle maakt van een Android-head-unit een radio die niet wegvalt.

WAARSCHUWINGEN, OOK ALS U INTERNETRADIO LUISTERT
Klarwelle ondersteunt ASA (Automatic Safety Alert, ETSI TS 104 089). Waarschuwingen komen via DAB+ en hebben geen mobiel netwerk nodig. Ze worden gefilterd op uw locatie: via gps dat met het voertuig meebeweegt, of voor vaste plekken zoals thuis, waarvan u de code invoert of via plaatszoeken laat berekenen. De DAB+-ontvanger bewaakt het waarschuwingsensemble ook terwijl u iets anders luistert.

ZENDERS ZOALS DE OMROEP ZE BEDOELT
Via RadioDNS haalt Klarwelle de officiele logo's rechtstreeks bij de omroep, toont live titels en beelden bij het programma en vindt de internetstream van een DAB+-zender, zodat de overstap naar internet werkt als de ontvangst wegvalt.

ONTVANGST DIE NIET WEGVALT
Wordt DAB+ te zwak, dan schakelt Klarwelle zelf: eerst naar hetzelfde programma in een ander ensemble, dan naar FM of internet – in de volgorde die u kiest – en terug zodra DAB+ er weer is.

VERDER
• DAB+ via USB-ontvanger, ensemble-scan, DLS-tekst en slideshow
• Internetradio met zoeken in de open radio-browser-database, eigen streams
• Zes voorkeuzes per band, stuurwiel- en mediatoetsen, mini-player boven andere apps
• Opgeruimde interface in de stijl van moderne fabrieksradio's, dag- en nachtmodus, acht talen

GOED OM TE WETEN
• DAB+ en waarschuwingen vereisen een USB-DAB-ontvanger. Zonder is Klarwelle een internetradio.
• FM/AM werkt alleen op ondersteunde head-units (Microntek/HCT).
• ASA wordt nog uitgerold; er zijn nu testuitzendingen. Klarwelle is een extra kanaal en vervangt geen officiele waarschuwingsmiddelen. Klarwelle is niet ASA-gecertificeerd.
• Geen account, geen reclame, geen tracking. Uw locatie verlaat het apparaat niet.

Klarwelle is vrije software (GPL-3.0). Broncode: github.com/ThxGiving/klarwelle
DAB+ en het ASA-logo zijn merken van WorldDAB.''')
T['pl']=('Klarwelle: radio DAB+ do auta','DAB+ przez USB, oficjalne ostrzezenia (ASA), radio internetowe, prawdziwe logo.','''Klarwelle zamienia stacje Android w radio, ktore nie traci sygnalu.

OSTRZEZENIA, NAWET GDY SLUCHASZ RADIA INTERNETOWEGO
Klarwelle obsluguje ASA (Automatic Safety Alert, ETSI TS 104 089). Ostrzezenia przychodza przez DAB+ i nie wymagaja sieci komorkowej. Sa filtrowane wedlug Twojej lokalizacji: przez GPS podazajacy za pojazdem lub dla stalych miejsc, np. domu, ktorych kod wpisujesz albo obliczasz przez wyszukiwanie miejsca. Odbiornik DAB+ pilnuje multipleksu ostrzegawczego takze wtedy, gdy sluchasz czegos innego.

STACJE TAK, JAK CHCE NADAWCA
Przez RadioDNS Klarwelle pobiera oficjalne logo bezposrednio od nadawcy, pokazuje tytuly i obrazy na zywo i znajduje strumien internetowy stacji DAB+, aby przelaczenie na internet dzialalo, gdy odbior zanika.

ODBIOR, KTORY NIE ZANIKA
Gdy DAB+ slabnie, Klarwelle przelacza sie sama: najpierw na ten sam program w innym multipleksie, potem na FM lub internet – w wybranej przez Ciebie kolejnosci – i wraca, gdy tylko DAB+ powroci.

PONADTO
• DAB+ przez odbiornik USB, skanowanie multipleksow, tekst DLS i pokaz slajdow
• Radio internetowe z wyszukiwaniem w otwartej bazie radio-browser, wlasne strumienie
• Szesc przyciskow pamieci na pasmo, przyciski na kierownicy i multimedialne, mini-odtwarzacz nad innymi aplikacjami
• Przejrzysty interfejs w stylu fabrycznych radii, tryb dzienny i nocny, osiem jezykow

WARTO WIEDZIEC
• DAB+ i ostrzezenia wymagaja odbiornika DAB na USB. Bez niego Klarwelle jest radiem internetowym.
• FM/AM dziala tylko na obslugiwanych stacjach (Microntek/HCT).
• ASA jest w fazie wdrazania; obecnie trwaja transmisje testowe. Klarwelle to dodatkowy kanal i nie zastepuje oficjalnych srodkow ostrzegania. Klarwelle nie ma certyfikatu ASA.
• Bez konta, reklam i sledzenia. Twoja lokalizacja nie opuszcza urzadzenia.

Klarwelle to wolne oprogramowanie (GPL-3.0). Kod: github.com/ThxGiving/klarwelle
DAB+ i logo ASA sa znakami towarowymi WorldDAB.''')
T['ka']=('Klarwelle: DAB+ ავტორადიო','DAB+ USB-ით, ოფიციალური გაფრთხილებები (ASA), ინტერნეტრადიო, ნამდვილი ლოგოები.','''Klarwelle Android-ის სათავო მოწყობილობას რადიოდ აქცევს, რომელიც არ წყდება.

გაფრთხილებები, ინტერნეტრადიოს მოსმენისასაც კი
Klarwelle მხარს უჭერს ASA-ს (Automatic Safety Alert, ETSI TS 104 089). გაფრთხილებები DAB+-ით მოდის და მობილურ ქსელს არ საჭიროებს. ისინი თქვენი ადგილმდებარეობის მიხედვით იფილტრება: GPS-ით, რომელიც ავტომობილს მიჰყვება, ან ფიქსირებული ადგილებისთვის, მაგალითად სახლისთვის, რომლის კოდსაც შეიყვანთ ან ადგილის ძებნით გამოთვლით. DAB+ მიმღები გაფრთხილების ანსამბლს მაშინაც აკვირდება, როცა სხვა რამეს უსმენთ.

სადგურები ისე, როგორც მაუწყებელს სურს
RadioDNS-ით Klarwelle ოფიციალურ ლოგოებს პირდაპირ მაუწყებლისგან იღებს, აჩვენებს ცოცხალ სათაურებსა და სურათებს და პოულობს DAB+ სადგურის ინტერნეტ-ნაკადს — რომ მიღების გაწყვეტისას ინტერნეტზე გადართვა იმუშაოს.

მიღება, რომელიც არ წყდება
როცა DAB+ სუსტდება, Klarwelle თავად გადაერთვება: ჯერ იმავე პროგრამაზე სხვა ანსამბლში, შემდეგ FM-ზე ან ინტერნეტზე — თქვენ მიერ არჩეული თანმიმდევრობით — და უკან ბრუნდება, როგორც კი DAB+ დაბრუნდება.

ასევე
• DAB+ USB-მიმღებით, ანსამბლების სკანირება, DLS ტექსტი და სლაიდშოუ
• ინტერნეტრადიო ღია radio-browser ბაზაში ძებნით, საკუთარი ნაკადები
• ექვსი წინასწარი არჩევანი ზოლზე, საჭისა და მედია ღილაკები, მინი-პლეერი სხვა აპებზე
• სუფთა ინტერფეისი თანამედროვე ქარხნული რადიოების სტილში, დღისა და ღამის რეჟიმი, რვა ენა

გასათვალისწინებელი
• DAB+ და გაფრთხილებები USB DAB-მიმღებს საჭიროებს. მის გარეშე Klarwelle ინტერნეტრადიოა.
• FM/AM მხოლოდ მხარდაჭერილ მოწყობილობებზე მუშაობს (Microntek/HCT).
• ASA ჯერ კიდევ ინერგება; ამჟამად სატესტო გადაცემები მიმდინარეობს. Klarwelle დამატებითი არხია და ოფიციალურ გაფრთხილების საშუალებებს არ ცვლის. Klarwelle ASA-სერტიფიცირებული არ არის.
• არც ანგარიში, არც რეკლამა, არც თვალთვალი. თქვენი ადგილმდებარეობა მოწყობილობას არ ტოვებს.

Klarwelle თავისუფალი პროგრამაა (GPL-3.0). წყარო: github.com/ThxGiving/klarwelle
DAB+ და ASA ლოგო WorldDAB-ის სავაჭრო ნიშნებია.''')
for lang,(title,short,full) in T.items():
    assert len(title)<=30,(lang,'title',len(title)); assert len(short)<=80,(lang,'short',len(short)); assert len(full)<=4000,(lang,'full',len(full))
    with open(os.path.join(D,f'{lang}.md'),'w',encoding='utf-8') as f:
        f.write(f"# Store-Eintrag {lang}\n\n## Titel ({len(title)}/30)\n{title}\n\n## Kurzbeschreibung ({len(short)}/80)\n{short}\n\n## Beschreibung ({len(full)}/4000)\n{full}\n")
print('ok:', ', '.join(T))
