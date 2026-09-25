// FAKE for host tests only: the minimum android.* surface AICoreProvider touches.
package android.app
class ActivityManager { val runningAppProcesses: List<RunningAppProcessInfo>? = null
  class RunningAppProcessInfo { var pid = 0; var importance = 0; companion object { const val IMPORTANCE_FOREGROUND = 100 } } }
