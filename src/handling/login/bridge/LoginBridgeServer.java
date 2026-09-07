package handling.login.bridge;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import client.MapleClient;
import constants.ServerConstants;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import server.ServerProperties;
import tools.MapleAESOFB;

/**
 * Phase-1 localhost-only HTTP JSON bridge for an external Go login UI.
 * Bind address is always 127.0.0.1 (never 0.0.0.0).
 */
public final class LoginBridgeServer {
    static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String BIND_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 17979;
    static final long SESSION_TTL_MS = 30L * 60L * 1000L;

    private static HttpServer httpServer;
    static final ConcurrentHashMap sessions = new ConcurrentHashMap();

    private LoginBridgeServer() {
    }

    public static synchronized void startIfEnabled() {
        if (httpServer != null) {
            return;
        }
        // Default true for this fork; set RoyMS.LoginBridge=false to disable.
        if (!Boolean.parseBoolean(ServerProperties.getProperty("RoyMS.LoginBridge", "true"))) {
            System.out.println("[LoginBridge] disabled (RoyMS.LoginBridge=false)");
            return;
        }
        int port = DEFAULT_PORT;
        try {
            port = Integer.parseInt(ServerProperties.getProperty("RoyMS.LoginBridgePort", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            System.err.println("[LoginBridge] invalid RoyMS.LoginBridgePort, using " + DEFAULT_PORT);
            port = DEFAULT_PORT;
        }
        try {
            httpServer = HttpServer.create(new InetSocketAddress(BIND_HOST, port), 0);
            httpServer.createContext("/health", new LoginBridgeHandlers.HealthHandler());
            httpServer.createContext("/api/login", new LoginBridgeHandlers.LoginHandler());
            httpServer.createContext("/api/worlds", new LoginBridgeHandlers.WorldsHandler());
            httpServer.createContext("/api/characters", new LoginBridgeHandlers.CharactersHandler());
            httpServer.createContext("/api/select", new LoginBridgeHandlers.SelectHandler());
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            System.out.println("[LoginBridge] listening on http://" + BIND_HOST + ":" + port + " (localhost only)");
        } catch (IOException e) {
            System.err.println("[LoginBridge] failed to bind " + BIND_HOST + ":" + port + " - " + e.getMessage());
            httpServer = null;
        }
    }

    public static synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            sessions.clear();
            System.out.println("[LoginBridge] stopped");
        }
    }

    static void purgeExpired() {
        final long now = System.currentTimeMillis();
        final Iterator it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry e = (Map.Entry) it.next();
            final BridgeSession s = (BridgeSession) e.getValue();
            if (now - s.createdAt > SESSION_TTL_MS) {
                it.remove();
            }
        }
    }

    static BridgeSession requireSession(final HttpExchange ex) throws IOException {
        purgeExpired();
        final Headers h = ex.getRequestHeaders();
        String auth = h.getFirst("Authorization");
        if (auth == null || auth.length() == 0) {
            auth = h.getFirst("X-Login-Token");
        }
        String token = null;
        if (auth != null) {
            if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = auth.substring(7).trim();
            } else {
                token = auth.trim();
            }
        }
        if (token == null || token.length() == 0) {
            writeJson(ex, 401, "{\"ok\":false,\"error\":\"missing_token\"}");
            return null;
        }
        final BridgeSession s = (BridgeSession) sessions.get(token);
        if (s == null) {
            writeJson(ex, 401, "{\"ok\":false,\"error\":\"invalid_or_expired_token\"}");
            return null;
        }
        s.createdAt = System.currentTimeMillis();
        return s;
    }

    static MapleClient newBridgeClient() {
        final byte[] iv = new byte[] { 1, 2, 3, 4 };
        final short ver = ServerConstants.MAPLE_VERSION;
        return new MapleClient(new MapleAESOFB(iv, (short) (65535 - ver)), new MapleAESOFB(iv, ver), new LoginBridgeSession("127.0.0.1"));
    }

    static String readBody(final HttpExchange ex) throws IOException {
        final InputStream in = ex.getRequestBody();
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                bos.write(buf, 0, n);
            }
        }
        return new String(bos.toByteArray(), UTF8);
    }

    static void writeJson(final HttpExchange ex, final int code, final String json) throws IOException {
        final byte[] bytes = json.getBytes(UTF8);
        final Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        final OutputStream os = ex.getResponseBody();
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    static String loginErrorMessage(final int code) {
        switch (code) {
            case 3:
                return "banned";
            case 4:
                return "wrong_password";
            case 5:
                return "not_registered";
            case 7:
                return "already_logged_in";
            default:
                return "login_failed_" + code;
        }
    }

    static void refreshChannelLoad() {
        final Map load = ChannelServer.getChannelLoad();
        if (load == null || load.isEmpty()) {
            return;
        }
        int usersOn = 0;
        final double loadFactor = 1200.0 / (LoginServer.getUserLimit() / (double) load.size());
        final Iterator it = load.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry e = (Map.Entry) it.next();
            final int v = ((Integer) e.getValue()).intValue();
            usersOn += v;
            e.setValue(Integer.valueOf(Math.min(1200, (int) (v * loadFactor))));
        }
        LoginServer.setLoad(load, usersOn);
    }

    static final class BridgeSession {
        final String token;
        final MapleClient client;
        long createdAt;

        BridgeSession(final String token, final MapleClient client) {
            this.token = token;
            this.client = client;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
