#!/usr/bin/env python3
"""Prepare, verify and publish the three certificate-pinned APKs of an official release."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import quote
import zipfile


ARM_ABIS = {"armeabi-v7a", "arm64-v8a"}
ARCHITECTURES = ARM_ABIS | {"universal"}
BUILD_TYPES = ("release",)
FEATURE_NOTES_FILE = "release-notes.json"
SIGNING_MODE = "shared-persistent-key"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
SIGNING_CERTIFICATE_PATH = PROJECT_ROOT / "gradle/signing-certificate.sha256"


def environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value or "\n" in value or "\r" in value:
        raise ValueError(f"Missing or invalid CI environment variable: {name}")
    return value


def version_name(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", value):
        raise ValueError("versionName is not safe for filenames and Git tags")
    return value


def run_identity() -> tuple[str, int]:
    run_id = environment("GITHUB_RUN_ID")
    attempt = environment("GITHUB_RUN_ATTEMPT")
    if not re.fullmatch(r"[1-9][0-9]*", run_id) or not re.fullmatch(r"[1-9][0-9]*", attempt):
        raise ValueError("Invalid GitHub run identity")
    return run_id, int(attempt)


def output(**values: str) -> None:
    with Path(environment("GITHUB_OUTPUT")).open("a", encoding="utf-8") as stream:
        for name, value in values.items():
            if "\n" in value or "\r" in value:
                raise ValueError(f"Invalid workflow output: {name}")
            stream.write(f"{name}={value}\n")


def command(*args: str) -> str:
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, text=True, encoding="utf-8").stdout.strip()


def manual_context() -> str:
    if environment("GITHUB_REF") != "refs/heads/main" or environment("GITHUB_EVENT_NAME") != "workflow_dispatch":
        raise ValueError("Official releases require a manual run from main")
    commit = environment("GITHUB_SHA")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or command("git", "rev-parse", "HEAD") != commit:
        raise ValueError("Checked-out source does not match the build commit")
    return commit


def api_object(endpoint: str, *arguments: str) -> dict:
    value = json.loads(command("gh", "api", endpoint, *arguments))
    if not isinstance(value, dict):
        raise ValueError("Expected a GitHub API object")
    return value


def api_collection(endpoint: str) -> list[dict]:
    # Failed requests must propagate, not be mistaken for a missing release or tag.
    pages = json.loads(command("gh", "api", "--paginate", "--slurp", endpoint))
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError("Expected paginated GitHub API arrays")
    values = [value for page in pages for value in page]
    if any(not isinstance(value, dict) for value in values):
        raise ValueError("Invalid GitHub API collection entry")
    return values


def release_for_tag(repository: str, tag: str) -> dict | None:
    releases = [release for release in api_collection(f"repos/{repository}/releases?per_page=100")
                if release.get("tag_name") == tag]
    if len(releases) > 1:
        raise ValueError("Multiple releases use the same version tag")
    return releases[0] if releases else None


def remote_tag(repository: str, tag: str) -> dict | None:
    refs = api_collection(f"repos/{repository}/git/matching-refs/tags/{quote(tag, safe='')}")
    matches = [ref for ref in refs if ref.get("ref") == f"refs/tags/{tag}"]
    if len(matches) > 1:
        raise ValueError("Multiple remote references use the release tag")
    return matches[0] if matches else None


def prepare() -> None:
    manual_context()
    dispatch_feature_notes()
    source = (PROJECT_ROOT / "build.gradle").read_text(encoding="utf-8")
    versions = re.findall(r'''^\s*versionName\s*=\s*(['"])([^'"\r\n]+)\1\s*$''', source, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("Expected one literal versionName in the root build.gradle")
    version = version_name(versions[0][1])
    tag = f"v{version}"
    command("git", "check-ref-format", f"refs/tags/{tag}")
    repository = environment("GITHUB_REPOSITORY")
    if release_for_tag(repository, tag) is not None:
        raise ValueError(f"{tag} already has a Release; increment versionName and versionCode in build.gradle, "
                         "then start a new manual run from updated main. "
                         "To resume a matching draft from this run, rerun only the failed publish job.")
    if remote_tag(repository, tag) is not None:
        raise ValueError(f"{tag} already exists as a Git tag; deleting a Release does not delete its tag. "
                         "Increment versionName and versionCode in build.gradle, "
                         "then start a new manual run from updated main. Existing tags are not overwritten.")
    output(version=version)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def certificate_digest(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Invalid APK signing certificate SHA-256 digest")
    return value


def pinned_signing_certificate() -> str:
    if not SIGNING_CERTIFICATE_PATH.is_file() or SIGNING_CERTIFICATE_PATH.is_symlink():
        raise ValueError("Missing regular signing certificate pin file")
    contents = SIGNING_CERTIFICATE_PATH.read_bytes().decode("ascii")
    return certificate_digest(contents.removesuffix("\n"))


def validate_signing_certificate(value: object) -> str:
    certificate = certificate_digest(value)
    if certificate != pinned_signing_certificate():
        raise ValueError("APK signing certificate does not match gradle/signing-certificate.sha256")
    return certificate


def apk_names(version: str) -> set[str]:
    return {f"NexBox-{version}-{abi}-{kind}.apk" for kind in BUILD_TYPES for abi in ARCHITECTURES}


def release_apks(bundle_dir: Path) -> dict[str, Path]:
    version = version_name(environment("NEXBOX_VERSION"))
    paths = {name: bundle_dir / name for name in sorted(apk_names(version))}
    if any(not path.is_file() or path.is_symlink() or path.stat().st_size == 0 for path in paths.values()):
        raise ValueError("Every published Release APK must be a nonempty regular file")
    return paths


def validate_native_abis(apk: Path, abi: str) -> None:
    expected = ARM_ABIS if abi == "universal" else {abi}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
    # CI collection must enforce the same host-only boundary as local APK verification.
    # A retired guest gallery must never be reintroduced as an embedded install payload.
    nested_apks = [name for name in names if name.lower().endswith(".apk")]
    if nested_apks:
        raise ValueError(f"Unexpected nested APK payloads in {apk.name}: {nested_apks}")
    native_abis = {name.split("/")[1] for name in names
                   if name.startswith("lib/") and name.endswith(".so") and len(name.split("/")) == 3}
    if native_abis != expected or any(f"lib/{item}/libnexbox.so" not in names for item in expected):
        raise ValueError(f"Native libraries do not match the APK architecture: {apk.name}")


def collect(apk_root: Path, output_dir: Path, mapping: Path) -> None:
    entries = dispatch_feature_notes()
    apks = []
    versions = set()
    codes = set()
    for kind in BUILD_TYPES:
        directory = apk_root / kind
        metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        if metadata.get("applicationId") != "com.nexbox.app" or metadata.get("variantName") != kind:
            raise ValueError(f"Unexpected {kind} host APK metadata")
        elements = metadata.get("elements", [])
        if len(elements) != 3:
            raise ValueError(f"Expected three APKs for {kind}")
        seen = set()
        for element in elements:
            version = version_name(element["versionName"])
            versions.add(version)
            code = element["versionCode"]
            if type(code) is not int or code < 1:
                raise ValueError("Invalid versionCode")
            codes.add(code)
            filters = element.get("filters", [])
            if len(filters) > 1 or any(item.get("filterType") != "ABI" for item in filters):
                raise ValueError("Unexpected APK filters")
            abi = filters[0]["value"] if filters else "universal"
            filename = f"NexBox-{version}-{abi}-{kind}.apk"
            if abi not in ARCHITECTURES or abi in seen or element["outputFile"] != filename:
                raise ValueError("Unexpected, duplicate or misnamed APK output")
            seen.add(abi)
            apk = directory / filename
            if not apk.is_file() or apk.is_symlink() or apk.stat().st_size == 0:
                raise ValueError(f"Missing, empty or symlinked APK: {filename}")
            validate_native_abis(apk, abi)
            apks.append(apk)
        if seen != ARCHITECTURES or set(directory.glob("*.apk")) != {apk for apk in apks if apk.parent == directory}:
            raise ValueError(f"Unexpected extra or missing {kind} APKs")
    if len(versions) != 1 or len(codes) != 1:
        raise ValueError("Release APKs disagree on versionName/versionCode")
    version = versions.pop()
    if version != version_name(environment("NEXBOX_VERSION")):
        raise ValueError("Built APK version does not match the prepared release version")
    if not mapping.is_file() or mapping.is_symlink() or mapping.stat().st_size == 0:
        raise ValueError("Release obfuscation mapping is missing or empty")
    with mapping.open(encoding="utf-8") as stream:
        if any(line.startswith(("com.nexbox.gallery.",
                                "com.nexbox.app.view.gallery.NexBoxGuestGalleryInstaller")) for line in stream):
            raise ValueError("Removed guest gallery implementation is still in the release mapping")
    certificate = validate_signing_certificate(environment("SIGNING_CERT_SHA256"))
    run_id, attempt = run_identity()
    commit = environment("GITHUB_SHA")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid source commit")
    mapping_name = f"NexBox-{version}-mapping-release.txt"
    info = {
        "schema_version": 2, "version": version, "version_code": codes.pop(),
        "release_channel": "stable", "tag": f"v{version}",
        "commit": commit, "ref": environment("GITHUB_REF"),
        "repository": environment("GITHUB_REPOSITORY"), "run_id": run_id, "build_attempt": attempt,
        "run_url": f"{environment('GITHUB_SERVER_URL')}/{environment('GITHUB_REPOSITORY')}/actions/runs/{run_id}",
        "build_types": list(BUILD_TYPES), "architectures": sorted(ARCHITECTURES),
        "signing": SIGNING_MODE, "certificate_sha256": certificate,
        "tests": "not-run", "device_tests": "not-run",
        "apks": sorted(apk_names(version)), "mapping": mapping_name,
    }
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("Output directory must be empty; refusing to mix stale build artifacts")
    output_dir.mkdir(parents=True, exist_ok=True)
    for apk in apks:
        shutil.copy2(apk, output_dir / apk.name)
    shutil.copy2(mapping, output_dir / mapping_name)
    (output_dir / FEATURE_NOTES_FILE).write_text(
        json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "BUILD-INFO.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksums = [f"{sha256(path)}  {path.name}\n" for path in sorted(output_dir.iterdir())]
    (output_dir / "SHA256SUMS").write_text("".join(checksums), encoding="utf-8")
    output(version=version, artifact_name=f"NexBox-{version}-release-{run_id}-{attempt}", build_attempt=str(attempt))


def validate_bundle(bundle_dir: Path, version: str, commit: str, run_id: str, build_attempt: int) -> dict:
    mapping_name = f"NexBox-{version}-mapping-release.txt"
    expected = apk_names(version) | {mapping_name, "BUILD-INFO.json", FEATURE_NOTES_FILE}
    if {path.name for path in bundle_dir.iterdir()} != expected | {"SHA256SUMS"}:
        raise ValueError("The complete three-APK Release bundle and all metadata must be present")
    if any(not path.is_file() or path.is_symlink() for path in bundle_dir.iterdir()):
        raise ValueError("Release bundle files must be regular files")
    checked = set()
    for line in (bundle_dir / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, separator, name = line.partition("  ")
        if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest) or name not in expected or name in checked:
            raise ValueError("Invalid, duplicate or unsafe checksum entry")
        if sha256(bundle_dir / name) != digest:
            raise ValueError(f"Attachment checksum mismatch: {name}")
        checked.add(name)
    if checked != expected:
        raise ValueError("Checksums do not cover every release bundle file")
    feature_notes(json.loads((bundle_dir / FEATURE_NOTES_FILE).read_text(encoding="utf-8")))
    info = json.loads((bundle_dir / "BUILD-INFO.json").read_text(encoding="utf-8"))
    if not isinstance(info, dict):
        raise ValueError("Build provenance must be a JSON object")
    validate_signing_certificate(info.get("certificate_sha256"))
    required = {"schema_version": 2, "version": version, "commit": commit, "run_id": run_id,
                "release_channel": "stable", "tag": f"v{version}",
                "build_attempt": build_attempt, "repository": environment("GITHUB_REPOSITORY"),
                "ref": "refs/heads/main", "build_types": list(BUILD_TYPES),
                "architectures": sorted(ARCHITECTURES), "signing": SIGNING_MODE,
                "tests": "not-run", "device_tests": "not-run",
                "apks": sorted(apk_names(version)), "mapping": mapping_name}
    if any(info.get(key) != value for key, value in required.items()):
        raise ValueError("Downloaded provenance does not match the expected build")
    return info


def previous_release(repository: str, commit: str, *, exclude_tag: str | None = None) -> str | None:
    published = command("gh", "api", "--paginate", f"repos/{repository}/releases?per_page=100",
                        "--jq", ".[] | select(.draft == false and .prerelease == false) | .tag_name").splitlines()
    reachable = set(command("git", "for-each-ref", f"--merged={commit}",
                            "--format=%(refname:strip=2)", "refs/tags/").splitlines())
    nearest = None
    distance = None
    for tag in published:
        if tag == exclude_tag or tag not in reachable or not re.fullmatch(r"v[0-9][A-Za-z0-9._+-]*", tag):
            continue
        count = int(command("git", "rev-list", "--count", f"refs/tags/{tag}..{commit}"))
        if distance is None or count < distance:
            nearest, distance = tag, count
        if count == 0:
            break
    return nearest


def markdown_text(value: str) -> str:
    value = " ".join(value.split())[:240]
    for character in "\\`*_[]<>":
        value = value.replace(character, "\\" + character)
    return value.replace("@", "@\u200b")


def feature_notes(entries: object) -> dict[str, tuple[str, str]]:
    if not isinstance(entries, list):
        raise ValueError("Feature notes must be a list of bilingual entries")
    result = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"id", "zh", "en"}:
            raise ValueError("Each feature note must contain exactly id, zh and en")
        identifier = entry["id"]
        if not isinstance(identifier, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,99}", identifier):
            raise ValueError("Invalid feature note id")
        if identifier in result:
            raise ValueError(f"Duplicate feature note id: {identifier}")
        for language in ("zh", "en"):
            text = entry[language]
            if not isinstance(text, str) or not text.strip() or len(text) > 240 or "\n" in text or "\r" in text:
                raise ValueError(f"Feature note {identifier} needs a short, single-line {language} description")
        result[identifier] = (entry["zh"], entry["en"])
    return result


def dispatch_feature_notes() -> list[dict[str, str]]:
    raw = os.environ.get("NEXBOX_RELEASE_NOTES", "").strip()
    entries = json.loads(raw) if raw else []
    feature_notes(entries)
    return entries


def release_marker(bundle_dir: Path, info: dict) -> str:
    return (f"<!-- nexbox-release {info['repository']} {info['run_id']}/{info['build_attempt']} "
            f"{info['commit']} {sha256(bundle_dir / 'SHA256SUMS')} -->")


def publication_info(bundle_dir: Path) -> dict:
    commit = manual_context()
    version = version_name(environment("NEXBOX_VERSION"))
    run_id, attempt = run_identity()
    build_attempt = int(environment("NEXBOX_BUILD_ATTEMPT"))
    if not 1 <= build_attempt <= attempt:
        raise ValueError("Invalid source build attempt")
    info = validate_bundle(bundle_dir, version, commit, run_id, build_attempt)
    command("git", "check-ref-format", f"refs/tags/{info['tag']}")
    return info


def write_release_notes(bundle_dir: Path, info: dict) -> Path:
    version, commit, repository = info["version"], info["commit"], info["repository"]
    repository_url = f"{environment('GITHUB_SERVER_URL')}/{repository}"
    previous = previous_release(repository, commit, exclude_tag=info["tag"])
    # Only a new draft needs a body. Publication retries retain the original release body.
    changes = list(feature_notes(json.loads(
        (bundle_dir / FEATURE_NOTES_FILE).read_text(encoding="utf-8"))).values())
    if not changes:
        if previous is None:
            changes = [("首个版本。", "First release.")]
        else:
            changes = [("优化应用的流畅度", "Improve app smoothness"),
                       ("提高应用稳定性", "Improve app stability")]
    lines = [f"# NexBox {version}", "", "## 更新内容", "",
             *[f"- {markdown_text(zh)}" for zh, _ in changes], "", "## What's new", "",
             *[f"- {markdown_text(en)}" for _, en in changes], "", "## SHA256SUMS", "", "```text",
             *[f"{sha256(path)}  {name}" for name, path in release_apks(bundle_dir).items()], "```", ""]
    if previous:
        lines += ["", f"[完整变更 / Full changes]({repository_url}/compare/{quote(previous, safe='')}...{commit})"]
    lines += ["", release_marker(bundle_dir, info), ""]
    path = Path(environment("RUNNER_TEMP")) / "nexbox-release-notes.md"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def notes(bundle_dir: Path) -> dict:
    info = publication_info(bundle_dir)
    write_release_notes(bundle_dir, info)
    output(tag=info["tag"])
    return info


def require_own_release(release: dict, info: dict, marker: str) -> bool:
    expected = {"tag_name": info["tag"], "target_commitish": info["commit"],
                "name": f"NexBox {info['version']}"}
    if (any(release.get(key) != value for key, value in expected.items())
            or release.get("prerelease") is not False
            or type(release.get("draft")) is not bool
            or type(release.get("id")) is not int or release["id"] < 1
            or not isinstance(release.get("body"), str)
            or not release["body"].rstrip().endswith(marker)):
        raise ValueError("Existing release does not belong to this exact run and bundle; refusing to modify it")
    return release["draft"]


def require_tag_target(repository: str, tag: str, commit: str, *, required: bool) -> bool:
    ref = remote_tag(repository, tag)
    if ref is None:
        if required:
            raise ValueError("Published release tag is missing")
        return False
    target = ref.get("object", {})
    if not isinstance(target, dict) or target.get("type") != "commit" or target.get("sha") != commit:
        raise ValueError("Release tag points to another commit; refusing to retag or publish")
    return True


def verify_uploaded_assets(repository: str, release_id: int, bundle_dir: Path, *, complete: bool) -> list[Path]:
    expected = release_apks(bundle_dir)
    assets = api_collection(f"repos/{repository}/releases/{release_id}/assets?per_page=100")
    seen = set()
    for asset in assets:
        name = asset.get("name")
        if not isinstance(name, str) or name not in expected or name in seen:
            raise ValueError("Unexpected or duplicate remote release attachment")
        path = expected[name]
        if (asset.get("state") != "uploaded" or asset.get("size") != path.stat().st_size
                or asset.get("digest") != f"sha256:{sha256(path)}"):
            raise ValueError(f"Remote attachment differs or is incomplete: {name}; refusing to replace it")
        seen.add(name)
    missing = [expected[name] for name in sorted(expected.keys() - seen)]
    if complete and missing:
        raise ValueError("Remote release attachments are incomplete; release must remain a draft")
    return missing


def publish(bundle_dir: Path) -> None:
    info = publication_info(bundle_dir)
    repository, tag, commit = info["repository"], info["tag"], info["commit"]
    marker = release_marker(bundle_dir, info)
    release = release_for_tag(repository, tag)
    if release is None:
        if remote_tag(repository, tag) is not None:
            raise ValueError(f"{tag} already exists without this run's release; refusing to reuse it")
        notes_path = write_release_notes(bundle_dir, info)
        # Use the creation response: the release-list index can lag behind a successful POST.
        release = api_object(f"repos/{repository}/releases", "--method", "POST",
                             "--raw-field", f"tag_name={tag}", "--raw-field", f"target_commitish={commit}",
                             "--raw-field", f"name=NexBox {info['version']}",
                             "--field", f"body=@{notes_path}",
                             "--field", "draft=true", "--field", "prerelease=false")
    is_draft = require_own_release(release, info, marker)
    release_id = release["id"]
    require_tag_target(repository, tag, commit, required=not is_draft)
    missing = verify_uploaded_assets(repository, release_id, bundle_dir, complete=not is_draft)
    if is_draft:
        # Only an exact retry may reuse a draft. Never clobber assets or edit an unrelated release.
        if missing:
            current = api_object(f"repos/{repository}/releases/{release_id}")
            if not require_own_release(current, info, marker):
                raise ValueError("Release became public before its uploads completed")
            command("gh", "release", "upload", tag, *[str(path) for path in missing], "--repo", repository)
        current = api_object(f"repos/{repository}/releases/{release_id}")
        still_draft = require_own_release(current, info, marker)
        verify_uploaded_assets(repository, release_id, bundle_dir, complete=True)
        tag_exists = require_tag_target(repository, tag, commit, required=not still_draft)
        if still_draft:
            if not tag_exists:
                command("gh", "api", "--method", "POST", f"repos/{repository}/git/refs",
                        "--raw-field", f"ref=refs/tags/{tag}", "--raw-field", f"sha={commit}")
                require_tag_target(repository, tag, commit, required=True)
            command("gh", "release", "edit", tag, "--repo", repository,
                    "--draft=false", "--prerelease=false", "--latest", "--verify-tag")
        current = api_object(f"repos/{repository}/releases/{release_id}")
        if require_own_release(current, info, marker):
            raise ValueError("GitHub still reports a draft; official publication is not confirmed")
        require_tag_target(repository, tag, commit, required=True)
        verify_uploaded_assets(repository, release_id, bundle_dir, complete=True)
    output(tag=tag, url=f"{environment('GITHUB_SERVER_URL')}/{repository}/releases/tag/{quote(tag, safe='')}",
           already_published=str(not is_draft).lower())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    modes.add_parser("prepare", help="Check a manual main run, project version and unused release tag")
    modes.add_parser("validate-notes", help="Validate manual update notes without building or publishing")
    collect_args = modes.add_parser("collect")
    collect_args.add_argument("--apk-root", type=Path, required=True)
    collect_args.add_argument("--output-dir", type=Path, required=True)
    collect_args.add_argument("--mapping", type=Path, required=True)
    notes_args = modes.add_parser("notes")
    notes_args.add_argument("--bundle-dir", type=Path, required=True)
    publish_args = modes.add_parser("publish", help="Verify the complete bundle and publish only its three Release APKs")
    publish_args.add_argument("--bundle-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.mode == "prepare":
            prepare()
        elif args.mode == "collect":
            collect(args.apk_root, args.output_dir, args.mapping)
        elif args.mode == "notes":
            notes(args.bundle_dir)
        elif args.mode == "publish":
            publish(args.bundle_dir)
        else:
            dispatch_feature_notes()
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Release bundle error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
