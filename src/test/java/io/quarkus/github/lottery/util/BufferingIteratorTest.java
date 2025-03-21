package io.quarkus.github.lottery.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class BufferingIteratorTest {

    @Test
    void nonEmpty() {
        var it = new BufferingIterator<>(List.of(1, 2, 3).iterator());

        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);

        it.backToStart();
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(1);
        assertThat(it).hasNext();
        assertThat(it.next()).isEqualTo(2);

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
        var it = new BufferingIterator<>(Collections.emptyIterator());

        assertThat(it).isExhausted();

        it.backToStart();
        assertThat(it).isExhausted();
    }

}