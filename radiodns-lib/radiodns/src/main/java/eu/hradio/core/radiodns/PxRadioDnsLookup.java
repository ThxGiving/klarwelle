package eu.hradio.core.radiodns;

import android.content.Context;
import android.util.Log;

import org.minidns.DnsClient;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties;
import org.minidns.hla.ResolverApi;
import org.minidns.hla.ResolverResult;
import org.minidns.record.CNAME;
import org.minidns.record.Data;
import org.minidns.record.Record;
import org.minidns.record.SRV;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import eu.hradio.core.radiodns.radioepg.bearer.Bearer;
import eu.hradio.core.radiodns.radioepg.mediadescription.MediaDescription;
import eu.hradio.core.radiodns.radioepg.multimedia.Multimedia;
import eu.hradio.core.radiodns.radioepg.serviceinformation.Service;

/**
 * Clean, synchronous entry point for our app: resolve a broadcast station's RadioDNS identity to
 * its official Service Information (logos + IP simulcast bearers). Unlike {@link RadioDnsCore}
 * (which needs an omri {@code RadioService} object and only builds DAB FQDNs), this takes raw
 * identity parameters and also constructs FM FQDNs — so we can drive it from our own {@code Station}
 * model for both bands.
 *
 * Blocking (DNS + HTTP) — call off the main thread. Returns a best-effort {@link Result}; any
 * failure (no record, network down, malformed SI) yields an empty result rather than throwing.
 *
 * A single lookup returns EVERY service the SI document describes (a whole ensemble/provider),
 * each tagged with its DAB SId, so the caller can map logos+streams onto its station list in one
 * shot. See the validated Deutschlandfunk example in the project memory.
 */
public final class PxRadioDnsLookup {

	private final static String TAG = "PxRadioDnsLookup";
	private final static int MAX_REDIRECTS = 5;

	private PxRadioDnsLookup() {}

	/** One image variant for a service (RadioDNS {@code <multimedia>} logo entry). */
	public static final class Logo {
		public final String url;
		public final int width;
		public final int height;
		Logo(String url, int width, int height) { this.url = url; this.width = width; this.height = height; }
	}

	/** One IP audio simulcast for a service (RadioDNS http(s) {@code <bearer>}). */
	public static final class Stream {
		public final String url;
		public final int bitrate;   // kbit/s, 0 if unknown
		public final int cost;      // RadioDNS bearer cost (lower = preferred), -1 if unknown
		public final String mime;
		Stream(String url, int bitrate, int cost, String mime) {
			this.url = url; this.bitrate = bitrate; this.cost = cost; this.mime = mime;
		}
	}

	/** All artwork + streams for one service, keyed by its DAB SId (-1 when not a DAB bearer). */
	public static final class ServiceEntry {
		public final int sid;                 // DAB Service Id parsed from the dab: bearer, else -1
		public final int fmPi;                // RDS PI parsed from an fm: bearer, else -1 — so an FM
		                                      // lookup can pick THIS station, not the provider's first
		                                      // (e.g. WDR 2 vs. 1LIVE, which share the WDR SI).
		public final List<Logo> logos;
		public final List<Stream> streams;
		ServiceEntry(int sid, int fmPi, List<Logo> logos, List<Stream> streams) {
			this.sid = sid; this.fmPi = fmPi; this.logos = logos; this.streams = streams;
		}
	}

	public static final class Result {
		public final List<ServiceEntry> services;
		Result(List<ServiceEntry> services) { this.services = services; }
		public boolean isEmpty() { return services.isEmpty(); }
	}

	private static final Result EMPTY = new Result(new ArrayList<ServiceEntry>());

	/**
	 * DAB lookup. FQDN per ETSI TS 103 270 / omri {@link RadioDnsCore}:
	 * {@code 0.<sid>.<eid>.<gcc>.dab.radiodns.org}, where the gcc is <b>derived, not hardcoded</b>:
	 * the SId's country nibble + the ensemble ECC. So it resolves in any country (DE public "de0",
	 * DE commercial "1e0", NL, …) automatically.
	 *
	 * @param ecc Extended Country Code (FIG 0/9) from omri; 0xE0 (Germany) is the fallback when unknown.
	 */
	public static Result lookupDab(Context ctx, int eid, int sid, int ecc) {
		String sidHex = Integer.toHexString(sid);
		int e = (ecc >= 0 && ecc <= 0xFE) ? ecc : 0xE0;
		String gcc = sidHex.charAt(0) + Integer.toHexString(e);
		return resolve(ctx, "0." + sidHex + "." + Integer.toHexString(eid) + "." + gcc + ".dab.radiodns.org");
	}

