package com.aresstack.askai.net;

/**
 * Configures <em>which</em> certificate sources Java trusts during the TLS handshake. This is
 * deliberately kept separate from {@link ProxyConfiguration}, which only decides <em>how</em> AskAI
 * reaches the network (PAC/WPAD/manual proxy). Proxy resolution and certificate trust are two
 * independent concerns and must not be mixed.
 *
 * <p>The three sources are additive trust anchors:</p>
 * <ul>
 *   <li>{@code useJvmDefault} &mdash; the bundled {@code cacerts} truststore.</li>
 *   <li>{@code useWindowsRoot} &mdash; the {@code Windows-ROOT} store exposed by SunMSCAPI.</li>
 *   <li>{@code useWindowsCaStores} &mdash; the Windows Root <em>and</em> Intermediate CA stores
 *       (Machine and User scope), which SunMSCAPI does not expose. This is what lets PKIX build a
 *       chain when a TLS-intercepting corporate proxy omits the intermediate from the handshake.</li>
 * </ul>
 */
public final class CertificateTrustConfiguration {

    private final boolean useJvmDefault;
    private final boolean useWindowsRoot;
    private final boolean useWindowsCaStores;

    public CertificateTrustConfiguration(boolean useJvmDefault, boolean useWindowsRoot, boolean useWindowsCaStores) {
        this.useJvmDefault = useJvmDefault;
        this.useWindowsRoot = useWindowsRoot;
        this.useWindowsCaStores = useWindowsCaStores;
    }

    /**
     * @return the default trust configuration: trust the JVM {@code cacerts}, {@code Windows-ROOT},
     *         and the Windows Root/Intermediate CA stores. This preserves the previous behaviour that
     *         made corporate MITM proxies work out of the box.
     */
    public static CertificateTrustConfiguration defaults() {
        return new CertificateTrustConfiguration(true, true, true);
    }

    public boolean isUseJvmDefault() {
        return useJvmDefault;
    }

    public boolean isUseWindowsRoot() {
        return useWindowsRoot;
    }

    public boolean isUseWindowsCaStores() {
        return useWindowsCaStores;
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CertificateTrustConfiguration)) {
            return false;
        }
        CertificateTrustConfiguration that = (CertificateTrustConfiguration) other;
        return useJvmDefault == that.useJvmDefault
                && useWindowsRoot == that.useWindowsRoot
                && useWindowsCaStores == that.useWindowsCaStores;
    }

    public int hashCode() {
        int result = useJvmDefault ? 1 : 0;
        result = 31 * result + (useWindowsRoot ? 1 : 0);
        result = 31 * result + (useWindowsCaStores ? 1 : 0);
        return result;
    }

    public String toString() {
        return "CertificateTrustConfiguration{jvmDefault=" + useJvmDefault
                + ", windowsRoot=" + useWindowsRoot
                + ", windowsCaStores=" + useWindowsCaStores + '}';
    }
}
