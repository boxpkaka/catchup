# 自托管部署

[← 返回文档目录](../README.md#-文档)

本地先跑起来见 [快速开始](../README.md#-快速开始)；凭据和参数见 [配置](configuration.md)。

## 组件

作者的服务器上是这样跑的：

| 组件 | 作用 |
|:--|:--|
| `catchup collect`（cron，每 10 分钟） | 采集到期的来源，之后归并事件、写简报、推送 |
| `catchup serve`（systemd 用户服务 `catchup-web`） | 预览页和 App 接口 `/v1`，只监听本机 `127.0.0.1:2591` |
| 推送 hub（infofeed，独立服务，不在本仓库） | 推送通道（`/stream` 免认证，`/publish` 需要 `FEED_TOKEN`），并分发 App（`/app/version`、`/app.apk`） |
| Caddy | 对外入口 `https://43.139.27.91:8443`，证书由内部 CA 签发 |

## 常驻服务

`catchup serve` 作为 systemd 用户服务 `catchup-web` 常驻，改了代码后重启：

```bash
systemctl --user restart catchup-web
```

## 定时采集

Google、Hugging Face、YouTube、Reddit 在服务器上直连不通，所以采集走本机 mihomo 代理，国内站点由 mihomo 规则直连；`catchup-web` 的 systemd 单元里也设了同样的 `Environment=`：

```cron
*/10 * * * * cd /path/to/catchup && http_proxy=http://127.0.0.1:7890 https_proxy=http://127.0.0.1:7890 no_proxy=localhost,127.0.0.1 python3 -m catchup collect >> logs/collect.log 2>&1
```

## 设备管理

- App 经 Caddy 访问 `https://43.139.27.91:8443/v1/*`；第一台注册的设备自动批准，之后的新设备要用 `devices approve <id>` 确认。
- 给别人用时用 `devices mirror <id>` 批准为只读设备：内容和本人看到的一样（推送照收），但反馈、追踪、对话、改记忆都会被拒绝，不影响本人的推荐。
- 设了 `SHARE_KEY` 时，新设备在待确认页填对密钥就直接成为只读设备，不用等确认。

## 远程查看预览页

预览页只监听本机，远程查看用 SSH 端口转发：

```bash
ssh -L 2591:127.0.0.1:2591 <server>
```
