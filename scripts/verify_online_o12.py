#!/usr/bin/env python3
"""verify_online_o12：v1.7.5.1 部署后外网验证 16 项（官网新口径 + 双 JAR 往返 + 归档下线）。"""
import subprocess


def curl_code(url):
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "30", url],
                       capture_output=True, text=True)
    return r.stdout.strip()


def main():
    r = subprocess.run(["curl", "-s", "-m", "30", "https://kerong.xin/penguin/index.html"],
                       capture_output=True, text=True)
    idx = r.stdout
    checks = []

    def c(name, ok):
        checks.append((name, ok))

    c("OI1 index 含 1.7.5.1 下载链接（双 JAR）",
      "KERONGPenguin_Spigot-1.7.5.1.jar" in idx and "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar" in idx)
    c("OI2 无 1.7.2/1.7.1/1.7.0 下载链接",
      "1.7.2.jar" not in idx and "1.7.1.jar" not in idx and "1.7.0.jar" not in idx)
    c("OI3 更新日志最老为 v1.7.3",
      idx.count('class="version"') == 6 and "v1.7.3<" in idx and "v1.7.2" not in idx)
    c("OI4 无任何源码包链接", "penguin-source" not in idx and "Source-v" not in idx)
    c("OI5 独立源码包字段已删", "独立源码包" not in idx)
    c("OI6 Folia 去多线程区域化描述", "多线程区域化核心" not in idx and "Folia</span>" in idx)
    c("OI7 NeoForge 芯片带 MC 版本标注", "NeoForge（MC 1.20.4，1.20.6，1.21.x，Java 21）" in idx)
    c("OI8 模组版行不再带版本括号", "<b>模组版</b>：" in idx)
    c("OI9 Fabric 主系已测试版本", "主系已测试版本</b>：1.20.4，1.21，1.21.6" in idx)
    c("OI10 Fabric 支持版本行", "支持版本</b>：1.20.4 及以上" in idx)
    c("OI11 Spigot 1.7.5.1 JAR 可下",
      curl_code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.1.jar") == "200")
    c("OI12 NeoForge 1.7.5.1 JAR 可下",
      curl_code("https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar") == "200")
    c("OI13 旧 1.7.2 JAR 已下线(404)",
      curl_code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.2.jar") == "404")
    c("OI14 源码包 URL 已下线(404)",
      curl_code("https://kerong.xin/penguin-source/KERONGPenguin-Fabric-Source-v1.7.4.1.zip") == "404")
    c("OI15 Fabric 1.7.4.1 仍在架（fa 冻结不随主线升版）",
      curl_code("https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.4.1.jar") == "200")
    c("OI16 1.7.5 旧 JAR 仍在架（历史表可达）",
      curl_code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.jar") == "200")

    failed = [x for x in checks if not x[1]]
    for name, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
