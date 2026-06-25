# KnowCLI

KnowCLI 是一个运行在终端里的本地智能编码助手。它接入 DeepSeek API，可以在当前项目中读取文件、查看目录、写入文件、执行命令，并根据任务持续调用工具完成多步骤开发工作。

当前版本已经包含两种工作方式：

- 普通 ReAct 模式：直接输入需求，Agent 会边思考边调用工具完成任务。
- Plan-and-Execute 模式：先把复杂需求拆成计划，确认后再逐步执行。

<img width="1109" height="749" alt="KnowCLI screenshot" src="https://github.com/user-attachments/assets/00e9b016-5aa4-4a86-ae57-633cd065e23c" />

## 功能特性

- 读取当前项目内的文件内容
- 列出目录内容
- 写入或修改当前项目内的文件
- 执行本地 Shell 命令
- 创建 Java、Python、Node.js 项目骨架
- 使用 DeepSeek 工具调用完成多轮任务
- 保留当前会话上下文，并支持手动清空
- 支持 `/plan` 进入 Plan-and-Execute 模式
- 网络请求失败时自动重试，并限制任务时长与最大推理轮数

## 运行环境

- JDK 17 或更高版本
- Maven 3.8 或更高版本
- DeepSeek API Key

检查本机环境：

```shell
java -version
mvn -version
```

## 快速开始

进入项目目录后，复制环境变量示例文件：

```shell
cp .env.example .env
```

Windows PowerShell 可以使用：

```powershell
Copy-Item .env.example .env
```

然后在 `.env` 中填写 DeepSeek API Key：

```dotenv
DEEPSEEK_API_KEY=your_deepseek_api_key
```

安装依赖并运行测试：

```shell
mvn test
```

启动 KnowCLI：

```shell
mvn exec:java
```

启动后直接输入任务即可：

```text
> 阅读这个项目，帮我总结主要模块
> 检查 pom.xml 中的依赖配置
> 创建一个名为 demo-api 的 Java 项目
```

## 常用命令

```text
clear          清空普通对话历史
exit           退出 KnowCLI
/plan          进入 Plan-and-Execute 模式
/plan <task>   直接为指定任务生成执行计划
```

进入 `/plan` 后，可以输入要规划的任务；生成计划后，使用方向键选择：

```text
执行计划
补充需求
取消计划
```

## 项目结构

```text
KnowCLI/
├── src/main/java/com/knowcli/
│   ├── cli/
│   │   ├── Main.java                 # 命令行入口
│   │   ├── PlanModeState.java        # /plan 命令状态管理
│   │   ├── PlanInteraction.java      # 计划确认与补充交互
│   │   └── PlanDecisionMenu.java     # 计划决策菜单
│   ├── agent/
│   │   ├── Agent.java                # ReAct 对话循环、重试与任务控制
│   │   └── PlanExecuteAgent.java     # 计划生成与分步执行
│   ├── llm/
│   │   └── DeepSeekClient.java       # DeepSeek API 客户端
│   ├── plan/
│   │   ├── Planner.java              # 任务规划器
│   │   ├── ExecutionPlan.java        # 执行计划模型
│   │   └── Task.java                 # 单个任务模型
│   └── tool/
│       └── ToolRegistry.java         # 本地工具注册与执行
├── src/main/resources/
│   ├── SYSTEM_PROMPT.txt             # 普通 Agent 系统提示词
│   └── PLANNING_PROMPT.txt           # 计划生成提示词
├── src/test/java/                    # 单元测试
├── .env.example
└── pom.xml
```

## 工作方式

普通模式下，用户输入任务后，Agent 会把需求、对话历史和可用工具交给模型。模型可以直接回答，也可以请求读取文件、写入文件、列目录或执行命令；工具结果会回到同一轮对话中，直到任务完成或达到限制。

Plan-and-Execute 模式下，KnowCLI 会先为用户目标生成一个包含任务依赖关系的执行计划。用户可以选择直接执行、补充需求重新规划，或取消计划。执行时，每个子任务都会带着原始目标和依赖结果交给 Agent 处理。

默认模型为 `deepseek-v4-flash`，默认 API 地址为 `https://api.deepseek.com`。单个任务最长执行 300 秒，最多进行 20 轮工具调用；网络请求失败时最多重试 5 次。

## 内置工具

| 工具 | 用途 |
| --- | --- |
| `read_file` | 读取文件内容 |
| `write_file` | 写入文件内容 |
| `list_dir` | 列出目录内容 |
| `execute_command` | 执行 Shell 命令 |
| `create_project` | 创建 Java、Python 或 Node.js 项目骨架 |

## 安全说明

KnowCLI 能修改文件并执行本地命令，请只在可信项目中使用，并在输入任务时尽量描述清楚边界。

当前安全限制：

- 文件写入限制在启动时的项目根目录内
- 单个写入文件最大 5 MiB
- 不允许写入符号链接目标
- 命令最长运行 60 秒
- 命令输出超过 1 MiB 会被截断
- 目录列表最多返回 1000 条

不要提交 `.env`，也不要把 API Key 写进源码、截图或日志。

## 开发与测试

运行全部测试：

```shell
mvn test
```

编译项目：

```shell
mvn package
```

主要测试覆盖：

- `DeepSeekClientTest`：请求构造、响应解析和异常处理
- `AgentTest`：Agent 循环、工具调用与会话逻辑
- `PlanExecuteAgentTest`：计划执行流程
- `PlannerTest`：计划生成与解析
- `ExecutionPlanTest`、`PlanModeStateTest`、`PlanInteractionTest`、`PlanDecisionMenuTest`：计划模型与 CLI 交互

## 当前限制与后续方向

- 目前主要接入 DeepSeek API，后续可以增加模型配置切换能力
- 记忆系统仍较轻量，后续可以扩展长期记忆、短期记忆和上下文压缩
- 命令执行依赖当前操作系统 Shell
- 源码中部分历史中文字符串需要进一步统一编码和文案

## 作者

Aheng666
