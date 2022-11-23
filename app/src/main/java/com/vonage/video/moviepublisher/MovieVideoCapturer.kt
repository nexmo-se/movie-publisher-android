package com.vonage.video.moviepublisher

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.opengl.*
import android.util.Log
import android.view.Surface
import com.opentok.android.BaseVideoCapturer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


class MovieVideoCapturer : BaseVideoCapturer(){
    private var capturing: Boolean = false
    private var fileId: Int = -1
    var ctx : Context? = null
    var decoder: MediaCodec? = null
    var extractor: MediaExtractor? = null
    var outputSurface: CodecOutputSurface? = null
    var movieWidth: Int = -1
    var movieHeight: Int = -1
    var trackIndex: Int = -1
    var frameRate: Int = 30
    var prevTimestamp: Long = 0
    var TAG: String = "MovieCapturer"
    override fun init() {

    }

    fun getMovieDimensions(){
        val retriever = MediaMetadataRetriever()
        var afd: AssetFileDescriptor = ctx!!.getResources().openRawResourceFd(fileId);
        retriever.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength())
        movieWidth =
            Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        movieHeight =
            Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        retriever.release()
    }
    fun setMovieFile(file: Int, mainContext: Context){
        fileId = file
        ctx = mainContext

        getMovieDimensions();

        extractor = MediaExtractor()
        var afd: AssetFileDescriptor = ctx!!.getResources().openRawResourceFd(fileId);
        extractor?.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
        trackIndex = selectTrack(extractor!!)
        if (trackIndex < 0) {
            throw  RuntimeException("No video track found");
        }
        extractor!!.selectTrack(trackIndex);
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        // Select the first video track we find, ignore the rest.
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("video/")) {
                    Log.d(TAG, "Extractor selected track $i ($mime): $format")
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                        Log.d(TAG, "Frame rate: $frameRate")
                    }
                return i
            }
        }
        return -1
    }
    override fun startCapture(): Int {
        capturing = true
        Thread(Runnable{
            val format = extractor!!.getTrackFormat(trackIndex)
            outputSurface = CodecOutputSurface(movieWidth, movieHeight)
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            decoder!!.configure(format, outputSurface!!.surface, null, 0)
            decoder?.start()
            doExtract(extractor!!, trackIndex, decoder!!, outputSurface!!);
        }).start()
        return 0
    }

    override fun stopCapture(): Int {
        capturing = false
        return 0
    }

    override fun destroy() {

    }

    override fun isCaptureStarted(): Boolean {
        return capturing
    }

    override fun getCaptureSettings(): CaptureSettings {

        val settings:CaptureSettings = CaptureSettings()
        settings.expectedDelay = 0
        settings.format = ARGB
        settings.mirrorInLocalRender = false
        settings.width = movieWidth
        settings.height = movieHeight
        return settings
    }

    override fun onPause() {

    }

    override fun onResume() {

    }

    fun doExtract(extractor: MediaExtractor, trackIndex: Int, decoder: MediaCodec, outputSurface: CodecOutputSurface) {
        val TIMEOUT_USEC = 10000
        val decoderInputBuffers = decoder.inputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = 0
        var decodeCount = 0
        var frameSaveTime: Long = 0
        var outputDone = false
        var inputDone = false
        while (!outputDone) {

            // Feed more data to the decoder.
            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                if (inputBufIndex >= 0) {
                    val inputBuf = decoderInputBuffers[inputBufIndex]
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    val chunkSize = extractor.readSampleData(inputBuf, 0)
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        if (extractor.sampleTrackIndex != trackIndex) {
                            Log.w(
                                TAG, "WEIRD: got sample from track " +
                                        extractor.sampleTrackIndex + ", expected " + trackIndex
                            )
                        }
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, chunkSize,
                            presentationTimeUs, 0 /*flags*/
                        )
                        inputChunk++
                        extractor.advance()
                    }
                } else {
                    Log.d(TAG, "input buffer not available")
                }
            }
            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    Log.d(TAG, "no output from decoder available")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                   Log.d(TAG, "decoder output buffers changed")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    Log.d(TAG, "decoder output format changed: $newFormat")
                } else if (decoderStatus < 0) {
                    Log.e(TAG,"unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                } else { // decoderStatus >= 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "output EOS")
                        outputDone = true
                    }
                    val doRender = info.size != 0

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                    if (doRender) {
                       // Log.d(TAG, "awaiting decode of frame $decodeCount")
                        outputSurface.awaitNewImage()
                        outputSurface.drawImage(true)
                        provideBufferFrame(outputSurface.getPixelBuffer(), ARGB,movieWidth,movieHeight,0,false)
                        val curTimeMs = System.currentTimeMillis()
                        val sleepMs = (1000/frameRate) - (curTimeMs-prevTimestamp)
                        //Log.d(TAG, "Sleep time: $sleepMs")
                        if(sleepMs>0) {
                            Thread.sleep(sleepMs)
                        }
                    }
                    prevTimestamp = System.currentTimeMillis()
                }
            }
        }
    }
}

