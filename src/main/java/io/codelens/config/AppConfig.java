package io.codelens.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * CodeLens configuration, loaded from {@code config.yml} in the working directory.
 * Missing values fall back to built-in defaults. {@code ${ENV_VAR}} placeholders are
 * resolved from environment variables.
 */
public class AppConfig {

    public String modelProvider = "kimi";
    public String modelName = "kimi-k2";
    public String modelApiKey = null; // resolved from env or config
    public String modelBaseUrl = null; // optional custom endpoint
    public Integer maxCompletionTokens = 16000;

    public int historyDepth = 300; // git clone depth
    public int subagentSteps = 30;

    public Path outputDir = Paths.get("output");
    public Path runRoot =
            Paths.get(System.getProperty("user.home"), ".codelens", "runs");

    public static AppConfig load() {
        return load(Paths.get("config.yml"));
    }

    @SuppressWarnings("unchecked")
    public static AppConfig load(Path file) {
        AppConfig cfg = new AppConfig();
        if (!Files.exists(file)) {
            cfg.modelApiKey = firstNonBlank(
                    System.getenv("MODEL_API_KEY"),
                    System.getenv("MOONSHOT_API_KEY"),
                    System.getenv("KIMI_API_KEY"),
                    System.getenv("OPENAI_API_KEY"));
            return cfg;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) {
                return cfg;
            }
            Map<String, Object> model = (Map<String, Object>) root.get("model");
            if (model != null) {
                cfg.modelProvider = str(model.get("provider"), cfg.modelProvider);
                cfg.modelName = str(model.get("name"), cfg.modelName);
                cfg.modelApiKey = blankToNull(str(model.get("api-key"), null));
                cfg.modelBaseUrl = blankToNull(str(model.get("base-url"), null));
                cfg.maxCompletionTokens = integer(model.get("max-completion-tokens"),
                        cfg.maxCompletionTokens);
            }
            Map<String, Object> analysis = (Map<String, Object>) root.get("analysis");
            if (analysis != null) {
                cfg.historyDepth = integer(analysis.get("history-depth"), cfg.historyDepth);
                cfg.subagentSteps = integer(analysis.get("subagent-steps"), cfg.subagentSteps);
            }
            Map<String, Object> output = (Map<String, Object>) root.get("output");
            if (output != null) {
                String dir = str(output.get("dir"), null);
                if (dir != null) {
                    cfg.outputDir = Paths.get(dir);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config file: " + file, e);
        }
        if (cfg.modelApiKey == null) {
            cfg.modelApiKey = firstNonBlank(
                    System.getenv("MODEL_API_KEY"),
                    System.getenv("MOONSHOT_API_KEY"),
                    System.getenv("KIMI_API_KEY"),
                    System.getenv("OPENAI_API_KEY"));
        }
        return cfg;
    }

    private static String str(Object v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String s = resolvePlaceholders(String.valueOf(v).trim());
        return s.isEmpty() ? fallback : s;
    }

    private static Integer integer(Object v, Integer fallback) {
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Resolves ${VAR} and ${VAR:-default} placeholders against environment variables. */
    private static String resolvePlaceholders(String value) {
        String result = value;
        while (true) {
            int start = result.indexOf("${");
            if (start < 0) {
                return result;
            }
            int end = result.indexOf('}', start);
            if (end < 0) {
                return result;
            }
            String expr = result.substring(start + 2, end);
            String name = expr;
            String def = "";
            int sep = expr.indexOf(":-");
            if (sep >= 0) {
                name = expr.substring(0, sep);
                def = expr.substring(sep + 2);
            }
            String env = System.getenv(name);
            result = result.substring(0, start) + (env != null ? env : def)
                    + result.substring(end + 1);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public String modelId() {
        return modelProvider + ":" + modelName;
    }
}
