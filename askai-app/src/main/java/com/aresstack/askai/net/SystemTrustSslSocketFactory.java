package com.aresstack.askai.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link SSLSocketFactory} whose trust anchors are chosen by a
 * {@link CertificateTrustConfiguration}: any combination of the JVM default {@code cacerts}, the
 * Windows {@code Windows-ROOT} store, and the Windows Root/Intermediate CA stores.
 *
 * <p>Corporate proxies frequently terminate TLS and re-sign responses with a private CA. That CA is
 * installed in the Windows certificate store (so browsers and {@code curl} work) but is unknown to
 * the JVM's bundled {@code cacerts}. Without merging the Windows store, every HTTPS request to
 * huggingface.co fails during the TLS handshake with
 * {@code PKIX path building failed ... unable to find valid certification path to requested target},
 * even though proxy discovery/resolution succeeds.</p>
 *
 * <p>Unlike the previous implementation this factory does not silently swallow failures: the
 * {@link Result} it returns records which trust sources were actually loaded, how many certificates
 * the Windows export produced, and any diagnostic messages, so the HuggingFace connection test can
 * show whether a PKIX failure is due to a missing trust source rather than a proxy problem.</p>
 */
public final class SystemTrustSslSocketFactory {

    private static final Map<CertificateTrustConfiguration, Result> CACHE =
            new HashMap<CertificateTrustConfiguration, Result>();

    private SystemTrustSslSocketFactory() {
    }

    /**
     * The outcome of assembling the trust factory: the socket factory to use plus diagnostics about
     * which sources contributed and any problems encountered.
     */
    public static final class Result {

        private final SSLSocketFactory socketFactory;
        private final boolean jvmDefaultTrusted;
        private final boolean windowsRootTrusted;
        private final boolean windowsCaStoresTrusted;
        private final int windowsExportedCertificateCount;
        private final List<String> diagnostics;

        Result(SSLSocketFactory socketFactory, boolean jvmDefaultTrusted, boolean windowsRootTrusted,
               boolean windowsCaStoresTrusted, int windowsExportedCertificateCount, List<String> diagnostics) {
            this.socketFactory = socketFactory;
            this.jvmDefaultTrusted = jvmDefaultTrusted;
            this.windowsRootTrusted = windowsRootTrusted;
            this.windowsCaStoresTrusted = windowsCaStoresTrusted;
            this.windowsExportedCertificateCount = windowsExportedCertificateCount;
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }

        public SSLSocketFactory getSocketFactory() {
            return socketFactory;
        }

        public boolean isJvmDefaultTrusted() {
            return jvmDefaultTrusted;
        }

        public boolean isWindowsRootTrusted() {
            return windowsRootTrusted;
        }

        public boolean isWindowsCaStoresTrusted() {
            return windowsCaStoresTrusted;
        }

        public int getWindowsExportedCertificateCount() {
            return windowsExportedCertificateCount;
        }

        public List<String> getDiagnostics() {
            return diagnostics;
        }
    }

    /**
     * @param trust the trust sources to enable; {@code null} is treated as
     *              {@link CertificateTrustConfiguration#defaults()}.
     * @return a cached {@link Result} for the given configuration. The factory build is cached because
     *         reading the Windows CA stores spawns PowerShell and is comparatively expensive.
     */
    public static Result build(CertificateTrustConfiguration trust) {
        CertificateTrustConfiguration configuration = trust == null
                ? CertificateTrustConfiguration.defaults() : trust;
        synchronized (CACHE) {
            Result cached = CACHE.get(configuration);
            if (cached != null) {
                return cached;
            }
            Result result = create(configuration);
            CACHE.put(configuration, result);
            return result;
        }
    }