	/**
	 * FM lookup (the branch omri never implemented). FQDN:
	 * {@code <freq5>.<pi>.<gcc>.fm.radiodns.org} where freq is the frequency in 100 kHz units,
	 * zero-padded to 5 digits (98.8 MHz -> "09880").
	 */
	public static Result lookupFm(Context ctx, int piCode, int freqKhz, String gcc) {
		// Frequency in units of 10 kHz, 5 digits: 87.8 MHz = 87800 kHz → 8780 → "08780" (verified live:
		// 08780.d392.de0.fm.radiodns.org). Was /100 (→ "00878"), which didn't resolve.
		String freq5 = String.format("%05d", freqKhz / 10);
		String fqdn = freq5 + "." + Integer.toHexString(piCode) + "." + gcc + ".fm.radiodns.org";
		return resolve(ctx, fqdn);
	}

	/** A resolved RadioVIS (visualisation) STOMP endpoint for a bearer, plus its topic base. */
	public static final class VisEndpoint {
		public final String host;
		public final int port;
		public final String topicBase;   // "/topic/<bearer>", append "/text" | "/image"
		VisEndpoint(String host, int port, String topicBase) {
			this.host = host; this.port = port; this.topicBase = topicBase;
		}
	}

	/**
	 * Resolve a station's <b>RadioVIS</b> (now-playing text + slideshow) endpoint from its RadioDNS
	 * bearer — the general, broadcaster-independent path for IP streams with no in-band ICY metadata
	 * (e.g. BBC HLS). Same CNAME→SRV chain as the logo lookup, but for the {@code _radiovis._tcp}
	 * application instead of {@code _radioepg._tcp}.
	 *
	 * @param bearer slash-form bearer, {@code fm/<gcc>/<pi>/<freq5>} or {@code dab/<gcc>/<eid>/<sid>/<scids>}
	 *               — e.g. BBC Radio 1 {@code fm/ce1/c201/09880}. This IS the RadioVIS topic path body.
	 * @return endpoint (host, port 61613, topic base) or null if the broadcaster runs no RadioVIS.
	 */
	public static VisEndpoint lookupVis(Context ctx, String bearer) {
		String fqdn = visFqdn(bearer);
		if (fqdn == null) return null;
		StringBuilder d = new StringBuilder("vis ").append(fqdn).append(' ');
		try {
			runCatching(() -> AndroidUsingLinkProperties.setup(ctx));
			String authority = resolveCname(fqdn, d);
			if (authority == null) { lastDiag = d.append("cname=EMPTY").toString(); return null; }
			SRV srv = resolveSrv("_radiovis._tcp." + authority, d);
			if (srv == null) { lastDiag = d.append("srv=EMPTY").toString(); return null; }
			lastDiag = d.append("ok ").append(srv.target).append(':').append(srv.port).toString();
			return new VisEndpoint(stripDot(srv.target.toString()), srv.port, "/topic/" + bearer);
		} catch (Exception e) {
			lastDiag = d.append("EXC=").append(e.getMessage()).toString();
			return null;
		}
	}

	/** One service harvested from a broadcaster's SI: its names (for matching to our catalog) and the
	 *  best broadcast bearer (fm preferred over dab) in slash form for a RadioVIS subscription. */
	public static final class HarvestedService {
		public final List<String> names;
		public final String bearer;   // slash form, e.g. "dab/ce1/ce15/c22a/0"
		public final String logoUrl;  // best official logo from the SI, or null
		HarvestedService(List<String> names, String bearer, String logoUrl) {
			this.names = names; this.bearer = bearer; this.logoUrl = logoUrl;
		}
	}

