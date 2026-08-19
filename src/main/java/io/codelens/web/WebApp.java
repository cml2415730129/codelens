package io.codelens.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Web mode: serves the UI and streams agent events over SSE. */
@SpringBootApplication
public class WebApp {

    public static void run(String[] args) {
        SpringApplication app = new SpringApplication(WebApp.class);
        app.setDefaultProperties(java.util.Map.of(
                "server.port", String.valueOf(port(args)),
                "spring.main.banner-mode", "off",
                "logging.level.root", "warn"));
        app.run(args);
        int p = port(args);
        System.out.println();
        System.out.println("🔍 CodeLens web UI: http://localhost:" + p);
        System.out.println("   Press Ctrl+C to stop.");
    }

    private static int port(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        String env = System.getenv("CODELENS_PORT");
        if (env != null) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 8321;
    }
}
