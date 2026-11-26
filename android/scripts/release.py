#!/usr/bin/env python3
"""Publishes the keyboard's release APK to the owner's hub server. The hub's phone app finds it there,
shows "Keyboard update ready" and installs it on a tap, so an update needs no cable. The keyboard
itself never gets the network (docs/feature-spec.md, "Updates").

    python3 android/scripts/release.py            build, check and publish
    python3 android/scripts/release.py --check    everything up to the upload, which it leaves out

It stops at the first thing wrong, before anything reaches the server:

- the checkout must be on main with nothing uncommitted: what is built is what the folders hold;
- the release key's settings (../xrn-signing.properties beside the repo) must be there, which they
  are only from the main checkout: elsewhere Gradle builds an unsigned APK;
- the server must not already have this versionCode or a later one. Nothing is bumped here: bump
  versionCode and versionName in app/build.gradle.kts in a commit of their own first;
- the APK must be signed by the certificate pinned in the config ("signer"), the one the keyboard on
  the phone carries, or Android refuses the update;
- the APK must ask for no permission at all.

Reads ~/.config/xrnkey/release.json, which never enters the repo:

    { "server": "https://<host>/<hub path>", "ssh": ["<ssh host>", "<another way in>"],
      "signer": "<SHA-256 of the installed keyboard's signing certificate>",
      "dir": "<the hub's data folder on the server>/update/xrnkey" }

"dir" is relative to the ssh login's home and defaults to xrnhub/data/update/xrnkey. The first ssh
host that answers is used. The APK goes up first and app.json last, each written beside its name and
moved into place, so the phone never hears of a file that isn't whole.
"""
import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import urllib.error
import urllib.request

ANDROID = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ANDROID)
GRADLE = os.path.join(ANDROID, "app", "build.gradle.kts")
APK_DIR = os.path.join(ANDROID, "app", "build", "outputs", "apk", "release")
SIGNING = os.path.join(os.path.dirname(REPO), "xrn-signing.properties")
# The lock every Gradle build on this laptop takes (the worktrees' folder beside the checkouts), so a
# release never runs a second build beside another session's: two at once overran its memory.
GRADLE_LOCK = os.path.join(os.path.dirname(REPO), ".wt", ".gradle.lock")
CONFIG = os.path.join(os.path.expanduser("~"), ".config", "xrnkey", "release.json")
RELEASE_BRANCH = "main"
PACKAGE = "com.xern.xrnkey"
DEFAULT_DIR = "xrnhub/data/update/xrnkey"
UPDATE_PATH = "/api/keyboard/update"
SAFE_DIR = re.compile(r"[A-Za-z0-9._/-]+")


class Refused(Exception):
    """Why this isn't released."""


def tree_refusals(git):
    """Why the checkout isn't one to release from; empty if it is. `git(*args)` returns git's output."""
    out = []
    branch = git("rev-parse", "--abbrev-ref", "HEAD").strip()
    if branch != RELEASE_BRANCH:
        out.append(f"on {branch}, not {RELEASE_BRANCH}")
    changed = git("status", "--porcelain").strip()
    if changed:
        out.append(f"not committed:\n{changed}")
    return out


def pinned_signer(text):
    """The config's "signer" as apksigner prints a SHA-256: lower case hex, no colons."""
    signer = str(text or "").lower().replace(":", "").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", signer):
        raise Refused(f'{CONFIG} needs "signer": the SHA-256 of the installed keyboard\'s signing certificate')
    return signer


