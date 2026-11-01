# Diagrams and Flows

> 🌐 Language / 语言: English · [中文](DIAGRAMS.md)

This page collects the things that are easier to draw than to describe: state machines,
sequence diagrams, decision trees, structure charts. Everything is Mermaid, which GitHub
renders directly; the illustrations are PNG.

## 1. Chat screen state machine

```mermaid
stateDiagram-v2
    [*] --> Loading
    Loading --> Ready: nativeInit succeeded
    Loading --> Failed: no backend registered / corrupt model
    Failed --> Loading: retry / lower the context
    Ready --> Generating: send a message
    Generating --> Parsing: onDone
    Parsing --> Generating: there were tool calls and they ran
    Parsing --> Ready: no calls (final answer)
    Generating --> Ready: user stopped / error
    Parsing --> Ready: hit the 8-step ceiling
    Ready --> [*]: leave
```

Key point: **stopping is asynchronous**. Tapping stop only sets a flag and calls
`nativeCancel`; the actual transition happens at the next safe-point check. That is why the
UI shows an intermediate "stopping…" state.

## 2. Sequence of one tool call

```mermaid
sequenceDiagram
    participant U as User
    participant C as ChatActivity
    participant E as LlamaEngine
    participant N as llama_bridge
    participant T as AgentTools
    U->>C: "what files are in the workspace?"
    C->>E: generate(ctx, params)
    E->>N: nativeGenerate(...)
    N-->>E: token callbacks (coalesced at 40ms)
    E-->>C: onToken(piece)
    N-->>E: onDone(fullText)
    E-->>C: onDone(...)
    C->>C: ToolCall.parse(fullText)
    alt matches a registered tool
        C->>U: confirmation needed? (shell/write/delete)
        U-->>C: allow / deny
        C->>T: exec(call)
        T-->>C: result text
        C->>E: generate(ctx + <tool_response>)
    else no match
        C->>C: treat as plain text, end this round
    end
```

## 3. Download decision tree

```mermaid
flowchart TD
    A[Tap download] --> B{A .part already exists?}
    B -- yes --> C[Resume with a Range header]
    B -- no --> D[Download from scratch]
    C --> E{Server returns 206?}
    E -- no --> D
    E -- yes --> F[Space check]
    D --> F
    F --> G{Enough room?}
    G -- no --> H[Refuse and say how much is missing]
    G -- yes --> I[Write into .part]
    I --> J{Length matches?}
    J -- no --> K[Error, keep .part for resume]
    J -- yes --> L{GGUF magic?}
    L -- no --> M[Delete, report "not a valid GGUF"]
    L -- yes --> N[Atomic rename to .gguf]
    N --> O[Write index.json]
    O --> P[Clear downloads.json]
```

## 4. Context trimming strategy

```mermaid
flowchart LR
    A[History] --> B{Estimated tokens ≤ budget?}
    B -- yes --> C[Use as is; highest prefix hit rate]
    B -- no --> D[Drop the oldest message]
    D --> E{Is it user and is the next one assistant?}
    E -- yes --> F[Drop the pair, never leave an orphan answer]
    E -- no --> G[Drop just that one]
    F --> B
    G --> B
```

Why not truncate by token down in the native layer: that would invalidate the shared prefix
wholesale, forcing a full recompute every round and pushing warm-up from 0.5 s to well over
ten seconds. **The granularity of trimming decides whether the cache is worth anything.**

## 5. Recognising tool-call formats

```mermaid
flowchart TD
    A[Full model output] --> B[Strip thinking block first]
    B --> C{Scan four formats}
    C --> D[MiniCPM5: function name=]
    C --> E[Qwen: function=name]
    C --> F[```tool JSON```]
    C --> G[tool_call tag / TOOL_CALL line]
    D --> H[Extract name + params]
    E --> H
    F --> H
    G --> H
    H --> I{Name in the registered set?}
    I -- no --> J[Ignore: an example in prose is not a call]
    I -- yes --> K[Emit ToolCall]
```

## 6. Picking an instruction-set tier

```mermaid
flowchart LR
    A[Process starts] --> B[ggml_backend_load_all_from_path]
    B --> C[Enumerate CPU backends in nativeLibraryDir]
    C --> D[Call ggml_backend_score on each]
    D --> E[Score against HWCAP]
    E --> F[Register only the highest-scoring tier]
    F --> G[Model loads]
    B -.-> H{Nothing registered?}
    H -.-> I[Report "backend not registered": the .so files were not extracted]
```

## 7. Module dependencies

```mermaid
flowchart TB
    subgraph UI[UI layer]
        MA[MainActivity]
        DA[DetailActivity]
        CA[ChatActivity]
        WA[WorkspaceActivity]
        TA[TerminalActivity]
    end
    subgraph DOM[Domain layer]
        API[ModelApi]
        AG[agent/*]
        LL[llama/*]
        LO[local/*]
        CO[core/*]
    end
    subgraph NAT[Native layer]
        BR[llama_bridge.cpp]
        LC[llama.cpp + ggml]
    end
    UI --> DOM
    LL --> BR --> LC
    API -. HTTP .-> MS[(ModelScope API)]
    AG -. HTTP .-> SE[(Search engines)]
    CO -. no Android deps .-> JVM[(JVM unit tests)]
```

## 8. Version history

```mermaid
timeline
    title From a list to an agent
    v1.0 : First ListView release, zero dependencies
    v1.1 : Material 3 + release signing + CI
    v1.2 : API calls fixed (PUT + Criterion)
    v2.0 : llama.cpp built in, offline chat
    v2.1 : Speed (dotprod / prefix reuse / batch size)
    v2.2 : Web access + workspace + preview + terminal
    v2.3 : Instruction-set tiers + KV quantisation + FA
    v2.4 : Tool-call loop + system-level recovery
    v2.5 : core layer + adaptive defaults + docs
    v2.6 : Full EN/ZH bilingual app + in-app language switch
```
