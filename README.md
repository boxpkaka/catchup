# Catchup

替你持续盯住 AI 领域的信息源，把零散的信号合并成事件，只把对你真正重要的变化推给你。

产品思路见 `~/Catchup_for_AI_Product_Thesis.pdf`：Push-first、Event-first、Follow-up-first，而不是 Feed-first。

## 现状

| 阶段 | 状态 |
|---|---|
| 信号采集（GitHub / GitHub Trending / HF / arXiv / HN / 官方博客 / Google News / YouTube / Reddit / AINews 的 X 热帖 / DeepSeek 搜索 / 页面监控；新模型上架看 OpenRouter 和 HF 各家机构，科技快讯看 Techmeme，AI 新产品看 Product Hunt，AI 创投看 TechCrunch Venture / Crunchbase News / The Information / 极客公园；另有研究博客（Anthropic Research / Microsoft Research / Apple ML）、AI 媒体（The Decoder / MarkTechPost / MIT Technology Review / The Verge / Ars Technica）、中文媒体（量子位 / InfoQ）、专家博客（Simon Willison / Interconnects / Latent Space / Ahead of AI / One Useful Thing / SemiAnalysis / 宝玉）、厂商博客（ElevenLabs / Cursor）和播客（Lex Fridman / Practical AI / TWIML），后三类只推重要的；GitHub Trending 和 HF 模型热榜另记每天的名次） | ✅ 每 10 分钟检查，各来源按自己的采样间隔（10 分钟～6 小时）采集，失败的 20 分钟后重试 |
| 事件合并：规则聚类 + DeepSeek 合并同一件事，事件持久化有固定 id | ✅ |
| 排序：规则分（兴趣、来源、多源、热度）× 模型给的重要性；每个事件有 AI 简报（先读主来源原文，写概要和要点，不用点开链接也知道主要内容）和 AI 解读（新在哪、可能的影响、值得留意的局限，和只写事实的概要分开）；App 上的英文来源标题都翻成中文 | ✅ |
| 推送：能推的都推（模型判定为新闻、还没推过的事件，不限条数；排在后面但正热门的新闻也会判断；只出现在 GitHub Trending 等榜单或创投媒体上的要模型判为“重要”才推送、才进信息流，一般的留在专题里），不感兴趣的在首页右滑“不需要”，从反馈里学；23:00～8:00 只推重要性 5 的重大消息，其余早上合成一条早报 | ✅ 经 `../infofeed` 的 hub 送达 |
| 安卓 App：首页分“信息流”（按见报时间从新到旧、按天分组，每天先是分数接近当天第一的几件“最值得看”（最多 5 件），其余按时间排成一条线，话题写成文字不上色，最重要的标“要闻”，滑到底加载更早的）、“专题”（话题专题 AI 语音 / 大模型 / Agent / AI 创投 收这个话题的全部事件（包括没进信息流的一般动态）和榜单上的相关条目，榜单专题 GitHub Trending / HF 模型热榜 / Product Hunt 按名次列全部条目；只有专题卡片有自己的颜色和徽记）和“报告”三页，左滑下一页、右滑上一页；榜单的几个分类、报告的日报 / 周报 / 月报也是左右滑切换，相邻的一页跟着手指进来，标签胶囊跟着走，两头有橡皮筋；点开消息、专题卡片时卡片原地展开成整页，关闭时收回列表里那张卡片，其余二级页面从右边推进来、右滑返回（下面那页带视差）；报告往下滚到底接着出上一期；记忆左滑删除；底部面板从下面升起、下拉收起；松手都接着手指的速度，内容更新时淡入、其余平滑让位；详情页（概要 + 要点 + AI 解读）、右滑“不需要”、下拉刷新、设备自动绑定 | ✅ |
| 对话：事件下提问（先读原文、标注出处、流式输出），或直接问自己的事件库 | ✅ |
| 记忆：对话、反馈、漏报沉淀为六类记忆（正在做 / 长期关注 / 不想看 / 偏好来源 / 不想看的来源 / 写法），可查看编辑删除，可整体关闭 | ✅ |
| 反馈学习：“不需要”时选原因：模型列出几类可能不想看的内容由你选（选中的原样记下），或者这个来源 / 已经知道了 / 其他；选了的当场记下、撤回时一起删，“其他”和没选原因的先存档，攒几条一起找共同点；追踪、提问、看原文、评分记入行为日志，每小时按规律归纳（至少两条行为支撑） | ✅ |
| 记忆驱动：排序加权和简报的重要性判断参考记忆；记忆变化后重写前排简报（每轮先推新消息，推完再分批重写，不耽误推送） | ✅ |
| 早报推送（夜里攒下的合成一条）；持续追踪（主动搜后续、只推实质进展） | ✅ |
| 日报、周报、月报：每个自然日、周、月结束后写一份（要点、分领域，周报月报另有趋势和持续发展的事），周报月报由日报汇总，写好后推送一次 | ✅ |
| 替你留意的问题：对话里暂时没答案的问题，之后有报道回答了就写回原对话并推送 | ✅ |
| 分享到 Catchup：系统分享或复制链接，读原文、归入事件、写简报，不会再推回给你 | ✅ |
| 未追踪信息：热度高但和你无关、没放进首页的事，可以点“想看这类”纠正 | ✅ |
| X：不用付费 API，从 AINews 日报取每天按互动量排的热帖，帖子原文用官方 oEmbed 读 | ✅ |
| 故障提示：几个来源同时连续采不到内容（多半是网络或代理断了）、单个来源 6 小时没采到或采集停了，App 首页顶部就出一条小红条“服务疑似故障：…” | ✅ |
| 新来源：小红书 / 中文播客 | ⏳ |

