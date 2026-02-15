/* MIDST Headers - Tofino TNA Target
 * Includes shared headers/metadata and TNA-specific parser/deparser.
 */

#ifndef _MIDST_TOFINO_HEADERS_P4_
#define _MIDST_TOFINO_HEADERS_P4_

#include "platform.p4"
#include "../../common/headers.p4"

// ============================================================
// Ingress Parser (Tofino TNA)
// ============================================================

parser MidstIngressParser(
    packet_in pkt,
    out headers_t hdr,
    out midst_metadata_t meta,
    out ingress_intrinsic_metadata_t ig_intr_md
) {
    state start {
        pkt.extract(ig_intr_md);
        pkt.advance(PORT_METADATA_SIZE);
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
// Ingress Deparser (Tofino TNA)
// ============================================================

control MidstIngressDeparser(
    packet_out pkt,
    inout headers_t hdr,
    in midst_metadata_t meta,
    in ingress_intrinsic_metadata_for_deparser_t ig_dprsr_md
) {
    // Digest extern for window notifications
    Digest<window_digest_t>() window_digest;

    apply {
        // Send digest if window started
        if (meta.window_started == 1) {
            window_digest.pack({
                meta.digest_type,
                meta.digest_window_number,
                meta.vq_depth
            });
        }

        pkt.emit(hdr.ethernet);
        pkt.emit(hdr.sketch_meta);
        pkt.emit(hdr.ipv4);
        pkt.emit(hdr.tcp);
        pkt.emit(hdr.udp);
    }
}

// ============================================================
// Egress Parser (Tofino TNA)
// ============================================================

parser MidstEgressParser(
    packet_in pkt,
    out headers_t hdr,
    out midst_metadata_t meta,
    out egress_intrinsic_metadata_t eg_intr_md
) {
    state start {
        pkt.extract(eg_intr_md);
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
// Egress Deparser (Tofino TNA)
// ============================================================

control MidstEgressDeparser(
    packet_out pkt,
    inout headers_t hdr,
    in midst_metadata_t meta,
    in egress_intrinsic_metadata_for_deparser_t eg_dprsr_md
) {
    apply {
        pkt.emit(hdr.ethernet);
        // sketch_meta stripped in egress control before reaching here
        pkt.emit(hdr.sketch_meta);
        pkt.emit(hdr.ipv4);
        pkt.emit(hdr.tcp);
        pkt.emit(hdr.udp);
    }
}

// ============================================================
// Digest struct (same as BMv2 version, shared via common/headers.p4)
// ============================================================
// window_digest_t is defined in common/headers.p4 but if not,
// define it here for TNA Digest extern compatibility.

#endif /* _MIDST_TOFINO_HEADERS_P4_ */
