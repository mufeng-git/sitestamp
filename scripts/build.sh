#!/usr/bin/env bash
# SiteStamp 本地构建/验证（不依赖 CI）
#
# 用法：
#   scripts/build.sh              # 编译 debug 包
#   scripts/build.sh test         # 只跑单测
#   scripts/build.sh render       # 渲染水印样张到 build/reports/watermark/
#   scripts/build.sh clean        # 清理
set -euo pipefail

export JAVA_HOME="D:/devtools/jdk-17.0.21+9"
export ANDROID_HOME="D:/devtools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")/.."

# local.properties 缺失时补一份（路径含中文，必须用正斜杠）
if [ ! -f local.properties ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi

case "${1:-assemble}" in
  test)
    ./gradlew :app:testDebugUnitTest --no-daemon
    ;;
  render)
    ./gradlew :app:testDebugUnitTest --no-daemon \
      --tests "com.cscec.sitestamp.WatermarkRenderTest"
    echo "样张输出： app/build/reports/watermark/"
    ;;
  clean)
    ./gradlew clean --no-daemon
    ;;
  *)
    ./gradlew :app:assembleDebug --no-daemon
    echo "APK： app/build/outputs/apk/debug/app-debug.apk"
    ;;
esac
