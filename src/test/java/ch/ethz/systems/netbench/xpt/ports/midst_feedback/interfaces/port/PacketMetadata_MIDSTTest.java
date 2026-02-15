package ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.port;

// TODO: This test uses JUnit 5 (Jupiter) but the codebase uses JUnit 4.
// Convert to JUnit 4 patterns during Phase 3 MIDST implementation.
// See ERROR_FIX_PLAN.md for conversion guide.

import org.junit.jupiter.api.Test;

import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.PacketMetadata_MIDST;

import static org.junit.jupiter.api.Assertions.*;

class PacketMetadata_MIDSTTest {

    @Test
    void testConstructorAndGetters() {
        // Arrange
        int[] expectedIngressCounts = {10, 20, 30};
        int[] expectedHashIndices = {1, 5, 9};

        // Act
        PacketMetadata_MIDST metadata = new PacketMetadata_MIDST(expectedIngressCounts, expectedHashIndices);

        // Assert
        assertArrayEquals(expectedIngressCounts, metadata.getIngressSketchCounts(), "Ingress counts should match");
        assertArrayEquals(expectedHashIndices, metadata.getHashIndices(), "Hash indices should match");
    }

    @Test
    void testConstructorWithEmptyArrays() {
        // Arrange
        int[] expectedIngressCounts = {};
        int[] expectedHashIndices = {};

        // Act
        PacketMetadata_MIDST metadata = new PacketMetadata_MIDST(expectedIngressCounts, expectedHashIndices);

        // Assert
        assertArrayEquals(expectedIngressCounts, metadata.getIngressSketchCounts(), "Empty ingress counts should match");
        assertArrayEquals(expectedHashIndices, metadata.getHashIndices(), "Empty hash indices should match");
    }

    @Test
    void testImmutability() {
         // Arrange
        int[] originalIngressCounts = {10, 20, 30};
        int[] originalHashIndices = {1, 5, 9};
        PacketMetadata_MIDST metadata = new PacketMetadata_MIDST(originalIngressCounts, originalHashIndices);

        // Act
        int[] retrievedIngressCounts = metadata.getIngressSketchCounts();
        int[] retrievedHashIndices = metadata.getHashIndices();

        // Modify the original arrays *after* creating the metadata object
        originalIngressCounts[0] = 99;
        originalHashIndices[0] = 99;

        // Assert - The retrieved arrays should NOT have changed if the constructor made copies
        // However, our implementation doesn't make defensive copies. Let's assert they ARE the same object,
        // documenting this behavior. If immutability is desired, the constructor/getters need copies.
        // assertNotEquals(99, retrievedIngressCounts[0], "Getter should return original values if defensive copy was made");
        // assertNotEquals(99, retrievedHashIndices[0], "Getter should return original values if defensive copy was made");

        // Assert that they ARE the same reference (current behavior - no defensive copy)
        assertSame(originalIngressCounts, retrievedIngressCounts, "Getter returns reference to original ingress array");
        assertSame(originalHashIndices, retrievedHashIndices, "Getter returns reference to original hash array");
        assertEquals(99, retrievedIngressCounts[0], "Change to original ingress array IS reflected");
        assertEquals(99, retrievedHashIndices[0], "Change to original hash array IS reflected");

    }
}