def read_config(doc):
    """(server, ssh hosts, signer, remote dir) from release.json's contents."""
    if not isinstance(doc, dict):
        raise Refused(f"{CONFIG} must hold a JSON object")
    server = str(doc.get("server") or "").rstrip("/")
    hosts = doc.get("ssh")
    hosts = [hosts] if isinstance(hosts, str) else hosts
    if not re.match(r"https://[^/]+", server):
        raise Refused(f'{CONFIG} needs "server": the hub\'s https address')
    if not isinstance(hosts, list) or not hosts or not all(isinstance(h, str) and h for h in hosts):
        raise Refused(f'{CONFIG} needs "ssh": the server\'s ssh host, or a list of ways in')
    folder = str(doc.get("dir") or DEFAULT_DIR).strip("/")
    if not SAFE_DIR.fullmatch(folder) or ".." in folder.split("/"):
        raise Refused(f'{CONFIG}\'s "dir" must be a plain path under the ssh login\'s home')
    return server, hosts, pinned_signer(doc.get("signer")), folder


def version_code(gradle_text):
    match = re.search(r"\bversionCode\s*=\s*(\d+)", gradle_text)
    if not match:
        raise Refused(f"no versionCode in {GRADLE}")
    return int(match[1])


def check_newer(code, live):
    """Refuses a build the phone wouldn't take: the server's release has this versionCode or later."""
    live_code = live.get("versionCode") if isinstance(live, dict) else None
    if isinstance(live_code, int) and live_code >= code:
        raise Refused(
            f"the server already has build {live_code} ({live.get('versionName', '?')}), and this is {code}: "
            "bump versionCode and versionName in app/build.gradle.kts in a commit of their own, then run this again"
        )


def signer_of(apksigner_output):
    """The first signer's certificate SHA-256 from `apksigner verify --print-certs`, or None."""
    match = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", apksigner_output)
    return match[1] if match else None


def check_signer(cert, signer):
    """Unchecked is as bad as wrong: a build signed another way reached the server once, for another
    app, and the phone refused it."""
    if not cert:
        raise Refused("couldn't read the APK's signer to check it against the pinned one")
    if cert != signer:
        raise Refused(f"the APK is signed by {cert}, not the installed keyboard's {signer}: the phone would refuse it")


def asked_permissions(aapt2_output):
    """What `aapt2 dump permissions` says the APK asks for (uses-permission lines only)."""
    return re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", aapt2_output)


def check_no_permissions(asked):
    if asked:
        raise Refused(f"the APK asks for {', '.join(asked)}: the keyboard asks for no permission at all")


def release_doc(apk, code, name):
    """app.json: what the hub's phone app reads before downloading the APK it names."""
    return {
        "versionCode": code,
        "versionName": name,
        "apk": f"xrnkey-{code}.apk",
        "size": len(apk),
        "sha256": hashlib.sha256(apk).hexdigest(),
    }


