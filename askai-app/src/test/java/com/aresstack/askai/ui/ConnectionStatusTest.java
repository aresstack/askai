package com.aresstack.askai.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The connection indicator maps a server-version probe to a semantic status. */
public class ConnectionStatusTest {

    @Test
    public void versionPresentMeansConnected() {
        assertEquals(ConnectionStatus.CONNECTED, ConnectionStatus.forVersion("0.1.29"));
    }

    @Test
    public void missingVersionMeansNotReachable() {
        assertEquals(ConnectionStatus.NOT_REACHABLE, ConnectionStatus.forVersion(""));
        assertEquals(ConnectionStatus.NOT_REACHABLE, ConnectionStatus.forVersion(null));
    }
}
