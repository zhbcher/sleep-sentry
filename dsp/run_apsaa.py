"""
真实数据验证 —— APSAA 数据集（Audio-Polygraphy Dataset for Sleep Apnea Analysis）

这是本项目最关键的验证：真实录音 + 临床标注，不是合成数据。
来源：Zenodo record 14096541，CC-BY 4.0，32 人整夜录音与多导睡眠图。

诚实声明（写在最前面，因为下面的数字会被引用）：
  · 录音环境是医院睡眠实验室，不是"手机放床头"，环境噪声条件更好
  · 录音采样率只有 4 kHz（不是手机常见的 16/44.1 kHz），高频信息天然缺失
  · 标注只覆盖到"Obstructive/Mixed Apnea"这类会真正让鼾声中断的事件；
    Hypopnea（气流下降但声音未必停）与 Central Apnea（中枢性）本方法原理上抓不到，
    分开统计，不混进召回率
  · 声学可见的"鼾声中断时刻"与临床按气流定义的 onset 本来就不完全对齐，
    所以匹配容差取 ±10s，并单独报告偏移量分布
"""
import sys, os, io, csv, zipfile, struct, json
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import detector as D

import os
# 数据集不在仓库里（3.7GB）。默认从仓库同级目录找，也可用环境变量覆盖：
#   APSAA_ZIP=/path/to/APSAA.zip python3 run_apsaa.py
# 获取：https://zenodo.org/records/14096541  (CC-BY-4.0)
ZIP = os.environ.get(
    "APSAA_ZIP",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "data", "APSAA.zip")
)

MATCH_TOL_S = 10.0
TARGET_EVENTS = {"Obstructive Apnea", "Mixed Apnea"}
KNOWN_UNDETECTABLE = {"Central Apnea", "Hypopnea"}


def read_wav_header(fh):
    fh.seek(0)
    riff = fh.read(12)
    assert riff[:4] == b"RIFF" and riff[8:12] == b"WAVE", "不是标准 WAV"
    pos = 12
    while pos < 4096:
        head = fh.read(8)
        if len(head) < 8:
            break
        cid = head[:4]
        size = struct.unpack("<I", head[4:8])[0]
        if cid == b"fmt ":
            fmt, ch, sr, br, ba, bits = struct.unpack("<HHIIHH", fh.read(16))
            if cid == b"data":
                pass
            if ch != 1 or bits != 16:
                raise ValueError(f"暂只支持单声道 16bit，实测 ch={ch} bits={bits}")
            return sr, ch, bits
        if cid == b"data":
            fh.seek(-8, 1)
            break
        fh.seek(size + (size & 1), 1)
        pos += 8
    raise ValueError("未找到 fmt chunk")


def iter_pcm(zf, name, chunk_bytes=1 << 20):
    """流式读取，避免把 200MB 的整夜音频读进内存"""
    with zf.open(name) as fh:
        sr, ch, bits = read_wav_header(fh)
        # 定位到 data chunk
        fh.seek(0)
        fh.read(12)
        while True:
            head = fh.read(8)
            if len(head) < 8:
                return
            cid = head[:4]
            size = struct.unpack("<I", head[4:8])[0]
            if cid == b"data":
                break
            fh.seek(size + (size & 1), 1)
        left = size
        while left > 0:
            raw = fh.read(min(chunk_bytes, left))
            if not raw:
                break
            left -= len(raw)
            yield np.frombuffer(raw, dtype="<i2")


def hhmmss_to_sec(t):
    parts = t.strip().split(":")
    return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])


def load_annotations(zf, name):
    rows = []
    txt = zf.read(name).decode("utf-8", "replace")
    for r in csv.DictReader(io.StringIO(txt)):
        et = (r.get("Event_Name") or "").strip()
        if not et:
            continue
        try:
            st = hhmmss_to_sec(r["Start_Time"])
            du = float(r["Duration"])
        except Exception:
            continue
        rows.append((et, st, du))
    return rows


def match(preds, truth, tol):
    """一对一最近匹配。返回 (命中数, 未命中真值, 误报, 偏移列表)"""
    order = sorted(range(len(truth)), key=lambda i: truth[i])
    used = set()
    offsets = []
    for ti in order:
        t0, t1 = truth[ti]
        best, bd = None, 1e9
        for pi, p in enumerate(preds):
            if pi in used:
                continue
            d = abs(p["start"] - t0)
            if d < bd:
                bd, best = d, pi
        if best is not None and bd <= tol:
            used.add(best)
            offsets.append(preds[best]["start"] - t0)
    # 再跑一遍标记哪些真值被命中（两次必须用同样的贪心顺序，否则不可复现）
    used2, matched_idx = set(), set()
    for ti in order:
        t0, _ = truth[ti]
        best, bd = None, 1e9
        for pi, p in enumerate(preds):
            if pi in used2:
                continue
            d = abs(p["start"] - t0)
            if d < bd:
                bd, best = d, pi
        if best is not None and bd <= tol:
            used2.add(best); matched_idx.add(ti)
    fn = [truth[i] for i in range(len(truth)) if i not in matched_idx]
    fp = [preds[i] for i in range(len(preds)) if i not in used2]
    return len(matched_idx), fn, fp, offsets


