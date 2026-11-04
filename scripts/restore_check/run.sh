#!/usr/bin/env bash
# 「已丢失的注释会回来吗」端到端实测（1.7.2 覆盖版后新增）：
#   阶段 A：用旧版（首发）NF JAR 走真实 saveConfig 通路制造受害文件（注释 231→0，v29）
#   阶段 B：用线上新版 NF JAR 走 rebuildIfNeeded(v30) 升级重建，断言注释回来 + 值保留
# 用法：./run.sh <旧版NF.jar> <新版NF.jar> [工作目录]
set -euo pipefail
OLD="${1:?旧版 JAR 路径}"
NEW="${2:?新版 JAR 路径}"
WORK="${3:-$(mktemp -d)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SL="$(cd "$HERE/.." && pwd)"           # scripts/ 目录（含 slf4j-api/nop）
JAVA="${JAVA_HOME:-/home/z/my-project/tooling/jdk21}/bin"
SLF4J="$SL/slf4j-api-2.0.13.jar:$SL/slf4j-nop-2.0.13.jar"

mkdir -p "$WORK/classes-a" "$WORK/classes-b" "$WORK/victim-dir"
unzip -p "$OLD" config.yml > "$WORK/v29-template.yml"

"$JAVA/javac" -cp "$OLD:$SL/slf4j-api-2.0.13.jar" -d "$WORK/classes-a" "$HERE/AffectedUserSim.java"
"$JAVA/javac" -cp "$NEW:$SL/slf4j-api-2.0.13.jar" -d "$WORK/classes-b" "$HERE/UpgradeRestoreCheck.java"

echo "========== 阶段 A：旧版 JAR 复现受害文件 =========="
"$JAVA/java" -cp "$WORK/classes-a:$OLD:$SLF4J" AffectedUserSim "$WORK/v29-template.yml" "$WORK/victim-dir"
echo
echo "========== 阶段 B：换新版 JAR 首次启动（v29→v30 重建） =========="
"$JAVA/java" -cp "$WORK/classes-b:$NEW:$SLF4J" UpgradeRestoreCheck "$WORK/victim-dir/config.yml" "$WORK/upgrade-dir"
