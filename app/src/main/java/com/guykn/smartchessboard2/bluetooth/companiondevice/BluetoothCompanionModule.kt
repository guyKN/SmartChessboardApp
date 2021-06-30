package com.guykn.smartchessboard2.bluetooth.companiondevice

import android.app.Activity
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceCallbacks
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped

@Module
@InstallIn(ActivityComponent::class)
abstract class BluetoothCompanionModule {

    @ActivityScoped
    @Binds
    abstract fun bindDeviceCallback(
        deviceCallbacks: CompanionDeviceCallbacks
    ): CompanionDeviceConnector.DeviceCallback

    @ActivityScoped
    @Binds
    abstract fun bindBluetoothStateCallback(
        deviceCallbacks: CompanionDeviceCallbacks
    ): CompanionDeviceConnector.BluetoothStateCallback

    companion object {
        @Provides
        fun provideIntentCallback(activity: Activity): CompanionDeviceConnector.IntentCallback {
            check(activity is CompanionDeviceConnector.IntentCallback) { "All activities must implement CompanionDeviceConnector.IntentCallback" }
            return activity
        }
    }
}
