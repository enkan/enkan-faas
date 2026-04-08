#!/usr/bin/env bash
# coldstart.sh — Measure cold-start latency for enkan-todo Lambda functions.
#
# Compares two configurations:
#   $LATEST  — normal JVM cold start (no SnapStart optimization)
#   live     — SnapStart-optimized published version
#
# Cold-start forcing strategy:
#   $LATEST  : toggle env var BENCH_TOGGLE between 0/1 → Lambda discards warm env
#   live     : re-point the alias to the same version → Lambda discards warm env
#              (published versions are immutable; only the alias pointer changes)
#
# Usage:
#   ./coldstart.sh [options]
#
# Options:
#   -f FUNCTION   Function name (default: enkan-todo-read)
#   -n COUNT      Number of cold-start samples per qualifier (default: 5)
#   -r REGION     AWS region (default: ap-northeast-1)
#   -p PROFILE    AWS CLI profile (default: default)
#   -h            Show this help
#
# Requirements:
#   aws CLI v2, python3 (stdlib only)

set -euo pipefail

FUNCTION="enkan-todo-read"
COUNT=5
REGION="ap-northeast-1"
PROFILE="default"

while getopts "f:n:r:p:h" opt; do
    case $opt in
        f) FUNCTION="$OPTARG" ;;
        n) COUNT="$OPTARG" ;;
        r) REGION="$OPTARG" ;;
        p) PROFILE="$OPTARG" ;;
        h) sed -n '/^# Usage/,/^[^#]/p' "$0" | head -n -1 | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown option: -$OPTARG" >&2; exit 1 ;;
    esac
done

AWS="aws --region $REGION --profile $PROFILE"
PAYLOAD='{"version":"2.0","routeKey":"GET /todos","rawPath":"/todos","rawQueryString":"","headers":{"content-type":"application/json"},"requestContext":{"http":{"method":"GET","path":"/todos","protocol":"HTTP/1.1","sourceIp":"127.0.0.1","userAgent":"bench"},"requestId":"bench-0","stage":"live"},"isBase64Encoded":false}'

# ── helpers ───────────────────────────────────────────────────────────────────

wait_updated() {
    $AWS lambda wait function-updated --function-name "$FUNCTION" 2>/dev/null || true
}

# Invoke and return "init_ms invoke_ms" from the REPORT line.
invoke_and_parse() {
    local qualifier="$1"

    local response
    response=$($AWS lambda invoke \
        --function-name "$FUNCTION" \
        --qualifier "$qualifier" \
        --payload "$PAYLOAD" \
        --log-type Tail \
        --cli-binary-format raw-in-base64-out \
        /tmp/bench_response.json \
        --output json 2>&1)

    local log_b64
    log_b64=$(echo "$response" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('LogResult', ''))
")

    python3 -c "
import sys, base64, re
log_b64 = '''$log_b64'''.strip()
if not log_b64:
    print('0.0 0.0')
    sys.exit(0)
log = base64.b64decode(log_b64).decode()
report = next((l for l in log.split('\n') if l.startswith('REPORT')), '')
dur  = re.search(r'Duration: ([\d.]+) ms', report)
init = re.search(r'Init Duration: ([\d.]+) ms', report)
invoke_ms = float(dur.group(1)) if dur else 0.0
init_ms   = float(init.group(1)) if init else 0.0
print(f'{init_ms:.1f} {invoke_ms:.1f}')
"
}

# Force cold start for $LATEST: toggle BENCH_TOGGLE env var.
force_cold_latest() {
    local toggle="$1"
    $AWS lambda update-function-configuration \
        --function-name "$FUNCTION" \
        --environment "Variables={JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1,BENCH_TOGGLE=$toggle}" \
        --output text --query 'LastUpdateStatus' > /dev/null
    wait_updated
}

# Force cold start for the live alias: re-point alias to same version.
# Lambda treats an alias update as a signal to evict warm containers.
force_cold_alias() {
    local version
    version=$($AWS lambda get-alias \
        --function-name "$FUNCTION" \
        --name live \
        --query 'FunctionVersion' --output text)
    $AWS lambda update-alias \
        --function-name "$FUNCTION" \
        --name live \
        --function-version "$version" \
        --output text --query 'AliasArn' > /dev/null
    # Brief pause to let Lambda reconcile
    sleep 2
}

print_stats() {
    local label="$1"
    shift
    python3 - "$label" "$@" <<'PYEOF'
import sys, statistics

label = sys.argv[1]
pairs = [(float(sys.argv[i]), float(sys.argv[i+1])) for i in range(2, len(sys.argv), 2)]
inits   = [p[0] for p in pairs]
invokes = [p[1] for p in pairs]

def fmt(vals):
    mn  = min(vals)
    mx  = max(vals)
    avg = statistics.mean(vals)
    med = statistics.median(vals)
    n   = len(vals)
    return f"  min={mn:6.1f}  avg={avg:6.1f}  med={med:6.1f}  max={mx:6.1f}  ms  (n={n})"

snapstart = all(v == 0.0 for v in inits)
print(f"\n  ── {label} ──")
if snapstart:
    print(f"  Init Duration :    0.0 ms  (SnapStart checkpoint restore — JVM init skipped)")
else:
    print(f"  Init Duration :  {fmt(inits)}")
print(f"  Handler invoke:  {fmt(invokes)}")
PYEOF
}

# ── main ──────────────────────────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Enkan FaaS — Cold-Start Benchmark                          ║"
echo "╠══════════════════════════════════════════════════════════════╣"
printf "║  Function : %-48s║\n" "$FUNCTION"
printf "║  Region   : %-48s║\n" "$REGION"
printf "║  Samples  : %-48s║\n" "$COUNT per qualifier"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Verify live alias exists
if ! $AWS lambda get-alias --function-name "$FUNCTION" --name live > /dev/null 2>&1; then
    echo "ERROR: alias 'live' not found on $FUNCTION." >&2
    echo "       Run 'sam deploy' with AutoPublishAlias: live first." >&2
    exit 1
fi

for QUALIFIER in "\$LATEST" "live"; do
    [[ "$QUALIFIER" == "live" ]] && label="live (SnapStart)" || label="\$LATEST (no SnapStart)"

    echo "Measuring $label  ($COUNT samples) ..."
    raw_values=()
    toggle=0

    for i in $(seq 1 "$COUNT"); do
        printf "  sample %d/%d ... " "$i" "$COUNT"

        if [[ "$QUALIFIER" == "live" ]]; then
            force_cold_alias
        else
            force_cold_latest "$toggle"
            toggle=$(( 1 - toggle ))
        fi

        result=$(invoke_and_parse "$QUALIFIER")
        init_ms=$(echo "$result" | awk '{print $1}')
        invoke_ms=$(echo "$result" | awk '{print $2}')
        printf "init=%-8s invoke=%s ms\n" "${init_ms}" "${invoke_ms}"
        raw_values+=("$init_ms" "$invoke_ms")
    done

    print_stats "$label" "${raw_values[@]}"
    echo ""
done

# Restore original env (remove BENCH_TOGGLE)
echo "Restoring original environment ..."
$AWS lambda update-function-configuration \
    --function-name "$FUNCTION" \
    --environment "Variables={JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1}" \
    --output text --query 'LastUpdateStatus' > /dev/null

echo "Done."
