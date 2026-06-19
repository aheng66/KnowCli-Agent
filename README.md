# KnowCLI

KnowCLI 是一个运行在终端里的本地编码助手。它接入 DeepSeek API，可以在当前项目中读取文件、查看目录、修改代码、执行命令，也能生成 Java、Python 或 Node.js 项目骨架。

KnowCLI v1.0：功能比较简单，但已经实现ReAct模式，能完成一条完整的工作流——理解需求、检查代码、调用工具、根据结果继续处理，最后给出答复。

<img width="1109" height="749" alt="微信图片_20260619193605_263_224" src="https://github.com/user-attachments/assets/00e9b016-5aa4-4a86-ae57-633cd065e23c" />


## 能做什么

- 读取和写入当前项目中的文件
- 查看目录内容
- 执行 Shell 命令
- 创建 Java、Python、Node.js 项目骨架
- 使用 DeepSeek 的工具调用完成多步任务
- 保留当前会话上下文，支持手动清空
- 网络失败时自动重试，并限制任务时长与最大推理轮数

## 运行环境

- JDK 17 或更高版本
- Maven 3.8 或更高版本
- DeepSeek API Key

可用下面的命令检查本机环境：

```shell
java -version
mvn -version
```

## 快速开始

进入项目目录，把 `.env.example` 复制为 `.env`：

```shell
cp .env.example .env
```

Windows PowerShell 可以使用：

```powershell
Copy-Item .env.example .env
```

然后填写 DeepSeek API Key：

```dotenv
DEEPSEEK_API_KEY=你的_API_Key
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
> 阅读这个项目，帮我补充用户登录模块的单元测试
> 检查 pom.xml 中的依赖配置
> 创建一个名为 demo-api 的 Java 项目
```

终端内置两条会话命令：

```text
clear    清空会话历史
exit     退出 KnowCLI
```

## 项目结构

```text
KnowCLI/
├─ src/main/java/com/knowcli/
│  ├─ cli/Main.java              # 命令行入口
│  ├─ agent/Agent.java           # 对话循环、重试与任务控制
│  ├─ llm/DeepSeekClient.java    # DeepSeek API 客户端
│  └─ tool/ToolRegistry.java     # 本地工具注册与执行
├─ src/main/resources/
│  └─ system-prompt.txt          # Agent 系统提示词
├─ src/test/java/                # 单元测试
└─ pom.xml
```

## 工作方式

用户输入任务后，Agent 会把需求和可用工具交给模型。模型可以直接回答，也可以读取文件或执行命令；工具结果会回到同一轮对话中，直到任务完成。

v1.0 默认使用 `deepseek-v4-flash`，API 地址为 `https://api.deepseek.com`。单个任务最长执行 300 秒，最多进行 20 轮工具调用；网络请求失败时最多重试 5 次。

## 安全说明

KnowCLI 能修改文件并执行本地命令，请在可信项目中使用，执行前也要把任务描述清楚。文件写入被限制在启动时的项目根目录内，单个文件最大 5 MiB；命令最长运行 60 秒，输出超过 1 MiB 时会截断。

不要提交 `.env`，也不要把 API Key 写进源码、截图或日志。

## 当前限制

- 只支持 DeepSeek API
- 暂不提供配置文件或命令行参数来切换模型
- 命令执行依赖当前操作系统的 Shell
- 还没有打包为独立可执行程序，需要通过 Maven 启动

## 开发与测试

运行全部测试：

```shell
mvn test
```

编译项目：

```shell
mvn package
```

主要测试位于：

- `DeepSeekClientTest`：请求构造、响应解析和异常处理
- `AgentTest`：Agent 循环、工具调用与会话逻辑

## 作者

Zheng666
