/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android.game

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

object GameSources {

  fun mainActivityKotlin(packageId: String): String =
      """
      package $packageId
      
      import android.view.View
      import com.google.androidgamesdk.GameActivity
      
      class MainActivity : GameActivity() {
          companion object {
              init {
                  System.loadLibrary("myapplication")
              }
          }
      
          override fun onWindowFocusChanged(hasFocus: Boolean) {
              super.onWindowFocusChanged(hasFocus)
              if (hasFocus) {
                  hideSystemUi()
              }
          }
      
          private fun hideSystemUi() {
              val decorView = window.decorView
              decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                      or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                      or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                      or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                      or View.SYSTEM_UI_FLAG_FULLSCREEN)
          }
      }
  """
          .trimIndent()

  fun mainActivityJava(packageId: String): String =
      """
      package $packageId;
      
      import android.view.View;
      
      import com.google.androidgamesdk.GameActivity;
      
      public class MainActivity extends GameActivity {
          static {
              System.loadLibrary("myapplication");
          }
      
          @Override
          public void onWindowFocusChanged(boolean hasFocus) {
              super.onWindowFocusChanged(hasFocus);
      
              if (hasFocus) {
                  hideSystemUi();
              }
          }
      
          private void hideSystemUi() {
              View decorView = getWindow().getDecorView();
              decorView.setSystemUiVisibility(
                      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                              | View.SYSTEM_UI_FLAG_FULLSCREEN
              );
          }
      }
  """
          .trimIndent()
  // Android.mk
  //
  // Unlike the plain NativeCpp template, GameActivity's native sources depend on the
  // game-activity Prefab package (headers + static library). When Gradle invokes ndk-build,
  // it injects the resolved Prefab import roots through NDK_GRADLE_INJECTED_IMPORT_PATH.
  // This template consumes that path, makes the prefab module discoverable via
  // import-module, and links against game-activity_static so the generated project can
  // build without manual Android.mk fixes.
  fun jniBuildSystemNdk(): String =
      """
           # For more information about using Android.mk:
          # https://developer.android.com/ndk/guides/android_mk
          
          LOCAL_PATH := $(call my-dir)

          # Gradle/AGP exposes Prefab ndk-build packages through this injected import path.
          # Make game-activity discoverable by import-module below.
          ifdef NDK_GRADLE_INJECTED_IMPORT_PATH
          NDK_MODULE_PATH += $(subst $(space),:,$(NDK_GRADLE_INJECTED_IMPORT_PATH))
          endif
          
          include $(CLEAR_VARS)
          
          # Module name — must match the name used in Java/Kotlin when loading:
          # System.loadLibrary("myapplication")
          LOCAL_MODULE := myapplication

          
          # Source files
          LOCAL_SRC_FILES := \
              main.cpp \
              AndroidOut.cpp \
              Renderer.cpp \
              Shader.cpp \
              TextureAsset.cpp \
              Utility.cpp

          LOCAL_CPPFLAGS += -std=c++17
          
          # Link against the GameActivity static library exposed from the prefab package.
          LOCAL_STATIC_LIBRARIES := game-activity_static
          
          # Add libraries to link against
          LOCAL_LDLIBS := -llog -landroid -lEGL -lGLESv3 -ljnigraphics
          
          # Force linker to keep the JNI entry point for GameActivity
          LOCAL_LDFLAGS += -u Java_com_google_androidgamesdk_GameActivity_initializeNativeCode
          
          include $(BUILD_SHARED_LIBRARY)

          $(call import-module,prefab/game-activity)
      """
          .trimIndent()

  // Application.mk
  fun jniBuildSystemApplicatNdk(): String =
      """
        APP_ABI := all
        APP_PLATFORM := android-21
        APP_STL := c++_shared
        APP_CPPFLAGS += -std=c++17
      """
          .trimIndent()

  fun jniBuildSystemCMake(): String =
      """
          # For more information about using CMake with Android Studio, read the
          # documentation: https://d.android.com/studio/projects/add-native-code.html
          
          cmake_minimum_required(VERSION 3.22.1)
          
          project("myapplication")
          
          # Creates your game shared library. The name must be the same as the
          # one used for loading in your Kotlin/Java or AndroidManifest.txt files.
          add_library(myapplication SHARED
                  main.cpp
                  AndroidOut.cpp
                  Renderer.cpp
                  Shader.cpp
                  TextureAsset.cpp
                  Utility.cpp)
          
          # Searches for a package provided by the game activity dependency
          find_package(game-activity REQUIRED CONFIG)
          
          # Forces the linker to keep the JNI entry point for GameActivity
          set(CMAKE_SHARED_LINKER_FLAGS
                  "${'$'}{CMAKE_SHARED_LINKER_FLAGS} -u Java_com_google_androidgamesdk_GameActivity_initializeNativeCode")
          
          # Configure libraries CMake uses to link your target library.
          target_link_libraries(myapplication
                  # The game activity
                  game-activity::game-activity_static
          
                  # EGL and other dependent libraries required for drawing
                  # and interacting with Android system
                  EGL
                  GLESv3
                  jnigraphics
                  android
                  log)
      """
          .trimIndent()

  private fun packageToJniTransformer(id: String): String {
    return id.replace(".", "_")
  }
}
