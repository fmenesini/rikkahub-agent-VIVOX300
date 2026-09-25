// FAKE for host tests only: the minimum android.* surface AICoreProvider touches.
package android.content
open class Intent { fun addFlags(f: Int): Intent = this; companion object { const val FLAG_ACTIVITY_NEW_TASK = 1; const val FLAG_ACTIVITY_REORDER_TO_FRONT = 2 } }
class PackageManager { fun getLaunchIntentForPackage(p: String): Intent? = null }
abstract class Context { abstract val packageName: String; abstract val packageManager: PackageManager
  abstract fun startActivity(i: Intent); abstract fun getSystemService(n: String): Any?
  companion object { const val ACTIVITY_SERVICE = "a" } }
