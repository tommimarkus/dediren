package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchTest {
    @Test
    void summarizeComputesMinMedianMaxOverRuns() {
        Bench.Stat stat = Bench.summarize("layout", List.of(50L, 10L, 30L, 40L, 20L));
        assertThat(stat.command()).isEqualTo("layout");
        assertThat(stat.runs()).isEqualTo(5);
        assertThat(stat.minMs()).isEqualTo(10L);
        assertThat(stat.medianMs()).isEqualTo(30L);
        assertThat(stat.maxMs()).isEqualTo(50L);
    }

    @Test
    void summarizeMedianOfEvenCountTakesLowerMiddle() {
        Bench.Stat stat = Bench.summarize("capabilities", List.of(10L, 20L, 30L, 40L));
        assertThat(stat.medianMs()).isEqualTo(20L);
    }

    @Test
    void summarizeRejectsEmptySamples() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> Bench.summarize("x", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
