// FAKE for host tests only: the minimum android.* surface AICoreProvider and
// HeadlessConversations touch.
package android.content
open class Intent { fun addFlags(f: Int): Intent = this; companion object { const val FLAG_ACTIVITY_NEW_TASK = 1; const val FLAG_ACTIVITY_REORDER_TO_FRONT = 2 } }
class PackageManager { fun getLaunchIntentForPackage(p: String): Intent? = null }
interface SharedPreferences {
  fun getString(k: String, d: String?): String?
  fun edit(): Editor
  interface Editor { fun putString(k: String, v: String?): Editor; fun apply() }
}
abstract class Context { abstract val packageName: String; abstract val packageManager: PackageManager
  abstract fun startActivity(i: Intent); abstract fun getSystemService(n: String): Any?
  open val applicationContext: Context get() = this
  open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("not stubbed")
  companion object { const val ACTIVITY_SERVICE = "a"; const val MODE_PRIVATE = 0 } }
