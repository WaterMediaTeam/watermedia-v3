package org.watermedia.test;

import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.javacpp.*;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.watermedia.WaterMedia.NAME;

import java.nio.ByteBuffer;

public class FFmpegVideoPlayer {
    private static long window;

    public static void main(String[] args) {
        String videoPath = "https://files.catbox.moe/3nhndw.mp4";

        // --- INIT GLFW + OpenGL ---
        if (!glfwInit()) throw new IllegalStateException("Failed to initialize GLFW");
        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        // Create the window
        window = glfwCreateWindow(1280, 720, NAME, NULL, NULL);
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);
        GL.createCapabilities();

        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // vsync

        glfwShowWindow(window);
        glEnable(GL_TEXTURE_2D);

        // --- INIT FFMPEG ---
        avformat.avformat_network_init();

        AVFormatContext formatContext = avformat.avformat_alloc_context();
        if (avformat.avformat_open_input(formatContext, videoPath, null, null) < 0)
            throw new RuntimeException("Could not open video file");

        if (avformat.avformat_find_stream_info(formatContext, (PointerPointer<?>) null) < 0)
            throw new RuntimeException("Could not retrieve stream info");

        int videoStreamIndex = -1;
        for (int i = 0; i < formatContext.nb_streams(); i++) {
            AVCodecParameters codecpar = formatContext.streams(i).codecpar();
            if (codecpar.codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
                videoStreamIndex = i;
                break;
            }
        }

        if (videoStreamIndex == -1) throw new RuntimeException("No video stream found.");

        AVCodecParameters codecpar = formatContext.streams(videoStreamIndex).codecpar();
        AVCodec codec = avcodec.avcodec_find_decoder(codecpar.codec_id());
        AVCodecContext codecContext = avcodec.avcodec_alloc_context3(codec);
        avcodec.avcodec_parameters_to_context(codecContext, codecpar);
        avcodec.avcodec_open2(codecContext, codec, (PointerPointer<?>) null);

        int width = codecContext.width();
        int height = codecContext.height();

        AVPacket packet = avcodec.av_packet_alloc();
        AVFrame frame = avutil.av_frame_alloc();
        AVFrame rgbaFrame = avutil.av_frame_alloc();

        int bufferSize = avutil.av_image_get_buffer_size(avutil.AV_PIX_FMT_RGBA, width, height, 1);
        BytePointer buffer = new BytePointer(avutil.av_malloc(bufferSize));
        avutil.av_image_fill_arrays(rgbaFrame.data(), rgbaFrame.linesize(), buffer,
                avutil.AV_PIX_FMT_RGBA, width, height, 1);

        SwsContext swsCtx = swscale.sws_getContext(
                width, height, codecContext.pix_fmt(),
                width, height, avutil.AV_PIX_FMT_RGBA,
                swscale.SWS_BILINEAR, null, null, (DoublePointer) null);

        // --- OpenGL texture ---
        int textureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureID);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        // --- Main loop ---
        while (!glfwWindowShouldClose(window)) {
            if (avformat.av_read_frame(formatContext, packet) >= 0) {
                if (packet.stream_index() == videoStreamIndex) {
                    if (avcodec.avcodec_send_packet(codecContext, packet) == 0) {
                        while (avcodec.avcodec_receive_frame(codecContext, frame) == 0) {
                            swscale.sws_scale(
                                    swsCtx, frame.data(), frame.linesize(), 0, height,
                                    rgbaFrame.data(), rgbaFrame.linesize());

                            // Upload frame data to OpenGL texture
                            glBindTexture(GL_TEXTURE_2D, textureID);
                            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                                    GL_RGBA, GL_UNSIGNED_BYTE,
                                    rgbaFrame.data(0).asByteBuffer());

                            // Render
                            glClear(GL_COLOR_BUFFER_BIT);
                            glBegin(GL_QUADS);
                            glTexCoord2f(0, 1); glVertex2f(-1, -1);
                            glTexCoord2f(1, 1); glVertex2f(1, -1);
                            glTexCoord2f(1, 0); glVertex2f(1, 1);
                            glTexCoord2f(0, 0); glVertex2f(-1, 1);
                            glEnd();

                            glfwSwapBuffers(window);
                            glfwPollEvents();

                            // Optional: small delay for rough sync (imprecise)
                            try { Thread.sleep(33); } catch (InterruptedException ignored) {}
                        }
                    }
                }
                avcodec.av_packet_unref(packet);
            } else {
                // Reached end of file
                avformat.av_seek_frame(formatContext, videoStreamIndex, 0, avformat.AVSEEK_FLAG_BACKWARD);
            }
        }

        // --- Cleanup ---
        glDeleteTextures(textureID);
        avutil.av_free(buffer);
        avutil.av_frame_free(frame);
        avutil.av_frame_free(rgbaFrame);
        avcodec.avcodec_free_context(codecContext);
        avformat.avformat_close_input(formatContext);
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
