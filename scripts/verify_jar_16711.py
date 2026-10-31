#!/usr/bin/env python3
"""verify_jar_16711 —— 1.6.7.11 产物门禁（登录闸门 mod 总线注册修复）。

背景：线上 1.6.7.9/1.6.7.10 的 NeoForge 版启动日志出现
  [QQ绑定] 登录闸门挂载异常（老玩家拦截退化为加入后踢出）: Method ...
  BindLoginGate$BindGateBusListener.onModBusEvent(net.neoforged.bus.api.Event)
  has @SubscribeEvent annotation, but takes an argument that is not valid for this bus
且紧跟着一条「登录闸门已挂载」的误导性 INFO——闸门实际从未挂上（外层无条件报成功）。

根因：mod 总线以 markerType(IModBusEvent) 构造，EventBus 校验监听参数必须实现
IModBusEvent，事件基类 Event 不满足 → register() 抛 IllegalArgumentException。
@SubscribeEvent 参数必须同时是 Event 子类型与目标事件类超类，编译目标 20.4 下
不存在这样的公共类型 → 注解监听此路不通。修复：反射调用
IEventBus.addListener(Class, Consumer) 以「运行时探测到的具体事件类」精确注册。

断言组：
  G 组  本版修复（NF JAR 字节级 + 源码 + CFR 反编译）
       G1  无 BindGateBusListener（旧基类监听器整体删除）
       G2  BindLoginGate 无 SubscribeEvent 注解引用
       G3  registerTo 描述符 (IEventBus)Z（返回 boolean 供如实反馈）
       G4  常量池含 addListener（反射注册路径）
       G5  幂等护栏 mounted（AtomicBoolean）
       G6  事件类探测与 addListener 解析方法在位
       G7  BindGateTask 仍实现 ICustomConfigurationTask（跨版本桥保持）
       G8  KERONGNeoForge 反编译：已挂载 INFO 在 registerTo 真值分支内
       G9  源码层：无 @SubscribeEvent / 有 addListener.invoke / 有 AtomicBoolean
  V 组  版本五处 1.6.7.11
  TO 组 双 toml 段头字节级 + 版本 + tomllib 解析
  B 组  基线：重定位、目录条目、slf4j/gson 剔除、plugin.yml、
       PanelSyncResult 与 PANEL_TRIM_ORDER（1.6.7.10 回归）

用法：python3 scripts/verify_jar_16711.py [nf_jar] [spigot_jar]
"""
import pathlib
import subprocess
import sys
import tempfile
import tomllib
import zipfile

NF_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.7.11.jar"
SPIGOT_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_Spigot-1.6.7.11.jar"
CFR = str(pathlib.Path("/home/z/my-project/tooling/cfr.jar"))
JAVA = "java"

checks = []


def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    suffix = f" — {detail}" if detail else ""
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}{suffix}")


MODS_BYTES = bytes([0x5B, 0x5B, 0x6D, 0x6F, 0x64, 0x73, 0x5D, 0x5D])  # [[mods]]


def non_comment_text(text):
    return "\n".join(l for l in text.splitlines() if not l.strip().startswith("#"))