	/**
	 * From ONE known bearer of a broadcaster, fetch its RadioDNS SI document and return every service
	 * it lists with a broadcast bearer — so a single seeded station (e.g. BBC Radio 1) unlocks RadioVIS
	 * now-playing for its whole network (Radio 2, 1Xtra, 6 Music, …) by name match, no per-station
	 * hand-seeding. The SI lists each service's fm/dab bearers; we convert the best one to slash form.
	 */
	public static List<HarvestedService> harvestBearers(Context ctx, String seedBearer) {
		List<HarvestedService> out = new ArrayList<>();
		String fqdn = visFqdn(seedBearer);
		if (fqdn == null) return out;
		StringBuilder d = new StringBuilder("harvest ").append(fqdn).append(' ');
		try {
			runCatching(() -> AndroidUsingLinkProperties.setup(ctx));
			String authority = resolveCname(fqdn, d);
			if (authority == null) { lastDiag = d.append("cname=EMPTY").toString(); return out; }
			SRV srv = resolveSrv("_radioepg._tcp." + authority, d);
			if (srv == null) { lastDiag = d.append("srv=EMPTY").toString(); return out; }
			String host = stripDot(srv.target.toString());
			int port = srv.port;
			String siUrl = "http://" + host + (port != 80 ? ":" + port : "") + "/radiodns/spi/3.1/SI.xml";
			InputStream in = openFollowingRedirects(siUrl);
			if (in == null) { lastDiag = d.append("si=HTTP-FAIL").toString(); return out; }
			RadioEpgServiceInformation si;
			try { si = new RadioEpgSiParser().parse(in); }
			finally { try { in.close(); } catch (Exception ignore) {} }
			if (si == null || si.getServices() == null) { lastDiag = d.append("si=EMPTY").toString(); return out; }
			for (Service svc : si.getServices()) {
				String bearer = bestBroadcastBearer(svc);
				if (bearer == null) continue;
				List<String> names = new ArrayList<>();
				if (svc.getNames() != null) {
					for (eu.hradio.core.radiodns.radioepg.name.Name n : svc.getNames()) {
						if (n.getName() != null && !n.getName().isEmpty()) names.add(n.getName());
					}
				}
				if (!names.isEmpty()) out.add(new HarvestedService(names, bearer, bestLogo(svc)));
			}
			lastDiag = d.append("services=").append(out.size()).toString();
		} catch (Exception e) {
			lastDiag = d.append("EXC=").append(e.getMessage()).toString();
		}
		return out;
	}

	/** The largest logo URL declared for a service in the SI (official per-station artwork). */
	private static String bestLogo(Service svc) {
		if (svc.getMediaDescriptions() == null) return null;
		String best = null; int bestW = -1;
		for (MediaDescription md : svc.getMediaDescriptions()) {
			Multimedia mm = md.getMultimedia();
			if (mm == null || mm.getUrl() == null || mm.getUrl().isEmpty()) continue;
			if (mm.getWidth() > bestW) { bestW = mm.getWidth(); best = mm.getUrl(); }
		}
		return best;
	}

	/** Pick a service's best RadioVIS-usable bearer: FM if present (cleanest topic), else DAB. Both
	 *  are converted from the SI {@code scheme:a.b.c} form to the {@code scheme/a/b/c} topic form. */
	private static String bestBroadcastBearer(Service svc) {
		if (svc.getBearers() == null) return null;
		String dab = null;
		for (Bearer b : svc.getBearers()) {
			String id = b.getBearerIdString();
			if (id == null) continue;
			if (id.startsWith("fm:")) return "fm/" + id.substring(3).replace('.', '/');
			if (id.startsWith("dab:") && dab == null) dab = "dab/" + id.substring(4).replace('.', '/');
		}
		return dab;
	}

	/** Build the RadioDNS CNAME FQDN from a slash-form bearer (reverse-dotted, per ETSI TS 103 270). */
	private static String visFqdn(String bearer) {
		if (bearer == null) return null;
		String[] p = bearer.split("/");
		if (p.length >= 4 && p[0].equals("fm")) {          // fm/gcc/pi/freq5
			return p[3] + "." + p[2] + "." + p[1] + ".fm.radiodns.org";
		}
		if (p.length >= 5 && p[0].equals("dab")) {         // dab/gcc/eid/sid/scids
			return p[4] + "." + p[3] + "." + p[2] + "." + p[1] + ".dab.radiodns.org";
		}
		return null;
	}

