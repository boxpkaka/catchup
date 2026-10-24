# CLAUDE.md

项目说明、结构、命令和扩展方式见 `README.md`。这里只记录容易踩坑的约束。

- **零运行时依赖**：只用标准库（系统 Python 3.10，没有 `tomllib`，所以配置用 JSON）。测试用 `unittest`，不要引入 pytest/requests。
- **URL 是信号主键**：`Store.add` 用 `insert or ignore` 的 `rowcount` 同时完成写入和判新。改 URL 规范化规则会让旧内容被当成新内容。
- **数据库迁移**：只在 `store.py` 的 `MIGRATIONS` 末尾追加，不要修改已有条目。
- **时间统一存 UTC ISO 字符串**（`util.to_iso`），`Store.recent` 依赖字符串比较。
- **网络**：交互 shell 的 `.bashrc` 会加载代理，cron 不会。各来源都已确认可以直连；手动测试时用 `env -u http_proxy -u https_proxy -u all_proxy ...` 模拟 cron 环境。Reddit 走代理反而更容易被 429。
- **Reddit 公开 RSS 限流严**：版块间隔默认 60 秒，一次完整采集约 4 分钟。
- `.env` 含 `GITHUB_TOKEN`，不要读出内容或写进日志。
- 推送服务仍在 `../infofeed`（`feed.py` / `hub.py`），本项目采集、预览，并维护安卓 App（`android/`，从 `../infofeed/android` 迁入）。
- **App 签名**：`android/release.keystore` 和 `.env` 的 `APK_KEYSTORE_PASS` 不入库、不要读出。包名 `cloud.boxpkaka.infofeed` 和签名都不能改，否则已装的 App 无法覆盖升级。
- **App 版本**：`versionCode` 按构建时间自动递增；应用内更新比较的是 `versionName` 与服务器 `/app/version`（来自 `android/dist/version.json`）。App 不直连 GitHub（国内网络不稳定），GitHub Release 只做存档。发版用 `android/release.sh <版本号>`。
