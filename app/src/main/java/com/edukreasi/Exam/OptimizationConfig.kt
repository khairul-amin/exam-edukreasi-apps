package com.edukreasi.Exam

/**
 * Configuration untuk optimization settings
 * Mudah di-toggle tanpa rebuild
 */
object OptimizationConfig {

    // === MEMORY OPTIMIZATION ===
    /** Enable automatic memory cleanup on lifecycle events */
    const val ENABLE_AUTO_CLEANUP = true
    
    /** Threshold MB untuk trigger aggressive cleanup */
    const val LOW_MEMORY_THRESHOLD_MB = 50
    
    /** Enable garbage collection force di low memory */
    const val FORCE_GC_ON_LOW_MEMORY = true

    // === WEBVIEW OPTIMIZATION ===
    /** Cache mode: LOAD_CACHE_ELSE_NETWORK atau LOAD_DEFAULT */
    const val WEBVIEW_CACHE_MODE = "LOAD_CACHE_ELSE_NETWORK"
    
    /** Enable cache untuk images */
    const val WEBVIEW_CACHE_IMAGES = true
    
    /** Block network images jika low memory */
    const val BLOCK_IMAGES_ON_LOW_MEMORY = true
    
    /** Media memerlukan user gesture (hemat battery) */
    const val MEDIA_REQUIRES_GESTURE = true
    
    /** Disable plugins (Flash dll) */
    const val DISABLE_PLUGINS = true

    // === NETWORK OPTIMIZATION ===
    /** Auto refresh interval dalam milidetik (3 menit) */
    const val REFRESH_INTERVAL_MS = 3 * 60 * 1000L
    
    /** Enable CSR caching untuk data fetch */
    const val ENABLE_DATA_CACHING = true
    
    /** Cache validity dalam milidetik (2 menit) */
    const val CACHE_VALIDITY_MS = 2 * 60 * 1000L

    // === FIREBASE & ML KIT ===
    /** Enable Firebase Analytics (disable untuk low-end) */
    const val ENABLE_FIREBASE_ANALYTICS = true
    
    /** Enable Firebase Auth (required) */
    const val ENABLE_FIREBASE_AUTH = true
    
    /** Enable ML Kit Barcode Scanning (heavy, disable jika tidak perlu) */
    const val ENABLE_ML_KIT = true

    // === LOGGING & DEBUG ===
    /** Enable verbose logging (disable di production) */
    val ENABLE_DEBUG_LOG = BuildConfig.DEBUG
    
    /** Enable memory monitoring logs */
    val ENABLE_MEMORY_LOGS = BuildConfig.DEBUG
    
    /** Enable performance metrics */
    val ENABLE_PERFORMANCE_LOGS = BuildConfig.DEBUG

    // === FEATURE FLAGS ===
    /** Enable barcode scanner feature */
    const val FEATURE_BARCODE_SCAN = true
    
    /** Enable offline mode (LAN) */
    const val FEATURE_OFFLINE_MODE = true
    
    /** Enable auto logout after inactivity */
    const val FEATURE_AUTO_LOGOUT = true
    
    /** Inactivity timeout dalam milidetik (30 menit) */
    const val AUTO_LOGOUT_TIMEOUT_MS = 30 * 60 * 1000L

    // === ANIMATION & UI ===
    /** Enable animations (disable di low-end untuk smooth) */
    const val ENABLE_ANIMATIONS = true
    
    /** Animation duration (ms) - kurangi untuk low-end */
    const val ANIMATION_DURATION_MS = 300L
    
    /** Enable hardware acceleration */
    const val ENABLE_HARDWARE_ACCELERATION = true

    // === PROFILING SETTINGS ===
    /** Enable strict mode untuk development */
    val ENABLE_STRICT_MODE = BuildConfig.DEBUG
    
    /** Enable ANR detection logs */
    val ENABLE_ANR_DETECTION = BuildConfig.DEBUG

    // === OPTIMIZATION PRESETS ===
    enum class OptimizationLevel {
        LOW_END,      // RAM < 1GB, disable banyak fitur
        MID_RANGE,    // RAM 1-2GB, balanced
        HIGH_END      // RAM > 2GB, semua fitur aktif
    }

    /**
     * Detect device optimization level based on RAM
     */
    fun detectOptimizationLevel(deviceRamMB: Long): OptimizationLevel {
        return when {
            deviceRamMB < 1024 -> OptimizationLevel.LOW_END
            deviceRamMB < 2048 -> OptimizationLevel.MID_RANGE
            else -> OptimizationLevel.HIGH_END
        }
    }

    /**
     * Apply optimization preset based on device capability
     */
    fun applyOptimizationPreset(level: OptimizationLevel) {
        when (level) {
            OptimizationLevel.LOW_END -> {
                // Aggressive optimization untuk low-end
                // - Disable animations
                // - Limit refresh interval
                // - Disable ML Kit
            }
            OptimizationLevel.MID_RANGE -> {
                // Balanced optimization
                // - Normal animations
                // - Reasonable refresh
                // - Enable ML Kit
            }
            OptimizationLevel.HIGH_END -> {
                // Full features enabled
                // - All animations
                // - Frequent refresh
                // - ML Kit enabled
            }
        }
    }
}
