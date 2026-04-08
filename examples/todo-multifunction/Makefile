# SAM invokes build-<LogicalId> targets with $ARTIFACTS_DIR set.
# This Makefile lives in examples/todo-multifunction/.
#
# The enkan-faas-maven-plugin must be installed to the local repository before
# the bundle goal runs, because Maven resolves reactor plugins from the local
# repository rather than target/classes. We install all prerequisite modules
# first, then run package on this module only.

MAKEFILE_DIR := $(dir $(realpath $(lastword $(MAKEFILE_LIST))))
REPO_ROOT    := $(realpath $(MAKEFILE_DIR)/../..)
MODULE       := examples/todo-multifunction

# Install all modules up to (but not including) the example, then package only
# the example so the plugin runs from the local repository.
PREREQ_INSTALL = cd "$(REPO_ROOT)" && mvn install \
  -pl enkan-component-faas,enkan-component-faas-aws,enkan-faas-maven-plugin -am \
  -DskipTests -q

EXAMPLE_PACKAGE = cd "$(REPO_ROOT)" && mvn package \
  -pl $(MODULE) -DskipTests

# --- JVM (java25 + SnapStart) -------------------------------------------------

build-TodoReadFunction:
	$(PREREQ_INSTALL)
	$(EXAMPLE_PACKAGE)
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp "$(REPO_ROOT)/$(MODULE)/target/todo-read-shaded.jar" "$(ARTIFACTS_DIR)/lib/"

build-TodoWriteFunction:
	$(PREREQ_INSTALL)
	$(EXAMPLE_PACKAGE)
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp "$(REPO_ROOT)/$(MODULE)/target/todo-write-shaded.jar" "$(ARTIFACTS_DIR)/lib/"

# --- GraalVM native (provided.al2023) ----------------------------------------
# Must run on Linux arm64. On macOS use Docker:
#   docker run --rm -v $(REPO_ROOT):/src -w /src \
#     ghcr.io/graalvm/native-image-community:25 \
#     make -f examples/todo-multifunction/Makefile \
#       ARTIFACTS_DIR=<path> build-TodoReadFunctionNative

build-TodoReadFunctionNative:
	$(PREREQ_INSTALL)
	cd "$(REPO_ROOT)" && mvn package -pl $(MODULE) -DskipTests -Pnative
	cp "$(REPO_ROOT)/$(MODULE)/target/todo-read" "$(ARTIFACTS_DIR)/bootstrap"
	chmod +x "$(ARTIFACTS_DIR)/bootstrap"

build-TodoWriteFunctionNative:
	$(PREREQ_INSTALL)
	cd "$(REPO_ROOT)" && mvn package -pl $(MODULE) -DskipTests -Pnative
	cp "$(REPO_ROOT)/$(MODULE)/target/todo-write" "$(ARTIFACTS_DIR)/bootstrap"
	chmod +x "$(ARTIFACTS_DIR)/bootstrap"
