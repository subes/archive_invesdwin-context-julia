package de.invesdwin.context.julia.runtime.libjuliaclj.internal;

import java.util.concurrent.Future;

import javax.annotation.concurrent.ThreadSafe;

import com.fasterxml.jackson.databind.JsonNode;

import de.invesdwin.context.julia.runtime.libjuliaclj.LibjuliacljScriptTaskRunnerJulia;
import de.invesdwin.util.concurrent.WrappedExecutorService;
import de.invesdwin.util.concurrent.future.Futures;
import de.invesdwin.util.concurrent.lock.IReentrantLock;

@ThreadSafe
public final class ExecutorJuliaEngineWrapper implements IJuliaEngineWrapper {

    public static final ExecutorJuliaEngineWrapper INSTANCE = new ExecutorJuliaEngineWrapper();
    private final UnsafeJuliaEngineWrapper delegate;
    private final WrappedExecutorService executor;

    private ExecutorJuliaEngineWrapper() {
        this.delegate = UnsafeJuliaEngineWrapper.INSTANCE;
        this.executor = UnsafeJuliaEngineWrapper.EXECUTOR;
    }

    public UnsafeJuliaEngineWrapper getDelegate() {
        return delegate;
    }

    public WrappedExecutorService getExecutor() {
        return executor;
    }

    @Override
    public void eval(final String command) {
        final Future<?> future = LibjuliacljScriptTaskRunnerJulia.EXECUTOR.submit(() -> delegate.eval(command));
        Futures.waitNoInterrupt(future);
    }

    @Override
    public JsonNode getAsJsonNode(final String variable) {
        final Future<JsonNode> future = LibjuliacljScriptTaskRunnerJulia.EXECUTOR
                .submit(() -> delegate.getAsJsonNode(variable));
        return Futures.getNoInterrupt(future);
    }

    @Override
    public void reset() {
        final Future<?> future = LibjuliacljScriptTaskRunnerJulia.EXECUTOR.submit(() -> delegate.reset());
        Futures.waitNoInterrupt(future);
    }

    @Override
    public IReentrantLock getLock() {
        return delegate.getLock();
    }

}