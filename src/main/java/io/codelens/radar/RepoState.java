package io.codelens.radar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** Persisted per-repository radar state. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoState {

    public enum Severity { QUIET, ACTIVE, RELEASE, BREAKING }

    public record HistoryEntry(String date, String severity, String digest) {}

    public record StarPoint(String date, long stars) {}

    public String slug;
    public String description = "";
    public String language = "";
    public long stars;
    public long forks;
    public long openIssues;
    public String lastCommitSha = "";
    public String lastCommitDate = "";
    public String lastReleaseTag = "";
    public String lastReleaseAt = "";
    public String lastCheckAt = "";
    public String severity = "QUIET";
    public String digest = "";
    public int newCommitCount;
    public List<HistoryEntry> history = new ArrayList<>();
    public List<StarPoint> starHistory = new ArrayList<>();
}
