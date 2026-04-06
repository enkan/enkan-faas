#!/usr/bin/env bash
#
# Cold-start measurement helper for the todo-multifunction SAM stack.
#
# Prerequisites:
#   - AWS CLI configured (aws configure)
#   - jq
#   - The SAM stack already deployed (see ../README.md). The default function
#     names below match examples/todo-multifunction/deploy/aws/template.yaml.
#
# What this does:
#   1. Forces a cold start by updating an environment variable (which creates
#      a new Lambda version and discards any warm containers).
#   2. Invokes the function once and parses the REPORT line from CloudWatch
#      Logs to extract Init Duration and Billed Duration.
#   3. Prints a one-line summary per sample.
#
# Usage:
#   ./measure-cold-start.sh [samples]
#   ./measure-cold-start.sh 5   # 5 cold-start samples per function

set -euo pipefail

SAMPLES=${1:-3}
FUNCTIONS=(enkan-todo-read enkan-todo-write)

force_cold() {
    local fn=$1
    # Toggling an environment variable triggers Lambda to publish a new
    # version and drop all warm containers, guaranteeing the next invoke is
    # a cold start.
    aws lambda update-function-configuration \
        --function-name "$fn" \
        --environment "Variables={COLD_START_KEY=$(date +%s%N)}" \
        >/dev/null
    aws lambda wait function-updated --function-name "$fn"
}

invoke_once() {
    local fn=$1
    aws lambda invoke --function-name "$fn" \
        --cli-binary-format raw-in-base64-out \
        --payload '{"version":"2.0","rawPath":"/todos","requestContext":{"http":{"method":"GET","path":"/todos","protocol":"HTTP/1.1","sourceIp":"127.0.0.1"},"domainName":"bench.local"},"headers":{}}' \
        /tmp/lambda-response.json \
        >/dev/null
}

latest_report() {
    local fn=$1
    local log_group="/aws/lambda/$fn"
    local stream
    stream=$(aws logs describe-log-streams \
        --log-group-name "$log_group" \
        --order-by LastEventTime --descending --limit 1 \
        --query 'logStreams[0].logStreamName' --output text)
    aws logs filter-log-events \
        --log-group-name "$log_group" \
        --log-stream-names "$stream" \
        --filter-pattern '"REPORT RequestId"' \
        --query 'events[-1].message' \
        --output text
}

parse_field() {
    # $1 = REPORT line, $2 = field label (e.g. "Init Duration")
    echo "$1" | grep -oE "$2: [0-9.]+ ms" | head -1
}

echo "Cold-start benchmark — $SAMPLES samples per function"
echo "============================================================="

for fn in "${FUNCTIONS[@]}"; do
    echo
    echo "Function: $fn"
    echo "------------------------------"
    for i in $(seq 1 "$SAMPLES"); do
        force_cold "$fn"
        invoke_once "$fn"
        # Give CloudWatch ~2s to ingest the REPORT line.
        sleep 2
        local_report=$(latest_report "$fn")
        init=$(parse_field "$local_report" "Init Duration")
        billed=$(parse_field "$local_report" "Billed Duration")
        duration=$(parse_field "$local_report" "Duration")
        printf "  sample %d: %s | %s | %s\n" "$i" "$init" "$duration" "$billed"
    done
done

echo
echo "Tip: repeat with SnapStart enabled on a published version to see the"
echo "Init Duration drop from ~2-4s to ~150-400ms."
