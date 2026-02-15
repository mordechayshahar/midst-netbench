/* MIDST Headers - BMv2 Target
 * Includes shared headers/metadata and BMv2-specific parser/deparser.
 */

#ifndef _MIDST_HEADERS_P4_
#define _MIDST_HEADERS_P4_

#include "../common/headers.p4"

// ============================================================
// Parser (BMv2 v1model)
// ============================================================

parser MidstParser(
    packet_in pkt,
    out headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    state start {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.ethertype) {
            ETHERTYPE_SKETCH_META: parse_sketch_meta;
            ETHERTYPE_IPV4:        parse_ipv4;
            default:               accept;
        }
    }

    state parse_sketch_meta {
        pkt.extract(hdr.sketch_meta);
        transition parse_ipv4;
    }

    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition select(hdr.ipv4.protocol) {
            8w0x06: parse_tcp;
            8w0x11: parse_udp;
            default: accept;
        }
    }

    state parse_tcp {
        pkt.extract(hdr.tcp);
        transition accept;
    }

    state parse_udp {
        pkt.extract(hdr.udp);
        transition accept;
    }
}

// ============================================================
// Deparser (BMv2 v1model)
// ============================================================

control MidstDeparser(
    packet_out pkt,
    in headers_t hdr
) {
    apply {
        pkt.emit(hdr.ethernet);
        // sketch_meta is only emitted if valid (ingress sets it;
        // egress invalidates it before final output)
        pkt.emit(hdr.sketch_meta);
        pkt.emit(hdr.ipv4);
        pkt.emit(hdr.tcp);
        pkt.emit(hdr.udp);
    }
}

// ============================================================
// Checksum Verification / Computation (stubs for BMv2)
// ============================================================

control MidstVerifyChecksum(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply { }
}

control MidstComputeChecksum(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        update_checksum(
            hdr.ipv4.isValid(),
            {
                hdr.ipv4.version,
                hdr.ipv4.ihl,
                hdr.ipv4.dscp,
                hdr.ipv4.ecn,
                hdr.ipv4.total_len,
                hdr.ipv4.identification,
                hdr.ipv4.flags,
                hdr.ipv4.frag_offset,
                hdr.ipv4.ttl,
                hdr.ipv4.protocol,
                hdr.ipv4.src_addr,
                hdr.ipv4.dst_addr
            },
            hdr.ipv4.checksum,
            HashAlgorithm.csum16
        );
    }
}

#endif /* _MIDST_HEADERS_P4_ */
