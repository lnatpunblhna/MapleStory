package handling.login.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import client.MapleCharacter;
import client.MapleClient;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import server.ServerProperties;

/** HTTP route handlers for LoginBridgeServer. */
public final class LoginBridgeHandlers {
    private LoginBridgeHandlers() {
    }

    public static final class HealthHandler implements HttpHandler {
        public void handle(final HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LoginBridgeServer.writeJson(ex, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            LoginBridgeServer.writeJson(ex, 200, "{\"ok\":true,\"service\":\"LoginBridge\",\"bind\":\"127.0.0.1\"}");
        }
    }

    public static final class LoginHandler implements HttpHandler {
        public void handle(final HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                LoginBridgeServer.writeJson(ex, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            final String body = LoginBridgeServer.readBody(ex);
            final String username = LoginBridgeJson.stringField(body, "username");
            final String password = LoginBridgeJson.stringField(body, "password");
            if (username == null || password == null || username.length() == 0) {
                LoginBridgeServer.writeJson(ex, 400, "{\"ok\":false,\"error\":\"username_and_password_required\"}");
                return;
            }
            final MapleClient c = LoginBridgeServer.newBridgeClient();
            c.setAccountName(username);
            final int loginok = c.login(username, password, false);
            if (loginok != 0) {
                LoginBridgeServer.writeJson(ex, 401, "{\"ok\":false,\"error\":\"" + LoginBridgeServer.loginErrorMessage(loginok) + "\",\"code\":" + loginok + "}");
                return;
            }
            if (c.getTempBanCalendar() != null && c.getTempBanCalendar().getTimeInMillis() != 0L) {
                LoginBridgeServer.writeJson(ex, 403, "{\"ok\":false,\"error\":\"temp_banned\"}");
                return;
            }
            if (LoginServer.isAdminOnly() && !c.isGm()) {
                LoginBridgeServer.writeJson(ex, 403, "{\"ok\":false,\"error\":\"admin_only\"}");
                return;
            }
            if (c.getGender() == 10) {
                LoginBridgeServer.writeJson(ex, 409, "{\"ok\":false,\"error\":\"gender_required\",\"hint\":\"set gender once via stock login client (Phase 1)\"}");
                return;
            }
            final int fin = c.finishLogin();
            if (fin != 0) {
                LoginBridgeServer.writeJson(ex, 409, "{\"ok\":false,\"error\":\"" + LoginBridgeServer.loginErrorMessage(fin) + "\",\"code\":" + fin + "}");
                return;
            }
            final String token = UUID.randomUUID().toString().replace("-", "");
            LoginBridgeServer.sessions.put(token, new LoginBridgeServer.BridgeSession(token, c));
            final StringBuilder sb = new StringBuilder(160);
            sb.append("{\"ok\":true,\"token\":\"").append(token).append("\"");
            sb.append(",\"accountId\":").append(c.getAccID());
            sb.append(",\"username\":\"").append(LoginBridgeJson.escape(username)).append("\"");
            sb.append(",\"gender\":").append((int) c.getGender());
            sb.append(",\"gm\":").append(c.isGm() ? "true" : "false");
            sb.append("}");
            LoginBridgeServer.writeJson(ex, 200, sb.toString());
        }
    }

    public static final class WorldsHandler implements HttpHandler {
        public void handle(final HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LoginBridgeServer.writeJson(ex, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            if (LoginBridgeServer.requireSession(ex) == null) {
                return;
            }
            LoginBridgeServer.refreshChannelLoad();
            final StringBuilder chans = new StringBuilder();
            chans.append('[');
            boolean first = true;
            final Iterator it = ChannelServer.getAllInstances().iterator();
            while (it.hasNext()) {
                final ChannelServer cs = (ChannelServer) it.next();
                if (!first) {
                    chans.append(',');
                }
                first = false;
                final String ip = cs.getIP();
                String host = ServerProperties.getProperty("RoyMS.IP", "127.0.0.1");
                int port = cs.getPort();
                if (ip != null && ip.indexOf(':') > 0) {
                    final String[] parts = ip.split(":");
                    host = parts[0];
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignore) {
                    }
                }
                Integer load = null;
                if (LoginServer.getLoad() != null) {
                    load = (Integer) LoginServer.getLoad().get(Integer.valueOf(cs.getChannel()));
                }
                chans.append("{\"id\":").append(cs.getChannel());
                chans.append(",\"load\":").append(load == null ? cs.getConnectedClients() : load.intValue());
                chans.append(",\"host\":\"").append(LoginBridgeJson.escape(host)).append("\"");
                chans.append(",\"port\":").append(port);
                chans.append('}');
            }
            chans.append(']');
            final StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"worlds\":[{");
            sb.append("\"id\":0");
            sb.append(",\"name\":\"").append(LoginBridgeJson.escape(LoginServer.getServerName())).append("\"");
            sb.append(",\"flag\":").append((int) LoginServer.getFlag());
            sb.append(",\"eventMessage\":\"").append(LoginBridgeJson.escape(LoginServer.getEventMessage())).append("\"");
            sb.append(",\"userLimit\":").append(LoginServer.getUserLimit());
            sb.append(",\"usersOn\":").append(LoginServer.getUsersOn());
            sb.append(",\"maxCharacters\":").append(LoginServer.getMaxCharacters());
            sb.append(",\"channels\":").append(chans);
            sb.append("}]}");
            LoginBridgeServer.writeJson(ex, 200, sb.toString());
        }
    }