def main():
    print(f"== verify_jar_16711 ==")
    print(f"  NF JAR: {NF_JAR}")
    print(f"  Spigot JAR: {SPIGOT_JAR}")

    root = pathlib.Path(".")
    gate_src = (root / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/BindLoginGate.kt").read_text(encoding="utf-8")
    host_src = (root / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8")

    # ---------------- G 组：本版修复 ----------------
    with zipfile.ZipFile(NF_JAR) as zf:
        names = zf.namelist()
        gate = zf.read("cn/kerong/penguin/neoforge/BindLoginGate.class")
        task = zf.read("cn/kerong/penguin/neoforge/BindLoginGate$BindGateTask.class")
        host = zf.read("cn/kerong/penguin/neoforge/KERONGNeoForge.class")
        menu = zf.read("cn/kerong/bot/MenuManager.class")

        check("G1 无旧 BindGateBusListener（基类监听器已删除）",
              "cn/kerong/penguin/neoforge/BindLoginGate$BindGateBusListener.class" not in names)
        check("G2 BindLoginGate 无 SubscribeEvent 注解引用",
              b"Lnet/neoforged/bus/api/SubscribeEvent;" not in gate)
        check("G3 registerTo 描述符 (Lnet/neoforged/bus/api/IEventBus;)Z",
              b"(Lnet/neoforged/bus/api/IEventBus;)Z" in gate)
        check("G4 常量池含 addListener（反射注册路径）", b"addListener" in gate)
        check("G5 幂等护栏 mounted + AtomicBoolean",
              b"mounted" in gate and b"java/util/concurrent/atomic/AtomicBoolean" in gate)
        check("G6 事件类探测与 addListener 解析方法在位",
              b"resolveConfigurationEventClass" in gate and b"resolveAddListenerMethod" in gate
              and b"RegisterConfigurationTasksEvent" in gate and b"OnGameConfigurationEvent" in gate)
        check("G7 BindGateTask 实现 ICustomConfigurationTask（跨版本桥保持）",
              b"net/neoforged/neoforge/network/configuration/ICustomConfigurationTask" in task)

        # G8 CFR 反编译核验如实反馈分支（已挂载 INFO 只在 registerTo 真值分支内）
        with tempfile.TemporaryDirectory() as td:
            tf = pathlib.Path(td) / "KERONGNeoForge.class"
            tf.write_bytes(host)
            proc = subprocess.run(
                [JAVA, "-jar", CFR, str(tf), "--silent", "true"],
                capture_output=True, text=True, timeout=120)
            txt = proc.stdout
            cond_branch = "if (BindLoginGate.INSTANCE.registerTo(modBus))" in txt
            # 分段 = if 行之后到首个右花括号之间：应含 modLogger.info 与
            # 「已挂载」的 CFR 转义形态（\\u5df2\\u6302\\u8f7d），证明 INFO 只在真值分支内
            seg = ""
            if cond_branch:
                seg = txt.split("if (BindLoginGate.INSTANCE.registerTo(modBus))", 1)[1].split("}", 1)[0]
            gate_info_after_branch = "modLogger.info" in seg and "\\u5df2\\u6302\\u8f7d" in seg
            check("G8 CFR：已挂载 INFO 在 registerTo 真值分支内（如实反馈）",
                  cond_branch and gate_info_after_branch)
            check("G8b CFR：null 总线分支只 WARN 不再报成功",
                  "modBus == null" in txt and txt.count("registerTo(modBus))") == 1)

    # G9 源码层断言（注释中描述历史问题的字样合法，只断言编译相关的 import）
    check("G9a 源码无 SubscribeEvent 导入（BindLoginGate）",
          "import net.neoforged.bus.api.SubscribeEvent" not in gate_src)
    check("G9b 源码反射注册 addListener.invoke 在位", "addListener.invoke(" in gate_src)
    check("G9c 源码幂等护栏 AtomicBoolean", "AtomicBoolean" in gate_src)
    check("G9d 源码挂载方按真实结果反馈",
          "if (BindLoginGate.registerTo(modBus))" in host_src
          and host_src.index("if (BindLoginGate.registerTo(modBus))") < host_src.index("登录闸门已挂载"))

    # ---------------- V 组：版本五处 ----------------
    v_root = (root / "build.gradle.kts").read_text(encoding="utf-8")
    v_plugin = (root / "server-Spigot/src/main/resources/plugin.yml").read_text(encoding="utf-8")
    v_nfbuild = (root / "server-NeoForge/build.gradle.kts").read_text(encoding="utf-8")
    v_host = host_src
    check("V1 根 build.gradle.kts 1.6.7.11", 'version = "1.6.7.11"' in v_root)
    check("V2 plugin.yml 1.6.7.11", "version: '1.6.7.11'" in v_plugin)
    check("V3 NF build.gradle.kts 1.6.7.11", 'version = "1.6.7.11"' in v_nfbuild)
    check("V4 KERONGNeoForge MOD_VERSION 1.6.7.11", 'MOD_VERSION = "1.6.7.11"' in v_host)

    # ---------------- TO 组：双 toml ----------------
    with zipfile.ZipFile(NF_JAR) as zf:
        tomls = {}
        for toml_name in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
            data = zf.read(toml_name)
            text = data.decode("utf-8")
            tomls[toml_name] = text
            header = next((l.strip() for l in text.splitlines()
                           if l.strip().startswith("[") and not l.strip().startswith("#")), "")
            check(f"TO-1 {toml_name} 段头字节级 [[mods]]", header.encode() == MODS_BYTES,
                  "hex=" + header.encode().hex())
            check(f"TO-2 {toml_name} 非注释行无损坏段头",
                  bytes([0x5B, 0x5B, 0x6F, 0x64, 0x73, 0x5D, 0x5D]) not in non_comment_text(text).encode())
            check(f"TO-3 {toml_name} 版本 1.6.7.11", 'version = "1.6.7.11"' in text)
            try:
                parsed = tomllib.loads(text)
                mods0 = parsed.get("mods", [{}])[0]
                ok = (mods0.get("modId") == "kerongpenguin"
                      and mods0.get("version") == "1.6.7.11"
                      and len(parsed.get("dependencies", {}).get("kerongpenguin", [])) == 2)
                check(f"TO-4 {toml_name} tomllib 解析（modId/version/deps=2）", ok)
            except Exception as e:
                check(f"TO-4 {toml_name} tomllib 解析", False, str(e))
        check("TO-5 双 toml 内容一致", tomls["META-INF/mods.toml"] == tomls["META-INF/neoforge.mods.toml"])

    # ---------------- B 组：基线 ----------------
    with zipfile.ZipFile(NF_JAR) as zf:
        names = zf.namelist()
        dir_entries = [n for n in names if n.endswith("/")]
        check("B1 重定位：cn/kerong/shaded 存在", any(n.startswith("cn/kerong/shaded/") for n in names))
        check("B2 kotlin 类已重定位（顶层 kotlin/*.class 不裸露；kotlin_builtins 资源合法保留）",
              not any(n.startswith("kotlin/") and n.endswith(".class") for n in names))
        check("B3 slf4j 剔除", not any(n.startswith("org/slf4j/") for n in names))
        check("B4 gson 剔除", not any(n.startswith("com/google/gson/") for n in names))
        check("B5 NF 目录条目基线（>=655）", len(dir_entries) >= 655, f"{len(dir_entries)} 个")
        check("B6 NF 条目总量量级", len(names) >= 9000, f"{len(names)} 条")
        check("B7 PanelSyncResult 保持（1.6.7.10 回归）",
              "cn/kerong/bot/MenuManager$PanelSyncResult.class" in names)
        check("B8 PANEL_TRIM_ORDER 保持（1.6.7.10 回归）", b"PANEL_TRIM_ORDER" in menu)

    with zipfile.ZipFile(SPIGOT_JAR) as zs:
        snames = zs.namelist()
        plugin_yml = zs.read("plugin.yml").decode("utf-8")
        sdir = [n for n in snames if n.endswith("/")]
        check("B9 Spigot plugin.yml 1.6.7.11", "version: '1.6.7.11'" in plugin_yml)
        check("B10 Spigot 目录条目基线（>=770）", len(sdir) >= 770, f"{len(sdir)} 个")
        check("B11 Spigot 条目总量量级（>=11000）", len(snames) >= 11000, f"{len(snames)} 条")
        check("B12 无旧包名残留", "cn/huohuas001/" not in snames)
        check("B13 Spigot 端 PanelSyncResult 同步在位",
              "cn/kerong/bot/MenuManager$PanelSyncResult.class" in snames)

    failed = [c for c in checks if not c[1]]
    print()
    print(f"== verify_jar_16711 结果：PASS {len(checks) - len(failed)} / FAIL {len(failed)} ==")
    if failed:
        for name, _, detail in failed:
            print(f"  FAILED: {name} {detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()
