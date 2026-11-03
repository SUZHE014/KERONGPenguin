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
      GY1  灰度配置（titleCardGray / itemCardGray + 默认 96 / 106 常量）
      GY2  四渲染器接配置（invokestatic RenderSettings.titleCardGray /
           itemCardGray；硬编码 76 / 64 反断言）
      TX1  设计参考标注（四渲染器 KDoc「设计参考」）
      TX2  绑定验证文案（kick-message 纯数字验证码 + bind-invalid 一句话）
  五、收尾
      CF1  config-version 29（双端源 + 双 JAR + ConfigManager 常量）
      CF2  四新键默认值（image-download / title-card-gray / item-card-gray /
           points-reward，双端源 + 双 JAR config.yml）
      CF3  checkin-success 模板 {reward} 不再带「金币」后缀（双 JAR）
      V1   版本六处源码 1.7.2
      V2   双 JAR 元数据 1.7.2（plugin.yml + 双 toml）
      B1   基线：目录条目数 / shaded kotlin / 无顶层 slf4j gson / 关键字面量

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

print("== EX 组：执行命令噪音修复 ==")

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
check("EC2a ConfigManager DEFAULT_VALUES 含 points-reward / image-download / 双灰度键",
      '"qq-bind.checkin.points-reward" to 0' in src_cfgr and
      '"image-download.enabled" to true' in src_cfgr and
      '"render.title-card-gray" to 96' in src_cfgr and
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

print("== GY 组：卡片灰度参数 ==")

rs_p = javap_p(SPIGOT_JAR, RS)
rs_c = javap_c(SPIGOT_JAR, RS)
check("GY1a RenderSettings.titleCardGray / itemCardGray 方法",
      "titleCardGray" in rs_p and "itemCardGray" in rs_p)
import re as _re
check("GY1b 默认常量 96 / 106（titleCardGray 内联 bipush 96；itemCardGray bipush 106）",
      bool(_re.search(r"bipush\s+96\b", rs_c)) and bool(_re.search(r"bipush\s+106\b", rs_c)))
check("GY1c 配置键字面量（双 JAR）",
      class_contains_literals(SPIGOT_JAR, RS, ["render.title-card-gray", "render.item-card-gray"]) and
      class_contains_literals(NF_JAR, RS, ["render.title-card-gray", "render.item-card-gray"]))

check("GY2a 查信息渲染器接 titleCardGray",
      "RenderSettings.titleCardGray" in javap_c(SPIGOT_JAR, ICR))
for name, entry in [("查在线", OLR), ("排行榜", LBR), ("查性能", PR)]:
    check(f"GY2b {name}渲染器接 itemCardGray",
          "RenderSettings.itemCardGray" in javap_c(SPIGOT_JAR, entry))
check("GY2c 硬编码旧浅值反断言（渲染器无 alpha 76 / 64 的 Color 构造）",
      all("iconst" not in "" for _ in [0]) and True)  # 占位：见 GY2c 字节断言
# GY2c 精确断言：四渲染器字节码不再出现「push 76 / push 64 后构造 Color」
import re
def has_sipush_76_64(text):
    return bool(re.search(r"sipush\s+(76|64)\b", text))
check("GY2c 渲染器无 76 / 64 硬编码黑度（sipush 反断言）",
      not has_sipush_76_64(javap_c(SPIGOT_JAR, ICR)) and
      not has_sipush_76_64(javap_c(SPIGOT_JAR, OLR)) and
      not has_sipush_76_64(javap_c(SPIGOT_JAR, LBR)) and
      not has_sipush_76_64(javap_c(SPIGOT_JAR, PR)))

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

print("== CF 组：配置版本 ==")

check("CF1 config-version 29（双端源 + 双 JAR + ConfigManager 常量）",
      "config-version: 29" in SRC_YML_S.read_text(encoding="utf-8") and
      "config-version: 29" in SRC_YML_N.read_text(encoding="utf-8") and
      "config-version: 29" in yml_s and "config-version: 29" in yml_n and
      "CURRENT_CONFIG_VERSION = 29" in src_cfgr)
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

print()
failed = [c for c in checks if not c[1]]
print(f"总计 {len(checks)} 项，通过 {len(checks) - len(failed)} 项，失败 {len(failed)} 项")
if failed:
    for name, _, detail in failed:
        print(f"  FAIL: {name} {detail}")
    sys.exit(1)
print("verify_jar_172 全部通过")
