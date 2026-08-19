package io.codelens.radar;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.codelens.config.AppConfig;
import io.codelens.radar.GitHubClient.CommitInfo;
import io.codelens.radar.GitHubClient.ReleaseInfo;
import io.codelens.radar.GitHubClient.RepoInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * RepoRadar engine: sweeps the watchlist, detects what changed on GitHub
 * (commits / releases / breaking changes) since the last sweep, and asks the
 * LLM to write a concise incremental digest per repo.
 */
public class RadarEngine {

    private static final int MAX_HISTORY = 30;

    private final AppConfig config;
    private final RadarStore store;
    private final GitHubClient github;

    public RadarEngine(AppConfig config, Path workDir) {
        this.config = config;
        this.store = new RadarStore(workDir);
        this.github = new GitHubClient(System.getenv("GITHUB_TOKEN"));
    }

    public record RepoAlert(String slug, String severity, String headline) {}

    public record CheckReport(List<RepoState> repos, List<RepoAlert> alerts,
                              String checkedAt, int errorCount) {}

    public RadarStore store() {
        return store;
    }

    // ---------------- watchlist ops ----------------

    public List<String> listWatchlist() throws IOException {
        return store.loadWatchlist();
    }

    public String addRepo(String url) throws IOException {
        String slug = RadarStore.slugOf(url);
        List<String> list = store.loadWatchlist();
        String full = "https://github.com/" + slug;
        if (!list.contains(full)) {
            list.add(full);
            store.saveWatchlist(list);
        }
        return slug;
    }

    public String removeRepo(String urlOrSlug) throws IOException {
        String slug = RadarStore.slugOf(urlOrSlug);
        List<String> list = store.loadWatchlist();
        list.removeIf(u -> RadarStore.slugOf(u).equalsIgnoreCase(slug));
        store.saveWatchlist(list);
        Map<String, RepoState> states = store.loadState();
        states.remove(slug);
        store.saveState(states);
        return slug;
    }

    // ---------------- the sweep ----------------

    public CheckReport check(String lang, Consumer<String> log) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        List<String> watchlist;
        try {
            watchlist = store.loadWatchlist();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load watchlist.yml", e);
        }
        Map<String, RepoState> states = store.loadState();
        List<RepoAlert> alerts = new ArrayList<>();
        int errors = 0;

