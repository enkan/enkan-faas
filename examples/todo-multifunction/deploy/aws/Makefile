# SAM build targets, one per Function. SAM invokes the matching `build-<LogicalId>`
# target with $ARTIFACTS_DIR set to the staging area for that Function.
#
# Each target builds ONLY its own Maven module (with `-am` to also build the
# transitive `shared` module dependency) and copies the resulting shaded JAR
# into the Function's staging directory. The JAR for todo-read does NOT contain
# todo-write classes, and vice versa — that is the per-Function bundling
# guarantee that this example demonstrates.

REPO_ROOT := $(shell cd ../../../.. && pwd)

build-TodoReadFunction:
	cd $(REPO_ROOT) && mvn -pl examples/todo-multifunction/todo-read -am package -DskipTests
	cp $(REPO_ROOT)/examples/todo-multifunction/todo-read/target/todo-read-*-shaded.jar $(ARTIFACTS_DIR)/

build-TodoWriteFunction:
	cd $(REPO_ROOT) && mvn -pl examples/todo-multifunction/todo-write -am package -DskipTests
	cp $(REPO_ROOT)/examples/todo-multifunction/todo-write/target/todo-write-*-shaded.jar $(ARTIFACTS_DIR)/
