---
name: ignore-screen-off-while-exam
description: Prevents false cheating detection by ignoring screen‑off events while an exam WebView is active.
source: auto-skill
extracted_at: '2026-06-17T14:11:49.649Z'
---

## Goal
When the device screen is turned off (power button pressed) during an active exam, the app should **not** treat this as a cheating violation that shows the "PELANGGARAN TERDETEKSI!" page.

## Approach
1. **Create a BroadcastReceiver** that listens for `Intent.ACTION_SCREEN_OFF`.
2. Inside `onReceive`, check the activity flag `isExamActive` (already present in `WebViewActivity`).
3. If the exam is active, simply log the event and do **nothing else** – this prevents any downstream logic that would load the violation HTML.
4. Register this receiver in `onResume()` and unregister it in `onPause()` to keep the lifecycle clean.
5. Keep the receiver lightweight; it only logs with `Log.d("ExamMonitor", "Screen off event ignored (exam active)")`.

## Implementation Details (Kotlin)
```kotlin
// Inside WebViewActivity.kt
private val screenOffReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Ignore screen‑off while exam is running
        if (isExamActive) {
            Log.d("ExamMonitor", "Screen off event ignored (exam active)")
        }
    }
}

override fun onResume() {
    super.onResume()
    registerReceiver(batteryStatusReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    // Register screen‑off receiver – we ignore the event while the exam is active
    registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    // … existing code …
}

override fun onPause() {
    super.onPause()
    try { unregisterReceiver(batteryStatusReceiver) } catch (e: Exception) { }
    // Unregister screen‑off receiver
    try { unregisterReceiver(screenOffReceiver) } catch (e: Exception) { }
    // … existing code …
}
```

## Why this works
- The system broadcasts `ACTION_SCREEN_OFF` when the power button is pressed.
- By handling the broadcast **inside the activity** and short‑circuiting when `isExamActive` is true, we stop the code path that generates the violation HTML (which is triggered elsewhere based on detected background apps or overlay bubbles).
- Logging provides visibility for debugging without affecting user experience.

## How to apply
Whenever you add a new exam‑display activity, copy the above receiver and lifecycle registration pattern. Ensure the activity maintains a boolean flag (`isExamActive`/`isExamRunning`) that is set to `true` while the exam is in progress and `false` otherwise.
