package org.example.rpc.context;

/**
 * Stores the current {@link ClientContext} in an {@link InheritableThreadLocal}
 * so it is available throughout the call chain, including in child threads.
 *
 * <p>Usage on the server side:
 * <pre>{@code
 * ClientContext ctx = ClientContextHolder.get();
 * }</pre>
 */
public class ClientContextHolder {

    private static final InheritableThreadLocal<ClientContext> CONTEXT = new InheritableThreadLocal<>();

    private ClientContextHolder() {}

    /** Set the context for the current thread (and its future children). */
    protected static void set(ClientContext ctx) {
        CONTEXT.set(ctx);
    }

    /** Get the context for the current thread, or {@code null} if none set. */
    public static ClientContext get() {
        return CONTEXT.get();
    }

    /** Remove the context from the current thread. */
    public static void clear() {
        CONTEXT.remove();
    }
}
