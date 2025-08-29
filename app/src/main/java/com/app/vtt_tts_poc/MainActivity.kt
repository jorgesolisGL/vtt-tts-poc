package com.app.vtt_tts_poc

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GaiaWebView"
        private const val GAIA_URL = "https://admin.gaia.com/vtt-tts"
    }

    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isAgentSpeaking = false

    // Audio effects for noise cancellation
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioPermissionGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val modifyAudioPermissionGranted = permissions[Manifest.permission.MODIFY_AUDIO_SETTINGS] ?: false
        
        if (audioPermissionGranted && modifyAudioPermissionGranted) {
            initializeAudioOptimizations()
            loadGaiaUrl()
        } else {
            showPermissionDeniedMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        webView = findViewById(R.id.webview)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        setupBackPressHandler()
        initializeWebView()
        requestAudioPermissions()
    }

    private fun initializeWebView() {
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // WebRTC and audio optimization settings
                cacheMode = WebSettings.LOAD_DEFAULT
//                userAgentString = buildOptimalUserAgent()
            }
            webChromeClient = GaiaWebChromeClient()
            webViewClient = GaiaWebViewClient()
        }
    }

    private fun buildOptimalUserAgent(): String {
        val defaultUA = WebSettings.getDefaultUserAgent(this)
        return "$defaultUA GaiaVoiceOptimized/1.0"
    }

    private fun requestAudioPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            initializeAudioOptimizations()
            loadGaiaUrl()
        }
    }

    private fun initializeAudioOptimizations() {
        try {
            // Configure audio manager for conversation mode
            audioManager.apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                isSpeakerphoneOn = false
                isBluetoothScoOn = true
                @Suppress("DEPRECATION")
                startBluetoothSco()
            }

            // Request audio focus for conversation
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)

            // Initialize noise suppression and echo cancellation
            initializeAudioEffects()

            // Acquire wake lock to prevent interruptions
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GaiaApp:ConversationWakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max

            Log.i(TAG, "Audio optimizations initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio optimizations", e)
        }
    }

    private fun initializeAudioEffects() {
        try {
            // Enable noise suppression if available
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(0)
                noiseSuppressor?.enabled = true
                Log.i(TAG, "Noise suppressor enabled")
            }

            // Enable echo cancellation if available
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(0)
                echoCanceler?.enabled = true
                Log.i(TAG, "Echo canceler enabled")
            }

            // Enable automatic gain control if available
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(0)
                gainControl?.enabled = true
                Log.i(TAG, "Automatic gain control enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio effects", e)
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus gained")
                webView.onResume()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Audio focus lost")
                webView.onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost temporarily")
                if (isAgentSpeaking) {
                    // Don't pause if agent is speaking to prevent interruption
                    Log.i(TAG, "Keeping audio active - agent speaking")
                }
            }
        }
    }

    private fun loadGaiaUrl() {
        Log.i(TAG, "Loading Gaia URL: $GAIA_URL")
        webView.loadUrl(GAIA_URL)
    }

    private fun showPermissionDeniedMessage() {
        Toast.makeText(
            this,
            "Audio permissions are required for optimal conversation experience",
            Toast.LENGTH_LONG
        ).show()
        
        // Load URL anyway but with reduced functionality
        loadGaiaUrl()
    }

    inner class GaiaWebChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.let { permissionRequest ->
                Log.i(TAG, "WebView permission request: ${permissionRequest.resources.joinToString()}")
                // Grant all requested permissions for Gaia functionality
                permissionRequest.grant(permissionRequest.resources)
            }
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
            message?.let {
                Log.d(TAG, "Console: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
            }
            return super.onConsoleMessage(message)
        }
    }

    inner class GaiaWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            Log.i(TAG, "Page loaded: $url")
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            Log.e(TAG, "WebView error: $errorCode - $description for $failingUrl")
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        wakeLock?.let { if (!it.isHeld) it.acquire(10 * 60 * 1000L) }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up audio effects
        noiseSuppressor?.release()
        echoCanceler?.release()
        gainControl?.release()
        
        // Release audio focus
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }

        // Clean up audio manager
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        
        webView.destroy()
    }
}