package de.invesdwin.context.julia.runtime.libjuliaclj.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.concurrent.NotThreadSafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import clojure.lang.IFn;
import de.invesdwin.context.integration.marshaller.MarshallerJsonJackson;
import de.invesdwin.context.julia.runtime.contract.IScriptTaskRunnerJulia;
import de.invesdwin.context.julia.runtime.contract.JuliaResetContext;
import de.invesdwin.context.julia.runtime.libjuliaclj.LibjuliacljProperties;
import de.invesdwin.context.julia.runtime.libjuliaclj.LibjuliacljScriptTaskEngineJulia;
import de.invesdwin.util.concurrent.Executors;
import de.invesdwin.util.concurrent.WrappedExecutorService;
import de.invesdwin.util.concurrent.lock.IReentrantLock;
import de.invesdwin.util.concurrent.lock.Locks;
import de.invesdwin.util.lang.Strings;
import de.invesdwin.util.math.Booleans;
import de.invesdwin.util.math.Bytes;
import de.invesdwin.util.math.Characters;
import de.invesdwin.util.math.Doubles;
import de.invesdwin.util.math.Floats;
import de.invesdwin.util.math.Integers;
import de.invesdwin.util.math.Longs;
import de.invesdwin.util.math.Shorts;

/**
 * Always acquire the lock first before accessing the julia engine instance. Also make sure commands are only executed
 * from inside the EXECUTOR thread. Otherwise julia will throw errors due to being thread bound.
 * 
 * https://cnuernber.github.io/libjulia-clj/signals.html
 */
@NotThreadSafe
public final class UncheckedJuliaEngineWrapper implements IJuliaEngineWrapper {

    public static final WrappedExecutorService EXECUTOR = Executors
            .newFixedThreadPool(InitializingJuliaEngineWrapper.class.getSimpleName(), 1);
    public static final UncheckedJuliaEngineWrapper INSTANCE = new UncheckedJuliaEngineWrapper();

    private final IReentrantLock lock;
    private final JuliaResetContext resetContext;
    private final ObjectMapper mapper;
    private IFn putGlobalFunction;

    private UncheckedJuliaEngineWrapper() {
        this.mapper = MarshallerJsonJackson.getInstance().getJsonMapper(false);
        this.lock = Locks.newReentrantLock(UncheckedJuliaEngineWrapper.class.getSimpleName() + "_lock");
        this.resetContext = new JuliaResetContext(new LibjuliacljScriptTaskEngineJulia(this));
    }

    public void init() {
        final Map<String, Object> initParams = new HashMap<String, Object>();
        //        initParams.put("n-threads", 8);
        //        initParams.put("signals-enabled?", false);
        initParams.put("julia-home", LibjuliacljProperties.JULIA_HOME.getAbsolutePath());
        initParams.put("n-threads", Executors.getCpuThreadPoolCount());
        final Object result = libjulia_clj.java_api.initialize(initParams);
        final String resultStr = String.valueOf(result);
        if (!":ok".equals(resultStr)) {
            throw new IllegalStateException("Initialization failed: " + resultStr);
        }
        eval("using InteractiveUtils; using Pkg; isinstalled(pkg::String) = any(x -> x.name == pkg && x.is_direct_dep, values(Pkg.dependencies())); if !isinstalled(\"JSON\"); Pkg.add(\"JSON\"); end; using JSON;");

        this.putGlobalFunction = (IFn) libjulia_clj.java_api.runString(
                "function libjuliaclj_putGlobal(variable, value); global __ans__ = value; eval(Meta.parse(\"global \"*variable*\" = __ans__\")); return nothing; end");

        this.resetContext.init();
    }

    @Override
    public void eval(final String command) {
        final String adjCommand = command + ";\ntrue";
        IScriptTaskRunnerJulia.LOG.debug("> %s", command);
        final Object result = libjulia_clj.java_api.runString(adjCommand);
        IScriptTaskRunnerJulia.LOG.debug("< %s", result);
        if (!(result instanceof Boolean) || !Booleans.checkedCast(result)) {
            throw new IllegalStateException("Command [" + command + "] failed: " + result);
        }
    }

