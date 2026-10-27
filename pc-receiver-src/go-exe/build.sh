#!/bin/bash
# 把电脑接收端封成单文件可执行程序（内联 HTML）。默认出 Windows exe，另出 Linux 版。
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../../dist/pc-receiver"
LDFLAGS="-s -w"
TRIM="-trimpath"

# 先把最新的 HTML 复制进来作为内联资源
cp "$OUT/码传电脑接收端.html" "$DIR/index.html"

cd "$DIR"

echo "构建 Windows (exe)..."
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build $TRIM -ldflags "$LDFLAGS" -o "$OUT/码传电脑接收端.exe" .

echo "构建 Linux (x86_64)..."
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build $TRIM -ldflags "$LDFLAGS" -o "$OUT/码传电脑接收端-linux-x64" .

echo "完成："
ls -lh "$OUT/码传电脑接收端.exe" "$OUT/码传电脑接收端-linux-x64"
