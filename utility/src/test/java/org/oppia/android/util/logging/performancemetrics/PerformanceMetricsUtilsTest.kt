package org.oppia.android.util.logging.performancemetrics

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.TrafficStats
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oppia.android.app.model.OppiaMetricLog
import org.oppia.android.testing.TestLogReportingModule
import org.oppia.android.testing.robolectric.RobolectricModule
import org.oppia.android.testing.threading.TestDispatcherModule
import org.oppia.android.testing.time.FakeOppiaClockModule
import org.oppia.android.util.data.DataProvidersInjector
import org.oppia.android.util.data.DataProvidersInjectorProvider
import org.oppia.android.util.locale.LocaleProdModule
import org.oppia.android.util.logging.EnableConsoleLog
import org.oppia.android.util.logging.EnableFileLog
import org.oppia.android.util.logging.GlobalLogLevel
import org.oppia.android.util.logging.LogLevel
import org.oppia.android.util.logging.SyncStatusModule
import org.oppia.android.util.networking.NetworkConnectionUtilDebugModule
import org.oppia.android.util.platformparameter.ENABLE_LANGUAGE_SELECTION_UI_DEFAULT_VALUE
import org.oppia.android.util.platformparameter.EnableLanguageSelectionUi
import org.oppia.android.util.platformparameter.LearnerStudyAnalytics
import org.oppia.android.util.platformparameter.PlatformParameterValue
import org.oppia.android.util.platformparameter.SPLASH_SCREEN_WELCOME_MSG_DEFAULT_VALUE
import org.oppia.android.util.platformparameter.SYNC_UP_WORKER_TIME_PERIOD_IN_HOURS_DEFAULT_VALUE
import org.oppia.android.util.platformparameter.SplashScreenWelcomeMsg
import org.oppia.android.util.platformparameter.SyncUpWorkerTimePeriodHours
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowActivityManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TEST_PACKAGE_NAME = "TEST_PACKAGE_NAME"
private const val TEST_PACKAGE_LABEL = "TEST_PACKAGE_LABEL"
private const val TEST_APP_PATH = "TEST_APP_PATH"
private const val TEST_PID = 1

/** Tests for [PerformanceMetricsUtils]. */
// FunctionName: test names are conventionally named with underscores.
@Suppress("FunctionName")
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = PerformanceMetricsUtilsTest.TestApplication::class)
class PerformanceMetricsUtilsTest {

  @Inject
  lateinit var performanceMetricsUtils: PerformanceMetricsUtils

  @Inject
  lateinit var context: Application

  @Before
  fun setUp() {
    setUpTestApplicationComponent()
  }

  @Test
  fun testPerformanceMetricsUtils_setTotalMemory_returnsCorrectMemoryTier() {
    val activityManager: ActivityManager =
      RuntimeEnvironment.application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val shadowActivityManager: ShadowActivityManager = shadowOf(activityManager)
    val memoryInfo = ActivityManager.MemoryInfo()
    memoryInfo.totalMem = (1.5 * 1024 * 1024 * 1024).toLong()
    shadowActivityManager.setMemoryInfo(memoryInfo)
    val memoryTier = performanceMetricsUtils.getDeviceMemoryTier()
    assertThat(memoryTier).isEqualTo(OppiaMetricLog.MemoryTier.MEDIUM_MEMORY_TIER)
  }

  @Test
  fun testPerformanceMetricsUtils_getTotalStorageUsed_returnsCorrectStorageUsage() {
    val permanentStorageUsage = context.filesDir.totalSpace - context.filesDir.freeSpace
    val cacheStorageUsage = context.cacheDir.totalSpace - context.cacheDir.freeSpace
    val expectedStorageValue = permanentStorageUsage + cacheStorageUsage

    assertThat(performanceMetricsUtils.getUsedStorage()).isEqualTo(expectedStorageValue)
  }

