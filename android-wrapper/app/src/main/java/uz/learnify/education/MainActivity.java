package uz.learnify.education;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String HOME = "https://learnify-education.lovable.app/";
    private static final String HOST = "learnify-education.lovable.app";
    private static final int FILE_REQUEST = 10;
    private static final int SAVE_REQUEST = 11;
    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    private WebView webView;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private ValueCallback<Uri[]> uploadCallback;
    private volatile String bridgeToken = "";
    private byte[] pendingDownload;

    private static boolean trusted(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new FrameLayout.LayoutParams(-1, 6, Gravity.TOP));
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(40, 40, 40, 40);
        errorPanel.setBackgroundColor(Color.WHITE);
        TextView message = new TextView(this);
        message.setText("Sahifa yuklanmadi. Internet aloqangizni tekshiring.");
        message.setGravity(Gravity.CENTER);
        message.setTextSize(18);
        errorPanel.addView(message);
        Button retry = new Button(this);
        retry.setText("Qayta urinish");
        retry.setOnClickListener(v -> { errorPanel.setVisibility(View.GONE); webView.reload(); });
        errorPanel.addView(retry);
        errorPanel.setVisibility(View.GONE);
        root.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new DownloadBridge(), "LearnifyFiles");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (trusted(request.getUrl().toString())) return false;
                openExternal(request.getUrl());
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                bridgeToken = trusted(url) ? UUID.randomUUID().toString() : "";
                errorPanel.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (trusted(url)) installDownloads();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    errorPanel.setVisibility(View.VISIBLE);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!trusted(view.getUrl())) return false;
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                } catch (ActivityNotFoundException error) {
                    uploadCallback.onReceiveValue(null);
                    uploadCallback = null;
                    notifyUser("Fayl tanlash ilovasi topilmadi.");
                }
                return true;
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
                if (!gesture) return false;
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        if (trusted(url)) webView.loadUrl(url); else openExternal(request.getUrl());
                        child.post(child::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(popup);
                result.sendToTarget();
                return true;
            }
        });
        webView.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (!trusted(webView.getUrl())) return;
            String name = URLUtil.guessFileName(url, disposition, mime);
            if (trusted(url) || url.startsWith("blob:" + HOME) || url.startsWith("data:")) {
                webView.evaluateJavascript("window.__learnifyDownload && window.__learnifyDownload("
                    + JSONObject.quote(url) + "," + JSONObject.quote(name) + ");", null);
            } else {
                openExternal(Uri.parse(url));
            }
        });
        if (state == null || webView.restoreState(state) == null) webView.loadUrl(HOME);
    }

    // The per-page token prevents foreign iframes from calling the file bridge.
    private void installDownloads() {
        String script = "(() => { if (window.__learnifyDownload) return; const token = " + JSONObject.quote(bridgeToken) + ";"
            + "const blobs = new Map(); const create = URL.createObjectURL.bind(URL); const revoke = URL.revokeObjectURL.bind(URL);"
            + "URL.createObjectURL = blob => { const url = create(blob); blobs.set(url, blob); setTimeout(() => blobs.delete(url), 60000); return url; };"
            + "URL.revokeObjectURL = url => { setTimeout(() => { blobs.delete(url); revoke(url); }, 60000); };"
            + "window.__learnifyDownload = async (url, name) => { try { const blob = blobs.get(url) || await (await fetch(url)).blob();"
            + "if (blob.size > 26214400) throw new Error('large'); const reader = new FileReader();"
            + "reader.onload = () => { LearnifyFiles.save(token, name || 'learnify-file', blob.type || 'application/octet-stream', String(reader.result).split(',')[1]); blobs.delete(url); };"
            + "reader.onerror = () => LearnifyFiles.error(token); reader.readAsDataURL(blob); } catch (error) { LearnifyFiles.error(token); } };"
            + "document.addEventListener('click', event => { const anchor = event.target.closest && event.target.closest('a[download]');"
            + "if (anchor && anchor.href.startsWith('blob:')) { event.preventDefault(); window.__learnifyDownload(anchor.href, anchor.download); } }, true);"
            + "})();";
        webView.evaluateJavascript(script, null);
    }

    private final class DownloadBridge {
        @JavascriptInterface public void save(String token, String name, String mime, String encoded) {
            if (token == null || bridgeToken.isEmpty() || !bridgeToken.equals(token)) return;
            if (encoded == null || encoded.length() > ((MAX_FILE_BYTES + 2) / 3) * 4) {
                notifyUser("Fayl 25 MB dan oshmasligi kerak.");
                return;
            }
            try {
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                if (bytes.length > MAX_FILE_BYTES) { notifyUser("Fayl juda katta."); return; }
                String safeName = name == null ? "learnify-file" : name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
                if (safeName.length() > 120) safeName = safeName.substring(safeName.length() - 120);
                final String filename = safeName.isEmpty() ? "learnify-file" : safeName;
                final String type = mime != null && mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? mime : "application/octet-stream";
                runOnUiThread(() -> {
                    if (!trusted(webView.getUrl()) || !bridgeToken.equals(token)) return;
                    if (pendingDownload != null) { notifyUser("Avval oldingi faylni saqlang."); return; }
                    pendingDownload = bytes;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(type).putExtra(Intent.EXTRA_TITLE, filename);
                    try { startActivityForResult(intent, SAVE_REQUEST); }
                    catch (ActivityNotFoundException error) { pendingDownload = null; notifyUser("Fayl saqlash ilovasi topilmadi."); }
                });
            } catch (IllegalArgumentException error) { notifyUser("Faylni yuklab bo‘lmadi."); }
        }
        @JavascriptInterface public void error(String token) {
            if (token != null && !bridgeToken.isEmpty() && bridgeToken.equals(token))
                notifyUser("Faylni yuklab bo‘lmadi. Internetni va fayl hajmini (25 MB gacha) tekshiring.");
        }
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"mailto".equalsIgnoreCase(scheme) && !"tel".equalsIgnoreCase(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException error) { notifyUser("Havolani ochadigan ilova topilmadi."); }
    }

    private void notifyUser(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FILE_REQUEST && uploadCallback != null) {
            uploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            uploadCallback = null;
        }
        if (request == SAVE_REQUEST && pendingDownload != null) {
            byte[] bytes = pendingDownload;
            pendingDownload = null;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                new Thread(() -> {
                    try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                        if (output == null) throw new java.io.IOException("No output stream");
                        output.write(bytes);
                        notifyUser("Fayl saqlandi.");
                    } catch (Exception error) { notifyUser("Faylni saqlab bo‘lmadi."); }
                }).start();
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        webView.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onDestroy() {
        if (uploadCallback != null) uploadCallback.onReceiveValue(null);
        bridgeToken = "";
        webView.removeJavascriptInterface("LearnifyFiles");
        webView.destroy();
        super.onDestroy();
    }
}
