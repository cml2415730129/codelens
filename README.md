# 🔍 CodeLens

**X-ray any GitHub repository — a team of AI agents maps its architecture, digs through its history, and writes your onboarding guide.**

[中文文档](README.zh-CN.md)

CodeLens points a team of AI agents at any public GitHub repository and produces three Markdown reports:

| Report | What you get |
|---|---|
| 🏛️ `01-architecture.md` | Module map, key abstractions, and **Mermaid diagrams** of components & data flow |
| 📜 `02-history.md` | The project's evolution story mined from git history: eras, milestones, contributor dynamics, fun facts |
| 📖 `03-getting-started.md` | A practical newcomer guide: prerequisites, quick start, configuration, where to read first |

Everything is assembled into a single `REPORT.md`.

Built on **[AgentScope Java 2.0](https://github.com/agentscope-ai/agentscope-java)** — one orchestrator agent fans out to three parallel subagents, with live event streaming to both a CLI and a web UI.

## How it works

```
        ┌──────────────────────────┐
        │   🎯 codelens (parent)   │  plans the mission, writes the executive summary
        └───────────┬──────────────┘
        agent_spawn × 3 (parallel)
   ┌────────────────┼─────────────────┐
   ▼                ▼                 ▼
🏛️ structure-   📜 history-       📖 guide-
   analyst         digger            writer
   reads the       mines git log     reads README &
   source tree     stats & tags      build files
   └────────────────┴─────────────────┘
                    ▼
        output/<owner>-<repo>/REPORT.md
```

## 📡 RepoRadar — continuous repo watching

CodeLens also ships **RepoRadar**: a watchlist-driven radar that checks your repos on a schedule and tells you what changed — new commits, releases, breaking changes — with LLM-written incremental digests. This is what a chatbot can't do: it works while you're not looking.

```bash
./codelens radar                        # run one sweep
./codelens radar add https://github.com/owner/repo
./codelens radar list
./codelens --serve                      # dashboard → http://localhost:8321/radar.html
```

- Watchlist lives in `watchlist.yml`; state & history in `~/.codelens/radar/state.json`
- Severity levels: ⚪ quiet · 🟢 new commits · 🚀 new release · 🚨 breaking change detected in release notes
- Digests are incremental: the LLM only runs on repos that actually changed
- Set `GITHUB_TOKEN` for higher API rate limits (optional)
- Wire `radar check` into cron / your agent runtime for hands-free monitoring

## Quick start (3 minutes)

**Prerequisite: JDK 17+** — that's all. No Maven, no git client needed (the build uses the Maven wrapper / your local `mvn`; cloning is pure-Java JGit).

```bash
# 1. Clone
git clone https://github.com/cml2415730129/codelens.git
cd codelens

# 2. Configure a model (pick ONE)
export MODEL_API_KEY=sk-...          # simplest: any OpenAI-compatible key
# or: cp config.example.yml config.yml  # then edit config.yml

# 3. Run
./codelens https://github.com/agentscope-ai/agentscope-java   # CLI mode
./codelens --serve                                             # Web mode → http://localhost:8321
```

The first run builds the jar automatically. Reports land in `output/<owner>-<repo>/`.

## Model configuration

CodeLens talks to models through AgentScope's `ModelRegistry` — **any OpenAI-compatible endpoint works**. Edit `config.yml`:

```yaml
model:
  provider: kimi        # kimi / openai / deepseek / glm / dashscope / anthropic / gemini / ollama
  name: kimi-k2
  api-key: ${MODEL_API_KEY}
  base-url: ""          # optional custom endpoint
```

Ready-made recipes:

| Provider | `provider` | `name` (example) | `base-url` |
|---|---|---|---|
| Kimi (Moonshot) | `kimi` | `kimi-k2` | _(default)_ |
| OpenAI | `openai` | `gpt-4.1-mini` | _(default)_ |
| DeepSeek | `deepseek` | `deepseek-chat` | `https://api.deepseek.com/v1` |
| Qwen | `dashscope` | `qwen-plus` | _(default)_ |
| Ollama (local, keyless) | `ollama` | `llama3` | _(default)_ |

CLI flags: `--lang Chinese` — write reports in another language (default English).

## Security notes

- `config.yml` is git-ignored; only `config.example.yml` is committed. Never commit API keys.
- Agents run with AgentScope's permission system in `BYPASS` mode **but** the built-in dangerous-path checks (`.env`, `.ssh`, credentials…) can never be bypassed. Analysis targets are read-only clones in a temp workspace under `~/.codelens/runs/`.

## Project layout

```
src/main/java/io/codelens/
├── CodeLens.java              entry point (CLI vs --serve)
├── config/AppConfig.java      YAML + env config, ${VAR} placeholders
├── engine/
│   ├── CodeLensEngine.java    orchestration, event streaming, report assembly
│   ├── RepoManager.java       JGit clone + tree/history metadata pre-computation
│   └── AnalysisEvent.java     UI-facing progress events
├── cli/CliApp.java            terminal UI
└── web/                       Spring Boot SSE server
src/main/resources/
├── codelens/subagents/*.md    the three subagent definitions (prompt + front matter)
└── static/index.html          zero-build web UI (marked.js + mermaid.js via CDN)
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
