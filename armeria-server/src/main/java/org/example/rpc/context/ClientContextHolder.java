package org.example.rpc.context;

import com.linecorp.armeria.common.RequestContext;
import io.netty.util.AttributeKey;

/**
 * Provides access to the {@link ClientContext} of the RPC call currently being served.
 *
 * <p>The context is stored as an attribute of Armeria's {@link RequestContext}, so it:
 * <ul>
 *   <li>lives exactly as long as the request — no leakage between calls served by the
 *       same event loop thread (the classic {@code ThreadLocal} hazard);</li>
 *   <li>is visible wherever the request context is current, i.e. on the event loop and
 *       in Armeria-managed executors such as {@code ServiceRequestContext.blockingTaskExecutor()}
 *       or executors wrapped with {@link RequestContext#makeContextPropagating}.</li>
 * </ul>
 *
 * <p>Usage on the server side:
 * <pre>{@code
 * ClientContext ctx = ClientContextHolder.get();
 * }</pre>
 *
 * <p>When dispatching to your own thread pool, keep the request context attached, e.g.:
 * <pre>{@code
 * ServiceRequestContext ctx = ServiceRequestContext.current();
 * executor.submit(ctx.makeContextAware(() -> {
 *     ClientContextHolder.get(); // still available
 * }));
 * }</pre>
 */
public final class ClientContextHolder {

    private static final AttributeKey<ClientContext> KEY =
            AttributeKey.valueOf(ClientContext.class.getName());

    private ClientContextHolder() {}

    /**
     * Attaches the context to the current Armeria request.
     *
     * @return {@code true} if attached, {@code false} if no {@link RequestContext} is
     *         active on the current thread (the caller is not inside an Armeria request).
     */
    static boolean set(ClientContext ctx) {
        RequestContext reqCtx = RequestContext.currentOrNull();
        if (reqCtx == null) {
            return false;
        }
        reqCtx.setAttr(KEY, ctx);
        return true;
    }

    /**
     * Get the context of the request being served, or {@code null} if none is attached
     * (not inside an Armeria request, or the call had no client context metadata).
     */
    public static ClientContext get() {
        RequestContext reqCtx = RequestContext.currentOrNull();
        return reqCtx == null ? null : reqCtx.attr(KEY);
    }
}
