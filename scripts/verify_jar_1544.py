#!/usr/bin/env python3
"""
KERONGPenguin Spigot fat JAR 门禁（1.5.4.4：插件与模组同步更新 + 背景暗色遮罩）。

缺省验证 build/dist/KERONGPenguin_Spigot-1.5.4.4.jar。
检查项：
  - zip 完整性 / plugin.yml 版本 / 目录条目基线（1.5.4.x 线 = 867）
  - 1.5.4.4 暗色遮罩：InfoCardRenderer 含 darkOverlay 方法，
    旧模糊路径（blurAndDarken / resample / BLUR_SCALE_DIVISOR）零残留
  - Spigot 线不重定位：kotlin/okhttp/fastjson 保持原包名（无模块层无撞包）
  - kloping SDK / 字体 / Markdown / config 资源齐备
  - 无 NeoForge 泄漏（mods.toml / neoforge 兼容层类不得出现）
"""
import os
import re
import sys
import zipfile

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    "/home/z/my-project/penguin-git", "build", "dist", "KERONGPenguin_Spigot-1.5.4.4.jar")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def main() -> None:
    if not os.path.isfile(JAR):
        raise SystemExit(f"JAR 不存在: {JAR}")
    with zipfile.ZipFile(JAR) as zf:
        names = zf.namelist()
        dir_count = sum(1 for n in names if n.endswith("/"))
        check("zip 完整性（testzip）", zf.testzip() is None)

        # 1. 插件元数据
        plugin_yml = zf.read("plugin.yml").decode("utf-8")
        check("plugin.yml 存在", True)
        check("plugin.yml version=1.5.4.4", re.search(
            r"^version:\s*['\"]?1\.5\.4\.4", plugin_yml, re.M) is not None)
        check("plugin.yml api-version=1.18", "api-version: '1.18'" in plugin_yml)

        # 2. 目录条目（kloping SDK 组件扫描依赖；1.5.4.x 线基线 867）
        check("目录条目 = 867（1.5.4.x 基线）", dir_count == 867, f"实际 {dir_count}")
        for d in ("io/github/kloping/", "io/github/kloping/qqbot/", "io/github/kloping/spt/"):
            check(f"kloping 目录条目存在 {d}", d in names)

        # 3. 1.5.4.4 暗色遮罩（背景不再模糊；darkOverlay 在 InfoCardAssets 同文件顶级 object）
        ica = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets.class")
        check("InfoCardAssets 含 darkOverlay 方法", b"darkOverlay" in ica)
        check("InfoCardAssets 无 blurAndDarken 残留", b"blurAndDarken" not in ica)
        check("InfoCardAssets 无 resample 残留", b"resample" not in ica)
        check("InfoCardAssets 无 BLUR_SCALE_DIVISOR 残留", b"BLUR_SCALE_DIVISOR" not in ica)
        olr = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class")
        check("OnlineListRenderer 渲染入口存在", b"measureHeight" in olr)

        # 4. Spigot 线不重定位（无模块层，kotlin 保持原包名）
        check("kotlin 原位未重定位", "kotlin/jvm/internal/Intrinsics.class" in names)
        check("okhttp3 原位未重定位", "okhttp3/OkHttpClient.class" in names)
        check("fastjson 原位未重定位", "com/alibaba/fastjson/JSON.class" in names)
        check("无 shaded 前缀字节码", not any(
            n.startswith("cn/huohuas001/shaded/") for n in names))

        # 5. 关键业务类
        for entry in (
            "cn/huohuas001/bot/QClient.class",
            "cn/huohuas001/bot/HuHoBot.class",
            "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/CardRenderPool.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/QueryInfoService.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class",
            "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class",
        ):
            check(f"存在 {entry.split('/')[-1]}", entry in names)

        # 6. 资源
        for entry in (
            "config.yml",
            "fonts/NotoSansSC-Regular-subset.ttf",
            "fonts/NotoSansSC-Bold-subset.ttf",
            "Markdown/online.md",
        ):
            check(f"资源 {entry}", entry in names)

        # 7. 无 NeoForge 泄漏
        check("无 neoforge.mods.toml", "META-INF/neoforge.mods.toml" not in names)
        check("无 org/bukkit shim 泄漏（真 Spigot API 场景）", not any(
            n.startswith("org/bukkit/entity/NeoPlayer") for n in names))
        check("无 server-NeoForge kotlin_module", "META-INF/server-NeoForge.kotlin_module" not in names)

        # 8. 签名与模块描述（META-INF/versions/9|11 多版本 module-info 为基线自带，
        #    与 1.5.4.2 发布包一致，Spigot 类加载路径无害；仅禁止根级 module-info）
        check("无签名文件 (.SF/.RSA/.DSA)", not any(
            n.startswith("META-INF/") and n.rsplit(".", 1)[-1] in {"SF", "RSA", "DSA"} for n in names))
        check("无根级 module-info.class", not any(
            n == "module-info.class" or n.endswith("/module-info.class") and not n.startswith("META-INF/versions/") for n in names))

    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("=== Spigot fat JAR 全部验证通过 ===")


if __name__ == "__main__":
    main()
