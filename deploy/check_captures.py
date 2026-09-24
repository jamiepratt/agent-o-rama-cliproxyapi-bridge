#!/usr/bin/env python3
"""Run cold persistence probes serially against one immutable application artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rama", type=Path)
    parser.add_argument("jar", type=Path)
    parser.add_argument("evidence", type=Path, help="new directory for synthetic corpora and logs")
    parser.add_argument("--kinds", nargs="+", choices=["admission", "reservation", "path", "metrics"],
                        default=["admission", "reservation", "path", "metrics"])
    parser.add_argument("--writer-module-first", action="store_true")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parent.parent
    cp = os.pathsep.join(map(str, [args.rama.resolve() / "rama.jar", args.rama.resolve() / "lib/*",
                                  args.jar.resolve(), root / "ipc/test"]))
    report = {"artifact_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(), "runs": []}
    for kind in args.kinds:
        corpus = args.evidence.resolve() / f"{kind}.nippy"
        for mode, order, perturb in [("write", int(args.writer_module_first), 0),
                                      ("read", 0, 1000), ("read", 1, 10000)]:
            name = f"{kind}-{mode}-module{order}-p{perturb}"
            env = {**os.environ, "BRIDGE_LOAD_MODULE_FIRST": str(order), "HASH_PERTURB": str(perturb),
                   "BRIDGE_REQUIRE_AOT": "1", "BRIDGE_METRICS_CORPUS": str(corpus)}
            command = [args.java, "-Xss6m", "-Xmx4g", "-Djdk.attach.allowAttachSelf", "-cp", cp,
                       "clojure.main", str(root / "ipc/test/bridge/capture_cold.clj"), mode, kind, str(corpus)]
            with (args.evidence / f"{name}.log").open("w") as log:
                result = subprocess.run(command, env=env, cwd=root, stdout=log, stderr=subprocess.STDOUT)
            report["runs"].append({"name": name, "exit": result.returncode})
            (args.evidence / "results.json").write_text(json.dumps(report, indent=2) + "\n")
            print(name, result.returncode, flush=True)
            if result.returncode:
                raise SystemExit(f"Persistence probe failed: {args.evidence / (name + '.log')}")
    final_hash = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    if final_hash != report["artifact_sha256"]:
        raise SystemExit("Application artifact changed during persistence probes")


if __name__ == "__main__":
    main()
