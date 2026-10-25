# Catchup

替你持续盯住 AI 领域的信息源，把零散的信号合并成事件，只把对你真正重要的变化推给你。

产品思路见 `~/Catchup_for_AI_Product_Thesis.pdf`：Push-first、Event-first、Follow-up-first，而不是 Feed-first。

## 现状

| 阶段 | 状态 |
|---|---|
| 信号采集（GitHub / HF / arXiv / HN / 官方博客 / Google News / YouTube / Reddit / DeepSeek 搜索 / 页面监控） | ✅ 每小时运行 |
| 事件合并：规则聚类 + DeepSeek 合并同一件事，事件持久化有固定 id | ✅ |
| 排序：规则分（兴趣、来源、多源、热度）× 模型给的重要性；每个事件有 AI 简报（发生了什么 / 与你有关） | ✅ |
| 推送：只推重要且未推过的事件，每次最多 2 条、24 小时最多 6 条、23:00～8:00 不推 | ✅ 经 `../infofeed` 的 hub 送达 |
| 安卓 App：首页补课句 + 事件卡、详情页、左滑“不需要”、下拉刷新、设备自动绑定 | ✅ |
| 事件下对话、对话沉淀为记忆、记忆驱动排序（P1）；补课简报、持续追踪（P2） | ⏳ |

## 使用

运行时只依赖 Python 3.10+ 标准库，在项目根目录直接执行：

```bash
python3 -m catchup sources            # 列出已配置的来源和可用类型
python3 -m catchup collect            # 采集一次，之后自动做事件归并、简报和推送（--no-update 只采集）
python3 -m catchup collect --only youtube reddit
python3 -m catchup update --no-push   # 不采集，只重新归并事件、写简报（不推送）
python3 -m catchup serve              # 预览页 http://127.0.0.1:2591，以及 App 接口 /v1
python3 -m catchup devices            # 查看 App 设备；devices approve <id> 批准新设备
python3 -m unittest                   # 测试
```

凭据放在 `.env`（参考 `.env.example`）：`GITHUB_TOKEN`（可选，提高额度）、`DEEPSEEK_API_KEY`（简报、合并和 DeepSeek 来源，没有则只跑规则部分）、`FEED_URL` / `FEED_TOKEN`（推送到 hub）。

`catchup serve` 作为 systemd 用户服务 `catchup-web` 常驻（`systemctl --user restart catchup-web`）。App 经 Caddy 访问 `https://43.139.27.91:8443/v1/*`；第一台注册的设备自动批准，之后的新设备要用 `devices approve` 确认。

定时任务：

```
37 * * * * cd /home/boxp/workspace/catchup && /usr/bin/python3 -m catchup collect >> logs/collect.log 2>&1
```

预览页只监听本机，远程查看用 SSH 端口转发：`ssh -L 2591:127.0.0.1:2591 <server>`。

## 安卓 App

不用 Gradle，`android/build.sh` 直接调用 SDK 工具打包（JDK 17 在 `~/.local/share/jdk17`，SDK 在 `~/.local/share/android-sdk`）。签名用本机的 `android/release.keystore`（不入库），密码是 `.env` 里的 `APK_KEYSTORE_PASS`；换了密钥，已安装的 App 就无法覆盖升级。

```bash
./android/build.sh                  # 本地调试包 → android/build/infofeed.apk（版本 0.0.0-dev）
./android/release.sh 1.1.0          # 构建 v1.1.0：放到 android/dist/ 供自建服务器分发，并发布 GitHub Release 存档
```

App 名为 Catchup（包名仍是 `cloud.boxpkaka.infofeed`，保证能覆盖升级），打开即用，不需要令牌：hub 的 `/stream` 免认证，只有 `/publish` 需要 `FEED_TOKEN`。图标是 `res/mipmap-anydpi-v26/` 下的自适应图标（宣纸底、墨色 C、朱砂点）。

