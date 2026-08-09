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

package com.tom.rv2ide.utils;

/**
 * Utilities related to VM.
 *
 * @author Akash Yadav
 */
public class VMUtils {

  private static Boolean isJvm = null;

  /**
   * @return <code>true</code> if the current platform is JVM, <code>false</code> otherwise.
   */
  public static boolean isJvm() {

    if (isJvm != null) {
      return isJvm;
    }

    // Instrumentation APKs include JUnit too, so its presence alone cannot identify a host JVM.
    // Android's ART exposes the historical Dalvik VM name; recognize it before the JUnit/Robolectric
    // fallback below so Android-only Handler scheduling remains enabled on devices.
    final String vmName = System.getProperty("java.vm.name", "");
    if (vmName.contains("Dalvik")) {
      return isJvm = false;
    }

    try {
      // Host JVM tests (including Robolectric) carry JUnit and must retain their JVM behavior.
      Class.forName("org.junit.runners.JUnit4");
      return isJvm = true;
    } catch (ClassNotFoundException e) {
      // ignored
    }

    try {
      Class.forName("android.content.Context");
      return isJvm = false;
    } catch (ClassNotFoundException e) {
      return isJvm = true;
    }
  }
}
