package com.temporal.client.bridge;

import com.mojang.logging.LogUtils;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.slf4j.Logger;

/** Native {@code ffxApiMessage} callback, {@code void(uint32_t type, const wchar_t* message)}, that logs FidelityFX's messages. */
final class FfxMessageCallback extends Callback implements CallbackI {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FFICIF CIF = APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_void,
            LibFFI.ffi_type_uint32, LibFFI.ffi_type_pointer);

    FfxMessageCallback() {
        super(CIF);
    }

    @Override
    public FFICIF getCallInterface() {
        return CIF;
    }

    @Override
    public void callback(long ret, long args) {
        int type = MemoryUtil.memGetInt(MemoryUtil.memGetAddress(args));
        long text = MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(args + Long.BYTES));
        String message = text == MemoryUtil.NULL ? "" : MemoryUtil.memUTF16(text).trim();
        if (type == 0) {
            LOGGER.error("FidelityFX: {}", message);
        } else {
            LOGGER.warn("FidelityFX: {}", message);
        }
    }
}
