"""
阈值扫描 —— 在真实录音上找最佳工作点。

背景：合成数据里打鼾比底噪高 20dB+，而 APSAA 真实录音里只有 ~3.6dB vs 1.4dB。
按合成数据调出来的 SNR_ON=8 用在真实录音上，等于"整夜都认为房间是安静的"，
于是任何噪声起伏都被当成呼吸，造出几百个假事件（实测精确率 2.9%）。

这个脚本回答两个问题：
  1. 把阈值调进真实分布，方法的召回/精确能到什么水平？
  2. 最优工作点是"宁可漏不可错"还是"宁可错不可漏"？产品上该怎么选？

做法：每晚只做一次带通滤波并缓存在内存（7h×4kHz×float32 ≈ 400MB），
然后在同一份缓存上扫全部阈值 —— 避免每个阈值都重读 200MB 的 WAV。
"""
import sys, os, zipfile
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import detector as D
from run_apsaa import (iter_pcm, read_wav_header, load_annotations,
                       match, MATCH_TOL_S, TARGET_EVENTS)

import os
# 数据集不在仓库里（3.7GB）。默认从仓库同级目录找，也可用环境变量覆盖：
#   APSAA_ZIP=/path/to/APSAA.zip python3 run_apsaa.py
# 获取：https://zenodo.org/records/14096541  (CC-BY-4.0)
ZIP = os.environ.get(
    "APSAA_ZIP",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "data", "APSAA.zip")
)


def load_filtered(zf, sid):
    """读 WAV → 带通 → 返回 float32 信号与采样率"""
    wav = f"{sid}/{sid}.wav"
    with zf.open(wav) as fh:
        sr, _, _ = read_wav_header(fh)
    filt = D.BandFilter(f_lo=100.0, f_hi=min(4000.0, sr * 0.45), sr=sr)
    out = []
    pending = []
    for pcm in iter_pcm(zf, wav):
        pending.append(pcm)
        if len(pending) >= 80:            # 攒 8 秒，减小滤波函数调用开销
            blk = np.concatenate(pending)
            pending = []
            out.append(filt(blk.astype(np.float32) / 32768.0))
    if pending:
        out.append(filt(np.concatenate(pending).astype(np.float32) / 32768.0))
    return np.concatenate(out), sr


def detect(sig, sr, **kw):
    det = D.EventDetector(sample_rate=sr, **kw)
    CH = 40 * sr                          # 每 40 秒一块
    t0 = 0.0
    for off in range(0, len(sig), CH):
        det.process_chunk(sig[off:off + CH], t0_s=t0)
        t0 += CH / sr
    return det.finalize()


def main(n_sessions=6,
         snr_on_list=(1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0),
         hysteresis=2.5):
    zf = zipfile.ZipFile(ZIP)
    sids = sorted({n.split("/")[0] for n in zf.namelist() if "/" in n})

    sessions = []
    for sid in sids:
        ann = load_annotations(zf, f"{sid}/{sid}_Annotations.csv")
        truth = [(st, st + d) for (e, st, d) in ann if e in TARGET_EVENTS]
        if len(truth) < 3:
            continue
        sig, sr = load_filtered(zf, sid)
        sessions.append((sid, sig, sr, len(sig) / sr, truth))
        print(f"  {sid}: {len(sig)/sr/3600:.1f}h  真值 {len(truth)} 次  "
              f"信号 RMS p50 {20*np.log10(np.sqrt((sig.astype(np.float64)**2).mean())+1e-12):.1f}dB  "
              f"峰值 {20*np.log10(np.abs(sig).max()):.1f}dB")
        if len(sessions) >= n_sessions:
            break

    print(f"\n{'SNR_ON':>7s} {'SNR_OFF':>8s} {'召回':>8s} {'精确':>8s} {'F1':>7s} "
          f"{'真值':>5s} {'命中':>5s} {'误报':>6s} {'AHI误差':>9s} {'质量OK':>7s}")
    rows = []
    for on in snr_on_list:
        off = on - hysteresis
        T = H = FP = OK = 0
        ahi_err = []
        for sid, sig, sr, dur, truth in sessions:
            ev, q = detect(sig, sr, SNR_ON_DB=on, SNR_OFF_DB=off)
            hit, fn, fp, offs = match(ev, truth, MATCH_TOL_S)
            T += len(truth); H += hit; FP += len(fp); OK += 1 if q["ok"] else 0
            ahi_err.append((len(ev) - len(truth)) / (dur / 3600.0))
        rec = H / T if T else 0
        prec = H / (H + FP) if (H + FP) else 0
        f1 = 2 * rec * prec / (rec + prec) if (rec + prec) else 0
        rows.append((on, off, rec, prec, f1, T, H, FP, float(np.mean(ahi_err)), OK))
        print(f"{on:7.1f} {off:8.1f} {rec:8.1%} {prec:8.1%} {f1:7.1%} "
              f"{T:5d} {H:5d} {FP:6d} {np.mean(ahi_err):+9.1f} {OK:3d}/{len(sessions)}")

    best = max(rows, key=lambda r: r[4])
    print(f"\n最佳 F1：SNR_ON={best[0]} / OFF={best[1]}  "
          f"召回 {best[2]:.1%}  精确 {best[3]:.1%}  F1 {best[4]:.1%}")
    for op in sorted(rows, key=lambda r: -r[2])[:3]:
        print(f"  高召回工作点 SNR_ON={op[0]}: 召回 {op[2]:.1%} 精确 {op[3]:.1%} F1 {op[4]:.1%}")


if __name__ == "__main__":
    main(n_sessions=int(sys.argv[1]) if len(sys.argv) > 1 else 6)