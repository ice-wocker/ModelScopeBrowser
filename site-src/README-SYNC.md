# ice 工坊站点源码（CNB 镜像）

`https://ice-wocker.github.io/icesuite/` 的生成器与数据，镜像到本仓库便于审阅与试跑。

```bash
cd site-src
python3 scripts/build_site.py      # 生成 site/
python3 scripts/check_site.py      # 自检（结构 / 站内链接 / update.json 一致性 / 外链）
python3 scripts/fetch_releases.py --check   # 快照是否落后于线上 Release（需联网）
```

零第三方依赖，只用 Python 标准库。文档见 `site-src/docs/`。
