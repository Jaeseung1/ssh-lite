package io.github.jaeseung1.sshlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 화면은 PC 앱과 같은 index.html 을 WebView 로 띄움. 화면이 부르는 기능은 AndroidBridge.call 로 받아서 Core 에서 실행.
 * 화면 파일은 https://sshlite.local/ 주소로 앱 안의 assets/ui 에서 바로 내줌 (인터넷에 나가지 않음).
 */
public class MainActivity extends Activity implements Core.Platform {
    private static final String HOST = "sshlite.local";
    private static final int PICK_FILES = 1;
    private static final int BG = 0xFF0E1014, PANEL = 0xFF14171D;

    private WebView web;
    private Core core;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Uri> downloads = new ConcurrentHashMap<>();     // 알림에 보인 위치 -> 받은 파일
    private final Map<String, String> downloadNames = new ConcurrentHashMap<>();  // 알림에 보인 위치 -> 파일 이름 (열 때 종류를 알려고)
    private volatile CountDownLatch pickWait;
    private volatile List<String> picked = new ArrayList<>();

    /** 터미널 입력: 추천 단어·자동 고침을 끔 (명령이 바뀌거나, 띄어쓰기 전까지 글자가 안 들어가는 문제 방지) */
    static class TermWebView extends WebView {
        TermWebView(Context c) { super(c); }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo out) {
            InputConnection ic = super.onCreateInputConnection(out);
            if ((out.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
                out.inputType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                out.inputType &= ~(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            }
            out.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            return ic;
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        getWindow().setStatusBarColor(PANEL);
        getWindow().setNavigationBarColor(PANEL);
        showLastCrash();
        try {
            core = new Core(this);
        } catch (Exception e) {
            Toast.makeText(this, "시작하지 못했어요: " + e, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        web = new TermWebView(this);
        web.setBackgroundColor(BG);
        // Android 15+ 를 대상으로 하면 화면이 상태 줄·내비게이션 줄 밑까지 그려짐 -> 그만큼 안쪽으로 (키보드가 올라오면 키보드 위까지)
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(PANEL);
        root.addView(web);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] p = barsAndKeyboard(insets);
            v.setPadding(p[0], p[1], p[2], p[3]);
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        registerBack();
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setTextZoom(100);                 // 폰 글꼴 크기 설정 때문에 화면이 깨지지 않게
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null || u.getPath().equals("/") ? "/index.html" : u.getPath();
                if (path.contains("..")) return null;
                try {
                    return new WebResourceResponse(mimeOf(path), "UTF-8", getAssets().open("ui" + path));
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                }
            }

            @Override
            @SuppressWarnings("deprecation")  // 새 폰에서도 WebView 가 이 형태를 불러줌 (API 23 기준으로 빌드)
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (HOST.equals(Uri.parse(url).getHost())) return false;
                openUrl(url);  // 다른 주소는 브라우저로
                return true;
            }
        });
        web.loadUrl("https://" + HOST + "/index.html");
    }

    /** 지난번에 앱이 멈췄으면 그 이유를 보여줌 (복사해서 보내주면 고칠 수 있게) */
    private void showLastCrash() {
        File f = App.crashFile(this);
        if (!f.exists()) return;
        String text;
        try {
            text = Core.readFile(f);
        } catch (IOException e) {
            text = String.valueOf(e);
        }
        f.delete();
        final String report = text;
        android.widget.TextView body = new android.widget.TextView(this);
        body.setText(report);
        body.setTextIsSelectable(true);
        body.setTextSize(11);
        body.setPadding(40, 20, 40, 20);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("지난번에 앱이 멈췄어요")
                .setMessage("아래 내용을 복사해서 보내주시면 고칠 수 있어요.")
                .setView(scroll)
                .setPositiveButton("복사", (d, w) -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("SSH Lite 오류", report));
                    Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    /** 상태 줄·내비게이션 줄·카메라 구멍·키보드가 차지하는 크기 {왼, 위, 오른, 아래}.
     *  Android 11+ 는 WindowInsets.getInsets(systemBars | displayCutout | ime) (API 23 기준으로 빌드해서 리플렉션) */
    private static int[] barsAndKeyboard(android.view.WindowInsets insets) {
        try {
            Class<?> type = Class.forName("android.view.WindowInsets$Type");
            int mask = (int) type.getMethod("systemBars").invoke(null) | (int) type.getMethod("displayCutout").invoke(null)
                    | (int) type.getMethod("ime").invoke(null);
            Object in = android.view.WindowInsets.class.getMethod("getInsets", int.class).invoke(insets, mask);
            Class<?> c = in.getClass();
            return new int[]{c.getField("left").getInt(in), c.getField("top").getInt(in), c.getField("right").getInt(in), c.getField("bottom").getInt(in)};
        } catch (Exception e) {
            return new int[]{insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
        }
    }

    private static String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** 화면(JS)이 부르는 입구: 기능 이름, 인자(JSON 배열), 답을 돌려줄 번호 */
    class Bridge {
        /** 길게 누르기가 됐다는 짧은 진동 (권한 필요 없는 화면 진동) */
        /** 키 줄을 누를 때 아주 짧은 진동 */
        @JavascriptInterface
        public void tap() {
            ui.post(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP));
        }

        @JavascriptInterface
        public void haptic() {
            ui.post(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS));
        }

        @JavascriptInterface
        public void call(final String name, final String args, final int id) {
            pool.execute(() -> {
                String json;
                try {
                    Object r = core.call(name, new JSONArray(args));
                    afterCall(name, r);
                    json = toJson(r);
                } catch (Throwable e) {
                    try {
                        json = new JSONObject().put("error", Core.friendly(e)).toString();
                    } catch (Exception e2) {
                        json = "null";
                    }
                }
                final String js = "window.__bridgeResolve && __bridgeResolve(" + id + "," + json + ")";
                ui.post(() -> web.evaluateJavascript(js, null));
            });
        }
    }

    /** 연결되면 '연결 유지' 알림(포그라운드 서비스)을 띄우고, 끊으면 내림 */
    private void afterCall(String name, Object r) {
        if (name.equals("connect") && r instanceof JSONObject && ((JSONObject) r).optBoolean("ok")) {
            final String label = ((JSONObject) r).optString("label");
            ui.post(() -> {
                askNotificationPermission();
                KeepAliveService.start(this, label);
            });
        } else if (name.equals("disconnect")) {
            ui.post(() -> KeepAliveService.stop(this));
        }
    }

    /** Android 13+ 는 알림을 띄우려면 허락이 필요 (거절해도 연결 유지는 됨, 알림만 안 보임) */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    private static String toJson(Object r) {
        if (r == null) return "null";
        if (r instanceof String) return JSONObject.quote((String) r);
        return r.toString();
    }

    @Override
    public void onBackPressed() {  // Android 12 까지
        handleBack();
    }

    /** 서랍·메뉴·대화상자가 열려 있으면 그것부터 닫음. 없으면 앱을 뒤로 보내기만 함 (연결은 그대로) */
    private void handleBack() {
        web.evaluateJavascript("window.__onBack ? __onBack() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    /** Android 13+ 의 뒤로 가기 (Android 16 을 대상으로 하면 onBackPressed 가 불리지 않음).
     *  API 23 기준으로 빌드해서 리플렉션 + Proxy 로 OnBackInvokedCallback 을 만듦 */
    private void registerBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            Class<?> cbType = Class.forName("android.window.OnBackInvokedCallback");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "onBackInvoked": ui.post(this::handleBack); return null;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "SSH Lite 뒤로 가기";
                    default: return null;
                }
            });
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
            Class.forName("android.window.OnBackInvokedDispatcher")
                    .getMethod("registerOnBackInvokedCallback", int.class, cbType).invoke(dispatcher, 0, cb);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        KeepAliveService.stop(this);
        if (core != null) core.shutdown();
        pool.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Core.Platform

    @Override
    public void emit(String kind, Object data) {
        try {
            final String js = "window.app && app.onEvent(" + new JSONObject().put("type", kind).put("data", data == null ? JSONObject.NULL : data) + ")";
            ui.post(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {
        }
    }

    @Override
    public File dataDir() { return getFilesDir(); }

    @Override
    public String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String deviceName() { return Build.MODEL; }

    /** UI 스레드에서 실행하고 끝날 때까지 기다림 */
    private <T> T onUi(java.util.concurrent.Callable<T> work) {
        final Object[] out = new Object[1];
        final CountDownLatch done = new CountDownLatch(1);
        ui.post(() -> {
            try {
                out[0] = work.call();
            } catch (Exception ignored) {
            }
            done.countDown();
        });
        try {
            done.await();
        } catch (InterruptedException ignored) {
        }
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    @Override
    public String clipboardGet() {
        String s = onUi(() -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData c = cm.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return "";
            CharSequence t = c.getItemAt(0).coerceToText(this);
            return t == null ? "" : t.toString();
        });
        return s == null ? "" : s;
    }

    @Override
    public boolean clipboardSet(String text) {
        Boolean ok = onUi(() -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("SSH Lite", text));
            return true;
        });
        return ok != null && ok;
    }

    @Override
    public void openUrl(String url) {
        ui.post(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
            }
        });
    }

    @Override
    public List<String> pickFiles() {
        pickWait = new CountDownLatch(1);
        picked = new ArrayList<>();
        ui.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(i, PICK_FILES);
            } catch (ActivityNotFoundException e) {
                pickWait.countDown();
            }
        });
        try {
            pickWait.await();
        } catch (InterruptedException ignored) {
        }
        return picked;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES) return;
        List<String> out = new ArrayList<>();
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) out.add(data.getClipData().getItemAt(i).getUri().toString());
            } else if (data.getData() != null) {
                out.add(data.getData().toString());
            }
        }
        picked = out;
        if (pickWait != null) pickWait.countDown();
    }

    private String query(String local, String column) {
        try (Cursor c = getContentResolver().query(Uri.parse(local), new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public String displayName(String local) {
        if (!local.startsWith("content:")) return new File(local).getName();
        String n = query(local, OpenableColumns.DISPLAY_NAME);
        if (n == null) n = Uri.parse(local).getLastPathSegment();
        return n == null ? "파일" : n.replace('/', '_');
    }

    @Override
    public long size(String local) {
        String s = query(local, OpenableColumns.SIZE);
        try {
            return s == null ? -1 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public InputStream openInput(String local) throws IOException {
        InputStream in = getContentResolver().openInputStream(Uri.parse(local));
        if (in == null) throw new IOException("파일을 읽을 수 없어요.");
        return in;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** 폰 앱이 알아보는 종류 (모르면 null). 저장할 때 이름 끝에 확장자가 붙지 않게, 저장은 늘 octet-stream 으로 함 */
    private static String viewMime(String name) {
        String ext = extOf(name);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        if (ext.matches("txt|md|log|py|js|ts|sh|bash|zsh|c|h|cpp|go|rs|java|kt|rb|php|lua|sql|json|ya?ml|toml|ini|conf|cfg|env|xml|csv|html?|css")) return "text/plain";
        return null;
    }

    @Override
    public Core.Download newDownload(String relDir, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        String rel = "Download/SSH Lite" + (relDir.isEmpty() ? "" : "/" + relDir);
        v.put("_display_name", name);
        v.put("mime_type", "application/octet-stream");
        v.put("relative_path", rel);
        v.put("is_pending", 1);
        // MediaStore.Downloads (Android 10 부터): 권한 없이 폰의 '다운로드' 폴더에 저장. 같은 이름이면 폰이 '이름 (1)'로 바꿈
        Uri uri = cr.insert(Uri.parse("content://media/external_primary/downloads"), v);
        if (uri == null) throw new IOException("다운로드 폴더에 저장할 수 없어요.");
        OutputStream out = cr.openOutputStream(uri);
        if (out == null) {
            cr.delete(uri, null, null);
            throw new IOException("다운로드 폴더에 저장할 수 없어요.");
        }
        String actual = query(uri.toString(), "_display_name");
        final String label = "다운로드/SSH Lite/" + (relDir.isEmpty() ? "" : relDir + "/") + (actual == null ? name : actual);
        final String fileName = actual == null ? name : actual;
        return new Core.Download() {
            public OutputStream out() { return out; }
            public String label() { return label; }

            public void finish() {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                ContentValues done = new ContentValues();
                done.put("is_pending", 0);
                cr.update(uri, done, null, null);
                downloads.put(label, uri);
                downloadNames.put(label, fileName);
            }

            public void abort() {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                cr.delete(uri, null, null);
            }
        };
    }

    private void view(Uri uri, String name) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        String mime = viewMime(name);
        i.setDataAndType(uri, mime == null ? "*/*" : mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(i, name));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "이 파일을 열 수 있는 앱이 없어요. 다운로드/SSH Lite 에 저장돼 있어요.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void showDownloads(String first) {
        ui.post(() -> {
            Uri u = first == null ? null : downloads.get(first);
            if (u != null) {
                view(u, downloadNames.getOrDefault(first, first));
                return;
            }
            try {
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        });
    }

    @Override
    public void openFile(Core.Download d) {
        ui.post(() -> {
            Uri u = downloads.get(d.label());
            if (u != null) view(u, downloadNames.getOrDefault(d.label(), d.label()));
        });
    }
}
