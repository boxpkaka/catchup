package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 访问 catchup 的 /v1 接口（经 Caddy 转到服务器的 catchup serve）。需在后台线程调用。
 * 首次使用自动注册设备并保存令牌：服务器上还没有已批准设备时这台手机直接通过，否则需在服务器上批准。
 */
final class Api {
    static final class Failure extends IOException {
        final int code;

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    interface Sink {
        void event(JSONObject e);
    }

    private Api() {}

    /** 流式接口（对话）：逐条把服务端 SSE 的 data 交给 sink，在调用线程里回调。 */
    static void stream(Context c, String path, JSONObject body, Sink sink) throws Exception {
        ensureDevice(c);
        HttpURLConnection conn = (HttpURLConnection) new URL(Store.server(c) + path).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + Store.token(c));
        conn.setRequestProperty("Accept", "text/event-stream");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            if (code >= 400) throw new Failure(code, "HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) sink.event(new JSONObject(line.substring(5).trim()));
            }
        } finally {
            conn.disconnect();
        }
    }

    static JSONObject get(Context c, String path) throws Exception {
        return authed(c, "GET", path, null);
    }

    static JSONObject post(Context c, String path, JSONObject body) throws Exception {
        return authed(c, "POST", path, body);
    }

    /** 带令牌调用；服务器不认识这个令牌（比如数据库重建过）时重新注册一次再试。 */
    private static JSONObject authed(Context c, String method, String path, JSONObject body) throws Exception {
        ensureDevice(c);
        try {
            return call(c, method, path, body, Store.token(c));
        } catch (Failure f) {
            if (f.code != 401) throw f;
            Store.setToken(c, "");
            ensureDevice(c);
            return call(c, method, path, body, Store.token(c));
        }
    }

    static void ensureDevice(Context c) throws Exception {
        if (!Store.token(c).isEmpty()) return;
        JSONObject r = call(c, "POST", "/v1/devices", new JSONObject().put("name", Build.MODEL), null);
        Store.setToken(c, r.getString("token"));
        Trace.log(c, "device.register", "id=" + r.optLong("id") + " approved=" + r.optBoolean("approved"));
    }

    private static JSONObject call(Context c, String method, String path, JSONObject body, String token)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(Store.server(c) + path).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String text = in == null ? "{}" : Updater.read(in);
            JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code >= 400) throw new Failure(code, json.optString("error", "HTTP " + code));
            return json;
        } finally {
            conn.disconnect();
        }
    }
}
