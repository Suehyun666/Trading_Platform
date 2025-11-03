#!/bin/bash
# Optimized order startup script with GC tuning

# GC 옵션 설정
JAVA_OPTS="-Xmx4G \
  -Xms4G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -XX:G1HeapRegionSize=16M \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UnlockExperimentalVMOptions \
  -XX:G1NewSizePercent=30 \
  -XX:G1MaxNewSizePercent=40 \
  -XX:+AlwaysPreTouch"

# GC 로그 (선택사항)
# JAVA_OPTS="$JAVA_OPTS -Xlog:gc*:file=gc.log:time,uptime,level,tags"

echo "Starting server with optimized GC settings..."
echo "Heap: 4GB, GC: G1, Max GC Pause: 50ms"

cd "$(dirname "$0")/.."
./gradlew run --args="--gc-optimized" -Dorg.gradle.jvmargs="$JAVA_OPTS"
