# 🔍 CodeLens

**透视任意 GitHub 仓库 —— 一支 AI 智能体小队为你绘制架构图、挖掘演进史、撰写上手指南。**

[English README](README.md)

把一个 GitHub 公开仓库丢给 CodeLens，它会产出一支智能体小队并行工作后的三份 Markdown 报告：

| 报告 | 内容 |
|---|---|
| 🏛️ `01-architecture.md` | 模块地图、核心抽象、**Mermaid 架构图**（组件依赖 + 数据流） |
| 📜 `02-history.md` | 从 git 历史挖出的项目演进故事：发展阶段、里程碑、贡献者动态、趣味发现 |
| 📖 `03-getting-started.md` | 新人上手指南：环境依赖、快速启动、配置项、推荐首读文件 |

最后汇总为一份 `REPORT.md`。

基于 **[AgentScope Java 2.0](https://github.com/agentscope-ai/agentscope-java)** 构建 —— 一个主编排 Agent 通过 `agent_spawn` 并行调度三个子智能体，事件流实时推送到 CLI 和 Web 界面。

## 工作原理

```
        ┌──────────────────────────┐
        │   🎯 codelens（主 Agent） │  拆解任务，撰写执行摘要
        └───────────┬──────────────┘
        agent_spawn × 3（并行）
   ┌────────────────┼─────────────────┐
   ▼                ▼                 ▼
🏛️ structure-   📜 history-       📖 guide-
   analyst         digger            writer
   读源码树        挖 git 日志       读 README 和
   画架构图        统计与标签        构建文件
   └────────────────┴─────────────────┘
                    ▼
        output/<owner>-<repo>/REPORT.md
```

## 📡 RepoRadar —— 持续盯仓雷达

CodeLens 内置 **RepoRadar**：维护一份仓库清单，定时巡检，告诉你哪里变了 —— 新提交、新版本、breaking 变更 —— 由 LLM 生成增量摘要。这是对话框 AI 做不到的事：**它在你不看它的时候也在工作。**

```bash
./codelens radar                        # 巡检一遍
./codelens radar add https://github.com/owner/repo
./codelens radar list
./codelens --serve                      # 仪表盘 → http://localhost:8321/radar.html
```

- 清单在 `watchlist.yml`；状态与历史在 `~/.codelens/radar/state.json`
- 严重级别：⚪ 平静 · 🟢 新提交 · 🚀 发新版 · 🚨 release notes 中检出 breaking change
- 摘要是增量的：LLM 只处理真正发生变化的仓库
- 设置 `GITHUB_TOKEN` 可获得更高 API 限额（可选）
- 把 `radar check` 挂到 cron / 智能体运行时，即可无人值守监控

## 三分钟上手

**唯一前置要求：JDK 17+**。不需要预装 Maven 和 git 客户端（构建走 Maven Wrapper 或本机 `mvn`，克隆用纯 Java 的 JGit）。

```bash
# 1. 克隆
git clone https://github.com/cml2415730129/codelens.git
cd codelens

# 2. 配置模型（二选一）
export MODEL_API_KEY=sk-...          # 最简单：任意 OpenAI 兼容 key
# 或：cp config.example.yml config.yml  # 然后编辑 config.yml

# 3. 运行
./codelens https://github.com/agentscope-ai/agentscope-java   # CLI 模式
./codelens --serve                                             # Web 模式 → http://localhost:8321
```

首次运行会自动构建 jar。报告输出到 `output/<owner>-<repo>/`。

## 模型配置

CodeLens 通过 AgentScope 的 `ModelRegistry` 接入模型 —— **任何 OpenAI 兼容端点都能用**。编辑 `config.yml`：

```yaml
model:
  provider: kimi        # kimi / openai / deepseek / glm / dashscope / anthropic / gemini / ollama
  name: kimi-k2
  api-key: ${MODEL_API_KEY}
  base-url: ""          # 可选自定义端点
```

现成配方：

| 服务商 | `provider` | `name` 示例 | `base-url` |
|---|---|---|---|
| Kimi（月之暗面） | `kimi` | `kimi-k2` | _（默认）_ |
| OpenAI | `openai` | `gpt-4.1-mini` | _（默认）_ |
| DeepSeek | `deepseek` | `deepseek-chat` | `https://api.deepseek.com/v1` |
| 通义千问 | `dashscope` | `qwen-plus` | _（默认）_ |
| Ollama（本地，免 key） | `ollama` | `llama3` | _（默认）_ |

CLI 参数：`--lang Chinese` —— 指定报告语言（默认英文）。

## 安全说明

- `config.yml` 已被 gitignore，仓库只提交 `config.example.yml` 模板。绝不要提交真实 API key。
- 智能体运行在 AgentScope 权限系统的 `BYPASS` 模式下，但内置危险路径检查（`.env`、`.ssh`、各类凭证文件）**不可绕过**。分析对象是 `~/.codelens/runs/` 下的只读临时克隆。

## 项目结构

```
src/main/java/io/codelens/
├── CodeLens.java              入口（CLI / --serve 分流）
├── config/AppConfig.java      YAML + 环境变量配置，支持 ${VAR} 占位符
├── engine/
│   ├── CodeLensEngine.java    编排、事件流、报告汇总
│   ├── RepoManager.java       JGit 克隆 + 文件树/历史元数据预计算
│   └── AnalysisEvent.java     面向 UI 的进度事件
├── cli/CliApp.java            终端界面
└── web/                       Spring Boot SSE 服务
src/main/resources/
├── codelens/subagents/*.md    三个子智能体定义（prompt + front matter）
└── static/index.html          零构建 Web 界面（CDN 引入 marked.js + mermaid.js）
```

## License

Apache-2.0 — 见 [LICENSE](LICENSE)。
