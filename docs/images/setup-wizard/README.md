# Setup wizard art

The seven Merlin pictures of the AskAI setup wizard.

- `originals/` holds the pictures exactly as delivered (1086 × 1448 px, RGB, unedited). Do not edit them;
  every derived version is a copy.
- The wizard loads the derived copies from
  `askai-app/src/main/resources/com/aresstack/askai/ui/setup/wizard/`: the same file names, scaled to
  480 × 640 px (enough for the largest figure at 2× screen scale) with transparent rounded corners, so the
  dark scene sits on the comic plate as a card.

| Picture | Used in the wizard |
|---|---|
| `wizard-1-runtime-install.png` | Step “AI runtime” while no runtime answers yet (install Ollama), and the general fallback |
| `wizard-2-security.png` | Step “Network & security” |
| `wizard-3-connecting-runtime.png` | Step “AI runtime” once a runtime answers |
| `wizard-4-features.png` | Step “Chat model” |
| `wizard-5-knowledge-research.png` | Step “Knowledge & research” |
| `wizard-6-setup-complete.png` | Step “Check” (final check) |
| `wizard-7-audio-voice.png` | Step “Voice” |
| `merlin-1-network.png` | Step “Network” (cut-out, transparent background) – used |
| `merlin-2-ai-runtime.png` | Step “AI runtime”, before and after a runtime answers – used |
| `merlin-3-chat-model.png` | Step “Chat model” – used |
| `merlin-4-knowledge.png` | Step “Knowledge” – used |
| `merlin-5-check.png` | Step “Check” – used |
| `merlin-6-voice.png` | Step “Voice” – used |

The wizard shows the `merlin-*` cut-outs; they grow to fill the free space of each step. The `wizard-*` scenes stay
as alternatives (`wizard-1-runtime-install.png` remains the general fallback). The script only scales the cut-outs
down to 900 px height, without rounded corners.

Regenerating the copies after an original changed (Python 3 with Pillow):

```bash
python3 scripts/setup-wizard-art.py
```
