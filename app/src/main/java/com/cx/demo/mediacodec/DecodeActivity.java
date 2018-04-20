package com.cx.demo.mediacodec;

import java.io.IOException;
import java.nio.ByteBuffer;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class DecodeActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "DecodeActivity";
    private static final String SAMPLE = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/V_20180417_114325.mp4";
    private PlayerThread2 mPlayer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        setContentView(sv);
    }

    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mPlayer == null) {
            mPlayer = new PlayerThread2(holder.getSurface());
            mPlayer.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mPlayer != null) {
            mPlayer.interrupt();
        }
    }

    private class PlayerThread extends Thread {
        private MediaExtractor extractor;
        private MediaCodec decoder;
        private Surface surface;

        public PlayerThread(Surface surface) {
            this.surface = surface;
        }

        @Override
        public void run() {
            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(SAMPLE);
            } catch (IOException e) {
                e.printStackTrace();
            }

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    try {

                        getDecoderMediaCodecInfo(mime);
                        decoder = MediaCodec.createByCodecName("OMX.google.h264.decoder");
//                        decoder = MediaCodec.createDecoderByType(mime);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (surface.isValid())
                        decoder.configure(format, surface, null, 0);
                    break;
                }
            }

            if (decoder == null) {
                Log.e("DecodeActivity", "Can't find video info!");
                return;
            }

            decoder.start();

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            BufferInfo info = new BufferInfo();
            boolean isEOS = false;
            long startMs = System.currentTimeMillis();

            while (!Thread.interrupted()) {
                if (!isEOS) {
                    int inIndex = decoder.dequeueInputBuffer(1000000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = inputBuffers[inIndex];
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            // We shouldn't stop the playback at this point, just pass the EOS
                            // flag to decoder, we will get it again from the
                            // dequeueOutputBuffer
                            Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 1000000);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
                        outputBuffers = decoder.getOutputBuffers();
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                        break;
                    default:
                        Log.i(TAG, "run: " + outIndex);
                        ByteBuffer buffer = outputBuffers[outIndex];
                        Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + buffer);

                        // We use a very simple clock to keep the video FPS, or the video
                        // playback will be too fast
                        while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
                            try {
                                sleep(10);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                                break;
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, true);
                        break;
                }

                // All decoded frames have been rendered, we can stop playing now
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                    break;
                }
            }

            decoder.stop();
            decoder.release();
            extractor.release();
        }
    }

    private class PlayerThread2 extends Thread {
        private MediaExtractor extractor;
        private MediaCodec decoder;
        private Surface surface;
        private boolean mVideoExtractorDone = false;
        private MediaFormat outputFormate;
        private boolean VERBOSE = true;
        private int mVideoExtractedFrameCount = 0;

        public PlayerThread2(Surface surface) {
            this.surface = surface;
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void run() {

            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(SAMPLE);
            } catch (IOException e) {
                e.printStackTrace();
            }

            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    break;
                }
            }
            try {
                decoder = MediaCodec.createByCodecName("OMX.google.h264.decoder");
            } catch (IOException e) {
                e.printStackTrace();
            }
            decoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    ByteBuffer decoderInputBuffer = codec.getInputBuffer(index);
                    while (!mVideoExtractorDone) {
                        int size = extractor.readSampleData(decoderInputBuffer, 0);
                        long presentationTime = extractor.getSampleTime();
                        if (VERBOSE) {
                            Log.d(TAG, "video extractor: returned buffer of size " + size);
                            Log.d(TAG, "video extractor: returned buffer for time " + presentationTime);
                        }
                        if (size >= 0) {
                            codec.queueInputBuffer(
                                    index,
                                    0,
                                    size,
                                    presentationTime,
                                    extractor.getSampleFlags());
                        }
                        mVideoExtractorDone = !extractor.advance();
                        if (mVideoExtractorDone) {
                            if (VERBOSE) Log.d(TAG, "video extractor: EOS");
                            codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                        mVideoExtractedFrameCount++;
                        if (size >= 0)
                            break;
                    }

                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull BufferInfo info) {
                    ByteBuffer outBuffer = codec.getOutputBuffer(index);

                    if (VERBOSE) {
                        Log.d(TAG, "video decoder: returned output buffer: " + index);
                        Log.d(TAG, "video decoder: returned buffer of size " + info.size);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (VERBOSE) Log.d(TAG, "video decoder: codec config buffer");
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }
                    if (VERBOSE) {
                        Log.d(TAG, "video decoder: returned buffer for time "
                                + info.presentationTimeUs);
                    }
                    boolean render = info.size != 0;
                    codec.releaseOutputBuffer(index, render);

                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    outputFormate = format;
                }
            });
            if (format == null) return;

            decoder.configure(format, surface, null, 0);
            outputFormate = decoder.getOutputFormat();


            if (decoder == null) {
                Log.e("DecodeActivity", "Can't find video info!");
                return;
            }

            decoder.start();

        }
    }

    private MediaCodecInfo getDecoderMediaCodecInfo(String mine) {
        MediaCodecInfo mediaCodecInfo = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo codecInfo : mediaCodecList.getCodecInfos()) {
                if (!codecInfo.isEncoder()) {
                    Log.i(TAG, "getDecoderMediaCodecInfo0: " + codecInfo.getName());
                    for (String s : codecInfo.getSupportedTypes()) {
                        if (mine.equals(s)) {
                            Log.i(TAG, "getDecoderMediaCodecInfo1: " + codecInfo.getName());
                            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mine);
                            for (int colorFormat : capabilities.colorFormats) {
                                Log.i(TAG, "getDecoderMediaCodecInfo: colorformat: " + colorFormat);
                            }
                            mediaCodecInfo = codecInfo;
                        }
                    }
                }

            }


        } else {


        }

        return mediaCodecInfo;
    }


}