	/** Public DNS servers tried when the head unit's own resolver returns nothing — car head units
	 *  often don't expose their DNS servers to minidns (not a filter, just no server discovered). */
	private static final String[] FALLBACK_DNS = {"8.8.8.8", "1.1.1.1"};

	/** Human-readable trace of the last lookup (which step succeeded/failed), for the diagnostics file. */
	public static volatile String lastDiag = "";

	private static Result resolve(Context ctx, String fqdn) {
		StringBuilder d = new StringBuilder(fqdn).append(" ");
		try {
			runCatching(() -> AndroidUsingLinkProperties.setup(ctx));

			// 1) CNAME to the broadcaster's authoritative RadioDNS host (system resolver, then public).
			String authority = resolveCname(fqdn, d);
			if (authority == null) { lastDiag = d.append("cname=EMPTY").toString(); return EMPTY; }

			// 2) SRV for the RadioEPG (SPI) application under that authority.
			SRV srv = resolveSrv("_radioepg._tcp." + authority, d);
			if (srv == null) { lastDiag = d.append("srv=EMPTY").toString(); return EMPTY; }
			String host = stripDot(srv.target.toString());
			int port = srv.port;

			// 3) Fetch + parse the SI document.
			String siUrl = "http://" + host + (port != 80 ? ":" + port : "") + "/radiodns/spi/3.1/SI.xml";
			InputStream in = openFollowingRedirects(siUrl);
			if (in == null) { lastDiag = d.append("si=HTTP-FAIL").toString(); return EMPTY; }

			RadioEpgServiceInformation si;
			try {
				si = new RadioEpgSiParser().parse(in);
			} finally {
				try { in.close(); } catch (Exception ignore) {}
			}
			if (si == null || si.getServices() == null) { lastDiag = d.append("si=PARSE-EMPTY").toString(); return EMPTY; }

			Result r = mapServices(si);
			lastDiag = d.append("services=").append(r.services.size()).toString();
			return r;
		} catch (Exception e) {
			lastDiag = d.append("EXC=").append(e.getMessage()).toString();
			Log.w(TAG, "RadioDNS lookup failed for " + fqdn + ": " + e.getMessage());
			return EMPTY;
		}
	}

	/** CNAME target via the system resolver, then public DNS. Null if none resolves. */
	private static String resolveCname(String fqdn, StringBuilder d) {
		try {
			ResolverResult<CNAME> r = ResolverApi.INSTANCE.resolve(fqdn, CNAME.class);
			if (r.wasSuccessful() && !r.getAnswers().isEmpty()) {
				d.append("cname=sys ");
				return r.getAnswers().iterator().next().target.toString();
			}
		} catch (Exception ignore) {}
		for (String server : FALLBACK_DNS) {
			try {
				DnsMessage resp = new DnsClient().query(
					DnsMessage.builder().addQuestion(new Question(fqdn, Record.TYPE.CNAME))
						.setRecursionDesired(true).build(),
					InetAddress.getByName(server), 53).response;
				for (Record<? extends Data> rec : resp.answerSection) {
					if (rec.type == Record.TYPE.CNAME) {
						d.append("cname=").append(server).append(' ');
						return ((CNAME) rec.getPayload()).target.toString();
					}
				}
			} catch (Exception ignore) {}
		}
		return null;
	}

	/** SRV record via the system resolver, then public DNS. Null if none resolves. */
	private static SRV resolveSrv(String name, StringBuilder d) {
		try {
			ResolverResult<SRV> r = ResolverApi.INSTANCE.resolve(name, SRV.class);
			if (r.wasSuccessful() && !r.getAnswers().isEmpty()) { d.append("srv=sys "); return r.getAnswers().iterator().next(); }
		} catch (Exception ignore) {}
		for (String server : FALLBACK_DNS) {
			try {
				DnsMessage resp = new DnsClient().query(
					DnsMessage.builder().addQuestion(new Question(name, Record.TYPE.SRV))
						.setRecursionDesired(true).build(),
					InetAddress.getByName(server), 53).response;
				for (Record<? extends Data> rec : resp.answerSection) {
					if (rec.type == Record.TYPE.SRV) { d.append("srv=").append(server).append(' '); return (SRV) rec.getPayload(); }
				}
			} catch (Exception ignore) {}
		}
		return null;
	}

