package io.quarkus.github.lottery.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * An iterator that buffers the content of another iterator,
 * allowing to remove elements from the buffer and to get back to the start of the buffer.
 * <p>
 * This is useful for cases where one might want to skip some elements,
 * and get back to them later.
 *
 * @param <E> the type of elements returned by this iterator
 */
public class BufferingIterator<E> implements Iterator<E> {

    private final Iterator<E> delegate;
    private final List<E> buffer = new ArrayList<>();
    private ListIterator<E> bufferIterator = buffer.listIterator();

    public BufferingIterator(Iterator<E> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
        return bufferIterator.hasNext() || delegate.hasNext();
    }

    @Override
    public E next() {
        if (!bufferIterator.hasNext()) {
            bufferIterator.add(delegate.next());
            bufferIterator.previous();
        }
        return bufferIterator.next();
    }

    @Override
    public void remove() {
        bufferIterator.remove();
    }

    public void backToStart() {
        bufferIterator = buffer.listIterator();
    }
}