    @Override
    public JsonNode getAsJsonNode(final String variable) {
        final String command = "JSON.json(" + variable + ")";
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object result = libjulia_clj.java_api.runString(command);
        try {
            final JsonNode node = mapper.readTree(String.valueOf(result));
            if (node instanceof NullNode) {
                return null;
            } else {
                return node;
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String[] getStringVectorAsJson(final String variable) {
        JsonNode strs = getAsJsonNode(variable);
        if (strs == null) {
            return null;
        }
        //unwrap array
        while (strs.size() == 1 && strs.get(0).size() > 1) {
            strs = strs.get(0);
        }
        final String[] values = new String[strs.size()];
        for (int i = 0; i < values.length; i++) {
            final String str = strs.get(i).asText();
            if (Strings.isBlankOrNullText(str)) {
                values[i] = null;
            } else {
                values[i] = str;
            }
        }
        return values;
    }

    @Override
    public String[][] getStringMatrixAsJson(final String variable) {
        //json returns the columns instead of rows
        final JsonNode strsMatrix = getAsJsonNode(variable);
        if (strsMatrix == null) {
            return null;
        }
        if (strsMatrix.size() == 0) {
            //https://stackoverflow.com/questions/23079625/extract-array-dimensions-in-julia
            //returns a JuliaTuple instead of a JuliaArray, thus use the faster getIntegerVectorAsJson here
            final int[] dims = getIntegerVectorAsJson("size(" + variable + ")");
            if (dims == null) {
                //Sting[0][]
                return Strings.EMPTY_MATRIX;
            }
            final int rows = dims[0];
            final String[][] emptyMatrix = new String[rows][];
            for (int i = 0; i < rows; i++) {
                emptyMatrix[i] = Strings.EMPTY_ARRAY;
            }
            return emptyMatrix;
        }
        //[11 12 13;21 22 23;31 32 33;41 42 43]
        //[[11,21,31,41],[12,22,32,42],[13,23,33,43]]
        final int columns = strsMatrix.size();
        final int rows = strsMatrix.get(0).size();
        final String[][] valuesMatrix = new String[rows][];
        for (int r = 0; r < rows; r++) {
            final String[] values = new String[columns];
            valuesMatrix[r] = values;
            for (int c = 0; c < columns; c++) {
                final String str = strsMatrix.get(c).get(r).asText();
                if (Strings.isBlankOrNullText(str)) {
                    values[c] = null;
                } else {
                    values[c] = str;
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public char[] getCharacterVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final char[] values = new char[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Characters.DEFAULT_MISSING_VALUE;
            } else {
                values[i] = Characters.checkedCast(str);
            }
        }
        return values;
    }

    @Override
    public char[][] getCharacterMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final char[][] valuesMatrix = new char[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final char[] values = new char[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Characters.DEFAULT_MISSING_VALUE;
                } else {
                    values[j] = Characters.checkedCast(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public boolean[] getBooleanVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final boolean[] values = new boolean[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Booleans.DEFAULT_MISSING_VALUE;
            } else {
                values[i] = Boolean.parseBoolean(str);
            }
        }
        return values;
    }

    @Override
    public boolean[][] getBooleanMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final boolean[][] valuesMatrix = new boolean[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final boolean[] values = new boolean[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Booleans.DEFAULT_MISSING_VALUE;
                } else {
                    values[j] = Boolean.parseBoolean(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public byte[] getByteVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final byte[] values = new byte[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Bytes.DEFAULT_MISSING_VALUE;
            } else {
                values[i] = Byte.parseByte(str);
            }
        }
        return values;
    }

    @Override
    public byte[][] getByteMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final byte[][] valuesMatrix = new byte[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final byte[] values = new byte[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Bytes.DEFAULT_MISSING_VALUE;
                } else {
                    values[j] = Byte.parseByte(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public short[] getShortVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final short[] values = new short[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Shorts.DEFAULT_MISSING_VALUE;
            } else {
                values[i] = Short.parseShort(str);
            }
        }
        return values;
    }

    @Override
    public short[][] getShortMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final short[][] valuesMatrix = new short[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final short[] values = new short[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Shorts.DEFAULT_MISSING_VALUE;
                } else {
                    values[j] = Short.parseShort(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public int[] getIntegerVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final int[] values = new int[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Integers.DEFAULT_MISSING_VALUE;
            } else {
                values[i] = Integer.parseInt(str);
            }
        }
        return values;
    }

    @Override
    public int[][] getIntegerMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final int[][] valuesMatrix = new int[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final int[] values = new int[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Integers.DEFAULT_MISSING_VALUE;
                } else {
                    values[j] = Integer.parseInt(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public long[] getLongVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final long[] values = new long[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Longs.DEFAULT_MISSING_VALUE;
            } else {
                values[i] = Long.parseLong(str);
            }
        }
        return values;
    }

    @Override
    public long[][] getLongMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final long[][] valuesMatrix = new long[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final long[] values = new long[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Longs.DEFAULT_MISSING_VALUE;
                } else {
                    values[j] = Long.parseLong(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public float[] getFloatVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final float[] values = new float[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Float.NaN;
            } else {
                values[i] = Float.parseFloat(str);
            }
        }
        return values;
    }

    @Override
    public float[][] getFloatMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final float[][] valuesMatrix = new float[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final float[] values = new float[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Float.NaN;
                } else {
                    values[j] = Float.parseFloat(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public double[] getDoubleVectorAsJson(final String variable) {
        final String[] strs = getStringVectorAsJson(variable);
        if (strs == null) {
            return null;
        }
        final double[] values = new double[strs.length];
        for (int i = 0; i < strs.length; i++) {
            final String str = strs[i];
            if (str == null) {
                values[i] = Double.NaN;
            } else {
                values[i] = Double.parseDouble(str);
            }
        }
        return values;
    }

    @Override
    public double[][] getDoubleMatrixAsJson(final String variable) {
        final String[][] strsMatrix = getStringMatrixAsJson(variable);
        if (strsMatrix == null) {
            return null;
        }
        final double[][] valuesMatrix = new double[strsMatrix.length][];
        for (int i = 0; i < strsMatrix.length; i++) {
            final String[] strs = strsMatrix[i];
            final double[] values = new double[strs.length];
            valuesMatrix[i] = values;
            for (int j = 0; j < strs.length; j++) {
                final String str = strs[j];
                if (str == null) {
                    values[j] = Double.NaN;
                } else {
                    values[j] = Double.parseDouble(str);
                }
            }
        }
        return valuesMatrix;
    }

    @Override
    public void putByteVector(final String variable, final byte[] vector) {
        final Object array = libjulia_clj.java_api.createArray("int8", new int[] { 1, vector.length }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public byte[] getByteVector(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getByteVectorAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        return Bytes.checkedCastVector(map.get("data"));
    }

    private boolean isJuliaArray(final Object array) {
        return "JuliaArray".equals(array.getClass().getSimpleName());
    }

    @Override
    public void putShortVector(final String variable, final short[] vector) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final Object array = libjulia_clj.java_api.createArray("int16", new int[] { 1, vector.length }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public short[] getShortVector(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getShortVectorAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        return Shorts.checkedCastVector(map.get("data"));
    }

    @Override
    public void putIntegerVector(final String variable, final int[] vector) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final Object array = libjulia_clj.java_api.createArray("int32", new int[] { 1, vector.length }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public int[] getIntegerVector(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getIntegerVectorAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        return Integers.checkedCastVector(map.get("data"));
    }

    @Override
    public void putLongVector(final String variable, final long[] vector) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final Object array = libjulia_clj.java_api.createArray("int64", new int[] { 1, vector.length }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public long[] getLongVector(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getLongVectorAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        return Longs.checkedCastVector(map.get("data"));
    }

    @Override
    public void putFloatVector(final String variable, final float[] vector) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final Object array = libjulia_clj.java_api.createArray("float32", new int[] { 1, vector.length }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public float[] getFloatVector(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getFloatVectorAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        return Floats.checkedCastVector(map.get("data"));
    }

    @Override
    public void putDoubleVector(final String variable, final double[] vector) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final Object array = libjulia_clj.java_api.createArray("float64", new int[] { 1, vector.length }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public double[] getDoubleVector(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getDoubleVectorAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        return Doubles.checkedCastVector(map.get("data"));
    }

    @Override
    public void putByteMatrix(final String variable, final byte[][] matrix) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final int cols = matrix[0].length;
        final int rows = matrix.length;
        final byte[] vector = new byte[rows * cols];
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vector[i] = matrix[r][c];
                i++;
            }
        }
        final Object array = libjulia_clj.java_api.createArray("int8", new int[] { cols, rows }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public byte[][] getByteMatrix(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getByteMatrixAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        final byte[] vector = Bytes.checkedCastVector(map.get("data"));
        final int[] dims = (int[]) map.get("shape");
        if (dims.length != 2) {
            throw new IllegalArgumentException("Not a matrix: " + Arrays.toString(dims));
        }
        final int cols = dims[0];
        final int rows = dims[1];
        final byte[][] matrix = new byte[rows][];
        for (int r = 0; r < rows; r++) {
            matrix[r] = new byte[cols];
        }
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = vector[i];
                i++;
            }
        }
        return matrix;
    }

    @Override
    public void putShortMatrix(final String variable, final short[][] matrix) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final int cols = matrix[0].length;
        final int rows = matrix.length;
        final short[] vector = new short[rows * cols];
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vector[i] = matrix[r][c];
                i++;
            }
        }
        final Object array = libjulia_clj.java_api.createArray("int16", new int[] { cols, rows }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public short[][] getShortMatrix(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getShortMatrixAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        final short[] vector = Shorts.checkedCastVector(map.get("data"));
        final int[] dims = (int[]) map.get("shape");
        if (dims.length != 2) {
            throw new IllegalArgumentException("Not a matrix: " + Arrays.toString(dims));
        }
        final int cols = dims[0];
        final int rows = dims[1];
        final short[][] matrix = new short[rows][];
        for (int r = 0; r < rows; r++) {
            matrix[r] = new short[cols];
        }
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = vector[i];
                i++;
            }
        }
        return matrix;
    }

    @Override
    public void putIntegerMatrix(final String variable, final int[][] matrix) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final int cols = matrix[0].length;
        final int rows = matrix.length;
        final int[] vector = new int[rows * cols];
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vector[i] = matrix[r][c];
                i++;
            }
        }
        final Object array = libjulia_clj.java_api.createArray("int32", new int[] { cols, rows }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public int[][] getIntegerMatrix(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getIntegerMatrixAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        final int[] vector = Integers.checkedCastVector(map.get("data"));
        final int[] dims = (int[]) map.get("shape");
        if (dims.length != 2) {
            throw new IllegalArgumentException("Not a matrix: " + Arrays.toString(dims));
        }
        final int cols = dims[0];
        final int rows = dims[1];
        final int[][] matrix = new int[rows][];
        for (int r = 0; r < rows; r++) {
            matrix[r] = new int[cols];
        }
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = vector[i];
                i++;
            }
        }
        return matrix;
    }

    @Override
    public void putLongMatrix(final String variable, final long[][] matrix) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final int cols = matrix[0].length;
        final int rows = matrix.length;
        final long[] vector = new long[rows * cols];
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vector[i] = matrix[r][c];
                i++;
            }
        }
        final Object array = libjulia_clj.java_api.createArray("int64", new int[] { cols, rows }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public long[][] getLongMatrix(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getLongMatrixAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        final long[] vector = Longs.checkedCastVector(map.get("data"));
        final int[] dims = (int[]) map.get("shape");
        if (dims.length != 2) {
            throw new IllegalArgumentException("Not a matrix: " + Arrays.toString(dims));
        }
        final int cols = dims[0];
        final int rows = dims[1];
        final long[][] matrix = new long[rows][];
        for (int r = 0; r < rows; r++) {
            matrix[r] = new long[cols];
        }
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = vector[i];
                i++;
            }
        }
        return matrix;
    }

    @Override
    public void putFloatMatrix(final String variable, final float[][] matrix) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final int cols = matrix[0].length;
        final int rows = matrix.length;
        final float[] vector = new float[rows * cols];
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vector[i] = matrix[r][c];
                i++;
            }
        }
        final Object array = libjulia_clj.java_api.createArray("float32", new int[] { cols, rows }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public float[][] getFloatMatrix(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getFloatMatrixAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        final float[] vector = Floats.checkedCastVector(map.get("data"));
        final int[] dims = (int[]) map.get("shape");
        if (dims.length != 2) {
            throw new IllegalArgumentException("Not a matrix: " + Arrays.toString(dims));
        }
        final int cols = dims[0];
        final int rows = dims[1];
        final float[][] matrix = new float[rows][];
        for (int r = 0; r < rows; r++) {
            matrix[r] = new float[cols];
        }
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = vector[i];
                i++;
            }
        }
        return matrix;
    }

    @Override
    public void putDoubleMatrix(final String variable, final double[][] matrix) {
        IScriptTaskRunnerJulia.LOG.debug("> put %s", variable);
        final int cols = matrix[0].length;
        final int rows = matrix.length;
        final double[] vector = new double[rows * cols];
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vector[i] = matrix[r][c];
                i++;
            }
        }
        final Object array = libjulia_clj.java_api.createArray("float64", new int[] { cols, rows }, vector);
        putGlobalFunction.invoke(variable, array);
    }

    @Override
    public double[][] getDoubleMatrix(final String variable) {
        IScriptTaskRunnerJulia.LOG.debug("> get %s", variable);
        final Object array = libjulia_clj.java_api.runString("__ans__=" + variable + ";\n__ans__");
        if (array == null) {
            return null;
        }
        if (!isJuliaArray(array)) {
            return getDoubleMatrixAsJson("__ans__");
        }
        final Map<?, ?> map = libjulia_clj.java_api.arrayToJVM(array);
        final double[] vector = Doubles.checkedCastVector(map.get("data"));
        final int[] dims = (int[]) map.get("shape");
        if (dims.length != 2) {
            throw new IllegalArgumentException("Not a matrix: " + Arrays.toString(dims));
        }
        final int cols = dims[0];
        final int rows = dims[1];
        final double[][] matrix = new double[rows][];
        for (int r = 0; r < rows; r++) {
            matrix[r] = new double[cols];
        }
        int i = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = vector[i];
                i++;
            }
        }
        return matrix;
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
