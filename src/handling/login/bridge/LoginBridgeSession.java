package handling.login.bridge;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.apache.mina.core.session.DummySession;

/**
 * Minimal IoSession for LoginBridge so MapleClient.getSessionIPAddress()
 * and ban checks see a localhost remote address (matches stock login handoff).
 */
public class LoginBridgeSession extends DummySession {
    private final SocketAddress remote;

    public LoginBridgeSession(final String ip) {
        this.remote = new InetSocketAddress(ip == null ? "127.0.0.1" : ip, 0);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.remote;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }
}
