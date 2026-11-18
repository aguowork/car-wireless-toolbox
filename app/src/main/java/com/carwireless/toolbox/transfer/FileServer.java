package com.carwireless.toolbox.transfer;

import android.content.Context;
import android.os.Environment;

import com.carwireless.toolbox.App;
import com.carwireless.toolbox.R;
import com.carwireless.toolbox.core.ReportLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 极简 HTTP 文件服务，让手机浏览器把文件传进车机。
 *
 * <p>为什么不用现成的库：车机是 Android 4.2.2，能用的只有框架自带的东西。
 * 但这件事恰好不需要库——车机只要会做三件事：把一张 HTML 页面吐出去、
 * 收一个 PUT 的原始字节流、把文件列表按 JSON 吐出去。加起来两百行。</p>
 *
 * <p>为什么用 PUT 而不是表单上传：表单是 multipart，要在服务端解析边界和分段，
 * 几十行容易写错。页面里用 XHR 直接把 File 对象 PUT 过来，服务端只按
 * Content-Length 读原始字节，简单到不可能出错，而且浏览器原生支持上传进度回调。</p>
 *
 * <p>服务是进程内单例：界面关掉再打开时状态还在，不用重新启动。
 * 进程被系统回收时服务自然结束，这是可以接受的——传文件时人就在车旁边。</p>
 */
public final class FileServer {

    /** 首选端口。真被占了会依次试后面的，全部失败才算开不起来。 */
    public static final int PORT = 8080;

    private static final int[] PORTS = { 8080, 8081, 8088, 9090 };

    /** start() 的结果。分开是因为「开不起来」的原因不同，提示和排查方向也完全不同。 */
    public static final int START_OK = 0;
    public static final int START_STORAGE = 1;
    public static final int START_PORT = 2;

    /** 文件落在外部存储的这个目录下，跟系统 Download 分开，免得混在一起找不着。 */
    private static final String DIR_NAME = "车机传输";

    /** 单文件上限 2G。车机 /sdcard 往往只有几个 G，再大就是拿整台车机去赌。 */
    private static final long MAX_FILE = 2L * 1024 * 1024 * 1024;

    /** 传完至少给车机自己留 200M，别把地图和日志挤爆。 */
    private static final long RESERVE = 200L * 1024 * 1024;

    /** ext4 的文件名上限是 255 字节，留点余量给「 (1)」这种后缀。 */
    private static final int MAX_NAME_BYTES = 200;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static FileServer instance;

    private volatile boolean running;
    private ServerSocket socket;
    private File dir;
    private byte[] page;
    private int port = PORT;
    private ReportLog log;

