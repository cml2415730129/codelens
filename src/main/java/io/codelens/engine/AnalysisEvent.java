package io.codelens.engine;

/**
 * CodeLens 分析仓库过程中向 UI 发出的进度事件。
 *
 * @param kind    phase / text / tool / agentStart / agentEnd / error / done
 * @param agent   事件的产生者："codelens"（orchestrator）、某个 subagent id，
 *                或 "system"（引擎级事件）
 * @param message 负载文本（增量片段、工具名、状态行等）
 */
public record AnalysisEvent(String kind, String agent, String message) {

    public static AnalysisEvent system(String message) {
        return new AnalysisEvent("phase", "system", message);
    }

    /** 分析流水线中的里程碑步骤：message = "id|label"。 */
    public static AnalysisEvent step(String id, String label) {
        return new AnalysisEvent("step", "system", id + "|" + label);
    }

    public static AnalysisEvent error(String message) {
        return new AnalysisEvent("error", "system", message);
    }
}
