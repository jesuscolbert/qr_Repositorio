#!/usr/bin/env bash
set -euo pipefail
# The connector is built from official source, not a third-party Android executable.
root=$(pwd)
source_dir="$root/.tools/cloudflared-source"
ndk="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$root/app/src/main/jniLibs/arm64-v8a" "$root/app/src/main/jniLibs/x86_64" "$root/app/src/main/assets"
cd "$source_dir"
export GOOS=android GOARCH=arm64 CGO_ENABLED=1
export CC="$ndk/aarch64-linux-android26-clang"
go build -mod=readonly -trimpath -buildmode=pie \
  -ldflags='-s -w -X main.Version=2026.10.6 -X github.com/cloudflare/cloudflared/cmd/cloudflared/updater.BuiltForPackageManager=SOBOCE' \
  -o "$root/app/src/main/jniLibs/arm64-v8a/libcloudflared.so" ./cmd/cloudflared
cp LICENSE "$root/app/src/main/assets/cloudflared-LICENSE.txt"
file "$root/app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
sha256sum "$root/app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
export GOARCH=amd64 CC="$ndk/x86_64-linux-android26-clang"
go build -mod=readonly -trimpath -buildmode=pie \
  -ldflags='-s -w -X main.Version=2026.10.6 -X github.com/cloudflare/cloudflared/cmd/cloudflared/updater.BuiltForPackageManager=SOBOCE' \
  -o "$root/app/src/main/jniLibs/x86_64/libcloudflared.so" ./cmd/cloudflared
