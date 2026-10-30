# CLAUDE.md

项目说明、结构、命令和扩展方式见 `README.md`。这里只记录容易踩坑的约束。

- **零运行时依赖**：只用标准库（系统 Python 3.10，没有 `tomllib`，所以配置用 JSON）。测试用 `unittest`，不要引入 pytest/requests。
- **URL 是信号主键**：`Store.add` 用 `insert or ignore` 的 `rowcount` 同时完成写入和判新。改 URL 规范化规则会让旧内容被当成新内容。
- **数据库迁移**：只在 `store.py` 的 `MIGRATIONS` 末尾追加，不要修改已有条目。
- **时间统一存 UTC ISO 字符串**（`util.to_iso`），`Store.recent` 依赖字符串比较。
- **网络**：服务器的 mihomo 没开 TUN，直连时 Google、Hugging Face、YouTube、Reddit 都不通（DNS 被污染），所以 cron 的 `collect` 和 `catchup-web` 都显式设置 `http_proxy`/`https_proxy=http://127.0.0.1:7890`，`no_proxy=localhost,127.0.0.1` 放行本机的推送 hub。手动测试用同一组变量，不要依赖当前 shell：`clauded` 会话设了 `no_proxy=*`，等于全部直连。
- **Reddit 公开 RSS 限流严**：版块间隔默认 60 秒，一次完整采集约 4 分钟。
- **采集频率**：cron 每 10 分钟跑一次 `collect`，各来源按自己的 `interval` 判断是否到期（`collector.due`），`--only` 不看间隔；上一轮没跑完时靠 `data/collect.lock` 跳过。`update` 每轮都跑，新加按时间触发或按次计费的逻辑要自己限频（参考 `follow.SEARCH_EVERY`、只发一次的早报 `push.morning`）。
- `.env` 含 `GITHUB_TOKEN`，不要读出内容或写进日志。
- 推送服务仍在 `../infofeed`（`feed.py` / `hub.py`），本项目采集、预览，并维护安卓 App（`android/`，从 `../infofeed/android` 迁入）。
- **App 签名**：`android/release.keystore` 和 `.env` 的 `APK_KEYSTORE_PASS` 不入库、不要读出。包名 `cloud.boxpkaka.infofeed` 和签名都不能改，否则已装的 App 无法覆盖升级。
- **App 版本**：`versionCode` 按构建时间自动递增；应用内更新比较的是 `versionName` 与服务器 `/app/version`（来自 `android/dist/version.json`）。App 不直连 GitHub（国内网络不稳定），GitHub Release 只做存档。发版用 `android/release.sh <版本号>`。
- **App 埋点**：`Trace.log` 记到手机本地 `trace.log`，连上后上传到 hub `POST /log`，落在 `../infofeed/applogs/<机型>-<随机id>.log`。排查手机端问题先看这里。
- **App 入口是 `https://43.139.27.91:8443`**：域名未备案，443 被腾讯云拦截。证书来自 Caddy 内部 CA（`caddy_data` 卷里的 root，有效期到 2036），App 内置该根证书做固定信任。重建 Caddy 数据卷会换 CA，已装的 App 将连不上，需要发新包并让用户用浏览器重装。
- **记忆闭环**：记忆存在 `memories` 表，任何增删改都要调用 `personal.bump()`（简报靠 `memory_rev` 判断要不要按新记忆重写）。测试里涉及详情接口、对话和带原因的反馈时要 mock `catchup.chat.prefetch` 和 `catchup.personal.rebrief_async`，否则后台线程会访问真实网络和项目数据库；读原文（对话、分享、`read_wait` 大于 0 的写简报）要 mock `catchup.reader.tool`，问题留意要 mock `catchup.pipeline.answers.tool`，对话会联网搜索，要 mock `catchup.chat.tool`。
- **反馈学习**：用户在事件上的动作都追加到 `actions` 表（`feedback`、`follows`、`ratings` 只存当前状态）。每小时的归纳只能改 origin 为“你的反馈”的记忆，新增必须有两条以上行为支撑（代码里校验，不靠提示词）；“不需要 / 想看这类”带原因时由 `personal.from_reason` 当场记下（origin 见 `REASON_ORIGINS`，记 event_id；不想看这类记的是用户从 `personal.avoid_options` 的候选里选的那一类，不让模型猜；“其他”不当场记，交给每小时的归纳），撤回或改反馈时 `personal.retract` 按 event_id 删除。
- **来源显示名**统一用 `sources.source_of`：Google News 显示真正的媒体（author），分享进来的显示“你分享的”（source 为 `sources.SHARED`，这类事件不推送）。
