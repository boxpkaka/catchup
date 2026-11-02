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

## 网络与证书

- App 经 `https://43.139.27.91:8443` 直连 IP：`boxpkaka.cloud` 未备案，443 上的普通 TLS 连接会被腾讯云 reset。
- 8443 的证书由服务器上 Caddy 的内部 CA 签发，App 通过 `res/xml/network_security_config.xml` 只对该 IP 信任内置的 `res/raw/catchup_ca.crt`。

图标、颜色和动效见 [文案与设计](design.md)。
