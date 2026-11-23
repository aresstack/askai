package com.aresstack.askai.modelport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * AskAI's preferred models on an OpenAI-compatible API, from {@code api-models.properties} next to this class: Space
 * Harvester's {@code PreferredModels} (from the Enterprise AI Client), adapted to AskAI's roles ({@code chat},
 * {@code embedding}, {@code rerank}, {@code vision}). Model names are configuration, not code; they apply only while
 * nothing is chosen. {@link #choose} picks among the models an API offers with {@link ModelPreselection}.
 */
public final class ApiModelPreferences {

    static final String RESOURCE = "api-models.properties";

    private static ApiModelPreferences bundled;

    private final Properties properties;

    ApiModelPreferences(Properties properties) {
        this.properties = properties;
    }

    public static synchronized ApiModelPreferences bundled() {
        if (bundled == null) {
            Properties properties = new Properties();
            InputStream in = ApiModelPreferences.class.getResourceAsStream(RESOURCE);
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                } catch (IOException e) {
                    properties = new Properties();
                }
            }
            bundled = new ApiModelPreferences(properties);
        }
        return bundled;
    }

    /** The preferred model of a role ({@code chat}, {@code embedding}, ...) or "". */
    public String model(String role) {
        return role == null ? "" : properties.getProperty(role.trim(), "").trim();
    }

    /** The words of the fallback ({@code <role>.match}); empty means the part after the last "/". */
    public List<String> match(String role) {
        List<String> words = new ArrayList<String>();
        for (String word : properties.getProperty(role + ".match", "").split(",")) {
            if (!word.trim().isEmpty()) {
                words.add(word.trim());
            }
        }
        return Collections.unmodifiableList(words);
    }

    /**
     * The model to preselect for {@code role} among {@code offered}: {@code current} while offered, else the preferred
     * one, else the single offered model containing its last path part, else the first offered model.
     */
    public String choose(String role, List<String> offered, String current) {
        return ModelPreselection.choose(offered, current, model(role), match(role));
    }
}
