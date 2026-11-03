package com.aresstack.askai.net;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfiguredConnectionsTest {

    static {
        // As AskAiApp.main does it: before the first HTTP connection of the JVM.
        ConfiguredConnections.allowBasicProxyTunneling();
    }

    @Test
    public void httpsTunnelThroughAProxyCarriesTheBasicCredentials() throws Exception {
        final List<String> connectAuthorizations = Collections.synchronizedList(new ArrayList<String>());
        try (final ServerSocket proxy = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket socket = proxy.accept()) {
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                            String auth = null;
                            String line = in.readLine();
                            assertTrue(line, line.startsWith("CONNECT "));
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase().startsWith("proxy-authorization:")) {
                                    auth = line.substring(line.indexOf(':') + 1).trim();
                                }
                            }
                            connectAuthorizations.add(String.valueOf(auth));
                            OutputStream out = socket.getOutputStream();
                            String reply = auth == null
                                    ? "HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n"
                                    + "Connection: close\r\n\r\n"
                                    : "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                            out.write(reply.getBytes(StandardCharsets.ISO_8859_1));
                            out.flush();
                        }
                    }
                } catch (Exception ignored) {
                    // the test asserts on what arrived
                }
            }, "fake-proxy");
            server.setDaemon(true);
            server.start();

            ConfiguredConnections connections = new ConfiguredConnections(
                    new ProxyConfiguration(ProxyConfiguration.MANUAL_PROXY, "", "", "", "127.0.0.1",
                            proxy.getLocalPort()),
                    CertificateTrustConfiguration.defaults(),
                    new HttpClientConfiguration("", HttpClientConfiguration.ProxyAuthMode.BASIC, "alice", "s3cret",
                            false));
            HttpURLConnection connection = connections.open("https://example.invalid/voice.onnx");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try {
                connection.getResponseCode();
            } catch (java.io.IOException expected) {
                // the fake proxy refuses the tunnel after checking the credentials
            }
            server.join(5000);
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "alice:s3cret".getBytes(StandardCharsets.UTF_8));
        assertTrue("the proxy saw the challenge answered: " + connectAuthorizations,
                connectAuthorizations.contains(expected));
        assertEquals(expected, connectAuthorizations.get(connectAuthorizations.size() - 1));
    }
}
