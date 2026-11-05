# 安卓 App

[← 返回文档目录](../README.md#-文档)

原生 Java 编写，只依赖 Android SDK，支持 Android 10（API 29）及以上。App 名为 Catchup，包名仍是 `cloud.boxpkaka.infofeed`，保证能覆盖升级。

## 安装与更新

- 首次安装：用手机浏览器打开 `https://43.139.27.91:8443/app.apk` 下载（浏览器会提示证书不受信任，选择继续访问即可）。
- 打开即用，不需要令牌。
- App 每次打开和下拉刷新时都检查一次更新，版本号比当前新就显示“新版本 … 已就绪”，点击后下载并调起系统安装；设置里也可以手动“检查更新”。
- 手机在国内网络下连 GitHub 不稳定，所以 App 只访问推送用的同一个服务器：推送 hub 从 `android/dist/` 提供 `/app/version` 和 `/app.apk`。[GitHub Releases](https://github.com/boxpkaka/catchup/releases) 只做存档。

## 构建与发布

不用 Gradle，`android/build.sh` 直接调用 SDK 工具打包，需要 JDK 17 和 Android SDK（build-tools 35.0.0、platform android-35），SDK 路径用 `ANDROID_HOME` 指定，默认值见 `build.sh`。

```bash
./android/build.sh                  # 本地调试包 → android/build/infofeed.apk（版本 0.0.0-dev）
./android/release.sh 1.1.0          # 构建 v1.1.0：放到 android/dist/ 供自建服务器分发，并发布 GitHub Release 存档
```

发布前要先提交并 `git push`，脚本会检查。

> [!WARNING]
> 签名用本机的 `android/release.keystore`（不入库），密码是 `.env` 里的 `APK_KEYSTORE_PASS`。换了密钥，已安装的 App 就无法覆盖升级。

## 代码分层与界面风格

App 正在改成可以切换界面风格：材质、背景、版式和动效都由风格提供，同样的功能换一种样子。代码分三层：

| 层 | 管什么 | 例子 |
|:--|:--|:--|
| 页面 | 一页上有哪些部分、数据从哪来 | `MainActivity` |
| 行为 | 怎么排、点了去哪、手势怎么判定、请求怎么发，不随风格变 | `FeedList`、`Undo`、`Feedback` |
| 样子 | 长什么样，由当前风格提供 | `FeedRows`（雾面金属）、`PlainRows`（朴素） |

一种风格的入口是 `Style`：

- **材质和配色**：`Theme.of(context)`，页面里的底、块、按钮都从它取，不直接 `new Theme`。
- **背景**：`Style.mist(...)`，不直接 `new Flow`。
- **版式**：按界面给出“样子”的类，现在接上的是首页的行（`Style.feedRows`）。
- **动效**：`Motion.now()` 给出按用途的弹簧，以及点开卡片时是原地展开还是从右边推进来，不在别处写弹簧参数。

新写或改动界面时按这个分：行为类里不写死材质、背景和动效，要样子时向 `Style` 要。

现在有两种风格：雾面金属（默认）和朴素（验证接口用，只能在“诊断信息”里切换）。还没接上的：详情页（`EventActivity`，背景动画也要能换）；对话、记忆、模型、专题页只换了材质和动效，背景和版式还是雾面金属的。

## 网络与证书

- App 经 `https://43.139.27.91:8443` 直连 IP：`boxpkaka.cloud` 未备案，443 上的普通 TLS 连接会被腾讯云 reset。
- 8443 的证书由服务器上 Caddy 的内部 CA 签发，App 通过 `res/xml/network_security_config.xml` 只对该 IP 信任内置的 `res/raw/catchup_ca.crt`。

图标、颜色和动效见 [文案与设计](design.md)。
