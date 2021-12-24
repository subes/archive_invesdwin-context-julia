package de.invesdwin.context.julia.runtime.juliacaller;

import java.io.IOException;

import javax.annotation.concurrent.NotThreadSafe;

import org.julia.jni.NativeUtils;
import org.julia.jni.swig.Julia4J;
import org.junit.jupiter.api.Test;

import de.invesdwin.instrument.DynamicInstrumentationReflections;

/**
 * Test of JNI Julia binding
 *
 *
 * Created by rss on 25/08/2018
 */
@NotThreadSafe
public class Julia4JJNITest {
    static {
        //the same as -Djava.library.path=/usr/lib/x86_64-linux-gnu/julia
        DynamicInstrumentationReflections.addPathToJavaLibraryPath(Julia4jProperties.JULIA_LIBRARY_PATH);
        try {
            NativeUtils.loadLibraryFromJar(NativeUtils.libnameToPlatform("libjulia4j"));
        } catch (final IOException e) {
            //CHECKSTYLE:OFF
            e.printStackTrace();
            //CHECKSTYLE:ON
        }
    }

    @Test
    public void juliaShouldWork() {
        Julia4J.jl_init();
        Julia4J.jl_eval_string("dump(:(1 + 2x^2))");
        Julia4J.jl_atexit_hook(0);
    }

}