        for (String url : watchlist) {
            String slug = RadarStore.slugOf(url);
            try {
                RepoState prev = states.get(slug);
                RepoState cur = sweepRepo(slug, prev, lang, now, log);
                states.put(slug, cur);
                if (!"QUIET".equals(cur.severity)) {
                    alerts.add(new RepoAlert(slug, cur.severity, cur.digest));
                }
                store.saveState(states); // persist after each repo
            } catch (Exception e) {
                errors++;
                log.accept("  ✘ " + slug + ": " + e.getMessage());
                RepoState cur = states.computeIfAbsent(slug, k -> new RepoState());
                cur.slug = slug;
                cur.lastCheckAt = now;
            }
        }
        store.saveState(states);
        return new CheckReport(new ArrayList<>(states.values()), alerts, now, errors);
    }

    private RepoState sweepRepo(String slug, RepoState prev, String lang,
            String now, Consumer<String> log) throws IOException, InterruptedException {
        log.accept("▸ " + slug + " ...");
        RepoInfo info = github.getRepo(slug);

        RepoState cur = prev != null ? prev : new RepoState();
        cur.slug = slug;
        cur.description = info.description() != null ? info.description() : "";
        cur.language = info.language() != null ? info.language() : "";
        cur.stars = info.stars();
        cur.forks = info.forks();
        cur.openIssues = info.openIssues();

        ReleaseInfo release = github.getLatestRelease(slug);
        String releaseTag = release != null ? release.tag() : "";

        boolean firstSeen = prev == null || prev.lastCommitSha.isEmpty();
        // Look back to the previous check (or 7 days on first sight)
        String since = firstSeen
                ? Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()
                : prev.lastCheckAt;
        List<CommitInfo> commits = github.getCommits(slug, since, 30);

        boolean newRelease = release != null && !releaseTag.equals(cur.lastReleaseTag);

        if (firstSeen) {
            cur.severity = "QUIET";
            cur.digest = "开始跟踪 · " + (cur.description.isEmpty() ? slug : cur.description);
            cur.newCommitCount = 0;
        } else {
            boolean breaking = newRelease && release.body() != null
                    && release.body().toLowerCase().matches("(?s).*\\b(breaking|incompatible|不兼容)\\b.*");
            int newCommits = 0;
            for (CommitInfo c : commits) {
                if (!c.sha().startsWith(cur.lastCommitSha)
                        && !cur.lastCommitSha.startsWith(c.sha())) {
                    newCommits++;
                } else {
                    break;
                }
            }
            cur.newCommitCount = newCommits;
            cur.severity = classify(newCommits, newRelease, breaking);

            if ("QUIET".equals(cur.severity)) {
                cur.digest = "无新变化";
            } else {
                cur.digest = summarize(slug, info, commits, newCommits,
                        newRelease ? release : null, cur.severity, lang, log);
            }
        }

        if (!commits.isEmpty()) {
            CommitInfo head = commits.get(0);
            cur.lastCommitSha = head.sha();
            cur.lastCommitDate = head.date();
        }
        if (release != null) {
            cur.lastReleaseTag = releaseTag;
            cur.lastReleaseAt = release.publishedAt();
        }
        cur.lastCheckAt = now;
        String today = now.length() >= 10 ? now.substring(0, 10) : now;
        if (cur.starHistory.isEmpty()
                || !cur.starHistory.get(cur.starHistory.size() - 1).date().equals(today)) {
            cur.starHistory.add(new RepoState.StarPoint(today, cur.stars));
            if (cur.starHistory.size() > 90) {
                cur.starHistory = new ArrayList<>(
                        cur.starHistory.subList(cur.starHistory.size() - 90, cur.starHistory.size()));
            }
        } else {
            cur.starHistory.set(cur.starHistory.size() - 1,
                    new RepoState.StarPoint(today, cur.stars));
        }
        cur.history.add(0, new RepoState.HistoryEntry(now, cur.severity, cur.digest));
        if (cur.history.size() > MAX_HISTORY) {
            cur.history = new ArrayList<>(cur.history.subList(0, MAX_HISTORY));
        }
        log.accept("  " + badge(cur.severity) + " " + slug
                + ("QUIET".equals(cur.severity) ? "" : " — " + oneLine(cur.digest)));
        return cur;
    }

    private String summarize(String slug, RepoInfo info, List<CommitInfo> commits,
            int newCommits, ReleaseInfo release, String severity,
            String lang, Consumer<String> log) {
        StringBuilder facts = new StringBuilder();
        facts.append("Repository: ").append(slug).append("\n");
        facts.append("Description: ").append(info.description()).append("\n");
        if (newCommits > 0) {
            facts.append("New commits (").append(newCommits).append("):\n");
            commits.stream().limit(Math.min(newCommits, 20))
                    .forEach(c -> facts.append("- ").append(c.date(), 0, 10)
                            .append(" ").append(c.message()).append("\n"));
        }
        if (release != null) {
            facts.append("New release: ").append(release.tag()).append(" ")
                    .append(release.name()).append("\nRelease notes:\n")
                    .append(release.body() == null ? ""
                            : release.body().substring(0, Math.min(3000, release.body().length())))
                    .append("\n");
        }

        String sys = "You are RepoRadar, an open-source intelligence analyst. "
                + "Write in " + lang + ".";
        String user = """
                Summarize what changed in this repository for a busy developer who watches it.
                Rules:
                - 2-4 sentences max, concrete (mention real commit themes / release version).
                - If severity is RELEASE or BREAKING, lead with the version number and the most important change.
                - End with one short "so-what": why a watcher should care (or "可以忽略" if trivial).
                - No headers, no bullet lists, plain prose.

                Severity: %s

                Facts:
                %s
                """.formatted(severity, facts);
        try {
            return chat(sys, user);
        } catch (Exception e) {
            log.accept("  (digest fallback: " + e.getMessage() + ")");
            StringBuilder fb = new StringBuilder();
            if (release != null) {
                fb.append("新版本 ").append(release.tag()).append("。");
            }
            if (newCommits > 0) {
                fb.append("新增 ").append(newCommits).append(" 个提交：");
                commits.stream().limit(3).forEach(c -> fb.append(c.message()).append("；"));
            }
            return fb.toString();
        }
    }

    /** Direct LLM call (no agent loop) for the digest. */
    private String chat(String sys, String user) {
        ModelCreationContext.Builder mcb = ModelCreationContext.builder().stream(true);
        if (config.modelApiKey != null) {
            mcb.apiKey(config.modelApiKey);
        }
        if (config.modelBaseUrl != null) {
            mcb.baseUrl(config.modelBaseUrl);
        }
        Model model = ModelRegistry.resolve(config.modelId(), mcb.build());

        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(sys).build(),
                new UserMessage(user));
        StringBuilder sb = new StringBuilder();
        model.stream(messages, List.of(), GenerateOptions.builder().build())
                .doOnNext(resp -> {
                    for (var block : resp.getContent()) {
                        if (block instanceof TextBlock t) {
                            sb.append(t.getText());
                        }
                    }
                })
                .blockLast();
        return sb.toString().trim();
    }

    /** Severity ladder: BREAKING > RELEASE > ACTIVE > QUIET. */
    static String classify(int newCommits, boolean newRelease, boolean breaking) {
        if (breaking) {
            return "BREAKING";
        }
        if (newRelease) {
            return "RELEASE";
        }
        if (newCommits > 0) {
            return "ACTIVE";
        }
        return "QUIET";
    }

    private static String badge(String severity) {
        return switch (severity) {
            case "BREAKING" -> "🚨";
            case "RELEASE" -> "🚀";
            case "ACTIVE" -> "🟢";
            default -> "⚪";
        };
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 100 ? t.substring(0, 100) + "…" : t;
    }
}
