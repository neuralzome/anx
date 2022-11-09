package com.flomobility.anx.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.flomobility.anx.shared.logger.Logger;
import com.flomobility.anx.shared.models.ExecutionCommand;
import com.flomobility.anx.shared.termux.TermuxConstants;

public class TermuxCommandExecutor implements ServiceConnection {

    private static final String BIN_PATH = "/data/data/com.flomobility.anx/files/usr/bin/";
    private TermuxService mTermuxService;
    private Context context;
    private  boolean isTermuxServiceBinded;
    private static TermuxCommandExecutor termuxCommandExecutor;
    private static final String LOG_TAG = TermuxCommandExecutor.class.getSimpleName();
    private ITermuxCommandExecutor iTermuxCommandExecutor;

    private TermuxCommandExecutor(Context context) {
        this.context = context;
        if(mTermuxService == null) {
            Intent serviceIntent = new Intent(context, TermuxService.class);
            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!context.bindService(serviceIntent, this, 0)) {
                throw new RuntimeException("bindService() failed");
            }
        }
    }

    public static TermuxCommandExecutor getInstance(Context context) {
        if (termuxCommandExecutor == null) {
            termuxCommandExecutor = new TermuxCommandExecutor(context);
        }
        return termuxCommandExecutor;
    }

    public int executeTermuxCommand(Context context, String binary, String[] arguments, int executionId ) {
        if(null == context || null == binary || null == arguments || TextUtils.isEmpty(binary)) {
            Log.e(LOG_TAG, "Invalid input parameters");
            return -1;
        }

        if(mTermuxService == null) {
            Log.e(LOG_TAG, "Termux service not connected, can not proceed further.");
            return -1;
        }

        if(!isTermuxServiceBinded) {
            Log.e(LOG_TAG, "Termux service not binded yet!");
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
        executionCommand.executableUri = new Uri.Builder().scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executionCommand.executable).build();
        mTermuxService.createTermuxTask(executionCommand);
        return 0;
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");
        mTermuxService = ((TermuxService.LocalBinder) service).service;
        isTermuxServiceBinded = true;
        if(iTermuxCommandExecutor != null) {
            iTermuxCommandExecutor.onTermuxServiceConnected();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        isTermuxServiceBinded = false;
        mTermuxService = null;
        if(iTermuxCommandExecutor != null) {
            iTermuxCommandExecutor.onTermuxServiceDisconnected();
        }
    }

    public void closeTermuxCommandExecutor() {
        try {
            termuxCommandExecutor.context.unbindService(termuxCommandExecutor);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startTermuxCommandExecutor(ITermuxCommandExecutor iTermuxCommandExecutor) {
        this.iTermuxCommandExecutor = iTermuxCommandExecutor;
        if(isTermuxServiceBinded) {
            iTermuxCommandExecutor.onTermuxServiceConnected();
        }
    }

    public interface ITermuxCommandExecutor {
        void onTermuxServiceConnected();
        void onTermuxServiceDisconnected();
    }

}