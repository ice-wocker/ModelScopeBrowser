# 域名切换手册（eu.org → GitHub Pages）

平时不用看。只有「换域名」或「域名掉了」时才需要。

## 前置

- eu.org 的 contact handle 已完成邮箱验证
- 域名已申请并被 eu.org 审核通过（这一步是人工的，几天到几周）

## 1. 配 eu.org 侧的 DNS

登录 <https://nic.eu.org/arf/en/>（handle `IW543-FREE`），进入域名 → Nameservers，
填 eu.org 自己提供的 nameserver，然后编辑 zone：

```
; 根域，GitHub Pages 的 4 个 A 记录
@   3600 IN A     185.199.108.153
@   3600 IN A     185.199.109.153
@   3600 IN A     185.199.110.153
@   3600 IN A     185.199.111.153

; 根域的 IPv6，GitHub Pages 的 4 个 AAAA
@   3600 IN AAAA  2606:50c0:8000::153
@   3600 IN AAAA  2606:50c0:8001::153
@   3600 IN AAAA  2606:50c0:8002::153
@   3600 IN AAAA  2606:50c0:8003::153

; www 走 CNAME
www 3600 IN CNAME ice-wocker.github.io.
```

> 注意 A / AAAA 必须是上面这 8 个 GitHub 官方地址，不要用 `ping` 出来的临时 IP。

## 2. 验证 DNS 生效

```bash
dig +short A icesuite.eu.org
dig +short AAAA icesuite.eu.org
dig +short CNAME www.icesuite.eu.org
```

## 3. 在 GitHub 侧绑定

```bash
TOKEN=<你的 PAT>
curl -X PUT https://api.github.com/repos/ice-wocker/icesuite/pages \
  -H "Authorization: token $TOKEN" \
  -d '{"cname":"icesuite.eu.org","https_enforced":true}'
```

或者仓库 Settings → Pages → Custom domain 填 `icesuite.eu.org`。

## 4. 等证书

绑好后 GitHub 会自动签 Let's Encrypt 证书，通常几分钟到一小时内生效。
验证：

```bash
curl -sI https://icesuite.eu.org | head -1
curl -sI https://www.icesuite.eu.org | head -1
```

## 换域名要做的事

**只有一处**：`data/projects.py` 里的 `SITE["domain"]`。

`scripts/build_site.py` 会据此产出 `site/CNAME`，
CI 里有一个 job 专门校验「域名在 data/ 里只出现一次」，
所以换域名不会漏改，也不会出现 CNAME 和配置打架。