    public static final class CharactersHandler implements HttpHandler {
        public void handle(final HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LoginBridgeServer.writeJson(ex, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            final LoginBridgeServer.BridgeSession s = LoginBridgeServer.requireSession(ex);
            if (s == null) {
                return;
            }
            int world = 0;
            final String q = ex.getRequestURI().getRawQuery();
            if (q != null) {
                final String[] parts = q.split("&");
                for (int i = 0; i < parts.length; ++i) {
                    final String p = parts[i];
                    if (p.startsWith("world=")) {
                        try {
                            world = Integer.parseInt(p.substring(6));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }
            s.client.setWorld(world);
            final List chars = s.client.loadCharacters(world);
            final StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"world\":").append(world).append(",\"characters\":[");
            if (chars != null) {
                for (int i = 0; i < chars.size(); ++i) {
                    final MapleCharacter ch = (MapleCharacter) chars.get(i);
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"id\":").append(ch.getId());
                    sb.append(",\"name\":\"").append(LoginBridgeJson.escape(ch.getName())).append("\"");
                    sb.append(",\"level\":").append(ch.getLevel());
                    sb.append(",\"job\":").append(ch.getJob());
                    sb.append(",\"gender\":").append((int) ch.getGender());
                    sb.append(",\"world\":").append(world);
                    sb.append('}');
                }
            }
            sb.append("]}");
            LoginBridgeServer.writeJson(ex, 200, sb.toString());
        }
    }

    public static final class SelectHandler implements HttpHandler {
        public void handle(final HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                LoginBridgeServer.writeJson(ex, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            final LoginBridgeServer.BridgeSession s = LoginBridgeServer.requireSession(ex);
            if (s == null) {
                return;
            }
            final String body = LoginBridgeServer.readBody(ex);
            final Integer charIdObj = LoginBridgeJson.intField(body, "characterId");
            Integer channelObj = LoginBridgeJson.intField(body, "channel");
            if (channelObj == null) {
                channelObj = LoginBridgeJson.intField(body, "channelId");
            }
            if (charIdObj == null || channelObj == null) {
                LoginBridgeServer.writeJson(ex, 400, "{\"ok\":false,\"error\":\"characterId_and_channel_required\"}");
                return;
            }
            final int charId = charIdObj.intValue();
            final int channel = channelObj.intValue();
            if (!s.client.isLoggedIn() || !s.client.login_Auth(charId)) {
                s.client.loadCharacters(s.client.getWorld());
                if (!s.client.login_Auth(charId)) {
                    LoginBridgeServer.writeJson(ex, 403, "{\"ok\":false,\"error\":\"character_not_on_account\"}");
                    return;
                }
            }
            if (ChannelServer.getInstance(channel) == null) {
                LoginBridgeServer.writeJson(ex, 400, "{\"ok\":false,\"error\":\"invalid_channel\"}");
                return;
            }
            if (s.client.getWorld() != 0) {
                s.client.setWorld(0);
            }
            s.client.setChannel(channel);
            final ChannelServer cs = ChannelServer.getInstance(channel);
            String clientIp = LoginBridgeJson.stringField(body, "clientIp");
            if (clientIp == null || clientIp.length() == 0) {
                final String sip = s.client.getSessionIPAddress();
                clientIp = sip.substring(sip.indexOf('/') + 1);
            }
            LoginServer.putLoginAuth(charId, clientIp, s.client.getTempIP(), channel);
            s.client.updateLoginState(MapleClient.LOGIN_SERVER_TRANSITION, s.client.getSessionIPAddress());

            String host = ServerProperties.getProperty("RoyMS.IP", "127.0.0.1");
            int port = cs.getPort();
            final String ip = cs.getIP();
            if (ip != null && ip.indexOf(':') > 0) {
                final String[] parts = ip.split(":");
                host = parts[0];
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignore) {
                }
            }
            final StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true");
            sb.append(",\"characterId\":").append(charId);
            sb.append(",\"channel\":").append(channel);
            sb.append(",\"host\":\"").append(LoginBridgeJson.escape(host)).append("\"");
            sb.append(",\"port\":").append(port);
            sb.append(",\"authIp\":\"").append(LoginBridgeJson.escape(clientIp)).append("\"");
            sb.append(",\"hint\":\"launch stock CMS079 client to host:port; PLAYER_LOGGEDIN uses putLoginAuth\"");
            sb.append('}');
            LoginBridgeServer.writeJson(ex, 200, sb.toString());
        }
    }
}
