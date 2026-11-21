package id.rizkon.academypronova;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    // Halaman pembungkus di GitHub Pages (tanpa banner Apps Script).
    private static final String APP_URL = "https://rizkonfor.github.io/ssb/";
    private static final int FILE_REQUEST = 7;
    private static final long EXIT_WINDOW_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private long lastBackAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1526"));
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " AcademyProNovaApp/1.0");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        try {
            NotifJobService.ensureChannel(this);
        } catch (Throwable t) {
            android.util.Log.e("SSB", "Kanal notifikasi gagal dibuat", t);
        }
        web.addJavascriptInterface(new NotifBridge(), "SSBAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                boolean isWeb = scheme.equals("http") || scheme.equals("https");
                if (isWeb && !request.isForMainFrame()) {
                    return false;
                }
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (isWeb && host.endsWith("rizkonfor.github.io")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // Tidak ada aplikasi yang bisa membuka tautan ini.
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Uji otomatis di emulator (GitHub Actions): daftarkan tugas notifikasi tanpa login.
        if (getIntent() != null && getIntent().getBooleanExtra("ssb_selftest", false)) {
            try {
                NotifJobService.schedule(this);
                android.util.Log.i("SSB", "SSB_SELFTEST_OK");
            } catch (Throwable t) {
                android.util.Log.e("SSB", "SSB_SELFTEST_FAIL", t);
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            // Kalau sudah di halaman awal, aplikasi web menampilkan
            // "Tekan Kembali sekali lagi", jadi tekan berikutnya langsung keluar.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!web.canGoBack()) {
                        lastBackAt = System.currentTimeMillis();
                    }
                }
            }, 400);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish();
            return;
        }
        lastBackAt = now;
        Toast.makeText(this, "Tekan Kembali sekali lagi untuk keluar", Toast.LENGTH_SHORT).show();
    }

    /** Dipanggil halaman pembungkus saat pengguna masuk atau keluar akun. */
    public class NotifBridge {
        @JavascriptInterface
        public void setNotif(String execUrl, String token, String role) {
            try {
                applyNotif(execUrl, token, role);
            } catch (Throwable t) {
                android.util.Log.e("SSB", "Pengaturan notifikasi gagal", t);
            }
        }

        private void applyNotif(String execUrl, String token, String role) {
            final boolean on = token != null && token.length() >= 24
                    && execUrl != null && execUrl.startsWith("https://script.google.com/");
            SharedPreferences sp = getSharedPreferences(NotifJobService.PREFS, MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            if (on) {
                if (!token.equals(sp.getString("token", ""))) {
                    ed.putLong("registered", System.currentTimeMillis());
                    ed.putLong("maxAt", 0L);
                    ed.putString("seen", "");
                }
                ed.putString("url", execUrl).putString("token", token).putString("role", role == null ? "" : role);
            } else {
                ed.remove("token");
            }
            ed.apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (on) {
                            NotifJobService.schedule(MainActivity.this);
                            askNotificationPermission();
                        } else {
                            NotifJobService.cancel(MainActivity.this);
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("SSB", "Jadwal notifikasi gagal", t);
                    }
                }
            });
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
