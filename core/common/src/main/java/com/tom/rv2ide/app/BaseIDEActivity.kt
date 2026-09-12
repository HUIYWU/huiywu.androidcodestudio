/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.tom.rv2ide.common.R
import com.tom.rv2ide.tasks.cancelIfActive
import com.tom.rv2ide.ui.themes.IThemeManager
import com.tom.rv2ide.utils.resolveAttr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory

abstract class BaseIDEActivity : AppCompatActivity() {

  private val log = LoggerFactory.getLogger(BaseIDEActivity::class.java)

  open val subscribeToEvents: Boolean = false

  open var enableSystemBarTheming: Boolean = true

  open val navigationBarColor: Int
    get() = resolveAttr(R.attr.colorSurface)

  open val statusBarColor: Int
    get() = resolveAttr(R.attr.colorSurface)

  /** [CoroutineScope] for executing tasks with the [Default][Dispatchers.Default] dispatcher. */
  val activityScope = CoroutineScope(Dispatchers.Default)

  /**
   * Signature of the theme applied in [onCreate]. Compared against the currently selected theme in
   * [onStart] to detect that this activity is still showing an appearance that is no longer
   * selected. See [recreateIfThemeIsStale].
   */
  private var appliedThemeSignature: String? = null

  /**
   * Set to `true` while [onStart] is recreating this activity because the theme it was created with
   * is no longer selected.
   *
   * An early `return` in [onStart] cannot stop a subclass from continuing its own `onStart` work, so
   * subclasses which do meaningful work there should consult this flag and skip it — the recreated
   * instance will perform that work again anyway.
   */
  protected var isRecreatingForThemeChange = false
    private set

  override fun onCreate(savedInstanceState: Bundle?) {
    if (enableSystemBarTheming) {
      window?.apply {
        navigationBarColor = this@BaseIDEActivity.navigationBarColor
        statusBarColor = this@BaseIDEActivity.statusBarColor
      }
    }
    val themeManager = IThemeManager.getInstance()
    // setTheme() must run before super.onCreate(), because the theme has to be in place by the time
    // the content view is inflated below.
    themeManager.applyTheme(this)
    super.onCreate(savedInstanceState)
    // Recorded only after super.onCreate(), so that AppCompat has already applied the night mode to
    // this activity's resources. The signature is then resolved under exactly the same conditions as
    // the comparison in [recreateIfThemeIsStale], which is what keeps a freshly themed activity from
    // reporting itself as stale and recreating in a loop.
    appliedThemeSignature = themeManager.getAppliedThemeSignature(this)
    preSetContentLayout()
    setContentView(bindLayout())
  }

  override fun onDestroy() {
    super.onDestroy()
    activityScope.cancelIfActive("Activity is being destroyed")
  }

  override fun onStart() {
    super.onStart()

    if (recreateIfThemeIsStale()) {
      // A recreate() has been scheduled. The early return below cannot stop a subclass from
      // continuing its own onStart work, so isRecreatingForThemeChange is set as well and checked by
      // the subclasses which would otherwise perform that work twice.
      return
    }

    if (!EventBus.getDefault().isRegistered(this) && subscribeToEvents) {
      EventBus.getDefault().register(this)
    }
  }

  /**
   * Recreates this activity when the theme it was created with is no longer the selected one.
   *
   * A theme change is applied to the activity that hosts the preference (`PreferencesActivity`), and
   * a pure palette change is not a configuration change, so activities that are already on the back
   * stack keep their old appearance until they are recreated. Comparing the signature recorded in
   * [onCreate] against the currently selected theme makes them converge on their own: the mismatch is
   * detected in [onStart], which runs before the window becomes visible, so the stale content is
   * never shown.
   *
   * @return `true` if a recreate was requested and the caller should stop its `onStart` work.
   */
  private fun recreateIfThemeIsStale(): Boolean {
    val applied = appliedThemeSignature ?: return false
    val current = IThemeManager.getInstance().getAppliedThemeSignature(this)
    if (applied == current) {
      return false
    }

    log.debug("Theme changed from {} to {}. Recreating {}.", applied, current, javaClass.simpleName)
    // Updated before recreate() so that the recreated instance is not immediately considered stale
    // again if onStart is somehow reached twice before the teardown completes.
    appliedThemeSignature = current
    isRecreatingForThemeChange = true
    recreate()
    return true
  }

  override fun onStop() {
    super.onStop()
    if (EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().unregister(this)
    }
  }

  fun loadFragment(fragment: Fragment, id: Int) {
    val transaction = supportFragmentManager.beginTransaction()
    transaction.replace(id, fragment)
    transaction.commit()
  }

  protected open fun preSetContentLayout() {}

  protected abstract fun bindLayout(): View
}
