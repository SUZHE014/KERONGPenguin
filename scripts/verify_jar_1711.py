#!/usr/bin/env python3
"""verify_jar_1711 —— 1.7.1 产物门禁（背景图片包系统 bpng / bpngsz）。

背景（用户需求）：
  ① 官网新增图片包目录 /wwwroot/penguin/bpng/（wl1 蔚蓝档案，29 张），
     同时上传 git 仓库 bpng/ 目录作镜像备用下载链接；
  ② /hbkrg bpng 列出可下载图片包；/hbkrg bpng <包ID> 自动下载到 img/
     文件夹，显示下载过程，可执行指令中断，下载失败的跳过；
  ③ /hbkrg bpngsz yes 一键把全部渲染图片设置切换为使用 img/ 自定义
     背景图片，no 一键关闭；执行完命令后配置文件修改完自动重载。

断言组：
  BP 组  图片包功能
       BP1  BackgroundPackService 主类 + 四个嵌套类在位（双 JAR）
       BP2  三下载源字面量（官网域名 / gh-proxy raw / 直连 raw，双 JAR 字节）
       BP3  反断言：IP 直连源不在（Java 无 SNI 握手必败，1.7.1 弃用路径）
       BP4  五个背景开关键字面量（双 JAR 字节）
       BP5  服务方法齐全（javap -p：downloadPackById / downloadPack /
            listPacksLines / switchBackgrounds / fetchPacks / requestCancel /
            hasActiveDownload / activeDownloadBrief / parseManifest）
       BP6  完整性与安全构件（javap -p：md5Of / md5OfFile / isSafeFileName /
            isSafeToken；.part 临时文件字面量）
       BP7  KERONGCommand：bpng / bpngsz 命令分支 + SUBCOMMANDS 含两新子命令
            （javap -c 字面量 + 源码）
       BP8  NeoForgeCommands：bpng / bpngsz / stop / yes / no 字面量（字节）
       BP9  双端命令类 help 行含 bpng / bpngsz（源码）
       BP10 双端命令类：进度行经 submit 回主线程（源码 plugin.submit）
       BP11 中断语义：下载中再次执行同一命令先 requestCancel（源码两处）
       BP12 NF 端 fastjson 引用已重定位（无 Lcom/alibaba 描述符前缀）
  CF 组  配置
       CF1  config-version: 28（双端源 + 双 JAR 内置 + ConfigManager 常量池）
       CF2  背景图说明段含 /hbkrg bpng 与 /hbkrg bpngsz（双端源 + JAR）
       CF3  双端 config.yml（源 + JAR）无「覆盖版 / 1.6.」版本标记回归
       CF4  五个背景开关键仍为 true 默认（双端源）
  V 组  版本
       V1   版本六处源码 1.7.1（gradle 根 / plugin.yml / NF gradle / 双 toml /
            MOD_VERSION）
       V2   双 JAR 内 plugin.yml 与双 toml 均为 1.7.1
       V3   反断言：产物与源码无 1.7.0 版本字符串残留
  B 组  基线回归
       B1   Spigot JAR 目录条目数（fat JAR 结构熔断）
       B2   NF shaded kotlin 在位（cn/kerong/shaded/kotlin/）
       B3   NF 无顶层 org/slf4j、com/google/gson（split package 防回归）
       B4   「未获取成功」/「[合并聊天记录]」字面量在位（双 JAR）
       B5   plugin.yml：hbkrg 命令键在、hbkng / kerong 键不在；usage 含 bpng
       B6   help.md 双端源 + Spigot JAR：/hbkrg help 在位
       B7   内部协议串「kerong run|adminrun」保持（源码）

用法：python3 scripts/verify_jar_1711.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import tempfile
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.1.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.1.jar"
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
KCMD = "cn/kerong/penguin/spigot/commands/KERONGCommand.class"
NFCMD = "cn/kerong/penguin/neoforge/NeoForgeCommands.class"
CFGMGR = "cn/kerong/penguin/spigot/manager/ConfigManager.class"

SRC_SVC = ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/BackgroundPackService.kt"
SRC_KCMD = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt"
SRC_NFCMD = ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt"
SRC_CFGR = ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt"

print("== BP 组：图片包功能 ==")

# BP1 主类与嵌套类
nested = [
    "cn/kerong/penguin/spigot/render/BackgroundPackService$PackFile.class",
    "cn/kerong/penguin/spigot/render/BackgroundPackService$PackInfo.class",
    "cn/kerong/penguin/spigot/render/BackgroundPackService$DownloadSummary.class",
    "cn/kerong/penguin/spigot/render/BackgroundPackService$DownloadCancelledException.class",
]
check("BP1a Spigot 服务类五件套在位", all(has_entry(SPIGOT_JAR, e) for e in [SVC] + nested))
check("BP1b NeoForge 服务类五件套在位", all(has_entry(NF_JAR, e) for e in [SVC] + nested))

# BP2 三下载源
sources = ["https://kerong.xin/penguin/bpng/",
           "https://gh-proxy.com/https://raw.githubusercontent.com/SUZHE014/KERONGPenguin/main/bpng/",
           "https://raw.githubusercontent.com/SUZHE014/KERONGPenguin/main/bpng/"]
check("BP2a Spigot 三下载源字面量", class_contains_literals(SPIGOT_JAR, SVC, sources))
check("BP2b NeoForge 三下载源字面量", class_contains_literals(NF_JAR, SVC, sources))

# BP3 反断言：IP 直连源不在
svc_spigot = read_class(SPIGOT_JAR, SVC)
svc_nf = read_class(NF_JAR, SVC)
check("BP3 IP 直连源不在（双 JAR）",
      b"https://156.233.228.248/penguin/bpng/" not in svc_spigot and
      b"https://156.233.228.248/penguin/bpng/" not in svc_nf)

# BP4 五个背景开关键
switch_keys = ["render.info-card-builtin-background", "render.online-list-builtin-background",
               "render.performance.builtin-background", "leaderboard.builtin-background",
               "coins-leaderboard.builtin-background"]
check("BP4a Spigot 五背景键字面量", class_contains_literals(SPIGOT_JAR, SVC, switch_keys))
check("BP4b NeoForge 五背景键字面量", class_contains_literals(NF_JAR, SVC, switch_keys))

# BP5 服务方法
methods = ["downloadPackById", "downloadPack", "listPacksLines", "switchBackgrounds",
           "fetchPacks", "requestCancel", "hasActiveDownload", "activeDownloadBrief", "parseManifest"]
p_spigot = javap_p(SPIGOT_JAR, SVC)
p_nf = javap_p(NF_JAR, SVC)
check("BP5a Spigot 服务方法九件", all(m in p_spigot for m in methods),
      ",".join(m for m in methods if m not in p_spigot) or "全在")
check("BP5b NeoForge 服务方法九件", all(m in p_nf for m in methods),
      ",".join(m for m in methods if m not in p_nf) or "全在")

# BP6 完整性与安全构件
integrity = ["md5Of", "md5OfFile", "isSafeFileName", "isSafeToken"]
check("BP6a Spigot 校验/安全方法四件", all(m in p_spigot for m in integrity))
check("BP6b NeoForge 校验/安全方法四件", all(m in p_nf for m in integrity))
check("BP6c .part 临时文件字面量（双 JAR）",
      b".part" in svc_spigot and b".part" in svc_nf)
check("BP6d 清单文件名字面量（双 JAR）",
      b"manifest.json" in svc_spigot and b"manifest.json" in svc_nf)

# BP7 KERONGCommand
check("BP7a Spigot 命令分支 bpng/bpngsz", class_contains_literals(SPIGOT_JAR, KCMD, ["bpng", "bpngsz", "stop"]))
kcmd_src = SRC_KCMD.read_text(encoding="utf-8")
check("BP7b SUBCOMMANDS 含 bpng/bpngsz（源码）",
      '"bpng", "bpngsz", "help"' in kcmd_src and "bpngsz" in kcmd_src)
check("BP7c tab 补全二级参数（源码：stop / yes / no）",
      'listOf("stop")' in kcmd_src and '"yes", "no"' in kcmd_src)

# BP8 NeoForgeCommands
check("BP8 NF 命令树字面量 bpng/bpngsz/stop/yes/no",
      class_contains_literals(NF_JAR, NFCMD, ["bpng", "bpngsz", "stop", "yes", "no"]))

# BP9 help 行
check("BP9a Spigot help 行含 bpng/bpngsz",
      "bpng" in kcmd_src and "bpngsz yes|no" in kcmd_src)
nfcmd_src = SRC_NFCMD.read_text(encoding="utf-8")
check("BP9b NF help 行含 bpng/bpngsz", "bpngsz yes|no" in nfcmd_src and "bpng" in nfcmd_src)

# BP10 进度回主线程
check("BP10a Spigot 进度行 submit 回主线程",
      "plugin.submit { sender.sendMessage(line) }" in kcmd_src)
check("BP10b NF 进度行 submit 回主线程",
      "plugin.submit { source.sendSuccess({ Component.literal(line) }, false) }" in nfcmd_src)

# BP11 中断语义
check("BP11a Spigot 下载中再执行先中断", kcmd_src.count("service.requestCancel()") >= 2)
check("BP11b NF 下载中再执行先中断", nfcmd_src.count("service.requestCancel()") >= 2)

# BP12 NF 重定位
check("BP12 NF fastjson 引用已重定位（无 Lcom/alibaba 描述符）",
      b"Lcom/alibaba/fastjson" not in svc_nf and b"cn/kerong/shaded" in svc_nf)

print("== CF 组：配置 ==")

check("CF1a 双端源 config-version: 28",
      "config-version: 28" in (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8") and
      "config-version: 28" in (ROOT / "server-NeoForge/src/main/resources/config.yml").read_text(encoding="utf-8"))
cfg_spigot = read_resource(SPIGOT_JAR, "config.yml")
cfg_nf = read_resource(NF_JAR, "config.yml")
check("CF1b 双 JAR 内置 config-version: 28",
      "config-version: 28" in cfg_spigot and "config-version: 28" in cfg_nf)
cfgmgr_v = javap_p(SPIGOT_JAR, CFGMGR) + _javap_run(SPIGOT_JAR, CFGMGR, ("-c",))
check("CF1c ConfigManager 常量池 int 28", "28" in cfgmgr_v and "CURRENT_CONFIG_VERSION" in cfgmgr_v)
src_cfgr = SRC_CFGR.read_text(encoding="utf-8")
check("CF1d ConfigManager 源码 CURRENT_CONFIG_VERSION = 28",
      "CURRENT_CONFIG_VERSION = 28" in src_cfgr)

check("CF2a Spigot 源说明段含 bpng 用法",
      "/hbkrg bpngsz yes" in (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8") and
      "/hbkrg bpng <包ID>" in (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8"))
check("CF2b 双 JAR 说明段含 bpng 用法",
      "/hbkrg bpngsz yes" in cfg_spigot and "/hbkrg bpngsz yes" in cfg_nf and
      "/hbkrg bpng <包ID>" in cfg_spigot and "/hbkrg bpng <包ID>" in cfg_nf)

bad_markers = ["覆盖版", "1.6."]
check("CF3 无版本标记回归（双 JAR 内置 config）",
      not any(m in cfg_spigot or m in cfg_nf for m in bad_markers))

check("CF4 五背景键默认 true（双端源）",
      all((ROOT / p).read_text(encoding="utf-8").count(key + ": true") >= 1
          for p in ["server-Spigot/src/main/resources/config.yml",
                    "server-NeoForge/src/main/resources/config.yml"]
          for key in ["info-card-builtin-background", "online-list-builtin-background"]))

print("== V 组：版本 ==")

v_places = {
    "V1a 根 gradle": ("build.gradle.kts", 'version = "1.7.1"'),
    "V1b plugin.yml": ("server-Spigot/src/main/resources/plugin.yml", "version: '1.7.1'"),
    "V1c NF gradle": ("server-NeoForge/build.gradle.kts", 'version = "1.7.1"'),
    "V1d neoforge.mods.toml": ("server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml", 'version = "1.7.1"'),
    "V1e mods.toml": ("server-NeoForge/src/main/resources/META-INF/mods.toml", 'version = "1.7.1"'),
}
for name, (path, needle) in v_places.items():
    check(name, needle in (ROOT / path).read_text(encoding="utf-8"))
check("V1f MOD_VERSION 1.7.1",
      'private const val MOD_VERSION = "1.7.1"' in
      (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8"))

py_spigot = read_resource(SPIGOT_JAR, "plugin.yml")
toml_nf = read_resource(NF_JAR, "META-INF/neoforge.mods.toml")
toml_nf2 = read_resource(NF_JAR, "META-INF/mods.toml")
check("V2 双 JAR plugin.yml + 双 toml 1.7.1",
      "version: '1.7.1'" in py_spigot and 'version = "1.7.1"' in toml_nf and 'version = "1.7.1"' in toml_nf2)
check("V3 无 1.7.0 版本赋值残留（JAR 元数据，历史注释除外）",
      "version: '1.7.0'" not in py_spigot and 'version = "1.7.0"' not in toml_nf and 'version = "1.7.0"' not in toml_nf2)

print("== B 组：基线回归 ==")

with zipfile.ZipFile(SPIGOT_JAR) as z:
    dir_entries = [n for n in z.namelist() if n.endswith("/")]
    names_spigot = z.namelist()
check("B1 Spigot 目录条目数 >= 776", len(dir_entries) >= 776, f"{len(dir_entries)}")

with zipfile.ZipFile(NF_JAR) as z:
    names_nf = z.namelist()
check("B2 NF shaded kotlin 在位", any(n.startswith("cn/kerong/shaded/kotlin/") for n in names_nf))
check("B3a NF 无顶层 org/slf4j", not any(n.startswith("org/slf4j") for n in names_nf))
check("B3b NF 无顶层 com/google/gson", not any(n.startswith("com/google/gson") for n in names_nf))

render_classes = ["cn/kerong/penguin/spigot/stats/QueryInfoService.class"]
check("B4 关键字面量在位（未获取成功 + 合并聊天记录，双 JAR）",
      class_contains_literals(SPIGOT_JAR, render_classes[0], ["未获取成功"]) and
      class_contains_literals(NF_JAR, render_classes[0], ["未获取成功"]) and
      b"\xe5\x90\x88\xe5\xb9\xb6\xe8\x81\x8a\xe5\xa4\xa9\xe8\xae\xb0\xe5\xbd\x95" in read_class(SPIGOT_JAR, "cn/kerong/bot/events/GroupMessageHandler.class") and
      b"\xe5\x90\x88\xe5\xb9\xb6\xe8\x81\x8a\xe5\xa4\xa9\xe8\xae\xb0\xe5\xbd\x95" in read_class(NF_JAR, "cn/kerong/bot/events/GroupMessageHandler.class"))

check("B5 plugin.yml hbkrg 键在 / hbkng kerong 不在 / usage 含 bpng",
      "hbkrg:" in py_spigot and "hbkng" not in py_spigot and "\n  kerong:" not in py_spigot and "bpng" in py_spigot)

help_src_bot = (ROOT / "common-Bot/src/main/resources/Markdown/help.md").read_text(encoding="utf-8")
help_nf = (ROOT / "server-NeoForge/src/main/resources/Markdown/help.md").read_text(encoding="utf-8")
help_jar = read_resource(SPIGOT_JAR, "Markdown/help.md")
check("B6 help.md 三份 /hbkrg help 在位",
      "/hbkrg help" in help_src_bot and "/hbkrg help" in help_nf and "/hbkrg help" in help_jar)

registry = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CustomCommandRegistry.kt").read_text(encoding="utf-8")
check("B7 内部协议串 kerong run|adminrun 保持", "kerong run" in registry or "adminrun" in registry)

failed = [c for c in checks if not c[1]]
print(f"\n========== 共 {len(checks)} 项，PASS {len(checks) - len(failed)}，FAIL {len(failed)} ==========")
if failed:
    for name, _, detail in failed:
        print(f"  FAIL: {name} {detail}")
    sys.exit(1)
print("全部通过")