## 使用

运行时只依赖 Python 3.10+ 标准库，在项目根目录直接执行：

```bash
python3 -m catchup sources            # 列出已配置的来源和可用类型
python3 -m catchup collect            # 采集到了采样间隔的来源，之后自动做事件归并、简报和推送（--no-update 只采集）
python3 -m catchup collect --only youtube reddit   # 只采指定来源，不看采样间隔
python3 -m catchup update --no-push   # 不采集，只重新归并事件、写简报（不推送）
python3 -m catchup serve              # 预览页 http://127.0.0.1:2591，以及 App 接口 /v1
python3 -m catchup devices            # 查看 App 设备；devices approve <id> 批准新设备
python3 -m unittest                   # 测试
```

凭据放在 `.env`（参考 `.env.example`）：`GITHUB_TOKEN`（可选，提高额度）、`DEEPSEEK_API_KEY`（简报、合并和 DeepSeek 来源，没有则只跑规则部分）、`FEED_URL` / `FEED_TOKEN`（推送到 hub）。

`catchup serve` 作为 systemd 用户服务 `catchup-web` 常驻（`systemctl --user restart catchup-web`）。App 经 Caddy 访问 `https://43.139.27.91:8443/v1/*`；第一台注册的设备自动批准，之后的新设备要用 `devices approve` 确认。

定时任务（Google、Hugging Face、YouTube、Reddit 在服务器上直连不通，所以采集走本机 mihomo 代理，国内站点由 mihomo 规则直连；`catchup-web` 的 systemd 单元里也设了同样的 `Environment=`）：

```
*/10 * * * * cd /home/boxp/workspace/catchup && http_proxy=http://127.0.0.1:7890 https_proxy=http://127.0.0.1:7890 no_proxy=localhost,127.0.0.1 /usr/bin/python3 -m catchup collect >> logs/collect.log 2>&1
```

预览页只监听本机，远程查看用 SSH 端口转发：`ssh -L 2591:127.0.0.1:2591 <server>`。

## 安卓 App

不用 Gradle，`android/build.sh` 直接调用 SDK 工具打包（JDK 17 在 `~/.local/share/jdk17`，SDK 在 `~/.local/share/android-sdk`）。签名用本机的 `android/release.keystore`（不入库），密码是 `.env` 里的 `APK_KEYSTORE_PASS`；换了密钥，已安装的 App 就无法覆盖升级。

```bash
./android/build.sh                  # 本地调试包 → android/build/infofeed.apk（版本 0.0.0-dev）
./android/release.sh 1.1.0          # 构建 v1.1.0：放到 android/dist/ 供自建服务器分发，并发布 GitHub Release 存档
```

App 名为 Catchup（包名仍是 `cloud.boxpkaka.infofeed`，保证能覆盖升级），打开即用，不需要令牌：hub 的 `/stream` 免认证，只有 `/publish` 需要 `FEED_TOKEN`。图标是 `res/mipmap-anydpi-v26/` 下的自适应图标（钴蓝底、白色粗体 c、缺口处一颗酸橙色的点），同一图形还用作主题图标和通知栏图标。