  @Test
  fun testPerformanceMetricsUtils_getTotalStorageUsageTier_returnsCorrectStorageUsageTier() {
    val permanentStorageUsage = context.filesDir.totalSpace - context.filesDir.freeSpace
    val cacheStorageUsage = context.cacheDir.totalSpace - context.cacheDir.freeSpace
    val expectedStorageValue = permanentStorageUsage + cacheStorageUsage

    val expectedStorageTierValue = when (expectedStorageValue.toDouble() / (1024 * 1024 * 1024)) {
      in 0.00..5.00 -> OppiaMetricLog.StorageTier.LOW_STORAGE
      in 5.00..20.00 -> OppiaMetricLog.StorageTier.MEDIUM_STORAGE
      else -> OppiaMetricLog.StorageTier.HIGH_STORAGE
    }

    assertThat(performanceMetricsUtils.getDeviceStorageTier())
      .isEqualTo(expectedStorageTierValue)
  }

  @Test
  fun testPerformanceMetricsUtils_getBytesSent_returnsCorrectAmountOfNetworkBytesSent() {
    val expectedNetworkBytesSent = TrafficStats.getUidTxBytes(context.applicationInfo.uid)

    assertThat(performanceMetricsUtils.getTotalSentBytes())
      .isEqualTo(expectedNetworkBytesSent)
  }

  @Test
  fun testPerformanceMetricsUtils_getBytesReceived_returnsCorrectAmountOfNetworkBytesReceived() {
    val expectedNetworkBytesReceived = TrafficStats.getUidRxBytes(context.applicationInfo.uid)

    assertThat(performanceMetricsUtils.getTotalReceivedBytes())
      .isEqualTo(expectedNetworkBytesReceived)
  }

  @Test
  fun testPerformanceMetricsUtils_setAppProcesses_getMemoryUsage_returnsCorrectMemoryUsage() {
    val process1 = ActivityManager.RunningAppProcessInfo().apply {
      this.pid = TEST_PID
      this.importanceReasonComponent = ComponentName("com.robolectric", "process 1")
    }
    val process2 = ActivityManager.RunningAppProcessInfo().apply {
      this.pid = TEST_PID
      this.importanceReasonComponent = ComponentName("com.robolectric", "process 2")
    }
    var totalPssUsedTest = 0
    val activityManager: ActivityManager =
      RuntimeEnvironment.application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val shadowActivityManager: ShadowActivityManager = shadowOf(activityManager)
    shadowActivityManager.setProcesses(listOf(process1, process2))
    val processMemoryInfo = activityManager.getProcessMemoryInfo(arrayOf(TEST_PID).toIntArray())
    if (processMemoryInfo != null) {
      for (element in processMemoryInfo) {
        totalPssUsedTest += element.totalPss
      }
    }
    assertThat(performanceMetricsUtils.getTotalPssUsed()).isEqualTo(totalPssUsedTest)
  }

  @Test
  fun testPerformanceMetricsUtils_removeCurrentApp_installTestApp_returnssCorrectApkSize() {
    val applicationInfo = ApplicationInfo()
    val testApkSize = (File(TEST_APP_PATH).length() / 1024)
    applicationInfo.apply {
      this.packageName = TEST_PACKAGE_NAME
      this.sourceDir = TEST_APP_PATH
      this.name = TEST_PACKAGE_LABEL
      this.flags = 0
    }
    val packageManager = RuntimeEnvironment.application.packageManager
    val shadowPackageManager = shadowOf(packageManager)

    shadowPackageManager.removePackage(RuntimeEnvironment.application.packageName)
    shadowPackageManager.installPackage(
      PackageInfo().apply {
        this.packageName = TEST_PACKAGE_NAME
        this.applicationInfo = applicationInfo
      }
    )

    val apkSize = performanceMetricsUtils.getApkSize()
    assertThat(apkSize).isEqualTo(testApkSize)
  }

