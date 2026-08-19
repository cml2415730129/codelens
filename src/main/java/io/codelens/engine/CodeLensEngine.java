package io.codelens.engine;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.codelens.config.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * CodeLens core engine: clones a repository, spins up a HarnessAgent orchestrator
 * with three subagents (structure / history / guide), streams progress events to
 * the caller, and assembles the final Markdown report.
 */
public class CodeLensEngine {

    /** Subagent spec files shipped on the classpath (filename == agent id). */
    private static final List<String> SUBAGENT_SPECS = List.of(
            "structure-analyst.md", "history-digger.md", "guide-writer.md");

    /** Single-user local tool: all runs share this user id inside the workspace. */
    private static final String USER_ID = "local";

    /** Attempts for the agent run; transient model overloads are retried. */
    private static final int MAX_ATTEMPTS = 3;

    private final AppConfig config;
    private final RepoManager repoManager = new RepoManager();

    public CodeLensEngine(AppConfig config) {
        this.config = config;
    }

    /**
     * Runs a full analysis. Blocking; progress is reported through {@code sink}.
     *
     * @return the output directory containing REPORT.md and section files
     */
    public Path analyze(String repoUrl, String reportLanguage,
            Consumer<AnalysisEvent> sink) {
        if (config.modelApiKey == null && !"ollama".equals(config.modelProvider)) {
            throw new IllegalStateException(
                    "No model API key configured. Set MODEL_API_KEY or model.api-key in config.yml");
        }
        String slug = RepoManager.repoSlug(repoUrl);
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now());
        Path workspace = config.runRoot.resolve(runId + "-" + slug).resolve("workspace");
        // HarnessAgent scopes per-user files under workspace/<userId>/ — clone the
        // repo there so agent tools can read it with plain relative paths.
        Path userRoot = workspace.resolve(USER_ID);

        try {
            prepareWorkspace(workspace, userRoot);
            RepoManager.CloneResult clone =
                    repoManager.cloneRepo(repoUrl, userRoot.resolve("repo"),
                            config.historyDepth, sink);
            repoManager.generateMeta(clone, userRoot.resolve("repo-meta"), sink);

            sink.accept(AnalysisEvent.system(
                    "Starting agent team with model " + config.modelId() + " ..."));
            HarnessAgent agent = buildAgent(workspace, repoUrl, reportLanguage);

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("s-" + runId)
                    .userId(USER_ID)
                    .build();

            // Retry on transient model errors (rate limits, gateway overload).
            // AgentState is persisted per session, so a retry resumes the mission.
            RuntimeException lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    agent.streamEvents(
                                    new UserMessage(missionPrompt(repoUrl, reportLanguage)), ctx)
                            .doOnNext(ev -> {
                                AnalysisEvent mapped = mapEvent(ev);
                                if (mapped != null) {
                                    sink.accept(mapped);
                                }
                            })
                            .blockLast();
                    lastError = null;
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                    sink.accept(AnalysisEvent.error("Attempt " + attempt + "/" + MAX_ATTEMPTS
                            + " failed: " + e.getMessage()));
                    if (attempt < MAX_ATTEMPTS) {
                        sink.accept(AnalysisEvent.system("Retrying in 60s ..."));
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (lastError != null) {
                sink.accept(AnalysisEvent.system(
                        "Agent run did not finish cleanly; assembling report from "
                                + "whatever sections were completed."));
            }

            // Assemble the report even on partial success — completed sections are valuable.
            Path out = assembleReport(repoUrl, slug, userRoot, sink);
            sink.accept(new AnalysisEvent("done", "system", out.toAbsolutePath().toString()));
            return out;
        } catch (IOException e) {
            sink.accept(AnalysisEvent.error(e.getMessage()));
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            sink.accept(AnalysisEvent.error(e.getMessage()));
            throw e;
        }
    }

