"""Prepare BSDIFF40 release artifacts from two existing, signed, standalone APKs.

Standard library only. Uses supplied SDK aapt/apksigner and a BSDIFF40 bsdiff
executable; never builds, signs, uploads, installs, or modifies an input APK.
The generated payloads are signed separately by SignUpdateManifest.java.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import urlsplit


MAX_APK = 1024 * 1024 * 1024
BSDIFF_SOURCE = "https://github.com/freebsd/freebsd-src/tree/725a9f47324d42037db93c27ceb40d4956872f3e/usr.bin/bsdiff"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            value.update(block)
    return value.hexdigest()


def command(*args):
    result = subprocess.run([str(arg) for arg in args], check=True, capture_output=True, text=True, encoding="utf-8")
    return result.stdout


def apk_info(path, aapt, apksigner):
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_APK:
        raise ValueError("APK must be a regular file between 1 byte and 1 GiB")
    signed = command(apksigner, "verify", "--verbose", "--print-certs", path)
    signers = sorted(set(re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", signed)))
    if not signers:
        raise ValueError("No verified signer certificates found")
    text = command(aapt, "dump", "badging", path)
    package = next((line for line in text.splitlines() if line.startswith("package:")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package))
    if "split" in fields:
        raise ValueError("Split APK updates are not supported")
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.MULTILINE)
    abi_line = next((line for line in text.splitlines() if line.startswith("native-code:")), "")
    abis = sorted(re.findall(r"'([^']+)'", abi_line))
    version = int(fields.get("versionCode", "0"))
    name = fields.get("versionName", "")
    package_name = fields.get("name", "")
    if not sdk or int(sdk[1]) < 31 or not abis or not set(abis) <= {"arm64-v8a", "x86_64"}:
        raise ValueError("Unsupported SDK or ABI declaration")
    if version <= 0 or not 1 <= len(name) <= 120 or any(c in name for c in "\r\n\t"):
        raise ValueError("Invalid version metadata")
    if not re.fullmatch(r"[A-Za-z0-9_.]{1,180}", package_name):
        raise ValueError("Invalid package name")
    return dict(package=package_name, version=version, name=name, min_sdk=int(sdk[1]),
                abis=abis, signers=[s.lower() for s in signers], size=path.stat().st_size, sha256=digest(path))


def https_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.fragment or any(c.isspace() for c in value)):
        raise argparse.ArgumentTypeError("Expected an HTTPS URL without credentials, fragment or whitespace")
    return value


def offset(value):
    number = int.from_bytes(value, "little")
    magnitude = number & ((1 << 63) - 1)
    return -magnitude if number >> 63 else magnitude


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--bsdiff", required=True, type=Path)
    parser.add_argument("--bsdiff-sha256", required=True, help="Expected SHA-256 of the release operator's BSDIFF40 generator")
    parser.add_argument("--apk-url", required=True, type=https_url)
    parser.add_argument("--patch-url", required=True, type=https_url)
    parser.add_argument("--channel", default="stable")
    parser.add_argument("--expires", required=True, type=int, help="Manifest expiration, Unix UTC seconds")
    parser.add_argument("--notes", type=Path, help="UTF-8 release notes")
    parser.add_argument("--output-dir", required=True, type=Path, help="A new directory; existing output is never overwritten")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.channel) or args.expires <= int(time.time()):
        raise ValueError("Invalid channel or expired manifest")
    notes = args.notes.read_text(encoding="utf-8") if args.notes else ""
    if len(notes.encode("utf-16-le")) // 2 > 16000:
        raise ValueError("Release notes exceed the Android manifest limit")
    generator = args.bsdiff.resolve(strict=True)
    generator_hash = digest(generator)
    if generator_hash != args.bsdiff_sha256.lower():
        raise ValueError("BSDiff generator hash does not match the supplied release tool")
    base = args.base.resolve(strict=True)
    target = args.target.resolve(strict=True)
    before = apk_info(base, args.aapt.resolve(strict=True), args.apksigner.resolve(strict=True))
    after = apk_info(target, args.aapt.resolve(strict=True), args.apksigner.resolve(strict=True))
    if before["package"] != after["package"] or before["signers"] != after["signers"]:
        raise ValueError("Base and target APK must have the same package and signer set")
    if before["version"] >= after["version"] or before["abis"] != after["abis"]:
        raise ValueError("Target must have a higher versionCode and the same standalone ABI set")
    args.output_dir.mkdir(parents=True, exist_ok=False)
    patch_info = None
    with tempfile.TemporaryDirectory(prefix="generating-", dir=args.output_dir) as temporary:
        candidate = Path(temporary) / "update.patch"
        command(generator, base, target, candidate)
        with candidate.open("rb") as stream:
            header = stream.read(32)
        size = candidate.stat().st_size
        if len(header) != 32 or header[:8] != b"BSDIFF40":
            raise ValueError("Generator did not produce BSDIFF40 (ENDSLEY/BSDIFF43 is incompatible)")
        control, diff, target_size = (offset(header[i:i + 8]) for i in (8, 16, 24))
        if control < 0 or diff < 0 or control + diff > size - 32 or target_size != after["size"]:
            raise ValueError("BSDIFF40 header does not match the target APK")
        if digest(base) != before["sha256"] or digest(target) != after["sha256"]:
            raise ValueError("Input APK changed during generation; no payload was published")
        if size < after["size"]:
            patch_info = dict(format="bsdiff40", size=size, sha256=digest(candidate), url=args.patch_url)
            candidate.rename(args.output_dir / "update.bsdiff")
    header = ["package=" + after["package"], "channel=" + args.channel, "expires=" + str(args.expires)]
    apk = "\t".join(map(str, ["apk", after["version"], after["name"], after["min_sdk"],
        ",".join(after["abis"]), after["size"], after["sha256"], args.apk_url,
        base64.b64encode(notes.encode("utf-8")).decode("ascii")]))
    v1 = ["flux-update-v1", *header, apk]
    v2 = ["flux-update-v2", *header, apk]
    if patch_info:
        v2.append("\t".join(map(str, ["patch", after["version"], after["sha256"], before["version"],
            before["sha256"], patch_info["format"], patch_info["size"], patch_info["sha256"], patch_info["url"]])))
    for name, lines in (("payload-v1.txt", v1), ("payload-v2.txt", v2)):
        with (args.output_dir / name).open("x", encoding="utf-8", newline="\n") as stream:
            stream.write("\n".join(lines) + "\n")
    report = dict(base=before, target=after, patch=patch_info, generator_sha256=generator_hash,
                  generator_source_reference=BSDIFF_SOURCE,
                  bytes_saved=after["size"] - patch_info["size"] if patch_info else 0,
                  decision="publish-patch-and-full" if patch_info else "full-only-patch-not-smaller")
    with (args.output_dir / "release-artifacts.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print("Prepared payload-v1.txt, payload-v2.txt and release-artifacts.json. Sign payloads separately.")
    print("Patch ready." if patch_info else "Patch is not smaller; publish only the complete APK.")


if __name__ == "__main__":
    main()
