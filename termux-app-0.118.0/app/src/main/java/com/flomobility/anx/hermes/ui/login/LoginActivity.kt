package com.flomobility.anx.hermes.ui.login

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.flomobility.anx.hermes.network.requests.LoginRequest
import com.flomobility.anx.hermes.other.*
import com.flomobility.anx.hermes.ui.home.HomeActivity
import com.flomobility.anx.databinding.ActivityLoginBinding
import com.flomobility.anx.hermes.ui.download.DownloadActivity
import com.github.ybq.android.spinkit.style.ThreeBounce
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject


@AndroidEntryPoint
class LoginActivity : ComponentActivity() {

    companion object {
        fun navigateToLogin(context: Context) {
            context.startActivity(
                Intent(context, LoginActivity::class.java)//,
//                ActivityOptions.makeSceneTransitionAnimation(context as Activity).toBundle()
            )
        }
    }

    private var binding: ActivityLoginBinding? = null
    private val bind get() = binding!!
    private lateinit var viewModel: LoginViewModel

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    private val phoneNumberHintIntentResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val phoneNumber =
                Identity.getSignInClient(this@LoginActivity).getPhoneNumberFromIntent(result.data)
            bind.deviceId.text = "DEVICE ID: $phoneNumber"
            sharedPreferences.putDeviceID(phoneNumber)
        } catch (e: Exception) {
            Timber.e("Phone Number Hint failed")
            showSnackBarWithAction("Couldn't get device ID", indefinite = true, actionText = "Ok")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        viewModel = ViewModelProvider(this@LoginActivity)[LoginViewModel::class.java]
        setContentView(binding?.root)
        if (sharedPreferences.getDeviceID() != null) {
            bind.deviceId.text = "DEVICE ID: ${sharedPreferences.getDeviceID()}"
        } else {
            requestPhoneNumber()
        }
        bind.spinKitLogin.setIndeterminateDrawable(ThreeBounce())
        setEventListeners()
        subscribeToObservers()
    }

    /**
     * Checks current status whether a user has already login or not
     **/

    private fun requestPhoneNumber() {
        val request: GetPhoneNumberHintIntentRequest =
            GetPhoneNumberHintIntentRequest.builder().build()
        Identity.getSignInClient(this@LoginActivity)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener {
                try {
                    val fillIn = Intent()
                    val senderRequest: IntentSenderRequest =
                        IntentSenderRequest.Builder(it.intentSender).setFillInIntent(fillIn).build()
                    phoneNumberHintIntentResultLauncher.launch(senderRequest)
                } catch (e: Exception) {
                    Timber.e(e, "Launching the PendingIntent failed")
                }
            }
            .addOnFailureListener {
                Timber.e(it, "Phone Number Hint failed")
            }
    }

    private fun subscribeToObservers() {
//        Snackbar.make(this@LoginActivity, bind.root, "${sharedPreferences.getIsInstalled()} ${File("${filesDir.absolutePath}/${Constants.FILES_SYSTEM_FILE_NAME}").exists()} ${File("${filesDir.absolutePath}/home").list()?.get(0)} ${File("${filesDir.absolutePath}/home").list()?.get(1)} ${File("${filesDir.absolutePath}/home").list()?.get(2)} ${File("${filesDir.absolutePath}/home").list()?.get(3)} ${File("${filesDir.absolutePath}").list()?.size}", Snackbar.LENGTH_LONG).show()
        viewModel.login.observe(this@LoginActivity) {
            when (it.getContentIfNotHandled()) {
                is Resource.Loading -> {
                    bind.btnLogin.visibility = View.GONE
                    bind.spinKitLogin.visibility = View.VISIBLE
                }
                is Resource.Success -> {
                    if (isExpired(it.peekContent().data?.robot?.expiry)) {
                        showSnackBar("Your subscription has expired")
                        bind.spinKitLogin.visibility = View.GONE
                        bind.btnLogin.visibility = View.VISIBLE
                        return@observe
                    }
                    sharedPreferences.putToken(it.peekContent().data?.token)
                    sharedPreferences.putDeviceExpiry(it.peekContent().data?.robot?.expiry)
                    when (true) {
                        sharedPreferences.getIsInstalled() -> {
                            HomeActivity.navigateToHome(this@LoginActivity)
                        }
                        else -> DownloadActivity.navigateToDownload(this@LoginActivity)
                    }
                    finish()
                }
                is Resource.Error -> {
                    bind.spinKitLogin.visibility = View.GONE
                    bind.btnLogin.visibility = View.VISIBLE
                    when (it.peekContent().errorData?.message) {
                        null -> {
                            var error = it.peekContent().message
                            if (error?.contains("Failed to connect to") == true)
                                error = "Failed to connect to server"
                            showSnackBar(error)
                            return@observe
                        }
                        "Missing required request parameter" -> {
                            showSnackBar("Login Failed! Try Again")
                        }
                        "Invalid Credentials" -> {
                            showSnackBar("Incorrect Credentials!!")
                        }
                        else -> {
                            val error = it.peekContent().errorData?.message
                            showSnackBar(error)
                        }
                    }
                }
                null -> TODO()
            }
        }

    }

    private fun setEventListeners() {
        bind.apply {
            btnLogin.setOnClickListener {
                bind.btnLogin.visibility = View.GONE
                bind.spinKitLogin.visibility = View.VISIBLE
                val email = bind.emailLayout.editText?.text?.trim().toString()
                val password = bind.passLayout.editText?.text?.trim().toString()
                val userLogin = LoginRequest(email, password, sharedPreferences.getDeviceID())
                if (!userLogin.deviceID.isNullOrEmpty())
                    if (Patterns.EMAIL_ADDRESS.matcher(userLogin.email).matches()) {
                        bind.emailLayout.error = null
                        if (userLogin.password.length >= 4) {
                            bind.passLayout.error = null
                            viewModel.sendLoginRequest(userLogin)
                        } else {
                            bind.passLayout.error = "Incorrect Password"
                            bind.spinKitLogin.visibility = View.GONE
                            bind.btnLogin.visibility = View.VISIBLE
                        }
                    } else {
                        bind.emailLayout.error = "Incorrect E-Mail"
                        bind.spinKitLogin.visibility = View.GONE
                        bind.btnLogin.visibility = View.VISIBLE
                    }
                else {
                    //TODO Highlight DeviceID textField
                    bind.spinKitLogin.visibility = View.GONE
                    bind.btnLogin.visibility = View.VISIBLE
                    requestPhoneNumber()
                }
            }
            deviceId.setOnClickListener {
                requestPhoneNumber()
            }
        }
    }

    private fun showSnackBar(
        msg: String?,
        indefinite: Boolean = false
    ) {
        runOnUiThread {
            if (msg != null) {
                Snackbar.make(
                    bind.root,
                    msg,
                    if (indefinite) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSnackBarWithAction(
        msg: String?,
        indefinite: Boolean = false,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        runOnUiThread {
            if (msg != null) {
                actionText?.let {
                    Snackbar.make(
                        bind.root,
                        msg,
                        if (indefinite) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG
                    ).apply {
                        setAction(actionText) {
                            action?.invoke()
                            dismiss()
                        }
                    }.show()
                    return@runOnUiThread
                }
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
