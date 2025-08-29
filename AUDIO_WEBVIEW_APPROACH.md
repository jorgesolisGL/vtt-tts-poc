# Audio-Optimized WebView Implementation for Gaia Conversational AI

## Overview

This document outlines the approach for implementing an Android WebView that loads the Gaia conversational AI interface (`https://admin.gaia.com/vtt-tts`) with comprehensive audio optimizations to ensure high-quality full-duplex conversation experience.

## Audio Optimization Strategy

### 1. Audio Manager Configuration

The implementation configures the Android AudioManager for optimal voice communication:

```kotlin
audioManager.apply {
    mode = AudioManager.MODE_IN_COMMUNICATION  // Optimizes for voice calls
    isSpeakerphoneOn = false                  // Prevents feedback loops
    isBluetoothScoOn = true                   // Enables Bluetooth headset support
    startBluetoothSco()                       // Activates Bluetooth audio
}
```

**Key Benefits:**
- `MODE_IN_COMMUNICATION` enables built-in echo cancellation and noise reduction
- Automatic audio routing to appropriate output devices
- Optimized latency for real-time conversation

### 2. Audio Effects Implementation

Three critical audio processing effects are enabled when available:

#### Noise Suppression
```kotlin
if (NoiseSuppressor.isAvailable()) {
    noiseSuppressor = NoiseSuppressor.create(0)
    noiseSuppressor?.enabled = true
}
```
- Reduces background noise during user speech
- Improves agent's ability to understand user input
- Essential for noisy environments

#### Echo Cancellation
```kotlin
if (AcousticEchoCanceler.isAvailable()) {
    echoCanceler = AcousticEchoCanceler.create(0)
    echoCanceler?.enabled = true
}
```
- Prevents agent's speech from being picked up by microphone
- Critical for full-duplex conversation without feedback
- Enables natural interruption capabilities

#### Automatic Gain Control
```kotlin
if (AutomaticGainControl.isAvailable()) {
    gainControl = AutomaticGainControl.create(0)
    gainControl?.enabled = true
}
```
- Automatically adjusts microphone sensitivity
- Maintains consistent audio levels
- Compensates for varying user distance from microphone

### 3. Audio Focus Management

Implements sophisticated audio focus handling to prevent interruptions:

```kotlin
val audioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(audioAttributes)
    .setAcceptsDelayedFocusGain(true)
    .setOnAudioFocusChangeListener(audioFocusChangeListener)
    .build()
```

**Focus Change Handling:**
- `AUDIOFOCUS_GAIN`: Resume WebView audio processing
- `AUDIOFOCUS_LOSS`: Pause WebView to preserve conversation state
- `AUDIOFOCUS_LOSS_TRANSIENT`: Conditional pause based on agent speaking state

### 4. Interruption Prevention

The system tracks conversation state to prevent unwanted interruptions:

```kotlin
private var isAgentSpeaking = false

// In audio focus listener:
AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
    if (isAgentSpeaking) {
        // Don't pause if agent is speaking to prevent interruption
        Log.i(TAG, "Keeping audio active - agent speaking")
    }
}
```

## WebView Configuration for Audio

### Core WebView Settings

```kotlin
webView.settings.apply {
    javaScriptEnabled = true                           // Required for WebRTC
    domStorageEnabled = true                          // WebRTC data storage
    mediaPlaybackRequiresUserGesture = false         // Auto-play audio
    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW  // HTTPS flexibility
    cacheMode = WebSettings.LOAD_DEFAULT             // Optimize loading
}
```

### Permission Handling via ChromeClient

```kotlin
inner class GaiaWebChromeClient : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.let { permissionRequest ->
            // Grant all requested permissions for Gaia functionality
            permissionRequest.grant(permissionRequest.resources)
        }
    }
}
```

**Automatically grants:**
- `android.webkit.resource.AUDIO_CAPTURE` - Microphone access
- `android.webkit.resource.VIDEO_CAPTURE` - Camera access (if needed)
- Other WebRTC-related permissions

## Permission Management Strategy

### Runtime Permission Flow

1. **Check Existing Permissions**: Verify `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`
2. **Request Missing Permissions**: Use ActivityResultContract for modern permission handling
3. **Initialize Audio**: Only proceed with audio optimizations after permissions granted
4. **Graceful Degradation**: Load WebView even if permissions denied (reduced functionality)

```kotlin
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
```

## Lifecycle Management

### Resource Acquisition and Release

**On Audio Initialization:**
- Acquire audio focus
- Initialize audio effects
- Acquire wake lock (prevents CPU sleep during conversations)

**On Pause:**
- Release wake lock to preserve battery
- Pause WebView audio processing

**On Resume:**
- Re-acquire wake lock if needed
- Resume WebView audio

**On Destroy:**
- Release all audio effects (`noiseSuppressor`, `echoCanceler`, `gainControl`)
- Abandon audio focus
- Stop Bluetooth SCO
- Destroy WebView

### Wake Lock Management

```kotlin
// Acquire partial wake lock
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "GaiaApp:ConversationWakeLock"
)
wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
```

**Purpose:**
- Prevents CPU from sleeping during active conversations
- Maintains audio processing continuity
- Automatically releases after timeout for battery preservation

## Back Navigation Handling

Modern back gesture handling using OnBackPressedDispatcher:

```kotlin
onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()  // Navigate within WebView first
        } else {
            finish()          // Exit app only when no more history
        }
    }
})
```

## Error Handling and Logging

- Comprehensive logging for debugging audio initialization
- Console message forwarding from WebView to Android logs
- Graceful error handling with continued functionality
- Permission denial fallbacks with user notification

## Key Advantages of This Approach

1. **Full-Duplex Communication**: Simultaneous speaking/listening without echo
2. **Noise-Free Experience**: Background noise suppression
3. **Interruption-Safe**: Prevents system audio from disrupting conversations
4. **Device Compatibility**: Automatic fallback when audio effects unavailable
5. **Battery Optimized**: Intelligent wake lock management
6. **Modern Architecture**: Uses latest Android permission and lifecycle APIs

This implementation ensures optimal audio quality for conversational AI interactions while maintaining system stability and battery efficiency.