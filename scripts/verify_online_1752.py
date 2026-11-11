#!/usr/bin/env python3
"""verify_online_1752：1.7.5.2 部署后外网验证。

① 双 JAR 公网下载 md5 与本地构建往返一致
② version.json 公网可读：版本 1.7.5.2 + 六条 changelog（含 ④⑤⑥）
③ index.html 公网含 1.7.5.2 六项更新点与配置版本 44 口径
④ tutorial.html 公网 kick-message 新口径（绑定码 + 单行写法）
⑤ 旧 1.7.5.1 JAR 仍在架（历史版本可下）
"""
import hashlib
import json
import urllib.request

BASE = "https://kerong.xin/penguin"
LOCAL_SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.7.5.2.jar"
LOCAL_NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar"

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def fetch(url, binary=False):
    req = urllib.request.Request(url, headers={"User-Agent": "verify-1752"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    return data if binary else data.decode("utf-8", "replace")


def md5b(data):
    return hashlib.md5(data).hexdigest()


def md5f(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    print("== ① 双 JAR 公网 md5 往返 ==")
    s = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.2.jar", binary=True)
    check("1a Spigot JAR 下载成功（%d 字节）" % len(s), len(s) > 19_000_000)
    check("1b Spigot md5 与本地一致", md5b(s) == md5f(LOCAL_SPIGOT), md5b(s))
    n = fetch(f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar", binary=True)
    check("1c NeoForge JAR 下载成功（%d 字节）" % len(n), len(n) > 18_000_000)
    check("1d NeoForge md5 与本地一致", md5b(n) == md5f(LOCAL_NEOFORGE), md5b(n))

    print("== ② version.json ==")
    vj = json.loads(fetch(f"{BASE}/version.json"))
    check("2a 版本号 1.7.5.2", vj.get("version") == "1.7.5.2")
    cl = vj.get("changelog", [])
    check("2b 六条 changelog", len(cl) == 6, str(len(cl)))
    check("2c 含 ④⑤⑥ 新条目",
          any("绑定码」口径多行文案" in x for x in cl)
          and any("自动检测服务器自带白名单" in x for x in cl)
          and any("单行写法" in x for x in cl))

    print("== ③ index.html ==")
    idx = fetch(f"{BASE}/index.html")
    check("3a v1.7.5.2 标题在架", "v1.7.5.2" in idx)
    check("3b 六项更新点齐备",
          "菜单模板去掉强制自动更新" in idx
          and "第三方连接失败" in idx
          and "管理员撤权后按钮权限仍保留 30 分钟" in idx
          and "QQ 绑定拦截默认提示换新" in idx
          and "自动检测并关闭服务器自带白名单" in idx
          and "QQ 绑定拦截文案支持单行写法" in idx)
    check("3c 配置版本 44 口径", "配置版本 43 → 44" in idx)

    print("== ④ tutorial.html ==")
    tut = fetch(f"{BASE}/tutorial.html")
    check("4a kick-message 绑定码口径", "你的专属绑定码: &a&l{code}" in tut)
    check("4b 单行写法说明", "也可写成一行用" in tut and "\\n" in tut)

    print("== ⑤ 历史版本在架 ==")
    try:
        old = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.1.jar", binary=True)
        check("5a 1.7.5.1 JAR 仍可下（%d 字节）" % len(old), len(old) > 19_000_000)
    except Exception as e:
        check("5a 1.7.5.1 JAR 仍可下", False, str(e))

    failed = [n for n, ok in checks if not ok]
    print(f"\n共 {len(checks)} 项，通过 {len(checks)-len(failed)}，失败 {len(failed)}")
    for n in failed:
        print("  -", n)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
