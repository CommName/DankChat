package com.flxrs.dankchat

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.flxrs.dankchat.preferences.AppearanceSettingsFragment
import com.flxrs.dankchat.preferences.DankChatPreferenceStore
import com.flxrs.dankchat.preferences.NotificationsSettingsFragment
import com.flxrs.dankchat.preferences.ChatSettingsFragment
import com.flxrs.dankchat.service.TwitchService
import com.flxrs.dankchat.utils.dialog.AddChannelDialogResultHandler
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity(), AddChannelDialogResultHandler,
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private val channels = mutableListOf<String>()
    private val viewModel: DankChatViewModel by viewModel()
    private lateinit var twitchPreferences: DankChatPreferenceStore
    private lateinit var broadcastReceiver: BroadcastReceiver
    private var twitchService: TwitchService? = null
    private val pendingChannelsToClear = mutableListOf<String>()

    private val twitchServiceConnection = TwitchServiceConnection()
    var isBound = false
    var channelToOpen = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = handleShutDown()
        }

        val filter = IntentFilter(SHUTDOWN_REQUEST_FILTER)
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter)
        setContentView(R.layout.main_activity)
        twitchPreferences = DankChatPreferenceStore(this)
        twitchPreferences.getChannelsAsString()?.let { channels.addAll(it.split(',')) }
            ?: twitchPreferences.getChannels()?.let {
                channels.addAll(it)
                twitchPreferences.setChannels(null)
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            handleShutDown()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) Intent(this, TwitchService::class.java).also {
            try {
                isBound = true
                ContextCompat.startForegroundService(this, it)
                bindService(it, twitchServiceConnection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.e(TAG, Log.getStackTraceString(t))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            isBound = false
            try {
                unbindService(twitchServiceConnection)
            } catch (t: Throwable) {
                Log.e(TAG, Log.getStackTraceString(t))
            }
            if (!isChangingConfigurations) {
                twitchService?.shouldNotifyOnMention = true
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return findNavController(R.id.main_content).navigateUp() || super.onSupportNavigateUp()
    }

    override fun onAddChannelDialogResult(channel: String) {
        val fragment =
            supportFragmentManager.primaryNavigationFragment?.childFragmentManager?.fragments?.first()
        if (fragment is MainFragment) {
            fragment.addChannel(channel)
        }
        invalidateOptionsMenu()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val channelExtra = intent?.getStringExtra(OPEN_CHANNEL_KEY) ?: ""
        channelToOpen = channelExtra
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val navController = findNavController(R.id.main_content)
        when (pref.fragment.substringAfterLast(".")) {
            AppearanceSettingsFragment::class.java.simpleName -> navController.navigate(R.id.action_overviewSettingsFragment_to_appearanceSettingsFragment)
            NotificationsSettingsFragment::class.java.simpleName -> navController.navigate(R.id.action_overviewSettingsFragment_to_notificationsSettingsFragment)
            ChatSettingsFragment::class.java.simpleName -> navController.navigate(R.id.action_overviewSettingsFragment_to_chatSettingsFragment)
            else -> return false
        }
        return true
    }

    fun clearNotificationsOfChannel(channel: String) {
        if (isBound && twitchService != null) {
            twitchService?.clearNotificationsOfChannel(channel)
        } else {
            pendingChannelsToClear += channel
        }
    }

    private fun handleShutDown() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        finishAndRemoveTask()
        Intent(this, TwitchService::class.java).also {
            stopService(it)
        }

        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private inner class TwitchServiceConnection : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TwitchService.LocalBinder
            twitchService = binder.service
            twitchService?.shouldNotifyOnMention = false
            isBound = true

            if (pendingChannelsToClear.isNotEmpty()) {
                pendingChannelsToClear.forEach { twitchService?.clearNotificationsOfChannel(it) }
                pendingChannelsToClear.clear()
            }

            if (viewModel.started) {
                if (!isChangingConfigurations) {
                    viewModel.reconnect(true)
                }

            } else {
                viewModel.started = true
                val oauth = twitchPreferences.getOAuthKey() ?: ""
                val name = twitchPreferences.getUserName() ?: ""
                viewModel.connectAndJoinChannels(name, oauth)
            }
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            twitchService = null
            isBound = false
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val SHUTDOWN_REQUEST_FILTER = "shutdown_request_filter"
        const val OPEN_CHANNEL_KEY = "open_channel"
    }
}