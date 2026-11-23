#!/usr/bin/env python3
"""Measures a passage judge against a blind-rated, stratified sample.

A passage judge files every passage of a research stock as 0 = off, 1 = background or 2 = on-subject. This script
answers four questions about such a judge, each with a threshold that was fixed before the measurement:

  1. precision on-subject  How much of what the judge calls on-subject is on-subject?        at least 85 %
  2. recall on-subject     How much of the on-subject material does the judge find?           at least 85 %
  3. lost in "off"         How much of the on-subject material does the judge file as off?    at most 5 %
  4. off in "background"   How much of the judge's background class is really off?            at most 25 %

Overall agreement is printed but decides nothing: a judge can agree in 87 % of the cases and still be wrong in a
third of its on-subject verdicts.

The sample is drawn by strata (for example the class an earlier judge gave, split by theme), so every item stands
for stratum size / items in the stratum passages. Estimates are weighted that way; intervals come from a bootstrap
inside the strata. A zero count has no bootstrap spread, so for "0 of n" the one-sided 95 % bound 1 - 0.05^(1/n) is
printed as well.

Input (--sample): {"strata": {"<name>": <passages in the stock>}, "items": [{"id": ..., "stratum": "<name>",
"sampledAs": 0|1|2, "reference": 0|1|2, "unsure": false}]}. "sampledAs" is the class of the judge the sample was
drawn by; without --judge that judge is measured. Several sample files of the same design are pooled.

With --judge <file> (lines {"id": ..., "score": 0|1|2}, as judge.py writes them) the judge in the file is measured
on the same sample. Items without a verdict are left out, unless --fallback names a second file whose verdict
counts there.
"""
import argparse
import collections
import json
import random
import sys

THRESHOLDS = (("precision on-subject", "prec2", 0.85, True), ("recall on-subject", "rec2", 0.85, True),
              ("on-subject lost in off", "lost", 0.05, False), ("off inside background", "bg_off", 0.25, False))


def estimate(rows, sizes):
    """rows: (stratum, judge class, reference class). Returns the weighted measures."""
    per_stratum = collections.Counter(row[0] for row in rows)
    cell = collections.defaultdict(float)
    for stratum, judged, reference in rows:
        cell[(judged, reference)] += sizes[stratum] / float(per_stratum[stratum])

    def judged_as(label):
        return sum(cell[(label, reference)] for reference in (0, 1, 2))

    def really(label):
        return sum(cell[(judged, label)] for judged in (0, 1, 2))

    def share(part, whole):
        return part / whole if whole else float("nan")

    total = sum(cell.values())
    return {"prec2": share(cell[(2, 2)], judged_as(2)), "rec2": share(cell[(2, 2)], really(2)),
            "lost": share(cell[(0, 2)], really(2)), "bg_off": share(cell[(1, 0)], judged_as(1)),
            "agreement": share(sum(cell[(label, label)] for label in (0, 1, 2)), total),
            "on_subject": really(2), "judged_off": judged_as(0)}


def bootstrap(rows, sizes, rounds, seed=7):
    generator = random.Random(seed)
    by_stratum = collections.defaultdict(list)
    for row in rows:
        by_stratum[row[0]].append(row)
    seen = collections.defaultdict(list)
    for _ in range(rounds):
        drawn = []
        for members in by_stratum.values():
            drawn += [members[generator.randrange(len(members))] for _ in members]
        for key, value in estimate(drawn, sizes).items():
            if value == value:
                seen[key].append(value)
    interval = {}
    for key, values in seen.items():
        values.sort()
        interval[key] = (values[int(0.025 * len(values))], values[max(0, int(0.975 * len(values)) - 1)])
    return interval


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sample", required=True, nargs="+",
                        help="the blind-rated sample (JSON, see above); several files of one design are pooled")
    parser.add_argument("--judge", help="output of the judge under test (JSON lines with id and score)")
    parser.add_argument("--fallback", help="verdicts to use where the judge under test gave none (same format)")
    parser.add_argument("--drop-unsure", action="store_true", help="leave out the items the rater marked unsure")
    parser.add_argument("--bootstrap", type=int, default=4000, help="bootstrap rounds (default 4000)")
    args = parser.parse_args()

    sizes = None
    items = []
    for path in args.sample:
        with open(path, encoding="utf-8") as handle:
            sample = json.load(handle)
        if sizes is not None and sizes != sample["strata"]:
            sys.exit("the samples do not share one design: " + path)
        sizes = sample["strata"]
        items += sample["items"]
    judged = None
    if args.judge:
        judged = {}
        for path in (args.fallback, args.judge):  # the judge under test overwrites the fallback
            if not path:
                continue
            with open(path, encoding="utf-8") as handle:
                for line in handle:
                    if line.strip():
                        record = json.loads(line)
                        if record.get("score") is not None:
                            judged[record["id"]] = record["score"]
    rows = []
    skipped = collections.Counter()
    for item in items:
        if args.drop_unsure and item.get("unsure"):
            skipped["unsure"] += 1
            continue
        label = item["sampledAs"] if judged is None else judged.get(item["id"])
        if label is None:
            skipped["no verdict of the judge"] += 1
            continue
        rows.append((item["stratum"], label, item["reference"]))
    if not rows:
        sys.exit("no items to evaluate")

    print("items %d%s" % (len(rows), (", left out %s" % dict(skipped)) if skipped else ""))
    counts = collections.Counter((judged_class, reference) for _, judged_class, reference in rows)
    print("unweighted, rows judge off/background/on-subject, columns reference off/background/on-subject:")
    for label in (0, 1, 2):
        print("   " + " ".join("%4d" % counts[(label, reference)] for reference in (0, 1, 2)))
    point = estimate(rows, sizes)
    spread = bootstrap(rows, sizes, args.bootstrap)
    met = 0
    for number, (name, key, threshold, at_least) in enumerate(THRESHOLDS, 1):
        ok = point[key] >= threshold if at_least else point[key] <= threshold
        met += 1 if ok else 0
        low, high = spread.get(key, (float("nan"), float("nan")))
        print("%d %-24s %5.1f %%  (95 %% interval %4.1f to %4.1f)  threshold %s %2.0f %%  %s" % (
            number, name, 100 * point[key], 100 * low, 100 * high, "at least" if at_least else "at most",
            100 * threshold, "met" if ok else "NOT met"))
    print("conditions met: %d of 4; overall agreement %.1f %% (decides nothing)" % (met, 100 * point["agreement"]))
    off_items = [row for row in rows if row[1] == 0]
    if off_items and not any(row[2] == 2 for row in off_items):
        bound = 1 - 0.05 ** (1.0 / len(off_items))
        passages = bound * point["judged_off"]
        print("off class: 0 of %d on-subject; one-sided 95 %% bound %.1f %% of the class, about %.0f passages, "
              "at most %.1f %% of the on-subject material" % (len(off_items), 100 * bound, passages,
                                                            100 * passages / (point["on_subject"] + passages)))


if __name__ == "__main__":
    main()
