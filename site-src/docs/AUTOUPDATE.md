# 版本自动更新

站点的版本号、体积、发布时间必须是真的。手写在清单里，发一次版就漂一次，而且没人会发现。
所以这条链路的唯一目标是：**对方一发新版本，页面自己就变，不需要人记得改 JSON。**

## 数据怎么流动

```
各仓库 GitHub Release
        │
        ├── 每小时定时（schedule，兜底）
        └── 发版即时（repository_dispatch，秒级）
        ▼
scripts/fetch_releases.py --write      # 只在这步联网
        ▼
data/releases.json                     # 入库的快照（真相源）
        ▼
scripts/build_site.py                  # 构建过程不联网
        ▼
site/*.html + site/update.json         # 页面给人看，JSON 给程序看
```

关键取舍：**构建仍然不联网**。这是站点自己的承诺（同一 commit 永远产出同一份产物，
CI 会比对两次构建的哈希）。联网只发生在同步这一步，同步的产物是要提交进仓库的文件。

## 三个 workflow

| 文件 | 什么时候跑 | 干什么 |
|---|---|---|
| `sync.yml` | 每小时 17 分、`repository_dispatch`、手点 | 拉线上 Release，数据真变了才提交 |
| `ci.yml` | 每次 push / PR、每周一 | 构建 + 结构检查 + 外链体检 |
| `deploy.yml` | `data/**` 或 `scripts/**` 有 push、`repository_dispatch` | 构建并发布 Pages |

`sync.yml` 提交后由 `deploy.yml` 完成发布——**先提交快照再发布**，顺序不能反。
反了的话 Pages 上的产物会和仓库里的快照对不上，下次谁重新构建，「页面就变回去了」。

## 幂等性

`fetch_releases.py` 比较内容时会忽略 `generated_at` 时间戳，只有版本 / 体积 / 文件名 /
下载地址真的变了，才写 `changed=true`，后面的提交步骤才会执行。
所以每小时跑一次不会把仓库刷成一堆空提交。

## 需要对方做的一步（可选，但推荐）

秒级同步靠 `repository_dispatch`，需要在**各应用仓库**里加一个 webhook：

- Payload URL：`https://api.github.com/repos/ice-wocker/icesuite/dispatches`
- Content type：`application/json`
- Events：**Releases**
- Body（这是 `repository_dispatch` 要求的固定格式）：

```json
{"event_type": "release"}
```

配一次即可。**不配也不会坏**——每小时定时兜底会把版本补上，只是延迟最多一小时。
webhook 会丢、会重装、会忘配，所以定时这一路必须存在，它不是冗余。

## update.json

给程序看的版本清单，`build_site.py` 生成：

```json
{
  "schema": 1,
  "apps": {
    "ModelScopeBrowser": {
      "name": "魔搭模型库",
      "page": "https://ice-wocker.github.io/icesuite/modelscopebrowser.html",
      "minAndroid": "7.0",
      "arch": "arm64-v8a",
      "latest": {
        "versionName": "2.7.0",
        "versionCode": null,
        "publishedAt": "2026-11-01",
        "size": 8422081,
        "url": "https://github.com/.../ModelScope-Models-2.7.0.apk",
        "sha256": null
      }
    }
  }
}
```

几个刻意的设计：

- `url` 是 APK **直链**，不是 Releases 页面。客户端拿去就能下，不用二次解析 HTML。
- `versionCode` 为 `null` 是正常的：不是每个仓库都会把 `android:versionCode` 写进 Release 正文。
  客户端应回退到 `versionName` 比较。**不要**自己从 `2.7.0` 推算出 `2700`——
  猜错了会让用户以为「已是最新」永远收不到更新。
- `sha256` 同样允许为 `null`，理由同上：没有就是没有，不做假数据。

`check_site.py` 会逐字段核对 `update.json` 与 `releases.json`，
并断言 `url` 以 `.apk` 结尾——这个文件的价值全在「准」，错一个字节客户端就会判错。

## 页面上的过期提示

商店页顶部会显示快照时间，并用 JS 把「页面渲染时的版本」和「线上 `update.json` 的版本」对一次。
不一致就明确提示刷新。这是为了防止静态站最阴的失败模式：**看起来一切正常，数字却是旧的**。

刻意**不**自动刷新——用户可能正在下载，刷新会打断它。

## 手动操作

```bash
python3 scripts/fetch_releases.py --check   # 只看快照与线上是否一致（CI 用）
python3 scripts/fetch_releases.py --write   # 更新快照
python3 scripts/build_site.py               # 重新生成站点
python3 scripts/check_site.py               # 自检
```