    private static Result create(CertificateTrustConfiguration configuration) {
        List<X509TrustManager> delegates = new ArrayList<X509TrustManager>();
        List<String> diagnostics = new ArrayList<String>();
        boolean jvmDefault = false;
        boolean windowsRoot = false;
        boolean windowsCa = false;
        int exportedCount = 0;

        if (configuration.isUseJvmDefault()) {
            if (addTrustManager(delegates, null)) {
                jvmDefault = true;
                diagnostics.add("JVM default cacerts loaded.");
            } else {
                diagnostics.add("JVM default cacerts could not be loaded.");
            }
        }

        if (configuration.isUseWindowsRoot()) {
            windowsRoot = addWindowsRootTrustManager(delegates, diagnostics);
        }

        if (configuration.isUseWindowsCaStores()) {
            WindowsCertificateStores.Result stores = WindowsCertificateStores.loadRootAndIntermediateCertificates();
            exportedCount = stores.getCertificates().size();
            if (stores.getError() != null) {
                diagnostics.add("Windows CA/Intermediate export: " + stores.getError());
            }
            windowsCa = addWindowsCaTrustManager(delegates, stores.getCertificates(), diagnostics);
        }

        SSLSocketFactory factory;
        if (delegates.isEmpty()) {
            diagnostics.add("No trust source produced a trust manager; using the JVM default SSL socket factory.");
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        } else {
            factory = buildCompositeFactory(delegates, diagnostics);
        }

        return new Result(factory, jvmDefault, windowsRoot, windowsCa, exportedCount, diagnostics);
    }

    private static SSLSocketFactory buildCompositeFactory(List<X509TrustManager> delegates, List<String> diagnostics) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeX509TrustManager(delegates)}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException ex) {
            diagnostics.add("Could not initialise the combined SSL context (" + messageOf(ex)
                    + "); using the JVM default SSL socket factory.");
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private static boolean addWindowsRootTrustManager(List<X509TrustManager> delegates, List<String> diagnostics) {
        try {
            KeyStore keyStore = KeyStore.getInstance("Windows-ROOT");
            keyStore.load(null, null);
            if (addTrustManager(delegates, keyStore)) {
                diagnostics.add("Windows-ROOT store loaded.");
                return true;
            }
            diagnostics.add("Windows-ROOT store produced no trust manager.");
            return false;
        } catch (Exception ex) {
            diagnostics.add("Windows-ROOT store unavailable: " + messageOf(ex));
            return false;
        }
    }

    /**
     * Adds the certificates from the Windows Root <em>and</em> Intermediate Certification Authorities
     * stores (which {@code SunMSCAPI}'s {@code Windows-ROOT} keystore does not expose) as trust
     * anchors. Making the corporate intermediate CA an anchor lets PKIX build the chain even when a
     * TLS-intercepting proxy omits the intermediate from the handshake, which is the case
     * {@code Windows-ROOT} alone cannot cover.
     */
    private static boolean addWindowsCaTrustManager(List<X509TrustManager> delegates,
                                                    List<X509Certificate> certificates, List<String> diagnostics) {
        if (certificates.isEmpty()) {
            diagnostics.add("Windows CA/Intermediate stores contributed no certificates.");
            return false;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            for (int i = 0; i < certificates.size(); i++) {
                keyStore.setCertificateEntry("askai-windows-ca-" + i, certificates.get(i));
            }
            if (addTrustManager(delegates, keyStore)) {
                diagnostics.add("Windows CA/Intermediate stores loaded " + certificates.size() + " certificate(s).");
                return true;
            }
            diagnostics.add("Windows CA/Intermediate stores produced no trust manager.");
            return false;
        } catch (Exception ex) {
            diagnostics.add("Could not assemble Windows CA/Intermediate anchors: " + messageOf(ex));
            return false;
        }
    }

    private static boolean addTrustManager(List<X509TrustManager> delegates, KeyStore keyStore) {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            boolean added = false;
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    delegates.add((X509TrustManager) trustManager);
                    added = true;
                }
            }
            return added;
        } catch (GeneralSecurityException ex) {
            return false;
        }
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message != null && message.trim().length() > 0 ? message : throwable.getClass().getName();
    }

    /**
     * Accepts a certificate chain when <em>any</em> of the delegate trust managers accepts it.
     */
    private static final class CompositeX509TrustManager implements X509TrustManager {

        private final List<X509TrustManager> delegates;

        CompositeX509TrustManager(List<X509TrustManager> delegates) {
            this.delegates = delegates;
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    delegate.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    last = ex;
                }
            }
            throw last != null ? last
                    : new CertificateException("No trust manager accepted the client certificate chain.");
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    last = ex;
                }
            }
            throw last != null ? last
                    : new CertificateException("No trust manager accepted the server certificate chain.");
        }

        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<X509Certificate>();
            for (X509TrustManager delegate : delegates) {
                X509Certificate[] certificates = delegate.getAcceptedIssuers();
                if (certificates != null) {
                    for (X509Certificate certificate : certificates) {
                        issuers.add(certificate);
                    }
                }
            }
            return issuers.toArray(new X509Certificate[issuers.size()]);
        }
    }
}
