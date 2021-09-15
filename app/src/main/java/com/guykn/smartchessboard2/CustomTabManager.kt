package com.guykn.smartchessboard2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.res.ResourcesCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject


@ActivityRetainedScoped
class CustomTabManager @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    serviceConnector: ServiceConnector
) {

    private var customTabsClient: CustomTabsClient? = null
    private var customTabSession: CustomTabsSession? = null

    private val connectedCallbacks: MutableList<()->Unit> = arrayListOf()

    private val likelyUrlsToLaunch: MutableList<String> = arrayListOf()

    private val connection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            customTabsClient = client
            client.warmup(0)
            serviceConnector.callWhenConnected { repository ->
                val session = client.newSession(repository.customTabNavigationManager)
                customTabSession = session
                if (session == null) {
                    customTabsClient = null
                    customTabsSessionError = true
                }

                connectedCallbacks.forEach { callback ->
                    callback()
                }
                connectedCallbacks.clear()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            customTabSession = null
            customTabsClient = null
            customTabsSessionError = true
        }
    }

    private var customTabsSessionError: Boolean = !startCustomTabsConnection()

    fun openChromeTab(context: Context ,url: String){
        when {
            customTabsSessionError -> {
                // the custom tabs session failed, so we simply open custom Chrome tabs without a session.
                openChromeTabInternal(context, url)
            }
            customTabSession == null -> {
                // the customTabSession is currently loading, we add a callback to open the custom tab when it's done loading.
                connectedCallbacks.add { openChromeTabInternal(context, url) }
            }
            else -> {
                // the customTabSession is done loading, so we open the chrome tab now.
                openChromeTabInternal(context, url)
            }
        }
    }

    fun mayLaunchUrl(url: String){
        if (!likelyUrlsToLaunch.contains(url)) {
            likelyUrlsToLaunch.add(url)
            customTabSession?.mayLaunchUrl(Uri.parse(url), null, null)
                ?: connectedCallbacks.add {
                    customTabSession?.mayLaunchUrl(Uri.parse(url), null, null)
                }
        }
    }

    private fun openChromeTabInternal(context: Context,url: String) {
        val backIconDrawable = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_baseline_arrow_back_24,
            context.theme
        )
        val customTabsIntentBuilder = CustomTabsIntent.Builder(customTabSession)
            .setUrlBarHidingEnabled(true)
            .setShowTitle(false)
        backIconDrawable?.toBitmap()?.let { backIconBitmap ->
            customTabsIntentBuilder.setCloseButtonIcon(backIconBitmap)
        }
        val customTabsIntent = customTabsIntentBuilder.build()
        customTabsIntent.launchUrl(context, Uri.parse(url))
    }

    /**
     * Returns true if the start was sucsessful, returns false if there was some error.
     */
    private fun startCustomTabsConnection(): Boolean {
        val customTabsPackages = getCustomTabsPackages()
        if (customTabsPackages.size == 0) {
            return false
        } else {
            val packageName = customTabsPackages[0].activityInfo.packageName
            return CustomTabsClient.bindCustomTabsService(applicationContext, packageName, connection)
        }
    }

    /**
     * Returns a list of packages that support Custom Tabs.
     */
    private fun getCustomTabsPackages(): ArrayList<ResolveInfo> {
        val pm = applicationContext.packageManager
        // Get default VIEW intent handler.
        val activityIntent = Intent()
            .setAction(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.fromParts("http", "", null))

        // Get all apps that can handle VIEW intents.
        val resolvedActivityList = pm.queryIntentActivities(activityIntent, 0)
        val packagesSupportingCustomTabs: ArrayList<ResolveInfo> = ArrayList()
        for (info in resolvedActivityList) {
            val serviceIntent = Intent()
            serviceIntent.action = ACTION_CUSTOM_TABS_CONNECTION
            serviceIntent.setPackage(info.activityInfo.packageName)
            // Check if this package also resolves the Custom Tabs service.
            if (pm.resolveService(serviceIntent, 0) != null) {
                packagesSupportingCustomTabs.add(info)
            }
        }
        return packagesSupportingCustomTabs
    }
}