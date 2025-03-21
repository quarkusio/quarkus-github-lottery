package io.quarkus.github.lottery.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class BufferingIteratorTest {

    @Test
    void simple() {
        var it = new BufferingIterator<>(List.of(1, 2, 3).iterator(), 1, ignored -> {
        });

        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        assertThat(it).hasNext();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        assertThat(it).hasNext();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        it.remove();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        it.remove();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        it.remove();
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).isExhausted();
    }

    @Test
    void empty() {
        var it = new BufferingIterator<>(Collections.emptyIterator(), 10, ignored -> {
        });

        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).isExhausted();
    }

    @Test
    void chunked() {
        var it = new BufferingIterator<>(List.of(1, 2, 3, 4, 5).iterator(), 2, Collections::reverse);

        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        assertThat(it).hasNext();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        assertThat(it).hasNext();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        it.remove();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(4);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(5);
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(4);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(5);
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);
        it.remove();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(4);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(5);
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(4);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(5);
        it.remove();
        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(4);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(3);
        assertThat(it).isExhausted();
    }

}