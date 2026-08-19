package io.codelens.cli;

import io.codelens.config.AppConfig;
import io.codelens.engine.AnalysisEvent;
import io.codelens.engine.CodeLensEngine;
import java.nio.file.Path;

/** CLI mode: analyze one repository, stream progress to the terminal. */
public class CliApp {

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";
    private static final String DIM = ESC + "[2m";
    private static final String CYAN = ESC + "[36m";
    private static final String GREEN = ESC + "[32m";
    private static final String YELLOW = ESC + "[33m";
    private static final String MAGENTA = ESC + "[35m";
    private static final String RED = ESC + "[31m";

    public static int run(String[] args) {
        String repoUrl = null;
        String lang = "English";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--lang" -> {
                    if (i + 1 < args.length) {
                        lang = args[++i];
                    }
                }
                case "--help", "-h" -> {
                    printUsage();
                    return 0;
                }
                default -> {
                    if (!args[i].startsWith("--")) {
                        repoUrl = args[i];
                    }
                }
            }
        }
        if (repoUrl == null) {
            printUsage();
            return 1;
        }

        AppConfig config = AppConfig.load();
        CodeLensEngine engine = new CodeLensEngine(config);

        banner(repoUrl, config);
        long start = System.currentTimeMillis();
        try {
            Path out = engine.analyze(repoUrl, lang, CliApp::render);
            long secs = (System.currentTimeMillis() - start) / 1000;
            System.out.println();
            System.out.println(GREEN + "✔ Done in " + secs + "s" + RESET);
            System.out.println("  Report: " + out.toAbsolutePath().resolve("REPORT.md"));
            return 0;
        } catch (RuntimeException e) {
            System.out.println();
            System.out.println(RED + "✘ Analysis failed: " + e.getMessage() + RESET);
            return 2;
        }
    }

    private static void render(AnalysisEvent ev) {
        switch (ev.kind()) {
            case "phase" ->
                System.out.println(CYAN + "▸ " + ev.message() + RESET);
            case "agentStart" -> {
                if (!"codelens".equals(ev.agent())) {
                    System.out.println();
                    System.out.println(MAGENTA + "┌─ [" + ev.agent() + "] started" + RESET);
                }
            }
            case "agentEnd" -> {
                if (!"codelens".equals(ev.agent())) {
                    System.out.println();
                    System.out.println(MAGENTA + "└─ [" + ev.agent() + "] finished" + RESET);
                }
            }
            case "tool" ->
                System.out.println(DIM + "  [" + ev.agent() + "] tool → "
                        + ev.message() + RESET);
            case "text" -> {
                if ("codelens".equals(ev.agent())) {
                    System.out.print(YELLOW + ev.message() + RESET);
                }
            }
            case "error" ->
                System.out.println(RED + "✘ " + ev.message() + RESET);
            case "done" -> {
                // handled after analyze() returns
            }
            default -> { }
        }
    }

    private static void banner(String repoUrl, AppConfig config) {
        System.out.println();
        System.out.println(CYAN + "🔍 CodeLens" + RESET + DIM + " — x-ray a repository with AI agents" + RESET);
        System.out.println(DIM + "   repo:  " + repoUrl + RESET);
        System.out.println(DIM + "   model: " + config.modelId() + RESET);
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("""
                CodeLens — x-ray any GitHub repository with a team of AI agents.

                Usage:
                  codelens <github-repo-url> [--lang Chinese]
                  codelens --serve [--port 8321]

                Configuration: config.yml in the working directory (see config.example.yml),
                or the MODEL_API_KEY environment variable.
                """);
    }
}
