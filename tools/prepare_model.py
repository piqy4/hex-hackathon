"""Download pinned official weights on the build machine, never at Android runtime."""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
REV = "9217f5db79a29953eb74d5343926648285ec7e67"
SHA = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
SIZE = 491400032
URL = f"https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/{REV}/{NAME}"

def valid(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == SHA

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", action="store_true", help="Embed weights into the next APK")
    args = parser.parse_args()
    folder = ROOT / ("app/src/main/assets/models" if args.assets else "models")
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / NAME
    if not valid(target):
        part = target.with_suffix(".part")
        try:
            request = urllib.request.Request(URL, headers={"User-Agent": "hex-role3/1.0"})
            with urllib.request.urlopen(request, timeout=90) as response, part.open("wb") as output:
                count = 0
                while chunk := response.read(1024 * 1024):
                    count += len(chunk)
                    if count > SIZE:
                        raise ValueError("Unexpected download size")
                    output.write(chunk)
            if not valid(part):
                raise ValueError("SHA-256 mismatch")
            part.replace(target)
        finally:
            part.unlink(missing_ok=True)
    print(f"Verified: {target} ({SIZE} bytes)")
    manifest = {"repository": "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "revision": REV,
                "filename": NAME, "sha256": SHA, "bytes": SIZE, "url": URL, "license": "Apache-2.0"}
    (ROOT / "models").mkdir(exist_ok=True)
    (ROOT / "models/manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