发布前要先提交并 `git push`，脚本会检查。手机在国内网络下连 GitHub 不稳定，所以 App 只访问推送用的同一个服务器：`../infofeed/hub.py` 从 `android/dist/` 提供 `/app/version` 和 `/app.apk`（App 经 `https://43.139.27.91:8443` 直连 IP；`boxpkaka.cloud` 未备案，443 上的普通 TLS 连接会被腾讯云 reset）。App 打开时每 6 小时最多检查一次，版本号比当前新就显示“发现新版本”，点击后下载并调起系统安装；设置里也可以手动“检查更新”。首次安装用手机浏览器打开 `https://43.139.27.91:8443/app.apk` 下载（浏览器会提示证书不受信任，选择继续访问即可）。8443 的证书由服务器上 Caddy 的内部 CA 签发，App 通过 `res/xml/network_security_config.xml` 只对该 IP 信任内置的 `res/raw/catchup_ca.crt`。

## 结构

```
catchup/
  models.py          Signal（一条原始内容）、Event（合并后的一件事）
  sources/           来源插件：base.py 定义基类和注册表，每种来源一个类
  store.py           SQLite 存储与顺序迁移（PRAGMA user_version）
  collector.py       依次运行来源，单个来源失败不影响其他来源；按采集记录判断是否疑似故障
  llm.py             DeepSeek 对话接口（deepseek-flash，关闭思考；JSON 输出和流式输出）
  personal.py        个人记忆：提取、反馈学习、漏报、构建带记忆的排序器和“读者是谁”
  chat.py            对话：读原文回答（标注 [1]）或问事件库（标注 [E12]），每轮后提取记忆和待留意的问题
  share.py           分享进来的链接：读原文、归入事件、写简报
  reader.py          读原文：一般网页走 DeepSeek fetch，Google News 跳转链接、Reddit 帖子、HF 模型页另行处理
  pipeline/
    events.py        信号 → 事件：规则聚类，并持久化为有固定 id 的事件
    ranking.py       事件打分，每项加分都附带可解释的理由
    brief.py         模型合并同一件事、读原文写简报（标题 / 概要 / 要点 / AI 解读 / 重要性）
    push.py          选择要推送的事件并经 hub 送达；早上合成早报；追踪进展推送
    follow.py        追踪：主动搜后续报道，判断是否有实质进展
    answers.py       替你留意的问题：在新报道里找答案，写回原对话
    translate.py     把 App 上会出现的英文原文（来源标题、没写简报的摘要）翻成中文并缓存
    reports.py       日报、周报、月报：时间段结束后写好，周报月报写好后推送
    update.py        采集之后的整条处理流程
    feed.py          预览页用的数据
  web/
    server.py        预览页 + App 接口路由
    app_api.py       App 接口：设备绑定、信息流（按见报时间翻页）、报告、详情、反馈、对话、记忆、追踪
config.json          来源、兴趣、排序参数
data/catchup.db      数据库（不入库）
```

数据流：`sources → store(signals) → events(持久化) → ranking(+记忆) → brief(LLM) → push / App`；App 里的对话、反馈、漏报 → 记忆 → 反过来影响 ranking 和 brief。采集和呈现解耦，改排序不需要重新采集。

## 配置

`config.json` 的 `sources` 里每个 key 是一个来源实例，`type` 决定用哪个插件，其余字段是该插件的参数。通用字段：

| 字段 | 含义 |
|---|---|
| `type` | 插件类型，`python3 -m catchup sources` 可查看全部 |
| `title` | 页面上的来源名 |
| `tier` | `official`（官方一级源）/ `primary`（一手技术源）/ `signal`（弱信号），影响排序 |
| `kind` | 内容类别，如 代码、模型、论文、社区、视频、新闻 |
| `interval` | 采样间隔（分钟，默认 60）。cron 每 10 分钟一轮，所以取 10 的倍数；距上次采集满间隔才采 |
| `enabled` | 设为 `false` 可临时停用 |
| `important_only` | 只推重要的：只来自这个来源的事件要模型判为“重要”（`push` 的 `board_min_importance`，默认 4）才推送、进信息流，一般的留在专题里。榜单来源默认打开；其他来源（如创投媒体 `venture`）打开后每件都会写简报、选主话题，不论排名 |

`interests` 是兴趣画像（名称、权重、关键词），每个兴趣也是一个话题专题（模型给每件事选的主话题从这里面挑），`ranking` 是排序参数（时间窗、半衰期、各等级权重等，默认值见 `pipeline/ranking.py` 的 `DEFAULTS`）。

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
榜单类来源（GitHub Trending、HF 模型热榜）设 `board = True`，`fetch()` 按名次返回：信号表按 URL 只记第一次上榜，
每个榜当前的名次和这一轮上榜的开始时间另记在 `board_items` 表（`Store.update_board` / `Store.board`）。
