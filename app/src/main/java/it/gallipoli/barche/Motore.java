package it.gallipoli.barche;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Versione Java di vesselfinder_tv.py: legge le barche da VesselFinder e MarineTraffic,
 * scarica schede e foto, calcola le barche in arrivo e prepara i dati per la pagina.
 */
public class Motore {

    // ------------------------------------------------------------------ CONFIGURAZIONE
    static final double LAT_NORD = 40.0694, LAT_SUD = 39.9942;
    static final double LON_OVEST = 17.8662, LON_EST = 18.0247;
    static final String ALLARGA_VERSO = "ovest";
    static final double ASPECT = 16.0 / 9.0;

    static final int POLL_POSIZIONI_S = 60;
    static final int CACHE_SCHEDE_ORE = 6;
    static final int AGGIORNA_SCHEDE_MIN = 10;
    static final int SBIADISCI_DOPO_MIN = 120;
    static final long PAUSA_TRA_RICHIESTE_MS = 800;
    static final int STORICO_ORE = 24;
    static final boolean USA_MARINETRAFFIC = true;
    static final int POLL_MARINETRAFFIC_S = 180;
    static final boolean ARRIVI = true;
    static final double ARRIVI_RAGGIO_NM = 20;
    static final double ARRIVI_VEL_MIN = 1.5;
    static final double ARRIVI_CONO_GRADI = 10;
    static final double ARRIVI_MAX_ORE = 4;
    static final double ARRIVI_POS_MAX_MIN = 30;
    static final int ARRIVI_CONFERME = 2;

    static final String VF = "https://www.vesselfinder.com";
    static final String FOTO_BASE = "https://static.vesselfinder.net/ship-photo/";
    static final String MT = "https://www.marinetraffic.com";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    static final double M_PER_DEG_LAT = 111320.0;

    /** Per leggere MarineTraffic con un browser (WebView nascosta) se blocca le richieste semplici. */
    public interface BrowserMT {
        String fetch(String path) throws Exception;
        byte[] fetchByte(String url) throws Exception;
    }

    // ------------------------------------------------------------------ AREA
    public static class Area {
        double nord, sud, ovest, est, latC, lonC, widthM, heightM;

        Area copia() {
            Area a = new Area();
            a.nord = nord; a.sud = sud; a.ovest = ovest; a.est = est;
            a.latC = latC; a.lonC = lonC; a.widthM = widthM; a.heightM = heightM;
            return a;
        }
    }

    static Area area169(double n, double s, double w, double e) {
        double latC0 = (n + s) / 2;
        double cos = Math.cos(Math.toRadians(latC0));
        double widthM = (e - w) * M_PER_DEG_LAT * cos;
        double heightM = (n - s) * M_PER_DEG_LAT;
        if (widthM / heightM < ASPECT) {
            double extra = (heightM * ASPECT - widthM) / (M_PER_DEG_LAT * cos);
            if ("ovest".equals(ALLARGA_VERSO)) w -= extra;
            else if ("est".equals(ALLARGA_VERSO)) e += extra;
            else { w -= extra / 2; e += extra / 2; }
            widthM = heightM * ASPECT;
        } else {
            double extra = (widthM / ASPECT - heightM) / M_PER_DEG_LAT;
            n += extra / 2; s -= extra / 2;
            heightM = widthM / ASPECT;
        }
        Area a = new Area();
        a.nord = n; a.sud = s; a.ovest = w; a.est = e;
        a.latC = (n + s) / 2; a.lonC = (w + e) / 2; a.widthM = widthM; a.heightM = heightM;
        return a;
    }

    static Area areaAllargata(Area area, double nm) {
        Area a = area.copia();
        double dlat = nm / 60, dlon = nm / (60 * Math.cos(Math.toRadians(area.latC)));
        a.nord += dlat; a.sud -= dlat; a.ovest -= dlon; a.est += dlon;
        return a;
    }

    // ------------------------------------------------------------------ STATO
    final Area area = area169(LAT_NORD, LAT_SUD, LON_OVEST, LON_EST);
    private final File cacheDir, fotoDir;
    private final BrowserMT browserMT;
    private volatile boolean fermo = false;
    private final Object attesa = new Object();
    private Thread thread;

    private final Object lock = new Object();
    private Map<Long, JSONObject> barche = new HashMap<>();
    private Map<Long, JSONObject> schede = new HashMap<>();
    private Map<Long, JSONObject> storico = new HashMap<>();
    private Map<String, JSONObject> mt = new HashMap<>();
    private final Map<String, double[]> fotoMt = new HashMap<>();     // id -> {t, ar (NaN = nessuna)}
    private List<JSONObject> arrivi = new ArrayList<>();
    private final Map<String, Integer> conferme = new HashMap<>();
    private Map<String, JSONObject> mtFuori = new HashMap<>();
    private String aggiornato = null, errore = null;
    private String modoMt = "python";

    public Motore(File cacheBase, BrowserMT browserMT) {
        this.cacheDir = new File(cacheBase, "barche");
        this.fotoDir = new File(cacheDir, "foto");
        fotoDir.mkdirs();
        this.browserMT = browserMT;
        caricaCache();
    }

    public void avvia() {
        if (thread != null) return;
        fermo = false;
        thread = new Thread(new Runnable() {
            @Override public void run() { worker(); }
        }, "barche");
        thread.setDaemon(true);
        thread.start();
    }

    public void ferma() {
        fermo = true;
        synchronized (attesa) { attesa.notifyAll(); }
    }

    /** Aspetta ms millisecondi; restituisce true se nel frattempo si e' chiesto di fermarsi. */
    private boolean aspetta(long ms) {
        synchronized (attesa) {
            if (!fermo) {
                try { attesa.wait(ms); } catch (InterruptedException ignored) { }
            }
        }
        return fermo;
    }

