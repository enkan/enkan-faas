#!/usr/bin/env bash
# Build and deploy the todo-multifunction SAM application.
# Usage: ./deploy.sh [--profile <aws-profile>] [--no-confirm-changeset]
#
# sam build produces .aws-sam/build/template.yaml with BuildMethod: makefile
# retained in Metadata. CloudFormation EarlyValidation rejects this because
# the resource already exists as a built artifact. Strip BuildMethod before
# deploying.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BUILT_TEMPLATE=".aws-sam/build/template.yaml"

echo "==> sam build"
sam build

echo "==> Stripping BuildMethod from ${BUILT_TEMPLATE}"
python3 - <<'EOF'
path = ".aws-sam/build/template.yaml"

with open(path) as f:
    lines = f.readlines()

out = []
for line in lines:
    stripped = line.lstrip()
    if stripped.startswith("BuildMethod:"):
        continue   # drop this line
    out.append(line)

with open(path, "w") as f:
    f.writelines(out)

print(f"  done — removed {sum(1 for l in lines if l.lstrip().startswith('BuildMethod:'))} line(s)")
EOF

echo "==> sam deploy $*"
sam deploy "$@"
