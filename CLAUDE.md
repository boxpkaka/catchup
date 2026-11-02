# CLAUDE.md

项目概览和命令见 `README.md`，细节分在 `docs/`：`features.md`（功能说明）、`architecture.md`（工作原理和项目结构）、`configuration.md`、`deployment.md`、`android.md`、`design.md`（文案与设计）、`development.md`（开发约定和新增来源）、`product.md`（产品思路）。README 只放概览，功能、界面或文案准则有变化时更新 `docs/` 里对应的文档。这里只记录容易踩坑的约束。

- **产品调性（准则）**：Catchup 是用户的私人助手，像一位和蔼、体贴、靠得住的管家或秘书——替他留意、帮他整理、把要紧的先告诉他。所有给用户看的文字都按这个调性写：App 文案、推送，以及会直接展示给用户的模型输出（提示词里引用 `catchup/tone.py` 的 `TONE`）。温和、平实、有分寸；有看法说成看法，建议说成可选的；不命令、不说教、不催促（不用“应该”“必须”“务必”“盯紧”“警惕”“赶紧”这类话），不制造焦虑，不谄媚、不客套。

- **零运行时依赖**：只用标准库（系统 Python 3.10，没有 `tomllib`，所以配置用 JSON）。测试用 `unittest`，不要引入 pytest/requests。唯一的系统工具是封面缩图用的 `cwebp`（`apt install webp`），没装时只是没有封面。
- **URL 是信号主键**：`Store.add` 用 `insert or ignore` 的 `rowcount` 同时完成写入和判新。改 URL 规范化规则会让旧内容被当成新内容。
- **数据库迁移**：只在 `store.py` 的 `MIGRATIONS` 末尾追加，不要修改已有条目。
- **时间统一存 UTC ISO 字符串**（`util.to_iso`），`Store.recent` 依赖字符串比较。
- **网络**：服务器的 mihomo 没开 TUN，直连时 Google、Hugging Face、YouTube、Reddit 都不通（DNS 被污染），所以 cron 的 `collect` 和 `catchup-web` 都显式设置 `http_proxy`/`https_proxy=http://127.0.0.1:7890`，`no_proxy=localhost,127.0.0.1` 放行本机的推送 hub。手动测试用同一组变量，不要依赖当前 shell：`clauded` 会话设了 `no_proxy=*`，等于全部直连。
- **Reddit 公开 RSS 限流严**：版块间隔默认 60 秒，一次完整采集约 4 分钟。
- **采集频率**：cron 每 10 分钟跑一次 `collect`，各来源按自己的 `interval` 判断是否到期（`collector.due`），上次失败（报错或 0 条）的隔 `collector.RETRY`（20 分钟）就重试，`--only` 不看间隔；上一轮没跑完时靠 `data/collect.lock` 跳过。`update` 每轮都跑，新加按时间触发或按次计费的逻辑要自己限频（参考 `follow.SEARCH_EVERY`、只发一次的早报 `push.morning`）。
- `.env` 含 `GITHUB_TOKEN`，不要读出内容或写进日志。
- 推送服务仍在 `../infofeed`（`feed.py` / `hub.py`），本项目采集、预览，并维护安卓 App（`android/`，从 `../infofeed/android` 迁入）。
- **App 签名**：`android/release.keystore` 和 `.env` 的 `APK_KEYSTORE_PASS` 不入库、不要读出。包名 `cloud.boxpkaka.infofeed` 和签名都不能改，否则已装的 App 无法覆盖升级。
- **App 版本**：`versionCode` 按构建时间自动递增；应用内更新比较的是 `versionName` 与服务器 `/app/version`（来自 `android/dist/version.json`）。App 不直连 GitHub（国内网络不稳定），GitHub Release 只做存档。发版用 `android/release.sh <版本号>`。
- **App 埋点**：`Trace.log` 记到手机本地 `trace.log`，连上后上传到 hub `POST /log`，落在 `../infofeed/applogs/<机型>-<随机id>.log`。排查手机端问题先看这里。
- **App 入口是 `https://43.139.27.91:8443`**：域名未备案，443 被腾讯云拦截。证书来自 Caddy 内部 CA（`caddy_data` 卷里的 root，有效期到 2036），App 内置该根证书做固定信任。重建 Caddy 数据卷会换 CA，已装的 App 将连不上，需要发新包并让用户用浏览器重装。
- **记忆闭环**：记忆存在 `memories` 表，任何增删改都要调用 `personal.bump()`（简报靠 `memory_rev` 判断要不要按新记忆重写）。测试里涉及详情接口、对话和带原因的反馈时要 mock `catchup.chat.prefetch` 和 `catchup.personal.rebrief_async`，否则后台线程会访问真实网络和项目数据库；读原文（对话、分享、`read_wait` 大于 0 的写简报）要 mock `catchup.reader.tool`，问题留意要 mock `catchup.pipeline.answers.tool`，对话会联网搜索，要 mock `catchup.chat.tool`；写报告会联网找评论，要 mock `catchup.pipeline.voices.gather`；跑带推送的 `update` 要 mock `catchup.images.cover`（推送前会去原网站取封面，测试配置的 root 是仓库目录，还会写进 `data/images`）。
- **反馈学习**：用户在事件上的动作都追加到 `actions` 表（`feedback`、`follows`、`ratings` 只存当前状态）。归纳（最多每 `personal.LEARN_EVERY` 一次）只能改 origin 为“你的反馈”的记忆，新增必须有两件以上事件、合计 `LEARN_MIN` 分量的主动行为支撑（代码里校验，不靠提示词）；App 上报的曝光和阅读（`/v1/track`）、看原文属于 `personal.PASSIVE`，只由 `personal.reinforce` 加强或减弱已有记忆的强度，不参与归纳；每天 `personal.consolidate` 合并重复记忆；“不需要 / 想看这类”带原因时由 `personal.from_reason` 当场记下（origin 见 `REASON_ORIGINS`，记 event_id；不想看这类记的是用户从 `personal.avoid_options` 的候选里选的那一类，不让模型猜；“其他”不当场记，交给之后的归纳），撤回或改反馈时 `personal.retract` 按 event_id 删除。
- **来源显示名**统一用 `sources.source_of`：Google News 显示真正的媒体（author），分享进来的显示“你分享的”（source 为 `sources.SHARED`，这类事件不推送）。
