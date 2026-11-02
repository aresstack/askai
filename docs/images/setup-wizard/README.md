# Setup wizard art

The seven Merlin pictures of the AskAI setup wizard.

- `originals/` holds the pictures exactly as delivered (1086 × 1448 px, RGB, unedited). Do not edit them;
  every derived version is a copy.
- The wizard loads the derived copies from
  `askai-app/src/main/resources/com/aresstack/askai/java8/ui/setup/wizard/`: the same file names, scaled to
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

Regenerating the copies after an original changed (Python 3 with Pillow):

```bash
python3 scripts/setup-wizard-art.py
```
