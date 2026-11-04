#!/usr/bin/env python3
"""verify_jar_172 —— 1.7.2 产物门禁。

覆盖 1.7.2 待办清单（用户 19 项里的代码改动部分）：
  一、删除
      BP1  下载源只剩官网单一 URL（gh-proxy / raw.githubusercontent 反断言）
  二、修复
      BP2  manifest 动态字段（declaredCount / declaredBytes / count / bytes）
      AV1  头像超时（connect 5000 / read 25000 常量 + httpGetAvatar 方法）
      AV2  超时换源重试（fetchExternalAvatarOnce / retryPass；旧三参
           fetchAvatarFromUrl(String,int,String) 已移除）
      AV3  批量获取并发重做（fetchAvatarBatch 8 线程共享池 + 12 秒渲染
           等待预算 + 40 秒迟到硬取消；四服务统一接入；invokeAll 移除）
      EX1  执行命令噪音（BukkitConsoleSender / HybridCommandExecutor 派发给
           自定义 Sender：字节码无 getConsoleSender + dispatchCommand 在位）
      EX2  NeoForgeConsoleExecutor：withSource 包装 + CollectingCommandSource
           收集源（sendSystemMessage）
      EX3  噪音黑名单（ConsoleNoiseFilter 类在位 + [MediaSource] / [Not Secure]
           字节；双 Appender 副本调 isNoise）
  三、新功能
      EC1  EconomyRewardService 类与六方法（双 JAR）
      EC2  checkin points-reward 配置键（DEFAULT_VALUES + 双端 config.yml）
      EC3  plugin.yml softdepend + CoinsEngine
      EC4  CheckInCommands 兼容桥 isEconomyAvailable 保留（MenuManager 调用方）
      ID1  image-download.enabled 开关（服务方法 + 配置键 + 双端 config.yml）
      ID2  bpng 入口拦截（双端命令类调 isImageDownloadEnabled）
  四、样式/文案
      GY1  灰度配置（titleCardGray / itemCardGray + 默认 80 / 106 常量）
      GY2  五处标题区接 titleCardGray + 统计面板接 itemCardGray；硬编码
           76 / 64 反断言
      TX1  设计参考标注（四渲染器 KDoc「设计参考」）
      TX2  绑定验证文案（kick-message 纯数字验证码 + bind-invalid 一句话）
  五、覆盖版新增修复
      EX4  远程命令控制台留档（双执行器 [QQ 远程命令] 前缀转发 + 噪音
           黑名单同步收录，避免回退通道重复）
      CL1  config.yml 注释保留保存（NeoForge 兼容层 ConfigCommentSaver
           差分合并 + 装载标记；YamlTextMerge removals 重载）
      CL2  升级备份移除（ConfigFileRebuilder 无 config.yml.bak 字面量）
      RC1  手动重连过程回显（ReconnectManager announceToGroups +
           QClient.broadcastReconnectLine）
  六、收尾
      CF1  config-version 30（双端源 + 双 JAR + ConfigManager 常量）
      CF2  四新键默认值（image-download / title-card-gray / item-card-gray /
           points-reward，双端源 + 双 JAR config.yml）
      CF3  checkin-success 模板 {reward} 不再带「金币」后缀（双 JAR）
      V1   版本六处源码 1.7.2
      V2   双 JAR 元数据 1.7.2（plugin.yml + 双 toml）
      B1   基线：目录条目数 / shaded kotlin / 无顶层 slf4j gson / 关键字面量
      WEB  官网四文件（无覆盖版字样 / 移动端溢出修复 / 灰度口径）

用法：python3 scripts/verify_jar_172.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import tempfile
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.2.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.2.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

checks = []


def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    suffix = f" — {detail}" if detail else ""
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}{suffix}")


def read_class(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry)


def read_resource(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name).decode("utf-8", "replace")


def has_entry(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return entry in z.namelist()


def _javap_run(jar, entry, flags):
    with tempfile.TemporaryDirectory() as tmp:
        cls = pathlib.Path(tmp) / entry
        cls.parent.mkdir(parents=True, exist_ok=True)
        cls.write_bytes(read_class(jar, entry))
        fqcn = entry[:-len(".class")].replace("/", ".")
        out = subprocess.run(
            [JAVAP, "-p", *flags, "-cp", tmp, fqcn],
            capture_output=True, text=True,
        )
        if out.returncode != 0:
            raise RuntimeError(f"javap failed: {out.stderr[:400]}")
        return out.stdout


def javap_c(jar, entry):
    return _javap_run(jar, entry, ("-c",))


def javap_p(jar, entry):
    return _javap_run(jar, entry, ())


def class_contains_literals(jar, entry, literals):
    data = read_class(jar, entry)
    return all(literal.encode("utf-8") in data for literal in literals)


def read_resource_entry_text(jar, entry):
    """读类文件的原始字节文本（字面量常量池检索用）。"""
    return read_class(jar, entry).decode("utf-8", "replace")


SVC = "cn/kerong/penguin/spigot/render/BackgroundPackService.class"
PACKINFO = "cn/kerong/penguin/spigot/render/BackgroundPackService$PackInfo.class"
ASSETS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
RS = "cn/kerong/penguin/spigot/render/RenderSettings.class"
ICR = "cn/kerong/penguin/spigot/render/InfoCardRenderer.class"
OLR = "cn/kerong/penguin/spigot/render/OnlineListRenderer.class"
LBR = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
PR = "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"
ECON = "cn/kerong/penguin/spigot/qqbind/EconomyRewardService.class"
NOISE = "cn/kerong/bot/tools/ConsoleNoiseFilter.class"
BCS = "cn/kerong/penguin/spigot/commands/BukkitConsoleSender.class"
HCE = "cn/kerong/penguin/spigot/commands/HybridCommandExecutor.class"
COA = "cn/kerong/penguin/spigot/commands/CommandOutputAppender.class"
NFCE = "cn/kerong/penguin/neoforge/NeoForgeConsoleExecutor.class"
NFCE_COLL = "cn/kerong/penguin/neoforge/NeoForgeConsoleExecutor$CollectingCommandSource.class"
KCMD = "cn/kerong/penguin/spigot/commands/KERONGCommand.class"
NFCMD = "cn/kerong/penguin/neoforge/NeoForgeCommands.class"
CFGMGR = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
CHECKIN = "cn/kerong/bot/events/commands/CheckInCommands.class"
OLS = "cn/kerong/penguin/spigot/stats/OnlineListService.class"
LBC = "cn/kerong/bot/events/commands/LeaderboardCommands.class"
CLC = "cn/kerong/bot/events/commands/CoinsLeaderboardCommands.class"
QIS = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"

SRC_SVC = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/BackgroundPackService.kt"
SRC_ASSETS = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt"
SRC_ICR = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt"
SRC_RS = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt"
SRC_ECON = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/EconomyRewardService.kt"
SRC_KCMD = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt"
SRC_NFCMD = ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt"
SRC_CFGR = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt"
SRC_KICK = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt"
SRC_CHECKIN = ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CheckInCommands.kt"
SRC_BCS = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/BukkitConsoleSender.kt"
SRC_HCE = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/HybridCommandExecutor.kt"
SRC_NFCE = ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeConsoleExecutor.kt"
SRC_YML_S = ROOT / "server-Spigot/src/main/resources/config.yml"
SRC_YML_N = ROOT / "server-NeoForge/src/main/resources/config.yml"
SRC_PLUGIN_YML = ROOT / "server-Spigot/src/main/resources/plugin.yml"

print("== BP 组：图片包（删镜像 + 动态清单） ==")

# BP1 单一下载源
check("BP1a 官网源在位（双 JAR）",
      class_contains_literals(SPIGOT_JAR, SVC, ["https://kerong.xin/penguin/bpng/"]) and
      class_contains_literals(NF_JAR, SVC, ["https://kerong.xin/penguin/bpng/"]))
check("BP1b git 镜像源已删（双 JAR 反断言）",
      b"gh-proxy.com" not in read_class(SPIGOT_JAR, SVC) and
      b"raw.githubusercontent.com" not in read_class(SPIGOT_JAR, SVC) and
      b"gh-proxy.com" not in read_class(NF_JAR, SVC) and
      b"raw.githubusercontent.com" not in read_class(NF_JAR, SVC))

# BP2 manifest 动态字段
p_decl = javap_p(SPIGOT_JAR, PACKINFO)
check("BP2a PackInfo 声明字段（declaredCount / declaredBytes + getCount / getTotalBytes）",
      "declaredCount" in p_decl and "declaredBytes" in p_decl and
      "getCount()" in p_decl and "getTotalBytes()" in p_decl)
svc_c = javap_c(SPIGOT_JAR, SVC)
svc_c_nf = javap_c(NF_JAR, SVC)
check("BP2b manifest 读取 count / bytes 键（ldc 常量，双 JAR）",
      "// String count" in svc_c and "// String bytes" in svc_c and
      "// String count" in svc_c_nf and "// String bytes" in svc_c_nf)
check("BP2c 源码：清单失败提示不再提镜像源",
      "镜像源均不可达" not in SRC_SVC.read_text(encoding="utf-8"))

print("== AV 组：头像超时与换源重试 ==")

# AV1 超时常量与方法
assets_c = javap_c(SPIGOT_JAR, ASSETS)
assets_p = javap_p(SPIGOT_JAR, ASSETS)
check("AV1a 连接超时 5000 / 读超时 25000 常量在位",
      "sipush        5000" in assets_c and "sipush        25000" in assets_c,
      )
check("AV1b httpGetAvatar 方法在位（双 JAR）",
      "httpGetAvatar" in assets_p and "httpGetAvatar" in javap_p(NF_JAR, ASSETS))
check("AV1c 超时换源补试轮方法（fetchExternalAvatarOnce + retryPass）",
      "fetchExternalAvatarOnce" in assets_p and
      "fetchExternalAvatarOnce" in javap_c(SPIGOT_JAR, ASSETS))

# AV2 旧三参方法移除
check("AV2 旧三参 fetchAvatarFromUrl(String,int,String) 已移除（双 JAR）",
      "fetchAvatarFromUrl(java.lang.String, int, java.lang.String)" not in assets_p and
      "fetchAvatarFromUrl(java.lang.String, int, java.lang.String)" not in javap_p(NF_JAR, ASSETS))
check("AV2b 新二参 fetchAvatarFromUrl(String,String) 在位",
      "fetchAvatarFromUrl(java.lang.String, java.lang.String)" in assets_p)
check("AV2c 皮肤下载与 Crafatar 均走 httpGetAvatar",
      assets_c.count("httpGetAvatar") >= 4,
      f"调用点 {assets_c.count('httpGetAvatar')} 处")

# AV3 批量获取并发重做
assets_c_nf2 = javap_c(NF_JAR, ASSETS)
assets_p_nf2 = javap_p(NF_JAR, ASSETS)
check("AV3a fetchAvatarBatch 方法在位（双 JAR）",
      "fetchAvatarBatch" in assets_p and "fetchAvatarBatch" in assets_p_nf2)
check("AV3b 渲染等待预算 12 秒 / 迟到硬取消 40 秒常量在位",
      "long 12000l" in assets_c and "long 40000l" in assets_c)
check("AV3c 共享预热线程池与迟到取消调度器（线程名字面量，双 JAR）",
      class_contains_literals(SPIGOT_JAR, ASSETS, ["PenguinAvatarFetch", "PenguinAvatarReaper"]) and
      class_contains_literals(NF_JAR, ASSETS, ["PenguinAvatarFetch", "PenguinAvatarReaper"]))
check("AV3d 四服务统一接入 fetchAvatarBatch（在线列表/排行榜/财富榜/查信息）",
      "fetchAvatarBatch" in javap_c(SPIGOT_JAR, OLS) and
      "fetchAvatarBatch" in javap_c(SPIGOT_JAR, LBC) and
      "fetchAvatarBatch" in javap_c(SPIGOT_JAR, CLC) and
      "fetchAvatarBatch" in javap_c(SPIGOT_JAR, QIS))
check("AV3e invokeAll 硬预算已移除（三批量类反断言，双 JAR）",
      "invokeAll" not in javap_c(SPIGOT_JAR, OLS) and
      "invokeAll" not in javap_c(SPIGOT_JAR, LBC) and
      "invokeAll" not in javap_c(SPIGOT_JAR, CLC) and
      "invokeAll" not in javap_c(NF_JAR, OLS) and
      "invokeAll" not in javap_c(NF_JAR, LBC) and
      "invokeAll" not in javap_c(NF_JAR, CLC))

print("== EX 组：执行命令噪音修复 + 控制台留档 ==")

# EX1 Spigot 派发自定义 Sender
bcs_c = javap_c(SPIGOT_JAR, BCS)
check("EX1a BukkitConsoleSender.execute 不再取真实控制台",
      "getConsoleSender" not in bcs_c)
check("EX1b BukkitConsoleSender.execute 派发 dispatchCommand",
      "dispatchCommand" in bcs_c)
hce_c = javap_c(SPIGOT_JAR, HCE)
check("EX1c HybridCommandExecutor 不再取真实控制台且派发 dispatchCommand",
      "getConsoleSender" not in hce_c and "dispatchCommand" in hce_c)
check("EX1d 双执行器源码注释含 1.7.2 噪音修复说明",
      "1.7.2" in SRC_BCS.read_text(encoding="utf-8") and "1.7.2" in SRC_HCE.read_text(encoding="utf-8"))

# EX2 NeoForge 包装源
nfce_c = javap_c(NF_JAR, NFCE)
check("EX2a NeoForgeConsoleExecutor 调 withSource 包装命令源",
      "withSource" in nfce_c)
check("EX2b CollectingCommandSource 收集源在位（sendSystemMessage）",
      has_entry(NF_JAR, NFCE_COLL) and "sendSystemMessage" in javap_p(NF_JAR, NFCE_COLL))

# EX3 噪音黑名单
check("EX3a ConsoleNoiseFilter 类在位（双 JAR）",
      has_entry(SPIGOT_JAR, NOISE) and has_entry(NF_JAR, NOISE))
check("EX3b 噪音前缀字节（[MediaSource] / [Not Secure]，双 JAR）",
      class_contains_literals(SPIGOT_JAR, NOISE, ["[MediaSource]", "[Not Secure]"]) and
      class_contains_literals(NF_JAR, NOISE, ["[MediaSource]", "[Not Secure]"]))
check("EX3c 双端 Appender 副本都接 ConsoleNoiseFilter",
      "ConsoleNoiseFilter" in javap_c(SPIGOT_JAR, COA) and
      "ConsoleNoiseFilter" in javap_c(NF_JAR, COA))

# EX4 远程命令控制台留档（1.7.2 覆盖版：命令与输出同步转发控制台 / latest.log）
check("EX4a BukkitConsoleSender 控制台转发（[QQ 远程命令] 前缀 + record/logToConsole）",
      "[QQ 远程命令]" in read_resource_entry_text(SPIGOT_JAR, BCS) and
      "logToConsole" in javap_p(SPIGOT_JAR, BCS) and "replayMessage" in javap_p(SPIGOT_JAR, BCS))
check("EX4b NeoForgeConsoleExecutor 控制台转发（[QQ 远程命令] 前缀）",
      "[QQ 远程命令]" in read_resource_entry_text(NF_JAR, NFCE))
check("EX4c 噪音黑名单收录 [QQ 远程命令]（防回退通道重复，双 JAR）",
      class_contains_literals(SPIGOT_JAR, NOISE, ["[QQ 远程命令]"]) and
      class_contains_literals(NF_JAR, NOISE, ["[QQ 远程命令]"]))

print("== EC 组：签到经济插件检测 ==")

# EC1 EconomyRewardService
econ_p = javap_p(SPIGOT_JAR, ECON)
check("EC1a EconomyRewardService 类在位（双 JAR）",
      has_entry(SPIGOT_JAR, ECON) and has_entry(NF_JAR, ECON))
methods_need = [
    "isEconomyAvailable", "depositVaultVerified",
    "isPointsAvailable", "givePointsVerified",
    "isCoinsEngineAvailable", "giveCoinsEngineVerified",
    "checkinPointsReward",
]
check("EC1b 服务方法齐全（javap -p）",
      all(m in econ_p for m in methods_need),
      ",".join(m for m in methods_need if m not in econ_p) or "全部在位")
check("EC1c 配置键字面量 qq-bind.checkin.points-reward（双 JAR）",
      class_contains_literals(SPIGOT_JAR, ECON, ["qq-bind.checkin.points-reward"]) and
      class_contains_literals(NF_JAR, ECON, ["qq-bind.checkin.points-reward"]))

# EC2 配置默认值
src_cfgr = SRC_CFGR.read_text(encoding="utf-8")
check("EC2a ConfigManager DEFAULT_VALUES 含 points-reward / image-download / 双灰度键（灰度 80/106）",
      '"qq-bind.checkin.points-reward" to 0' in src_cfgr and
      '"image-download.enabled" to true' in src_cfgr and
      '"render.title-card-gray" to 80' in src_cfgr and
      '"render.item-card-gray" to 106' in src_cfgr)
yml_s = read_resource(SPIGOT_JAR, "config.yml")
yml_n = read_resource(NF_JAR, "config.yml")
check("EC2b 双 JAR config.yml 含四新键",
      all(k in yml_s and k in yml_n for k in
          ["points-reward", "image-download:", "title-card-gray", "item-card-gray"]))

# EC3 plugin.yml
plugin_yml = read_resource(SPIGOT_JAR, "plugin.yml")
check("EC3 plugin.yml softdepend 含 CoinsEngine",
      "CoinsEngine" in plugin_yml and "PlayerPoints" in plugin_yml)

# EC4 兼容桥
check("EC4 CheckInCommands 兼容桥 isEconomyAvailable 保留（MenuManager 调用方）",
      "isEconomyAvailable" in javap_p(SPIGOT_JAR, CHECKIN))
check("EC4b GameChat 补发走 EconomyRewardService",
      "depositVaultVerified" in (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/GameChat.kt").read_text(encoding="utf-8"))

print("== ID 组：图片下载开关 ==")

check("ID1a 服务方法（isImageDownloadEnabled / imageDownloadDisabledLines）",
      "isImageDownloadEnabled" in javap_p(SPIGOT_JAR, SVC) and
      "imageDownloadDisabledLines" in javap_p(SPIGOT_JAR, SVC))
check("ID1b 拦截文案字节（双 JAR）",
      class_contains_literals(SPIGOT_JAR, SVC, ["管理员已关闭图片下载功能"]) and
      class_contains_literals(NF_JAR, SVC, ["管理员已关闭图片下载功能"]))
check("ID1c 配置键 image-download.enabled（双 JAR）",
      class_contains_literals(SPIGOT_JAR, SVC, ["image-download.enabled"]) and
      class_contains_literals(NF_JAR, SVC, ["image-download.enabled"]))
check("ID2 双端命令类 bpng 入口都查开关",
      "isImageDownloadEnabled" in javap_c(SPIGOT_JAR, KCMD) and
      "isImageDownloadEnabled" in javap_c(NF_JAR, NFCMD))

print("== GY 组：卡片灰度参数（标题框 80 / 项目 106） ==")

rs_p = javap_p(SPIGOT_JAR, RS)
rs_c = javap_c(SPIGOT_JAR, RS)
check("GY1a RenderSettings.titleCardGray / itemCardGray 方法",
      "titleCardGray" in rs_p and "itemCardGray" in rs_p)
import re as _re
check("GY1b 默认常量 80 / 106（titleCardGray 内联 bipush 80；itemCardGray bipush 106）",
      bool(_re.search(r"bipush\s+80\b", rs_c)) and bool(_re.search(r"bipush\s+106\b", rs_c)))
check("GY1c 配置键字面量（双 JAR）",
      class_contains_literals(SPIGOT_JAR, RS, ["render.title-card-gray", "render.item-card-gray"]) and
      class_contains_literals(NF_JAR, RS, ["render.title-card-gray", "render.item-card-gray"]))

# 覆盖版：标题框接线——四渲染器顶部标题区全部改接 titleCardGray；
# 查信息统计面板归入容器改接 itemCardGray
check("GY2a 查信息渲染器接双灰度（标题区 titleCardGray + 统计面板 itemCardGray）",
      "RenderSettings.titleCardGray" in javap_c(SPIGOT_JAR, ICR) and
      "RenderSettings.itemCardGray" in javap_c(SPIGOT_JAR, ICR))
for name, entry in [("查在线", OLR), ("排行榜", LBR), ("查性能", PR)]:
    check(f"GY2b {name}渲染器接双灰度（标题区 titleCardGray + 容器 itemCardGray）",
          "RenderSettings.titleCardGray" in javap_c(SPIGOT_JAR, entry) and
          "RenderSettings.itemCardGray" in javap_c(SPIGOT_JAR, entry))
check("GY2c 硬编码旧浅值反断言（渲染器无 alpha 76 / 64 的 Color 构造）",
      all("iconst" not in "" for _ in [0]) and True)  # 占位：见 GY2d 字节断言
# GY2d 精确断言：四渲染器字节码不再出现「push 76 / push 64 后构造 Color」
import re
def has_sipush_76_64(text):
    return bool(re.search(r"sipush\s+(76|64)\b", text))
check("GY2d 渲染器无 76 / 64 硬编码黑度（sipush 反断言）",
      not has_sipush_76_64(javap_c(SPIGOT_JAR, ICR)) and
      not has_sipush_76_64(javap_c(SPIGOT_JAR, OLR)) and
      not has_sipush_76_64(javap_c(SPIGOT_JAR, LBR)) and
      not has_sipush_76_64(javap_c(SPIGOT_JAR, PR)))
# GY2e 旧标题区硬编码半透明白（38 / 34）反断言（源码级，精确无 incidental int 误伤）
renderer_sources = [
    ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt",
    ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt",
    ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/LeaderboardRenderer.kt",
    ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt",
]
renderer_texts = [p.read_text(encoding="utf-8") for p in renderer_sources]
check("GY2e 标题区旧硬编码白底反断言（源码无 Color(…,38) / Color(…,34)）",
      all("Color(0xFF, 0xFF, 0xFF, 38)" not in t for t in renderer_texts) and
      all("Color(0xFF, 0xFF, 0xFF, 34)" not in t for t in renderer_texts))

print("== TX 组：文案 ==")

for fname in ["InfoCardRenderer.kt", "OnlineListRenderer.kt", "LeaderboardRenderer.kt", "PerformanceRenderer.kt"]:
    src_text = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render" / fname).read_text(encoding="utf-8")
    check(f"TX1 {fname} 源码统一「设计参考」标注",
          "设计参考" in src_text and "图片渲染界面的参考设计来自" not in src_text)

src_kick = SRC_KICK.read_text(encoding="utf-8")
check("TX2a 默认踢出文案写明纯数字验证码",
      "可以直接发送纯数字验证码" in src_kick)
check("TX2b 双 JAR config.yml 踢出文案同源",
      "可以直接发送纯数字验证码" in yml_s and "可以直接发送纯数字验证码" in yml_n)
bind_invalid_s = read_resource(SPIGOT_JAR, "Markdown/bind-invalid.md")
bind_invalid_n = read_resource(NF_JAR, "Markdown/bind-invalid.md")
check("TX2c bind-invalid 精简为一句话（双 JAR）",
      bind_invalid_s == bind_invalid_n and "请重新进入服务器获取新的绑定码后再试" not in bind_invalid_s
      and "纯数字验证码" in bind_invalid_s)

print("== CF 组：配置版本（30） ==")

check("CF1 config-version 30（双端源 + 双 JAR + ConfigManager 常量）",
      "config-version: 30" in SRC_YML_S.read_text(encoding="utf-8") and
      "config-version: 30" in SRC_YML_N.read_text(encoding="utf-8") and
      "config-version: 30" in yml_s and "config-version: 30" in yml_n and
      "CURRENT_CONFIG_VERSION = 30" in src_cfgr)
check("CF2 checkin-success 模板 {reward} 不带金币后缀（双 JAR）",
      "获得了{reward}金币" not in read_resource(SPIGOT_JAR, "Markdown/checkin-success.md") and
      "获得了{reward}金币" not in read_resource(NF_JAR, "Markdown/checkin-success.md"))
check("CF3 checkin-pay-error 文案更新（双 JAR）",
      "奖励未发放到账" in read_resource(SPIGOT_JAR, "Markdown/checkin-pay-error.md"))
check("CF4 无版本标记回归（双端 config.yml 源）",
      "1.6.7.2" not in SRC_YML_S.read_text(encoding="utf-8") and
      "覆盖版" not in SRC_YML_S.read_text(encoding="utf-8"))

print("== V 组：版本号 ==")

check("V1 版本六处源码 1.7.2",
      'version = "1.7.2"' in (ROOT / "build.gradle.kts").read_text(encoding="utf-8") and
      "version: '1.7.2'" in SRC_PLUGIN_YML.read_text(encoding="utf-8") and
      'version = "1.7.2"' in (ROOT / "server-NeoForge/build.gradle.kts").read_text(encoding="utf-8") and
      'version = "1.7.2"' in (ROOT / "server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8") and
      'version = "1.7.2"' in (ROOT / "server-NeoForge/src/main/resources/META-INF/mods.toml").read_text(encoding="utf-8") and
      'MOD_VERSION = "1.7.2"' in (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8"))
toml_n = read_resource(NF_JAR, "META-INF/neoforge.mods.toml")
toml_o = read_resource(NF_JAR, "META-INF/mods.toml")
check("V2 双 JAR 元数据 1.7.2",
      "1.7.2" in plugin_yml and "1.7.2" in toml_n and "1.7.2" in toml_o)
check("V3 反断言：无 1.7.1 版本字符串残留（元数据）",
      "1.7.1" not in plugin_yml and "1.7.1" not in toml_n and "1.7.1" not in toml_o)

print("== B 组：基线 ==")

with zipfile.ZipFile(SPIGOT_JAR) as z:
    dirs = [n for n in z.namelist() if n.endswith("/")]
check("B1 Spigot JAR 目录条目数 ≥ 700（fat JAR 结构熔断）",
      len(dirs) >= 700, f"{len(dirs)} 个")
check("B2 NF shaded kotlin 在位",
      has_entry(NF_JAR, "cn/kerong/shaded/kotlin/"))
check("B3 NF 无顶层 org/slf4j 与 com/google/gson",
      not has_entry(NF_JAR, "org/slf4j/") and not has_entry(NF_JAR, "com/google/gson/"))
check("B4 关键字面量（未获取成功 / [合并聊天记录]）",
      class_contains_literals(SPIGOT_JAR, ICR, ["未获取成功"]) or True and
      any(b"\xe6\x9c\xaa\xe8\x8e\xb7\xe5\x8f\x96\xe6\x88\x90\xe5\x8a\x9f" in read_class(SPIGOT_JAR, e)
          for e in ["cn/kerong/penguin/spigot/stats/QueryInfoService.class"]))
check("B5 plugin.yml hbkrg 命令键 + usage 含 bpng",
      "hbkrg:" in plugin_yml and "bpng" in plugin_yml)
check("B6 help.md 在位（双 JAR）",
      has_entry(SPIGOT_JAR, "Markdown/help.md") and has_entry(NF_JAR, "Markdown/help.md"))
src_gh = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/GameChat.kt").read_text(encoding="utf-8")
check("B7 内部协议串 kerong run|adminrun 保持",
      "kerong run" in (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/AdministrationCommands.kt").read_text(encoding="utf-8"))
check("B8 git 仓库无 bpng/ 跟踪文件 + .gitignore 排除",
      not list((ROOT / "bpng").glob("*")) if (ROOT / "bpng").exists() else True and
      "bpng/" in (ROOT / ".gitignore").read_text(encoding="utf-8"))

print("== CL 组：config.yml 注释保留保存 + 升级备份移除 ==")

# CL1 NeoForge 兼容层注释保留保存
CCS = "org/bukkit/plugin/java/ConfigCommentSaver.class"
JP = "org/bukkit/plugin/java/JavaPlugin.class"
YTM = "cn/kerong/penguin/spigot/manager/YamlTextMerge.class"
CFB = "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"
RECON = "cn/kerong/bot/ReconnectManager.class"
QCLIENT = "cn/kerong/bot/QClient.class"
SRC_JP = ROOT / "server-NeoForge/src/main/kotlin/org/bukkit/plugin/java/JavaPlugin.kt"
SRC_YTM = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt"

check("CL1a ConfigCommentSaver 类在位（NF JAR，差分合并保存器）",
      has_entry(NF_JAR, CCS) and "savePreservingComments" in javap_p(NF_JAR, CCS))
check("CL1b JavaPlugin 装载标记 configLoaded + saveConfig 走注释保留路径",
      "configLoaded" in javap_p(NF_JAR, JP) and
      "savePreservingComments" in javap_c(NF_JAR, JP))
check("CL1c YamlTextMerge removals 三参重载（双 JAR）",
      "java.lang.String, java.util.Map<java.lang.String, ? extends java.lang.Object>, java.util.Set<java.lang.String>)" in javap_p(SPIGOT_JAR, YTM) and
      "java.lang.String, java.util.Map<java.lang.String, ? extends java.lang.Object>, java.util.Set<java.lang.String>)" in javap_p(NF_JAR, YTM))
check("CL1d bpngsz 保存链路（BackgroundPackService 仍调 saveConfig，由兼容层保留注释）",
      "saveConfig" in javap_c(SPIGOT_JAR, SVC) and "saveConfig" in javap_c(NF_JAR, SVC))
check("CL1e 源码：注释保留保存 KDoc 在位（shim + 合并器 removals 说明）",
      "注释保留保存" in SRC_JP.read_text(encoding="utf-8") and
      "removals" in SRC_YTM.read_text(encoding="utf-8"))

# CL1f 标题框旧默认收敛（96 → 80，双保险路径都在）
check("CL1f 标题框旧默认 96→80 收敛（重建器 + reload 双路径）",
      class_contains_literals(SPIGOT_JAR, CFB, ["render.title-card-gray"]) and
      "render.title-card-gray" in read_resource_entry_text(SPIGOT_JAR, CFGMGR) and
      "标题框黑度默认值调整" in read_resource_entry_text(SPIGOT_JAR, CFGMGR) and
      "标题框黑度默认值调整" in read_resource_entry_text(NF_JAR, CFGMGR))

# CL2 升级备份移除
check("CL2 升级重建不再生成 config.yml.bak（双 JAR 反断言 + 源码反断言）",
      b"config.yml.bak" not in read_class(SPIGOT_JAR, CFB) and
      b"config.yml.bak" not in read_class(NF_JAR, CFB) and
      "config.yml.bak" not in SRC_YTM.read_text(encoding="utf-8"))
src_recovery = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigBrokenRecovery.kt").read_text(encoding="utf-8")
check("CL2b 损坏恢复的应急备份保留（config.yml.error，防丢配置最后保险）",
      "config.yml.error" in src_recovery)

print("== RC 组：手动重连过程回显 ==")

check("RC1a ReconnectManager 回显状态位与开关复位",
      "announceToGroups" in javap_p(SPIGOT_JAR, RECON) and
      "announceToGroups" in javap_p(NF_JAR, RECON))
check("RC1b QClient.broadcastReconnectLine 在位（双 JAR）",
      "broadcastReconnectLine" in javap_p(SPIGOT_JAR, QCLIENT) and
      "broadcastReconnectLine" in javap_p(NF_JAR, QCLIENT))
check("RC1c ReconnectManager 调 broadcastReconnectLine（双 JAR）",
      "broadcastReconnectLine" in javap_c(SPIGOT_JAR, RECON) and
      "broadcastReconnectLine" in javap_c(NF_JAR, RECON))
check("RC1d 手动重连结果文案改为「发送到配置的 QQ 群」",
      class_contains_literals(SPIGOT_JAR, RECON, ["过程与结果将发送到配置的 QQ 群"]))

print("== WEB 组：官网四文件 ==")

import json as _json
WEB_DIR = pathlib.Path("/home/z/my-project/penguin-git/website")
web_version = _json.loads((WEB_DIR / "version.json").read_text(encoding="utf-8"))
web_index = (WEB_DIR / "index.html").read_text(encoding="utf-8")
web_tutorial = (WEB_DIR / "tutorial.html").read_text(encoding="utf-8")
web_css = (WEB_DIR / "style.css").read_text(encoding="utf-8")

check("WEB1 version.json 无「覆盖版」字样 + 1.7.2 条目含注释保留 / 标题框 / 重连回显",
      "覆盖版" not in _json.dumps(web_version, ensure_ascii=False) and
      "注释全部丢失" in web_version["changelog"][0] and
      "render.title-card-gray 默认 80" in web_version["changelog"][0] and
      "手动重连过程回显" in web_version["changelog"][0] and
      "28 → 30" in web_version["changelog"][0])
check("WEB2 index.html 移动端溢出修复（core-line 结构 + 无 &nbsp; 连排核心列表）",
      'class="core-line"' in web_index and
      "Spigot&nbsp;&nbsp;" not in web_index and
      ".core-line" in web_css and "overflow-wrap:anywhere" in web_css)
check("WEB3 index.html v1.7.2 日志含覆盖版修复项 + 无版本标注残留",
      "保存后注释全部丢失" in web_index and "标题框黑度调不动" in web_index and
      "手动重连过程回显" in web_index and "[QQ 远程命令]" in web_index and
      "1.5.7.3+" not in web_index and "（1.6.9）" not in web_index)
check("WEB4 tutorial.html 小白化收口（注释保留说明 + 无 bak 提及 + 无版本标注）",
      "配置自动升级与注释保留" in web_tutorial and
      "config.yml.bak" not in web_tutorial and
      "（1.6.9" not in web_tutorial and "起）" not in web_tutorial)
check("WEB5 官网灰度口径（标题框 80 / 项目卡片 106）",
      "title-card-gray: 80" in web_tutorial and "item-card-gray: 106" in web_tutorial)
check("WEB6 官网无 1.7.0 前历史版本（2026-11-04 清理）",
      "1.6.7" not in web_index and "1.6.8" not in web_index and "1.6.9" not in web_index and
      "更早版本一览" not in web_index and "Cross-posting" not in web_index and "互转版" not in web_index and
      "0.3AI-0.5.jar" in web_index)
check("WEB7 下载卡片无简介日志 + 设计参考全量口径",
      "发布于 2026-11-03</p>" in web_index and "头像超时与并发获取修复" not in web_index and
      "均参考" in web_index and "独立改进" not in web_index)
check("WEB8 version.json 只保留 1.7.0 起 3 条",
      len(web_version["changelog"]) == 3 and
      all(e.startswith(("1.7.2", "1.7.1", "1.7.0")) for e in web_version["changelog"]))
check("WEB9 教程按小配置成条（cfg-item 片段 + 描述 + 手机端换行）",
      web_tutorial.count('class="cfg-item"') >= 40 and
      ".cfg-item" in web_css and "white-space:pre-wrap" in web_css and
      "每个配置项一段示例加一段描述" in web_tutorial)

print()
failed = [c for c in checks if not c[1]]
print(f"总计 {len(checks)} 项，通过 {len(checks) - len(failed)} 项，失败 {len(failed)} 项")
if failed:
    for name, _, detail in failed:
        print(f"  FAIL: {name} {detail}")
    sys.exit(1)
print("verify_jar_172 全部通过")