    /**
     * 正在服务的连接。
     *
     * <p>stop() 必须把它们一起关掉：只关监听口的话，已经在传的那条 TCP 还活着，
     * 手机那边进度条继续走、文件继续写——用户以为停了，其实还在收。</p>
     */
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());

    private FileServer() { }

    public static synchronized FileServer get() {
        if (instance == null) instance = new FileServer();
        return instance;
    }

    public boolean isRunning() {
        return running;
    }

    /** 实际绑定上的端口。8080 被占时会往后挪，界面必须显示这个而不是常量。 */
    public int port() {
        return port;
    }

    public File dir() {
        return dir;
    }

    public synchronized int start(Context context) {
        if (running) return START_OK;

        dir = new File(Environment.getExternalStorageDirectory(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return START_STORAGE;
        if (!dir.isDirectory() || !dir.canWrite()) return START_STORAGE;

        if (page == null) page = readPage(context.getApplicationContext());
        log = App.log();

        // 端口被占不该是死路：换个口照样能用，卡片上的地址会跟着变
        for (int candidate : PORTS) {
            try {
                ServerSocket server = new ServerSocket();
                // 刚停掉马上再开时，端口可能还在 TIME_WAIT，允许复用
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(candidate));
                socket = server;
                port = candidate;
                break;
            } catch (Throwable error) {
                socket = null;
            }
        }
        if (socket == null) return START_PORT;

        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "file-transfer");
        thread.setDaemon(true);
        thread.start();
        event("文件服务已开启，端口 " + port);
        return START_OK;
    }

    public synchronized void stop() {
        boolean was = running;
        running = false;
        ServerSocket server = socket;
        socket = null;
        if (server != null) {
            try { server.close(); } catch (Throwable ignored) { }
        }

        // 收掉还在传的连接，让手机端立刻拿到断连，而不是等它自己传完
        synchronized (clients) {
            for (Socket client : clients) {
                try { client.close(); } catch (Throwable ignored) { }
            }
            clients.clear();
        }
        if (was) event("文件服务已停止");
    }

    // ------------------------------------------------------------------ 接收

    private void loop() {
        while (running) {
            ServerSocket server = socket;
            if (server == null) return;

            Socket client;
            try {
                client = server.accept();
            } catch (Throwable error) {
                // close() 会让 accept 抛异常，这是正常的退出路径。
                // 但 fd 耗尽之类的意外也走这里——那种情况下如果不管，
                // running 会一直是 true，界面显示「已开启」却根本没人监听。
                if (running) {
                    running = false;
                    failure("监听线程异常退出，端口 " + port, error);
                }
                return;
            }

            final Socket target = client;
            clients.add(target);

            // stop() 可能正好跑在 accept 和 add 之间：它清空 clients 时这条还没进去，
            // 不补这一刀的话界面显示已关闭、上传却还在继续。
            if (!running) {
                clients.remove(target);
                try { target.close(); } catch (Throwable ignored) { }
                return;
            }

            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    try { handle(target); } finally { clients.remove(target); }
                }
            }, "file-transfer-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket client) {
        InputStream in = null;
        OutputStream out = null;
        try {
            client.setSoTimeout(120000);
            in = new BufferedInputStream(client.getInputStream(), 8192);
            out = new BufferedOutputStream(client.getOutputStream(), 8192);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.length() == 0) return;

            int first = requestLine.indexOf(' ');
            if (first <= 0) return;
            int second = requestLine.indexOf(' ', first + 1);
            String method = requestLine.substring(0, first);
            String target = second > 0
                ? requestLine.substring(first + 1, second)
                : requestLine.substring(first + 1);

            // -1 表示「没读到 Content-Length」。0 是合法的（空文件），两者必须分开，
            // 否则分块传输（chunked）会被当成空文件收下，然后回 200。
            long length = -1;
            String header;
            while ((header = readLine(in)) != null && header.length() > 0) {
                int colon = header.indexOf(':');
                if (colon <= 0) continue;
                if (!header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) continue;
                try {
                    length = Long.parseLong(header.substring(colon + 1).trim());
                } catch (Throwable ignored) {
                    length = -1;
                }
            }

            if ("GET".equals(method)) {
                if (target.startsWith("/api/files")) {
                    send(out, 200, "application/json; charset=utf-8", utf8(listJson()));
                } else if (target.startsWith("/download")) {
                    sendFile(out, target);
                } else {
                    send(out, 200, "text/html; charset=utf-8", page);
                }
            } else if ("PUT".equals(method) && target.startsWith("/upload")) {
                receive(in, out, target, length);
            } else if ("DELETE".equals(method) && target.startsWith("/file")) {
                remove(out, target);
            } else {
                send(out, 404, "text/plain; charset=utf-8", utf8("404"));
            }
        } catch (Throwable ignored) {
            // 手机中途断开、请求不完整都走这里，不需要告诉谁
        } finally {
            if (out != null) {
                try { out.flush(); } catch (Throwable ignored) { }
            }
            try { client.close(); } catch (Throwable ignored) { }
        }
    }

    private void receive(InputStream in, OutputStream out, String target, long length)
        throws IOException {
        String name = safeName(decode(param(target, "name")));
        if (name == null || length < 0) {
            send(out, 400, "text/plain; charset=utf-8", utf8("bad request"));
            return;
        }
        if (length > MAX_FILE) {
            failure("拒绝 " + name + "：超过 " + (MAX_FILE >> 20) + "M 上限", null);
            send(out, 413, "text/plain; charset=utf-8", utf8("too large"));
            return;
        }
        // 写满车机的后果不是「传失败」，是整台车机的日志、地图、缓存一起完蛋
        if (dir.getUsableSpace() < length + RESERVE) {
            failure("拒绝 " + name + "：剩余空间不足", null);
            send(out, 507, "text/plain; charset=utf-8", utf8("no space"));
            return;
        }

        File file = unique(name);
        if (file == null) {
            send(out, 500, "text/plain; charset=utf-8", utf8("name unavailable"));
            return;
        }

        FileOutputStream stream = null;
        long written = 0;
        boolean complete = false;
        try {
            stream = new FileOutputStream(file);
            byte[] buffer = new byte[16384];
            long left = length;
            while (left > 0) {
                int want = (int) Math.min(buffer.length, left);
                int read = in.read(buffer, 0, want);
                if (read < 0) break;
                stream.write(buffer, 0, read);
                left -= read;
                written += read;
            }
            stream.flush();

            // 手机中途关掉页面走的是 FIN 不是 RST，read 会返回 -1 而不是抛异常。
            // 以前这里直接当成功收尾，留下半截文件还回 200——用户装出来是「解析包错误」。
            complete = written == length;
            if (complete) {
                // 车机最常见的断电方式是熄火，不 fsync 的话文件可能只剩 0 字节，
                // 而界面上早就显示「已传完」了
                try { stream.getFD().sync(); } catch (Throwable ignored) { }
            }
        } catch (Throwable error) {
            failure("写入失败 " + name, error);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Throwable ignored) { }
            }
        }

        if (!complete) {
            // 半截文件一并删掉，免得留下一个打不开的残包
            file.delete();
            send(out, 400, "text/plain; charset=utf-8", utf8("incomplete"));
            return;
        }

        event("收到 " + name + "（" + written + " 字节）");
        send(out, 200, "application/json; charset=utf-8",
            utf8("{\"ok\":true,\"size\":" + written + "}"));
    }

    // ------------------------------------------------------------------ 发送

    /**
     * 把车机上的文件回传给手机。
     *
     * <p>边读边写，不把整个文件读进内存——车机内存本来就紧，传个 1G 的包会直接 OOM。
     * 名字只做防穿越处理，不做长度压缩：文件已经在盘上了，名字必须原样对上，
     * 老版本可能留下过超过 200 字节的文件名，压缩了就 404。</p>
     */
    private void sendFile(OutputStream out, String target) throws IOException {
        String name = storedName(decode(param(target, "name")));
        File file = name == null ? null : new File(dir, name);
        if (file == null || !file.isFile() || !inside(file)) {
            send(out, 404, "text/plain; charset=utf-8", utf8("not found"));
            return;
        }

        long size = file.length();
        String head = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: " + contentType(name) + "\r\n"
            + "Content-Length: " + size + "\r\n"
            // 中文名走 RFC 5987 的 filename*，老浏览器退回 ASCII 版；
            // 只给 filename 的话，安卓浏览器存下来会变成一串下划线
            + "Content-Disposition: attachment; filename=\"" + asciiName(name)
            + "\"; filename*=UTF-8''" + percent(name) + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(utf8(head));

        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(file), 16384);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
        event("下载 " + name + "（" + size + " 字节）");
    }

    /** 防穿越的兜底：万一 safeName 哪条分支漏了，这里还能拦住读到目录外的东西。 */
    private boolean inside(File file) {
        try {
            String base = dir.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(base);
        } catch (Throwable error) {
            return false;
        }
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    /** Content-Disposition 里的 ASCII 兜底名：非 ASCII 一律换成下划线。 */
    private static String asciiName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') out.append('_');
            else out.append(c);
        }
        return out.length() == 0 ? "file" : out.toString();
    }

    /** RFC 5987 的 percent-encoding，只保留 unreserved 字符。 */
    private static String percent(String text) {
        byte[] bytes = utf8(text);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            boolean plain = (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '-' || value == '.' || value == '_' || value == '~';
            if (plain) {
                out.append((char) value);
            } else {
                out.append('%').append(HEX[value >> 4]).append(HEX[value & 0x0F]);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 删除

    /**
     * 删除车机上的一个文件。
     *
     * <p>不可逆，所以只做一件事：确认它确实是「车机传输」目录下的<b>普通文件</b>。
     * 不做回收站——/sdcard 上没有回收站这个概念，假装有反而更危险。</p>
     *
     * <p>二次确认放在页面上，不放在这里：服务端收到 DELETE 就该删，
     * 把「确定吗」塞进协议只会让接口变得没法用。</p>
     */
    private void remove(OutputStream out, String target) throws IOException {
        String name = storedName(decode(param(target, "name")));
        File file = name == null ? null : new File(dir, name);
        if (file == null || !file.isFile() || !inside(file)) {
            send(out, 404, "text/plain; charset=utf-8", utf8("not found"));
            return;
        }
        if (!file.delete()) {
            failure("删除失败 " + name, null);
            send(out, 500, "text/plain; charset=utf-8", utf8("delete failed"));
            return;
        }
        event("删除 " + name);
        send(out, 200, "application/json; charset=utf-8", utf8("{\"ok\":true}"));
    }

    // ------------------------------------------------------------------ 列表

    private String listJson() {
        List<File> files = new ArrayList<File>();
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null) {
            for (File file : all) {
                if (file.isFile()) files.add(file);
            }
        }
        // 最近改动的排在前面，刚传完的一眼就能看到
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long left = a.lastModified();
                long right = b.lastModified();
                return left == right ? 0 : (left > right ? -1 : 1);
            }
        });

        // 上限放到 200：以前卡 50 条，传 60 个文件会有 10 个在页面上凭空消失
        StringBuilder json = new StringBuilder("[");
        int count = 0;
        for (File file : files) {
            if (count >= 200) break;
            if (count > 0) json.append(',');
            json.append("{\"name\":\"").append(escape(file.getName()))
                .append("\",\"size\":").append(file.length()).append('}');
            count++;
        }
        return json.append(']').toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(' ');
            else out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 同名文件不覆盖，改成「名字 (1).扩展名」。传第二遍时不会把第一份弄丢。
     *
     * <p>必须加锁并用 createNewFile() 占位：exists() 再 open 之间是有窗口的，
     * 两台手机同时传同名文件会双双判定「不存在」、双双拿到同一个路径，
     * 后开的那个把先开的截断，两份数据交错写进同一份文件，而且两边都收到 200。
     * 传文件本来就可能是两个人一起传，这个窗口真的会被踩到。</p>
     */
    private synchronized File unique(String name) {
        File file = new File(dir, name);
        if (claim(file)) return file;

        String base = name;
        String suffix = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            suffix = name.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            File candidate = new File(dir, base + " (" + index + ")" + suffix);
            if (claim(candidate)) return candidate;
        }
        for (int index = 0; index < 100; index++) {
            File candidate = new File(dir, System.currentTimeMillis() + "-" + index + suffix);
            if (claim(candidate)) return candidate;
        }
        return null;
    }

    private static boolean claim(File file) {
        try {
            return file.createNewFile();
        } catch (Throwable error) {
            return false;
        }
    }

    /** 去掉路径分隔符，防止用 ../../ 把文件写到别的目录去。 */
    private static String safeName(String raw) {
        String name = storedName(raw);
        return name == null ? null : fit(name);
    }

    /** 只做防穿越和去空白，不压长度。下载时用这个：盘上的名字必须原样对上。 */
    private static String storedName(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.trim();
        if (name.length() == 0 || ".".equals(name) || "..".equals(name)) return null;
        return name;
    }

    /**
     * 把文件名压到 ext4 能接受的长度。
     *
     * <p>以前按 120 个<b>字符</b>截断，但 ext4 的上限是 255 <b>字节</b>。一个 85 个汉字的
     * 升级包名字（255 字节）根本到不了截断阈值，却已经越界，open 直接 ENAMETOOLONG，
     * 手机端只看到「失败 500」。而且原来是从前面砍，名字被砍得只剩后半截。</p>
     */
    private static String fit(String name) {
        if (utf8(name).length <= MAX_NAME_BYTES) return name;

        String base = name;
        String suffix = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 12) {
            base = name.substring(0, dot);
            suffix = name.substring(dot);
        }
        int budget = MAX_NAME_BYTES - utf8(suffix).length;
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            int width = utf8(String.valueOf(c)).length;
            if (used + width > budget) break;
            out.append(c);
            used += width;
        }
        if (out.length() == 0) return null;
        return out.toString() + suffix;
    }

    private static String param(String target, String key) {
        int mark = target.indexOf('?');
        if (mark < 0) return null;
        String query = target.substring(mark + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equal = pair.indexOf('=');
            if (equal <= 0) continue;
            if (pair.substring(0, equal).equals(key)) return pair.substring(equal + 1);
        }
        return null;
    }

    /** 只解 %XX。不用 URLDecoder 是因为它会把文件名里的 + 变成空格。 */
    private static String decode(String raw) {
        if (raw == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int high = hex(raw.charAt(i + 1));
                int low = hex(raw.charAt(i + 2));
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = utf8(String.valueOf(c));
            out.write(bytes, 0, bytes.length);
        }
        try {
            return out.toString("UTF-8");
        } catch (Throwable error) {
            return null;
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > 4096) return line.toString();
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void send(OutputStream out, int code, String type, byte[] body)
        throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(utf8(head));
        out.write(body);
        out.flush();
    }

    private static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (Throwable error) {
            return new byte[0];
        }
    }

    private byte[] readPage(Context context) {
        InputStream in = null;
        try {
            in = context.getResources().openRawResource(R.raw.transfer_page);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Throwable error) {
            return utf8("<!DOCTYPE html><meta charset=utf-8><p>页面读取失败</p>");
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 取本机在热点网段里的 IPv4。不写死 192.168.43.1——厂商可能改过网段。
     * 读不到时退回 Android 的默认网关地址。
     */
    public static String localIp() {
        try {
            List<NetworkInterface> cards = Collections.list(NetworkInterface.getNetworkInterfaces());

            // 第一轮只认私有网段：接口枚举顺序不保证，拿到一个能路由但手机够不着的
            // 对端地址，比拿不到还难查
            for (NetworkInterface card : cards) {
                String ip = usableIp(card, true);
                if (ip != null) return ip;
            }
            for (NetworkInterface card : cards) {
                String ip = usableIp(card, false);
                if (ip != null) return ip;
            }
        } catch (Throwable ignored) {
            // 权限或系统限制，走下面的兜底
        }
        return "192.168.43.1";
    }

    private static String usableIp(NetworkInterface card, boolean privateOnly) {
        try {
            if (!card.isUp() || card.isLoopback()) return null;
            String name = card.getName();
            if (name == null) return null;
            // ap0 / softap0 是另一些 ROM 给热点起的名字，漏掉就会退到兜底地址
            if (!name.startsWith("wlan") && !name.startsWith("eth")
                && !name.startsWith("ap") && !name.startsWith("softap")) return null;

            for (InetAddress address : Collections.list(card.getInetAddresses())) {
                if (!(address instanceof Inet4Address)) continue;
                if (address.isLoopbackAddress()) continue;
                String ip = address.getHostAddress();
                if (privateOnly && !isPrivate(ip)) continue;
                return ip;
            }
        } catch (Throwable ignored) {
            // 单张网卡读失败不影响其它网卡
        }
        return null;
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.");
    }

    // ------------------------------------------------------------------ 日志

    private void event(String message) {
        ReportLog target = log;
        if (target != null) target.event("传输", message);
    }

    private void failure(String message, Throwable error) {
        ReportLog target = log;
        if (target == null) return;
        if (error == null) target.event("传输", message);
        else target.error("传输", message, error);
    }
}
