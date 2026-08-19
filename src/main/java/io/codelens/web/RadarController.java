package io.codelens.web;

import io.codelens.config.AppConfig;
import io.codelens.radar.RadarEngine;
import io.codelens.radar.RepoState;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RepoRadar REST API backing the radar dashboard page. */
@RestController
public class RadarController {

    private final RadarEngine engine =
            new RadarEngine(AppConfig.load(), Paths.get("."));
    private final List<String> checkLog = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    @GetMapping("/api/radar/overview")
    public Map<String, Object> overview() throws Exception {
        Map<String, RepoState> states = engine.store().loadState();
        List<String> watchlist = engine.listWatchlist();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repos", new ArrayList<>(states.values()));
        out.put("watchlist", watchlist);
        out.put("running", running);
        return out;
    }

    @PostMapping("/api/radar/check")
    public Map<String, Object> check(
            @RequestParam(defaultValue = "Chinese") String lang) {
        if (running) {
            return Map.of("started", false, "reason", "already running");
        }
        running = true;
        checkLog.clear();
        Thread t = new Thread(() -> {
            try {
                engine.check(lang, checkLog::add);
            } catch (Exception e) {
                checkLog.add("✘ " + e.getMessage());
            } finally {
                running = false;
            }
        }, "reporadar-check");
        t.setDaemon(true);
        t.start();
        return Map.of("started", true);
    }

    @GetMapping("/api/radar/check/status")
    public Map<String, Object> status() {
        return Map.of("running", running, "log", new ArrayList<>(checkLog));
    }

    @PostMapping("/api/radar/add")
    public Map<String, Object> add(@RequestParam String url) throws Exception {
        return Map.of("slug", engine.addRepo(url));
    }

    @PostMapping("/api/radar/remove")
    public Map<String, Object> remove(@RequestParam String slug) throws Exception {
        return Map.of("removed", engine.removeRepo(slug));
    }
}