def main(limit=None):
    zf = zipfile.ZipFile(ZIP)
    sessions = sorted({n.split("/")[0] for n in zf.namelist() if "/" in n})
    if limit:
        sessions = sessions[:limit]
    print(f"共 {len(sessions)} 个受试者夜\n")
    print(f"{'受试者':14s} {'真值':>5s} {'命中':>5s} {'误报':>5s} {'召回':>7s} {'精确':>7s} "
          f"{'AHI真':>7s} {'AHI估':>7s} {'偏移中位':>8s} {'质量':>6s}")

    tot = dict(truth=0, hit=0, fp=0, hyp=0, cen=0, ok=0)
    all_off = []
    rows = []
    for sid in sessions:
        wav = f"{sid}/{sid}.wav"
        ann = f"{sid}/{sid}_Annotations.csv"
        if wav not in zf.namelist() or ann not in zf.namelist():
            continue
        annotations = load_annotations(zf, ann)
        truth = [(st, st + du) for (et, st, du) in annotations if et in TARGET_EVENTS]
        n_hyp = sum(1 for et, _, _ in annotations if et == "Hypopnea")
        n_cen = sum(1 for et, _, _ in annotations if et == "Central Apnea")
        if not truth:
            continue

        elapsed = 0.0
        with zf.open(wav) as fh:
            sr_real, _, _ = read_wav_header(fh)
        # 上界必须随采样率收缩：4kHz 录音的奈奎斯特只有 2kHz
        filt = D.BandFilter(f_lo=100.0, f_hi=min(4000.0, sr_real * 0.45), sr=sr_real)
        det = D.EventDetector(sample_rate=sr_real)
        pending = []
        for pcm in iter_pcm(zf, wav):
            pending.append(pcm)
            if len(pending) >= 40:          # 攒 4 秒再喂，减小调用开销
                blk = np.concatenate(pending)
                pending = []
                f = blk.astype(np.float32) / 32768.0
                det.process_chunk(filt(f), t0_s=elapsed)
                elapsed += len(blk) / sr_real
        if pending:
            blk = np.concatenate(pending)
            f = blk.astype(np.float32) / 32768.0
            det.process_chunk(filt(f), t0_s=elapsed)
            elapsed += len(blk) / sr_real

        events, quality = det.finalize()
        hit, fn, fp, offs = match(events, truth, MATCH_TOL_S)
        rec = hit / len(truth) if truth else 0.0
        prec = hit / len(events) if events else 0.0
        ahi_t = len(truth) / (elapsed / 3600.0)
        ahi_p = len(events) / (elapsed / 3600.0)
        med_off = float(np.median(np.abs(offs))) if offs else float("nan")
        print(f"{sid:14s} {len(truth):5d} {hit:5d} {len(fp):5d} {rec:7.1%} {prec:7.1%} "
              f"{ahi_t:7.1f} {ahi_p:7.1f} {med_off:8.1f} {'OK' if quality['ok'] else 'BAD':>6s}")

        tot["truth"] += len(truth); tot["hit"] += hit; tot["fp"] += len(fp)
        tot["hyp"] += n_hyp; tot["cen"] += n_cen
        tot["ok"] += 1 if quality["ok"] else 0
        all_off += offs
        rows.append(dict(sid=sid, truth=len(truth), hit=hit, fp=len(fp),
                         recall=rec, precision=prec, ahi_true=ahi_t, ahi_pred=ahi_p,
                         quality_ok=bool(quality["ok"]),
                         snr=quality["active_snr_median"], peaks=quality["peak_count"]))

    print("\n" + "=" * 88)
    if tot["truth"]:
        print(f"合并召回  {tot['hit']}/{tot['truth']} = {tot['hit']/tot['truth']:.1%}")
        prec = tot["hit"] / (tot["hit"] + tot["fp"]) if (tot["hit"] + tot["fp"]) else 0
        print(f"合并精确  {tot['hit']}/{tot['hit']+tot['fp']} = {prec:.1%}")
        print(f"质量达标夜数  {tot['ok']}/{len(rows)}")
    if all_off:
        a = np.abs(np.array(all_off))
        print(f"起点偏移(s)  中位 {np.median(a):.1f}  p90 {np.percentile(a,90):.1f}  最大 {a.max():.1f}")
    if rows:
        err = np.array([r["ahi_pred"] - r["ahi_true"] for r in rows])
        print(f"AHI 估算误差  平均 {err.mean():+.1f} 次/小时  "
              f"平均绝对误差 {np.abs(err).mean():.1f}")
    print(f"\n原理上抓不到、故未计入召回的：Hypopnea {tot['hyp']} 次 · Central Apnea {tot['cen']} 次")
    json.dump(rows, open("/tmp/apsaa_result.json", "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(limit=int(sys.argv[1]) if len(sys.argv) > 1 else None)