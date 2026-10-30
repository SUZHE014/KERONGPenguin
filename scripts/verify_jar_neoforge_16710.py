#!/usr/bin/env python3
"""verify_jar_neoforge_16710 —— 1.6.7.10 NeoForge 产物门禁。

重点：
  NF1  mods.toml / neoforge.mods.toml 段头字节级为 [[mods]]（0x5b5b6d6f64735d5d）
  NF2  双 toml 版本 1.6.7.10
  NF3  mods.toml 可被 tomllib 解析且 mods[0].modId = kerongpenguin
  NF4  NeoForgeCommands 异步反馈 + 如实反馈字符串
  NF5  面板修复类与字符串（同 Spigot 线 PM 组核心项）
  NF6  重定位基线：cn/kerong/shaded 存在、原始包 kotlin/ 不裸露
  NF7  目录条目存在（SDK 组件扫描）
"""
import io
import sys
import tomllib
import zipfile

NF_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.7.10.jar"

checks = []


def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    suffix = f" — {detail}" if detail else ""
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}{suffix}")


MODS_BYTES = bytes([0x5B, 0x5B, 0x6D, 0x6F, 0x64, 0x73, 0x5D, 0x5D])  # [[mods]]
BROKEN_BYTES = bytes([0x5B, 0x5B, 0x6F, 0x64, 0x73, 0x5D, 0x5D])  # [[ods]]


def main():
    print(f"== verify_jar_neoforge_16710: {NF_JAR} ==")
    with zipfile.ZipFile(NF_JAR) as zf:
        names = zf.namelist()

        for toml_name in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
            data = zf.read(toml_name)
            text = data.decode("utf-8")
            header_line = next(
                (l for l in text.splitlines() if l.strip().startswith("[") and "mods" in l and not l.strip().startswith("#")),
                "",
            )
            check(f"NF1 {toml_name} 段头字节级 [[mods]]", header_line.strip().encode() == MODS_BYTES,
                  "bytes=" + header_line.strip().encode().hex())
            check(f"NF1b {toml_name} 无损坏段头", BROKEN_BYTES not in b"".join(
                l.encode() for l in text.splitlines() if not l.strip().startswith("#")))
            check(f"NF2 {toml_name} 版本 1.6.7.10", 'version = "1.6.7.10"' in text)
            try:
                parsed = tomllib.loads(text)
                ok = parsed.get("mods", [{}])[0].get("modId") == "kerongpenguin" and \
                    parsed.get("mods", [{}])[0].get("version") == "1.6.7.10" and \
                    len(parsed.get("dependencies", {}).get("kerongpenguin", [])) == 2
                check(f"NF3 {toml_name} tomllib 解析", ok)
            except Exception as e:
                check(f"NF3 {toml_name} tomllib 解析", False, str(e))

        nfc = zf.read("cn/kerong/penguin/neoforge/NeoForgeCommands.class")
        check("NF4 NeoForgeCommands 异步提示", "异步执行，完成后会反馈结果".encode() in nfc)
        check("NF4b NeoForgeCommands 如实反馈", "快捷面板同步失败：".encode() in nfc)
        check("NF4c 旧无条件成功文案移除", "已重新同步 QQ 快捷指令面板。".encode() not in nfc)

        menu = zf.read("cn/kerong/bot/MenuManager.class")
        menu_r = zf.read("cn/kerong/bot/MenuManager$PanelSyncResult.class")
        check("NF5a 面板裁剪日志", "个超过平台上限".encode() in menu)
        check("NF5b PanelSyncResult 存在", len(menu_r) > 0)
        check("NF5c 退避重试", "ms 后自动清空全部面板重建".encode() in menu)
        check("NF5d QPM 节流", b"java/util/concurrent/atomic/AtomicLong" in menu)
        check("NF5e 分页", b"next_cursor" in menu)

        check("NF6 重定位基线", any(n.startswith("cn/kerong/shaded/") for n in names))
        top_level_kotlin = [n for n in names if n.startswith("kotlin/") and n.endswith(".class")]
        check("NF6b kotlin 类已重定位（不裸露）", len(top_level_kotlin) == 0, f"{len(top_level_kotlin)} 个")
        check("NF6c slf4j 剔除", not any(n.startswith("org/slf4j/") for n in names))
        check("NF6d gson 剔除", not any(n.startswith("com/google/gson/") for n in names))

        dir_entries = [n for n in names if n.endswith("/")]
        # 基线：1.6.7.9 NF JAR 为 661 个目录条目（重定位+剔除后天然少于 Spigot 线）
        check("NF7 目录条目存在（基线 661）", len(dir_entries) >= 655, f"{len(dir_entries)} 个")

    failed = [c for c in checks if not c[1]]
    print(f"\n== 结果：{len(checks) - len(failed)}/{len(checks)} PASS ==")
    if failed:
        for name, _, _ in failed:
            print(f"  FAILED: {name}")
        sys.exit(1)


if __name__ == "__main__":
    main()
