package io.quarkus.github.lottery.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.kohsuke.github.PagedIterable;

public final class Streams {

    private Streams() {
    }

    public static <T> Stream<T> toStream(Iterable<T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    }

    public static <T> Stream<T> toStream(Iterator<T> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }

    public static <T> Stream<T> interleave(List<Stream<T>> streams) {
        return toStream(interleaveIterators(
                streams.stream().map(Stream::iterator).toList()));
    }

    private static <T> Iterator<T> interleaveIterators(List<Iterator<T>> iterators) {
        List<Iterator<T>> iteratorsWorkingCopy = new ArrayList<>(iterators);
        return new Iterator<T>() {
            Iterator<Iterator<T>> iteratorsIterator = iteratorsWorkingCopy.iterator();
            Iterator<T> nextIterator = null;

            @Override
            public boolean hasNext() {
                while (nextIterator == null && !iteratorsWorkingCopy.isEmpty()) {
                    if (!iteratorsIterator.hasNext()) {
                        iteratorsIterator = iteratorsWorkingCopy.iterator();
                    }
                    nextIterator = iteratorsIterator.next();
                    if (!nextIterator.hasNext()) {
                        iteratorsIterator.remove();
                        nextIterator = null;
                    }
                }
                return nextIterator != null;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                T next = nextIterator.next();
                nextIterator = null;
                return next;
            }
        };
    }

    public static <T> Stream<T> toStream(PagedIterable<T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    }
}
