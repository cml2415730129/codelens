package io.codelens.radar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minimal GitHub REST v3 client. Token optional (60 req/h without, 5000/h with). */
public class GitHubClient {

    public record RepoInfo(String slug, String description, String language,
                           long stars, String defaultBranch, String pushedAt) {}

    public record CommitInfo(String sha, String date, String author, String message) {}

    public record ReleaseInfo(String tag, String name, String publishedAt, String body) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String token;

    public GitHubClient(String token) {
        this.token = (token == null || token.isBlank()) ? null : token.trim();
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "codelens-reporadar")
                .GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new IOException("GitHub API " + path + " -> HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    public RepoInfo getRepo(String slug) throws IOException, InterruptedException {
        JsonNode n = get("/repos/" + slug);
        if (n == null) {
            throw new IOException("Repository not found: " + slug);
        }
        return new RepoInfo(
                slug,
                text(n, "description"),
                text(n, "language"),
                n.path("stargazers_count").asLong(),
                text(n, "default_branch"),
                text(n, "pushed_at"));
    }

    /** Commits on the default branch since an ISO-8601 timestamp (may be null). */
    public List<CommitInfo> getCommits(String slug, String sinceIso, int limit)
            throws IOException, InterruptedException {
        String path = "/repos/" + slug + "/commits?per_page=" + Math.min(limit, 100)
                + (sinceIso != null ? "&since=" + sinceIso : "");
        JsonNode arr = get(path);
        List<CommitInfo> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode c : arr) {
            JsonNode commit = c.path("commit");
            out.add(new CommitInfo(
                    c.path("sha").asText(),
                    commit.path("committer").path("date").asText(),
                    commit.path("author").path("name").asText(),
                    commit.path("message").asText().lines().findFirst().orElse("")));
        }
        return out;
    }

    public ReleaseInfo getLatestRelease(String slug) throws IOException, InterruptedException {
        JsonNode n = get("/repos/" + slug + "/releases/latest");
        if (n == null) {
            return null;
        }
        return new ReleaseInfo(
                text(n, "tag_name"),
                text(n, "name"),
                text(n, "published_at"),
                n.path("body").isNull() ? "" : n.path("body").asText());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? "" : v.asText();
    }
}