    private static void log(String s) {
        android.util.Log.i("Barche", s);
    }

    // ------------------------------------------------------------------ HTTP
    static byte[] httpGet(String url, String referer, String accept, boolean xhr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.8");
        if (xhr) c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static byte[] vfGet(String url) throws Exception {
        return httpGet(url, VF + "/", "*/*", false);
    }

    // ------------------------------------------------------------------ VESSELFINDER
    static Map<Long, JSONObject> decodificaPosizioni(byte[] data, double s, double n, double w, double e) throws Exception {
        Map<Long, JSONObject> migliori = new HashMap<>();
        ByteOrder[] ordini = {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN};
        double[] scale = {600000.0, 1000000.0};
        for (ByteOrder bo : ordini) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(bo);
            for (double scala : scale) {
                for (int ordine = 0; ordine < 2; ordine++) {
                    Map<Long, JSONObject> trovate = new HashMap<>();
                    int i = 0;
                    while (i + 12 <= data.length) {
                        long mmsi = bb.getInt(i);
                        if (mmsi >= 100000000L && mmsi <= 999999999L) {
                            int a = bb.getInt(i + 4), b = bb.getInt(i + 8);
                            double lat = (ordine == 0 ? a : b) / scala, lon = (ordine == 0 ? b : a) / scala;
                            if (s <= lat && lat <= n && w <= lon && lon <= e) {
                                JSONObject o = new JSONObject();
                                o.put("mmsi", mmsi); o.put("lat", lat); o.put("lon", lon);
                                o.put("name", leggiNome(data, i + 12));
                                trovate.put(mmsi, o);
                                i += 12;
                                continue;
                            }
                        }
                        i += 1;
                    }
                    if (trovate.size() > migliori.size()) migliori = trovate;
                }
            }
        }
        return migliori;
    }

