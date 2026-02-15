package ch.ethz.systems.netbench.ext.demo;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.network.Packet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.verifyNoInteractions;

@RunWith(MockitoJUnitRunner.class)
public class DemoIntermediaryTest {

    @Mock
    private Packet packet;

    @Test
    public void testGenerator() {
        Simulator.setup(0);
        DemoIntermediaryGenerator generator = new DemoIntermediaryGenerator();
        DemoIntermediary intermediary = (DemoIntermediary) generator.generate(22);
        intermediary.adaptIncoming(packet);
        intermediary.adaptOutgoing(packet);
        verifyNoInteractions(packet);
        Simulator.reset();
    }

}
