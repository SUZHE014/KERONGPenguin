#!/usr/bin/env python3
"""1.7.6.5 覆盖轮官网三件更新(幂等):
- version.json:changelog 追加 ⑬(查性能内存占用含预留口径)
- index.html:v1.7.6.5 块追加 ⑬ 行;发布日期 2026-11-17 → 2026-11-18
  (主下载框 + 历史表四行 + 版本块 date;「已于 2026-11-17 归档」为历史
   事件日期,精确锚点替换不会误伤)
- tutorial.html:/查性能 命令行补口径;「内存占用说明」段补一句
复跑规则:每处先探测目标态已达成则 SKIP。
"""
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(REPO, "website")

ENTRY_13 = ("⑬ 查性能内存占用改为含预留口径：按 JVM 已提交堆显示，与 MC 面板上显示的"
             "内存占用一致（JVM 预留未用部分也计入）；/hbkrg mem 诊断仍可查看插件实际存活占用")

LI_13 = ("<li>⑬ 查性能内存占用改为含预留口径：按 JVM 已提交堆显示，与 MC 面板上显示的"
         "内存占用一致（JVM 预留未用部分也计入）；/hbkrg mem 仍可查看插件实际占用</li>")


def patch(path, old, new, tag, idem_old, idem_new):
    """文本补丁:目标态(idem_new)已达成 → SKIP;前置态(idem_old)在 → 改;
    两者都不在 → 报 ERR(锚点被破坏)。"""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if idem_new in text:
        print(f"  SKIP {tag} (已是目标态)")
        return True
    if idem_old not in text:
        print(f"  ERR  {tag} (前置锚点缺失)")
        return False
    if old not in text:
        print(f"  ERR  {tag} (替换段缺失)")
        return False
    text = text.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"  DONE {tag}")
    return True


def main():
    ok = True

    # ---- 1. version.json ----
    vj = os.path.join(WEB, "version.json")
    with open(vj, encoding="utf-8") as f:
        data = json.load(f)
    if any("⑬" in c for c in data["changelog"]):
        print("  SKIP version.json ⑬ (已是目标态)")
    else:
        assert data["version"] == "1.7.6.5", f"版本号异常: {data['version']}"
        last = data["changelog"][-1]
        assert last.startswith("⑫"), f"末条不是⑫: {last[:20]}"
        data["changelog"].append(ENTRY_13)
        with open(vj, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("  DONE version.json ⑬ (12 → 13 条)")
    # 结构断言:纯字符串数组
    with open(vj, encoding="utf-8") as f:
        chk = json.load(f)
    assert all(isinstance(c, str) for c in chk["changelog"]), "changelog 出现非字符串条目"
    assert len(chk["changelog"]) == 13, f"changelog 条数 {len(chk['changelog'])} != 13"
    print(f"  OK   version.json 结构: 13 条纯字符串")

    # ---- 2. index.html ----
    idx = os.path.join(WEB, "index.html")
    # 2a. ⑬ 行(锚:⑫ 行,块的最后一个 li)
    ok &= patch(
        idx,
        "<li>⑫ config-version 60 → 63（升级自动补新键与注释，已有配置值不动）</li>",
        "<li>⑫ config-version 60 → 63（升级自动补新键与注释，已有配置值不动）</li>\n" + LI_13,
        "index ⑬ 行",
        idem_old="<li>⑫ config-version 60 → 63",
        idem_new="⑬ 查性能内存占用改为含预留口径",
    )
    # 2b. 发布日期 11-17 → 11-18(精确锚,不误伤「已于 2026-11-17 归档」)
    for anchor, new, tag in [
        (">发布于 2026-11-17</p>", ">发布于 2026-11-18</p>", "index 主下载框日期"),
        ('<h3>v1.7.6.5</h3><span class="date">2026-11-17</span>',
         '<h3>v1.7.6.5</h3><span class="date">2026-11-18</span>', "index 版本块日期"),
    ]:
        ok &= patch(idx, anchor, new, tag, idem_old=anchor, idem_new=new)
    # 历史表四行同锚:replace 一次性全量(旧形态计数=0 且新形态计数=4 → SKIP)
    with open(idx, encoding="utf-8") as f:
        text = f.read()
    old_cnt = text.count("1.7.6.5</td><td>2026-11-17</td>")
    new_cnt = text.count("1.7.6.5</td><td>2026-11-18</td>")
    if old_cnt == 0 and new_cnt == 4:
        print("  SKIP index 历史表日期(四行已是目标态)")
    elif old_cnt == 4 and new_cnt == 0:
        text = text.replace("1.7.6.5</td><td>2026-11-17</td>", "1.7.6.5</td><td>2026-11-18</td>")
        with open(idx, "w", encoding="utf-8") as f:
            f.write(text)
        print("  DONE index 历史表日期(四行)")
    else:
        # 上轮脚本 replace(count=1) 只改了一行的中间态:剩余 old 全量补齐
        if old_cnt > 0:
            text = text.replace("1.7.6.5</td><td>2026-11-17</td>", "1.7.6.5</td><td>2026-11-18</td>")
            with open(idx, "w", encoding="utf-8") as f:
                f.write(text)
            print(f"  FIX  index 历史表日期(补齐 {old_cnt} 行)")
            ok &= True
        else:
            print(f"  ERR  index 历史表日期(old={old_cnt} new={new_cnt} 非预期)")
            ok = False

    # ---- 3. tutorial.html ----
    tut = os.path.join(WEB, "tutorial.html")
    # 3a. 命令表查性能行
    ok &= patch(
        tut,
        "<tr><td><code>/查性能</code></td><td>服务器性能卡片：TPS、MSPT、内存、CPU 与右上角综合状态</td></tr>",
        "<tr><td><code>/查性能</code></td><td>服务器性能卡片：TPS、MSPT、内存（1.7.6.5 起为含预留口径，与 MC 面板显示一致）、CPU 与右上角综合状态</td></tr>",
        "tutorial 查性能行",
        idem_old="<code>/查性能</code></td><td>服务器性能卡片：TPS、MSPT、内存、CPU",
        idem_new="含预留口径，与 MC 面板显示一致",
    )
    # 3b. 内存占用说明段
    ok &= patch(
        tut,
        "<p>面板显示的是 JVM 预留量，不代表插件占用。游戏内发送 <code>/hbkrg mem</code> 可查看插件实际占用；插件在渲染空闲后会自动释放图像素材缓存，一般无需处理。</p>",
        "<p>面板显示的是 JVM 预留量，不代表插件占用。1.7.6.5 起查性能卡的「内存占用（含预留）」即为该口径（JVM 已提交堆），与面板显示一致；插件实际存活占用仍以 <code>/hbkrg mem</code> 为准。插件在渲染空闲后会自动释放图像素材缓存，一般无需处理。</p>",
        "tutorial 内存说明段",
        idem_old="面板显示的是 JVM 预留量，不代表插件占用。游戏内发送",
        idem_new="「内存占用（含预留）」即为该口径",
    )

    print("\n===== web_1765b:", "OK" if ok else "有 ERR", "=====")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
