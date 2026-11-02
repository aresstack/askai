package com.aresstack.askai.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads X.509 certificates from the Windows certificate stores that Java's {@code SunMSCAPI}
 * provider does <em>not</em> expose.
 *
 * <p>{@code SunMSCAPI} only offers {@code Windows-ROOT} (Trusted Root Certification Authorities) and
 * {@code Windows-MY} (Personal). It has <strong>no</strong> access to the Windows
 * <em>Intermediate Certification Authorities</em> store. Corporate TLS-intercepting proxies present
 * a leaf certificate signed by an intermediate CA whose root is trusted in {@code Windows-ROOT}, but
 * the intermediate itself lives in the intermediate store and is frequently <em>not</em> sent in the
 * TLS handshake. Windows/SChannel (browsers, {@code curl}) build the chain by pulling the
 * intermediate from that store, but Java's PKIX validator only has the server-sent certificates plus
 * the {@code Windows-ROOT} anchors, so path building fails with
 * {@code unable to find valid certification path to requested target}.</p>
 *
 * <p>To close that gap this helper enumerates the Root <em>and</em> Intermediate CA stores (both the
 * {@code LocalMachine} and {@code CurrentUser} scopes, which include GPO/enterprise-pushed CAs) via
 * PowerShell and returns their certificates. Adding those certificates as trust anchors lets PKIX
 * build the chain even when the proxy omits the intermediate. Only certificates the operating system
 * already trusts are returned, so certificate validation is not weakened.</p>
 *
 * <p>To avoid a stdout pipe-buffer deadlock, the PowerShell script writes the Base64-encoded
 * certificates to a temporary <em>file</em>. Java waits for the process to exit and only then reads
 * that file. Any failure (for example when corporate policy blocks PowerShell) is reported through
 * {@link Result#getError()} instead of being silently swallowed, so the HuggingFace connection test
 * can surface it.</p>
 *
 * <p>On non-Windows platforms an empty result with no error is returned.</p>
 */
final class WindowsCertificateStores {

    private static final long TIMEOUT_SECONDS = 25L;

    private WindowsCertificateStores() {
    }

    /**
     * The outcome of reading the Windows Root/Intermediate CA stores: the certificates found plus an
     * optional human-readable error describing why the export could not be performed.
     */
    static final class Result {

        private final List<X509Certificate> certificates;
        private final String error;

        Result(List<X509Certificate> certificates, String error) {
            this.certificates = certificates;
            this.error = error;
        }

        List<X509Certificate> getCertificates() {
            return certificates;
        }

        /**
         * @return {@code null} when the stores were read successfully (or the platform is not
         *         Windows), otherwise a message describing the failure.
         */
        String getError() {
            return error;
        }
    }

    /**
     * @return the certificates found in the Windows Root and Intermediate CA stores plus a diagnostic
     *         error when they could not be read (for example when PowerShell is blocked). On
     *         non-Windows platforms an empty result with no error is returned.
     */
    static Result loadRootAndIntermediateCertificates() {
        if (!isWindows()) {
            return new Result(new ArrayList<X509Certificate>(), null);
        }
        try {
            return exportViaFile();
        } catch (Exception ex) {
            return new Result(new ArrayList<X509Certificate>(),
                    "Windows certificate export failed: " + messageOf(ex));
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase().contains("win");
    }

    private static Result exportViaFile() throws IOException, CertificateException {
        File directory = new File(System.getProperty("java.io.tmpdir"), "askai-proxy");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create temporary directory: " + directory.getAbsolutePath());
        }
        File scriptFile = new File(directory, "askai-windows-ca-export.ps1");
        File outputFile = new File(directory, "askai-windows-ca-export.b64");
        deleteQuietly(outputFile);
        writeText(scriptFile, buildScript(outputFile));

        String consoleOutput = runPowerShell(scriptFile);

        if (!outputFile.isFile()) {
            String detail = consoleOutput.trim();
            String message = "PowerShell did not produce the certificate export file"
                    + (detail.length() > 0 ? " (" + detail + ")" : "") + ".";
            return new Result(new ArrayList<X509Certificate>(), message);
        }

        List<X509Certificate> certificates = parseCertificates(readText(outputFile));
        deleteQuietly(outputFile);
        String error = certificates.isEmpty() && consoleOutput.trim().length() > 0
                ? "PowerShell reported: " + consoleOutput.trim()
                : null;
        return new Result(certificates, error);
    }

    /**
     * PowerShell script that writes each certificate as a single line of Base64-encoded DER to the
     * given output file, gathered from the Root and Intermediate CA stores in both machine and user
     * scopes. Writing to a file (rather than stdout) avoids a pipe-buffer deadlock.
     */
    private static String buildScript(File outputFile) {
        String outputPath = outputFile.getAbsolutePath().replace("'", "''");
        StringBuilder builder = new StringBuilder();
        builder.append("$ErrorActionPreference = 'SilentlyContinue'\r\n");
        builder.append("$out = '").append(outputPath).append("'\r\n");
        builder.append("$stores = @('Cert:\\LocalMachine\\CA','Cert:\\CurrentUser\\CA',");
        builder.append("'Cert:\\LocalMachine\\Root','Cert:\\CurrentUser\\Root')\r\n");
        builder.append("$lines = New-Object System.Collections.Generic.List[string]\r\n");
        builder.append("foreach ($store in $stores) {\r\n");
        builder.append("    Get-ChildItem -Path $store | ForEach-Object {\r\n");
        builder.append("        $lines.Add([Convert]::ToBase64String($_.RawData))\r\n");
        builder.append("    }\r\n");
        builder.append("}\r\n");
        builder.append("[System.IO.File]::WriteAllLines($out, $lines)\r\n");
        return builder.toString();
    }

    /**
     * Runs the export script and returns whatever it printed to stdout/stderr (used only for
     * diagnostics). stdout is drained on a separate thread so a full pipe buffer cannot block the
     * process; the certificate data itself goes to the output file, not the pipe.
     */
    private static String runPowerShell(File scriptFile) throws IOException {
        final Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.getAbsolutePath())
                .redirectErrorStream(true)
                .start();

        final StringBuilder console = new StringBuilder();
        Thread drain = new Thread(new Runnable() {
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (console) {
                            if (console.length() < 4000) {
                                console.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Nothing useful to report; the output file presence is the real signal.
                } finally {
                    closeQuietly(reader);
                }
            }
        }, "askai-windows-ca-drain");
        drain.setDaemon(true);
        drain.start();

        boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Reading Windows certificate stores was interrupted.", ex);
        }
        if (!completed) {
            process.destroy();
            throw new IOException("Reading Windows certificate stores timed out after " + TIMEOUT_SECONDS + "s.");
        }
        try {
            drain.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        synchronized (console) {
            return console.toString();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write(text);
        } finally {
            closeQuietly(writer);
        }
    }

    private static String readText(File file) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            closeQuietly(reader);
        }
    }

    /**
     * Parses the export output, one Base64-encoded DER certificate per line, de-duplicating repeated
     * certificates (the machine and user scopes overlap heavily).
     */
    private static List<X509Certificate> parseCertificates(String output) throws CertificateException {
        List<X509Certificate> certificates = new ArrayList<X509Certificate>();
        if (output == null || output.trim().length() == 0) {
            return certificates;
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0 || seen.containsKey(line)) {
                continue;
            }
            seen.put(line, Boolean.TRUE);
            byte[] der = decodeBase64(line);
            if (der == null) {
                continue;
            }
            try {
                Object certificate = factory.generateCertificate(new ByteArrayInputStream(der));
                if (certificate instanceof X509Certificate) {
                    certificates.add((X509Certificate) certificate);
                }
            } catch (CertificateException ex) {
                // Skip anything that is not a parseable X.509 certificate.
            }
        }
        return certificates;
    }

    private static byte[] decodeBase64(String value) {
        try {
            return java.util.Base64.getMimeDecoder().decode(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message != null && message.trim().length() > 0 ? message : throwable.getClass().getName();
    }
}
