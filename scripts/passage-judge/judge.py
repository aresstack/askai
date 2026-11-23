#!/usr/bin/env python3
"""Files passages of a research stock as 0 = off, 1 = background or 2 = on-subject with a local Ollama model.

A measuring instrument for the research stock, not part of the product. Every variant in the rubrics file names its
model, its rubric and how the verdict is taken:

  score    the model answers {"score": 0|1|2}
  reason   the model first writes one sentence and the number of the deciding rule, then the score
  extract  the model answers three questions (running text? a device worn by employees, quoted verbatim? a hazard
           or legal basis?) and this script decides; a quoted device that is not in the passage does not count

Every request sets num_ctx itself (the server default can be far too large), runs at temperature 0, and the model
is unloaded at the end unless --keep-loaded is given. A run can be repeated: passages that already have a verdict in
the output file are skipped. Needs Python 3 and nothing else.

Input: --passages, JSON lines {"id": ..., "text": ...}; --rubrics, see rubrics.json. Output: JSON lines
{"id", "score", "raw", "note", "thinkChars", "evalCount", "error"}; evaluate.py reads them.
"""
import argparse
import concurrent.futures
import json
import os
import re
import sys
import threading
import time
import urllib.request

SCORE = {"type": "integer", "enum": [0, 1, 2]}
SCHEMAS = {
    "score": {"type": "object", "properties": {"score": SCORE}, "required": ["score"]},
    "reason": {"type": "object", "properties": {"grund": {"type": "string"},
                                                "regel": {"type": "integer", "enum": [1, 2, 3, 4]}, "score": SCORE},
               "required": ["grund", "regel", "score"]},
    "extract": {"type": "object", "properties": {"fliesstext": {"type": "boolean"}, "geraet": {"type": "string"},
                                                 "bezug": {"type": "boolean"}},
                "required": ["fliesstext", "geraet", "bezug"]},
}


def prompt_of(rubrics, variant, text):
    """The prompt of one passage: the book, the passage, then the rubric of the variant."""
    return (rubrics["book"] + "\n\n" + rubrics["passageLabel"] + "\n\"\"\"\n" + text + "\n\"\"\"\n\n"
            + rubrics["variants"][variant]["rubric"])


def normalised(text):
    return re.sub(r"\s+", " ", (text or "").replace("\u00ad", "").lower()).strip(" .,;:!?\"'()")


def decide(answer, text):
    """The verdict of the extract mode. Returns (score, note)."""
    if not answer.get("fliesstext"):
        return 0, ""
    device = normalised(answer.get("geraet"))
    if device:
        if device in normalised(text):
            return 2, ""
        return (1 if answer.get("bezug") else 0), "quote_miss"
    return (1 if answer.get("bezug") else 0), ""