class CodecOutputSurface(width: Int, height: Int) :
    OnFrameAvailableListener {
    private var mTextureRender: STextureRender? = null
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mSurface: Surface? = null
    private var mEGLDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var mEGLContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var mEGLSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    var mWidth: Int
    var mHeight: Int
    private val mFrameSyncObject = Object() // guards mFrameAvailable
    private var mFrameAvailable = false
    private var mPixelBuf // used by saveFrame()
            : ByteBuffer? = null

    /**
     * Creates interconnected instances of TextureRender, SurfaceTexture, and Surface.
     */
    private fun setup() {
        mTextureRender = STextureRender()
        mTextureRender?.surfaceCreated()
        mSurfaceTexture = SurfaceTexture(mTextureRender!!.textureId)

        // This doesn't work if this object is created on the thread that CTS started for
        // these test cases.
        //
        // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
        // create a Handler that uses it.  The "frame available" message is delivered
        // there, but since we're not a Looper-based thread we'll never see it.  For
        // this to do anything useful, CodecOutputSurface must be created on a thread without
        // a Looper, so that SurfaceTexture uses the main application Looper instead.
        //
        // Java language note: passing "this" out of a constructor is generally unwise,
        // but we should be able to get away with it here.
        mSurfaceTexture!!.setOnFrameAvailableListener(this)
        mSurface = Surface(mSurfaceTexture)
        mPixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4)
        mPixelBuf?.order(ByteOrder.LITTLE_ENDIAN)
    }

    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports pbuffer.
     */
    private fun eglSetup() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (mEGLDisplay === EGL14.EGL_NO_DISPLAY) {
            throw java.lang.RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null
            throw java.lang.RuntimeException("unable to initialize EGL14")
        }

        // Configure EGL for pbuffer and OpenGL ES 2.0, 24-bit RGB.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs: Array<EGLConfig?> = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                mEGLDisplay, attribList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            throw java.lang.RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        // Configure context for OpenGL ES 2.0.
        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        mEGLContext = EGL14.eglCreateContext(
            mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            attrib_list, 0
        )
        checkEglError("eglCreateContext")
        if (mEGLContext == null) {
            throw java.lang.RuntimeException("null context")
        }

        // Create a pbuffer surface.
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, mWidth,
            EGL14.EGL_HEIGHT, mHeight,
            EGL14.EGL_NONE
        )
        mEGLSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs, 0)
        checkEglError("eglCreatePbufferSurface")
        if (mEGLSurface == null) {
            throw java.lang.RuntimeException("surface was null")
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    fun release() {
        if (mEGLDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface)
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEGLDisplay)
        }
        mEGLDisplay = EGL14.EGL_NO_DISPLAY
        mEGLContext = EGL14.EGL_NO_CONTEXT
        mEGLSurface = EGL14.EGL_NO_SURFACE
        mSurface?.release()

        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //mSurfaceTexture.release();
        mTextureRender = null
        mSurface = null
        mSurfaceTexture = null
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw java.lang.RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Returns the Surface.
     */
    val surface: Surface?
        get() = mSurface

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the CodecOutputSurface object.  (More specifically, it must be called on the thread
     * with the EGLContext that contains the GL texture object used by SurfaceTexture.)
     */
    fun awaitNewImage() {
        val TIMEOUT_MS = 2500
        synchronized(mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    mFrameSyncObject.wait(TIMEOUT_MS.toLong())
                    if (!mFrameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        throw java.lang.RuntimeException("frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    // shouldn't happen
                    throw java.lang.RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }

        // Latch the data.
        mTextureRender?.checkGlError("before updateTexImage")
        mSurfaceTexture!!.updateTexImage()
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     *
     * @param invert if set, render the image with Y inverted (0,0 in top left)
     */
    fun drawImage(invert: Boolean) {
        mTextureRender?.drawFrame(mSurfaceTexture!!, invert)
    }

    // SurfaceTexture callback
    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(mFrameSyncObject) {
            if (mFrameAvailable) {
                throw java.lang.RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            mFrameAvailable = true
            mFrameSyncObject.notifyAll()
        }
    }

    /**
     * Saves the current frame to disk as a PNG image.
     */
    fun getPixelBuffer (): ByteBuffer? {
        // glReadPixels gives us a ByteBuffer filled with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  To use the Bitmap
        // constructor that takes an int[] array with pixel data, we need an int[] filled
        // with little-endian ARGB data.
        //
        // If we implement this as a series of buf.get() calls, we can spend 2.5 seconds just
        // copying data around for a 720p frame.  It's better to do a bulk get() and then
        // rearrange the data in memory.  (For comparison, the PNG compress takes about 500ms
        // for a trivial frame.)
        //
        // So... we set the ByteBuffer to little-endian, which should turn the bulk IntBuffer
        // get() into a straight memcpy on most Android devices.  Our ints will hold ABGR data.
        // Swapping B and R gives us ARGB.  We need about 30ms for the bulk get(), and another
        // 270ms for the color swap.
        //
        // We can avoid the costly B/R swap here if we do it in the fragment shader (see
        // http://stackoverflow.com/questions/21634450/ ).
        //
        // Having said all that... it turns out that the Bitmap#copyPixelsFromBuffer()
        // method wants RGBA pixels, not ARGB, so if we create an empty bitmap and then
        // copy pixel data in we can avoid the swap issue entirely, and just copy straight
        // into the Bitmap from the ByteBuffer.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside-down relative to what appears on screen if the
        // typical GL conventions are used.  (For ExtractMpegFrameTest, we avoid the issue
        // by inverting the frame when we render it.)
        //
        // Allocating large buffers is expensive, so we really want mPixelBuf to be
        // allocated ahead of time if possible.  We still get some allocations from the
        // Bitmap / PNG creation.
        mPixelBuf?.rewind()
        GLES20.glReadPixels(
            0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            mPixelBuf
        )
        return mPixelBuf
    }

    /**
     * Checks for EGL errors.
     */
    private fun checkEglError(msg: String) {
        var error: Int
        if (EGL14.eglGetError().also { error = it } != EGL14.EGL_SUCCESS) {
            throw java.lang.RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }

    /**
     * Creates a CodecOutputSurface backed by a pbuffer with the specified dimensions.  The
     * new EGL context and surface will be made current.  Creates a Surface that can be passed
     * to MediaCodec.configure().
     */
    init {
        require(!(width <= 0 || height <= 0))
        mWidth = width
        mHeight = height
        eglSetup()
        makeCurrent()
        setup()
    }
}

private class STextureRender {
    private val mTriangleVerticesData = floatArrayOf( // X, Y, Z, U, V
        -1.0f, -1.0f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
    )
    private val mTriangleVertices: FloatBuffer
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram = 0
    var textureId = -12345
        private set
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    var TAG: String = "STextureRender"

    /**
     * Draws the external texture in SurfaceTexture onto the current EGL surface.
     */
    fun drawFrame(st: SurfaceTexture, invert: Boolean) {
        checkGlError("onDrawFrame start")
        st.getTransformMatrix(mSTMatrix)
        if (invert) {
            mSTMatrix[5] = -mSTMatrix[5]
            mSTMatrix[13] = 1.0f - mSTMatrix[13]
        }

        // (optional) clear to green so we can see if we're failing to set pixels
        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            maPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices
        )
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            maTextureHandle, 2, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices
        )
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        Matrix.setIdentityM(mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Initializes GL state.  Call this after the EGL surface has been created and made current.
     */
    fun surfaceCreated() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (mProgram == 0) {
            throw java.lang.RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkLocation(maPositionHandle, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkLocation(maTextureHandle, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkLocation(muMVPMatrixHandle, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkLocation(muSTMatrixHandle, "uSTMatrix")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlError("glBindTexture mTextureID")
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlError("glTexParameter")
    }

    /**
     * Replaces the fragment shader.  Pass in null to reset to default.
     */
    fun changeFragmentShader(fragmentShader: String?) {
        var fragmentShader = fragmentShader
        if (fragmentShader == null) {
            fragmentShader = FRAGMENT_SHADER
        }
        GLES20.glDeleteProgram(mProgram)
        mProgram = createProgram(VERTEX_SHADER, fragmentShader)
        if (mProgram == 0) {
            throw java.lang.RuntimeException("failed creating program")
        }
    }

    private fun loadShader(shaderType: Int, source: String?): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $shaderType:")
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String?): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "Could not create program")
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    fun checkGlError(op: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError $error")
            throw java.lang.RuntimeException("$op: glError $error")
        }
    }

    companion object {
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
        private const val VERTEX_SHADER = "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uMVPMatrix * aPosition;\n" +
                "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n"
        private const val FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +  // highp here doesn't seem to matter
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n"

        fun checkLocation(location: Int, label: String) {
            if (location < 0) {
                throw java.lang.RuntimeException("Unable to locate '$label' in program")
            }
        }
    }

    init {
        mTriangleVertices = ByteBuffer.allocateDirect(
            mTriangleVerticesData.size * FLOAT_SIZE_BYTES
        )
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        mTriangleVertices.put(mTriangleVerticesData).position(0)
        Matrix.setIdentityM(mSTMatrix, 0)
    }
}
