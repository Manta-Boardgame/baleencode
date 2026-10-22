package io.github.mantaboardgame.baleencode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.webkit.WebViewAssetLoader;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final int FILE_PICK = 1;
    private static final int FOLDER_PICK = 2;
    private static final int MAX_IMAGE_SIDE = 4096;            // larger images are downscaled before OCR
    private static final long MAX_IMAGE_PIXELS = 400_000_000L; // refuse "decompression bomb" images
    private static final int MAX_PDF_PAGES = 1000;
    private static final int MAX_PROMPT_CHARS = 2_000_000;

    private WebView webView;
    // The assistant's own browser for the browser modes: a separate WebView with NO bridge to this app
    private WebView agentWeb;
    private ValueCallback<Uri[]> filePicked;
    private int folderPickId = -1;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, HttpURLConnection> live = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        agentWeb = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);
        // The assistant's browser takes the upper half while a browser mode is in use, the app the lower half
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(agentWeb, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        agentWeb.setVisibility(View.GONE);
        root.addView(column);
        setContentView(root);
        setUpAgentWeb();

        // Keep content clear of the status bar, navigation bar and keyboard
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        // Page inspection only in debuggable (developer) builds; the release APK is not debuggable
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setNegativeButton("Cancel", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }

            // Used to rename a conversation
            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
                android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setSingleLine(true);
                if (defaultValue != null) { input.setText(defaultValue); input.setSelection(input.getText().length()); }
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setView(input)
                        .setPositiveButton("OK", (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("Cancel", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }

            // "Attach file" opens the system file picker
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePicked != null) filePicked.onReceiveValue(null);
                filePicked = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_PICK);
                    return true;
                } catch (Exception e) {
                    filePicked = null;
                    return false;
                }
            }
        });

        // Serve bundled assets from https://appassets.androidplatform.net/ so ES modules (pdf.js) can load
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            // Security: never load outside pages inside the app, because the page has access to the
            // native bridge. Links open in the system browser instead.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (APP_HOST.equals(url.getHost())) return false;
                if ("https".equals(url.getScheme()) || "http".equals(url.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (Exception ignored) {}
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "DSBridge");
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    /**
     * The browser the assistant drives. It is an ordinary web view: no JavaScript bridge to this app, no file or
     * content access, only http/https pages, no pop-up windows; permission requests (camera, location ...) are denied.
     * It shares no logins with the phone's own browser.
     */
    private void setUpAgentWeb() {
        WebSettings s = agentWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        agentWeb.setWebChromeClient(new WebChromeClient());
        agentWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("https".equals(scheme) || "http".equals(scheme));   // intent:, file:, app links ... are not opened
            }
        });
    }

    private static final Map<String, Integer> KEYS = new java.util.HashMap<>();
    static {
        KEYS.put("Enter", KeyEvent.KEYCODE_ENTER); KEYS.put("Tab", KeyEvent.KEYCODE_TAB); KEYS.put("Escape", KeyEvent.KEYCODE_ESCAPE);
        KEYS.put("Esc", KeyEvent.KEYCODE_ESCAPE); KEYS.put("Backspace", KeyEvent.KEYCODE_DEL); KEYS.put("Delete", KeyEvent.KEYCODE_FORWARD_DEL);
        KEYS.put("ArrowUp", KeyEvent.KEYCODE_DPAD_UP); KEYS.put("ArrowDown", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("ArrowLeft", KeyEvent.KEYCODE_DPAD_LEFT); KEYS.put("ArrowRight", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("PageUp", KeyEvent.KEYCODE_PAGE_UP); KEYS.put("PageDown", KeyEvent.KEYCODE_PAGE_DOWN);
        KEYS.put("Home", KeyEvent.KEYCODE_MOVE_HOME); KEYS.put("End", KeyEvent.KEYCODE_MOVE_END); KEYS.put("Space", KeyEvent.KEYCODE_SPACE);
    }

    /** Only public internet hosts: not this phone, the local network, other private ranges, or China's country domains */
    private static void checkPublicHost(String host) throws Exception {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
        if (h.isEmpty() || h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            throw new Exception("Only public web pages can be read.");
        }
        if (h.matches(".*\\.(cn|xn--fiqs8s|xn--fiqz9s|中国|中國)\\.?$")) throw new Exception("Sites under China's country domains are not read by this app.");
        for (InetAddress a : InetAddress.getAllByName(h)) {
            byte[] b = a.getAddress();
            int b0 = b[0] & 0xff, b1 = b.length > 1 ? b[1] & 0xff : 0;
            boolean bad = a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()
                    || (b.length == 4 && (b0 == 0 || b0 >= 224 || (b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 192 && b1 == 0) || (b0 == 198 && (b1 == 18 || b1 == 19))))
                    || (b.length == 16 && (b0 & 0xfe) == 0xfc);
            if (bad) throw new Exception("Only public web pages can be read.");
        }
    }

    /** Web search settings, the same as the desktop app (10 results, longer excerpts) */
    private static JSONObject searchConfig() throws Exception {
        return new JSONObject().put("max_results", 10)
                .put("excerpts", new JSONObject().put("max_chars_per_result", 2500).put("max_chars_total", 20000));
    }

    /** The visible part of the assistant's browser as a JPEG (at most 720 px wide) */
    private String agentWebShot() throws Exception {
        int vw = agentWeb.getWidth(), vh = agentWeb.getHeight();
        if (vw <= 0 || vh <= 0) throw new Exception("The browser panel is not on screen. Open a page first, and keep BaleenCode in front while it works.");
        Bitmap full = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
        agentWeb.draw(new Canvas(full));
        float scale = Math.min(1f, 720f / vw);
        Bitmap b = scale < 1f ? Bitmap.createScaledBitmap(full, Math.round(vw * scale), Math.round(vh * scale), true) : full;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, out);
        float d = getResources().getDisplayMetrics().density;
        return new JSONObject().put("w", b.getWidth()).put("h", b.getHeight()).put("cssW", vw / d).put("cssH", vh / d)
                .put("b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_PICK) {
            if (filePicked != null) {
                Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    uris = new Uri[]{ data.getData() };
                }
                filePicked.onReceiveValue(uris);
                filePicked = null;
            }
            return;
        }
        if (requestCode == FOLDER_PICK) {
            int id = folderPickId;
            folderPickId = -1;
            if (id < 0) return;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    String name = DocumentsContract.getTreeDocumentId(tree);
                    int colon = name.lastIndexOf(':');
                    if (colon >= 0 && colon < name.length() - 1) name = name.substring(colon + 1);
                    if (name.isEmpty()) name = "(storage root)";
                    deliver(id, 200, new JSONObject().put("uri", tree.toString()).put("name", name).toString());
                } catch (Exception e) {
                    deliver(id, 400, "Could not use that folder: " + e.getMessage());
                }
            } else {
                deliver(id, 400, "");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Native helpers called from the page's JavaScript */
    private class Bridge {
        /** The approved provider list, in the same shape the desktop server returns */
        @JavascriptInterface
        public void providers(int id) {
            io.execute(() -> {
                try { deliver(id, 200, Providers.asJson()); }
                catch (Exception e) { deliver(id, 500, "{}"); }
            });
        }

        /** Plain request to the gateway (used for the credit balance) */
        @JavascriptInterface
        public void request(int id, String method, String url, String body, String apiKey) {
            io.execute(() -> {
                int status = 0;
                String text = "";
                if (!url.startsWith(Providers.GATEWAY + "/")) {
                    text = "blocked";
                } else {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestMethod(method);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("Authorization", "Bearer " + apiKey);
                        if ("POST".equals(method)) {
                            c.setRequestProperty("Content-Type", "application/json");
                            c.setDoOutput(true);
                            try (OutputStream os = c.getOutputStream()) {
                                os.write(body.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        status = c.getResponseCode();
                        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                        text = is == null ? "" : readAll(is);
                        c.disconnect();
                    } catch (Exception e) {
                        status = 0;
                        text = String.valueOf(e.getMessage());
                    }
                }
                deliver(id, status, text);
            });
        }

        /**
         * Stream a chat reply. The provider allowlist is added here, in the app, so the
         * page cannot widen it. Chunks are pushed to the page as they arrive.
         */
        @JavascriptInterface
        public void chat(int id, String bodyJson, String apiKey) {
            io.execute(() -> {
                HttpURLConnection c = null;
                try {
                    JSONObject in = new JSONObject(bodyJson);
                    JSONArray messages = in.optJSONArray("messages");
                    if (messages == null || messages.length() == 0) throw new Exception("No message to send.");
                    int chars = 0;
                    for (int i = 0; i < messages.length(); i++) {
                        chars += messages.getJSONObject(i).optString("content").length();
                    }
                    if (chars > MAX_PROMPT_CHARS) {
                        throw new Exception("The conversation is too long. Start a new chat or remove the attached file.");
                    }

                    String effort = in.optString("effort", "none");
                    if (!effort.matches("none|low|high|max")) effort = "none";

                    JSONObject payload = new JSONObject();
                    payload.put("model", Providers.MODEL);
                    payload.put("messages", messages);
                    // Web search (opt-in): AI Gateway runs Parallel's search (US); only the search words leave
                    if (in.optBoolean("webSearch", false)) {
                        payload.put("tools", new JSONArray().put(new JSONObject().put("type", "vercel:parallel_search").put("config", searchConfig())));
                        payload.put("tool_choice", "auto");
                    }
                    payload.put("stream", true);
                    payload.put("stream_options", new JSONObject().put("include_usage", true));
                    payload.put("max_tokens", 8192);
                    payload.put("reasoning", new JSONObject().put("effort", effort));
                    payload.put("providerOptions", new JSONObject().put("gateway",
                            new JSONObject().put("only", Providers.allowedSlugs())));

                    c = (HttpURLConnection) new URL(Providers.GATEWAY + "/v1/chat/completions").openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(180000);
                    c.setRequestProperty("Authorization", "Bearer " + apiKey);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    live.put(id, c);

                    int status = c.getResponseCode();
                    if (status != 200) {
                        InputStream es = c.getErrorStream();
                        String err = es == null ? "Request failed." : readAll(es);
                        endStream(id, status, err);
                        return;
                    }

                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        StringBuilder batch = new StringBuilder();
                        while ((line = r.readLine()) != null) {
                            batch.append(line).append('\n');
                            if (batch.length() > 2000 || line.isEmpty()) {
                                pushChunk(id, batch.toString());
                                batch.setLength(0);
                            }
                        }
                        if (batch.length() > 0) pushChunk(id, batch.toString());
                    }
                    endStream(id, 200, "");
                } catch (Exception e) {
                    endStream(id, 0, String.valueOf(e.getMessage()));
                } finally {
                    live.remove(id);
                    if (c != null) c.disconnect();
                }
            });
        }

        /** Stop a running reply */
        @JavascriptInterface
        public void cancel(int id) {
            HttpURLConnection c = live.remove(id);
            if (c != null) io.execute(c::disconnect);
        }

        /** Keep the screen on while a long goal runs, so Android does not pause the app */
        @JavascriptInterface
        public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Let the user pick ONE folder with the system folder picker; the assistant is confined to it */
        @JavascriptInterface
        public void pickFolder(int id) {
            main.post(() -> {
                try {
                    folderPickId = id;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(i, FOLDER_PICK);
                } catch (Exception e) {
                    folderPickId = -1;
                    deliver(id, 400, "Could not open the folder picker.");
                }
            });
        }

        /**
         * One step of the agent loop: a non-streaming chat call with tools. The provider allowlist
         * is added here, in the app, exactly as for the chat.
         */
        @JavascriptInterface
        public void agentStep(int id, String bodyJson, String apiKey) {
            io.execute(() -> {
                HttpURLConnection c = null;
                try {
                    JSONObject in = new JSONObject(bodyJson);
                    JSONArray messages = in.optJSONArray("messages");
                    JSONArray tools = in.optJSONArray("tools");
                    if (messages == null || messages.length() == 0) throw new Exception("No message to send.");
                    if (bodyJson.length() > MAX_PROMPT_CHARS * 2) throw new Exception("The conversation is too long. Start a new session.");
                    String effort = in.optString("effort", "none");
                    if (!effort.matches("none|low|high|max")) effort = "none";

                    // The screenshot modes use the vision model, only on its own provider; everything else the usual allowlist
                    boolean vision = in.optBoolean("vision", false);
                    JSONArray only = new JSONArray();
                    if (vision) { for (String p : Providers.SCREEN_PROVIDERS) only.put(p); } else only = Providers.allowedSlugs();

                    // Web search (opt-in): AI Gateway runs Parallel's search (US) and hands the results to the model
                    if (in.optBoolean("webSearch", false)) {
                        if (tools == null) tools = new JSONArray();
                        tools.put(new JSONObject().put("type", "vercel:parallel_search").put("config", searchConfig()));
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("model", vision ? Providers.SCREEN_MODEL : Providers.MODEL);
                    payload.put("messages", messages);
                    if (tools != null && tools.length() > 0) { payload.put("tools", tools); payload.put("tool_choice", "auto"); }
                    payload.put("max_tokens", 8192);
                    payload.put("reasoning", new JSONObject().put("effort", effort));
                    payload.put("providerOptions", new JSONObject().put("gateway", new JSONObject().put("only", only)));

                    c = (HttpURLConnection) new URL(Providers.GATEWAY + "/v1/chat/completions").openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(240000);
                    c.setRequestProperty("Authorization", "Bearer " + apiKey);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    live.put(id, c);
                    int status = c.getResponseCode();
                    InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    String text = is == null ? "" : readAll(is);
                    // Pass the provider's Retry-After along so the page can wait properly
                    if (status == 429 || status == 503) {
                        String retry = c.getHeaderField("Retry-After");
                        text = new JSONObject().put("retryAfter", retry == null ? "" : retry).put("body", text).toString();
                    }
                    deliver(id, status, text);
                } catch (Exception e) {
                    deliver(id, 0, String.valueOf(e.getMessage()));
                } finally {
                    live.remove(id);
                    if (c != null) c.disconnect();
                }
            });
        }

        /** File operations inside the chosen folder (see AgentFs) */
        /**
         * Reads one public web page for the agent's web_fetch tool: GET only, no cookies, at most 5 redirects (each checked
         * again), at most 3 MB. Addresses on this phone, the local network or other private ranges are refused, and so are
         * China's country domains. Plain http is tried as https (Android blocks unencrypted traffic).
         */
        @JavascriptInterface
        public void webFetch(int id, String json) {
            io.execute(() -> {
                HttpURLConnection c = null;
                try {
                    String url = new JSONObject(json).getString("url");
                    for (int hop = 0; ; hop++) {
                        URL u = new URL(url);
                        if ("http".equals(u.getProtocol())) u = new URL("https", u.getHost(), u.getPort() == 80 ? -1 : u.getPort(), u.getFile());
                        if (!"https".equals(u.getProtocol())) throw new Exception("Only http and https pages can be read.");
                        if (u.getUserInfo() != null) throw new Exception("Addresses with a user name or password are not read.");
                        checkPublicHost(u.getHost());
                        c = (HttpURLConnection) u.openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setUseCaches(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(20000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; BaleenCode web_fetch)");
                        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
                        c.setRequestProperty("Accept-Language", "ja,en;q=0.8");
                        int status = c.getResponseCode();
                        String loc = c.getHeaderField("Location");
                        if (status >= 300 && status < 400 && loc != null) {
                            if (hop >= 5) throw new Exception("Too many redirects.");
                            url = new URL(u, loc).toString();
                            c.disconnect(); c = null;
                            continue;
                        }
                        if (status >= 400) throw new Exception("The site answered with HTTP " + status + ".");
                        String type = c.getContentType() == null ? "" : c.getContentType();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        boolean cut = false;
                        try (InputStream is = c.getInputStream()) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = is.read(buf)) > 0) {
                                if (bo.size() + n > 3 * 1024 * 1024) { cut = true; break; }
                                bo.write(buf, 0, n);
                            }
                        }
                        byte[] raw = bo.toByteArray();
                        String cs = null;
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("charset=([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(type);
                        if (m.find()) cs = m.group(1);
                        if (cs == null) {
                            String head = new String(raw, 0, Math.min(raw.length, 2048), StandardCharsets.ISO_8859_1);
                            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("<meta[^>]+charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(head);
                            if (mm.find()) cs = mm.group(1);
                        }
                        String body;
                        try { body = new String(raw, java.nio.charset.Charset.forName(cs == null ? "UTF-8" : cs.equalsIgnoreCase("sjis") || cs.equalsIgnoreCase("x-sjis") ? "Shift_JIS" : cs)); }
                        catch (Exception e) { body = new String(raw, StandardCharsets.UTF_8); }
                        deliver(id, 200, new JSONObject().put("url", u.toString()).put("type", type).put("body", body).put("cut", cut).toString());
                        return;
                    }
                } catch (Exception e) {
                    deliver(id, 400, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void fsOp(int id, String json) {
            io.execute(() -> {
                try {
                    JSONObject a = new JSONObject(json);
                    Uri tree = Uri.parse(a.getString("uri"));
                    if (!DocumentsContract.isTreeUri(tree)) throw new Exception("Choose the folder again.");
                    boolean granted = false;
                    for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                        if (p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission()) { granted = true; break; }
                    }
                    if (!granted) throw new Exception("Access to the folder was lost. Choose the folder again.");
                    AgentFs fs = new AgentFs(getContentResolver(), tree);
                    String path = a.optString("path", "");
                    String out;
                    switch (a.getString("op")) {
                        case "exists": out = fs.exists(path); break;
                        case "list": out = fs.list(path); break;
                        case "read": {
                            AgentFs.Node n = fs.resolve(path);
                            if (n != null && !n.isDir() && AgentFs.isImage(n.name)) {
                                out = "[OCR text of the image]\n" + recognize(decodeSafely(fs.readImage(path)));
                            } else if (n != null && !n.isDir() && AgentFs.isOffice(n.name)) {
                                out = fs.readOffice(path, a.optInt("offset", 0));
                            } else if (n != null && !n.isDir() && n.name.toLowerCase().endsWith(".pdf")) {
                                out = readPdfPages(fs, path, a.optString("pages", ""));
                            } else {
                                out = fs.read(path, a.optInt("offset", 0));
                            }
                            break;
                        }
                        case "readAll": out = fs.readAll(path); break;
                        case "write": out = fs.write(path, a.getString("content")); break;
                        case "move": out = fs.move(a.getString("from"), a.getString("to")); break;
                        case "trash": out = fs.trash(path); break;
                        case "find": out = fs.find(path, a.getString("pattern")); break;
                        case "search": out = fs.search(path, a.getString("pattern"), a.optString("glob", ""), a.optBoolean("ignoreCase", false)); break;
                        default: throw new Exception("Unknown operation.");
                    }
                    deliver(id, 200, out);
                } catch (Exception e) {
                    deliver(id, 400, String.valueOf(e.getMessage()));
                }
            });
        }

        // ---------- the assistant's own browser (see setUpAgentWeb) ----------

        /** show / navigate / eval / state / back / shot / tap / key: always on the assistant's browser, never on this page */
        @JavascriptInterface
        public void browserOp(int id, String json) {
            main.post(() -> {
                try {
                    JSONObject a = new JSONObject(json);
                    switch (a.getString("op")) {
                        case "show":
                            agentWeb.setVisibility(a.optBoolean("on", false) ? View.VISIBLE : View.GONE);
                            deliver(id, 200, "ok");
                            break;
                        case "navigate": {
                            Uri u = Uri.parse(a.getString("url"));
                            if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) throw new Exception("Only http and https addresses can be opened.");
                            agentWeb.setVisibility(View.VISIBLE);
                            agentWeb.loadUrl(u.toString());
                            deliver(id, 200, "ok");
                            break;
                        }
                        case "eval":
                            agentWeb.evaluateJavascript(a.getString("script"), v -> deliver(id, 200, v == null ? "null" : v));
                            break;
                        case "state":
                            deliver(id, 200, new JSONObject().put("url", agentWeb.getUrl() == null ? "" : agentWeb.getUrl())
                                    .put("title", agentWeb.getTitle() == null ? "" : agentWeb.getTitle()).put("progress", agentWeb.getProgress())
                                    .put("canGoBack", agentWeb.canGoBack()).put("visible", agentWeb.getVisibility() == View.VISIBLE).toString());
                            break;
                        case "back":
                            if (!agentWeb.canGoBack()) throw new Exception("There is no previous page.");
                            agentWeb.goBack();
                            deliver(id, 200, "ok");
                            break;
                        case "shot":
                            deliver(id, 200, agentWebShot());
                            break;
                        case "tap": {
                            // x, y are page (CSS) pixels of the visible area
                            float d = getResources().getDisplayMetrics().density;
                            float x = (float) a.getDouble("x") * d, y = (float) a.getDouble("y") * d;
                            if (x < 0 || y < 0 || x > agentWeb.getWidth() || y > agentWeb.getHeight()) throw new Exception("That point is outside the page.");
                            long t = SystemClock.uptimeMillis();
                            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                            MotionEvent up = MotionEvent.obtain(t, t + (a.optBoolean("long", false) ? 700 : 60), MotionEvent.ACTION_UP, x, y, 0);
                            agentWeb.dispatchTouchEvent(down);
                            agentWeb.dispatchTouchEvent(up);
                            down.recycle();
                            up.recycle();
                            deliver(id, 200, "ok");
                            break;
                        }
                        case "key": {
                            Integer code = KEYS.get(a.getString("key"));
                            if (code == null) throw new Exception("Unknown key. Use Enter, Tab, Escape, Backspace, Delete, arrows, PageUp, PageDown, Home, End or Space.");
                            agentWeb.requestFocus();
                            agentWeb.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                            agentWeb.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                            deliver(id, 200, "ok");
                            break;
                        }
                        default:
                            throw new Exception("Unknown operation.");
                    }
                } catch (Exception e) {
                    deliver(id, 400, String.valueOf(e.getMessage()));
                }
            });
        }

        // ---------- device control (see DeviceControlService) ----------

        /** "running" when the user has turned the accessibility service on, otherwise "off" */
        @JavascriptInterface
        public void deviceStatus(int id) {
            deliver(id, 200, DeviceControlService.running() ? "running" : "off");
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            main.post(() -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
            });
        }

        /** Apps the user can pick for device control */
        @JavascriptInterface
        public void listApps(int id) {
            io.execute(() -> {
                try {
                    deliver(id, 200, launcherApps().toString());
                } catch (Exception e) {
                    deliver(id, 500, "[]");
                }
            });
        }

        /** snapshot / act / shot / open — always for the one app the user chose */
        @JavascriptInterface
        public void deviceOp(int id, String json) {
            io.execute(() -> {
                try {
                    DeviceControlService s = DeviceControlService.instance;
                    if (s == null) throw new Exception("Accessibility access is off. Turn on \"BaleenCode\" in Settings > Accessibility.");
                    JSONObject a = new JSONObject(json);
                    String pkg = a.getString("pkg");
                    if (pkg.equals(getPackageName()) || !isLauncherApp(pkg)) throw new Exception("That app cannot be controlled.");
                    String out;
                    switch (a.getString("op")) {
                        case "snapshot": out = s.snapshot(pkg); break;
                        case "act": out = s.act(pkg, a.optString("path", ""), a.optString("expect", ""), a.getString("action"),
                                a.optString("text", ""), a.optInt("amount", 3)); break;
                        case "shot": out = s.screenshot(pkg); break;
                        case "probe": out = s.probe(pkg, a.getDouble("x"), a.getDouble("y"), a.getInt("w"), a.getInt("h")); break;
                        case "tap": out = s.tap(pkg, a.getDouble("x"), a.getDouble("y"), a.getInt("w"), a.getInt("h"), a.optBoolean("long", false)); break;
                        case "swipe": out = s.swipe(pkg, a.getDouble("x1"), a.getDouble("y1"), a.getDouble("x2"), a.getDouble("y2"),
                                a.getInt("w"), a.getInt("h"), a.optInt("ms", 400)); break;
                        case "typefocused": out = s.typeFocused(pkg, a.getString("text"), a.optBoolean("allowPassword", false)); break;
                        case "open": DeviceControlService.openApp(MainActivity.this, pkg); out = "ok"; break;
                        default: throw new Exception("Unknown operation.");
                    }
                    deliver(id, 200, out);
                } catch (Exception e) {
                    deliver(id, 400, String.valueOf(e.getMessage()));
                }
            });
        }

        /** Open a PDF and return its page count (pages are then OCR'd one at a time) */
        @JavascriptInterface
        public void pdfPrep(int id, String base64) {
            io.execute(() -> {
                try {
                    closePdf();
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    File f = new File(getCacheDir(), "input.pdf");
                    try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                    pdfFd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    pdfRenderer = new PdfRenderer(pdfFd);
                    if (pdfRenderer.getPageCount() > MAX_PDF_PAGES) {
                        closePdf();
                        throw new Exception("PDFs with more than " + MAX_PDF_PAGES + " pages are not supported.");
                    }
                    deliver(id, 200, String.valueOf(pdfRenderer.getPageCount()));
                } catch (Exception e) {
                    deliver(id, 500, "Could not open the PDF. " + e.getMessage());
                }
            });
        }

        /** Render one PDF page and read its text */
        @JavascriptInterface
        public void pdfPage(int id, int pageNumber) {
            io.execute(() -> {
                try {
                    if (pdfRenderer == null) throw new Exception("No PDF is open.");
                    String text;
                    synchronized (MainActivity.this) {
                        PdfRenderer.Page page = pdfRenderer.openPage(pageNumber - 1);
                        int width = Math.min(2200, page.getWidth() * 3);
                        long h = (long) width * page.getHeight() / Math.max(1, page.getWidth());
                        if (h > MAX_IMAGE_SIDE * 2) {
                            width = (int) Math.max(1, (long) width * MAX_IMAGE_SIDE * 2 / h);
                            h = MAX_IMAGE_SIDE * 2;
                        }
                        Bitmap bitmap = Bitmap.createBitmap(width, (int) Math.max(1, h), Bitmap.Config.ARGB_8888);
                        new Canvas(bitmap).drawColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                        text = recognize(bitmap);
                        bitmap.recycle();
                    }
                    deliver(id, 200, text);
                } catch (Exception e) {
                    deliver(id, 500, "Could not read the page. " + e.getMessage());
                }
            });
        }

        /** Read text from an image (base64). Runs on-device; the image is never uploaded */
        @JavascriptInterface
        public void ocr(int id, String base64) {
            io.execute(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    deliver(id, 200, recognize(decodeSafely(data)));
                } catch (Exception e) {
                    deliver(id, 500, "Could not read text from the image. " + e.getMessage());
                }
            });
        }
    }

    private JSONArray launcherApps() throws Exception {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(main, 0);
        java.util.TreeMap<String, String> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            byName.put(String.valueOf(ri.loadLabel(pm)) + "\u0000" + pkg, pkg);
        }
        JSONArray arr = new JSONArray();
        for (java.util.Map.Entry<String, String> e : byName.entrySet()) {
            if (arr.length() >= 300) break;
            arr.put(new JSONObject().put("name", e.getKey().substring(0, e.getKey().indexOf('\u0000'))).put("pkg", e.getValue()));
        }
        return arr;
    }

    private boolean isLauncherApp(String pkg) {
        try {
            JSONArray arr = launcherApps();
            for (int i = 0; i < arr.length(); i++) if (arr.getJSONObject(i).getString("pkg").equals(pkg)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Reads pages of a PDF with on-device OCR ("1-5" style; at most 10 pages per call) */
    private String readPdfPages(AgentFs fs, String path, String spec) throws Exception {
        ParcelFileDescriptor fd = fs.openPdf(path);
        PdfRenderer renderer = null;
        try {
            renderer = new PdfRenderer(fd);
            int total = renderer.getPageCount();
            if (total > MAX_PDF_PAGES) throw new Exception("PDFs with more than " + MAX_PDF_PAGES + " pages are not supported.");
            int from = 1, to = Math.min(total, 5);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*(?:-\\s*(\\d+))?\\s*$").matcher(spec == null ? "" : spec);
            if (m.find()) {
                from = Math.max(1, Math.min(total, Integer.parseInt(m.group(1))));
                to = m.group(2) == null ? from : Math.max(from, Math.min(total, Integer.parseInt(m.group(2))));
            }
            if (to - from + 1 > 10) to = from + 9;
            StringBuilder sb = new StringBuilder("[PDF, " + total + " pages, read with OCR on this device; showing pages " + from + "-" + to + "]\n");
            for (int p = from; p <= to; p++) {
                PdfRenderer.Page page = renderer.openPage(p - 1);
                int width = Math.min(2200, page.getWidth() * 3);
                long h = (long) width * page.getHeight() / Math.max(1, page.getWidth());
                if (h > MAX_IMAGE_SIDE * 2) { width = (int) Math.max(1, (long) width * MAX_IMAGE_SIDE * 2 / h); h = MAX_IMAGE_SIDE * 2; }
                Bitmap bitmap = Bitmap.createBitmap(width, (int) Math.max(1, h), Bitmap.Config.ARGB_8888);
                new Canvas(bitmap).drawColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                sb.append("--- page ").append(p).append(" ---\n").append(recognize(bitmap).trim()).append("\n\n");
                bitmap.recycle();
            }
            if (to < total) sb.append("[Call again with pages=\"").append(to + 1).append("-").append(Math.min(total, to + 10)).append("\" for more.]");
            return sb.toString().trim();
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
            try { fd.close(); } catch (Exception ignored) { }
        }
    }

    /** Decode an image without ever allocating a huge bitmap: check the size first, then downscale */
    private static Bitmap decodeSafely(byte[] data) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("Could not decode the image.");
        if ((long) bounds.outWidth * bounds.outHeight > MAX_IMAGE_PIXELS) {
            throw new Exception("The image dimensions are too large.");
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > MAX_IMAGE_SIDE) opts.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bitmap == null) throw new Exception("Could not decode the image.");
        return bitmap;
    }

    /** On-device text recognition (the Japanese model also reads Latin script) */
    private static String recognize(Bitmap bitmap) throws Exception {
        TextRecognizer recognizer =
                TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
        return result.getText();
    }

    private synchronized void closePdf() {
        try { if (pdfRenderer != null) pdfRenderer.close(); } catch (Exception ignored) {}
        try { if (pdfFd != null) pdfFd.close(); } catch (Exception ignored) {}
        pdfRenderer = null;
        pdfFd = null;
    }

    private void deliver(int id, int status, String text) {
        run("window.__dsDone(" + id + "," + status + "," + JSONObject.quote(text) + ")");
    }

    private void pushChunk(int id, String text) {
        run("window.__dsChunk(" + id + "," + JSONObject.quote(text) + ")");
    }

    private void endStream(int id, int status, String text) {
        run("window.__dsEnd(" + id + "," + status + "," + JSONObject.quote(text) + ")");
    }

    private void run(String js) {
        main.post(() -> webView.evaluateJavascript(js, null));
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        return out.toString("UTF-8");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        closePdf();
        io.shutdownNow();
        webView.destroy();
        super.onDestroy();
    }
}
