package it.gallipoli.barche;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mappa delle barche di Gallipoli per Fire TV.
 * La pagina (assets/pagina.html) e' la stessa dello script Python, adattata al telecomando;
 * i dati li prepara Motore.java e la pagina li chiede a "https://barche.app/api/...".
 */
public class MainActivity extends Activity {

    static final String BASE = "https://barche.app/";
    static final String HOST = "barche.app";

    private WebView web;
    private Motore motore;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ MarineTraffic col browser nascosto
    private WebView mtWeb;
    private volatile boolean mtPronta = false;
    private final AtomicInteger mtId = new AtomicInteger();
    private final Map<Integer, String[]> mtRisposte = new HashMap<>();
    private final Map<Integer, CountDownLatch> mtAttese = new HashMap<>();

    public class PonteMT {
        @JavascriptInterface
        public void risultato(int id, String testo, String errore) {
            synchronized (mtRisposte) {
                mtRisposte.put(id, new String[]{testo, errore});
                CountDownLatch l = mtAttese.get(id);
                if (l != null) l.countDown();
            }
        }
    }

    /** Fa la richiesta dall'interno di una pagina MarineTraffic aperta in una WebView invisibile. */
    private String fetchMt(final String path) throws Exception {
        if (!mtPronta) {
            final CountDownLatch pronta = new CountDownLatch(1);
            ui.post(new Runnable() {
                @Override public void run() {
                    if (mtWeb == null) {
                        mtWeb = new WebView(MainActivity.this);
                        WebSettings s = mtWeb.getSettings();
                        s.setJavaScriptEnabled(true);
                        s.setDomStorageEnabled(true);
                        s.setUserAgentString(Motore.UA);
                        mtWeb.addJavascriptInterface(new PonteMT(), "PonteMT");
                        mtWeb.setWebViewClient(new WebViewClient());
                        mtWeb.loadUrl(Motore.MT + "/en/ais/home/centerx:17.95/centery:40.04/zoom:12");
                    }
                    pronta.countDown();
                }
            });
            pronta.await(10, TimeUnit.SECONDS);
            Thread.sleep(8000);                      // il tempo di caricare la pagina e i cookie
            mtPronta = true;
        }
        final int id = mtId.incrementAndGet();
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (mtRisposte) { mtAttese.put(id, latch); }
        final String js = "fetch(" + org.json.JSONObject.quote(path) + ", {headers: {'X-Requested-With': 'XMLHttpRequest'}})"
                + ".then(function(r){return r.text();})"
                + ".then(function(t){PonteMT.risultato(" + id + ", t, null);})"
                + ".catch(function(e){PonteMT.risultato(" + id + ", null, String(e));});";
        ui.post(new Runnable() {
            @Override public void run() {
                if (mtWeb != null) mtWeb.evaluateJavascript(js, null);
            }
        });
        boolean arrivata = latch.await(30, TimeUnit.SECONDS);
        String[] r;
        synchronized (mtRisposte) {
            mtAttese.remove(id);
            r = mtRisposte.remove(id);
        }
        if (!arrivata || r == null) throw new Exception("MarineTraffic: nessuna risposta dal browser");
        if (r[1] != null) throw new Exception("MarineTraffic: " + r[1]);
        return r[0];
    }

    // ------------------------------------------------------------ ATTIVITA'
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        motore = new Motore(getCacheDir(), new Motore.BrowserMT() {
            @Override public String fetch(String path) throws Exception { return fetchMt(path); }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0xaa, 0xd3, 0xdf));
        web.setFocusable(false);                      // i tasti li gestisce l'attivita'
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (!HOST.equals(req.getUrl().getHost())) return null;      // mappe di sfondo: normali
                return rispondi(req.getUrl().getPath());
            }
        });
        setContentView(web);

        String pagina = leggiAsset("pagina.html").replace("%%BOUNDS%%", motore.bounds());
        web.loadDataWithBaseURL(BASE, pagina, "text/html", "UTF-8", null);
        motore.avvia();
    }

    @Override
    protected void onDestroy() {
        motore.ferma();
        if (mtWeb != null) mtWeb.destroy();
        web.destroy();
        super.onDestroy();
    }

    /** Risposte della "parte server" (come il piccolo server dello script Python). */
    private WebResourceResponse rispondi(String path) {
        if (path == null) path = "/";
        try {
            if (path.equals("/api/vessels")) {
                return risposta("application/json", motore.snapshot().getBytes("UTF-8"), false);
            }
            if (path.startsWith("/photo/")) {
                Object[] f = motore.foto(path.substring("/photo/".length()));
                return f == null ? vuota(404) : risposta((String) f[1], (byte[]) f[0], true);
            }
            if (path.startsWith("/foto_grande/")) {
                String chiave = path.substring("/foto_grande/".length());
                if (!chiave.matches("(mt)?\\d+")) return vuota(404);
                Object[] f = motore.fotoGrande(chiave);
                return f == null ? vuota(404) : risposta((String) f[1], (byte[]) f[0], true);
            }
            if (path.equals("/leaflet.js")) return risposta("application/javascript", leggiAssetByte("leaflet.js"), true);
            if (path.equals("/leaflet.css")) return risposta("text/css", leggiAssetByte("leaflet.css"), true);
        } catch (Exception ignored) { }
        return vuota(404);
    }

    private static WebResourceResponse risposta(String mime, byte[] data, boolean cache) {
        WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(data));
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", cache ? "max-age=86400" : "no-store");
        h.put("Access-Control-Allow-Origin", "*");
        r.setStatusCodeAndReasonPhrase(200, "OK");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse vuota(int code) {
        WebResourceResponse r = new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(code, code == 404 ? "Not Found" : "Error");
        return r;
    }

    private byte[] leggiAssetByte(String nome) throws Exception {
        InputStream in = getAssets().open(nome);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private String leggiAsset(String nome) {
        try {
            return new String(leggiAssetByte(nome), "UTF-8");
        } catch (Exception e) {
            return "<h1>Errore: " + e + "</h1>";
        }
    }

    // ------------------------------------------------------------ TELECOMANDO
    private void js(String codice) {
        web.evaluateJavascript(codice, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        String t = null;
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: t = "su"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: t = "giu"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: t = "sx"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: t = "dx"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: t = "ok"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: t = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: t = "piu"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: t = "meno"; break;
            case KeyEvent.KEYCODE_MENU: t = "pannello"; break;
            case KeyEvent.KEYCODE_BACK:
                if (ev.getAction() == KeyEvent.ACTION_UP) {
                    web.evaluateJavascript("typeof indietro === 'function' ? indietro() : false",
                            new ValueCallback<String>() {
                                @Override public void onReceiveValue(String v) {
                                    if (!"true".equals(v)) finish();
                                }
                            });
                }
                return true;
            default:
                return super.dispatchKeyEvent(ev);
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            // le frecce si possono tenere premute; gli altri tasti valgono una volta
            boolean freccia = t.equals("su") || t.equals("giu") || t.equals("sx") || t.equals("dx");
            if (ev.getRepeatCount() == 0 || freccia) js("typeof tasto === 'function' && tasto('" + t + "')");
        }
        return true;
    }
}