    static String leggiNome(byte[] data, int j) {
        for (int k = j; k < Math.min(j + 24, data.length - 1); k++) {
            int n = data[k] & 0xFF;
            if (n >= 2 && n <= 40 && k + 1 + n <= data.length) {
                boolean ok = true;
                int maiuscole = 0;
                for (int q = k + 1; q < k + 1 + n; q++) {
                    int c = data[q] & 0xFF;
                    if (c < 32 || c >= 127) { ok = false; break; }
                    if (c >= 65 && c <= 90) maiuscole++;
                }
                if (ok && maiuscole >= Math.max(1, n / 2)) {
                    return new String(data, k + 1, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
                }
            }
        }
        return "";
    }

    Map<Long, JSONObject> scaricaPosizioni() throws Exception {
        double mlat = (area.nord - area.sud) * 0.05, mlon = (area.est - area.ovest) * 0.05;
        double s = area.sud - mlat, n = area.nord + mlat, w = area.ovest - mlon, e = area.est + mlon;
        String b6 = (long) Math.floor(w * 600000) + "," + (long) Math.floor(s * 600000) + ","
                + (long) Math.floor(e * 600000) + "," + (long) Math.floor(n * 600000);
        String b1 = (long) (w * 1e6) + "," + (long) (s * 1e6) + "," + (long) (e * 1e6) + "," + (long) (n * 1e6);
        String[] urls = {
                VF + "/api/pub/mp2?bbox=" + b6 + "&zoom=12&mmsi=0&show_names=1",
                VF + "/api/pub/vesselsonmap?bbox=" + b1 + "&zoom=12&mmsi=0&show_names=1&ref="
                        + String.format(Locale.US, "%.8f", new Random().nextDouble() * 99999),
        };
        String err = null;
        for (String url : urls) {
            String corto = url.split("\\?")[0];
            try {
                Map<Long, JSONObject> b = decodificaPosizioni(vfGet(url), s, n, w, e);
                if (!b.isEmpty()) return b;
                err = "nessuna barca riconosciuta nella risposta di " + corto;
            } catch (Exception ex) {
                err = corto + ": " + ex;
            }
        }
        throw new Exception(err != null ? err : "nessuna risposta");
    }

    static JSONObject scaricaScheda(long mmsi) {
        String[] paths = {"/api/pub/click/" + mmsi, "/api/embed/v5/click/" + mmsi};
        for (String p : paths) {
            try {
                JSONObject d = new JSONObject(new String(vfGet(VF + p), "UTF-8"));
                if (d.length() > 0) return d;
            } catch (Exception ignored) { }
        }
        return null;
    }

    static List<String> urlFoto(String pic) {
        List<String> out = new ArrayList<>();
        if (pic == null || pic.isEmpty() || "null".equals(pic)) return out;
        if (pic.startsWith("//")) pic = "https:" + pic;
        String base = pic.startsWith("http") ? pic : FOTO_BASE + pic.replaceAll("^/+", "");
        String radice = (base.endsWith("/0") || base.endsWith("/1")) ? base.substring(0, base.length() - 2) : base;
        out.add(radice + "/0");
        out.add(radice + "/1");
        out.add(radice);
        return out;
    }

    static int[] dimensioniImg(byte[] d) {
        try {
            if (d.length > 24 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
                ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
                return new int[]{b.getInt(16), b.getInt(20)};
            }
            if (d.length > 30 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                    && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
                String tag = new String(d, 12, 4, "US-ASCII");
                ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                if (tag.equals("VP8 ")) {
                    return new int[]{(b.getShort(26) & 0xFFFF) & 0x3FFF, (b.getShort(28) & 0xFFFF) & 0x3FFF};
                }
                if (tag.equals("VP8L")) {
                    int q0 = d[21] & 0xFF, q1 = d[22] & 0xFF, q2 = d[23] & 0xFF, q3 = d[24] & 0xFF;
                    return new int[]{1 + (((q1 & 0x3F) << 8) | q0),
                            1 + (((q3 & 0x0F) << 10) | (q2 << 2) | ((q1 & 0xC0) >> 6))};
                }
                if (tag.equals("VP8X")) {
                    int w = (d[24] & 0xFF) | ((d[25] & 0xFF) << 8) | ((d[26] & 0xFF) << 16);
                    int h = (d[27] & 0xFF) | ((d[28] & 0xFF) << 8) | ((d[29] & 0xFF) << 16);
                    return new int[]{1 + w, 1 + h};
                }
            }
            if (d.length > 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
                int i = 2;
                while (i < d.length - 9) {
                    if ((d[i] & 0xFF) != 0xFF) { i++; continue; }
                    int m = d[i + 1] & 0xFF;
                    if ((m >= 0xC0 && m <= 0xCF) && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                        int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                        int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                        return new int[]{w, h};
                    }
                    if (m == 0xFF) { i++; continue; }
                    if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
                    i += 2 + (((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF));
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String tipoImg(byte[] d) {
        if (d.length > 4 && (d[0] & 0xFF) == 0x89 && d[1] == 'P') return "image/png";
        if (d.length > 12 && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        return "image/jpeg";
    }

    private static void scrivi(File f, byte[] data) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
    }

    static byte[] leggi(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /** Scarica la foto VF e restituisce il rapporto larghezza/altezza, o NaN. */
    double scaricaFoto(long mmsi, String pic) {
        for (String u : urlFoto(pic)) {
            try {
                byte[] data = vfGet(u);
                int[] dim = dimensioniImg(data);
                if (data.length > 1000 && dim != null && dim[1] > 0) {
                    scrivi(new File(fotoDir, mmsi + ".img"), data);
                    return (double) dim[0] / dim[1];
                }
            } catch (Exception ignored) { }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ MARINETRAFFIC
    List<String> mtRiquadri(Area a) {
        int[] t0 = tile(a.nord, a.ovest), t1 = tile(a.sud, a.est);
        List<String> out = new ArrayList<>();
        for (int x = t0[0]; x <= t1[0]; x++)
            for (int y = t0[1]; y <= t1[1]; y++)
                out.add("/getData/get_data_json_4/z:11/X:" + x + "/Y:" + y + "/station:0");
        return out;
    }

    static int[] tile(double lat, double lon) {
        int n = 1 << 10;
        int x = (int) ((lon + 180) / 360 * n);
        double r = Math.toRadians(lat);
        double asinh = Math.log(Math.tan(r) + Math.sqrt(Math.tan(r) * Math.tan(r) + 1));
        int y = (int) ((1 - asinh / Math.PI) / 2 * n);
        return new int[]{x, y};
    }

    private JSONObject mtPython(String path) throws Exception {
        byte[] d = httpGet(MT + path, MT + "/en/ais/home", "application/json, text/plain, */*", true);
        return new JSONObject(new String(d, "UTF-8"));
    }

    /** Legge i riquadri che coprono 'larga'; restituisce le barche dentro l'area, quelle fuori in mtFuori. */
    Map<String, JSONObject> leggiMt(Area larga) throws Exception {
        Map<String, JSONObject> dentro = new HashMap<>(), fuori = new HashMap<>();
        for (String path : mtRiquadri(larga)) {
            JSONObject d;
            try {
                d = "python".equals(modoMt) ? mtPython(path) : new JSONObject(browserMT.fetch(path));
            } catch (Exception ex) {
                if ("python".equals(modoMt) && browserMT != null) {
                    log("MarineTraffic blocca le richieste semplici (" + ex + "): uso il browser nascosto");
                    modoMt = "browser";
                    d = new JSONObject(browserMT.fetch(path));
                } else {
                    throw ex;
                }
            }
            JSONObject data = d.optJSONObject("data");
            JSONArray rows = data != null ? data.optJSONArray("rows") : null;
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r == null) continue;
                    double lat, lon;
                    try {
                        lat = Double.parseDouble(r.getString("LAT"));
                        lon = Double.parseDouble(r.getString("LON"));
                    } catch (Exception ex) { continue; }
                    JSONObject riga = new JSONObject();
                    riga.put("id", String.valueOf(r.opt("SHIP_ID")));
                    riga.put("lat", lat); riga.put("lon", lon);
                    riga.put("name", r.optString("SHIPNAME", "").equals("null") ? "" : r.optString("SHIPNAME", "").trim());
                    riga.put("elapsed", (int) num0(r.opt("ELAPSED")));
                    putNum(riga, "len", num(r.opt("LENGTH")));
                    putNum(riga, "wid", num(r.opt("WIDTH")));
                    riga.put("speed", num0(r.opt("SPEED")) / 10.0);
                    Object co = r.opt("COURSE");
                    if (co != null && co != JSONObject.NULL && !String.valueOf(co).isEmpty()) {
                        try { riga.put("course", Double.parseDouble(String.valueOf(co))); } catch (Exception ignored) { }
                    }
                    Object st = r.opt("SHIPTYPE");
                    if (st != null && st != JSONObject.NULL) riga.put("shiptype", String.valueOf(st));
                    riga.put("letto", now());
                    String id = riga.getString("id");
                    if (area.sud <= lat && lat <= area.nord && area.ovest <= lon && lon <= area.est) dentro.put(id, riga);
                    else if (larga.sud <= lat && lat <= larga.nord && larga.ovest <= lon && lon <= larga.est) fuori.put(id, riga);
                }
            }
            if (aspetta(PAUSA_TRA_RICHIESTE_MS)) break;
        }
        mtFuori = fuori;
        return dentro;
    }

    /** Immagine MarineTraffic: richiesta semplice, oppure dalla pagina invisibile se MT blocca. */
    private byte[] mtImg(String url) {
        byte[] d = null;
        if (!"browser".equals(modoMt)) {
            try {
                d = httpGet(url, MT + "/en/ais/home", "image/avif,image/webp,image/*,*/*;q=0.8", false);
            } catch (Exception ignored) { }
        }
        if (d == null && browserMT != null) {
            try { d = browserMT.fetchByte(url); } catch (Exception ignored) { }
        }
        if (d == null) return null;
        int[] dim = dimensioniImg(d);
        return (dim != null && dim[1] > 0 && d.length > 1000) ? d : null;
    }

    double scaricaFotoMt(String shipId) {
        String base = MT + "/photoCollection/getAssetDefaultPhoto?asset_id=" + shipId + "&asset_type_id=0&photo_size=";
        for (String size : new String[]{"800", "300"}) {
            byte[] data = mtImg(base + size);
            if (data != null) {
                try { scrivi(new File(fotoDir, "mt" + shipId + ".img"), data); } catch (Exception ignored) { }
                int[] dim = dimensioniImg(data);
                return (double) dim[0] / dim[1];
            }
        }
        return Double.NaN;
    }

    /** Foto nella qualita' migliore (per la vista ingrandita), in cache. {byte[], content-type} o null. */
    Object[] fotoGrande(String chiave) {
        File dest = new File(fotoDir, chiave + "_grande.img");
        try {
            if (dest.isFile()) {
                byte[] d = leggi(dest);
                return new Object[]{d, tipoImg(d)};
            }
        } catch (Exception ignored) { }
        byte[] scelta = null;
        if (chiave.startsWith("mt")) {
            String base = MT + "/photoCollection/getAssetDefaultPhoto?asset_id=" + chiave.substring(2) + "&asset_type_id=0&photo_size=";
            for (String size : new String[]{"1600", "1200", "800", "600", "300"}) {
                scelta = mtImg(base + size);
                if (scelta != null) break;
            }
        } else {
            String pic;
            synchronized (lock) {
                JSONObject sc = schede.get(Long.parseLong(chiave));
                pic = sc != null ? sc.optString("pic", null) : null;
            }
            List<String> u = urlFoto(pic);
            if (!u.isEmpty()) {
                String radice = u.get(2);
                byte[] piccola = prendiImg(radice + "/0", VF + "/");
                byte[] grande = prendiImg(radice + "/1", VF + "/");
                scelta = piccola;
                if (piccola != null && grande != null) {
                    int[] d0 = dimensioniImg(piccola), d1 = dimensioniImg(grande);
                    double ar0 = (double) d0[0] / d0[1], ar1 = (double) d1[0] / d1[1];
                    if (Math.abs(ar1 - ar0) / ar0 < 0.03 && d1[0] > d0[0]) scelta = grande;
                }
            }
        }
        if (scelta == null) return null;
        try { scrivi(dest, scelta); } catch (Exception ignored) { }
        return new Object[]{scelta, tipoImg(scelta)};
    }

    private static byte[] prendiImg(String u, String referer) {
        try {
            byte[] d = httpGet(u, referer, "image/avif,image/webp,image/*,*/*;q=0.8", false);
            int[] dim = dimensioniImg(d);
            return (dim != null && dim[1] > 0 && d.length > 1000) ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Foto piccola gia' in cache: {byte[], content-type} o null. */
    Object[] foto(String chiave) {
        if (!chiave.matches("(mt)?\\d+")) return null;
        File f = new File(fotoDir, chiave + ".img");
        if (!f.isFile()) return null;
        try {
            byte[] d = leggi(f);
            return new Object[]{d, tipoImg(d)};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ ARRIVI
    /** Semiretta dalla barca lungo la rotta: {distanza NM, lat, lon} del punto di ingresso, o null. */
    double[] ingressoArea(double lat, double lon, double rotta) {
        double k = 60 * Math.cos(Math.toRadians(area.latC));
        double px = (lon - area.lonC) * k, py = (lat - area.latC) * 60;
        double x0 = (area.ovest - area.lonC) * k, x1 = (area.est - area.lonC) * k;
        double y0 = (area.sud - area.latC) * 60, y1 = (area.nord - area.latC) * 60;
        double dx = Math.sin(Math.toRadians(rotta)), dy = Math.cos(Math.toRadians(rotta));
        double tIn = 0, tOut = Double.POSITIVE_INFINITY;
        double[][] assi = {{px, dx, x0, x1}, {py, dy, y0, y1}};
        for (double[] a : assi) {
            double p = a[0], d = a[1], lo = a[2], hi = a[3];
            if (Math.abs(d) < 1e-9) {
                if (!(lo <= p && p <= hi)) return null;
                continue;
            }
            double ta = (lo - p) / d, tb = (hi - p) / d;
            tIn = Math.max(tIn, Math.min(ta, tb));
            tOut = Math.min(tOut, Math.max(ta, tb));
        }
        if (tIn > tOut || tOut <= 0) return null;
        double ex = px + dx * tIn, ey = py + dy * tIn;
        return new double[]{tIn, area.latC + ey / 60, area.lonC + ex / k};
    }

    List<JSONObject> calcolaArrivi(Map<String, JSONObject> fuori) throws Exception {
        double ora = now();
        List<JSONObject> out = new ArrayList<>();
        Set<String> viste = new HashSet<>();
        for (Map.Entry<String, JSONObject> en : fuori.entrySet()) {
            JSONObject r = en.getValue();
            if (!r.has("course")) continue;
            double speed = r.getDouble("speed");
            if (speed < ARRIVI_VEL_MIN || r.getInt("elapsed") > ARRIVI_POS_MAX_MIN) continue;
            double rotta = r.getDouble("course");
            double[] dritto = ingressoArea(r.getDouble("lat"), r.getDouble("lon"), rotta);
            double[] colpo = dritto;
            if (colpo == null) {
                for (double d : new double[]{-ARRIVI_CONO_GRADI, ARRIVI_CONO_GRADI}) {
                    double[] c = ingressoArea(r.getDouble("lat"), r.getDouble("lon"), rotta + d);
                    if (c != null && (colpo == null || c[0] < colpo[0])) colpo = c;
                }
            }
            if (colpo == null) continue;
            double dist = Math.max(0, colpo[0] - speed * r.getInt("elapsed") / 60.0);
            double etaMin = dist / speed * 60;
            if (etaMin > ARRIVI_MAX_ORE * 60) continue;
            String sid = en.getKey();
            viste.add(sid);
            int c = (conferme.containsKey(sid) ? conferme.get(sid) : 0) + 1;
            conferme.put(sid, c);
            if (c >= ARRIVI_CONFERME) {
                JSONObject a = new JSONObject(r.toString());
                a.put("dist_nm", dist); a.put("eta_min", etaMin); a.put("calcolato", ora);
                a.put("ing_lat", colpo[1]); a.put("ing_lon", colpo[2]);
                out.add(a);
            }
        }
        Iterator<String> it = conferme.keySet().iterator();
        while (it.hasNext()) if (!viste.contains(it.next())) it.remove();
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Double.compare(a.optDouble("eta_min"), b.optDouble("eta_min"));
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ UTILITA'
    static double now() { return System.currentTimeMillis() / 1000.0; }

    static Double num(Object x) {
        if (x == null || x == JSONObject.NULL) return null;
        try {
            double v = Double.parseDouble(String.valueOf(x));
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    static double num0(Object x) {
        Double v = num(x);
        return v == null ? 0 : v;
    }

    static void putNum(JSONObject o, String k, Double v) throws Exception {
        if (v != null) o.put(k, v);
    }

    static String normNome(String n) {
        return n == null ? "" : n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static double distanzaM(JSONObject a, JSONObject b) {
        double dy = (a.optDouble("lat") - b.optDouble("lat")) * M_PER_DEG_LAT;
        double dx = (a.optDouble("lon") - b.optDouble("lon")) * M_PER_DEG_LAT * Math.cos(Math.toRadians(a.optDouble("lat")));
        return Math.hypot(dx, dy);
    }

    static final Map<String, String> MT_TIPI = new HashMap<>();
    static {
        MT_TIPI.put("9", "Pleasure craft"); MT_TIPI.put("8", "Tanker"); MT_TIPI.put("7", "Cargo");
        MT_TIPI.put("6", "Passenger"); MT_TIPI.put("3", "Tug"); MT_TIPI.put("2", "Fishing");
        MT_TIPI.put("4", "High speed craft");
    }

    static String tipoMt(JSONObject r) {
        if (r == null) return "";
        String t = MT_TIPI.get(r.optString("shiptype", ""));
        return t == null ? "" : t;
    }

    static String fmt(Double x) {
        if (x == null || x == 0) return "-";
        if (x == Math.rint(x)) return String.valueOf(x.longValue());
        return String.format(Locale.US, "%.1f", x);
    }

    static String colore(String tipo) {
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        Object[][] regole = {
                {new String[]{"sail", "pleasure", "yacht"}, "#c43bd6"},
                {new String[]{"passenger", "ferry", "cruise"}, "#2f6bff"},
                {new String[]{"cargo", "container", "bulk"}, "#3fae49"},
                {new String[]{"tanker"}, "#e0383e"},
                {new String[]{"fishing"}, "#f08a24"},
                {new String[]{"tug", "pilot", "search", "rescue", "port", "law", "military", "dredg"}, "#1fb3c4"},
        };
        for (Object[] r : regole)
            for (String k : (String[]) r[0])
                if (t.contains(k)) return (String) r[1];
        return t.isEmpty() ? "#c43bd6" : "#8a8a8a";
    }

    // ------------------------------------------------------------------ CACHE SU DISCO
    private void caricaCache() {
        schede = caricaMappa(new File(cacheDir, "schede_v3.json"));
        storico = caricaMappa(new File(cacheDir, "storico.json"));
        caricaMt();
    }

    /** Barche e foto MarineTraffic dell'ultima lettura: cosi' alla riapertura si vedono subito,
     *  senza aspettare i ~20 secondi della pagina invisibile. */
    private void caricaMt() {
        try {
            JSONObject o = new JSONObject(new String(leggi(new File(cacheDir, "marinetraffic.json")), "UTF-8"));
            JSONObject righe = o.optJSONObject("righe");
            if (righe != null) {
                Iterator<String> it = righe.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    mt.put(k, righe.getJSONObject(k));
                }
            }
            JSONObject foto = o.optJSONObject("foto");
            if (foto != null) {
                Iterator<String> it = foto.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject f = foto.getJSONObject(k);
                    double ar = f.optDouble("ar", Double.NaN);
                    if (!Double.isNaN(ar)) fotoMt.put(k, new double[]{f.optDouble("t", 0), ar});
                }
            }
        } catch (Exception ignored) { }
    }

    private void salvaMt() {
        try {
            JSONObject righe = new JSONObject(), foto = new JSONObject();
            synchronized (lock) {
                for (Map.Entry<String, JSONObject> e : mt.entrySet()) righe.put(e.getKey(), e.getValue());
                for (Map.Entry<String, double[]> e : fotoMt.entrySet()) {
                    if (Double.isNaN(e.getValue()[1])) continue;          // le foto non trovate non si salvano
                    JSONObject f = new JSONObject();
                    f.put("t", e.getValue()[0]);
                    f.put("ar", e.getValue()[1]);
                    foto.put(e.getKey(), f);
                }
            }
            JSONObject o = new JSONObject();
            o.put("righe", righe);
            o.put("foto", foto);
            scrivi(new File(cacheDir, "marinetraffic.json"), o.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private static Map<Long, JSONObject> caricaMappa(File f) {
        Map<Long, JSONObject> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(new String(leggi(f), "UTF-8"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(Long.parseLong(k), o.getJSONObject(k));
            }
        } catch (Exception ignored) { }
        return m;
    }

    private static void salvaMappa(File f, Map<Long, JSONObject> m) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Long, JSONObject> e : m.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
            scrivi(f, o.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private void registra(Map<Long, JSONObject> nuove) throws Exception {
        double ora = now();
        Map<Long, JSONObject> copia;
        synchronized (lock) {
            for (Map.Entry<Long, JSONObject> e : nuove.entrySet()) {
                JSONObject b = new JSONObject(e.getValue().toString());
                b.put("seen", ora);
                storico.put(e.getKey(), b);
            }
            Iterator<Map.Entry<Long, JSONObject>> it = storico.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, JSONObject> e = it.next();
                if (!nuove.containsKey(e.getKey()) && ora - e.getValue().optDouble("seen", 0) >= STORICO_ORE * 3600) it.remove();
            }
            copia = new HashMap<>(storico);
        }
        salvaMappa(new File(cacheDir, "storico.json"), copia);
    }

    private void salvaSchede() {
        Map<Long, JSONObject> copia;
        synchronized (lock) { copia = new HashMap<>(schede); }
        salvaMappa(new File(cacheDir, "schede_v3.json"), copia);
    }

    // ------------------------------------------------------------------ ABBINAMENTI E SNAPSHOT
    /** VesselFinder (MMSI) <-> MarineTraffic (SHIP_ID), per nome o per vicinanza. Da chiamare con lock. */
    private Map<Long, String> abbinamenti(Set<String> usati) {
        Map<Long, String> abb = new HashMap<>();
        Map<String, List<String>> perNome = new HashMap<>();
        for (Map.Entry<String, JSONObject> e : mt.entrySet()) {
            String k = normNome(e.getValue().optString("name"));
            if (!perNome.containsKey(k)) perNome.put(k, new ArrayList<String>());
            perNome.get(k).add(e.getKey());
        }
        for (Map.Entry<Long, JSONObject> e : storico.entrySet()) {
            JSONObject b = e.getValue();
            JSONObject sc = schede.get(e.getKey());
            String nomeSc = sc != null ? sc.optString("name", "") : "";
            if (nomeSc.equals("null")) nomeSc = "";
            String nome = normNome(nomeSc.isEmpty() ? b.optString("name") : nomeSc);
            List<String> cand = new ArrayList<>();
            if (!nome.isEmpty() && perNome.containsKey(nome))
                for (String sid : perNome.get(nome)) if (!usati.contains(sid)) cand.add(sid);
            if (cand.isEmpty()) {
                for (Map.Entry<String, JSONObject> m : mt.entrySet())
                    if (!usati.contains(m.getKey()) && distanzaM(b, m.getValue()) < 25
                            && normNome(m.getValue().optString("name")).isEmpty()) cand.add(m.getKey());
            }
            if (!cand.isEmpty()) {
                String best = null;
                double dmin = Double.POSITIVE_INFINITY;
                for (String sid : cand) {
                    double d = distanzaM(b, mt.get(sid));
                    if (d < dmin) { dmin = d; best = sid; }
                }
                abb.put(e.getKey(), best);
                usati.add(best);
            }
        }
        return abb;
    }

    public String snapshot() {
        try {
            synchronized (lock) {
                JSONArray out = new JSONArray();
                double ora = now();
                Set<String> usati = new HashSet<>();
                Map<Long, String> abb = abbinamenti(usati);
                for (Map.Entry<Long, JSONObject> e : storico.entrySet()) {
                    long m = e.getKey();
                    JSONObject b = e.getValue();
                    JSONObject sc = schede.containsKey(m) ? schede.get(m) : new JSONObject();
                    JSONObject r = abb.containsKey(m) ? mt.get(abb.get(m)) : null;
                    List<Double> stime = new ArrayList<>();
                    if (sc.has("ts") && !sc.isNull("ts")) {
                        double ts = sc.optDouble("ts", Double.NaN);
                        if (!Double.isNaN(ts)) stime.add((ora - ts) / 60);
                    }
                    if (r != null) stime.add(r.getInt("elapsed") + (ora - r.getDouble("letto")) / 60);
                    if (!barche.containsKey(m)) stime.add((ora - b.optDouble("seen", ora)) / 60);
                    int eta = stime.isEmpty() ? 0 : (int) Math.max(0, Collections.min(stime));
                    Double L = num(sc.opt("len")), W = num(sc.opt("wid"));
                    if (L == null && r != null && r.has("len")) L = r.getDouble("len");
                    if (W == null && r != null && r.has("wid")) W = r.getDouble("wid");
                    String tipo = sc.optString("type", "");
                    if (tipo.isEmpty() || tipo.equals("null") || tipo.equals("Unknown")) tipo = tipoMt(r);
                    String fotoKey = null;
                    double ar = Double.NaN;
                    if (sc.has("foto") && !Double.isNaN(sc.optDouble("foto", Double.NaN))) {
                        fotoKey = String.valueOf(m);
                        ar = sc.getDouble("foto");
                    } else if (r != null) {
                        double[] fm = fotoMt.get(r.getString("id"));
                        if (fm != null && !Double.isNaN(fm[1])) { fotoKey = "mt" + r.getString("id"); ar = fm[1]; }
                    }
                    String nome = sc.optString("name", "");
                    if (nome.isEmpty() || nome.equals("null")) nome = b.optString("name", "");
                    if (nome.isEmpty() && r != null) nome = r.optString("name", "");
                    if (nome.isEmpty()) nome = String.valueOf(m);
                    out.put(voce(String.valueOf(m), b.getDouble("lat"), b.getDouble("lon"), nome, L, W, tipo,
                            fotoKey, ar, eta, r != null ? "VF+MT" : "VF"));
                }
                for (Map.Entry<String, JSONObject> e : mt.entrySet()) {
                    String sid = e.getKey();
                    if (usati.contains(sid)) continue;
                    JSONObject r = e.getValue();
                    int eta = r.getInt("elapsed") + (int) ((ora - r.getDouble("letto")) / 60);
                    if (eta > STORICO_ORE * 60) continue;
                    double[] fm = fotoMt.get(sid);
                    boolean haFoto = fm != null && !Double.isNaN(fm[1]);
                    String nome = r.optString("name", "");
                    out.put(voce("mt" + sid, r.getDouble("lat"), r.getDouble("lon"), nome.isEmpty() ? "?" : nome,
                            r.has("len") ? r.getDouble("len") : null, r.has("wid") ? r.getDouble("wid") : null,
                            tipoMt(r), haFoto ? "mt" + sid : null, haFoto ? fm[1] : Double.NaN, eta, "MT"));
                }
                JSONArray arr = new JSONArray();
                for (JSONObject a : arrivi) {
                    double passati = (ora - a.getDouble("calcolato")) / 60;
                    JSONObject o = new JSONObject();
                    String nome = a.optString("name", "");
                    o.put("name", nome.isEmpty() ? "?" : nome);
                    o.put("eta", Math.max(0, (int) (a.getDouble("eta_min") - passati)));
                    o.put("dist", Math.round(Math.max(0, a.getDouble("dist_nm") - a.getDouble("speed") * passati / 60) * 10) / 10.0);
                    o.put("speed", Math.round(a.getDouble("speed") * 10) / 10.0);
                    o.put("course", a.getDouble("course"));
                    if (a.has("len")) o.put("len", a.getDouble("len"));
                    o.put("color", colore(tipoMt(a)));
                    o.put("ing_lat", a.getDouble("ing_lat"));
                    o.put("ing_lon", a.getDouble("ing_lon"));
                    arr.put(o);
                }
                JSONObject ris = new JSONObject();
                ris.put("updated", aggiornato == null ? JSONObject.NULL : aggiornato);
                ris.put("error", errore == null ? JSONObject.NULL : errore);
                ris.put("vessels", out);
                ris.put("arrivi", arr);
                return ris.toString();
            }
        } catch (Exception ex) {
            return "{\"updated\":null,\"error\":\"" + String.valueOf(ex).replace("\"", "'") + "\",\"vessels\":[],\"arrivi\":[]}";
        }
    }

    private static JSONObject voce(String key, double lat, double lon, String nome, Double L, Double W, String tipo,
                                   String fotoKey, double ar, int eta, String fonte) throws Exception {
        JSONObject o = new JSONObject();
        o.put("mmsi", key); o.put("lat", lat); o.put("lon", lon); o.put("name", nome);
        o.put("len", L == null ? JSONObject.NULL : L);
        o.put("wid", W == null ? JSONObject.NULL : W);
        o.put("size", (L != null || W != null) ? fmt(L) + " / " + fmt(W) + " m" : JSONObject.NULL);
        o.put("type", tipo); o.put("color", colore(tipo));
        o.put("photo", fotoKey == null ? JSONObject.NULL : fotoKey);
        o.put("ar", Double.isNaN(ar) ? 1.5 : ar);
        o.put("v", 1); o.put("age", eta); o.put("src", fonte);
        return o;
    }

    // ------------------------------------------------------------------ SCHEDE
    private void aggiornaScheda(long mmsi) throws Exception {
        JSONObject d = scaricaScheda(mmsi);
        JSONObject vecchia;
        synchronized (lock) { vecchia = schede.containsKey(mmsi) ? schede.get(mmsi) : new JSONObject(); }
        boolean rifareFoto = now() - vecchia.optDouble("t_foto", 0) > CACHE_SCHEDE_ORE * 3600;
        double foto;
        if (rifareFoto) {
            String pic = d != null && d.has("pic") && !d.isNull("pic") ? String.valueOf(d.get("pic")) : null;
            foto = scaricaFoto(mmsi, pic);
        } else {
            foto = vecchia.optDouble("foto", Double.NaN);
        }
        JSONObject nuova;
        if (d == null) {
            nuova = new JSONObject(vecchia.toString());
        } else {
            nuova = new JSONObject();
            nuova.put("name", d.opt("name")); nuova.put("type", d.opt("type"));
            nuova.put("len", d.opt("al")); nuova.put("wid", d.opt("aw"));
            nuova.put("pic", d.opt("pic"));
            Object ts = d.opt("ts");
            if (ts == null || ts == JSONObject.NULL) ts = vecchia.opt("ts");
            if (ts != null) nuova.put("ts", ts);
        }
        nuova.put("t", now());
        if (Double.isNaN(foto)) nuova.remove("foto"); else nuova.put("foto", foto);
        nuova.put("t_foto", rifareFoto ? now() : vecchia.optDouble("t_foto", 0));
        synchronized (lock) { schede.put(mmsi, nuova); }
    }

    // ------------------------------------------------------------------ CICLO PRINCIPALE
    private void worker() {
        double ultimoMt = 0;
        while (!fermo) {
            // 1. posizioni VesselFinder
            Map<Long, JSONObject> nuove = new HashMap<>();
            try {
                nuove = scaricaPosizioni();
                registra(nuove);
                synchronized (lock) {
                    barche = nuove;
                    aggiornato = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
                    errore = null;
                }
                log("VesselFinder: " + nuove.size() + " barche nell'area");
            } catch (Exception ex) {
                synchronized (lock) { errore = ex.getMessage() != null ? ex.getMessage() : ex.toString(); }
                log("Errore posizioni VesselFinder: " + ex);
            }
            if (fermo) break;

            // 2. MarineTraffic
            boolean mtLetto = false;
            if (USA_MARINETRAFFIC && now() - ultimoMt > POLL_MARINETRAFFIC_S) {
                ultimoMt = now();
                try {
                    Map<String, JSONObject> righe = leggiMt(ARRIVI ? areaAllargata(area, ARRIVI_RAGGIO_NM) : area);
                    synchronized (lock) {
                        mt = righe;
                        if (ARRIVI) arrivi = calcolaArrivi(mtFuori);
                    }
                    log("MarineTraffic: " + righe.size() + " barche, " + arrivi.size() + " in arrivo");
                    mtLetto = true;
                    salvaMt();
                } catch (Exception ex) {
                    log("Errore MarineTraffic: " + ex);
                }
            }
            if (fermo) break;

            // 3. schede VesselFinder mancanti o vecchie
            double limite = now() - AGGIORNA_SCHEDE_MIN * 60;
            List<Long> daFare = new ArrayList<>();
            synchronized (lock) {
                for (Long m : nuove.keySet()) {
                    JSONObject sc = schede.get(m);
                    if (sc == null || sc.optDouble("t", 0) < limite) daFare.add(m);
                }
            }
            for (Long m : daFare) {
                if (fermo) break;
                try { aggiornaScheda(m); } catch (Exception ex) { log("Scheda " + m + ": " + ex); }
                if (aspetta(PAUSA_TRA_RICHIESTE_MS)) break;
            }
            if (!daFare.isEmpty()) salvaSchede();

            // 4. foto MarineTraffic per chi non ha la foto VesselFinder
            if (mtLetto && !fermo) {
                List<String> servono = new ArrayList<>();
                synchronized (lock) {
                    Map<Long, String> abb = abbinamenti(new HashSet<String>());
                    Set<String> conFotoVf = new HashSet<>();
                    for (Map.Entry<Long, String> e : abb.entrySet()) {
                        JSONObject sc = schede.get(e.getKey());
                        if (sc != null && sc.has("foto")) conFotoVf.add(e.getValue());
                    }
                    for (String sid : mt.keySet()) {
                        double[] fm = fotoMt.get(sid);
                        // foto mai cercata, non trovata l'ultima volta (si riprova ogni 3 minuti) o vecchia di 6 ore
                        boolean rifare = fm == null || Double.isNaN(fm[1]) || now() - fm[0] > CACHE_SCHEDE_ORE * 3600;
                        if (!conFotoVf.contains(sid) && rifare) servono.add(sid);
                    }
                }
                int trovate = 0;
                for (String sid : servono) {
                    if (fermo) break;
                    double ar = scaricaFotoMt(sid);
                    if (!Double.isNaN(ar)) trovate++;
                    synchronized (lock) { fotoMt.put(sid, new double[]{now(), ar}); }
                    if (aspetta(PAUSA_TRA_RICHIESTE_MS)) break;
                }
                if (!servono.isEmpty()) {
                    log("MarineTraffic: foto trovate " + trovate + " su " + servono.size());
                    salvaMt();
                }
            }

            if (mtLetto) riepilogo();

            if (aspetta(POLL_POSIZIONI_S * 1000L)) break;
        }
        thread = null;
    }

    /** Nel registro: per ogni barca, da dove arrivano nome, misure e foto. */
    private void riepilogo() {
        List<String> righe = new ArrayList<>();
        synchronized (lock) {
            Set<String> usati = new HashSet<>();
            Map<Long, String> abb = abbinamenti(usati);
            for (Map.Entry<Long, JSONObject> e : storico.entrySet()) {
                long m = e.getKey();
                JSONObject sc = schede.containsKey(m) ? schede.get(m) : new JSONObject();
                String sid = abb.get(m);
                JSONObject r = sid != null ? mt.get(sid) : null;
                String nome = sc.optString("name", "");
                if (nome.isEmpty() || nome.equals("null")) nome = e.getValue().optString("name", "?");
                double[] fm = sid != null ? fotoMt.get(sid) : null;
                righe.add("VF " + m + " " + nome
                        + " | misure VF: " + fmt(num(sc.opt("len"))) + "x" + fmt(num(sc.opt("wid")))
                        + " | foto VF: " + (sc.has("foto") ? "si" : "no")
                        + " | MT: " + (r == null ? "non abbinata"
                        : sid + " " + r.optString("name", "?") + " misure " + fmt(num(r.opt("len"))) + "x" + fmt(num(r.opt("wid")))
                        + " foto " + (fm == null ? "mai cercata" : Double.isNaN(fm[1]) ? "non trovata" : "si"))
                        + (barche.containsKey(m) ? "" : " | non piu' segnalata da VF"));
            }
            for (Map.Entry<String, JSONObject> e : mt.entrySet()) {
                if (usati.contains(e.getKey())) continue;
                JSONObject r = e.getValue();
                double[] fm = fotoMt.get(e.getKey());
                righe.add("solo MT " + e.getKey() + " " + r.optString("name", "?")
                        + " | misure " + fmt(num(r.opt("len"))) + "x" + fmt(num(r.opt("wid")))
                        + " | foto " + (fm == null ? "mai cercata" : Double.isNaN(fm[1]) ? "non trovata" : "si")
                        + " | vista " + r.optInt("elapsed", -1) + " min fa");
            }
        }
        log("Riepilogo barche (" + righe.size() + "):");
        for (String r : righe) log("  " + r);
    }

    /** Dati di configurazione per la pagina. */
    public String bounds() {
        return "[[" + area.sud + "," + area.ovest + "],[" + area.nord + "," + area.est + "]]";
    }
}
