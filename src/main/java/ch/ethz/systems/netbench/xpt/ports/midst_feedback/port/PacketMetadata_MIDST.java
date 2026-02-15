package ch.ethz.systems.netbench.xpt.ports.midst_feedback.port;

/**
 * Helper class to store MIDST metadata associated with a packet
 * while it is inside the MIDSTCapableOutputPort's system (e.g., in queue or map).
 *
 * NOTE: This implementation does not perform defensive copies of the arrays
 * passed to the constructor or returned by getters. The caller should be aware
 * that modifying the arrays after creation or retrieval will affect the stored state.
 */
public class PacketMetadata_MIDST {
    private final int[] ingressSketchCounts; // Counts stored *after* incrementing in sin
    private final int[] hashIndices;         // Indices used in the sketches

    /**
     * Constructor. Stores references to the provided arrays.
     *
     * @param ingressSketchCounts Counts from the ingress sketch (sin) for each hash function *after* increment.
     * @param hashIndices The hash indices used for this packet in the sketches.
     */
    public PacketMetadata_MIDST(int[] ingressSketchCounts, int[] hashIndices) {
        // Stores references, does not copy arrays for performance.
        this.ingressSketchCounts = ingressSketchCounts;
        this.hashIndices = hashIndices;
    }

    /**
     * Get the counts recorded from the ingress sketch (sin) when the packet arrived.
     * Note: These counts typically reflect the value *after* the packet's own increment.
     * Returns a reference to the internal array.
     *
     * @return Reference to the ingress sketch counts array.
     */
    public int[] getIngressSketchCounts() {
        return ingressSketchCounts;
    }

    /**
     * Get the hash indices used for this packet in both sin and sout sketches.
     * Returns a reference to the internal array.
     *
     * @return Reference to the hash indices array.
     */
    public int[] getHashIndices() {
        return hashIndices;
    }
}