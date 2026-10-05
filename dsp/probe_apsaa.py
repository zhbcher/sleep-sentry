"""
APSAA 数据集探查 —— 先摸清目录结构与标注格式，再谈跑指标。
不做任何假设，看到什么报什么。
"""
import zipfile, sys, collections, io, json

import os
# 数据集不在仓库里（3.7GB）。默认从仓库同级目录找，也可用环境变量覆盖：
#   APSAA_ZIP=/path/to/APSAA.zip python3 run_apsaa.py
# 获取：https://zenodo.org/records/14096541  (CC-BY-4.0)
ZIP = os.environ.get(
    "APSAA_ZIP",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "data", "APSAA.zip")
)

def main():
    with zipfile.ZipFile(ZIP) as z:
        names = z.namelist()
        print(f"共 {len(names)} 个条目\n")
        byext = collections.Counter()
        for n in names:
            ext = n.rsplit(".", 1)[-1].lower() if "." in n.rsplit("/", 1)[-1] else "(无扩展名)"
            byext[ext] += 1
        print("按扩展名:")
        for e, c in byext.most_common(20):
            print(f"  {e:12s} {c}")

        print("\n前 40 个条目:")
        for n in names[:40]:
            print("  ", n)

        # 顶层目录
        tops = collections.Counter(n.split("/")[0] for n in names)
        print("\n顶层:", dict(tops))

        # 找一个可能的说明文件
        for cand in ("README", "readme.txt", "README.txt", "README.md"):
            hits = [n for n in names if n.lower().endswith(cand.lower())]
            if hits:
                print(f"\n=== {hits[0]} ===")
                try:
                    print(z.read(hits[0]).decode("utf-8", "replace")[:3000])
                except Exception as e:
                    print("读取失败:", e)
                break

        # 各类文件的样例大小
        print("\n各类样本大小:")
        seen = set()
        for i in sorted(z.infolist(), key=lambda x: x.filename):
            ext = i.filename.rsplit(".", 1)[-1].lower() if "." in i.filename else "?"
            if ext in seen:
                continue
            seen.add(ext)
            print(f"  {i.filename}  {i.file_size/1e6:.2f} MB")

if __name__ == "__main__":
    main()