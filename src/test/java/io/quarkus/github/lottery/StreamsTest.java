package io.quarkus.github.lottery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.quarkus.github.lottery.util.Streams;

class StreamsTest {

    @ParameterizedTest
    @MethodSource("interleaveParamSource")
    void interleave(List<Integer> expectedOutput, List<List<Integer>> input) {
        assertThat(Streams.interleave(input.stream().map(List::stream).toList()))
                .containsExactlyElementsOf(expectedOutput);
    }

    static Stream<Arguments> interleaveParamSource() {
        return Stream.of(
                Arguments.of(List.of(),
                        List.of(List.of())),
                Arguments.of(List.of(),
                        List.of(List.of(), List.of())),
                Arguments.of(List.of(),
                        List.of(List.of(), List.of(), List.of())),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 2, 3, 4, 5))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1), List.of(2), List.of(3), List.of(4), List.of(5))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(), List.of(1), List.of(2), List.of(3), List.of(), List.of(4), List.of(5), List.of())),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 5), List.of(2), List.of(3), List.of(4))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 4), List.of(2, 5), List.of(3))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 4, 5), List.of(2), List.of(3))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 3, 5), List.of(2, 4))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 4, 5), List.of(2), List.of(3))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 3, 4, 5), List.of(2))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1, 3, 4, 5), List.of(2))),
                Arguments.of(List.of(1, 2, 3, 4, 5),
                        List.of(List.of(1), List.of(2, 5), List.of(3), List.of(4))),
                Arguments.of(List.of(1, 2, 3, 4, 5, 6, 7),
                        List.of(List.of(1), List.of(2, 6), List.of(3), List.of(4, 7), List.of(5))));
    }

}