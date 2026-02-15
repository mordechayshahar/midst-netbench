# MIDST P4 Build System
# Targets: compile, run, test, clean
# Supports dual-target: BMv2 (v1model) and Tofino (TNA)

P4C        = p4c
BUILD_DIR  = build
BMV2       = simple_switch
THRIFT_PORT = 9090
PYTHON     = python3
PYTEST     = $(PYTHON) -m pytest

# Source paths
BMV2_SRC   = p4src/midst_main.p4
TOFINO_SRC = p4src/tofino/midst_main.p4
BMV2_JSON  = $(BUILD_DIR)/midst_main.json

# Feature flags (uncomment to enable)
# P4_FLAGS += -DMIDST_ENABLE_AIFO
# P4_FLAGS += -DMIDST_ENABLE_PACKS
# P4_FLAGS += -DMIDST_ENABLE_SPPIFO
# P4_FLAGS += -DMIDST_ENABLE_STATISTICS

.PHONY: all compile compile-bmv2 compile-tofino run test test-vq test-sketch \
        test-admission test-e2e test-sppifo clean help

all: compile

help:
	@echo "MIDST P4 Build Targets:"
	@echo ""
	@echo "  BMv2 (v1model):"
	@echo "    compile          - Compile P4 to BMv2 JSON (default)"
	@echo "    compile-bmv2     - Same as compile"
	@echo "    compile-aifo     - Compile with AIFO admission control"
	@echo "    compile-packs    - Compile with PACKS admission control"
	@echo "    compile-sppifo   - Compile with SP-PIFO scheduling"
	@echo "    compile-full     - Compile with all features"
	@echo ""
	@echo "  Tofino (TNA):"
	@echo "    compile-tofino   - Compile for Tofino (requires p4c-tofino)"
	@echo ""
	@echo "  Run:"
	@echo "    run              - Run BMv2 simple_switch"
	@echo "    controller       - Run MIDST control plane"
	@echo "    topology         - Start Mininet topology (requires sudo)"
	@echo ""
	@echo "  Test:"
	@echo "    test             - Run all unit tests"
	@echo "    test-vq          - Run virtual queue tests"
	@echo "    test-sketch      - Run sketch tests"
	@echo "    test-admission   - Run admission control tests"
	@echo "    test-e2e         - Run end-to-end tests"
	@echo "    test-sppifo      - Run SP-PIFO tests"
	@echo ""
	@echo "  Other:"
	@echo "    clean            - Remove build artifacts"

# ---- Compile (BMv2) ----

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

compile: $(BUILD_DIR)
	$(P4C) --target bmv2 --arch v1model \
		$(P4_FLAGS) \
		-o $(BMV2_JSON) \
		$(BMV2_SRC)
	@echo "Compiled BMv2: $(BMV2_JSON)"

compile-bmv2: compile

compile-aifo: P4_FLAGS += -DMIDST_ENABLE_AIFO -DMIDST_ENABLE_STATISTICS
compile-aifo: compile

compile-packs: P4_FLAGS += -DMIDST_ENABLE_PACKS -DMIDST_ENABLE_STATISTICS
compile-packs: compile

compile-sppifo: P4_FLAGS += -DMIDST_ENABLE_SPPIFO -DMIDST_ENABLE_STATISTICS
compile-sppifo: compile

compile-full: P4_FLAGS += -DMIDST_ENABLE_AIFO -DMIDST_ENABLE_PACKS -DMIDST_ENABLE_SPPIFO -DMIDST_ENABLE_STATISTICS
compile-full: compile

# ---- Compile (Tofino) ----
# Requires Barefoot/Intel p4c-tofino compiler

compile-tofino: $(BUILD_DIR)
	@if command -v p4c-tofino >/dev/null 2>&1; then \
		p4c-tofino --target tofino --arch tna \
			$(P4_FLAGS) \
			-o $(BUILD_DIR)/tofino/ \
			$(TOFINO_SRC); \
		echo "Compiled Tofino: $(BUILD_DIR)/tofino/"; \
	else \
		echo "p4c-tofino not found. Validating syntax with p4c..."; \
		$(P4C) --target bmv2 --arch v1model \
			-DMIDST_TARGET_TOFINO \
			$(P4_FLAGS) \
			-o /dev/null \
			$(TOFINO_SRC) 2>&1 || echo "(Tofino uses TNA, so BMv2 syntax check may show expected errors)"; \
	fi

# ---- Run ----

run: compile
	$(BMV2) \
		--device-id 0 \
		--thrift-port $(THRIFT_PORT) \
		--log-console \
		$(BMV2_JSON)

controller:
	cd controller && $(PYTHON) midst_controller.py config.yaml

# ---- Test ----

test:
	$(PYTEST) tests/ -v

test-vq:
	$(PYTEST) tests/test_virtual_queue.py -v

test-sketch:
	$(PYTEST) tests/test_sketches.py -v

test-admission:
	$(PYTEST) tests/test_admission.py -v

test-e2e:
	$(PYTEST) tests/test_e2e.py -v

test-sppifo:
	$(PYTEST) tests/test_sppifo.py -v

# ---- Mininet ----

topology: compile
	sudo $(PYTHON) tests/topology.py --p4-json $(BMV2_JSON)

# ---- Clean ----

clean:
	rm -rf $(BUILD_DIR)
	rm -f *.pcap
	rm -f controller/midst_stats.jsonl
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -name "*.pyc" -delete 2>/dev/null || true