发布前要先提交并 `git push`，脚本会检查。手机在国内网络下连 GitHub 不稳定，所以 App 只访问推送用的同一个服务器：`../infofeed/hub.py` 从 `android/dist/` 提供 `/app/version` 和 `/app.apk`（App 经 `https://43.139.27.91:8443` 直连 IP；`boxpkaka.cloud` 未备案，443 上的普通 TLS 连接会被腾讯云 reset）。App 打开时每 6 小时最多检查一次，版本号比当前新就显示“发现新版本”，点击后下载并调起系统安装；设置里也可以手动“检查更新”。首次安装用手机浏览器打开 `https://43.139.27.91:8443/app.apk` 下载（浏览器会提示证书不受信任，选择继续访问即可）。8443 的证书由服务器上 Caddy 的内部 CA 签发，App 通过 `res/xml/network_security_config.xml` 只对该 IP 信任内置的 `res/raw/catchup_ca.crt`。

## 结构

```
catchup/
  models.py          Signal（一条原始内容）、Event（合并后的一件事）
  sources/           来源插件：base.py 定义基类和注册表，每种来源一个类
  store.py           SQLite 存储与顺序迁移（PRAGMA user_version）
  collector.py       依次运行来源，单个来源失败不影响其他来源
  llm.py             DeepSeek 对话接口（deepseek-flash，关闭思考，输出 JSON）
  pipeline/
    events.py        信号 → 事件：规则聚类，并持久化为有固定 id 的事件
    ranking.py       事件打分，每项加分都附带可解释的理由
    brief.py         模型合并同一件事、写简报（标题 / 摘要 / 与你有关 / 重要性）
    push.py          选择要推送的事件并经 hub 送达
    update.py        采集之后的整条处理流程
    feed.py          预览页用的数据
  web/
    server.py        预览页 + App 接口路由
    app_api.py       App 接口：设备绑定、事件流、详情、反馈
config.json          来源、兴趣、排序参数
data/catchup.db      数据库（不入库）
```

数据流：`sources → store(signals) → events(持久化) → ranking → brief(LLM) → push / App`。采集和呈现解耦，改排序不需要重新采集。

## 配置

`config.json` 的 `sources` 里每个 key 是一个来源实例，`type` 决定用哪个插件，其余字段是该插件的参数。通用字段：

| 字段 | 含义 |
|---|---|
| `type` | 插件类型，`python3 -m catchup sources` 可查看全部 |
| `title` | 页面上的来源名 |
| `tier` | `official`（官方一级源）/ `primary`（一手技术源）/ `signal`（弱信号），影响排序 |
| `kind` | 内容类别，如 代码、模型、论文、社区、视频、新闻 |
| `enabled` | 设为 `false` 可临时停用 |

`interests` 是兴趣画像（名称、权重、关键词），`ranking` 是排序参数（时间窗、半衰期、各等级权重等，默认值见 `pipeline/ranking.py` 的 `DEFAULTS`）。

## 新增一种来源

1. 在 `catchup/sources/` 下新建模块，继承 `Source`（feed 类继承 `FeedSource`，只需实现 `feeds()`）：

   ```python
   @register
   class Bluesky(Source):
       type = "bluesky"
       default_kind = "社区"
       label_format = "Bluesky · {channel}"

       def fetch(self):
           for handle in self.options["accounts"]:
               ...
               yield self.signal(url, title, summary, channel=handle, published=parse_time(ts), heat=likes / 100)
   ```

2. 在 `catchup/sources/__init__.py` 里导入这个模块。
3. 在 `config.json` 的 `sources` 里加一个 `{"type": "bluesky", ...}` 实例。
4. 在 `tests/` 里用 `unittest.mock` 替换 `catchup.http.get` / `get_json` 写解析测试。

约定：URL 是去重主键，要尽量规范化（如 arXiv 去掉版本号、HN 优先用外链）；`heat` 由来源自己归一化，约 1 表示明显热门；子请求失败用 `log.warning` 记录后继续。
