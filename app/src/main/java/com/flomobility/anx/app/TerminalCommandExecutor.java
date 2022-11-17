package com.flomobility.anx.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.flomobility.anx.hermes.daemon.EndlessService;
import com.flomobility.anx.shared.logger.Logger;
import com.flomobility.anx.shared.models.ExecutionCommand;
import com.flomobility.anx.shared.terminal.TerminalConstants;

public class TerminalCommandExecutor implements ServiceConnection {

    private static final String BIN_PATH = "/data/data/com.flomobility.anx/files/usr/bin/";
    private EndlessService mEndlessService;
    private Context context;
    private  boolean isEndlessServiceBinded;
    private static TerminalCommandExecutor terminalCommandExecutor;
    private static final String LOG_TAG = TerminalCommandExecutor.class.getSimpleName();
    private ITerminalCommandExecutor iTerminalCommandExecutor;

    private TerminalCommandExecutor(Context context) {
        this.context = context;
        if(mEndlessService == null) {
            Intent serviceIntent = new Intent(context, EndlessService.class);
            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!context.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)) {
                throw new RuntimeException("bindService() failed");
            }
        }
    }

    public static TerminalCommandExecutor getInstance(Context context) {
        if (terminalCommandExecutor == null) {
            terminalCommandExecutor = new TerminalCommandExecutor(context);
        }
        return terminalCommandExecutor;
    }

    public int executeCommand(Context context, String binary, String[] arguments, int executionId ) {
        if(null == context || null == binary || null == arguments || TextUtils.isEmpty(binary)) {
            Log.e(LOG_TAG, "Invalid input parameters");
            return -1;
        }

        if(mEndlessService == null) {
            Log.e(LOG_TAG, "Terminal service not connected, can not proceed further.");
            return -1;
        }

        if(!isEndlessServiceBinded) {
            Log.e(LOG_TAG, "Terminal service not binded yet!");
            return -1;
        }

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.executable = BIN_PATH + binary;
        executionCommand.inBackground = true;
        executionCommand.isPluginExecutionCommand = true;
        Intent pluginResultsServiceIntent = new Intent(context, PluginResultsService.class);
        pluginResultsServiceIntent.putExtra(PluginResultsService.EXTRA_EXECUTION_ID, executionId);
        executionCommand.arguments = arguments;
        executionCommand.resultConfig.resultPendingIntent = PendingIntent.getService(context, executionId, pluginResultsServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        executionCommand.executableUri = new Uri.Builder().scheme(TerminalConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executionCommand.executable).build();
        mEndlessService.createTermuxTask(executionCommand);
        return 0;
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");
        mEndlessService = ((EndlessService.LocalBinder) service).getService();
        isEndlessServiceBinded = true;
        if(iTerminalCommandExecutor != null) {
            iTerminalCommandExecutor.onEndlessServiceConnected();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        isEndlessServiceBinded = false;
        mEndlessService = null;
        if(iTerminalCommandExecutor != null) {
            iTerminalCommandExecutor.onEndlessServiceDisconnected();
        }
    }

    public void closeTermuxCommandExecutor() {
        try {
            terminalCommandExecutor.context.unbindService(terminalCommandExecutor);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startCommandExecutor(ITerminalCommandExecutor iTerminalCommandExecutor) {
        this.iTerminalCommandExecutor = iTerminalCommandExecutor;
        if(isEndlessServiceBinded) {
            iTerminalCommandExecutor.onEndlessServiceConnected();
        }
    }

    public interface ITerminalCommandExecutor {
        void onEndlessServiceConnected();
        void onEndlessServiceDisconnected();
    }

}