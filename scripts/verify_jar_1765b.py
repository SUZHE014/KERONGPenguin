#!/usr/bin/env python3
"""1.7.6.5 覆盖轮门禁(2026-11-18:查性能内存占用改为含预留口径)。

M 组:内存口径(四端 JAR 字节码/字节级断言)
  M1 PerformanceSnapshot 字段 usedMemoryMb 消失 / committedMemoryMb 在位
  M2 capture 五参工厂方法体只调 totalMemory、不再调 freeMemory
  M3 PerformanceRenderer 类字节含「内存占用（含预留）」「（含 JVM 预留）」中文常量
  M4 PerformanceRenderer 全文 ≥2 处 getCommittedMemoryMb(指标卡 + 综合状态)且无 usedMemoryMb
  M5 源码级:KERONG.kt / PerformanceRenderer 无 usedMemoryMb;无 total-free 旧采集形态
V 组:版本官网
  V1 version.json 13 条纯字符串 + ⑬ 关键词
  V2 index.html ⑬ 行 + 日期 2026-11-18 + 无 2026-11-17 残留(发布日期位)
  V3 tutorial 查性能行 + 内存说明段
  V4 README 覆盖轮行
R 组:回归与终态
  R1 dist 无 1.7.6.3/.4 JAR(删除口径保持;上轮 verify_jar_1765 R5 的时序断言
     由本项终态断言取代——打包底座改用 dist 当版副本,不再依赖旧版产物在位)
  R2 四端 JAR 在位 + JAR 内版本 1.7.6.5
  R3 上轮核心抽查:game-fallback-text 键 / crawler 默认开 / stripInvisibleChars 在位
"""
import json
import os
import re
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

JARS = {
    "Spigot": os.path.join(DIST, "KERONGPenguin_Spigot-1.7.6.5.jar"),
    "NeoForge": os.path.join(DIST, "KERONGPenguin_NeoForge-1.20.4-1.7.6.5.jar"),
    "Fabric-1204": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.4-1.7.6.5.jar"),
    "Fabric-1201": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.1-1.7.6.5.jar"),
}

pass_n = 0
fail_n = 0
fails = []


def check(name, ok):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print(f"  PASS {name}")
    else:
        fail_n += 1
        fails.append(name)
        print(f"  FAIL {name}")


def read_entry(jar_path, entry):
    with zipfile.ZipFile(jar_path) as zf:
        return zf.read(entry)


def javap_text(jar_path, cls, extra=("-p", "-c")):
    out = subprocess.run(
        [JAVAP, *extra, "-cp", jar_path, cls],
        capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"javap 失败 {cls}: {out.stderr[:200]}")
    return out.stdout


def method_section(jv_text, signature):
    """截取一个方法的反汇编段(从签名行到下一个方法/末尾)。"""
    m = re.search(signature, jv_text)
    if not m:
        return ""
    start = m.start()
    nxt = re.search(r"\n  (public|private|protected|static|final).*\(", jv_text[m.end():])
    end = m.end() + nxt.start() if nxt else len(jv_text)
    return jv_text[start:end]


SNAP = "cn.kerong.bot.PerformanceSnapshot"
# @JvmStatic companion:外类 static 方法只是两行委托,真身在 $Companion(坑表 386)
SNAP_COMP = "cn.kerong.bot.PerformanceSnapshot$Companion"
RENDER = "cn.kerong.penguin.spigot.render.PerformanceRenderer"
CAPTURE5 = r"capture\(java\.lang\.Double, java\.lang\.Double, java\.lang\.Double, java\.lang\.Integer, java\.lang\.Integer\)"