	private interface ThrowingRunnable { void run() throws Exception; }
	private static void runCatching(ThrowingRunnable r) { try { r.run(); } catch (Exception ignore) {} }

	/**
	 * Test/diagnostic entry point: parse an already-fetched SI document (no DNS, no HTTP) into the
	 * same {@link Result} the live lookup produces. Lets the parse+map pipeline be unit-tested
	 * against a captured SI.xml (the "RadioDNS test set").
	 */
	public static Result parseSi(InputStream in) throws java.io.IOException {
		RadioEpgServiceInformation si = new RadioEpgSiParser().parse(in);
		if (si == null || si.getServices() == null) return EMPTY;
		return mapServices(si);
	}

	private static Result mapServices(RadioEpgServiceInformation si) {
		List<ServiceEntry> entries = new ArrayList<>();
		for (Service svc : si.getServices()) {
			int sid = -1;
			int fmPi = -1;
			List<Stream> streams = new ArrayList<>();
			if (svc.getBearers() != null) {
				for (Bearer b : svc.getBearers()) {
					String id = b.getBearerIdString();
					if (id == null) continue;
					if (id.startsWith("dab:")) {
						int parsed = sidFromDabBearer(id);
						if (parsed >= 0) sid = parsed;
					} else if (id.startsWith("fm:")) {
						int parsed = piFromFmBearer(id);   // fm:<gcc>.<pi>.<freq> -> the RDS PI
						if (parsed >= 0) fmPi = parsed;
					} else if (id.startsWith("http://") || id.startsWith("https://")) {
						streams.add(new Stream(id, b.getBitrate(), b.getCost(), b.getMimeType()));
					}
				}
			}

			List<Logo> logos = new ArrayList<>();
			if (svc.getMediaDescriptions() != null) {
				for (MediaDescription md : svc.getMediaDescriptions()) {
					Multimedia mm = md.getMultimedia();
					if (mm != null && mm.getUrl() != null && !mm.getUrl().isEmpty()) {
						logos.add(new Logo(mm.getUrl(), mm.getWidth(), mm.getHeight()));
					}
				}
			}

			if (sid >= 0 || fmPi >= 0 || !logos.isEmpty() || !streams.isEmpty()) {
				entries.add(new ServiceEntry(sid, fmPi, logos, streams));
			}
		}
		return new Result(entries);
	}

	/** Parse the RDS PI out of an {@code fm:<gcc>.<pi>.<freq>} bearer id (e.g. {@code fm:de0.d392.*}). */
	private static int piFromFmBearer(String bearerId) {
		try {
			String[] parts = bearerId.substring("fm:".length()).split("\\.");
			if (parts.length >= 2) return Integer.parseInt(parts[1], 16);
		} catch (Exception ignore) {}
		return -1;
	}

	/** Parse the SId out of a {@code dab:<gcc>.<eid>.<sid>.<scids>} bearer id. */
	private static int sidFromDabBearer(String bearerId) {
		try {
			String body = bearerId.substring("dab:".length());
			String[] parts = body.split("\\.");
			if (parts.length >= 3) {
				return Integer.parseInt(parts[2], 16);
			}
		} catch (Exception ignore) {}
		return -1;
	}

	private static String stripDot(String s) {
		return (s != null && s.endsWith(".")) ? s.substring(0, s.length() - 1) : s;
	}

	/**
	 * HttpURLConnection follows http->http redirects but NOT http->https; RadioDNS SPI hosts often
	 * bounce to https, so follow manually.
	 */
	private static InputStream openFollowingRedirects(String url) throws Exception {
		String current = url;
		for (int i = 0; i < MAX_REDIRECTS; i++) {
			HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
			conn.setConnectTimeout(15000);
			conn.setReadTimeout(15000);
			conn.setRequestMethod("GET");
			conn.setInstanceFollowRedirects(false);
			conn.connect();
			int code = conn.getResponseCode();
			if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
					|| code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
				String loc = conn.getHeaderField("Location");
				conn.disconnect();
				if (loc == null || loc.isEmpty()) return null;
				current = loc;
				continue;
			}
			if (code == HttpURLConnection.HTTP_OK) {
				return conn.getInputStream();
			}
			conn.disconnect();
			return null;
		}
		return null;
	}
}
