# Passage judge

A measuring instrument for a research stock: it files every passage as **off** (0), **background** (1) or
**on-subject** (2) for the book the research was run for. It is not part of the product and decides nothing in
the app. It exists because the app has no check that measures the collected material against the mission, and a
check that is not measured itself cannot be trusted either.

| File | What it is |
| --- | --- |
| `judge.py` | Runs one variant over passages with a local Ollama model. Python 3, no packages. |
| `rubrics.json` | The book description and every variant: model, rubric text, how the verdict is taken. |
| `evaluate.py` | Measures a judge against a blind-rated, stratified sample: the four conditions below. |
| `example-passages.jsonl` | Six passages written for this folder, with the verdict one would expect. |
| `measurements/` | The verdicts behind the numbers below. No passage text, no ids of the research stock. |

## The four conditions

They were fixed before the second measurement. Overall agreement is reported and decides nothing: the first
judge agreed with the rater in 87 % of the cases on "on-subject or not" and was still wrong in a third of its
on-subject verdicts.

| # | Question | Threshold |
| --- | --- | --- |
| 1 | How much of what the judge calls on-subject is on-subject? (precision) | at least 85 % |
| 2 | How much of the on-subject material does the judge find? (recall) | at least 85 % |
| 3 | How much of the on-subject material does the judge file as off? | at most 5 % |
| 4 | How much of the judge's background class is really off? | at most 25 % |

## Run it

```
python3 judge.py --rubrics rubrics.json --variant g1 --passages example-passages.jsonl --out out.jsonl
python3 evaluate.py --sample measurements/2026-11-21/sample-3.json
python3 evaluate.py --sample measurements/2026-11-21/sample-3.json --judge measurements/2026-11-21/judge-g1.jsonl
```

`judge.py` sets `num_ctx` on every request, runs at temperature 0, keeps going after a failed call and unloads
the model at the end (`--keep-loaded` leaves it). `--dry-run` writes the prompts instead of asking the model.
`evaluate.py` without `--judge` measures the judge the sample was drawn by; several sample files of one design
are pooled.

## How a sample is drawn

1. Run a judge over the whole stock.
2. Draw passages by strata: the judge's class, and inside "on-subject" the theme of the book, so that thin
   themes are not lost. At most two passages per source and class.
3. Give the rater a sheet with the book description and the passage text only, in random order, without the
   judge's verdict.
4. Write the rater's verdicts down before any new judge variant is run on the sample.
5. Keep the size of every stratum in the stock: the estimates are weighted with it.

A rubric that was tightened on the errors of one sample has to be tested on a fresh one.

## Measurements of 2026-11-21

The stock is the research of 17 Nov 2026 for a short German handbook on wearables on construction sites: 3,199
passages, of which the 2,425 with 25 to 220 words were sampled. Two samples of 150 passages were drawn by the class
of the first judge (`strict`) and rated blind. Sample 2 is the development set: the tightened rubrics were written
from its errors, and the variant to test was chosen on it. Sample 3 was drawn and rated afterwards, before any
variant ran. Variants and the rule of choice were written down before the first model output.

Both samples pooled (300 passages), in per cent:

| Variant | Model | What it changes | 1 precision | 2 recall | 3 lost in off | 4 off in background | met |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `strict` | gemma4:12b | the first rubric | 70.7 | 91.2 | 0.0 | 73.3 | 2 |
| `g1` | gemma4:12b | ordered rules with exclusions | 79.8 | 93.9 | 3.9 | 56.6 | 2 |
| `g2` | gemma4:12b | `g1` without the medicine, sport and leisure exclusion | 76.3 | 94.8 | 3.0 | 53.1 | 2 |
| `g5` | gemma4:12b | `g1`, a reason and the rule number before the score | 87.5 | 88.3 | 5.6 | 18.8 | 3 |
| `g4` | gemma4:12b | the model extracts three facts, the script decides | 83.8 | 87.1 | 6.3 | 69.7 | 1 |
| `g3` | gemma4:12b | `g1` with thinking on | 76.1 | 93.2 | 4.7 | 12.7 | 3 |
| `o0` | gpt-oss:20b | the first rubric | 76.7 | 84.4 | 9.2 | 54.8 | 0 |
| `o1` | gpt-oss:20b | the `g1` rules | 88.2 | 80.5 | 16.3 | 31.7 | 1 |

```
M=measurements/2026-11-21
python3 evaluate.py --sample $M/sample-2.json $M/sample-3.json                            # strict
python3 evaluate.py --sample $M/sample-2.json $M/sample-3.json --judge $M/judge-g5.jsonl
python3 evaluate.py --sample $M/sample-2.json $M/sample-3.json --judge $M/judge-g3.jsonl --fallback $M/judge-g1.jsonl
```

`g3` gave no verdict for 23 of the 300 passages, because thinking used up the 1,200 output tokens; the row counts
the `g1` verdict there.

What the measurement says:

- **No variant passes.** The rule chose on sample 2 by the number of conditions met. That was `g3` with two. On
  the fresh sample 3 it fails on precision (77 %) and on the background class (32 % off). On the 136 passages
  where it gave a verdict it meets all four.
- **A written reason helps most.** `g5` meets all four conditions on sample 3 and one on sample 2; pooled it
  misses only condition 3, by 0.6 points. Its count is unbiased: it files 528 passages as on-subject where about
  523 are, `strict` files 675.
- **Thinking is expensive.** `g3` needs 13 minutes for 300 passages instead of 2 to 3 and leaves one passage in
  thirteen without a verdict, the hard ones.
- **The larger model is stricter, not better.** It files 9 to 16 % of the on-subject material as off.
- **The off class of `strict` is safe.** None of 100 sampled passages was on-subject (at most 3.9 % of the
  on-subject material, one-sided 95 % bound).
- **150 passages are too few for thresholds five points apart.** The same variant meets one condition on one
  sample and four on the other, and a single passage can move condition 3 by five points.

Limits: the reference is the rating of a language model session, not of a human, and 19 of the 150 passages of
sample 3 are marked unsure; one book and one stock; passages shorter than 25 or longer than 220 words were not
sampled.
