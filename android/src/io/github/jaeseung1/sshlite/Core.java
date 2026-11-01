package io.github.jaeseung1.sshlite;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 폰 앱의 서버 쪽 기능 (Windows 앱의 ssh_lite.pyw Backend 를 Java 로 옮긴 것).
 * 화면(index.html)이 부르는 이름과 돌려주는 모양이 파이썬 쪽과 같아서 화면 코드를 그대로 씀.
 * 안드로이드 클래스는 쓰지 않음 (PC 에서도 테스트할 수 있게). 폰에만 있는 기능은 Platform 으로 받음.
 */
public class Core {
    public static final String APP_VERSION = "2026.11.01-8";

    /** 폰(안드로이드)마다 다른 기능: MainActivity 가 구현. PC 테스트에서는 가짜로 구현 */
    public interface Platform {
        void emit(String kind, Object data);
        File dataDir();                              // 설정·SSH 키·서버 키 목록을 두는 앱 전용 폴더
        String readAsset(String name) throws IOException;
        String deviceName();
        String clipboardGet();
        boolean clipboardSet(String text);
        void openUrl(String url);
        List<String> pickFiles();                    // 파일 고르기 창 (고를 때까지 기다림). 취소하면 빈 목록
        String displayName(String local);
        long size(String local);
        InputStream openInput(String local) throws IOException;
        Download newDownload(String relDir, String name) throws IOException;  // 폰의 다운로드/SSH Lite 아래
        void showDownloads(String first);
        void openFile(Download d) throws IOException;
    }

    /** 폰에 받는 파일 하나 */
    public interface Download {
        OutputStream out();
        String label();                              // 알림에 보여줄 위치
        void finish();
        void abort();
    }

    static class Cancelled extends Exception {
        Cancelled() { super("취소됨"); }
    }

    // ------------------------------------------------------------------ 상태

    private final Platform p;
    private final Object lock = new Object();
    private final JSONObject cfg;
    private final String markBegin, markEnd, bashSnippet, zshSnippet, statsCmd;
    private final KnownHosts knownHosts;
    private volatile Session session;
    private ChannelSftp sftp;
    private volatile int sessionNo = 0;
    private final Map<Integer, Shell> terms = new ConcurrentHashMap<>();
    private int nextTerm = 1;
    private final LinkedBlockingQueue<Object[]> jobs = new LinkedBlockingQueue<>();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Map<String, Object> statsPrev = new HashMap<>();
    private final Map<String, Download> downloaded = new ConcurrentHashMap<>();

    public Core(Platform platform) throws IOException, JSONException {
        p = platform;
        JSONObject shared = new JSONObject(p.readAsset("shared.json"));
        markBegin = shared.getString("mark_begin");
        markEnd = shared.getString("mark_end");
        bashSnippet = shared.getString("bash");
        zshSnippet = shared.getString("zsh");
        statsCmd = shared.getString("stats_cmd");
        cfg = loadConfig();
        knownHosts = new KnownHosts(new File(p.dataDir(), "known_hosts"));
        Thread t = new Thread(this::transferWorker, "transfers");
        t.setDaemon(true);
        t.start();
    }

    private File configFile() { return new File(p.dataDir(), "config.json"); }
    private File keyFile() { return new File(p.dataDir(), "id_ecdsa"); }

