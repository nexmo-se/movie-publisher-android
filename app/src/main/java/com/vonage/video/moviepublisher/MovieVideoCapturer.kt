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


class MovieVideoCapturer(moviePlayer: MoviePlayer) : BaseVideoCapturer(){
    private var capturing: Boolean = false
    var TAG: String = "MovieCapturer"
    var mPlayer: MoviePlayer? = null
    var prevTimestamp: Long = 0;
    init {
        mPlayer = moviePlayer
    }
    override fun init() {

    }

    override fun startCapture(): Int {
        capturing = true
        Thread(Runnable{
            captureProcess()
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
        settings.width = mPlayer!!.movieWidth
        settings.height = mPlayer!!.movieHeight
        return settings
    }

    override fun onPause() {

    }

    override fun onResume() {

    }

    fun captureProcess() {
        mPlayer?.initVideoSurface()
        while(true){
            val videoFrame = mPlayer?.readVideoData()

            val curTimeMs = System.currentTimeMillis()
            val sleepMs = (1000/mPlayer!!.frameRate) - (curTimeMs-prevTimestamp)
            //Log.d(TAG, "Sleep time: $sleepMs")
            if(sleepMs>0) Thread.sleep(sleepMs)
            mPlayer?.shouldPushVideo()
            provideBufferFrame(videoFrame, ARGB,mPlayer!!.movieWidth,mPlayer!!.movieHeight,0,false)
            prevTimestamp = System.currentTimeMillis()

        }
    }
}
