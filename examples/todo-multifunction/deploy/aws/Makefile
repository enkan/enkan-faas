# SAM build targets, one per Function. SAM invokes the matching `build-<LogicalId>`
# target with $ARTIFACTS_DIR set to the staging area for that Function.
#
# Two deployment modes share this Makefile — SAM picks the right target by
# LogicalId based on which template you use:
#
#   sam build --template template.yaml         → build-TodoReadFunction / build-TodoWriteFunction
#                                                 (JVM + SnapStart, shaded JAR)
#
#   sam build --template template-native.yaml  → build-TodoReadFunctionNative / build-TodoWriteFunctionNative
#                                                 (GraalVM native binary as bootstrap)
#
# Each target builds ONLY its own Maven module (with `-am` to also build the
# transitive `shared` module dependency). The artifact for todo-read does NOT
# contain todo-write classes, and vice versa — the per-Function bundling
# guarantee is preserved in both modes.

REPO_ROOT := $(shell cd ../../../.. && pwd)

# --- JVM (java25 + SnapStart) targets -----------------------------------------

build-TodoReadFunction:
	cd $(REPO_ROOT) && mvn -pl examples/todo-multifunction/todo-read -am package -DskipTests
	cp $(REPO_ROOT)/examples/todo-multifunction/todo-read/target/todo-read-*-shaded.jar $(ARTIFACTS_DIR)/

build-TodoWriteFunction:
	cd $(REPO_ROOT) && mvn -pl examples/todo-multifunction/todo-write -am package -DskipTests
	cp $(REPO_ROOT)/examples/todo-multifunction/todo-write/target/todo-write-*-shaded.jar $(ARTIFACTS_DIR)/

# --- Native (provided.al2023) targets -----------------------------------------
#
# These must be run on a Linux x86_64 or arm64 host (matching the Lambda
# architecture) so native-image produces an ELF binary Lambda can execute.
# On macOS, run inside a Linux container:
#
#   docker run --rm -v $(pwd):/src -w /src \
#       ghcr.io/graalvm/native-image-community:25 \
#       mvn -pl examples/todo-multifunction/todo-read -am -Pnative package -DskipTests
#
# SAM on Linux (e.g. CI) will run these targets directly.

build-TodoReadFunctionNative:
	cd $(REPO_ROOT) && mvn -pl examples/todo-multifunction/todo-read -am -Pnative package -DskipTests
	cp $(REPO_ROOT)/examples/todo-multifunction/todo-read/target/todo-read $(ARTIFACTS_DIR)/bootstrap
	chmod +x $(ARTIFACTS_DIR)/bootstrap

build-TodoWriteFunctionNative:
	cd $(REPO_ROOT) && mvn -pl examples/todo-multifunction/todo-write -am -Pnative package -DskipTests
	cp $(REPO_ROOT)/examples/todo-multifunction/todo-write/target/todo-write $(ARTIFACTS_DIR)/bootstrap
	chmod +x $(ARTIFACTS_DIR)/bootstrap
