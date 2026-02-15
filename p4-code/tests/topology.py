"""Mininet topology for MIDST testing.

Single BMv2 switch with 4 hosts:
  h1 (10.0.1.1) -- s1 port 1
  h2 (10.0.2.1) -- s1 port 2
  h3 (10.0.3.1) -- s1 port 3
  h4 (10.0.4.1) -- s1 port 4

Usage:
  sudo python3 tests/topology.py
  # Then in mininet CLI:
  h1 ping h2
  h1 iperf h2
"""

import argparse
import os
import sys

try:
    from mininet.net import Mininet
    from mininet.topo import Topo
    from mininet.log import setLogLevel, info
    from mininet.cli import CLI
    from mininet.link import TCLink
    HAS_MININET = True
except ImportError:
    HAS_MININET = False


# Path to compiled P4 JSON (output of p4c)
P4_JSON = os.path.join(
    os.path.dirname(__file__), "..", "build", "midst_main.json"
)

# BMv2 simple_switch binary
BMV2_SWITCH = "simple_switch"

# Thrift port for runtime CLI
THRIFT_PORT = 9090


class MidstTopo(Topo):
    """Single switch, 4 hosts topology."""

    def build(self, **opts):
        # Add switch
        s1 = self.addSwitch(
            "s1",
            cls=None,  # Will be overridden by Mininet
        )

        # Add hosts with different subnets
        for i in range(1, 5):
            host = self.addHost(
                f"h{i}",
                ip=f"10.0.{i}.1/24",
                mac=f"00:00:00:00:0{i}:01",
            )
            self.addLink(host, s1, bw=100)  # 100 Mbps links


def configure_switch(net):
    """Configure L2 forwarding rules on the switch."""
    s1 = net.get("s1")

    # Add L2 forwarding entries via simple_switch_CLI
    for i in range(1, 5):
        mac = f"00:00:00:00:0{i}:01"
        port = i
        cmd = (
            f'echo "table_add l2_forward forward {mac} => {port}" | '
            f"simple_switch_CLI --thrift-port {THRIFT_PORT}"
        )
        os.system(cmd)

    info("*** L2 forwarding rules installed\n")


def configure_arp(net):
    """Manually configure ARP entries to avoid ARP flooding."""
    hosts = [net.get(f"h{i}") for i in range(1, 5)]
    for src in hosts:
        for dst in hosts:
            if src != dst:
                src.cmd(f"arp -s {dst.IP()} {dst.MAC()}")
    info("*** Static ARP entries configured\n")


def main():
    parser = argparse.ArgumentParser(description="MIDST Mininet Topology")
    parser.add_argument("--p4-json", default=P4_JSON,
                        help="Path to compiled P4 JSON")
    parser.add_argument("--thrift-port", type=int, default=THRIFT_PORT,
                        help="Thrift port for BMv2")
    parser.add_argument("--cli", action="store_true", default=True,
                        help="Open Mininet CLI")
    args = parser.parse_args()

    if not HAS_MININET:
        print("Mininet not installed. Install with:")
        print("  sudo apt-get install mininet")
        print("  pip install mininet")
        sys.exit(1)

    if not os.path.exists(args.p4_json):
        print(f"P4 JSON not found: {args.p4_json}")
        print("Compile first: make compile")
        sys.exit(1)

    setLogLevel("info")

    info("*** Creating MIDST topology\n")
    topo = MidstTopo()

    info("*** Starting network with BMv2\n")
    net = Mininet(
        topo=topo,
        link=TCLink,
        autoStaticArp=False,
    )

    # Start BMv2 switch manually
    s1 = net.get("s1")
    s1_cmd = (
        f"{BMV2_SWITCH} "
        f"--device-id 0 "
        f"--thrift-port {args.thrift_port} "
        f"-i 1@s1-eth1 -i 2@s1-eth2 -i 3@s1-eth3 -i 4@s1-eth4 "
        f"--log-console "
        f"--pcap "
        f"{args.p4_json} &"
    )

    info(f"*** Starting BMv2: {s1_cmd}\n")

    net.start()

    # Wait for switch to initialize
    import time
    time.sleep(2)

    configure_switch(net)
    configure_arp(net)

    info("*** MIDST topology ready\n")
    info("*** Test with: h1 ping h2\n")
    info("*** Generate traffic: h1 iperf h2\n")

    if args.cli:
        CLI(net)

    net.stop()


if __name__ == "__main__":
    main()
