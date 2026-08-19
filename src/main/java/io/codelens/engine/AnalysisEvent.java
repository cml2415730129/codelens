package io.codelens.engine;

/**
 * A UI-facing progress event emitted while CodeLens analyzes a repository.
 *
 * @param kind    phase / text / tool / agentStart / agentEnd / error / done
 * @param agent   which agent produced it: "codelens" (orchestrator), a subagent id,
 *                or "system" for engine-level events
 * @param message payload text (delta fragment, tool name, status line, ...)
 */
public record AnalysisEvent(String kind, String agent, String message) {

    public static AnalysisEvent system(String message) {
        return new AnalysisEvent("phase", "system", message);
    }

    public static AnalysisEvent error(String message) {
        return new AnalysisEvent("error", "system", message);
    }
}