def main():
    print("== M 组：内存口径（四端） ==")
    # M1 字段
    for name, path in JARS.items():
        fields = javap_text(path, SNAP, ("-p",))
        check(f"M1-{name} 字段 committedMemoryMb 在位 / usedMemoryMb 消失",
              "committedMemoryMb" in fields and "usedMemoryMb" not in fields)
    # M2 capture 方法体(真身在 $Companion)
    for name, path in JARS.items():
        body = method_section(javap_text(path, SNAP_COMP), CAPTURE5)
        check(f"M2-{name} capture 只调 totalMemory 不调 freeMemory",
              "java/lang/Runtime.totalMemory" in body and "freeMemory" not in body
              and "isub" not in body)
    # M3 渲染器中文常量(字节级)
    for name, path in JARS.items():
        b = read_entry(path, "cn/kerong/penguin/spigot/render/PerformanceRenderer.class")
        check(f"M3-{name} 卡片文案「内存占用（含预留）/（含 JVM 预留）」",
              "内存占用（含预留）".encode() in b and "（含 JVM 预留）".encode() in b
              and "内存占用（JVM 堆）".encode() not in b)
    # M4 渲染器访问
    for name, path in JARS.items():
        full = javap_text(path, RENDER)
        cnt = full.count("getCommittedMemoryMb")
        check(f"M4-{name} getCommittedMemoryMb ≥2（指标卡+综合状态）且无 usedMemoryMb",
              cnt >= 2 and "usedMemoryMb" not in full)
    # M5 源码级
    kerong = open(os.path.join(REPO, "common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt"),
                  encoding="utf-8").read()
    renderer = open(os.path.join(REPO, "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt"),
                    encoding="utf-8").read()
    check("M5 源码无 usedMemoryMb / 无 total-free 旧采集形态",
          "usedMemoryMb" not in kerong + renderer
          and "totalMemory() - runtime.freeMemory()" not in kerong
          and "committedMemoryMb" in kerong and "committedMemoryMb" in renderer)

    print("== V 组：版本官网 ==")
    vj = json.loads(open(os.path.join(REPO, "website/version.json"), encoding="utf-8").read())
    check("V1 version.json 13 条纯字符串 + ⑬ 关键词",
          vj["version"] == "1.7.6.5" and len(vj["changelog"]) == 13
          and all(isinstance(c, str) for c in vj["changelog"])
          and "含预留" in vj["changelog"][12] and "已提交" in vj["changelog"][12]
          and "MC 面板" in vj["changelog"][12])
    idx = open(os.path.join(REPO, "website/index.html"), encoding="utf-8").read()
    check("V2 index ⑬ 行 + 日期 2026-11-18 + 无 2026-11-17 发布日期残留",
          "⑬ 查性能内存占用改为含预留口径" in idx
          and ">发布于 2026-11-18</p>" in idx
          and '<h3>v1.7.6.5</h3><span class="date">2026-11-18</span>' in idx
          and "1.7.6.5</td><td>2026-11-18</td>" in idx
          and ">发布于 2026-11-17</p>" not in idx
          and 'class="date">2026-11-17' not in idx
          and "1.7.6.5</td><td>2026-11-17</td>" not in idx)
    tut = open(os.path.join(REPO, "website/tutorial.html"), encoding="utf-8").read()
    check("V3 tutorial 查性能行 + 内存说明段",
          "含预留口径，与 MC 面板显示一致" in tut
          and "「内存占用（含预留）」即为该口径" in tut)
    readme = open(os.path.join(REPO, "README.md"), encoding="utf-8").read()
    check("V4 README 覆盖轮行（committedMemoryMb 口径说明）",
          "committedMemoryMb" in readme and "覆盖轮（2026-11-18）：查性能内存占用改为含预留口径" in readme)

    print("== R 组：回归与终态 ==")
    left = [f for f in os.listdir(DIST) if re.search(r"1\.7\.6\.[34]\.jar$", f)]
    check("R1 dist 无 1.7.6.3/.4 JAR（删除口径保持）", not left)
    ok_r2 = all(os.path.isfile(p) for p in JARS.values())
    if ok_r2:
        ok_r2 = ("version: '1.7.6.5'" in read_entry(JARS["Spigot"], "plugin.yml").decode("utf-8")
                 and '"version": "1.7.6.5"' in read_entry(JARS["Fabric-1201"], "fabric.mod.json").decode("utf-8"))
    check("R2 四端 JAR 在位 + JAR 内版本", ok_r2)
    cfg = read_entry(JARS["Spigot"], "config.yml").decode("utf-8")
    wc_bytes = read_entry(JARS["Spigot"], "cn/kerong/penguin/spigot/qqbind/WebCrawler.class")
    check("R3 上轮核心保持（game-fallback-text 键 / crawler 默认开 / stripInvisibleChars）",
          "game-fallback-text" in cfg and "enabled: true" in cfg
          and b"stripInvisibleChars" in wc_bytes)

    print(f"\n===== 1.7.6.5 覆盖轮门禁: {pass_n} PASS / {fail_n} FAIL =====")
    if fails:
        for name in fails:
            print(f"  FAIL 项: {name}")
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
