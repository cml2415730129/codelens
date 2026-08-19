package io.codelens.radar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Watchlist (watchlist.yml) + persisted radar state (~/.codelens/radar/state.json). */
public class RadarStore {

    private final Path watchlistFile;
    private final Path stateFile;
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public RadarStore(Path workDir) {
        this.watchlistFile = workDir.resolve("watchlist.yml");
        Path dir = Paths.get(System.getProperty("user.home"), ".codelens", "radar");
        this.stateFile = dir.resolve("state.json");
    }

    public Path watchlistFile() {
        return watchlistFile;
    }

    // ---------- watchlist ----------

    @SuppressWarnings("unchecked")
    public List<String> loadWatchlist() throws IOException {
        if (!Files.exists(watchlistFile)) {
            List<String> defaults = List.of(
                    "https://github.com/agentscope-ai/agentscope-java");
            saveWatchlist(defaults);
            return new ArrayList<>(defaults);
        }
        try (InputStream in = Files.newInputStream(watchlistFile)) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null || root.get("repos") == null) {
                return new ArrayList<>();
            }
            List<String> out = new ArrayList<>();
            for (Object item : (List<Object>) root.get("repos")) {
                if (item instanceof Map<?, ?> m && m.get("url") != null) {
                    out.add(String.valueOf(m.get("url")).trim());
                } else if (item != null) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return out;
        }
    }

    public void saveWatchlist(List<String> urls) throws IOException {
        StringBuilder sb = new StringBuilder("# RepoRadar watchlist — one GitHub URL per line\nrepos:\n");
        for (String u : urls) {
            sb.append("  - ").append(u).append("\n");
        }
        Files.writeString(watchlistFile, sb.toString());
    }

    // ---------- state ----------

    @SuppressWarnings("unchecked")
    public Map<String, RepoState> loadState() {
        if (!Files.exists(stateFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = json.readValue(stateFile.toFile(), Map.class);
            Map<String, RepoState> out = new LinkedHashMap<>();
            Object repos = raw.get("repos");
            if (repos instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    RepoState s = json.convertValue(e.getValue(), RepoState.class);
                    out.put(String.valueOf(e.getKey()), s);
                }
            }
            return out;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    public void saveState(Map<String, RepoState> states) {
        try {
            Files.createDirectories(stateFile.getParent());
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("repos", states);
            json.writeValue(stateFile.toFile(), wrapper);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save radar state", e);
        }
    }

    /** Extracts "owner/repo" from a GitHub URL. */
    public static String slugOf(String url) {
        String u = url.trim().replaceAll("\\.git$", "").replaceAll("/+$", "");
        int idx = u.indexOf("github.com/");
        if (idx >= 0) {
            u = u.substring(idx + "github.com/".length());
        }
        String[] parts = u.split("/");
        return parts.length >= 2 ? parts[0] + "/" + parts[1] : u;
    }
}
