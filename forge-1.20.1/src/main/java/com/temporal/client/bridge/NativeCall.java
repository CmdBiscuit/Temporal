package com.temporal.client.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;

/**
 * Calls C functions by address through LWJGL's bundled libffi, so Streamline can be used without compiling a JNI library.
 *
 * <p>Every Streamline entry point takes only pointers and 32/64-bit integers and returns {@code sl::Result} (an int).
 * On Windows x64 integers of up to 64 bits travel in the same 8-byte slots as pointers, so each argument is passed as a
 * 64-bit value; a 32-bit parameter simply ignores the upper half.
 */
public final class NativeCall {
    /** Shared by every thread that calls native code (the render thread, a capture thread). */
    private static final Map<Integer, FFICIF> CIFS = new ConcurrentHashMap<>();

    /** Calls a function returning a 32-bit value (or void, in which case the result is meaningless). */
    public static int callInt(long function, long... args) {
        return (int) call(function, args);
    }

    /** Calls a function returning a 64-bit value or pointer. */
    public static long callLong(long function, long... args) {
        return call(function, args);
    }

    private static long call(long function, long[] args) {
        if (function == MemoryUtil.NULL) {
            throw new IllegalStateException("native function not loaded");
        }
        // Returning uint64 is ABI-compatible with int and void returns on x64: the value is read from RAX either way.
        FFICIF cif = CIFS.computeIfAbsent(args.length, NativeCall::prepare);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer values = stack.mallocPointer(args.length);
            for (int i = 0; i < args.length; i++) {
                values.put(i, stack.longs(args[i]));
            }
            var result = stack.calloc(8);
            LibFFI.ffi_call(cif, function, result, values);
            return result.getLong(0);
        }
    }

    private static FFICIF prepare(int argCount) {
        FFICIF cif = FFICIF.calloc();
        PointerBuffer types = MemoryUtil.memAllocPointer(argCount);
        for (int i = 0; i < argCount; i++) {
            types.put(i, LibFFI.ffi_type_uint64);
        }
        // The cif keeps pointing at the type array, so it is intentionally never freed.
        int status = LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_uint64, types);
        if (status != LibFFI.FFI_OK) {
            throw new IllegalStateException("ffi_prep_cif failed: " + status);
        }
        return cif;
    }

    private NativeCall() {}
}
