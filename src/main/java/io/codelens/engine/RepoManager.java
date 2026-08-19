package io.codelens.engine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Clones a Git repository with JGit (pure Java) and pre-computes repository
 * metadata (file tree, git history statistics) so the analysis agents can read
 * plain text files instead of running shell commands.
 */
public class RepoManager {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    public record CloneResult(Path repoDir, String defaultBranch) {}

    /** Parses "owner/repo" from a GitHub URL for naming output directories. */
    public static String repoSlug(String repoUrl) {
        String u = repoUrl.trim();
        u = u.replaceAll("\\.git$", "").replaceAll("/+$", "");
        int idx = u.lastIndexOf('/');
        String name = idx >= 0 ? u.substring(idx + 1) : u;
        String rest = idx >= 0 ? u.substring(0, idx) : "";
        int idx2 = rest.lastIndexOf('/');
        String owner = idx2 >= 0 ? rest.substring(idx2 + 1) : rest.replace("https:", "");
        owner = owner.replaceAll("[^A-Za-z0-9._-]", "");
        name = name.replaceAll("[^A-Za-z0-9._-]", "");
        return owner.isEmpty() ? name : owner + "-" + name;
    }

    public CloneResult cloneRepo(String repoUrl, Path targetDir, int depth,
            Consumer<AnalysisEvent> sink) {
        sink.accept(AnalysisEvent.system("Cloning " + repoUrl + " (depth " + depth + ") ..."));
        try {
            var cmd = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(targetDir.toFile())
                    .setCloneAllBranches(false)
                    .setProgressMonitor(new JGitProgress(sink));
            if (depth > 0) {
                cmd.setDepth(depth);
            }
            try (Git git = cmd.call()) {
                String branch = git.getRepository().getBranch();
                sink.accept(AnalysisEvent.system(
                        "Clone finished. Default branch: " + branch));
                return new CloneResult(targetDir, branch);
            }
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to clone " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    /** Generates repo-meta/tree.txt and repo-meta/git-stats.txt next to the repo. */
    public void generateMeta(CloneResult clone, Path metaDir, Consumer<AnalysisEvent> sink) {
        try {
            Files.createDirectories(metaDir);
            writeTree(clone.repoDir(), metaDir.resolve("tree.txt"));
            writeGitStats(clone.repoDir(), metaDir.resolve("git-stats.txt"));
            sink.accept(AnalysisEvent.system("Repository metadata generated."));
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate repo metadata", e);
        }
    }

    private void writeTree(Path repoDir, Path out) throws IOException {
        List<String> lines = new ArrayList<>();
        Map<String, Integer> extHistogram = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(repoDir)) {
            walk.filter(p -> {
                        String rel = repoDir.relativize(p).toString();
                        return !rel.startsWith(".git") && !rel.isEmpty();
                    })
                    .sorted(Comparator.comparing(repoDir::relativize))
                    .forEach(p -> {
                        String rel = repoDir.relativize(p).toString();
                        if (Files.isDirectory(p)) {
                            lines.add(rel + "/");
                        } else {
                            try {
                                lines.add(rel + " (" + Files.size(p) + " bytes)");
                            } catch (IOException ignored) {
                                lines.add(rel);
                            }
                            String name = p.getFileName().toString();
                            int dot = name.lastIndexOf('.');
                            if (dot > 0) {
                                String ext = name.substring(dot + 1).toLowerCase();
                                extHistogram.merge(ext, 1, Integer::sum);
                            }
                        }
                    });
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("# File tree of the repository (excluding .git)");
            w.println();
            lines.forEach(w::println);
            w.println();
            w.println("# File count by extension");
            extHistogram.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> w.println(e.getKey() + ": " + e.getValue()));
        }
    }

    private void writeGitStats(Path repoDir, Path out) throws IOException {
        try (Git git = Git.open(repoDir.toFile());
                PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            Repository repo = git.getRepository();
            w.println("# Git history statistics");
            w.println();

            // Tags
            List<Ref> tags = git.tagList().call();
            w.println("## Tags (" + tags.size() + ")");
            for (Ref tag : tags) {
                w.println("- " + Repository.shortenRefName(tag.getName()));
            }
            w.println();

            // Commits
            Map<String, Integer> authorCommits = new LinkedHashMap<>();
            Map<String, Integer> yearCommits = new TreeMap<>();
            List<String> logLines = new ArrayList<>();
            int total = 0;
            String firstDate = null;
            String lastDate = null;
            try {
                Iterable<RevCommit> commits = git.log().call();
                for (RevCommit c : commits) {
                    total++;
                    String date = ISO.format(Instant.ofEpochSecond(c.getCommitTime()));
                    if (lastDate == null) {
                        lastDate = date; // log is newest-first
                    }
                    firstDate = date;
                    String author = c.getAuthorIdent().getName();
                    authorCommits.merge(author, 1, Integer::sum);
                    yearCommits.merge(date.substring(0, 4), 1, Integer::sum);
                    if (logLines.size() < 400) {
                        String msg = c.getShortMessage().replaceAll("\\s+", " ");
                        logLines.add(date + " " + c.abbreviate(8).name() + " "
                                + author + ": " + msg);
                    }
                }
            } catch (GitAPIException e) {
                w.println("(failed to read git log: " + e.getMessage() + ")");
            }

            w.println("## Overview");
            w.println("Commits in this clone: " + total
                    + " (may be limited by shallow clone depth)");
            w.println("First commit: " + firstDate);
            w.println("Latest commit: " + lastDate);
            w.println();

            w.println("## Commits per year");
            yearCommits.forEach((year, count) -> w.println(year + ": " + count));
            w.println();

            w.println("## Top contributors");
            authorCommits.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(20)
                    .forEach(e -> w.println(e.getKey() + ": " + e.getValue() + " commits"));
            w.println();

            w.println("## Recent commit log (newest first, up to 400)");
            logLines.forEach(w::println);
        } catch (GitAPIException e) {
            throw new IOException("Failed to list tags", e);
        }
    }

    /** Bridges JGit progress callbacks into analysis events (throttled). */
    private static class JGitProgress implements org.eclipse.jgit.lib.ProgressMonitor {
        private final Consumer<AnalysisEvent> sink;
        private long lastReport;

        JGitProgress(Consumer<AnalysisEvent> sink) {
            this.sink = sink;
        }

        @Override
        public void start(int totalTasks) { }

        @Override
        public void beginTask(String title, int totalWork) { }

        @Override
        public void update(int completed) {
            long now = System.currentTimeMillis();
            if (now - lastReport > 3000) {
                lastReport = now;
                sink.accept(AnalysisEvent.system("Cloning... still downloading"));
            }
        }

        @Override
        public void endTask() { }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) { }
    }
}
