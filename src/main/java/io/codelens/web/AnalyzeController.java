package io.codelens.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codelens.config.AppConfig;
import io.codelens.engine.AnalysisEvent;
import io.codelens.engine.CodeLensEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
public class AnalyzeController {

    private final AppConfig config = AppConfig.load();
    private final CodeLensEngine engine = new CodeLensEngine(config);
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping(value = "/api/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> analyze(
            @RequestParam String repoUrl,
            @RequestParam(defaultValue = "English") String lang) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().directBestEffort();
        Thread worker = new Thread(() -> {
            try {
                engine.analyze(repoUrl, lang, ev -> sink.tryEmitNext(toSse(ev)));
                sink.tryEmitComplete();
            } catch (RuntimeException e) {
                sink.tryEmitNext(toSse(AnalysisEvent.error(e.getMessage())));
                sink.tryEmitComplete();
            }
        }, "codelens-analysis");
        worker.setDaemon(true);
        worker.start();
        return sink.asFlux();
    }

    @GetMapping("/api/report")
    public ResponseEntity<String> report(@RequestParam String dir,
            @RequestParam String file) throws IOException {
        // Path traversal guard: file must be a plain name, dir must be under outputDir
        if (file.contains("/") || file.contains("..") || !file.endsWith(".md")) {
            return ResponseEntity.badRequest().body("invalid file");
        }
        Path base = config.outputDir.toAbsolutePath().normalize();
        Path target = Path.of(dir).toAbsolutePath().normalize().resolve(file).normalize();
        if (!target.startsWith(base) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(Files.readString(target, StandardCharsets.UTF_8));
    }

    @GetMapping("/api/reports")
    public List<String> reports(@RequestParam String dir) {
        Path base = config.outputDir.toAbsolutePath().normalize();
        Path target = Path.of(dir).toAbsolutePath().normalize();
        if (!target.startsWith(base) || !Files.isDirectory(target)) {
            return List.of();
        }
        try (var stream = Files.list(target)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private ServerSentEvent<String> toSse(AnalysisEvent ev) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ev.kind());
        payload.put("agent", ev.agent());
        payload.put("message", ev.message());
        try {
            return ServerSentEvent.<String>builder()
                    .data(mapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().data("{}").build();
        }
    }
}
