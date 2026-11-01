# 图解与流程图

> 🌐 语言 / Language: 中文 · [English](DIAGRAMS.en.md)

这一页是「图比其他文字省事」的那些东西：状态机、时序、决策树、结构图。
全部用 Mermaid 写，GitHub 直接渲染；配图是 PNG。

## 1. 对话页状态机

```mermaid
stateDiagram-v2
    [*] --> 加载中
    加载中 --> 就绪: nativeInit 成功
    加载中 --> 失败: 后端未注册 / 模型损坏
    失败 --> 加载中: 重试 / 降级上下文
    就绪 --> 生成中: 发送消息
    生成中 --> 解析中: onDone
    解析中 --> 生成中: 有工具调用且已执行
    解析中 --> 就绪: 无调用（最终回答）
    生成中 --> 就绪: 用户停止 / 出错
    解析中 --> 就绪: 达到 8 步上限
    就绪 --> [*]: 退出
```

要点：**停止是异步生效的**。点停止只是置标志位并 `nativeCancel`，
真正的状态切换发生在下一次安全点检查时。所以界面上有「正在停止…」这个中间态。

## 2. 一次工具调用的时序

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as ChatActivity
    participant E as LlamaEngine
    participant N as llama_bridge
    participant T as AgentTools
    U->>C: 发送「看看工作区里有哪些文件」
    C->>E: generate(ctx, params)
    E->>N: nativeGenerate(...)
    N-->>E: token 回调（40ms 合并）
    E-->>C: onToken(piece)
    N-->>E: onDone(fullText)
    E-->>C: onDone(...)
    C->>C: ToolCall.parse(fullText)
    alt 命中已注册工具
        C->>U: 需要确认？（shell/write/delete）
        U-->>C: 允许 / 拒绝
        C->>T: exec(call)
        T-->>C: 结果文本
        C->>E: generate(ctx + <tool_response>)
    else 未命中
        C->>C: 当作普通文本，结束本轮
    end
```

## 3. 下载决策树

```mermaid
flowchart TD
    A[点击下载] --> B{已有 .part?}
    B -- 是 --> C[带 Range 头续传]
    B -- 否 --> D[从头下载]
    C --> E{服务端返回 206?}
    E -- 否 --> D
    E -- 是 --> F[空间体检]
    D --> F
    F --> G{放得下?}
    G -- 否 --> H[拒绝并说明差多少]
    G -- 是 --> I[写入 .part]
    I --> J{长度匹配?}
    J -- 否 --> K[报错，保留 .part 供续传]
    J -- 是 --> L{GGUF 魔数?}
    L -- 否 --> M[删除，报「不是有效的 GGUF」]
    L -- 是 --> N[原子改名 .gguf]
    N --> O[写入 index.json]
    O --> P[清理 downloads.json]
```

## 4. 上下文裁剪策略

```mermaid
flowchart LR
    A[历史消息] --> B{估算 token ≤ 预算?}
    B -- 是 --> C[原样使用，前缀命中率最高]
    B -- 否 --> D[丢掉最早的一条]
    D --> E{它是 user 且下一条是 assistant?}
    E -- 是 --> F[成对丢弃，避免孤立回答]
    E -- 否 --> G[只丢这一条]
    F --> B
    G --> B
```

为什么不在 native 层按 token 截断：那会让公共前缀整体作废，每轮都要全量重算，
预热时间从 0.5 秒涨到十几秒。**裁剪的粒度决定了缓存的有效性**。

## 5. 工具调用格式识别

```mermaid
flowchart TD
    A[模型输出全文] --> B[先剥离思考块]
    B --> C{扫描四种格式}
    C --> D[MiniCPM5: function name=]
    C --> E[Qwen: function=name]
    C --> F[```tool JSON```]
    C --> G[tool_call 标签 / TOOL_CALL 行]
    D --> H[提取 name + params]
    E --> H
    F --> H
    G --> H
    H --> I{name 在已注册集合?}
    I -- 否 --> J[忽略：普通示例不算调用]
    I -- 是 --> K[产出 ToolCall]
```

## 6. 多档后端选择

```mermaid
flowchart LR
    A[进程启动] --> B[ggml_backend_load_all_from_path]
    B --> C[枚举 nativeLibraryDir 下的 CPU 后端]
    C --> D[逐个调用 ggml_backend_score]
    D --> E[按 HWCAP 打分]
    E --> F[只注册分数最高的那一档]
    F --> G[模型加载成功]
    B -.-> H{一个都没注册?}
    H -.-> I[报「后端未注册」：.so 没释放]
```

## 7. 模块依赖

```mermaid
flowchart TB
    subgraph UI[界面层]
        MA[MainActivity]
        DA[DetailActivity]
        CA[ChatActivity]
        WA[WorkspaceActivity]
        TA[TerminalActivity]
    end
    subgraph DOM[领域层]
        API[ModelApi]
        AG[agent/*]
        LL[llama/*]
        LO[local/*]
        CO[core/*]
    end
    subgraph NAT[原生层]
        BR[llama_bridge.cpp]
        LC[llama.cpp + ggml]
    end
    UI --> DOM
    LL --> BR --> LC
    API -. HTTP .-> MS[(魔搭接口)]
    AG -. HTTP .-> SE[(搜索引擎)]
    CO -. 无 Android 依赖 .-> JVM[(JVM 单测)]
```

## 8. 版本演进

```mermaid
timeline
    title 从列表到智能体
    v1.0 : ListView 初版，零依赖
    v1.1 : Material 3 + 正式签名 + CI
    v1.2 : 接口改对（PUT + Criterion）
    v2.0 : 内置 llama.cpp，离线对话
    v2.1 : 提速（dotprod / 前缀复用 / 批大小）
    v2.2 : 联网 + 工作区 + 预览 + 终端
    v2.3 : 多档指令集 + KV 量化 + FA
    v2.4 : 工具调用闭环 + 系统纠错
    v2.5 : core 层 + 自适应 + 文档
```
