"""Strict export validation and reproducible WER / RTF / timing diagnostics. Python 3.11+."""
import argparse
import functools
import json
import math
import re
from pathlib import Path

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate key: {key}")
        result[key] = value
    return result

def read_transcript(path, duration_ms):
    obj = json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique_object,
                     parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    if type(obj) is not dict or set(obj) != {"transcript"} or type(obj["transcript"]) is not list:
        raise ValueError("Expected exactly {transcript: [...]} ")
    previous = -1
    for item in obj["transcript"]:
        if type(item) is not dict or set(item) != {"speaker_id", "start_ms", "end_ms", "text"}:
            raise ValueError("Wrong utterance fields")
        if type(item["speaker_id"]) is not str or not re.fullmatch(r"speaker_[1-9][0-9]*", item["speaker_id"]):
            raise ValueError("Invalid speaker ID")
        start, end = item["start_ms"], item["end_ms"]
        if type(start) is not int or type(end) is not int or not 0 <= start < end <= duration_ms:
            raise ValueError("Invalid timestamps")
        if start < previous:
            raise ValueError("Unsorted transcript")
        previous = start
        if type(item["text"]) is not str or not item["text"].strip():
            raise ValueError("Empty/non-string text")
    return obj["transcript"]

def words(text):
    # Same rule for hypothesis and reference: casefold, ё->е, letters/numbers only.
    # Numbers are NOT expanded: 15 and пятнадцать remain different tokens.
    return re.findall(r"[^\W_]+", text.casefold().replace("ё", "е"), re.UNICODE)

def wer(reference, hypothesis):
    # Cells: edit cost, substitutions, deletions, insertions.
    prev = [(j, 0, 0, j) for j in range(len(hypothesis) + 1)]
    for i, ref in enumerate(reference, 1):
        row = [(i, 0, i, 0)]
        for j, hyp in enumerate(hypothesis, 1):
            c, s, d, ins = prev[j - 1]
            sub = (c + (ref != hyp), s + (ref != hyp), d, ins)
            c, s, d, ins = prev[j]
            delete = (c + 1, s, d + 1, ins)
            c, s, d, ins = row[-1]
            insert = (c + 1, s, d, ins + 1)
            row.append(min((sub, delete, insert), key=lambda v: v[0]))
        prev = row
    c, s, d, i = prev[-1]
    return {"S": s, "D": d, "I": i, "N": len(reference),
            "wer": c / len(reference) if reference else (0.0 if not hypothesis else None)}

def overlap(a, b):
    return max(0, min(a["end_ms"], b["end_ms"]) - max(a["start_ms"], b["start_ms"]))

def speaker_mapping(ref, hyp):
    rs = sorted({x["speaker_id"] for x in ref})
    hs = sorted({x["speaker_id"] for x in hyp})
    if len(rs) > 12 or len(hs) > 12:
        raise ValueError("This diagnostic matcher supports up to 12 speakers")
    weights = [[sum(overlap(r, h) for r in ref for h in hyp
                    if r["speaker_id"] == rid and h["speaker_id"] == hid) for rid in rs] for hid in hs]
    @functools.lru_cache(None)
    def match(i, mask):
        if i == len(hs):
            return 0, ()
        score, tail = match(i + 1, mask)
        best = score, (None,) + tail
        for j in range(len(rs)):
            if not mask & (1 << j) and weights[i][j] > 0:
                score, tail = match(i + 1, mask | (1 << j))
                candidate = score + weights[i][j], (rs[j],) + tail
                if candidate[0] > best[0]:
                    best = candidate
        return best
    return dict(zip(hs, match(0, 0)[1]))

def timing(ref, hyp, mapping):
    # Monotonic one-to-one matching by maximum overlap, without using speaker labels.
    # Splits/merges produce extra/missed utterances, rather than artificial perfect timing.
    n, m = len(ref), len(hyp)
    scores = [[0] * (m + 1) for _ in range(n + 1)]
    moves = {}
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            choices = [(scores[i - 1][j], "r"), (scores[i][j - 1], "h")]
            weight = overlap(ref[i - 1], hyp[j - 1])
            if weight:
                choices.append((scores[i - 1][j - 1] + weight, "both"))
            scores[i][j], moves[i, j] = max(choices, key=lambda x: x[0])
    pairs = []
    i, j = n, m
    while i and j:
        move = moves[i, j]
        if move == "both":
            r, h = ref[i - 1], hyp[j - 1]
            pairs.append({"reference_index": i - 1, "hypothesis_index": j - 1,
                "start_error_ms": h["start_ms"] - r["start_ms"], "end_error_ms": h["end_ms"] - r["end_ms"],
                "speaker_correct": mapping.get(h["speaker_id"]) == r["speaker_id"]})
            i -= 1; j -= 1
        elif move == "r": i -= 1
        else: j -= 1
    return {"pairs": list(reversed(pairs)), "missed": n - len(pairs), "extra": m - len(pairs),
            "speaker_errors": sum(not x["speaker_correct"] for x in pairs),
            "start_mae_ms": sum(abs(x["start_error_ms"]) for x in pairs) / len(pairs) if pairs else None,
            "end_mae_ms": sum(abs(x["end_error_ms"]) for x in pairs) / len(pairs) if pairs else None}

def evaluate(reference, hypothesis, duration_ms, elapsed_ms):
    if duration_ms <= 0 or elapsed_ms < 0 or not math.isfinite(elapsed_ms):
        raise ValueError("Invalid duration or elapsed time")
    ref = read_transcript(reference, duration_ms)
    hyp = read_transcript(hypothesis, duration_ms)
    mapping = speaker_mapping(ref, hyp)
    return {"reference": str(reference), "hypothesis": str(hypothesis), "duration_ms": duration_ms,
            "elapsed_ms": elapsed_ms, "rtf": elapsed_ms / duration_ms, "valid_export": True,
            **wer(words(" ".join(x["text"] for x in ref)), words(" ".join(x["text"] for x in hyp))),
            "speaker_mapping": mapping, "timing": timing(ref, hyp, mapping),
            "normalization": "casefold; ё=е; punctuation/underscore removed; numbers NOT expanded"}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("hypothesis", type=Path)
    parser.add_argument("--duration-ms", type=int, required=True)
    parser.add_argument("--elapsed-ms", type=float, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = evaluate(args.reference, args.hypothesis, args.duration_ms, args.elapsed_ms)
    text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)

if __name__ == "__main__": main()
