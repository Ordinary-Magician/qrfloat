#!/bin/bash
# 组装单文件电脑接收端：把 mqtt.js / qrcode 内联进 HTML
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../dist/pc-receiver/码传电脑接收端.html"
LIB="$DIR/libs"
mkdir -p "$LIB" "$(dirname "$OUT")"

[ -s "$LIB/mqtt.min.js" ]   || curl -sL --max-time 60 -o "$LIB/mqtt.min.js"   "https://cdn.jsdelivr.net/npm/mqtt@5.10.1/dist/mqtt.min.js"
[ -s "$LIB/qrcode.min.js" ] || curl -sL --max-time 60 -o "$LIB/qrcode.min.js" "https://cdn.bootcdn.net/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"

cat "$DIR/head.html" "$LIB/mqtt.min.js" "$DIR/mid.html" "$LIB/qrcode.min.js" "$DIR/mid.html" "$DIR/app.js" "$DIR/tail.html" > "$OUT"
echo "已生成: $OUT ($(wc -c <"$OUT") 字节)"
