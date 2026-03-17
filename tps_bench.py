#!/usr/bin/env python3
import argparse
import http.client
import json
import threading
import time
from urllib.parse import urlsplit


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    values = sorted(values)
    k = (len(values) - 1) * p
    f = int(k)
    c = min(f + 1, len(values) - 1)
    if f == c:
        return values[f]
    return values[f] * (c - k) + values[c] * (k - f)


def worker(
    worker_id: int,
    scheme: str,
    host: str,
    port: int,
    path: str,
    method: str,
    body_bytes: bytes | None,
    headers: dict[str, str],
    deadline: float,
    timeout: float,
    out: list[dict],
) -> None:
    latencies_ms: list[float] = []
    status_counts: dict[str, int] = {}
    total = 0
    ok_2xx = 0
    errors = 0

    conn = None

    def connect():
        if scheme == "https":
            return http.client.HTTPSConnection(host, port, timeout=timeout)
        return http.client.HTTPConnection(host, port, timeout=timeout)

    try:
        conn = connect()
        while time.monotonic() < deadline:
            start = time.perf_counter()
            code_key = "EXC"
            try:
                conn.request(method, path, body=body_bytes, headers=headers)
                resp = conn.getresponse()
                _ = resp.read()
                code = resp.status
                code_key = str(code)
                total += 1
                if 200 <= code < 300:
                    ok_2xx += 1
                else:
                    errors += 1
            except Exception:
                errors += 1
                total += 1
                try:
                    if conn is not None:
                        conn.close()
                except Exception:
                    pass
                conn = connect()
            finally:
                elapsed_ms = (time.perf_counter() - start) * 1000.0
                latencies_ms.append(elapsed_ms)
                status_counts[code_key] = status_counts.get(code_key, 0) + 1
    finally:
        try:
            if conn is not None:
                conn.close()
        except Exception:
            pass

    out[worker_id] = {
        "total": total,
        "ok_2xx": ok_2xx,
        "errors": errors,
        "latencies_ms": latencies_ms,
        "status_counts": status_counts,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Simple TPS benchmark with only Python stdlib")
    parser.add_argument("--url", required=True, help="Full URL, e.g. http://142.1.46.6:14000/user/2")
    parser.add_argument("--method", default="GET", choices=["GET", "POST"])
    parser.add_argument("--body", default="", help="JSON body for POST")
    parser.add_argument("--threads", type=int, default=50)
    parser.add_argument("--duration", type=float, default=30.0, help="Seconds")
    parser.add_argument("--timeout", type=float, default=5.0, help="Per-request timeout in seconds")
    args = parser.parse_args()

    parsed = urlsplit(args.url)
    if parsed.scheme not in ("http", "https"):
        raise SystemExit("URL must start with http:// or https://")

    host = parsed.hostname
    if host is None:
        raise SystemExit("Could not parse hostname from URL")

    port = parsed.port
    if port is None:
        port = 443 if parsed.scheme == "https" else 80

    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query

    body_bytes = None
    headers = {"Connection": "keep-alive"}
    if args.method == "POST":
        body_bytes = args.body.encode("utf-8")
        headers["Content-Type"] = "application/json"
        headers["Content-Length"] = str(len(body_bytes))

    deadline = time.monotonic() + args.duration
    results: list[dict] = [{} for _ in range(args.threads)]
    threads: list[threading.Thread] = []

    start_wall = time.perf_counter()
    for i in range(args.threads):
        t = threading.Thread(
            target=worker,
            args=(
                i,
                parsed.scheme,
                host,
                port,
                path,
                args.method,
                body_bytes,
                headers,
                deadline,
                args.timeout,
                results,
            ),
            daemon=True,
        )
        threads.append(t)
        t.start()

    for t in threads:
        t.join()
    elapsed = time.perf_counter() - start_wall

    total = 0
    ok_2xx = 0
    errors = 0
    all_latencies: list[float] = []
    status_counts: dict[str, int] = {}

    for r in results:
        total += r["total"]
        ok_2xx += r["ok_2xx"]
        errors += r["errors"]
        all_latencies.extend(r["latencies_ms"])
        for k, v in r["status_counts"].items():
            status_counts[k] = status_counts.get(k, 0) + v

    print(f"URL:            {args.url}")
    print(f"Method:         {args.method}")
    print(f"Threads:        {args.threads}")
    print(f"Elapsed:        {elapsed:.2f} s")
    print(f"Total requests: {total}")
    print(f"2xx responses:  {ok_2xx}")
    print(f"Non-2xx/Errors: {total - ok_2xx}")
    print(f"TPS (all):      {total / elapsed:.2f}")
    print(f"TPS (2xx only): {ok_2xx / elapsed:.2f}")
    print(f"Latency p50:    {percentile(all_latencies, 0.50):.2f} ms")
    print(f"Latency p95:    {percentile(all_latencies, 0.95):.2f} ms")
    print(f"Latency p99:    {percentile(all_latencies, 0.99):.2f} ms")
    print(f"Latency max:    {max(all_latencies) if all_latencies else 0.0:.2f} ms")
    print("Status counts:")
    for code in sorted(status_counts.keys(), key=lambda x: (x == "EXC", x)):
        print(f"  {code}: {status_counts[code]}")


if __name__ == "__main__":
    main()