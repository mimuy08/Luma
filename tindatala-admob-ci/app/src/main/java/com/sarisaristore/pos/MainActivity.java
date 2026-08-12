package com.sarisaristore.pos;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 3101;
    private static final int REQ_FILE = 3102;
    private static final String HOME = "file:///android_asset/www/index.html";
    private static final String TEST_BANNER = "ca-app-pub-3940256099942544/9214589741";

    private LinearLayout root;
    private WebView web;
    private FrameLayout adBox;
    private AdView ad;
    private boolean adLoaded;
    private boolean keyboardVisible;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingCameraRequest;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        web = new WebView(this);
        adBox = new FrameLayout(this);
        adBox.setVisibility(View.GONE);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(adBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        applyInsetsAndKeyboardWatcher();
        configureWebView();
        watchNetwork();
        MobileAds.initialize(this, ignored -> runOnUiThread(this::loadAd));
        web.loadUrl(HOME);
    }

    private void applyInsetsAndKeyboardWatcher() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private final Rect r = new Rect();
            @Override public void onGlobalLayout() {
                root.getWindowVisibleDisplayFrame(r);
                int h = root.getRootView().getHeight();
                boolean shown = Math.max(0, h - r.bottom) > h * 0.15f;
                if (shown != keyboardVisible) { keyboardVisible = shown; updateAdVisibility(); }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        Bridge bridge = new Bridge();
        for (String name : new String[]{"Android","AndroidBridge","NativeBridge","SariStoreNative","TindaTalaNative","ReceiptShareBridge"}) {
            web.addJavascriptInterface(bridge, name);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return externalIfNeeded(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return externalIfNeeded(Uri.parse(url)); }
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("window.__TINDATALA_NATIVE__=true;", null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                boolean camera = false;
                for (String item : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(item)) camera = true;
                if (!camera) { request.deny(); return; }
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingCameraRequest = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), REQ_FILE); }
                catch (Exception e) {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
                    startActivityForResult(i, REQ_FILE);
                }
                return true;
            }
        });
    }

    private boolean externalIfNeeded(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (scheme.equals("file") || scheme.equals("http") || scheme.equals("https") || scheme.equals("about")) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); return true; } catch (Exception e) { return false; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = resultCode == RESULT_OK && data != null && data.getData() != null ? new Uri[]{data.getData()} : null;
            fileCallback.onReceiveValue(result); fileCallback = null;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && pendingCameraRequest != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pendingCameraRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingCameraRequest.deny();
            pendingCameraRequest = null;
        }
    }

    private void watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { runOnUiThread(() -> { if (!adLoaded) loadAd(); updateAdVisibility(); }); }
            @Override public void onLost(Network n) { runOnUiThread(MainActivity.this::updateAdVisibility); }
        };
        try { cm.registerDefaultNetworkCallback(networkCallback); } catch (Exception ignored) {}
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void loadAd() {
        if (!online() || adLoaded) { updateAdVisibility(); return; }
        adBox.post(() -> {
            if (!online()) return;
            if (ad != null) { try { ad.destroy(); } catch (Exception ignored) {} adBox.removeAllViews(); }
            int px = adBox.getWidth() > 0 ? adBox.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int widthDp = Math.max(320, (int)(px / getResources().getDisplayMetrics().density));
            ad = new AdView(this);
            ad.setAdUnitId(TEST_BANNER);
            ad.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp));
            ad.setAdListener(new AdListener() {
                @Override public void onAdLoaded() { adLoaded = true; updateAdVisibility(); }
                @Override public void onAdFailedToLoad(LoadAdError error) { adLoaded = false; updateAdVisibility(); }
            });
            adBox.addView(ad, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ad.loadAd(new AdRequest.Builder().build());
        });
    }

    private void updateAdVisibility() {
        adBox.setVisibility(adLoaded && online() && !keyboardVisible ? View.VISIBLE : View.GONE);
    }

    private byte[] decodeDataUrl(String url) throws Exception {
        int comma = url.indexOf(','); if (comma < 0) throw new IllegalArgumentException();
        String meta = url.substring(0, comma), data = url.substring(comma + 1);
        if (meta.contains(";base64")) return Base64.decode(data, Base64.DEFAULT);
        return URLDecoder.decode(data, "UTF-8").getBytes(StandardCharsets.UTF_8);
    }

    private String safeName(String name) {
        if (name == null || name.isEmpty()) name = "TindaTala-file";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void shareData(String dataUrl, String filename, String mime, String title) {
        try {
            File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
            File f = new File(dir, safeName(filename));
            try (OutputStream out = new FileOutputStream(f)) { out.write(decodeDataUrl(dataUrl)); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND); i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
            i.putExtra(Intent.EXTRA_STREAM, uri); i.setClipData(ClipData.newRawUri(f.getName(), uri)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, title == null || title.isEmpty() ? "Share" : title));
            web.postDelayed(f::delete, 120000);
        } catch (Exception e) { toast("Could not share file."); }
    }

    private void saveData(String dataUrl, String filename, String mime) {
        try {
            byte[] bytes = decodeDataUrl(dataUrl); filename = safeName(filename);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues(); cv.put(MediaStore.Downloads.DISPLAY_NAME, filename); cv.put(MediaStore.Downloads.MIME_TYPE, mime); cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TindaTala");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(bytes); }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); dir.mkdirs();
                try (OutputStream out = new FileOutputStream(new File(dir, filename))) { out.write(bytes); }
            }
            toast("Saved to Downloads");
        } catch (Exception e) { toast("Could not save file."); }
    }

    private void printPage() {
        runOnUiThread(() -> {
            try { ((PrintManager)getSystemService(Context.PRINT_SERVICE)).print("TindaTala", web.createPrintDocumentAdapter("TindaTala"), new PrintAttributes.Builder().build()); }
            catch (Exception e) { toast("Printing is not available."); }
        });
    }

    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }

    public class Bridge {
        @JavascriptInterface public boolean isNativeApp() { return true; }
        @JavascriptInterface public String getVersion() { return "1.3.2-admob-test"; }
        @JavascriptInterface public String getAppVersion() { return "1.3.2-admob-test"; }
        @JavascriptInterface public void print() { printPage(); }
        @JavascriptInterface public void printPage() { MainActivity.this.printPage(); }
        @JavascriptInterface public void shareFile(String d, String f, String m, String t) { shareData(d,f,m,t); }
        @JavascriptInterface public void share(String d, String f, String m, String t) { shareData(d,f,m,t); }
        @JavascriptInterface public void shareReceipt(String d, String f, String m, String t) { shareData(d,f,m,t); }
        @JavascriptInterface public void downloadFile(String d, String f, String m) { saveData(d,f,m); }
        @JavascriptInterface public void saveFile(String d, String f, String m) { saveData(d,f,m); }
        @JavascriptInterface public void saveDataUrl(String d, String f, String m) { saveData(d,f,m); }
    }

    @Override protected void onResume() { super.onResume(); web.onResume(); if (ad != null) ad.resume(); if (!adLoaded && online()) loadAd(); }
    @Override protected void onPause() { if (ad != null) ad.pause(); web.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (networkCallback != null) try { ((ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        if (ad != null) ad.destroy(); if (web != null) web.destroy(); super.onDestroy();
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
