#!/usr/bin/env bash
# 构建部署产物：后端 fat jar + 前端 dist，统一放到 dist-deploy/
#
# 用法（Linux / macOS / CI 直接跑）：
#   bash scripts/deploy/build.sh
# 本机 Windows Git Bash：
#   MVN=mvn.cmd bash scripts/deploy/build.sh
#
# 产物结构（deploy.sh 直接吃这一份）：
#   dist-deploy/emotion-server.jar
#   dist-deploy/web/index.html  + assets/
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/dist-deploy"
MVN="${MVN:-mvn}"

echo "==> [1/3] 构建后端"
( cd "$ROOT/emotion-server" && "$MVN" -q -DskipTests clean package )
JAR="$(ls "$ROOT"/emotion-server/target/emotion-server-*.jar | grep -v '\.original$' | head -n1)"
if [ -z "$JAR" ]; then echo "[ERROR] 没找到 fat jar" >&2; exit 1; fi

echo "==> [2/3] 构建前端"
( cd "$ROOT/emotion-web" && npm ci && npm run build )

echo "==> [3/3] 归集到 $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/web"
cp "$JAR" "$OUT/emotion-server.jar"
cp -r "$ROOT/emotion-web/dist/." "$OUT/web/"

echo "==> 完成"
echo "    jar: $(du -h "$OUT/emotion-server.jar" | cut -f1)"
echo "    web: $(find "$OUT/web" -type f | wc -l) 个文件"
