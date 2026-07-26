package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import java.util.Objects;

/** Strongly typed views over engine signal values generated from the accepted API schema. */
public final class FoundryTypedSignal {
    private FoundryTypedSignal() {}

    public static final class Of0 {
        private final FoundrySignal signal;

        public Of0(FoundrySignal signal) {
            this.signal = Objects.requireNonNull(signal, "signal");
        }

        public FoundrySignal.Connection connect(Listener listener) {
            Objects.requireNonNull(listener, "listener");
            return signal.connect(
                    FoundryCallable.fixed(
                            0,
                            arguments -> {
                                listener.accept();
                                return Variant.nil();
                            }));
        }

        public void emit() {
            signal.emit();
        }

        @FunctionalInterface
        public interface Listener {
            void accept();
        }
    }

    public static final class Of1<A> {
        private final FoundrySignal signal;
        private final VariantCodec<A> a;

        public Of1(FoundrySignal signal, VariantCodec<A> a) {
            this.signal = Objects.requireNonNull(signal, "signal");
            this.a = Objects.requireNonNull(a, "a");
        }

        public FoundrySignal.Connection connect(Listener<A> listener) {
            Objects.requireNonNull(listener, "listener");
            return signal.connect(
                    FoundryCallable.fixed(
                            1,
                            arguments -> {
                                listener.accept(a.decode(arguments.get(0)));
                                return Variant.nil();
                            }));
        }

        public void emit(A value) {
            signal.emit(a.encode(value));
        }

        @FunctionalInterface
        public interface Listener<A> {
            void accept(A value);
        }
    }

    public static final class Of2<A, B> {
        private final FoundrySignal signal;
        private final VariantCodec<A> a;
        private final VariantCodec<B> b;

        public Of2(FoundrySignal signal, VariantCodec<A> a, VariantCodec<B> b) {
            this.signal = Objects.requireNonNull(signal, "signal");
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
        }

        public FoundrySignal.Connection connect(Listener<A, B> listener) {
            Objects.requireNonNull(listener, "listener");
            return signal.connect(
                    FoundryCallable.fixed(
                            2,
                            arguments -> {
                                listener.accept(
                                        a.decode(arguments.get(0)), b.decode(arguments.get(1)));
                                return Variant.nil();
                            }));
        }

        public void emit(A first, B second) {
            signal.emit(a.encode(first), b.encode(second));
        }

        @FunctionalInterface
        public interface Listener<A, B> {
            void accept(A first, B second);
        }
    }

    public static final class Of3<A, B, C> {
        private final FoundrySignal signal;
        private final VariantCodec<A> a;
        private final VariantCodec<B> b;
        private final VariantCodec<C> c;

        public Of3(FoundrySignal signal, VariantCodec<A> a, VariantCodec<B> b, VariantCodec<C> c) {
            this.signal = Objects.requireNonNull(signal, "signal");
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
            this.c = Objects.requireNonNull(c, "c");
        }

        public FoundrySignal.Connection connect(Listener<A, B, C> listener) {
            Objects.requireNonNull(listener, "listener");
            return signal.connect(
                    FoundryCallable.fixed(
                            3,
                            arguments -> {
                                listener.accept(
                                        a.decode(arguments.get(0)),
                                        b.decode(arguments.get(1)),
                                        c.decode(arguments.get(2)));
                                return Variant.nil();
                            }));
        }

        public void emit(A first, B second, C third) {
            signal.emit(a.encode(first), b.encode(second), c.encode(third));
        }

        @FunctionalInterface
        public interface Listener<A, B, C> {
            void accept(A first, B second, C third);
        }
    }

    public static final class Of4<A, B, C, D> {
        private final FoundrySignal signal;
        private final VariantCodec<A> a;
        private final VariantCodec<B> b;
        private final VariantCodec<C> c;
        private final VariantCodec<D> d;

        public Of4(
                FoundrySignal signal,
                VariantCodec<A> a,
                VariantCodec<B> b,
                VariantCodec<C> c,
                VariantCodec<D> d) {
            this.signal = Objects.requireNonNull(signal, "signal");
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
            this.c = Objects.requireNonNull(c, "c");
            this.d = Objects.requireNonNull(d, "d");
        }

        public FoundrySignal.Connection connect(Listener<A, B, C, D> listener) {
            Objects.requireNonNull(listener, "listener");
            return signal.connect(
                    FoundryCallable.fixed(
                            4,
                            arguments -> {
                                listener.accept(
                                        a.decode(arguments.get(0)),
                                        b.decode(arguments.get(1)),
                                        c.decode(arguments.get(2)),
                                        d.decode(arguments.get(3)));
                                return Variant.nil();
                            }));
        }

        public void emit(A first, B second, C third, D fourth) {
            signal.emit(a.encode(first), b.encode(second), c.encode(third), d.encode(fourth));
        }

        @FunctionalInterface
        public interface Listener<A, B, C, D> {
            void accept(A first, B second, C third, D fourth);
        }
    }

    public static final class Of5<A, B, C, D, E> {
        private final FoundrySignal signal;
        private final VariantCodec<A> a;
        private final VariantCodec<B> b;
        private final VariantCodec<C> c;
        private final VariantCodec<D> d;
        private final VariantCodec<E> e;

        public Of5(
                FoundrySignal signal,
                VariantCodec<A> a,
                VariantCodec<B> b,
                VariantCodec<C> c,
                VariantCodec<D> d,
                VariantCodec<E> e) {
            this.signal = Objects.requireNonNull(signal, "signal");
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
            this.c = Objects.requireNonNull(c, "c");
            this.d = Objects.requireNonNull(d, "d");
            this.e = Objects.requireNonNull(e, "e");
        }

        public FoundrySignal.Connection connect(Listener<A, B, C, D, E> listener) {
            Objects.requireNonNull(listener, "listener");
            return signal.connect(
                    FoundryCallable.fixed(
                            5,
                            arguments -> {
                                listener.accept(
                                        a.decode(arguments.get(0)),
                                        b.decode(arguments.get(1)),
                                        c.decode(arguments.get(2)),
                                        d.decode(arguments.get(3)),
                                        e.decode(arguments.get(4)));
                                return Variant.nil();
                            }));
        }

        public void emit(A first, B second, C third, D fourth, E fifth) {
            signal.emit(
                    a.encode(first),
                    b.encode(second),
                    c.encode(third),
                    d.encode(fourth),
                    e.encode(fifth));
        }

        @FunctionalInterface
        public interface Listener<A, B, C, D, E> {
            void accept(A first, B second, C third, D fourth, E fifth);
        }
    }
}
