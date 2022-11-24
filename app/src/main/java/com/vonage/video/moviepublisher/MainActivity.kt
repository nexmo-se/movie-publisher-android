package com.vonage.video.moviepublisher
import android.Manifest
import android.media.MediaCodec.BufferInfo
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.opentok.android.*
import java.io.PipedInputStream
import java.io.PipedOutputStream


class MainActivity : AppCompatActivity() {
    private var token: String = "T1==cGFydG5lcl9pZD00NjE4MzQ1MiZzaWc9MDc2ZDVkNDBhZGU1Mzg5OWJmNWVjMTIwZTRjZDQxYzllMmE3N2JmOTpzZXNzaW9uX2lkPTJfTVg0ME5qRTRNelExTW41LU1UWTJPRGs1TlRBeE5qYzBNWDVFYjNrdmFHaENiMFEzUjIxcmNVMW9UM2RsU2tWMU5uQi1mZyZjcmVhdGVfdGltZT0xNjY4OTk1MDE3Jm5vbmNlPTAuNzg5Mzg5MTU0OTQxMDkxNCZyb2xlPW1vZGVyYXRvciZleHBpcmVfdGltZT0xNjY5NTk5ODE3JmluaXRpYWxfbGF5b3V0X2NsYXNzX2xpc3Q9"
    private var sessionId: String = "2_MX40NjE4MzQ1Mn5-MTY2ODk5NTAxNjc0MX5Eb3kvaGhCb0Q3R21rcU1oT3dlSkV1NnB-fg"
    private var apiKey: String = "46183452"
    private var pubLayout: FrameLayout? = null
    private var pub: Publisher? = null
    private var sub: Subscriber? = null
    private var session: Session? = null
    private var TAG: String = "MoviePublisherMainActivity"

    private var moviePlayer: MoviePlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pubLayout = findViewById(R.id.publisherLayout)

        moviePlayer = MoviePlayer(R.raw.vonage_roadshow,this)

        var aDevice = MixedAudioDevice(this,moviePlayer)
        AudioDeviceManager.setAudioDevice(aDevice)
        initializeSession(apiKey,sessionId,token)
    }

    private fun initializeSession(apiKey: String, sessionId: String, token: String) {
        Log.i(TAG, "apiKey: $apiKey")
        Log.i(TAG, "sessionId: $sessionId")
        Log.i(TAG, "token: $token")
       // com.opentok.android.OpenTokConfig.setWebRTCLogs(true)
        //com.opentok.android.OpenTokConfig.setOTKitLogs(true)
        //com.opentok.android.OpenTokConfig.setJNILogs(true)
        session = Session.Builder(this, apiKey, sessionId).build().also {
            it.setSessionListener(sessionListener)
            it.connect(token)
        }
    }

    private val sessionListener: Session.SessionListener = object : Session.SessionListener {
        override fun onConnected(session: Session) {
            Log.d(TAG, "onConnected: Connected to session: ${session.sessionId}")
            var movieVideoCapturer: MovieVideoCapturer = MovieVideoCapturer(moviePlayer!!)

            pub = Publisher.Builder(this@MainActivity).capturer(movieVideoCapturer).build()
            //pub?.publishAudio = false
            pub?.setPublisherListener(publisherListerner)
            pub?.setStyle(BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL);
            pubLayout?.addView(pub?.view)

            session.publish(pub)
            moviePlayer?.hasStarted = true
        }

        override fun onDisconnected(session: Session) {
            Log.d(TAG, "onDisconnected: Disconnected from session: ${session.sessionId}")
        }

        override fun onStreamReceived(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamReceived: New Stream Received ${stream.streamId} in session: ${session.sessionId}")
        }

        override fun onStreamDropped(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamDropped: Stream Dropped: ${stream.streamId} in session: ${session.sessionId}")
        }

        override fun onError(session: Session, opentokError: OpentokError) {
            finishWithMessage("Session error: ${opentokError.message}")
        }
    }

    var publisherListerner: PublisherKit.PublisherListener = object: PublisherKit.PublisherListener{
        override fun onStreamCreated(p0: PublisherKit?, p1: Stream?) {
            Log.i("PUBLISHERLOG","Stream created")
        }

        override fun onStreamDestroyed(p0: PublisherKit?, p1: Stream?) {
            Log.i("PUBLISHERLOG","Stream destroyed")
        }

        override fun onError(p0: PublisherKit?, p1: OpentokError?) {
            Log.e("PUBLISHERLOG", "Error message: " + p1?.message + " Code:" + p1?.errorCode)
        }

    }
    var subscriberListener: SubscriberKit.SubscriberListener = object : SubscriberKit.SubscriberListener {
        override fun onConnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onConnected: Subscriber connected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onDisconnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onDisconnected: Subscriber disconnected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onError(subscriberKit: SubscriberKit, opentokError: OpentokError) {
            finishWithMessage("SubscriberKit onError: ${opentokError.message}")
        }
    }

    private fun finishWithMessage(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}