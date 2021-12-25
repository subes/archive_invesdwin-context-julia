package de.invesdwin.context.julia.runtime.julia4j.internal;

import java.io.File;
import java.io.IOException;

import javax.annotation.concurrent.NotThreadSafe;

import org.julia.jni.NativeUtils;
import org.julia.jni.swig.Julia4J;
import org.julia.jni.swig.SWIGTYPE_p_jl_value_t;
import org.julia.jni.swig.SWIGTYPE_p_void;
import org.julia.jni.swig.SwigAccessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import de.invesdwin.context.ContextProperties;
import de.invesdwin.context.integration.marshaller.MarshallerJsonJackson;
import de.invesdwin.context.julia.runtime.contract.IScriptTaskRunnerJulia;
import de.invesdwin.context.julia.runtime.contract.JuliaResetContext;
import de.invesdwin.context.julia.runtime.julia4j.Julia4jProperties;
import de.invesdwin.context.julia.runtime.julia4j.Julia4jScriptTaskEngineJulia;
import de.invesdwin.util.concurrent.Executors;
import de.invesdwin.util.concurrent.WrappedExecutorService;
import de.invesdwin.util.concurrent.future.Futures;
import de.invesdwin.util.concurrent.lock.IReentrantLock;
import de.invesdwin.util.concurrent.lock.Locks;
import de.invesdwin.util.lang.Files;
import de.invesdwin.util.math.Booleans;
import de.invesdwin.util.streams.buffer.bytes.ByteBuffers;
import de.invesdwin.util.streams.buffer.bytes.IByteBuffer;

/**
 * Always acquire the lock first before accessing the julia engine instance. Also make sure commands are only executed
 * from inside the EXECUTOR thread. Otherwise julia will throw errors due to being thread bound.
 * 
 * https://cnuernber.github.io/libjulia-clj/signals.html
 */
@NotThreadSafe
public final class UnsafeJuliaEngineWrapper implements IJuliaEngineWrapper {

    private static final int MAX_STRING_LENGTH = 1024 * 1024;
    public static final WrappedExecutorService EXECUTOR = Executors
            .newFixedThreadPool(UnsafeJuliaEngineWrapper.class.getSimpleName(), 1);
    public static final UnsafeJuliaEngineWrapper INSTANCE = new UnsafeJuliaEngineWrapper();

    private final IReentrantLock lock;
    private final JuliaResetContext resetContext;
    private final ObjectMapper mapper;
    private final File outputFile = new File(ContextProperties.TEMP_DIRECTORY,
            UnsafeJuliaEngineWrapper.class.getSimpleName() + ".out");
    private final String outputFilePath = outputFile.getAbsolutePath();
    private boolean initialized = false;

    private UnsafeJuliaEngineWrapper() {
        final String path = new File(Julia4jProperties.JULIA_LIBRARY_PATH, "libjulia.so").getAbsolutePath();
        System.load(path);
        try {
            ModifiedNativeUtils.loadLibraryFromJar(NativeUtils.libnameToPlatform("libjulia4j"));
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        this.mapper = MarshallerJsonJackson.getInstance().getJsonMapper(false);
        this.lock = Locks.newReentrantLock(UnsafeJuliaEngineWrapper.class.getSimpleName() + "_lock");
        this.resetContext = new JuliaResetContext(new Julia4jScriptTaskEngineJulia(this));
        Futures.waitNoInterrupt(EXECUTOR.submit(() -> init()));
    }

    private void init() {
        if (initialized) {
            return;
        }
        if (Julia4J.jl_is_initialized() == 0) {
            Julia4J.jl_init();
        }
        try {
            Files.touch(outputFile);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        eval("using InteractiveUtils; using Pkg; isinstalled(pkg::String) = any(x -> x.name == pkg && x.is_direct_dep, values(Pkg.dependencies())); if !isinstalled(\"JSON\"); Pkg.add(\"JSON\"); end; using JSON;");
        this.resetContext.init();
        initialized = true;
    }

    @Override
    public void eval(final String eval) {
        final String command = eval + "; true";
        IScriptTaskRunnerJulia.LOG.debug("> eval %s", eval);
        final SWIGTYPE_p_jl_value_t value = Julia4J.jl_eval_string(command);
        try {
            assertResponseNotNull(eval, value);
            final boolean success = Booleans.checkedCast(Julia4J.jl_unbox_bool(value));
            assertResponseSuccess(eval, success);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertResponseNotNull(final String command, final SWIGTYPE_p_jl_value_t value) throws IOException {
        if (value == null) {
            throw new IllegalStateException(
                    "Command [" + command + "] returned null response which might be caused by a parser error");
        }
    }

    @Override
    public JsonNode getAsJsonNode(final String variable) {
        final String command = "__ans__ = JSON.json(" + variable + ")";
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final SWIGTYPE_p_jl_value_t value = Julia4J.jl_eval_string(command);
        try {
            assertResponseNotNull(variable, value);
            final SWIGTYPE_p_void pointer = Julia4J.jl_unbox_voidpointer(value);
            final long address = SwigAccessor.getPointer(pointer);
            final IByteBuffer buffer = ByteBuffers.wrap(address, MAX_STRING_LENGTH);
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < buffer.capacity(); i++) {
                final char c = buffer.getChar(i);
                if (c == '\0') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            final boolean success = Booleans.checkedCast(Julia4J.jl_unbox_bool(value));
            //        IScriptTaskRunnerJulia.LOG.debug("< %s", success);
            assertResponseSuccess(variable, success);
            final String result = sb.toString();
            final JsonNode node = mapper.readTree(result);
            if (node instanceof NullNode) {
                return null;
            } else {
                return node;
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertResponseSuccess(final String command, final boolean success) throws IOException {
        if (!success) {
            throw new IllegalStateException("Command [" + command + "] returned a false response");
        }
    }

    @Override
    public void reset() {
        resetContext.reset();
    }

    @Override
    public IReentrantLock getLock() {
        return lock;
    }

}