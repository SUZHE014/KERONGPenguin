Geyser/Floodgate 基岩版兼容功能测试（1.5.7.2）

独立 JVM 走真实 QqBindManager 代码路径，验证 findPlayerUuidByName：
基岩玩家前缀名 / Floodgate UUID / 大小写 / 同名双记录 /
旧版纯名字键回退 / 玩家名含括号的严格 UUID 格式防误截取。

运行方式（参考 scripts/render-test/，classpath 需含）：
  common-Bot/build/libs/common-Bot.jar
  common-Bot/libs/deps.jar
  paper-api-1.18.2（gradle 缓存）
  kotlin-stdlib-2.2.0 及其依赖（gradle 缓存）

  javac -cp <cp> -d out GeyserCheck.java TestPlugin.java
  java  --add-opens java.base/java.lang=ALL-UNNAMED \
        -cp out:<cp> GeyserCheck   # 15 项全过退出码 0