    private JSONObject loadConfig() throws JSONException {
        JSONObject c = new JSONObject();
        c.put("host", "").put("port", 22).put("user", "")  // 처음 한 번 화면에서 입력하면 기억함
         .put("auto_connect", false).put("show_hidden", false).put("sidebar_width", 300)
         .put("tab_titles", true).put("follow_cwd", true).put("font_size", 13).put("server_stats", true)
         .put("download_dir", "다운로드/SSH Lite");
        try {  // 개인용 빌드: 앱 안에 defaults.local.json 이 있으면 서버 정보가 채워져 있음 (저장소에는 안 올림)
            JSONObject mine = new JSONObject(p.readAsset("defaults.local.json"));
            for (String k : new String[]{"host", "port", "user", "auto_connect"}) if (mine.has(k)) c.put(k, mine.get(k));
        } catch (Exception ignored) {
        }
        try {
            JSONObject saved = new JSONObject(readFile(configFile()));
            for (java.util.Iterator<String> it = saved.keys(); it.hasNext(); ) {
                String k = it.next();
                c.put(k, saved.get(k));
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    private void saveConfig() {
        try {
            writeFile(configFile(), cfg.toString(2));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ 화면에서 부르는 기능 (이름 -> 실행)

    /** 화면이 부른 기능 실행. 돌려주는 값: JSONObject / JSONArray / String / Boolean / Number / null */
    public Object call(String name, JSONArray a) throws Exception {
        switch (name) {
            case "get_config": return getConfig();
            case "save_ui": saveUi(a.getJSONObject(0)); return null;
            case "connect": return connect(a.optString(0), a.optString(1), a.optString(2), str(a, 3), str(a, 4));
            case "disconnect": disconnect(); return null;
            case "setup_key": return setupKey();
            case "set_tab_titles": return setTabTitles(a.optBoolean(0));
            case "term_open": return termOpen(a.getInt(0), a.getInt(1), a.optString(2, "ssh"));
            case "term_input": termInput(a.getInt(0), a.getString(1)); return null;
            case "term_resize": termResize(a.getInt(0), a.getInt(1), a.getInt(2)); return null;
            case "term_close": termClose(a.getInt(0)); return null;
            case "list_dir": return listDir(a.getString(0));
            case "mkdir": { String parent = a.getString(0), n = a.getString(1);
                return simple(s -> s.mkdir(join(parent, n))); }
            case "rename": { String path = a.getString(0), n = a.getString(1);
                return simple(s -> s.rename(path, join(dirname(path), n))); }
            case "move": return move(a.getJSONArray(0), a.getString(1));
            case "delete": return delete(a.getJSONArray(0));
            case "existing_names": return existingNames(a.getString(0), a.getJSONArray(1));
            case "pick_files": return new JSONArray(p.pickFiles());
            case "pick_folder": return null;  // 폰은 늘 다운로드/SSH Lite 에 받음
            case "basenames": { JSONArray out = new JSONArray(), in = a.getJSONArray(0);
                for (int i = 0; i < in.length(); i++) out.put(p.displayName(in.getString(i)));
                return out; }
            case "show_in_explorer": p.showDownloads(a.optString(0)); return null;
            case "clipboard_get": return p.clipboardGet();
            case "clipboard_set": return p.clipboardSet(a.optString(0));
            case "server_stats": return serverStats(a.optBoolean(0));
            case "open_url": { String u = a.optString(0); if (u.matches("^https?://.*")) p.openUrl(u); return null; }
            case "open_remote": return openRemote(a.getString(0));
            case "upload": jobs.put(new Object[]{"upload", a.getJSONArray(0), a.getString(1), sessionNo});
                return new JSONObject().put("queued", jobs.size());
            case "download": jobs.put(new Object[]{"download", a.getJSONArray(0), cfg.optString("download_dir"), sessionNo});
                return new JSONObject().put("queued", jobs.size());
            case "cancel_transfers": cancel.set(true); jobs.clear(); return null;
            default: throw new IllegalArgumentException("알 수 없는 기능: " + name);
        }
    }

    private static String str(JSONArray a, int i) {
        return a.isNull(i) ? null : a.optString(i, null);
    }

    // ------------------------------------------------------------------ 설정

    private JSONObject getConfig() throws JSONException {
        JSONObject c = new JSONObject(cfg.toString());
        c.put("has_key", keyFile().exists()).put("version", APP_VERSION).put("platform", "android");
        return c;
    }

    private void saveUi(JSONObject values) throws JSONException {
        for (String k : new String[]{"show_hidden", "sidebar_width", "auto_connect", "tab_titles", "follow_cwd", "font_size", "server_stats"}) {
            if (values.has(k)) cfg.put(k, values.get(k));
        }
        saveConfig();
    }

    // ------------------------------------------------------------------ 연결

    private JSONObject connect(String host, String port, String user, String password, String trust) throws JSONException {
        host = host.trim();
        user = user.trim();
        port = port.trim();
        if (!host.matches("[\\w.:-]+") || !user.matches("[\\w.-]+") || !port.matches("\\d+")) {
            return err("호스트, 포트, 사용자를 확인해주세요.");
        }
        int portNo = Integer.parseInt(port);
        synchronized (lock) {
            closeAll();
            JSch jsch = new JSch();
            knownHosts.trusted = trust;
            knownHosts.lastUnknown = null;
            jsch.setHostKeyRepository(knownHosts);
            Session s = null;
            try {
                if (keyFile().exists()) jsch.addIdentity(keyFile().getAbsolutePath());
                s = jsch.getSession(user, host, portNo);
                s.setConfig("StrictHostKeyChecking", "yes");
                s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
                if (password != null && !password.isEmpty()) s.setPassword(password);
                s.setUserInfo(new Login(password));
                s.setServerAliveInterval(15000);
                s.setServerAliveCountMax(3);
                s.connect(10000);
                ChannelSftp ch = (ChannelSftp) s.openChannel("sftp");
                ch.connect(10000);
                String home = ch.realpath(".");
                session = s;
                sftp = ch;
                sessionNo++;
                statsPrev = new HashMap<>();
                cfg.put("host", host).put("port", portNo).put("user", user);
                saveConfig();
                JSONObject titles = Boolean.FALSE.equals(cfg.opt("tab_titles")) ? new JSONObject() : shellIntegration(Boolean.TRUE);
                return new JSONObject().put("ok", true).put("home", home).put("label", user + "@" + host + ":" + portNo)
                        .put("used_password", password != null && !password.isEmpty())
                        .put("titles_added", titles.optBoolean("added")).put("titles_error", titles.opt("error"));
            } catch (Exception e) {
                if (s != null) s.disconnect();
                String[] unknown = knownHosts.lastUnknown;
                if (unknown != null) {
                    return new JSONObject().put("need", "hostkey").put("host", unknown[0]).put("type", unknown[1])
                            .put("fingerprint", unknown[2]);
                }
                String m = String.valueOf(e.getMessage());
                if (m.contains("Auth fail") || m.contains("Auth cancel") || m.contains("USERAUTH fail")
                        || m.contains("Too many authentication failures")) {
                    return new JSONObject().put("need", "password").put("retry", password != null && !password.isEmpty());
                }
                return err(friendly(e));
            }
        }
    }

    /** 키보드 대화식(맥 기본) / 비밀번호 로그인에 비밀번호를 한 번만 넘김 (틀리면 다시 묻지 않고 실패로) */
    static class Login implements UserInfo, UIKeyboardInteractive {
        private final String password;
        private boolean used;

        Login(String password) { this.password = password == null || password.isEmpty() ? null : password; }

        public String getPassphrase() { return null; }
        public String getPassword() { return password; }
        public boolean promptPassword(String message) { return false; }  // 비밀번호는 setPassword 로 한 번만 (틀리면 다시 묻지 않음)
        public boolean promptPassphrase(String message) { return false; }
        public boolean promptYesNo(String message) { return false; }
        public void showMessage(String message) { }

        public String[] promptKeyboardInteractive(String dest, String name, String instruction, String[] prompt, boolean[] echo) {
            if (password == null || used || prompt.length == 0) return null;
            used = true;
            String[] out = new String[prompt.length];
            java.util.Arrays.fill(out, password);
            return out;
        }
    }

    private void disconnect() {
        synchronized (lock) {
            closeAll();
        }
    }

    private void closeAll() {
        cancel.set(true);
        sessionNo++;
        Session s = session;
        session = null;
        sftp = null;
        if (s != null) s.disconnect();
        terms.clear();
    }

    private boolean connected() {
        Session s = session;
        return s != null && s.isConnected();
    }

    private ChannelSftp needSftp() throws IOException {
        ChannelSftp s = sftp;
        if (s == null || !connected()) throw new IOException("서버에 연결되어 있지 않아요.");
        return s;
    }

    /** 이 폰의 공개키를 서버 ~/.ssh/authorized_keys 에 등록 -> 다음부터 비밀번호 없이 접속 */
    private JSONObject setupKey() throws JSONException {
        try {
            String line = ensureKey();
            synchronized (lock) {
                ChannelSftp s = needSftp();
                String sshDir = join(s.realpath("."), ".ssh");
                try {
                    s.stat(sshDir);
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
                    s.mkdir(sshDir);
                }
                String auth = join(sshDir, "authorized_keys");
                String existing = readRemote(s, auth);
                String[] parts = line.split(" ");
                if (!existing.contains(parts[0] + " " + parts[1])) {
                    String prefix = !existing.isEmpty() && !existing.endsWith("\n") ? "\n" : "";
                    s.put(new ByteArrayInputStream((prefix + line + "\n").getBytes(StandardCharsets.UTF_8)), auth, ChannelSftp.APPEND);
                }
                s.chmod(0600, auth);  // sshd 는 권한이 느슨하면 키를 무시함
                s.chmod(0700, sshDir);
            }
            return new JSONObject().put("ok", true).put("key", "앱 전용 저장공간");
        } catch (Exception e) {
            return err(friendly(e));
        }
    }

    private String ensureKey() throws Exception {
        File key = keyFile(), pub = new File(key.getPath() + ".pub");
        if (pub.exists() && key.exists()) return readFile(pub).trim();
        KeyPair kp = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256);
        String comment = "ssh-lite@" + p.deviceName().replaceAll("[^\\w.-]", "-");
        try (FileOutputStream f = new FileOutputStream(key)) {
            kp.writePrivateKey(f);
        }
        try (FileOutputStream f = new FileOutputStream(pub)) {
            kp.writePublicKey(f, comment);
        }
        kp.dispose();
        key.setReadable(false, false);
        key.setReadable(true, true);
        return readFile(pub).trim();
    }

    private JSONObject setTabTitles(boolean enable) throws JSONException {
        JSONObject r = shellIntegration(enable);
        if (!r.has("error")) {
            cfg.put("tab_titles", enable);
            saveConfig();
        }
        return r;
    }

    /** 서버 셸 설정의 탭 이름 표시. enable=null: 상태 / true: 추가(예전 버전이면 바꿈) / false: 제거 (파이썬과 같음) */
    private JSONObject shellIntegration(Boolean enable) throws JSONException {
        try {
            synchronized (lock) {
                ChannelSftp s = needSftp();
                String home = s.realpath(".");
                Map<String, String> texts = new java.util.LinkedHashMap<>();
                for (String n : new String[]{".bashrc", ".bash_profile", ".zshrc"}) {
                    texts.put(n, exists(s, join(home, n)) ? readRemote(s, join(home, n)) : null);
                }
                boolean installed = false;
                for (String t : texts.values()) if (t != null && t.contains(markBegin)) installed = true;
                if (enable == null) return new JSONObject().put("installed", installed);

                Map<String, String> want = new HashMap<>();
                if (enable) {
                    String loginShell = exec("printf %s \"$SHELL\"", 10000)[0].trim();
                    String shell = loginShell.substring(loginShell.lastIndexOf('/') + 1);
                    if (shell.equals("zsh")) {
                        want.put(".zshrc", zshSnippet);
                    } else if (shell.equals("bash")) {
                        want.put(".bashrc", bashSnippet);
                        String profile = texts.get(".bash_profile");
                        if (profile != null) {
                            String rest = profile.contains(markBegin) ? stripBlock(profile) : profile;
                            if (!rest.contains(".bashrc")) want.put(".bash_profile", bashSnippet);
                        }
                    } else {
                        return err("서버 기본 셸(" + (loginShell.isEmpty() ? "알 수 없음" : loginShell) + ")은 지원하지 않아요. bash와 zsh만 돼요.");
                    }
                }
                boolean changed = false;
                for (Map.Entry<String, String> e : texts.entrySet()) {
                    String name = e.getKey(), text = e.getValue(), current = text == null ? "" : text, next;
                    boolean has = current.contains(markBegin);
                    if (want.containsKey(name)) {
                        if (has && current.contains(want.get(name))) continue;  // 이미 최신
                        String base = has ? stripBlock(current) : current;
                        if (text != null && !text.isEmpty() && !has) {
                            String backup = join(home, name + ".ssh-lite.bak");
                            if (!exists(s, backup)) writeRemote(s, backup, text);  // 처음 한 번만 원래 파일 백업
                        }
                        next = base + (!base.isEmpty() && !base.endsWith("\n") ? "\n" : "") + (base.isEmpty() ? "" : "\n") + want.get(name);
                    } else if (has) {
                        next = stripBlock(current);
                    } else {
                        continue;
                    }
                    writeRemote(s, join(home, name), next);
                    changed = true;
                }
                return new JSONObject().put("installed", enable).put("added", enable && changed && !installed);
            }
        } catch (Exception e) {
            return err(friendly(e));
        }
    }

    String stripBlock(String text) {
        int start = text.indexOf(markBegin);
        int end = text.indexOf(markEnd, start) + markEnd.length();
        String before = text.substring(0, start).replaceAll("\n+$", ""), after = text.substring(end).replaceAll("^\n+", "");
        return (before.isEmpty() ? "" : before + "\n") + after;
    }

    // ------------------------------------------------------------------ 터미널

    class Shell {
        final ChannelShell ch;
        final OutputStream out;

        Shell(ChannelShell ch) throws IOException {
            this.ch = ch;
            out = ch.getOutputStream();
        }
    }

    private JSONObject termOpen(int cols, int rows, String kind) throws JSONException {
        if (!"ssh".equals(kind)) return err("폰에서는 서버 터미널만 열 수 있어요.");
        Shell shell;
        InputStream in;
        int no;
        try {
            synchronized (lock) {
                if (!connected()) return err("서버에 연결되어 있지 않아요.");
                ChannelShell ch = (ChannelShell) session.openChannel("shell");
                ch.setPtyType("xterm-256color", cols, rows, 0, 0);
                in = ch.getInputStream();
                shell = new Shell(ch);
                ch.connect(10000);
                no = sessionNo;
            }
        } catch (Exception e) {
            return err(friendly(e));
        }
        int tid;
        synchronized (this) {
            tid = nextTerm++;
        }
        terms.put(tid, shell);
        final InputStream input = in;
        Thread t = new Thread(() -> termReader(tid, shell, input, no), "term-" + tid);
        t.setDaemon(true);
        t.start();
        return new JSONObject().put("ok", true).put("id", tid);
    }

    private void termReader(int tid, Shell shell, InputStream in, int no) {
        // 한글이 조각나서 와도 깨지지 않게 이어서 풀기
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer bytes = ByteBuffer.allocate(65536 + 8);
        CharBuffer chars = CharBuffer.allocate(65536 + 8);
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                bytes.put(buf, 0, n);
                bytes.flip();
                dec.decode(bytes, chars, false);
                bytes.compact();
                chars.flip();
                if (chars.hasRemaining()) {
                    p.emit("term", new JSONObject().put("id", tid).put("data", chars.toString()));
                }
                chars.clear();
            }
        } catch (Exception ignored) {
        }
        terms.remove(tid);
        try {
            if (no == sessionNo) {  // 일부러 연결을 끊은 경우는 알리지 않음
                if (connected()) p.emit("term_closed", new JSONObject().put("id", tid));
                else p.emit("disconnected", null);
            }
        } catch (JSONException ignored) {
        }
    }

    private void termInput(int tid, String data) {
        Shell s = terms.get(tid);
        if (s == null) return;
        try {
            synchronized (s) {
                s.out.write(data.getBytes(StandardCharsets.UTF_8));
                s.out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private void termResize(int tid, int cols, int rows) {
        Shell s = terms.get(tid);
        if (s != null) s.ch.setPtySize(cols, rows, 0, 0);
    }

    private void termClose(int tid) {
        Shell s = terms.remove(tid);
        if (s != null) s.ch.disconnect();
    }

    // ------------------------------------------------------------------ 파일 목록 / 정리

    interface SftpWork {
        void run(ChannelSftp s) throws Exception;
    }

    private JSONObject simple(SftpWork work) throws JSONException {
        try {
            synchronized (lock) {
                work.run(needSftp());
            }
            return new JSONObject().put("ok", true);
        } catch (Exception e) {
            return err(friendly(e)).put("disconnected", !connected());
        }
    }

    private JSONObject listDir(String path) throws JSONException {
        try {
            List<JSONObject> entries = new ArrayList<>();
            synchronized (lock) {
                ChannelSftp s = needSftp();
                path = s.realpath(path);
                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> list = s.ls(path);
                for (ChannelSftp.LsEntry e : list) {
                    String n = e.getFilename();
                    if (n.equals(".") || n.equals("..")) continue;
                    SftpATTRS a = e.getAttrs();
                    String full = join(path, n);
                    boolean isLink = a.isLink(), isDir = a.isDir();
                    if (isLink) {  // 바로가기는 가리키는 대상이 폴더인지 확인
                        try {
                            isDir = s.stat(full).isDir();
                        } catch (SftpException ignored) {
                        }
                    }
                    entries.add(new JSONObject().put("name", n).put("path", full).put("dir", isDir).put("link", isLink)
                            .put("size", a.getSize()).put("mtime", a.getMTime()));
                }
            }
            Collections.sort(entries, (x, y) -> {
                boolean dx = x.optBoolean("dir"), dy = y.optBoolean("dir");
                if (dx != dy) return dx ? -1 : 1;
                return x.optString("name").toLowerCase().compareTo(y.optString("name").toLowerCase());
            });
            return new JSONObject().put("path", path).put("entries", new JSONArray(entries));
        } catch (Exception e) {
            return err(friendly(e)).put("disconnected", !connected());
        }
    }

    private JSONObject move(JSONArray paths, String target) throws JSONException {
        return simple(s -> {
            for (int i = 0; i < paths.length(); i++) {
                String src = paths.getString(i), dest = join(target, basename(src));
                if (!dest.equals(src)) s.rename(src, dest);
            }
        });
    }

    private JSONObject delete(JSONArray items) throws JSONException {
        return simple(s -> {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                // 바로가기는 바로가기만 지움 (가리키는 원본 폴더는 건드리지 않음)
                remove(s, it.getString("path"), it.optBoolean("dir") && !it.optBoolean("link"));
            }
        });
    }

    private void remove(ChannelSftp s, String path, boolean isDir) throws SftpException {
        if (isDir) {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> list = s.ls(path);
            for (ChannelSftp.LsEntry e : list) {
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                remove(s, join(path, n), e.getAttrs().isDir() && !e.getAttrs().isLink());
            }
            s.rmdir(path);
        } else {
            s.rm(path);
        }
    }

    private JSONArray existingNames(String dir, JSONArray names) throws JSONException {
        JSONObject r = listDir(dir);
        Set<String> have = new HashSet<>();
        JSONArray entries = r.optJSONArray("entries");
        if (entries != null) for (int i = 0; i < entries.length(); i++) have.add(entries.getJSONObject(i).getString("name"));
        JSONArray out = new JSONArray();
        for (int i = 0; i < names.length(); i++) if (have.contains(names.getString(i))) out.put(names.getString(i));
        return out;
    }

    // ------------------------------------------------------------------ 서버 파일 열기 (폰으로 받아서 폰 앱으로)

    private JSONObject openRemote(String remote) throws JSONException {
        Download d = null;
        try {
            synchronized (lock) {
                ChannelSftp s = needSftp();
                d = p.newDownload("", basename(remote));
                s.get(remote, d.out());
            }
            d.finish();
            downloaded.put(d.label(), d);
            p.openFile(d);
            return new JSONObject().put("ok", true).put("local", d.label());
        } catch (Exception e) {
            if (d != null) d.abort();
            return err(friendly(e)).put("disconnected", !connected());
        }
    }

    // ------------------------------------------------------------------ 서버 사용량

    private JSONObject serverStats(boolean raw) throws JSONException {
        if (!connected()) return err("서버에 연결되어 있지 않아요.");
        String text = "", errText = "";
        try {
            String[] r = exec(statsCmd, 8000);
            text = r[0];
            errText = r[1];
            Map<String, Object> next = new HashMap<>();
            JSONObject stats = parseStats(text, statsPrev, System.nanoTime() / 1e9, next);
            statsPrev = next;
            if (raw) stats.put("detail", detail(text, errText));
            return stats;
        } catch (Exception e) {
            return err(friendly(e)).put("detail", detail(text, errText));
        }
    }

    private static String detail(String text, String errText) {
        String d = (text + (errText.trim().isEmpty() ? "" : "\n[stderr]\n" + errText)).trim();
        String[] lines = d.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(40, lines.length); i++) b.append(i > 0 ? "\n" : "").append(lines[i]);
        return b.toString();
    }

    private static String value(List<String> lines, String key) {
        for (String l : lines) if (l.startsWith(key)) return l.substring(key.length()).trim();
        return null;
    }

    /** 사용량 명령 출력 읽기 (파이썬 parse_stats 와 같음). CPU·메모리·디스크를 하나도 못 읽으면 예외 */
    static JSONObject parseStats(String text, Map<String, Object> prev, double now, Map<String, Object> next) throws JSONException {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n")) if (!l.trim().isEmpty()) lines.add(l.trim());
        JSONObject r = new JSONObject().put("cpu", JSONObject.NULL).put("net_in", JSONObject.NULL).put("net_out", JSONObject.NULL);
        boolean got = false, darwin = lines.contains("OS darwin");
        try {  // 네트워크: 받은/보낸 바이트 누적값 -> 초당 속도
            String[] f = value(lines, "NET ").split("\\s+");
            long in = Long.parseLong(f[0]), out = Long.parseLong(f[1]);
            next.put("net", new double[]{now, in, out});
            double[] p0 = (double[]) prev.get("net");
            if (p0 != null && now > p0[0] && in >= p0[1] && out >= p0[2]) {
                r.put("net_in", Math.round((in - p0[1]) / (now - p0[0]))).put("net_out", Math.round((out - p0[2]) / (now - p0[0])));
            }
        } catch (Exception ignored) {
        }
        try {  // 가동 시간
            if (darwin) {
                Matcher m = Pattern.compile("sec = (\\d+)").matcher(value(lines, "BOOT "));
                if (m.find()) r.put("uptime", Long.parseLong(value(lines, "NOW ")) - Long.parseLong(m.group(1)));
            } else {
                r.put("uptime", (long) Double.parseDouble(value(lines, "UPTIME ")));
            }
        } catch (Exception ignored) {
        }
        String diskLine = value(lines, "DISK ");
        if (diskLine != null) {
            String[] disk = diskLine.split("\\s+");
            int at = -1;
            for (int i = 0; i < disk.length; i++) if (disk[i].matches("\\d+%")) { at = i; break; }
            if (at >= 3) {
                StringBuilder mount = new StringBuilder();
                for (int i = at + 1; i < disk.length; i++) mount.append(mount.length() > 0 ? " " : "").append(disk[i]);
                r.put("disk_total", Long.parseLong(disk[at - 3]) * 1024).put("disk_used", Long.parseLong(disk[at - 2]) * 1024)
                 .put("disk_pct", Integer.parseInt(disk[at].replace("%", ""))).put("mount", mount.length() > 0 ? mount.toString() : "/");
                got = true;
            }
        }
        if (darwin) {
            try {
                r.put("cpu", Math.max(0, Math.min(100, Math.round(Double.parseDouble(value(lines, "PSCPU ")) / Math.max(Integer.parseInt(value(lines, "NCPU ")), 1)))));
                got = true;
            } catch (Exception ignored) {
            }
            try {
                long memsize = Long.parseLong(value(lines, "MEMSIZE "));
                Matcher pm = Pattern.compile("page size of (\\d+) bytes").matcher(text);
                if (!pm.find()) throw new IllegalStateException();
                long page = Long.parseLong(pm.group(1));
                Map<String, Long> pages = new HashMap<>();
                Pattern row = Pattern.compile("^\"?([A-Za-z][A-Za-z -]+)\"?:\\s+(\\d+)\\.?$");
                for (String l : lines) {
                    Matcher m = row.matcher(l);
                    if (m.matches()) pages.put(m.group(1), Long.parseLong(m.group(2)));
                }
                long used;
                if (pages.containsKey("Anonymous pages")) {  // 활성 상태 보기의 '사용된 메모리' = 앱 메모리 + 고정 + 압축
                    used = pages.get("Anonymous pages") - pages.getOrDefault("Pages purgeable", 0L);
                } else {
                    if (!pages.containsKey("Pages active")) throw new IllegalStateException();
                    used = pages.get("Pages active");
                }
                used += pages.getOrDefault("Pages wired down", 0L) + pages.getOrDefault("Pages occupied by compressor", 0L);
                r.put("mem_used", Math.min(used * page, memsize)).put("mem_total", memsize);
                got = true;
            } catch (Exception ignored) {
            }
        } else if (lines.contains("OS linux")) {
            try {
                String[] f = value(lines, "cpu ").split("\\s+");
                long total = 0;
                long[] nums = new long[Math.min(8, f.length)];
                for (int i = 0; i < nums.length; i++) { nums[i] = Long.parseLong(f[i]); total += nums[i]; }
                long idle = nums[3] + (nums.length > 4 ? nums[4] : 0);
                next.put("cpu", new long[]{total, idle});
                long[] c0 = (long[]) prev.get("cpu");
                if (c0 != null && total > c0[0]) {
                    r.put("cpu", Math.max(0, Math.min(100, Math.round(100 * (1 - (double) (idle - c0[1]) / (total - c0[0]))))));
                }
                got = true;
            } catch (Exception ignored) {
            }
            try {
                Long total = null, avail = null;
                for (String l : lines) {
                    if (l.startsWith("MemTotal:")) total = Long.parseLong(l.split("\\s+")[1]) * 1024;
                    if (l.startsWith("MemAvailable:")) avail = Long.parseLong(l.split("\\s+")[1]) * 1024;
                }
                if (total != null && avail != null) {
                    r.put("mem_used", total - avail).put("mem_total", total);
                    got = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (!got) throw new IllegalStateException("서버 응답에서 사용량을 찾지 못했어요.");
        return r;
    }

    /** 서버에서 짧은 명령 하나 실행 -> {출력, 오류 출력} */
    private String[] exec(String command, int timeoutMs) throws Exception {
        Session s = session;
        if (s == null || !s.isConnected()) throw new IOException("서버에 연결되어 있지 않아요.");
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        try {
            ch.setCommand(command);
            ch.setInputStream(null);
            InputStream out = ch.getInputStream(), errs = ch.getErrStream();
            ch.connect(timeoutMs);
            ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (true) {
                boolean idle = true;
                while (out.available() > 0) { int n = out.read(buf); if (n < 0) break; o.write(buf, 0, n); idle = false; }
                while (errs.available() > 0) { int n = errs.read(buf); if (n < 0) break; e.write(buf, 0, n); idle = false; }
                if (ch.isClosed() && out.available() <= 0 && errs.available() <= 0) break;
                if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("timeout");
                if (idle) Thread.sleep(20);
            }
            return new String[]{new String(o.toByteArray(), StandardCharsets.UTF_8), new String(e.toByteArray(), StandardCharsets.UTF_8)};
        } finally {
            ch.disconnect();
        }
    }

    // ------------------------------------------------------------------ 업로드 / 다운로드 (대기열에서 하나씩)

    private void transferWorker() {
        ChannelSftp ts = null;
        int tsSession = -1;
        while (true) {
            Object[] job;
            try {
                job = jobs.take();
            } catch (InterruptedException e) {
                return;
            }
            String kind = (String) job[0], dest = (String) job[2];
            JSONArray items = (JSONArray) job[1];
            int no = (Integer) job[3];
            if (no != sessionNo) continue;  // 그새 연결이 바뀐 작업은 버림
            cancel.set(false);
            try {
                if (tsSession != no || ts == null || !ts.isConnected()) {  // 전송은 목록 보기와 따로 SFTP 채널을 하나 더 씀
                    synchronized (lock) {
                        if (!connected()) throw new IOException("서버에 연결되어 있지 않아요.");
                        ts = (ChannelSftp) session.openChannel("sftp");
                        ts.connect(10000);
                        tsSession = no;
                    }
                }
                if (kind.equals("upload")) {
                    int count = doUpload(ts, items, dest);
                    p.emit("transfer_done", new JSONObject().put("kind", kind).put("count", count).put("dir", dest));
                } else {
                    String[] first = new String[1];
                    int count = doDownload(ts, items, first);
                    p.emit("transfer_done", new JSONObject().put("kind", kind).put("count", count).put("dir", dest)
                            .put("first", first[0] == null ? dest : first[0]));
                }
            } catch (Cancelled e) {
                emitSafe("transfer_cancelled", kind, dest, null);
            } catch (Exception e) {
                emitSafe("transfer_error", kind, dest, friendly(e));
            }
        }
    }

    private void emitSafe(String event, String kind, String dir, String error) {
        try {
            JSONObject o = new JSONObject().put("kind", kind).put("dir", dir);
            if (error != null) o.put("error", error).put("disconnected", !connected());
            p.emit(event, o);
        } catch (JSONException ignored) {
        }
    }

    /** 전송 진행률: base 는 앞 파일들로 이미 보낸 양, all 은 이번 전송 전체 크기 (속도·남은 시간 계산용) */
    private SftpProgressMonitor progress(String kind, String label, int index, int total, long size, long base, long all) {
        return new SftpProgressMonitor() {
            long sent = 0, last = 0;

            public void init(int op, String src, String dest, long max) { }

            public boolean count(long n) {
                sent += n;
                if (cancel.get()) return false;
                long now = System.currentTimeMillis();
                if (now - last >= 100 || sent >= size) {
                    last = now;
                    try {
                        p.emit("transfer", new JSONObject().put("kind", kind).put("label", label).put("index", index)
                                .put("total", total).put("sent", sent).put("size", size).put("done", base + sent)
                                .put("all", Math.max(all, base + sent)).put("queued", jobs.size()));
                    } catch (JSONException ignored) {
                    }
                }
                return true;
            }

            public void end() { }
        };
    }

    private int doUpload(ChannelSftp s, JSONArray locals, String remoteDir) throws Exception {
        long all = 0, base = 0;
        long[] sizes = new long[locals.length()];
        for (int i = 0; i < locals.length(); i++) { sizes[i] = Math.max(0, p.size(locals.getString(i))); all += sizes[i]; }
        for (int i = 0; i < locals.length(); i++) {
            if (cancel.get()) throw new Cancelled();
            String local = locals.getString(i), name = p.displayName(local), remote = join(remoteDir, name);
            try (InputStream in = p.openInput(local)) {
                s.put(in, remote, progress("upload", name, i + 1, locals.length(), sizes[i], base, all), ChannelSftp.OVERWRITE);
            }
            if (cancel.get()) {
                try { s.rm(remote); } catch (SftpException ignored) { }  // 덜 올라간 파일 정리
                throw new Cancelled();
            }
            base += sizes[i];
        }
        return locals.length();
    }

    private int doDownload(ChannelSftp s, JSONArray items, String[] first) throws Exception {
        List<String[]> files = new ArrayList<>();  // {서버 경로, 폰 안 폴더, 크기}
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            collect(s, it.getString("path"), "", it.optBoolean("dir"), files);
        }
        long all = 0, base = 0;
        for (String[] f : files) all += Long.parseLong(f[2]);
        for (int i = 0; i < files.size(); i++) {
            if (cancel.get()) throw new Cancelled();
            String[] f = files.get(i);
            long size = Long.parseLong(f[2]);
            Download d = p.newDownload(f[1], basename(f[0]));
            try {
                s.get(f[0], d.out(), progress("download", basename(f[0]), i + 1, files.size(), size, base, all));
                if (cancel.get()) throw new Cancelled();
                d.finish();
            } catch (Exception e) {
                d.abort();  // 덜 받은 파일 정리
                throw e;
            }
            downloaded.put(d.label(), d);
            if (files.size() == 1) first[0] = d.label();
            base += size;
        }
        return files.size();
    }

    private void collect(ChannelSftp s, String remote, String relDir, boolean isDir, List<String[]> files) throws SftpException {
        if (!isDir) {
            files.add(new String[]{remote, relDir, String.valueOf(s.stat(remote).getSize())});
            return;
        }
        String sub = relDir.isEmpty() ? basename(remote) : relDir + "/" + basename(remote);
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> list = s.ls(remote);
        for (ChannelSftp.LsEntry e : list) {
            String n = e.getFilename();
            if (n.equals(".") || n.equals("..")) continue;
            SftpATTRS a = e.getAttrs();
            String child = join(remote, n);
            boolean dir = a.isDir();
            if (a.isLink()) {
                try {
                    dir = s.stat(child).isDir();
                } catch (SftpException ex) {
                    continue;  // 깨진 바로가기
                }
                if (dir) continue;  // 폴더 안의 폴더 바로가기는 건너뜀 (무한 반복 방지)
            }
            collect(s, child, sub, dir, files);
        }
    }

    // ------------------------------------------------------------------ 서버 키 목록 (처음 보는 서버는 화면에서 지문 확인)

    static class KnownHosts implements HostKeyRepository {
        private final File file;
        volatile String trusted;          // 화면에서 사용자가 확인한 지문
        volatile String[] lastUnknown;    // {호스트, 종류, 지문}

        KnownHosts(File file) { this.file = file; }

        private List<String[]> load() {
            List<String[]> out = new ArrayList<>();
            try {
                for (String l : readFile(file).split("\n")) {
                    String[] f = l.trim().split(" ");
                    if (f.length >= 3) out.add(f);
                }
            } catch (IOException ignored) {
            }
            return out;
        }

        public int check(String host, byte[] key) {
            String type = keyType(key), b64 = Base64.getEncoder().encodeToString(key);
            boolean sameType = false;
            for (String[] f : load()) {
                if (!f[0].equals(host)) continue;
                if (f[2].equals(b64)) return OK;
                if (f[1].equals(type)) sameType = true;
            }
            if (sameType) return CHANGED;
            String fp = fingerprint(key);
            if (fp.equals(trusted)) {
                try {
                    String old = file.exists() ? readFile(file) : "";
                    writeFile(file, old + (old.isEmpty() || old.endsWith("\n") ? "" : "\n") + host + " " + type + " " + b64 + "\n");
                } catch (IOException ignored) {
                }
                return OK;
            }
            lastUnknown = new String[]{host, type, fp};
            return NOT_INCLUDED;
        }

        public void add(HostKey hostkey, UserInfo ui) { }
        public void remove(String host, String type) { }
        public void remove(String host, String type, byte[] key) { }
        public String getKnownHostsRepositoryID() { return file.getPath(); }
        public HostKey[] getHostKey() { return getHostKey(null, null); }

        public HostKey[] getHostKey(String host, String type) {
            List<HostKey> out = new ArrayList<>();
            for (String[] f : load()) {
                if ((host == null || f[0].equals(host)) && (type == null || f[1].equals(type))) {
                    try {
                        out.add(new HostKey(f[0], HostKey.GUESS, Base64.getDecoder().decode(f[2])));
                    } catch (Exception ignored) {
                    }
                }
            }
            return out.toArray(new HostKey[0]);
        }
    }

    /** SSH 공개키 바이트의 맨 앞에 적힌 종류 (ssh-ed25519, ecdsa-sha2-nistp256 ...) */
    static String keyType(byte[] key) {
        int n = ((key[0] & 0xff) << 24) | ((key[1] & 0xff) << 16) | ((key[2] & 0xff) << 8) | (key[3] & 0xff);
        return new String(key, 4, n, StandardCharsets.US_ASCII);
    }

    /** OpenSSH 와 같은 형식(SHA256:...)의 서버 키 지문 */
    static String fingerprint(byte[] key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ 도우미

    static JSONObject err(String message) throws JSONException {
        return new JSONObject().put("error", message);
    }

    static String friendly(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) return "서버 주소를 찾을 수 없어요. 호스트 이름을 확인해주세요.";
            if (t instanceof ConnectException || t instanceof NoRouteToHostException)
                return "서버에 연결할 수 없어요. 포트 번호와 서버가 켜져 있는지 확인해주세요.";
            if (t instanceof SocketTimeoutException) return "서버가 응답하지 않아요. 네트워크 상태를 확인해주세요.";
            if (t instanceof SftpException) {
                int id = ((SftpException) t).id;
                if (id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return "파일이나 폴더를 찾을 수 없어요.";
                if (id == ChannelSftp.SSH_FX_PERMISSION_DENIED) return "권한이 없어요.";
            }
        }
        String m = String.valueOf(e.getMessage());
        if (m.contains("HostKey has been changed"))
            return "서버의 호스트 키가 예전과 달라요! 서버를 다시 설치한 게 아니라면 접속하지 마세요. "
                    + "(정상이라면 폰 설정 → 애플리케이션 → SSH Lite → 저장공간 → 데이터 삭제 후 다시 연결하세요)";
        if (m.contains("timeout") || m.contains("timed out")) return "서버가 응답하지 않아요. 네트워크 상태를 확인해주세요.";
        if (m.contains("Connection refused")) return "서버에 연결할 수 없어요. 포트 번호와 서버가 켜져 있는지 확인해주세요.";
        if (e instanceof JSchException && m.startsWith("session is down")) return "서버와 연결이 끊어졌어요.";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    static String dirname(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    static String basename(String path) {
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static boolean exists(ChannelSftp s, String path) throws SftpException {
        try {
            s.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
            throw e;
        }
    }

    private static String readRemote(ChannelSftp s, String path) throws SftpException, IOException {
        if (!exists(s, path)) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        s.get(path, o);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeRemote(ChannelSftp s, String path, String text) throws SftpException {
        s.put(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), path, ChannelSftp.OVERWRITE);
    }

    static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeFile(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("저장하지 못했어요: " + f);
    }

    /** 앱을 닫을 때 */
    public void shutdown() {
        saveConfig();
        disconnect();
    }
}
