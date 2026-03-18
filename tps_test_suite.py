#!/usr/bin/env python3
"""
High‑level TPS test suite for the CSC301 A2C2 services.

This script automates running the provided `tps_bench.py` against a variety
of endpoints exposed by the user, product and order services.  It loads
network addresses from `config.json` and iterates through different thread
counts and endpoints, recording throughput and latency metrics for each
combination.  The goal of the suite is to provide a comprehensive picture
of system behaviour under load and help identify configurations that can
exceed prior throughput results (e.g. > 355 TPS for the `/order` test).

Endpoints tested:
  * GET  /user/<id>           – look up a user by ID
  * POST /user               – create a user
  * GET  /product/<id>        – look up a product by ID
  * POST /product            – update product quantity
  * POST /order              – place an order (also performs a restart between runs)
  * GET  /user/purchased/<id> – list purchases for a user (requires prior orders)

For each endpoint the script can exercise multiple levels of concurrency by
varying the number of worker threads.  Results are printed to stdout and
written to a CSV file for later analysis.  All benchmarks use keep‑alive
connections and can be customised via command‑line options.

Usage:

    python3 tps_test_suite.py [--threads 10,50,100] [--duration 20] [--output results.csv]

Dependencies:
  * Python 3.9+ (for typing annotations)
  * The local `tps_bench.py` script must reside in the same directory.

Note: Running these benchmarks will generate significant load on the
services defined in config.json.  Ensure that the services are compiled and
running before executing the suite.  Network connectivity to the IP
addresses in config.json is required.
"""

import argparse
import csv
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import http.client


def load_config(path: Path) -> Dict[str, Dict[str, str | int]]:
    """Load the JSON configuration file mapping services to IPs and ports."""
    with path.open() as f:
        return json.load(f)


def call_order_restart(host: str, port: int, timeout: float = 5.0) -> None:
    """Issue a restart command to the OrderService.

    The first request to the OrderService determines whether persisted state
    should be preserved.  To ensure a fair test each run begins with a
    restart which clears any previous data without deleting persisted files.
    If the call fails it is silently ignored.
    """
    try:
        conn = http.client.HTTPConnection(host, port, timeout=timeout)
        payload = json.dumps({"command": "restart"}).encode("utf-8")
        headers = {"Content-Type": "application/json", "Content-Length": str(len(payload))}
        conn.request("POST", "/order", body=payload, headers=headers)
        conn.getresponse().read()
        conn.close()
    except Exception:
        # Ignore errors; the upcoming benchmark will report failures.
        pass


def run_benchmark(
    url: str,
    method: str,
    body: Optional[str],
    threads: int,
    duration: float,
) -> Dict[str, str]:
    """Invoke the `tps_bench.py` script for a single test configuration.

    Returns a mapping of metric names to values parsed from the output.
    """
    cmd: List[str] = [
        sys.executable,
        str(Path(__file__).with_name("tps_bench.py")),
        "--url",
        url,
        "--method",
        method,
        "--threads",
        str(threads),
        "--duration",
        str(duration),
    ]
    if body is not None and method == "POST":
        cmd.extend(["--body", body])

    proc = subprocess.run(cmd, capture_output=True, text=True)
    output = proc.stdout
    result: Dict[str, str] = {
        "url": url,
        "method": method,
        "threads": str(threads),
        "duration": str(duration),
    }
    # Parse key metrics from the output.  Each line has form "Key: value".
    for line in output.splitlines():
        if ":" not in line:
            continue
        parts = line.split(":", 1)
        if len(parts) != 2:
            continue
        key = parts[0].strip().lower().replace(" ", "_")
        value = parts[1].strip()
        result[key] = value
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a suite of TPS benchmarks")
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("config.json"),
        help="Path to config.json file with service addresses",
    )
    parser.add_argument(
        "--threads",
        type=str,
        default="10,50,100,150,200,250,300,350,400,500",
        help="Comma‑separated list of thread counts to test",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=20.0,
        help="Duration (seconds) for each benchmark run",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("tps_results.csv"),
        help="CSV file to write results to",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)

    # Define test endpoints.  Each entry is (name, method, path, body_json)
    tests: List[Tuple[str, str, str, Optional[dict]]] = [
        ("user_get", "GET", "/user/1", None),
        ("user_create", "POST", "/user", {"command": "create", "username": "u1", "email": "u1@example.com", "password": "p1"}),
        ("product_get", "GET", "/product/1", None),
        ("product_update", "POST", "/product", {"command": "update", "id": 1, "quantity": 100}),
        ("order_place", "POST", "/order", {"command": "place order", "product_id": 1, "user_id": 1, "quantity": 1}),
        ("user_purchased", "GET", "/user/purchased/1", None),
    ]

    thread_counts: List[int] = [int(t) for t in args.threads.split(",") if t]

    results: List[Dict[str, str]] = []
    start_time = time.time()
    for name, method, path, body_dict in tests:
        # Build the base URL for the service.  Determine which service the path
        # belongs to: /user* -> UserService, /product* -> ProductService,
        # /order* and /user/purchased* -> OrderService.
        if path.startswith("/user/purchased") or path.startswith("/order"):
            service = cfg.get("OrderService")
        elif path.startswith("/user"):
            service = cfg.get("UserService")
        elif path.startswith("/product"):
            service = cfg.get("ProductService")
        else:
            raise RuntimeError(f"Unknown path: {path}")
        host = service["ip"]
        port = service["port"]
        base_url = f"http://{host}:{port}{path}"
        for threads in thread_counts:
            # If this is the order benchmark then issue a restart command to
            # guarantee a fresh start.  Do this once per thread count.
            if name == "order_place":
                call_order_restart(cfg["OrderService"]["ip"], cfg["OrderService"]["port"])
            body_str: Optional[str] = None
            if body_dict is not None:
                body_str = json.dumps(body_dict)
            res = run_benchmark(
                url=base_url,
                method=method,
                body=body_str,
                threads=threads,
                duration=args.duration,
            )
            res["test"] = name
            results.append(res)
            # Print progress to stdout for visibility
            print(f"Completed {name} with {threads} threads: TPS (all) = {res.get('tps_(all)', 'N/A')}")

    elapsed_suite = time.time() - start_time
    # Write results to CSV
    fieldnames = sorted({k for r in results for k in r.keys()})
    with args.output.open("w", newline="") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)
    print(f"\nAll tests completed in {elapsed_suite:.1f} seconds. Results saved to {args.output}")


if __name__ == "__main__":
    main()