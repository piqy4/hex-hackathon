"""WER against a text-only reference; explicitly does not invent ground-truth timestamps."""
import argparse
import json
from pathlib import Path
from evaluate import read_transcript, wer, words

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('hypothesis', type=Path)
    parser.add_argument('--references', type=Path, default=Path('evaluation/references.json'))
    parser.add_argument('--recording', required=True, choices=['dialog2', 'dialog3'])
    parser.add_argument('--duration-ms', required=True, type=int)
    args = parser.parse_args()
    ref = json.loads(args.references.read_text(encoding='utf-8'))[args.recording]
    hyp = read_transcript(args.hypothesis, args.duration_ms)
    result = wer(words(' '.join(u['text'] for u in ref['utterances'])), words(' '.join(u['text'] for u in hyp)))
    result.update(reference_verified=ref['verified_verbatim'],
                  reference_kind='verbatim' if ref['verified_verbatim'] else 'provisional_script',
                  speakers_expected=ref['expected_speakers'],
                  speakers_found=len({u['speaker_id'] for u in hyp}),
                  valid_export=True, DER=None, timing_errors=None,
                  normalization='casefold; ё=е; punctuation removed; numbers not expanded')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
