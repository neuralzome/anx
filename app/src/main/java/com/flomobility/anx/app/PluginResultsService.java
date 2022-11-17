package com.flomobility.anx.app;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.flomobility.anx.shared.terminal.TerminalConstants.TERMUX_APP.TERMUX_SERVICE;

public class PluginResultsService extends IntentService {

    public static final String EXTRA_EXECUTION_ID = "execution_id";

    private static int EXECUTION_ID = 1000;

    public static final String PLUGIN_SERVICE_LABEL = "PluginResultsService";

    private static final String LOG_TAG = "PluginResultsService";

    public PluginResultsService(){
        super(PLUGIN_SERVICE_LABEL);
    }

    public static final int COMMAND_LS_EXECUTION_ID = 1000;

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) return;

        Log.d(LOG_TAG, PLUGIN_SERVICE_LABEL + " received execution result");

        final Bundle resultBundle = intent.getBundleExtra(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE);
        if (resultBundle == null) {
            Log.e(LOG_TAG, "The intent does not contain the result bundle at the \"" + TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE + "\" key.");
            return;
        }

        final int executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, 0);

        // based on the
        Log.d(LOG_TAG, "Execution id " + executionId + " result:\n" +
            "stdout:\n```\n" + resultBundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "") + "\n```\n" +
            "stdout_original_length: `" + resultBundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH) + "`\n" +
            "stderr:\n```\n" + resultBundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, "") + "\n```\n" +
            "stderr_original_length: `" + resultBundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH) + "`\n" +
            "exitCode: `" + resultBundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE) + "`\n" +
            "errCode: `" + resultBundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR) + "`\n" +
            "errmsg: `" + resultBundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG, "") + "`");


        sendResultBroadcast(executionId, resultBundle);

        switch (executionId) {
            case COMMAND_LS_EXECUTION_ID:
                Log.d(LOG_TAG , "Command LS execution result received");
                break;
            default:
                break;
        }
    }

    public static synchronized int getNextExecutionId() {
        return EXECUTION_ID++;
    }

    private void sendResultBroadcast(int executionCode, Bundle result) {
        Intent intent = new Intent(RESULT_BROADCAST_INTENT);
        intent.putExtra(RESULT_BROADCAST_EXECUTION_CODE_KEY, executionCode);
        intent.putExtra(RESULT_BROADCAST_RESULT_KEY, result);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public static final String RESULT_BROADCAST_INTENT = "com.flomobility.anx.result_broadcast";
    public static final String RESULT_BROADCAST_EXECUTION_CODE_KEY = "RESULT_BROADCAST_EXECUTION_CODE_KEY";
    public static final String RESULT_BROADCAST_RESULT_KEY = "RESULT_BROADCAST_RESULT_KEY";

}