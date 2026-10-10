#!/usr/bin/env python3
"""Run the Camel fork probe with an independent broker and HTTP responder."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import subprocess
import threading
import time
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ports_available():
    for port in (18480, 18443, 18444):
        with socket.socket() as handle:
            handle.bind(("127.0.0.1", port))


def stop(process, stdin=False):
    if process.poll() is not None:
        return False
    if stdin:
        process.stdin.close()
    else:
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=15)
        return False
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("runtime", "template-profile", "archive", "java", "broker-module"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--javascript", action="store_true")
    parser.add_argument("--browser", action="store_true")
    parser.add_argument("--dsl", choices=("java", "yaml", "both"))
    parser.add_argument("--shared-yaml", action="store_true")
    parser.add_argument("--vertx", action="store_true")
    parser.add_argument("--camel-bundle", type=Path)
    parser.add_argument("--camel-xml-bundle", type=Path)
    parser.add_argument("--bundle-overlay", action="append", default=[], metavar="SYMBOLIC_NAME=PATH")
    parser.add_argument("--additional-bundle", action="append", type=Path, default=[])
    args = parser.parse_args()
    for name in ("runtime", "template_profile", "archive", "java", "broker_module"):
        setattr(args, name, getattr(args, name).resolve())
    if args.camel_bundle:
        args.camel_bundle = args.camel_bundle.resolve()
    if args.camel_xml_bundle:
        args.camel_xml_bundle = args.camel_xml_bundle.resolve()
    source_options = shlex.split((args.runtime / "jvm.args").read_text())
    source_homes = [x[len("-Dkura.home="):] for x in source_options if x.startswith("-Dkura.home=")]
    if len(source_homes) != 1 or not Path(source_homes[0]).is_absolute():
        parser.error("Runtime must declare one absolute kura.home")
    runtime_profile = Path(source_homes[0]).resolve()
    protected = Path.home() / ".kura-dev"
    if (args.archive == protected or protected in args.archive.parents
            or args.archive == args.runtime or args.runtime in args.archive.parents
            or args.archive == runtime_profile or runtime_profile in args.archive.parents
            or args.archive == args.template_profile or args.template_profile in args.archive.parents):
        parser.error("Use a new archive outside runtime and personal profiles")
    ports_available()
    args.archive.mkdir(parents=True, exist_ok=False)
    module = Path(__file__).resolve().parent
    helper = args.archive / "camel-fork-probe.jar"
    shutil.copy2(module / "target/kura-full-runtime-camel-fork-acceptance-1.0.0-SNAPSHOT.jar", helper)
    overlays = {}
    for symbolic_name, bundle in (("org.eclipse.kura.camel", args.camel_bundle),
                                  ("org.eclipse.kura.camel.xml", args.camel_xml_bundle)):
        if bundle:
            overlays[symbolic_name] = args.archive / (symbolic_name + ".jar")
            shutil.copy2(bundle, overlays[symbolic_name])
    for spec in args.bundle_overlay:
        symbolic_name, source = spec.split("=", 1)
        if symbolic_name in overlays or not re.fullmatch(r"[A-Za-z0-9_.-]+", symbolic_name):
            parser.error("Invalid or duplicate bundle overlay: " + symbolic_name)
        overlays[symbolic_name] = args.archive / (symbolic_name + ".jar")
        shutil.copy2(Path(source).resolve(), overlays[symbolic_name])
    additional = []
    for index, source in enumerate(args.additional_bundle):
        destination = args.archive / ("additional-" + str(index) + "-" + source.name)
        shutil.copy2(source.resolve(), destination)
        additional.append(destination)
    home = args.archive / "profile"
    shutil.copytree(args.template_profile, home, ignore=shutil.ignore_patterns("logs", "tmp", "*-result.json"))
    (home / "logs").mkdir()
    (home / "tmp").mkdir()
    relocated = []
    for file in home.rglob("*"):
        if file.is_file() and file.suffix in (".xml", ".properties", ".json"):
            raw = file.read_bytes()
            if str(args.template_profile).encode() in raw:
                file.write_bytes(raw.replace(str(args.template_profile).encode(), str(home).encode()))
                relocated.append(str(file.relative_to(home)))
    (home / ".camel-fork-acceptance-owned").write_text("Complete Mac Camel fork acceptance\n")
    (args.archive / "relocated-profile-files.json").write_text(json.dumps(relocated, indent=2) + "\n")
    (args.archive / "runtime-home-relocation.json").write_text(json.dumps({
        "runtimeProfile": str(runtime_profile), "templateProfile": str(args.template_profile),
        "ownedProfile": str(home)
    }, indent=2) + "\n")
    if args.browser:
        tree = ET.parse(home / "user/snapshots/snapshot_0.xml")
        component = next(c for c in tree.getroot().iter() if c.attrib.get("pid") == "HttpsKeystore")
        values = {p.attrib["name"]: p.find("{*}value").text for p in component.iter() if p.tag.endswith("property")}
        keystore = Path(values["keystore.path"]).resolve()
        assert home in keystore.parents and keystore.is_file()
        environment = os.environ.copy()
        environment["KURA_ACCEPTANCE_KEYSTORE_PASSWORD"] = values["keystore.password"]
        alias = "complete-mac-camel-browser-https"
        keytool = str(args.java.parent / "keytool")
        commands = [
            [keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
             "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-keystore", str(keystore),
             "-storepass:env", "KURA_ACCEPTANCE_KEYSTORE_PASSWORD", "-noprompt"],
            [keytool, "-exportcert", "-rfc", "-alias", alias, "-keystore", str(keystore),
             "-storepass:env", "KURA_ACCEPTANCE_KEYSTORE_PASSWORD", "-file", str(args.archive / "https-cert.pem")]]
        with (args.archive / "https-provision.log").open("w") as log:
            for command in commands:
                subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    configuration = args.archive / "configuration"
    configuration.mkdir()
    def relocate_runtime(content):
        return (content.replace(str(args.template_profile), str(home))
                .replace(source_homes[0], str(home))
                .replace(str(args.runtime / "configuration"), str(configuration)))

    for file in (args.runtime / "configuration").iterdir():
        if file.is_file():
            content = relocate_runtime(file.read_text())
            if file.name == "config.ini":
                for symbolic_name, overlay in overlays.items():
                    inventory = json.loads((args.runtime / "inventory.json").read_text())
                    entry = next(e for e in inventory if e["symbolicName"] == symbolic_name)
                    candidates = ("file:" + entry["path"], (args.runtime / entry["path"]).as_uri())
                    matches = [uri for uri in candidates if content.count(uri) == 1]
                    assert len(matches) == 1
                    content = content.replace(matches[0], overlay.as_uri())
                additions = "".join(",reference:" + path.as_uri() + "@5:start" for path in additional)
                content = re.sub(r"(?m)^osgi.bundles=(.*)$", lambda m: m.group(0) + additions + ",reference:" + helper.as_uri() + "@6:start", content)
            (configuration / file.name).write_text(content)
    requests = []

    class Responder(BaseHTTPRequestHandler):
        def do_GET(self):
            body = ("acceptance-http:" + self.path).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            requests.append({"path": self.path, "status": 200, "bodySha256": hashlib.sha256(body).hexdigest()})

        def log_message(self, *_):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", 0), Responder)
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()
    endpoint = args.archive / "broker.json"
    classpath = str(args.broker_module / "target/test-classes") + ":" + (args.broker_module / "target/broker-classpath.txt").read_text().strip()
    broker_command = [str(args.java), "-cp", classpath, "org.eclipse.kura.cloud.testing.fullruntime.AcceptanceBroker", str(endpoint)]
    processes, forced = [], []
    result = {"passed": False}
    started = time.monotonic()
    with (args.archive / "broker.log").open("w") as broker_log, (args.archive / "console.log").open("w") as app_log:
        broker = subprocess.Popen(broker_command, stdin=subprocess.PIPE, stdout=broker_log, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(broker)
        try:
            deadline = time.monotonic() + 15
            while broker.poll() is None and not endpoint.exists() and time.monotonic() < deadline:
                time.sleep(0.1)
            if not endpoint.exists():
                raise RuntimeError("No independent broker endpoint")
            uri = json.loads(endpoint.read_text())["uri"]
            options = [relocate_runtime(x) for x in source_options]
            assert "-Dkura.home=" + str(home) in options
            command = [str(args.java), *options, "-Dkura.acceptance.root=" + str(args.archive),
                       "-Dkura.acceptance.broker=" + uri, "-Dkura.acceptance.http=http://127.0.0.1:" + str(http.server_port),
                       "-Dkura.acceptance.javascript=" + str(args.javascript).lower(),
                       "-Dkura.acceptance.browser=" + str(args.browser).lower(),
                       "-Dkura.acceptance.dsl=" + (args.dsl or ""),
                       "-Dkura.acceptance.sharedYaml=" + str(args.shared_yaml).lower(),
                       "-Dkura.acceptance.vertx=" + str(args.vertx).lower(),
                       "-jar", str(args.runtime / "launcher.jar"), "-configuration", str(configuration),
                       "-install", str(args.runtime), "-console", "-consoleLog"]
            (args.archive / "command.json").write_text(json.dumps(command, indent=2) + "\n")
            app = subprocess.Popen(command, cwd=args.runtime, stdin=subprocess.PIPE, stdout=app_log, stderr=subprocess.STDOUT, start_new_session=True)
            processes.append(app)
            output = args.archive / "camel-probe-result.json"
            deadline = time.monotonic() + (750 if args.browser else 150)
            browser_announced = False
            while app.poll() is None and not output.exists() and time.monotonic() < deadline:
                ready = args.archive / "camel-browser-ready.json"
                if ready.exists() and not browser_announced:
                    print(json.dumps({"browserReady": str(ready), "url": "https://localhost:18443"}), flush=True)
                    browser_announced = True
                time.sleep(0.25)
            result = json.loads(output.read_text()) if output.exists() else {"passed": False, "error": "No result before exit/150-second deadline"}
        except Exception as error:
            result.update(passed=False, error=repr(error))
        finally:
            for process in reversed(processes):
                forced.append(stop(process, stdin=process is broker))
                if process.stdin and not process.stdin.closed:
                    process.stdin.close()
            http.shutdown()
            http.server_close()
            thread.join(timeout=5)
    ports_available()
    result.update(elapsedSeconds=round(time.monotonic() - started, 3), pids=[p.pid for p in processes],
                  exitCodes=[p.returncode for p in processes], forcedShutdown=any(forced), portsReleased=True,
                  independentHttpRequests=requests)
    final = args.archive / "broker-final.json"
    result["broker"] = json.loads(final.read_text()) if final.exists() else {"stopped": False}
    if result.get("passed"):
        expected = {result["clientId"], result["observerId"]}
        expected_http = 4 + int(args.javascript) + int(args.browser) + (2 if args.dsl == "both" else int(bool(args.dsl)))
        if not expected.issubset(result["broker"].get("authenticatedClients", [])) or len(requests) != expected_http or any(forced):
            result.update(passed=False, error="Independent broker/HTTP/cleanup evidence mismatch")
    result["helperSha256"] = sha(helper)
    if overlays:
        result["productionOverlaySha256"] = {name: sha(path) for name, path in overlays.items()}
    if additional:
        result["additionalBundleSha256"] = {path.name: sha(path) for path in additional}
    result["sources"] = {str(p.relative_to(module)): sha(p) for p in module.rglob("*") if p.is_file() and "target" not in p.relative_to(module).parts}
    result["brokerSourceSha256"] = sha(args.broker_module / "src/test/java/org/eclipse/kura/cloud/testing/fullruntime/AcceptanceBroker.java")
    result["logs"] = {f: sha(args.archive / f) for f in ("console.log", "broker.log", "profile/logs/kura.log") if (args.archive / f).is_file()}
    (args.archive / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: result.get(k) for k in ("passed", "error", "elapsedSeconds", "forcedShutdown", "portsReleased")}), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
