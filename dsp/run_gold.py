"""
金标准对账测试：把已知答案的合成音频喂给检测器，逐条比对。

两类指标分开看：
  · 计数指标 —— "数出来的次数对不对"（同一次事件允许几秒对齐误差，用 MATCH_TOL_S）
  · 计时指标 —— "时刻准不准"（验收线 TIME_TOL_S = 0.5s，与方案 v3.0 一致）
"""
import sys, os, json
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth
from detector import EventDetector, biquad_bandpass, SR

MATCH_TOL_S = 3.0      # 判定"是否同一个事件"的对齐容差
TIME_TOL_S = 0.5       # 计时精度验收线
COUNT_ACC_MIN = 0.95   # 计数准确率验收线
RECALL_MIN = 0.90


def match(preds, truth, tol_s=MATCH_TOL_S):
    """按起点最近做一对一匹配"""
    pairs = []
    for ti, tp in enumerate(truth):
        cands = [(abs(p["start"] - tp[0]), pi) for pi, p in enumerate(preds)]
        cands = [c for c in cands if c[0] <= tol_s]
        if cands:
            d, pi = min(cands)
            pairs.append((ti, pi, d))
    used_p = {pi for _, pi, _ in pairs}
    used_t = {ti for ti, _, _ in pairs}
    return pairs, [preds[i] for i in range(len(preds)) if i not in used_p], \
           [truth[i] for i in range(len(truth)) if i not in used_t]


def run_case(duration_s, apnea_count, seed, noise_scale=1.0, tv=3, label="", verbose=True):
    audio, truth = synth.generate(duration_s, apnea_count=apnea_count, seed=seed,
                                  noise_scale=noise_scale, tv_bursts=tv)
    filt = biquad_bandpass(audio)

    det = EventDetector()
    CHUNK = 10 * SR          # 每 10 秒一块，模拟真实流式喂入
    for off in range(0, len(filt), CHUNK):
        det.process_chunk(filt[off:off + CHUNK], t0_s=off / SR)
    events, quality = det.finalize()

    ta = truth["apneas"]
    pairs, extra, miss = match(events, ta)
    n_t, n_p = len(ta), len(events)
    recall = len(pairs) / n_t if n_t else 1.0
    precision = len(pairs) / n_p if n_p else 1.0
    f1 = 2 * recall * precision / (recall + precision) if (recall + precision) else 0.0
    count_acc = 1.0 - min(1.0, abs(n_p - n_t) / max(1, n_t))
    t_err = [d for _, _, d in pairs]
    d_err = [abs(events[pi]["silence"] - ta[ti][1]) for ti, pi, _ in pairs]

    p90 = float(np.percentile(np.array(t_err), 90)) if t_err else 0.0
    med = float(np.median(t_err)) if t_err else 0.0
    ok = recall >= RECALL_MIN and count_acc >= COUNT_ACC_MIN and med <= TIME_TOL_S

    print(f"\n=== {label or f'dur={duration_s} seed={seed}'} ===")
    print(f"  真实事件 {n_t} | 检出 {n_p} | 对上 {len(pairs)}")
    print(f"  召回 {recall:.1%}  精确 {precision:.1%}  F1 {f1:.1%}   计数准确率 {count_acc:.1%}")
    te = np.array(t_err) if t_err else np.zeros(1)
    print(f"  起点误差  中位 {np.median(te):.2f}s  p90 {np.percentile(te,90):.2f}s  最大 {te.max():.2f}s")
    if d_err:
        print(f"  时长误差  中位 {np.median(d_err):.2f}s   最大 {max(d_err):.2f}s")
    print(f"  质量 ok={quality['ok']} 有声SNR中位 {quality['active_snr_median']:.1f}dB "
          f"有声占比 {quality['active_frac']:.1%} 峰数 {quality['peak_count']}")
    if extra:
        print(f"  误报 {len(extra)}: " + ", ".join(f"{e['start']:.0f}s/{e['silence']:.0f}s" for e in extra[:8]))
    if miss:
        print(f"  漏报 {len(miss)}: " + ", ".join(f"{m[0]:.0f}s/{m[1]:.0f}s" for m in miss[:8]))
        if verbose:
            for m in miss[:5]:
                end = m[0] + m[1]
                near = [(t, p) for (t, _d, p) in det.peaks if -2 <= t - end <= 6]
                print(f"     · 真实静默 {m[0]:.0f}-{end:.0f}s，附近峰: "
                      + (", ".join(f"{t:.1f}s/p{p:.0f}" for t, p in near) or "无"))
    print(f"  计时达标(中位<=0.5s): {med:.2f}s | p90 {p90:.2f}s | 最大 {max(t_err) if t_err else 0:.2f}s")
    print(f"  => {'PASS' if ok else 'FAIL'}")
    return {"label": label, "n_truth": n_t, "n_pred": n_p, "recall": recall,
            "precision": precision, "count_acc": count_acc, "ok": ok,
            "max_t_err": float(max(t_err)) if t_err else 0.0,
            "p90_t_err": float(np.percentile(np.array(t_err), 90)) if t_err else 0.0,
            "med_t_err": float(np.median(t_err)) if t_err else 0.0,
            "max_d_err": float(max(d_err)) if d_err else 0.0,
            "quality": quality}


CASES = [
    dict(duration_s=1800, apnea_count=8,  seed=7,  noise_scale=1.0, tv=3, label="基准-安静环境"),
    dict(duration_s=1800, apnea_count=8,  seed=11, noise_scale=1.0, tv=5, label="基准-电视频繁"),
    dict(duration_s=1800, apnea_count=6,  seed=23, noise_scale=3.0, tv=3, label="高噪声环境(3x)"),
    dict(duration_s=1800, apnea_count=12, seed=31, noise_scale=1.0, tv=2, label="高事件密度"),
    dict(duration_s=1800, apnea_count=10, seed=47, noise_scale=0.5, tv=1, label="极安静环境(0.5x)"),
    dict(duration_s=1800, apnea_count=10, seed=59, noise_scale=2.0, tv=6, label="嘈杂+电视(2x,6段)"),
    dict(duration_s=3600, apnea_count=15, seed=71, noise_scale=1.2, tv=4, label="长夜1小时-混合"),
]

if __name__ == "__main__":
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = [c for c in CASES if only is None or only in c["label"]]
    res = [run_case(**c) for c in cases]
    npass = sum(1 for r in res if r["ok"])
    print(f"\n{'='*58}")
    print(f"总计 {npass}/{len(res)} 组通过   |   "
          f"平均召回 {np.mean([r['recall'] for r in res]):.1%}   "
          f"平均计数准确率 {np.mean([r['count_acc'] for r in res]):.1%}   "
          f"最大起点误差 {max(r['max_t_err'] for r in res):.2f}s")
    json.dump(res, open("/tmp/gold_result.json", "w"), ensure_ascii=False, indent=1)
    sys.exit(0 if npass == len(res) else 1)