def build_tools():
    """The newest build-tools of the SDK local.properties (or ANDROID_HOME) names."""
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    try:
        with open(os.path.join(ANDROID, "local.properties"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip()
    except OSError:
        pass
    if not sdk or not os.path.isdir(os.path.join(sdk, "build-tools")):
        raise Refused("no Android SDK: set sdk.dir in android/local.properties or ANDROID_HOME")
    versions = sorted(os.listdir(os.path.join(sdk, "build-tools")),
                      key=lambda v: [int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", v)])
    return os.path.join(sdk, "build-tools", versions[-1])


def gradle_command(*tasks):
    """./gradlew for `tasks` under the shared Gradle lock (flock waits for a build that holds it),
    leaving no daemon behind: Gradle's and Kotlin's once held 8 GB of a laptop's memory."""
    return ["flock", GRADLE_LOCK, "./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", *tasks]


def run(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw).stdout


def first_host(hosts):
    for host in hosts:
        ok = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, "true"],
                            capture_output=True).returncode == 0
        if ok:
            return host
    raise Refused(f"none of {', '.join(hosts)} answers over ssh")


def main(argv=None):
    p = argparse.ArgumentParser(description="Publish the keyboard's release APK to the hub server.")
    p.add_argument("--check", action="store_true", help="build and check, but upload nothing")
    args = p.parse_args(argv)

    refusals = tree_refusals(lambda *a: run(["git", "-C", REPO, *a]))
    if refusals:
        raise Refused("\n".join(refusals))
    if not os.path.isfile(SIGNING):
        raise Refused(f"no {SIGNING}: Gradle would build an unsigned APK. Releases are made from the main checkout")
    try:
        with open(CONFIG, encoding="utf-8") as f:
            config = json.load(f)
    except OSError:
        raise Refused(f"""no {CONFIG}. Create it with the hub's address, its ssh host and the installed keyboard's
signing certificate (apksigner verify --print-certs on the APK it was installed from):
  {{ "server": "https://<host>/<hub path>", "ssh": ["<ssh host>"], "signer": "<SHA-256>" }}""") from None
    server, hosts, signer, folder = read_config(config)
    host = first_host(hosts)
    remote = shlex.quote(folder)

    def ssh(command, data=None):
        return subprocess.run(["ssh", "-o", "BatchMode=yes", host, command], input=data, check=True,
                              capture_output=True).stdout

    with open(GRADLE, encoding="utf-8") as f:
        code = version_code(f.read())
    live_text = ssh(f"cat {remote}/app.json 2>/dev/null || true").decode().strip()
    check_newer(code, json.loads(live_text) if live_text else None)

    env = dict(os.environ, ANDROID_USER_HOME=os.environ.get("ANDROID_USER_HOME", os.path.expanduser("~/.android")))
    os.makedirs(os.path.dirname(GRADLE_LOCK), exist_ok=True)
    subprocess.run(gradle_command(":app:assembleRelease"), cwd=ANDROID, env=env, check=True)
    with open(os.path.join(APK_DIR, "output-metadata.json"), encoding="utf-8") as f:
        built = json.load(f)["elements"][0]
    if built["versionCode"] != code:
        raise Refused(f"built versionCode {built['versionCode']}, expected {code}")
    apk_file = os.path.join(APK_DIR, built["outputFile"])
    tools = build_tools()
    check_signer(signer_of(run([os.path.join(tools, "apksigner"), "verify", "--print-certs", apk_file])), signer)
    check_no_permissions(asked_permissions(run([os.path.join(tools, "aapt2"), "dump", "permissions", apk_file])))
    badging = run([os.path.join(tools, "aapt2"), "dump", "badging", apk_file])
    if f"package: name='{PACKAGE}'" not in badging:
        raise Refused(f"the APK isn't {PACKAGE}")
    with open(apk_file, "rb") as f:
        apk = f.read()
    doc = release_doc(apk, code, built["versionName"])
    print(f"{doc['apk']}: {len(apk) / 1048576:.1f} MB, version {doc['versionName']} ({code}), signed by {signer}")
    if args.check:
        print("--check: nothing uploaded")
        return

    ssh(f"mkdir -p {remote}")
    name = shlex.quote(doc["apk"])
    ssh(f"cat > {remote}/{name}.part && mv {remote}/{name}.part {remote}/{name}", apk)
    ssh(f"cat > {remote}/app.json.part && mv {remote}/app.json.part {remote}/app.json",
        (json.dumps(doc, indent=2) + "\n").encode())
    ssh(f'cd {remote} && for f in xrnkey-*.apk; do [ "$f" = {name} ] || rm -f "$f"; done')

    # Without the login it must say 401: the route is there, and guarded.
    try:
        urllib.request.urlopen(server + UPDATE_PATH, timeout=20)
        status = 200
    except urllib.error.HTTPError as e:
        status = e.code
    if status != 401:
        raise Refused(f"uploaded, but {server}{UPDATE_PATH} answered {status} without the login, not 401: "
                      "is the hub server with the keyboard routes deployed?")
    print(f"published {doc['versionName']} ({code}). The hub app's Apps page shows it as ready, and installs it on a tap.")


if __name__ == "__main__":
    try:
        main()
    except Refused as e:
        sys.exit(f"Not released: {e}")
    except subprocess.CalledProcessError as e:
        said = e.stderr.decode(errors="replace") if isinstance(e.stderr, bytes) else e.stderr or ""
        sys.exit(f"Not released: {' '.join(map(str, e.cmd[:3]))} failed\n{said}".rstrip())
