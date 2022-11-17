package com.flomobility.anx.hermes.daemon

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.flomobility.anx.R
import com.flomobility.anx.app.TerminalActivity
import com.flomobility.anx.app.settings.properties.FloAppSharedProperties
import com.flomobility.anx.app.terminal.FloTerminalSessionClient
import com.flomobility.anx.app.utils.PluginUtils
import com.flomobility.anx.hermes.assets.AssetManager
import com.flomobility.anx.hermes.comms.SessionManager
import com.flomobility.anx.hermes.comms.SocketManager
import com.flomobility.anx.hermes.other.Constants.ACTION_START_OR_RESUME_SERVICE
import com.flomobility.anx.hermes.other.Constants.ACTION_STOP_AND_EXIT
import com.flomobility.anx.hermes.other.Constants.ACTION_STOP_SERVICE
import com.flomobility.anx.hermes.other.Constants.NOTIFICATION_CHANNEL_ID
import com.flomobility.anx.hermes.other.Constants.NOTIFICATION_CHANNEL_NAME
import com.flomobility.anx.hermes.other.Constants.NOTIFICATION_ID
import com.flomobility.anx.hermes.other.ThreadStatus
import com.flomobility.anx.hermes.usb.UsbPortManager
import com.flomobility.anx.shared.data.DataUtils
import com.flomobility.anx.shared.data.IntentUtils
import com.flomobility.anx.shared.logger.Logger
import com.flomobility.anx.shared.models.ExecutionCommand
import com.flomobility.anx.shared.models.errors.Errno
import com.flomobility.anx.shared.packages.PermissionUtils
import com.flomobility.anx.shared.settings.preferences.FloAppSharedPreferences
import com.flomobility.anx.shared.shell.*
import com.flomobility.anx.shared.terminal.TerminalSessionClientBase
import com.flomobility.anx.shared.terminal.TerminalConstants
import com.flomobility.anx.shared.terminal.TerminalConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.flomobility.anx.shared.terminal.TerminalConstants.TERMUX_APP.TERMUX_SERVICE
import com.flomobility.anx.terminal.TerminalSession
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.system.exitProcess

@AndroidEntryPoint
class EndlessService : LifecycleService() , TerminalTask.TermuxTaskClient, com.flomobility.anx.shared.shell.TerminalSession.TermuxSessionClient {

    private var isRunning = false

    private var EXECUTION_ID = 1000
    private val LOG_TAG = "EndlessService"


    /** This service is only bound from inside the same process and never uses IPC.  */
//    internal class LocalBinder : Binder() {
//        val service: EndlessService = this@
//    }

    inner class LocalBinder : Binder() {
        fun getService(): EndlessService? {
            return this@EndlessService
        }
    }

    private val mBinder: IBinder = LocalBinder()

    private val mHandler = Handler()

    /**
     * The foreground TerminalSessions which this service manages.
     * Note that this list is observed by [TerminalActivity.mTermuxSessionListViewController],
     * so any changes must be made on the UI thread and followed by a call to
     * [ArrayAdapter.notifyDataSetChanged] }.
     */

    val mTerminalSessions : MutableList<com.flomobility.anx.shared.shell.TerminalSession> = ArrayList()

    /**
     * The background TermuxTasks which this service manages.
     */
    val mTerminalTasks: MutableList<TerminalTask> = ArrayList()

    /**
     * The pending plugin ExecutionCommands that have yet to be processed by this service.
     */
    val mPendingPluginExecutionCommands: MutableList<ExecutionCommand> = ArrayList()


    /** The full implementation of the [TerminalSessionClient] interface to be used by [TerminalSession]
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    var mFloTerminalSessionClient: FloTerminalSessionClient? = null

    /** The basic implementation of the [TerminalSessionClient] interface to be used by [TerminalSession]
     * that does not hold activity references.
     */
    val mTerminalSessionClientBase =
        TerminalSessionClientBase()

    /** The wake lock and wifi lock are always acquired and released together.  */
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    /** If the user has executed the [TERMUX_SERVICE.ACTION_STOP_SERVICE] intent.  */
    var mWantsToStop = false