def post(url, body, timeout):
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rubrics", required=True, help="rubrics file (book, system prompt, variants)")
    parser.add_argument("--variant", required=True, help="name of the variant in the rubrics file")
    parser.add_argument("--passages", required=True, help="JSON lines with id and text")
    parser.add_argument("--out", required=True, help="output file (JSON lines); existing verdicts are kept")
    parser.add_argument("--url", default="http://127.0.0.1:11434", help="Ollama (default %(default)s)")
    parser.add_argument("--workers", type=int, default=8, help="parallel requests (default %(default)s)")
    parser.add_argument("--num-ctx", type=int, default=4096, help="context per request (default %(default)s)")
    parser.add_argument("--keep-loaded", action="store_true", help="do not unload the model at the end")
    parser.add_argument("--dry-run", action="store_true",
                        help="write the prompts to the output file instead of asking the model")
    args = parser.parse_args()

    with open(args.rubrics, encoding="utf-8") as handle:
        rubrics = json.load(handle)
    if args.variant not in rubrics["variants"]:
        sys.exit("unknown variant %s; the file has %s" % (args.variant, ", ".join(sorted(rubrics["variants"]))))
    config = rubrics["variants"][args.variant]
    model, mode = config["model"], config["mode"]
    think = {"off": False, "on": True}.get(config["think"], config["think"])
    passages = []
    with open(args.passages, encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                passages.append(json.loads(line))

    if args.dry_run:
        with open(args.out, "w", encoding="utf-8") as handle:
            for passage in passages:
                handle.write(json.dumps({"id": passage["id"], "system": rubrics["system"],
                                         "prompt": prompt_of(rubrics, args.variant, passage["text"])}) + "\n")
        print("wrote %d prompts to %s" % (len(passages), args.out))
        return

    done = set()
    if os.path.exists(args.out):
        kept, dropped = [], 0
        with open(args.out, encoding="utf-8") as handle:
            for line in handle:
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                except ValueError:
                    dropped += 1
                    continue
                kept.append(json.dumps(record) + "\n")
                if record.get("score") is not None:
                    done.add(record["id"])
        if dropped:
            # An interrupted run can leave a partial last record; appending to it would corrupt the next one.
            with open(args.out, "w", encoding="utf-8") as handle:
                handle.writelines(kept)
            print("dropped %d incomplete record(s) from %s before resuming" % (dropped, args.out), flush=True)
    todo = [passage for passage in passages if passage["id"] not in done]
    print("%s variant %s: passages %d, done %d, to do %d, model %s, think %s, mode %s" % (
        time.strftime("%H:%M:%S"), args.variant, len(passages), len(done), len(todo), model, think, mode), flush=True)

    lock = threading.Lock()
    totals = {"n": 0, "failed": 0, "think": 0, "tokens": 0}
    started = time.time()
    out = open(args.out, "a", encoding="utf-8")

    def work(passage):
        raw, thinking, tokens, score, note, error = "", "", 0, None, "", ""
        for _ in range(2):
            try:
                answer = post(args.url + "/api/chat", {
                    "model": model, "stream": False, "think": think, "format": SCHEMAS[mode], "keep_alive": "5m",
                    "messages": [{"role": "system", "content": rubrics["system"]},
                                 {"role": "user", "content": prompt_of(rubrics, args.variant, passage["text"])}],
                    "options": {"temperature": 0, "num_ctx": args.num_ctx, "num_predict": config["numPredict"]}},
                    600)
                message = answer.get("message", {})
                raw = message.get("content", "") or ""
                thinking = message.get("thinking", "") or ""
                tokens = answer.get("eval_count", 0) or 0
                parsed = json.loads(raw)
                if mode == "extract":
                    score, note = decide(parsed, passage["text"])
                else:
                    score = parsed["score"]
                break
            except Exception as failure:  # the run goes on; the failure is in the output
                error = str(failure)[:200]
        with lock:
            out.write(json.dumps({"id": passage["id"], "score": score, "raw": raw[:400], "note": note,
                                  "thinkChars": len(thinking), "evalCount": tokens,
                                  "error": error if score is None else ""}) + "\n")
            out.flush()
            totals["n"] += 1
            totals["failed"] += 1 if score is None else 0
            totals["think"] += len(thinking)
            totals["tokens"] += tokens
            if totals["n"] % 50 == 0:
                print("%d/%d %.0fs" % (totals["n"], len(todo), time.time() - started), flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(work, todo))
    out.close()
    calls = max(1, totals["n"])
    print("%s done %s: %d in %.0fs, failed %d, thinking characters per call %.0f, output tokens per call %.0f" % (
        time.strftime("%H:%M:%S"), args.variant, totals["n"], time.time() - started, totals["failed"],
        totals["think"] / calls, totals["tokens"] / calls), flush=True)
    if not args.keep_loaded:
        try:
            post(args.url + "/api/generate", {"model": model, "keep_alive": 0}, 60)
        except Exception as failure:
            print("unload failed: %s" % str(failure)[:100])


if __name__ == "__main__":
    main()
