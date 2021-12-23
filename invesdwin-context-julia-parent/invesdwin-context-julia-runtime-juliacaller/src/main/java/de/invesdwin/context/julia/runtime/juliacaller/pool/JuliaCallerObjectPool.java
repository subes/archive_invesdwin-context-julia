package de.invesdwin.context.julia.runtime.juliacaller.pool;

import java.io.IOException;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Named;

import org.springframework.beans.factory.FactoryBean;

import de.invesdwin.context.integration.network.NetworkUtil;
import de.invesdwin.util.concurrent.pool.timeout.ATimeoutObjectPool;
import de.invesdwin.util.time.date.FTimeUnit;
import de.invesdwin.util.time.duration.Duration;

@ThreadSafe
@Named
public final class JuliaCallerObjectPool extends ATimeoutObjectPool<ExtendedJuliaCaller>
        implements FactoryBean<JuliaCallerObjectPool> {

    public static final JuliaCallerObjectPool INSTANCE = new JuliaCallerObjectPool();

    private JuliaCallerObjectPool() {
        super(Duration.ONE_MINUTE, new Duration(10, FTimeUnit.SECONDS));
    }

    @Override
    public void destroyObject(final ExtendedJuliaCaller obj) {
        try {
            obj.shutdownServer();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected ExtendedJuliaCaller newObject() {
        final int port = NetworkUtil.findAvailableTcpPort();
        final ExtendedJuliaCaller session = new ExtendedJuliaCaller("julia", port);
        try {
            session.startServer();
            session.connect();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    @Override
    protected void passivateObject(final ExtendedJuliaCaller obj) {
        try {
            obj.reset();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public JuliaCallerObjectPool getObject() throws Exception {
        return INSTANCE;
    }

    @Override
    public Class<?> getObjectType() {
        return JuliaCallerObjectPool.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

}