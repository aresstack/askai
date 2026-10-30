package com.aresstack.askai.browser.playwright;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The AskAI Research Firefox add-on (sources in {@code src/main/resources/askai-addon/firefox}): written as an
 * unpacked add-on directory next to a browser's profile at every start, so Camoufox loads it through
 * {@code CAMOU_CONFIG.addons} like its bundled add-ons. The directory carries the {@code bridge.json} of THIS start
 * (port and token of its {@link AddonBridge}); a stale one from an earlier start is always overwritten.
 */
final class ResearchAddon {

    /** The add-on id from its manifest (browser_specific_settings.gecko.id). */
    static final String ID = "research-hud@askai.aresstack.com";
    static final String DIRECTORY = "askai-research-addon";
    private static final String RESOURCES = "/askai-addon/firefox/";
    /** Every file of the add-on; the jar cannot be listed, so the set is spelled out here. */
    static final List<String> FILES = List.of(
            "manifest.json", "background.js", "panel.html", "panel.css", "panel.js", "icon.svg");

    private ResearchAddon() {
    }

    /**
     * Write the add-on to {@code <parent>/askai-research-addon} with the bridge configuration of this start.
     * @param bridgeConfig the {@link AddonBridge#clientConfig()} JSON, or null for an add-on that stays idle
     * @return the add-on directory, for {@code CamoufoxLaunch.withAddon}
     */
    static Path install(Path parent, String bridgeConfig) throws IOException {
        Path target = parent.resolve(DIRECTORY);
        Files.createDirectories(target);
        for (String file : FILES) {
            try (InputStream in = ResearchAddon.class.getResourceAsStream(RESOURCES + file)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCES + file + " missing from the browser-playwright jar");
                }
                Files.copy(in, target.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path config = target.resolve("bridge.json");
        if (bridgeConfig == null) {
            Files.deleteIfExists(config);
        } else {
            Files.writeString(config, bridgeConfig, StandardCharsets.UTF_8);
        }
        return target;
    }
}
