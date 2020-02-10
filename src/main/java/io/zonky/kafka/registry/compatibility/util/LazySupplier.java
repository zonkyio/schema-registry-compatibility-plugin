package io.zonky.kafka.registry.compatibility.util;

import java.util.function.Supplier;

/**
 * Supplier that supplies a single value that it retrieves by calling internal supplier on the first call of get method.
 * Once internal supplier was called, LazySupplier always returns the same resolved value.
 * @param <T>
 */
public class LazySupplier<T> implements Supplier<T> {
    private T value;
    private final Supplier<T> supplier;

    public LazySupplier(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public T get() {
        if (value == null) {
            value = supplier.get();
        }
        return value;
    }
}
