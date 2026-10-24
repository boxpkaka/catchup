#!/usr/bin/env bash
# 本机构建签名 APK 并发布：./release.sh 1.1.0
# 1. 放到 dist/，由 ../infofeed/hub.py 以 /app/version 和 /app.apk 提供给 App 内更新（手机不直连 GitHub）
# 2. 上传到 GitHub Release 存档
# 签名密钥只留在本机。
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${1:?用法：release.sh <版本号，如 1.1.0>}"
[[ $VERSION =~ ^[0-9]+(\.[0-9]+)*$ ]] || { echo "版本号只能由数字和点组成：$VERSION" >&2; exit 1; }
git diff --quiet HEAD -- . || { echo "android/ 有未提交的修改，先提交再发布" >&2; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{u}')" ] || { echo "当前提交与远端不一致，先 git push" >&2; exit 1; }

./build.sh "$VERSION"
cp build/infofeed.apk "build/infofeed-$VERSION.apk"
mkdir -p dist
cp build/infofeed.apk dist/infofeed.apk.tmp && mv dist/infofeed.apk.tmp dist/infofeed.apk
printf '{"version": "%s"}\n' "$VERSION" > dist/version.json
gh release create "v$VERSION" "build/infofeed-$VERSION.apk" \
  --target "$(git rev-parse HEAD)" --title "v$VERSION" --generate-notes
