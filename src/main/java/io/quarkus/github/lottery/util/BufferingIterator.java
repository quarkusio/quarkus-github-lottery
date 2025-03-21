package io.quarkus.github.lottery.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;

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
    private final int chunkSize;
    private final Consumer<? super List<E>> chunkProcessor;

    private final List<E> buffer = new ArrayList<>();
    private ListIterator<E> bufferIterator = buffer.listIterator();

    /**
     * @param delegate A delegate iterator whose elements should be buffered.
     * @param chunkSize The number of elements to fetch from the delegate, when fetching is needed.
     * @param chunkProcessor A processor for elements fetched from the delegate. Can be used for sorting, shuffling, ...
     */
    public BufferingIterator(Iterator<E> delegate, int chunkSize, Consumer<? super List<E>> chunkProcessor) {
        this.delegate = delegate;
        this.chunkSize = chunkSize;
        this.chunkProcessor = chunkProcessor;
    }

    @Override
    public boolean hasNext() {
        return bufferIterator.hasNext() || delegate.hasNext();
    }

    @Override
    public E next() {
        if (!bufferIterator.hasNext()) {
            loadChunkIntoBuffer();
        }
        return bufferIterator.next();
    }

    private void loadChunkIntoBuffer() {
        if (!delegate.hasNext()) {
            return;
        }

        List<E> chunk = new ArrayList<>();
        for (int i = 0; i < chunkSize && delegate.hasNext(); i++) {
            chunk.add(delegate.next());
        }
        chunkProcessor.accept(chunk);

        for (E newElement : chunk) {
            bufferIterator.add(newElement);
        }
        // Get back to the initial position
        for (int i = 0; i < chunk.size(); i++) {
            bufferIterator.previous();
        }
    }

    @Override
    public void remove() {
        bufferIterator.remove();
    }

    public void backToStart() {
        bufferIterator = buffer.listIterator();
    }
}
