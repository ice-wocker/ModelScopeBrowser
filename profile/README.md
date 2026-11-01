<div align="center">

<img src="https://github.com/ice-wocker.png" width="120" alt="ice-wocker" />

# ice-wocker

**把大模型装进手机，让它真的能干活。**

*LLMs on phones — offline inference, agent loops, no cloud in the way.*

[ModelScopeBrowser](https://github.com/ice-wocker/ModelScopeBrowser) ·
[Releases](https://github.com/ice-wocker/ModelScopeBrowser/releases) ·
[Benchmarks](https://github.com/ice-wocker/ModelScopeBrowser/blob/main/docs/BENCHMARKS.md)

</div>

---

我做移动端 AI 的一种具体版本：不是给云端接口套个壳，而是把**模型、推理引擎、工具闭环**一起压进一台手机，
让它在飞行模式下依然能跑命令、读写文件、查资料。

- **离线优先** — 纯 CPU 推理，数据不出设备。没有登录，没有 API Key，没有「网络异常请重试」。
- **动手，而不是聊天** — 模型输出被当成指令执行，真实结果回灌再继续推理，最多 8 步；危险操作先弹窗。
- **按机器选档** — arm64 多份 CPU 后端，运行时按 `HWCAP` 打分只加载最高一档；老机型退回基线，不 SIGILL。
- **先把话说清楚** — 上下文开多大、空间够不够、参数为什么被改，先算成人话，再交给引擎。

我关心的不是「跑起来了」，而是**为什么它是这样**：JNI 的符号要能被 R8 重命名，特殊 token 在 `special=false` 时会静默丢失，
超核跑线程会让一次 decode 从 28 ms 变成 3952 ms。这类结论最后都变成代码和单元测试，而不是一句「已优化」。

---

<div align="center">

**[ModelScopeBrowser](https://github.com/ice-wocker/ModelScopeBrowser)**
Android 7.0+ · 内置 llama.cpp · GGUF · 智能体工具闭环 · MIT

[![Release](https://img.shields.io/github/v/release/ice-wocker/ModelScopeBrowser?color=FF5A2D&label=release&style=flat-square)](https://github.com/ice-wocker/ModelScopeBrowser/releases)
[![CI](https://github.com/ice-wocker/ModelScopeBrowser/actions/workflows/android.yml/badge.svg)](https://github.com/ice-wocker/ModelScopeBrowser/actions/workflows/android.yml)
[![Stars](https://img.shields.io/github/stars/ice-wocker/ModelScopeBrowser?color=FF5A2D&style=flat-square)](https://github.com/ice-wocker/ModelScopeBrowser/stargazers)
[![Last commit](https://img.shields.io/github/last-commit/ice-wocker/ModelScopeBrowser?style=flat-square&color=4B5563)](https://github.com/ice-wocker/ModelScopeBrowser/commits/main)

<sub>如果它对你有用，给个 ⭐ 是最大的鼓励。</sub>

</div>
