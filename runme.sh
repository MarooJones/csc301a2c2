#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/compiled"

compile_one () {
  local name="$1"
  local out="$OUT/$name"
  mkdir -p "$out"
  find "$SRC/shared" "$SRC/$name" -name "*.java" > "$out/sources.txt"
  javac -encoding UTF-8 -d "$out" @"$out/sources.txt"
  cp "$ROOT/config.json" "$out/config.json"
}

case "${1:-}" in
  -c)
    compile_one "ISCS"
    compile_one "UserService"
    compile_one "ProductService"
    compile_one "OrderService"
    echo "Compiled OK"
    ;;
  -i)
    (cd "$OUT/ISCS" && java --add-modules jdk.httpserver ISCS config.json)
    ;;
  -u)
    (cd "$OUT/UserService" && java --add-modules jdk.httpserver UserService config.json)
    ;;
  -p)
    (cd "$OUT/ProductService" && java --add-modules jdk.httpserver ProductService config.json)
    ;;
  -o)
    (cd "$OUT/OrderService" && java --add-modules jdk.httpserver OrderService config.json)
    ;;
  -w)
    (cd "" && python3 "/compiled/workload_parser.py" "" "/config.json")
    ;;
  *)
    echo "Usage: ./runme.sh -c | -i | -u | -p | -o | -w workloadfile" >&2
    exit 2
    ;;
esac
