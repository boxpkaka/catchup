# Catchup

替你持续盯住 AI 领域的信息源，把零散的信号合并成事件，只把对你真正重要的变化推给你。

产品思路见 `~/Catchup_for_AI_Product_Thesis.pdf`：Push-first、Event-first、Follow-up-first，而不是 Feed-first。

## 现状

| 阶段 | 状态 |
|---|---|
| 信号采集（GitHub / HF / arXiv / HN / 官方博客 / Google News / YouTube / Reddit） | ✅ 每小时运行 |
| 事件合并（同一论文/仓库/模型 + 标题相似度） | ✅ 规则版 |
| 相关性排序与“为什么推给你” | ✅ 规则版，兴趣画像手写在配置里 |
| 预览页（今日推送 / 事件流 / 来源） | ✅ 只读 |
| 安卓 App（`android/`，自建服务器分发 + 应用内更新，GitHub Release 存档） | ✅ 已迁入，推送仍走 `../infofeed` 的 hub |
| 推送到手机、持续追踪、反馈学习 | ⏳ 未开始 |

## 使用

运行时只依赖 Python 3.10+ 标准库，在项目根目录直接执行：

```bash
python3 -m catchup sources            # 列出已配置的来源和可用类型
python3 -m catchup collect            # 采集一次
python3 -m catchup collect --only youtube reddit
python3 -m catchup serve              # 预览页 http://127.0.0.1:2591
python3 -m unittest                   # 测试
```

凭据放在 `.env`（参考 `.env.example`），目前只有 `GITHUB_TOKEN`，不配也能跑，只是调用额度低。

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

发布前要先提交并 `git push`，脚本会检查。手机在国内网络下连 GitHub 不稳定，所以 App 只访问推送用的同一个服务器：`../infofeed/hub.py` 从 `android/dist/` 提供 `/app/version` 和 `/app.apk`（经 `https://boxpkaka.cloud/catchup`）。App 打开时每 6 小时最多检查一次，版本号比当前新就显示“发现新版本”，点击后下载并调起系统安装；设置里也可以手动“检查更新”。首次安装从 `https://boxpkaka.cloud/catchup/app.apk` 下载。旧路径 `/feed` 暂时保留给 v1.2.0 及更早的 App，手机升级后可在 Caddyfile 里删除。

## 结构

```
catchup/
  models.py          Signal（一条原始内容）、Event（合并后的一件事）
  sources/           来源插件：base.py 定义基类和注册表，每种来源一个类
  store.py           SQLite 存储与顺序迁移（PRAGMA user_version）
  collector.py       依次运行来源，单个来源失败不影响其他来源
  pipeline/
    events.py        信号 → 事件
    ranking.py       事件打分，每项加分都附带可解释的理由
    feed.py          组装给前端的数据
  web/               只读预览服务和页面
config.json          来源、兴趣、排序参数
data/catchup.db      数据库（不入库）
```

数据流：`sources → store(signals) → events → ranking → web`。采集和呈现完全解耦，改排序不需要重新采集。

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
