# 魔搭模型库 (ModelScopeBrowser)

一个轻量 Android App，用来浏览[魔搭社区 ModelScope](https://www.modelscope.cn/models)上的**全部大模型**：分页列表、关键字搜索、模型详情，并内置网页兜底模式。纯 Java 实现，**零第三方依赖**。

## 功能

- **模型列表**：分页加载魔搭全部模型（当前接口返回总量约 22.7 万个），滑到底自动加载下一页
- **关键字搜索**：按模型名 / 中文名检索，如 `Qwen`、`DeepSeek`、`语音`
- **列表项信息**：中文名、`命名空间/模型名`、任务类型、下载量、收藏数、许可证、标签、简介
- **详情页**：调用官方详情接口展示任务类型、下载/收藏、许可证、创建与更新时间、模型简介；支持「在魔搭打开 / 复制链接 / 系统浏览器打开」
- **网页模式**：右上角一键进入，直接加载 `modelscope.cn/models`，作为任何异常情况下的兜底

## 下载安装

- 仓库内：[`dist/ModelScope-Models.apk`](dist/ModelScope-Models.apk)
- 或到 [Releases](../../releases) 下载

要求：Android 7.0 (API 24) 及以上。首次安装需允许「安装未知来源应用」。

## 界面流程

```
启动 → 模型列表（分页 / 搜索）
        ├── 点击某一项 → 详情页 → 在魔搭打开 / 复制链接 / 浏览器打开
        └── 右上角「网页模式」→ 内置 WebView 打开魔搭官网（兜底）
```

## 构建

```bash
# 环境：JDK 17、Android SDK (platform 34 + build-tools 34)
export JAVA_HOME=/path/to/jdk17
echo "sdk.dir=/path/to/android-sdk" > local.properties

gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 与 `build/` 已在 `.gitignore` 中排除，需按本机路径自行生成。

## 目录结构

```
app/src/main/java/com/mscope/browser/
├── MainActivity.java      # 列表页：分页、搜索、下拉加载
├── DetailActivity.java    # 详情页
├── WebActivity.java       # 网页兜底模式
├── ModelApi.java          # 数据层：接口请求 + 宽松 JSON 解析
└── ModelItem.java         # 模型数据模型
app/src/main/res/          # 布局 / 文案 / 图标
```

## 接口说明

数据全部来自魔搭公开接口：

| 用途 | 接口 |
|---|---|
| 模型列表（主） | `POST /api/v1/dolphin/models` |
| 模型列表（降级 1） | `GET /api/v1/dolphin/models?PageSize=&PageNumber=&SortBy=Default&Name=` |
| 模型列表（降级 2，兜底） | `GET /api/v1/dolphin/agg/homepage` |
| 模型详情 | `GET /api/v1/models/{namespace}/{name}` |

列表采用**三层降级 + 宽松解析**：不依赖固定返回层级，会自动在响应 JSON 中定位「最像模型数组」的字段并读取同级 `TotalCount`，因此接口结构调整时仍可工作。

## 已知限制

- 列表主接口为 POST 请求，部分企业网关会拦截 POST，此时会自动降级；若全部失败可直接使用「网页模式」
- 详情页数据来自公开接口，模型简介为原始 Markdown 文本，未做富文本渲染
- 未做登录，因此不展示需要登录权限的模型内容

## 免责声明

本项目为第三方客户端，与魔搭社区/阿里巴巴无关联，仅用于学习与技术研究。所有模型数据与内容的版权归原作者及魔搭社区所有，请遵守其服务条款。

## License

未指定（如需开源协议请自行补充）。