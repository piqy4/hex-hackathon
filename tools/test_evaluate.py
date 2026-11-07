import json
import tempfile
import unittest
from pathlib import Path
from evaluate import wer, words, speaker_mapping, timing, read_transcript

def u(speaker, start, end, text="слово"):
    return dict(speaker_id=speaker, start_ms=start, end_ms=end, text=text)

class MetricsTests(unittest.TestCase):
    def test_known_wer(self):
        self.assertEqual(wer("a b c".split(), "a x c d".split()), dict(S=1,D=0,I=1,N=3,wer=2/3))
        self.assertEqual(wer(["a","b"], ["a"])["D"], 1)
    def test_normalization(self):
        self.assertEqual(words("Ёлка, 15 РУБЛЕЙ!"), ["елка", "15", "рублей"])
    def test_empty_reference(self):
        self.assertIsNone(wer([], ["noise"])["wer"])
        self.assertEqual(wer([], [])["wer"], 0)
    def test_aba_mapping(self):
        ref = [u("speaker_1",0,100),u("speaker_2",200,300),u("speaker_1",400,500)]
        hyp = [u("speaker_2",0,100),u("speaker_1",200,300),u("speaker_2",400,500)]
        mapping = speaker_mapping(ref,hyp)
        self.assertEqual(mapping, {"speaker_1":"speaker_2", "speaker_2":"speaker_1"})
        self.assertEqual(timing(ref,hyp,mapping)["speaker_errors"],0)
    def test_missing_and_shifted(self):
        ref = [u("speaker_1",0,100),u("speaker_2",200,300)]
        hyp = [u("speaker_1",10,120)]
        result = timing(ref,hyp,speaker_mapping(ref,hyp))
        self.assertEqual(result["missed"],1)
        self.assertEqual(result["start_mae_ms"],10)
        self.assertEqual(result["end_mae_ms"],20)
    def test_invalid_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/"export.json"
            for data in [dict(transcript=[u("speaker_1",False,100)]),
                         dict(transcript=[u("speaker_1",0,101)]),
                         dict(transcript=[u("speaker_1",0,100.0)]),
                         dict(transcript=[u("speaker_1",0,100)],debug=True)]:
                p.write_text(json.dumps(data),encoding="utf-8")
                with self.assertRaises(ValueError): read_transcript(p,100)
            p.write_text('{"transcript":[],"transcript":[]}',encoding="utf-8")
            with self.assertRaises(ValueError): read_transcript(p,100)

if __name__ == "__main__": unittest.main()
