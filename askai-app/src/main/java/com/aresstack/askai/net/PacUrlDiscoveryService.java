package com.aresstack.askai.net;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

public final class PacUrlDiscoveryService {

    public static String defaultScript() {
        StringBuilder builder = new StringBuilder();
        builder.append("Option Explicit\r\n");
        builder.append("Dim shell\r\n");
        builder.append("Set shell = CreateObject(\"WScript.Shell\")\r\n");
        builder.append("Probe shell, \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL\"\r\n");
        builder.append("Probe shell, \"HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL\"\r\n");
        builder.append("Probe shell, \"HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL\"\r\n");
        builder.append("Probe shell, \"HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL\"\r\n");
        builder.append("WScript.Quit 1\r\n");
        builder.append("Sub Probe(shell, registryPath)\r\n");
        builder.append("    Dim value\r\n");
        builder.append("    On Error Resume Next\r\n");
        builder.append("    Err.Clear\r\n");
        builder.append("    value = shell.RegRead(registryPath)\r\n");
        builder.append("    If Err.Number = 0 Then\r\n");
        builder.append("        WScript.Echo CStr(value)\r\n");
        builder.append("        WScript.Quit 0\r\n");
        builder.append("    End If\r\n");
        builder.append("    On Error GoTo 0\r\n");
        builder.append("End Sub\r\n");
        return builder.toString();
    }

    public String discoverWithScript(String script) throws IOException {
        String configuredScript = script == null || script.trim().length() == 0 ? defaultScript() : script;
        File directory = createWorkingDirectory();
        File scriptFile = new File(directory, "askai-pac-discovery.vbs");
        writeScript(scriptFile, configuredScript);
        return runScriptAndReadFirstLine(scriptFile);
    }

    public String discoverWithWScript() throws IOException {
        return discoverWithScript(defaultScript());
    }

    private File createWorkingDirectory() throws IOException {
        File directory = new File(System.getProperty("java.io.tmpdir"), "askai-proxy");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create temporary proxy directory: " + directory.getAbsolutePath());
        }
        return directory;
    }

    private void writeScript(File scriptFile, String script) throws IOException {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(scriptFile), "UTF-8");
            writer.write(script);
            if (!script.endsWith("\n")) {
                writer.write("\r\n");
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private String runScriptAndReadFirstLine(File scriptFile) throws IOException {
        Process process = new ProcessBuilder("cscript.exe", "//NoLogo", scriptFile.getAbsolutePath()).redirectErrorStream(true).start();
        boolean completed;
        try {
            completed = process.waitFor(20L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Script PAC discovery was interrupted.", ex);
        }
        if (!completed) {
            process.destroy();
            throw new IOException("Script PAC discovery timed out.");
        }
        String output = readText(process);
        if (process.exitValue() != 0) {
            throw new IOException("Script PAC discovery failed with exit code " + process.exitValue() + ": " + output);
        }
        String result = firstUsableLine(output);
        if (result == null) {
            throw new IOException("Script PAC discovery did not print an AutoConfigURL. Output was: " + output);
        }
        return result;
    }

    private String readText(Process process) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private String firstUsableLine(String output) {
        if (output == null) {
            return null;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() > 0 && line.toLowerCase().startsWith("http")) {
                return line;
            }
        }
        return null;
    }
}
