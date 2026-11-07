package com.temporal.client.bridge;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Writes Streamline's per-frame {@code sl::Constants}, shared by DLSS and DLSS Frame Generation (one set per frame). */
final class SlConstants {
    private static final float INVALID_FLOAT = 3.40282346638528859811704183484516925440e38f;

    /**
     * Streamline wants D3D-style row-vector matrices stored row-major, which is byte-for-byte JOML's column-major
     * layout of the same column-vector matrix. Clip space is converted to its convention: depth runs 0..1. (Y needs no
     * flip: the shared images are top-down, so clip-space up is image row 0 as in D3D.)
     */
    static void write(long c, Frame frame, float jitterSignX, float jitterSignY) {
        Matrix4f projection = frame.projection();
        Vec3 cameraDelta = frame.cameraDelta();
        Matrix4f toStreamlineClip = new Matrix4f().scaling(1.0F, 1.0F, 0.5F).setTranslation(0.0F, 0.0F, 0.5F);
        Matrix4f fromStreamlineClip = new Matrix4f(toStreamlineClip).invert();
        Matrix4f viewToClip = new Matrix4f(toStreamlineClip).mul(projection);
        Matrix4f clipToPrevClip = new Matrix4f(toStreamlineClip)
                .mul(frame.previousViewProjection())
                .translate((float) cameraDelta.x, (float) cameraDelta.y, (float) cameraDelta.z)
                .mul(new Matrix4f(frame.viewProjection()).invert())
                .mul(fromStreamlineClip);

        Streamline.header(c, Streamline.CONSTANTS, 2);
        viewToClip.getToAddress(c + 32);
        new Matrix4f(viewToClip).invert().getToAddress(c + 96);
        new Matrix4f().getToAddress(c + 160);
        clipToPrevClip.getToAddress(c + 224);
        new Matrix4f(clipToPrevClip).invert().getToAddress(c + 288);
        MemoryUtil.memPutFloat(c + 352, jitterSignX * frame.jitterX());   // jitterOffset, pixels
        MemoryUtil.memPutFloat(c + 356, jitterSignY * frame.jitterY());
        MemoryUtil.memPutFloat(c + 360, 1.0F);                            // mvecScale: vectors are in UV units
        MemoryUtil.memPutFloat(c + 364, 1.0F);
        MemoryUtil.memPutFloat(c + 368, 0.0F);                            // cameraPinholeOffset
        MemoryUtil.memPutFloat(c + 372, 0.0F);
        Camera camera = frame.camera();
        Vec3 position = camera.getPosition();
        Vector3f up = camera.getUpVector();
        Vector3f forward = camera.getLookVector();
        Vector3f right = new Vector3f(forward).cross(up);
        putVector(c + 376, (float) position.x, (float) position.y, (float) position.z);
        putVector(c + 388, up.x, up.y, up.z);
        putVector(c + 400, right.x, right.y, right.z);
        putVector(c + 412, forward.x, forward.y, forward.z);
        MemoryUtil.memPutFloat(c + 424, 0.05F);                                              // cameraNear
        MemoryUtil.memPutFloat(c + 428, Minecraft.getInstance().gameRenderer.getDepthFar());  // cameraFar
        MemoryUtil.memPutFloat(c + 432, (float) (2.0 * Math.atan(1.0 / projection.m11())));  // cameraFOV
        MemoryUtil.memPutFloat(c + 436, (float) frame.depth().width / frame.depth().height); // cameraAspectRatio
        MemoryUtil.memPutFloat(c + 440, INVALID_FLOAT);                                      // motionVectorsInvalidValue
        MemoryUtil.memPutByte(c + 444, Streamline.FALSE);           // depthInverted
        MemoryUtil.memPutByte(c + 445, Streamline.TRUE);            // cameraMotionIncluded
        MemoryUtil.memPutByte(c + 446, Streamline.FALSE);           // motionVectors3D
        MemoryUtil.memPutByte(c + 447, frame.reset() ? Streamline.TRUE : Streamline.FALSE);
        MemoryUtil.memPutByte(c + 448, Streamline.FALSE);           // orthographicProjection
        MemoryUtil.memPutByte(c + 449, Streamline.FALSE);           // motionVectorsDilated
        MemoryUtil.memPutByte(c + 450, Streamline.FALSE);           // motionVectorsJittered (asserted offset)
        MemoryUtil.memPutFloat(c + 452, 40.0F);                     // minRelativeLinearDepthObjectSeparation
    }

    private static void putVector(long address, float x, float y, float z) {
        MemoryUtil.memPutFloat(address, x);
        MemoryUtil.memPutFloat(address + 4, y);
        MemoryUtil.memPutFloat(address + 8, z);
    }

    private SlConstants() {}
}