  @Test
  fun testPerformanceMetricsUtils_putAppInForeground_verifyIsAppInForegroundReturnsCorrectValue() {
    performanceMetricsUtils.onAppInForeground()

    assertThat(performanceMetricsUtils.isAppInForeground()).isTrue()
  }

  @Test
  fun testPerformanceMetricsUtils_putAppInBackground_verifyIsAppInForegroundReturnsCorrectValue() {
    performanceMetricsUtils.onAppInBackground()

    assertThat(performanceMetricsUtils.isAppInForeground()).isFalse()
  }

  private fun setUpTestApplicationComponent() {
    ApplicationProvider.getApplicationContext<TestApplication>().inject(this)
  }

  // TODO(#89): Move this to a common test application component.
  @Module
  class TestModule {
    @Provides
    @Singleton
    fun provideContext(application: Application): Context {
      return application
    }

    // TODO(#59): Either isolate these to their own shared test module, or use the real logging
    // module in tests to avoid needing to specify these settings for tests.
    @EnableConsoleLog
    @Provides
    fun provideEnableConsoleLog(): Boolean = true

    @EnableFileLog
    @Provides
    fun provideEnableFileLog(): Boolean = false

    @GlobalLogLevel
    @Provides
    fun provideGlobalLogLevel(): LogLevel = LogLevel.VERBOSE
  }

  @Module
  class TestPlatformParameterModule {

    companion object {
      var forceLearnerAnalyticsStudy: Boolean = false
    }

    @Provides
    @SplashScreenWelcomeMsg
    fun provideSplashScreenWelcomeMsgParam(): PlatformParameterValue<Boolean> {
      return PlatformParameterValue.createDefaultParameter(SPLASH_SCREEN_WELCOME_MSG_DEFAULT_VALUE)
    }

    @Provides
    @SyncUpWorkerTimePeriodHours
    fun provideSyncUpWorkerTimePeriod(): PlatformParameterValue<Int> {
      return PlatformParameterValue.createDefaultParameter(
        SYNC_UP_WORKER_TIME_PERIOD_IN_HOURS_DEFAULT_VALUE
      )
    }

    @Provides
    @EnableLanguageSelectionUi
    fun provideEnableLanguageSelectionUi(): PlatformParameterValue<Boolean> {
      return PlatformParameterValue.createDefaultParameter(
        ENABLE_LANGUAGE_SELECTION_UI_DEFAULT_VALUE
      )
    }

    @Provides
    @LearnerStudyAnalytics
    fun provideLearnerStudyAnalytics(): PlatformParameterValue<Boolean> {
      return PlatformParameterValue.createDefaultParameter(forceLearnerAnalyticsStudy)
    }
  }

  // TODO(#89): Move this to a common test application component.
  @Singleton
  @Component(
    modules = [
      TestModule::class, TestLogReportingModule::class,
      TestDispatcherModule::class, RobolectricModule::class, FakeOppiaClockModule::class,
      NetworkConnectionUtilDebugModule::class, LocaleProdModule::class,
      TestPlatformParameterModule::class, SyncStatusModule::class
    ]
  )
  interface TestApplicationComponent : DataProvidersInjector {
    @Component.Builder
    interface Builder {
      @BindsInstance
      fun setApplication(application: Application): Builder
      fun build(): TestApplicationComponent
    }

    fun inject(performanceMetricsUtilsTest: PerformanceMetricsUtilsTest)
  }

  class TestApplication : Application(), DataProvidersInjectorProvider {
    private val component: TestApplicationComponent by lazy {
      DaggerPerformanceMetricsUtilsTest_TestApplicationComponent.builder()
        .setApplication(this)
        .build()
    }

    fun inject(performanceMetricsUtilsTest: PerformanceMetricsUtilsTest) {
      component.inject(performanceMetricsUtilsTest)
    }

    override fun getDataProvidersInjector(): DataProvidersInjector = component
  }
}