    private void prepareWorkspace(Path workspace, Path userRoot) throws IOException {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(userRoot.resolve("reports"));
        for (String spec : SUBAGENT_SPECS) {
            try (InputStream in = CodeLensEngine.class
                    .getResourceAsStream("/codelens/subagents/" + spec)) {
                if (in == null) {
                    throw new IOException("Missing classpath resource: /codelens/subagents/" + spec);
                }
                Files.copy(in, workspace.resolve("subagents").resolve(spec),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private HarnessAgent buildAgent(Path workspace, String repoUrl, String reportLanguage) {
        ModelCreationContext.Builder mcb = ModelCreationContext.builder().stream(true);
        if (config.modelApiKey != null) {
            mcb.apiKey(config.modelApiKey);
        }
        if (config.modelBaseUrl != null) {
            mcb.baseUrl(config.modelBaseUrl);
        }
        if (config.maxCompletionTokens != null) {
            mcb.component(GenerateOptions.class, GenerateOptions.builder()
                    .maxCompletionTokens(config.maxCompletionTokens)
                    .build());
        }
        Model model = ModelRegistry.resolve(config.modelId(), mcb.build());

        return HarnessAgent.builder()
                .name("codelens")
                .sysPrompt(orchestratorPrompt(repoUrl, reportLanguage))
                .model(model)
                .workspace(workspace)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(60)
                        .keepMessages(20)
                        .build())
                // Analysis runs unattended; deny rules of built-in dangerous-path
                // checks still apply even in BYPASS mode.
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .build();
    }

    private String orchestratorPrompt(String repoUrl, String reportLanguage) {
        return """
                You are CodeLens, the orchestrator of a repository-analysis agent team.

                Ground rules:
                - The target repository has ALREADY been cloned to ./repo inside your workspace. Never clone it again.
                - Pre-computed metadata is available at ./repo-meta/tree.txt (full file tree with sizes) and ./repo-meta/git-stats.txt (commit history, contributors, tags).
                - All reports must be written in %s.
                - Delegate the heavy work to your subagents; do not read large files yourself.

                Your workflow (follow exactly):
                1. In ONE single reasoning turn, spawn all three subagents in parallel via agent_spawn
                   (leave timeout_seconds at the default; results are pushed back to you automatically):
                   - agent_id="structure-analyst", task="Analyze the repository cloned at ./repo. Read ./repo-meta/tree.txt first, then explore key source files. Write your full report to ./reports/01-architecture.md."
                   - agent_id="history-digger", task="Analyze the git history of the repository cloned at ./repo using ./repo-meta/git-stats.txt (you may also run read-only git commands). Write your full report to ./reports/02-history.md."
                   - agent_id="guide-writer", task="Write a newcomer onboarding guide for the repository cloned at ./repo. Read its README and build files. Write your full guide to ./reports/03-getting-started.md."
                2. Wait until all three subagent results have been pushed back.
                3. Read the three files under ./reports/, then write ./reports/00-overview.md:
                   an executive summary (max 60 lines) covering what the project is, its architecture
                   in one paragraph, the most interesting history insights, and how to get started,
                   ending with a '## Key Findings' bullet list of 5-8 items.
                4. Finish by replying with a plain 3-sentence summary (no markdown headers).
                """.formatted(reportLanguage);
    }

    private String missionPrompt(String repoUrl, String reportLanguage) {
        return "Analyze this repository: " + repoUrl
                + " (reports in " + reportLanguage + "). Follow your workflow exactly.";
    }

    /** Maps an AgentScope stream event to a UI-facing AnalysisEvent. */
    private AnalysisEvent mapEvent(io.agentscope.core.event.AgentEvent ev) {
        String source = ev.getSource();
        String agent = source == null ? "codelens"
                : source.substring(source.lastIndexOf('/') + 1);
        AgentEventType type = ev.getType();
        if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            return new AnalysisEvent("text", agent,
                    ((TextBlockDeltaEvent) ev).getDelta());
        }
        if (type == AgentEventType.TOOL_CALL_START) {
            return new AnalysisEvent("tool", agent,
                    ((ToolCallStartEvent) ev).getToolCallName());
        }
        if (type == AgentEventType.AGENT_START) {
            return new AnalysisEvent("agentStart", agent, "");
        }
        if (type == AgentEventType.AGENT_END) {
            return new AnalysisEvent("agentEnd", agent, "");
        }
        return null;
    }

    /** Copies section reports to the output dir and assembles REPORT.md. */
    private Path assembleReport(String repoUrl, String slug, Path workspace,
            Consumer<AnalysisEvent> sink) throws IOException {
        Path reportsDir = workspace.resolve("reports");
        Path out = config.outputDir.resolve(slug);
        Files.createDirectories(out);

        List<Path> sections = new ArrayList<>();
        if (Files.exists(reportsDir)) {
            try (var stream = Files.list(reportsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> {
                            try {
                                Files.copy(p, out.resolve(p.getFileName().toString()),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                sections.add(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("# CodeLens Report — ").append(slug).append("\n\n");
        report.append("- **Repository**: ").append(repoUrl).append("\n");
        report.append("- **Generated**: ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
        report.append("- **Model**: ").append(config.modelId()).append("\n");
        report.append("- **Engine**: [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)\n\n---\n\n");
        for (Path section : sections) {
            report.append(Files.readString(section, StandardCharsets.UTF_8)).append("\n\n---\n\n");
        }
        Files.writeString(out.resolve("REPORT.md"), report.toString(), StandardCharsets.UTF_8);

        sink.accept(AnalysisEvent.system(
                "Report assembled: " + out.toAbsolutePath() + " (" + sections.size()
                        + " sections)"));
        return out;
    }
}
