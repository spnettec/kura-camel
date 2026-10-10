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
    args = parser.parse_args()
    for name in vars(args):
        setattr(args, name, getattr(args, name).resolve())
    protected = Path.home() / ".kura-dev"
    if args.archive == protected or protected in args.archive.parents or args.archive == args.runtime or args.runtime in args.archive.parents:
        parser.error("Use a new archive outside runtime and personal profiles")
    ports_available()
    args.archive.mkdir(parents=True, exist_ok=False)
    module = Path(__file__).resolve().parent
    helper = args.archive / "camel-fork-probe.jar"
    shutil.copy2(module / "target/kura-full-runtime-camel-fork-acceptance-1.0.0-SNAPSHOT.jar", helper)
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
    configuration = args.archive / "configuration"
    configuration.mkdir()
    for file in (args.runtime / "configuration").iterdir():
        if file.is_file():
            content = file.read_text().replace(str(args.template_profile), str(home))
            content = content.replace(str(args.runtime / "configuration"), str(configuration))
            if file.name == "config.ini":
                content = re.sub(r"(?m)^osgi.bundles=(.*)$", lambda m: m.group(0) + ",reference:" + helper.as_uri() + "@6:start", content)
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
            options = [x.replace(str(args.template_profile), str(home)).replace(str(args.runtime / "configuration"), str(configuration))
                       for x in shlex.split((args.runtime / "jvm.args").read_text())]
            command = [str(args.java), *options, "-Dkura.acceptance.root=" + str(args.archive),
                       "-Dkura.acceptance.broker=" + uri, "-Dkura.acceptance.http=http://127.0.0.1:" + str(http.server_port),
                       "-jar", str(args.runtime / "launcher.jar"), "-configuration", str(configuration),
                       "-install", str(args.runtime), "-console", "-consoleLog"]
            (args.archive / "command.json").write_text(json.dumps(command, indent=2) + "\n")
            app = subprocess.Popen(command, cwd=args.runtime, stdin=subprocess.PIPE, stdout=app_log, stderr=subprocess.STDOUT, start_new_session=True)
            processes.append(app)
            output = args.archive / "camel-probe-result.json"
            deadline = time.monotonic() + 150
            while app.poll() is None and not output.exists() and time.monotonic() < deadline:
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
        if not expected.issubset(result["broker"].get("authenticatedClients", [])) or len(requests) != 4 or any(forced):
            result.update(passed=False, error="Independent broker/HTTP/cleanup evidence mismatch")
    result["helperSha256"] = sha(helper)
    result["sources"] = {str(p.relative_to(module)): sha(p) for p in module.rglob("*") if p.is_file() and "target" not in p.relative_to(module).parts}
    result["brokerSourceSha256"] = sha(args.broker_module / "src/test/java/org/eclipse/kura/cloud/testing/fullruntime/AcceptanceBroker.java")
    result["logs"] = {f: sha(args.archive / f) for f in ("console.log", "broker.log", "profile/logs/kura.log") if (args.archive / f).is_file()}
    (args.archive / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: result.get(k) for k in ("passed", "error", "elapsedSeconds", "forcedShutdown", "portsReleased")}), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
