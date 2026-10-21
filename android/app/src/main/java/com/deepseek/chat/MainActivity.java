package com.deepseek.chat;

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
import android.view.WindowInsets;
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
import java.net.URL;
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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);
        root.addView(webView);
        setContentView(root);

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

                    JSONObject payload = new JSONObject();
                    payload.put("model", Providers.MODEL);
                    payload.put("messages", messages);
                    if (tools != null && tools.length() > 0) { payload.put("tools", tools); payload.put("tool_choice", "auto"); }
                    payload.put("max_tokens", 8192);
                    payload.put("reasoning", new JSONObject().put("effort", effort));
                    payload.put("providerOptions", new JSONObject().put("gateway",
                            new JSONObject().put("only", Providers.allowedSlugs())));

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
                    if (s == null) throw new Exception("Accessibility access is off. Turn on \"DeepSeek Agent\" in Settings > Accessibility.");
                    JSONObject a = new JSONObject(json);
                    String pkg = a.getString("pkg");
                    if (pkg.equals(getPackageName()) || !isLauncherApp(pkg)) throw new Exception("That app cannot be controlled.");
                    String out;
                    switch (a.getString("op")) {
                        case "snapshot": out = s.snapshot(pkg); break;
                        case "act": out = s.act(pkg, a.optString("path", ""), a.optString("expect", ""), a.getString("action"),
                                a.optString("text", ""), a.optInt("amount", 3)); break;
                        case "shot": out = s.screenshot(pkg); break;
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
