#!/usr/bin/env python3
"""
生成 1.20.1 的三列合并映射(mojang/official/intermediary)+ 继承错位补丁
—— Fa 版 1.20.1 支持(1.7.5.6 起)。

输入材料(本地 fabric-smoke/;缺失时从部署服务器 /root/fabric-deps/1201/ 拉取):
  - client-1.20.1.txt          Mojang 官方 ProGuard 映射(8.0MB)
  - intermediary-1.20.1-v2.jar Fabric intermediary 映射

输出:scripts/deps-work/fabric-libs/merged-1201.tiny(幂等;已有且新于材料则跳过)

补丁(合并完成后追加,幂等):
  tiny-remapper 按**编译期 classpath(1.20.4 mojmap)的继承链**解析方法定义类,
  而 1.20.1 的类层次不同(1.20.2 才引入 ServerCommonPacketListenerImpl=class_8609,
  1.20.1 中 ServerGamePacketListenerImpl=class_3244 自己定义 disconnect(Component))。
  mod 的踢人调用(ServerGamePacketListenerImpl.disconnect)经 1.20.4 继承解析定位到
  class_8609,再在 1.20.1 映射的 class_8609 节查不到行 → 漏映射 → 运行时
  NoSuchMethodError。补丁:在 class_8609 节(不存在则造)补 disconnect 行,
  使其在 1.20.1 remap 时命中 class_3244 的 method_14367(1.20.1 本类定义,
  invokevirtual 运行时继承解析成立)。
"""
import argparse
import os
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "scripts" / "deps-work" / "fabric-libs"
TOOLS = REPO / "scripts" / "fabric-tools"
OUT = WORK / "merged-1201.tiny"

# 材料位置(优先本地 fabric-smoke,其次经 paramiko 从服务器拉)
LOCAL_DIR = Path("/home/z/my-project/fabric-smoke")
CLIENT_TXT = "client-1.20.1.txt"
INTER_JAR = "intermediary-1.20.1-v2.jar"
MAPPING_IO_JAR = Path("/home/z/my-project/fabric-deps/mapping-io-0.7.1.jar")

# 继承错位补丁:class_8609 节补 disconnect(挂 class_3244 的 method_14367)
PATCH_CLASS_MOJANG = "net/minecraft/server/network/ServerCommonPacketListenerImpl"
PATCH_CLASS_INT = "net/minecraft/class_8609"
PATCH_LINES = [
    "\tm\t(Lnet/minecraft/network/chat/Component;)V\tdisconnect\tb\tmethod_14367\n",
]


def find_java() -> str:
    java = subprocess.run(["which", "java"], capture_output=True, text=True).stdout.strip()
    if java:
        return java
    cand = Path.home().glob(".gradle/jdks/*/bin/java")
    for c in sorted(cand):
        return str(c)
    raise SystemExit("找不到 java 运行时")


def fetch_materials(materials: Path) -> None:
    """本地缺材料时从服务器 /root/fabric-deps/1201/ 拉取。"""
    import paramiko
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248"), 22,
              "root", os.environ.get("PENGUIN_SSH_PASS", ""), timeout=30)
    try:
        sftp = c.open_sftp()
        for name in (CLIENT_TXT, INTER_JAR):
            local = materials / name
            if local.is_file() and local.stat().st_size > 1000:
                continue
            try:
                sftp.get(f"/root/fabric-deps/1201/{name}", str(local))
                print(f"[拉取] /root/fabric-deps/1201/{name} -> {local}")
            except Exception as e:
                raise SystemExit(f"服务器也缺材料 /root/fabric-deps/1201/{name}: {e}")
    finally:
        c.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 1.20.1 合并映射 merged-1201.tiny")
    parser.add_argument("--materials", default=str(LOCAL_DIR))
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    materials = Path(args.materials)
    WORK.mkdir(parents=True, exist_ok=True)

    client = materials / CLIENT_TXT
    inter = materials / INTER_JAR
    if not (client.is_file() and inter.is_file()):
        print("本地缺材料,尝试从服务器 /root/fabric-deps/1201/ 拉取…")
        fetch_materials(materials)

    def has_patch(lines):
        """补丁行是否已在 class_8609 节内。"""
        for i, line in enumerate(lines):
            if line.startswith("c\t") and line.rstrip("\n").split("\t")[-1] == PATCH_CLASS_INT:
                for j in range(i + 1, min(i + 400, len(lines))):
                    if lines[j].startswith("c\t"):
                        break
                    if "method_14367" in lines[j] and "disconnect" in lines[j]:
                        return True
                return False
        return False

    def apply_patch(lines):
        """插入补丁行,返回新 lines 或 None(找不到插入点)。
        ⚠️ class_8609 节不存在时,假类行必须插在 class_3244 节的**末尾**(下一个类行之前),
        不能插在类行之后——那会把原 class_3244 的成员行全部划入假节,导致
        player 等字段查表失败(1.7.5.6 首轮门禁 C3 抓出过此 bug)。"""
        for i, line in enumerate(lines):
            if line.startswith("c\t") and line.rstrip("\n").split("\t")[-1] == PATCH_CLASS_INT:
                out = list(lines)
                for k, pl in enumerate(PATCH_LINES):
                    out.insert(i + 1 + k, pl)
                return out
        for i, line in enumerate(lines):
            if line.startswith("c\t") and line.rstrip("\n").split("\t")[-1] == "net/minecraft/class_3244":
                # 找 class_3244 节的结束(下一个类行)——插在其前,不切断原节
                j = i + 1
                while j < len(lines) and not lines[j].startswith("c\t"):
                    j += 1
                out = list(lines)
                out.insert(j, f"c\t{PATCH_CLASS_MOJANG}\tzz\t{PATCH_CLASS_INT}\n")
                for k, pl in enumerate(PATCH_LINES):
                    out.insert(j + 1 + k, pl)
                return out
        return None

    if OUT.is_file() and not args.force:
        lines = OUT.read_text(encoding="utf-8", errors="ignore").splitlines(keepends=True)
        if has_patch(lines):
            print(f"[跳过] {OUT} 已存在且含补丁")
            return 0
        patched = apply_patch(lines)
        if patched:
            OUT.write_text("".join(patched), encoding="utf-8")
            print(f"[补丁] {OUT} 追加继承错位补丁")
            return 0
        raise SystemExit("已有 merged-1201.tiny 但无法定位补丁插入点")

    # 1. 解出 1.20.1 intermediary tiny
    tiny1201 = WORK / "mappings-1201.tiny"
    with zipfile.ZipFile(inter) as z:
        tiny1201.write_bytes(z.read("mappings/mappings.tiny"))

    # 2. MergeMappings 合并
    subprocess.run(
        [find_java(), "-cp", f"{TOOLS}:{MAPPING_IO_JAR}", "MergeMappings",
         str(tiny1201), str(client), str(OUT)],
        check=True, timeout=600)
    print(f"[合并] {OUT}")

    # 3. 继承错位补丁
    lines = OUT.read_text(encoding="utf-8").splitlines(keepends=True)
    if not has_patch(lines):
        patched = apply_patch(lines)
        if not patched:
            raise SystemExit("找不到 class_3244/class_8609 类行,无法打补丁")
        OUT.write_text("".join(patched), encoding="utf-8")
        print(f"[补丁] class_8609 节补 disconnect→method_14367(继承错位修正)")
    print(f"[完成] {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