    var mTerminalTranscriptRows: Int? = null
    @Inject
    lateinit var baseNotificationBuilder: NotificationCompat.Builder

    private lateinit var currentNotificationBuilder: NotificationCompat.Builder

    @Inject
    lateinit var socketManager: SocketManager

    @Inject
    lateinit var usbPortManager: UsbPortManager

    @Inject
    lateinit var assetManager: AssetManager

    @Inject
    lateinit var sessionManager: SessionManager

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START_OR_RESUME_SERVICE -> {
                    if(!isRunning) {
                        startEndlessService()
                        Timber.d("Started service")
                    }
                }
                ACTION_STOP_SERVICE -> {
                    killService()
                    actionStopService();
                    Timber.d("Stopped service")
                }
                ACTION_STOP_AND_EXIT -> {
                    killService()
		    actionStopService();
                    Timber.d("Stopped service")
                    exitProcess(0)
                }
 		TerminalConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                }
                TerminalConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                }
                TerminalConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                }
                else -> { /*NO-OP*/
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

 override fun onUnbind(intent: Intent?): Boolean {
        Logger.logVerbose(LOG_TAG, "onUnbind")

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mFloTerminalSessionClient != null) unsetTerminalSessionClient()
        return false
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
        //   TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Logger.logVerbose(LOG_TAG, "onBind")
        return mBinder
    }

    private fun startEndlessService() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager)
        }
        val pendingIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, EndlessService::class.java).apply {
                    action = ACTION_STOP_AND_EXIT
                },
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            )
        currentNotificationBuilder = baseNotificationBuilder
        currentNotificationBuilder = currentNotificationBuilder.addAction(R.drawable.ic_stop, "Exit", pendingIntent)
        startForeground(NOTIFICATION_ID, currentNotificationBuilder.build())

        // init
        if (socketManager.threadStatus != ThreadStatus.ACTIVE) {
            socketManager.init()
            socketManager.doOnSubscribed { subscribed ->
                Handler(Looper.getMainLooper()).postDelayed({
                    currentNotificationBuilder.setContentText(
                        if (subscribed) {
                            "Active session <-> ${sessionManager.connectedDeviceIp}"
                        } else {
                            "No active session."
                        }
                    )
                    notificationManager.notify(NOTIFICATION_ID, currentNotificationBuilder.build())
                }, 10L)
            }
        }
        if (usbPortManager.threadStatus != ThreadStatus.ACTIVE) usbPortManager.init()
        if (assetManager.threadStatus != ThreadStatus.ACTIVE) assetManager.init()
        isRunning = true
    }

    private fun killService() {
        socketManager.destroy()
        assetManager.stopAllAssets()
        stopForeground(true)
        stopSelf()
        currentNotificationBuilder.clearActions()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

 /** Process action to stop service.  */
    private fun actionStopService() {
        mWantsToStop = true
        killAllTermuxExecutionCommands()
        requestStopService()
    }

    /** Make service leave foreground mode.  */
    private fun runStopForeground() {
        stopForeground(true)
    }

    /** Request to stop service.  */
    private fun requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service")
        runStopForeground()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.logVerbose(LOG_TAG, "onDestroy")
        TerminalShellUtils.clearTermuxTMPDIR(true)
        actionReleaseWakeLock(false)
        if (!mWantsToStop) killAllTermuxExecutionCommands()
        runStopForeground()
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    @Synchronized
    private fun killAllTermuxExecutionCommands() {
        var processResult: Boolean
        /*Logger.logDebug(
            LOG_TAG,
            "Killing TerminalSessions=" + (mTerminalSessions?.size ?:0) + ", TerminalTasks=" + mTerminalTasks.size + ", PendingPluginExecutionCommands=" + mPendingPluginExecutionCommands.size
        )*/
        val terminalSessions: List<com.flomobility.anx.shared.shell.TerminalSession> = ArrayList(mTerminalSessions)
        for (i in terminalSessions.indices) {
            val executionCommand = terminalSessions[i].executionCommand
            processResult =
                mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult
            terminalSessions[i].killIfExecuting(this, processResult)
        }
        val terminalTasks: List<TerminalTask> = ArrayList(mTerminalTasks)
        for (i in terminalTasks.indices) {
            val executionCommand = terminalTasks[i].executionCommand
            if (executionCommand.isPluginExecutionCommandWithPendingResult) terminalTasks[i].killIfExecuting(
                this,
                true
            )
        }
        val pendingPluginExecutionCommands: List<ExecutionCommand> =
            ArrayList(mPendingPluginExecutionCommands)
        for (i in pendingPluginExecutionCommands.indices) {
            val executionCommand = pendingPluginExecutionCommands[i]
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult) {
                if (executionCommand.setStateFailed(
                        Errno.ERRNO_CANCELLED.code,
                        this.getString(com.flomobility.anx.shared.R.string.error_execution_cancelled)
                    )
                ) {
                    PluginUtils.processPluginExecutionCommandResult(
                        this,
                        LOG_TAG,
                        executionCommand
                    )
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks.  */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(
                LOG_TAG,
                "Ignoring acquiring WakeLocks since they are already held"
            )
            return
        }
        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, TerminalConstants.TERMINAL_APP_NAME.lowercase(
                Locale.getDefault()
            ) + ":service-wakelock"
        )
        mWakeLock?.acquire()

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        mWifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, TerminalConstants.TERMINAL_APP_NAME.lowercase(
                Locale.getDefault()
            )
        )
        mWakeLock?.acquire()
        val packageName = packageName
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val whitelist = Intent()
            whitelist.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            whitelist.data = Uri.parse("package:$packageName")
            whitelist.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                startActivity(whitelist)
            } catch (e: ActivityNotFoundException) {
                Logger.logStackTraceWithMessage(
                    LOG_TAG,
                    "Failed to call ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                    e
                )
            }
        }
        updateNotification()
        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully")
    }

    /*private fun buildNotification(): Notification? {
        val res = resources

        // Set pending intent to be launched when notification is clicked
        val notificationIntent = TermuxActivity.newInstance(this)
        val contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)


        // Set notification text
        val sessionCount = getTerminalSessionsSize()
        val taskCount = mTermuxTasks.size
        var notificationText =
            sessionCount.toString() + " session" + if (sessionCount == 1) "" else "s"
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + if (taskCount == 1) "" else "s"
        }
        val wakeLockHeld = mWakeLock != null
        if (wakeLockHeld) notificationText += " (wake lock held)"


        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        val priority = if (wakeLockHeld) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW


        // Build the notification
        val builder = NotificationUtils.geNotificationBuilder(
            this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority,
            TermuxConstants.TERMUX_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT
        ) ?: return null

        // No need to show a timestamp:
        builder.setShowWhen(false)

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification)

        // Set background color for small notification icon
        builder.setColor(-0x9f8275)

        // TerminalSessions are always ongoing
        builder.setOngoing(true)


        // Set Exit button action
        val exitIntent =
            Intent(this, EndlessService::class.java).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE)
        builder.addAction(
            android.R.drawable.ic_delete,
            res.getString(R.string.notification_action_exit),
            PendingIntent.getService(this, 0, exitIntent, 0)
        )


        // Set Wakelock button actions
        val newWakeAction =
            if (wakeLockHeld) TERMUX_SERVICE.ACTION_WAKE_UNLOCK else TERMUX_SERVICE.ACTION_WAKE_LOCK
        val toggleWakeLockIntent = Intent(this, EndlessService::class.java).setAction(newWakeAction)
        val actionTitle =
            res.getString(if (wakeLockHeld) R.string.notification_action_wake_unlock else R.string.notification_action_wake_lock)
        val actionIcon =
            if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock
        builder.addAction(
            actionIcon,
            actionTitle,
            PendingIntent.getService(this, 0, toggleWakeLockIntent, 0)
        )
        return builder.build()
    }*/


    /** Update the shown foreground service notification after making any changes that affect it.  */
    @Synchronized
    private fun updateNotification() {
        if (mWakeLock == null && mTerminalSessions.isEmpty() && mTerminalTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService()
        } else {
            // ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
        }
    }
    /** Process action to release Power and Wi-Fi WakeLocks.  */
    private fun actionReleaseWakeLock(updateNotification: Boolean) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(
                LOG_TAG,
                "Ignoring releasing WakeLocks since none are already held"
            )
            return
        }
        Logger.logDebug(LOG_TAG, "Releasing WakeLocks")
        if (mWakeLock != null) {
            mWakeLock?.release()
            mWakeLock = null
        }
        if (mWifiLock != null) {
            mWifiLock?.release()
            mWifiLock = null
        }
        if (updateNotification) updateNotification()
        Logger.logDebug(LOG_TAG, "WakeLocks released successfully")
    }

    /** Process [TERMUX_SERVICE.ACTION_SERVICE_EXECUTE] intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask.  */
    private fun actionServiceExecute(intent: Intent?) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute")
            return
        }
        val executionCommand = ExecutionCommand(getNextExecutionId())
        executionCommand.executableUri = intent.data
        executionCommand.inBackground =
            intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)
        if (executionCommand.executableUri != null) {
            executionCommand.executable = executionCommand.executableUri.path
            executionCommand.arguments =
                IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null)
            if (executionCommand.inBackground) executionCommand.stdin =
                IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null)
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(
                intent,
                TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL,
                null
            )
        }
        executionCommand.workingDirectory =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null)
        executionCommand.isFailsafe =
            intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION)
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(
            intent,
            TERMUX_SERVICE.EXTRA_COMMAND_LABEL,
            "Execution Intent Command"
        )
        executionCommand.commandDescription =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null)
        executionCommand.commandHelp =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null)
        executionCommand.pluginAPIHelp =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null)
        executionCommand.isPluginExecutionCommand = true
        executionCommand.resultConfig.resultPendingIntent =
            intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT)
        executionCommand.resultConfig.resultDirectoryPath =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null)
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile =
                intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false)
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(
                intent,
                TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME,
                null
            )
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(
                intent,
                TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT,
                null
            )
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(
                intent,
                TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT,
                null
            )
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(
                intent,
                TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX,
                null
            )
        }

        // Add the execution command to pending plugin execution commands list
        mPendingPluginExecutionCommands.add(executionCommand)
        if (executionCommand.inBackground) {
            executeTermuxTaskCommand(executionCommand)
        } else {
            executeTermuxSessionCommand(executionCommand)
        }
    }


    /** Execute a shell command in background [TerminalTask].  */
    private fun executeTermuxTaskCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return
        /*Logger.logDebug(
            LOG_TAG,
            "Executing background \"" + executionCommand.commandIdAndLabelLogString + "\" TerminalTask command"
        )*/
        val newTermuxTask = createTermuxTask(executionCommand)
    }

    /** Create a [TerminalTask].  */
    fun createTermuxTask(
        executablePath: String?,
        arguments: Array<String?>?,
        stdin: String?,
        workingDirectory: String?
    ): TerminalTask? {
        return createTermuxTask(
            ExecutionCommand(
                getNextExecutionId(),
                executablePath,
                arguments,
                stdin,
                workingDirectory,
                true,
                false
            )
        )
    }

    /** Create a [TerminalTask].  */
    @Synchronized
    fun createTermuxTask(executionCommand: ExecutionCommand?): TerminalTask? {
        if (executionCommand == null) return null
        /*Logger.logDebug(
            LOG_TAG,
            "Creating \"" + executionCommand.commandIdAndLabelLogString + "\" TerminalTask"
        )*/
        if (!executionCommand.inBackground) {
//            Logger.logDebug(
//                LOG_TAG,
//                "Ignoring a foreground execution command passed to createTerminalTask()"
//            )
            return null
        }
        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) Logger.logVerboseExtended(
            LOG_TAG,
            executionCommand.toString()
        )
        val newTerminalTask =
            TerminalTask.execute(this, executionCommand, this,
                TerminalShellEnvironmentClient(), false)
        if (newTerminalTask == null) {
            /*Logger.logError(
                LOG_TAG,
                """
                Failed to execute new TerminalTask command for:
                ${executionCommand.commandIdAndLabelLogString}
                """.trimIndent()
            )*/
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand) PluginUtils.processPluginExecutionCommandError(
                this,
                LOG_TAG,
                executionCommand,
                false
            ) else Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        mTerminalTasks.add(newTerminalTask)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand) mPendingPluginExecutionCommands.remove(
            executionCommand
        )
        updateNotification()
        return newTerminalTask
    }

    /** Callback received when a [TerminalTask] finishes.  */
    override fun onTermuxTaskExited(terminalTask: TerminalTask?) {
        mHandler.post {
            if (terminalTask != null) {
                val executionCommand = terminalTask.executionCommand
                /*Logger.logVerbose(
                    LOG_TAG,
                    "The onTerminalTaskExited() callback called for \"" + executionCommand!!.commandIdAndLabelLogString + "\" TerminalTask command"
                )*/

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand) PluginUtils.processPluginExecutionCommandResult(
                    this,
                    LOG_TAG,
                    executionCommand
                )
//                Logger.logVerbose(
//                    LOG_TAG,
//                    "The onTerminalTaskExited() result callback : " + executionCommand.resultData.exitCode
//                )
                mTerminalTasks.remove(terminalTask)
            }
            updateNotification()
        }
    }


    /** Execute a shell command in a foreground [TerminalSession].  */
    private fun executeTermuxSessionCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return
        /*Logger.logDebug(
            LOG_TAG,
            "Executing foreground \"" + executionCommand.commandIdAndLabelLogString + "\" TerminalSession command"
        )*/
        var sessionName: String? = null

        // Transform executable path to session name, e.g. "/bin/do-something.sh" => "do something.sh".
        if (executionCommand.executable != null) {
            sessionName =
                ShellUtils.getExecutableBasename(executionCommand.executable).replace('-', ' ')
        }
        val terminalSession = createTermuxSession(executionCommand, sessionName) ?: return
        handleSessionAction(
            DataUtils.getIntFromString(
                executionCommand.sessionAction,
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY
            ),
            terminalSession.terminalSession
        )
    }

    /**
     * Create a [TerminalSession].
     * Currently called by [FloTerminalSessionClient.addNewSession] to add a new [TerminalSession].
     */
    fun createTermuxSession(
        executablePath: String?,
        arguments: Array<String?>?,
        stdin: String?,
        workingDirectory: String?,
        isFailSafe: Boolean,
        sessionName: String?
    ): com.flomobility.anx.shared.shell.TerminalSession? {
        return createTermuxSession(
            ExecutionCommand(
                getNextExecutionId(),
                executablePath,
                arguments,
                stdin,
                workingDirectory,
                false,
                isFailSafe
            ), sessionName
        )
    }

    /** Create a [TerminalSession].  */
    @Synchronized
    fun createTermuxSession(
        executionCommand: ExecutionCommand?,
        sessionName: String?
    ): com.flomobility.anx.shared.shell.TerminalSession? {
        if (executionCommand == null) return null
        /*Logger.logDebug(
            LOG_TAG,
            "Creating \"" + executionCommand.commandIdAndLabelLogString + "\" TerminalSession"
        )*/
        if (executionCommand.inBackground) {
            /*Logger.logDebug(
                LOG_TAG,
                "Ignoring a background execution command passed to createTerminalSession()"
            )*/
            return null
        }
        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) Logger.logVerboseExtended(
            LOG_TAG,
            executionCommand.toString()
        )

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        executionCommand.terminalTranscriptRows = getTerminalTranscriptRows()
        val newTerminalSession = com.flomobility.anx.shared.shell.TerminalSession.execute(
            this,
            executionCommand,
            getTermuxTerminalSessionClient()!!,
            this,
            TerminalShellEnvironmentClient(),
            sessionName,
            executionCommand.isPluginExecutionCommand
        )
        if (newTerminalSession == null) {
            /*Logger.logError(
                LOG_TAG,
                """
                Failed to execute new TerminalSession command for:
                ${executionCommand.commandIdAndLabelLogString}
                """.trimIndent()
            )*/
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand) PluginUtils.processPluginExecutionCommandError(
                this,
                LOG_TAG,
                executionCommand,
                false
            ) else Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        mTerminalSessions.add(newTerminalSession)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand) mPendingPluginExecutionCommands.remove(
            executionCommand
        )

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mFloTerminalSessionClient != null) mFloTerminalSessionClient!!.termuxSessionListNotifyUpdated()
        updateNotification()
        TerminalActivity.updateTermuxActivityStyling(this)
        return newTerminalSession
    }

    /** Remove a TermuxSession.  */
    @Synchronized
    fun removeTermuxSession(sessionToRemove: TerminalSession): Int {
        val index: Int = getIndexOfSession(sessionToRemove)
        if (index >= 0) mTerminalSessions[index].finish()
        return index
    }

    /** Callback received when a [TerminalSession] finishes.  */
    override fun onTermuxSessionExited(terminalSession: com.flomobility.anx.shared.shell.TerminalSession?) {
        if (terminalSession != null) {
            val executionCommand = terminalSession.executionCommand
            /*Logger.logVerbose(
                LOG_TAG,
                "The onTerminalSessionExited() callback called for \"" + executionCommand!!.commandIdAndLabelLogString + "\" TerminalSession command"
            )*/

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand) PluginUtils.processPluginExecutionCommandResult(
                this,
                LOG_TAG,
                executionCommand
            )

            mTerminalSessions.remove(terminalSession)

            // Notify {@link TerminalSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mFloTerminalSessionClient != null) mFloTerminalSessionClient!!.termuxSessionListNotifyUpdated()
        }
        updateNotification()
    }

    /** Get the terminal transcript rows to be used for new [TerminalSession].  */
    fun getTerminalTranscriptRows(): Int? {
        if (mTerminalTranscriptRows == null) setTerminalTranscriptRows()
        return mTerminalTranscriptRows
    }

    fun setTerminalTranscriptRows() {
        // TermuxService only uses this termux property currently, so no need to load them all into
        // an internal values map like TermuxActivity does
        mTerminalTranscriptRows = FloAppSharedProperties.getTerminalTranscriptRows(this)
    }


    /** Process session action for new session.  */
    private fun handleSessionAction(sessionAction: Int, newTerminalSession: TerminalSession) {
        Logger.logDebug(
            LOG_TAG,
            "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\""
        )
        when (sessionAction) {
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                if (mFloTerminalSessionClient != null) mFloTerminalSessionClient!!.setCurrentSession(
                    newTerminalSession
                )
                startTermuxActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY -> {
                if (getTermuxSessionsSize() == 1) setCurrentStoredTerminalSession(newTerminalSession)
                startTermuxActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                if (mFloTerminalSessionClient != null) mFloTerminalSessionClient!!.setCurrentSession(
                    newTerminalSession
                )
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY -> if (getTermuxSessionsSize() == 1) setCurrentStoredTerminalSession(
                newTerminalSession
            )
            else -> {
                Logger.logError(
                    LOG_TAG,
                    "Invalid sessionAction: \"$sessionAction\". Force using default sessionAction."
                )
                handleSessionAction(
                    TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY,
                    newTerminalSession
                )
            }
        }
    }

    /** Launch the []TermuxActivity} to bring it to foreground.  */
    private fun startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TerminalActivity.startTermuxActivity(this)
        } else {
            val preferences = FloAppSharedPreferences.build(this) ?: return
            if (preferences.arePluginErrorNotificationsEnabled()) Logger.showToast(
                this,
                this.getString(R.string.error_display_over_other_apps_permission_not_granted),
                true
            )
        }
    }


    /** If [TerminalActivity] has not bound to the [TermuxService] yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the [.mTermuxTerminalSessionClientBase]. Once [TerminalActivity] bind
     * callback is received, it should call [.setTermuxTerminalSessionClient] to set the
     * [TermuxService.mTermuxTerminalSessionClient] so that further terminal sessions are directly
     * passed the [FloTerminalSessionClient] object which fully implements the
     * [TerminalSessionClient] interface.
     *
     * @return Returns the [FloTerminalSessionClient] if [TerminalActivity] has bound with
     * [TermuxService], otherwise [TerminalSessionClientBase].
     */
    @Synchronized
    fun getTermuxTerminalSessionClient(): TerminalSessionClientBase? {
        return if (mFloTerminalSessionClient != null) mFloTerminalSessionClient else mTerminalSessionClientBase
    }

    /** This should be called when [TerminalActivity.onServiceConnected] is called to set the
     * [TermuxService.mTermuxTerminalSessionClient] variable and update the [TerminalSession]
     * and [TerminalEmulator] clients in case they were passed [TerminalSessionClientBase]
     * earlier.
     *
     * @param floTerminalSessionClient The [FloTerminalSessionClient] object that fully
     * implements the [TerminalSessionClient] interface.
     */
    @Synchronized
    fun setTermuxTerminalSessionClient(floTerminalSessionClient: FloTerminalSessionClient) {
        mFloTerminalSessionClient = floTerminalSessionClient
        for (i in mTerminalSessions.indices) mTerminalSessions[i].terminalSession.updateTerminalSessionClient(
            mFloTerminalSessionClient
        )
    }

    /** This should be called when [TerminalActivity] has been destroyed and in [.onUnbind]
     * so that the [TermuxService] and [TerminalSession] and [TerminalEmulator]
     * clients do not hold an activity references.
     */
    @Synchronized
    fun unsetTerminalSessionClient() {
        for (i in mTerminalSessions.indices) mTerminalSessions[i].terminalSession.updateTerminalSessionClient(
            mTerminalSessionClientBase
        )
        mFloTerminalSessionClient = null
    }

    private fun setCurrentStoredTerminalSession(session: TerminalSession?) {
        if (session == null) return
        // Make the newly created session the current one to be displayed
        val preferences = FloAppSharedPreferences.build(this) ?: return
        preferences.currentSession = session.mHandle
    }

    @Synchronized
    fun isTermuxSessionsEmpty(): Boolean {
        return mTerminalSessions.isEmpty()
    }

    @Synchronized
    fun getTermuxSessionsSize(): Int {
        return mTerminalSessions.size
    }

    @Synchronized
    fun getTermuxSessions(): List<com.flomobility.anx.shared.shell.TerminalSession?>? {
        return mTerminalSessions
    }

    @Synchronized
    fun getTermuxSession(index: Int): com.flomobility.anx.shared.shell.TerminalSession? {
        return if (index >= 0 && index < mTerminalSessions.size) mTerminalSessions[index] else null
    }

    @Synchronized
    fun getLastTermuxSession(): com.flomobility.anx.shared.shell.TerminalSession? {
        return if (mTerminalSessions.isEmpty()) null else mTerminalSessions[mTerminalSessions.size - 1]
    }

    @Synchronized
    fun getIndexOfSession(terminalSession: TerminalSession): Int {
        for (i in mTerminalSessions.indices) {
            if (mTerminalSessions[i].terminalSession == terminalSession) return i
        }
        return -1
    }

    @Synchronized
    fun getTerminalSessionForHandle(sessionHandle: String): TerminalSession? {
        var terminalSession: TerminalSession
        var i = 0
        val len = mTerminalSessions.size
        while (i < len) {
            terminalSession = mTerminalSessions[i].terminalSession
            if (terminalSession.mHandle == sessionHandle) return terminalSession
            i++
        }
        return null
    }


    @Synchronized
    fun getNextExecutionId(): Int {
        return EXECUTION_ID++
    }

    fun wantsToStop(): Boolean {
        return mWantsToStop
    }

}