# Privacy Policy – Klarwelle

Last updated: 14 September 2026

**Controller:** Sebastian Müller · sebastian@masesoftware.de

Klarwelle is a radio app for Android head units and tablets: DAB+ via a USB receiver, internet
radio, official emergency alerts (ASA) and – on supported units – FM/AM.

## In short

- **No account**, no sign-up, no ads, no analytics or tracking.
- The app **sends nothing to the developer.** There is no server of ours.
- **Your location never leaves the device.** It is converted locally into a DAB location code to
  filter alerts for your area.

## Permissions

| Permission | Purpose | Required? |
|---|---|---|
| Internet, network state | Internet radio, station logos, station search, place search | For internet features |
| Location (fine/coarse) | Only for "follow the vehicle by GPS": alerts for where the car is right now | No – fully usable without |
| Draw over other apps | The floating mini-player while another app is in front | No – only if you enable it |
| USB access | The DAB+ receiver | For DAB+ |

## Where data goes

Each service below technically receives your IP address; beyond that, only what is listed.

| Service | When | What is sent |
|---|---|---|
| **Broadcasters' stream servers** | Listening to an internet station, or falling back to the internet | The stream request |
| **radio-browser.info** (open community database) | Station search; looking up the internet address of a DAB+/FM station for the fallback | Your search term or the station name |
| **RadioDNS / RadioVIS** (broadcaster infrastructure via radiodns.org) | When RadioDNS is on: logos, live titles, images, internet addresses | Identifiers of the received station (frequency, PI, ensemble) |
| **Media Broadcast** | Only when you trigger the DAB logo download | A download, no user data |
| **OpenStreetMap Nominatim** | Only when you search for a place in settings to compute a location code | The place name you typed |
| **api.ipify.org** | Only when you explicitly tap "look up public IP" in the info panel | One request; the reply is your public IP |

Place search may additionally use the Android system geocoder, whose privacy terms are those of
your device vendor (e.g. Google).

## Location in detail

With "follow the vehicle by GPS" enabled, the app reads the vehicle position, converts it **on the
device** into a DAB location code (ETSI TS 104 089 annex F – cells of roughly one kilometre) and
matches incoming alerts against it. Only the current code is kept, not the position; nothing is
transmitted. Manually entered location codes are stored locally in settings.

## Data stored locally

Settings, station list, presets, logos and location codes live in the app's private storage. **Only if you switch it on** (Settings › System › "Write diagnostics files", off by default), the
app writes diagnostic files (`klarwelle-*.txt`) to its own directory on the device and – if plugged
in – on a USB stick. They contain technical events (station changes, reception, alerts, derived
location codes) and are **never uploaded automatically**. "Delete diagnostics files" removes them at
any time; uninstalling removes them too. Independently of this setting, a crash stores a technical
error description (stack trace, no user data) locally.

## Emergency alerts (ASA)

Alerts are received over DAB+ and need no network. Klarwelle is an **additional** channel and does
not replace official warning means (cell broadcast, warning apps, sirens). Klarwelle is not ASA
certified.

## Your rights

Since no personal data is collected by the controller, there is nothing to access or erase there.
Questions: sebastian@masesoftware.de. You may complain to a data protection authority.

## Changes

Changes appear here with a new date.
