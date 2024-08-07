/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event

class ThirdPartySipAccountLoginViewModel @UiThread constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Third Party SIP Account Login ViewModel]"
    }

    val username = MutableLiveData<String>()

    val authId = MutableLiveData<String>()

    val password = MutableLiveData<String>()

    val domain = MutableLiveData<String>()

    val displayName = MutableLiveData<String>()

    val transport = MutableLiveData<String>()

    val internationalPrefix = MutableLiveData<String>()

    val internationalPrefixIsoCountryCode = MutableLiveData<String>()

    val showPassword = MutableLiveData<Boolean>()

    val loginEnabled = MediatorLiveData<Boolean>()

    val registrationInProgress = MutableLiveData<Boolean>()

    val accountLoggedInEvent = MutableLiveData<Event<Boolean>>()

    val accountLoginErrorEvent = MutableLiveData<Event<String>>()

    val availableTransports = arrayListOf<String>()

    private lateinit var newlyCreatedAuthInfo: AuthInfo
    private lateinit var newlyCreatedAccount: Account

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (account == newlyCreatedAccount) {
                Log.i("$TAG Newly created account registration state is [$state] ($message)")

                if (state == RegistrationState.Ok) {
                    registrationInProgress.postValue(false)
                    core.removeListener(this)

                    // Set new account as default
                    core.defaultAccount = newlyCreatedAccount
                    accountLoggedInEvent.postValue(Event(true))
                } else if (state == RegistrationState.Failed) {
                    registrationInProgress.postValue(false)
                    core.removeListener(this)

                    val error = when (account.error) {
                        Reason.Forbidden -> {
                            AppUtils.getString(R.string.assistant_account_login_forbidden_error)
                        }
                        else -> {
                            AppUtils.getFormattedString(
                                R.string.assistant_account_login_error,
                                account.error.toString()
                            )
                        }
                    }
                    accountLoginErrorEvent.postValue(Event(error))

                    Log.e("$TAG Account failed to REGISTER [$message], removing it")
                    core.removeAuthInfo(newlyCreatedAuthInfo)
                    core.removeAccount(newlyCreatedAccount)
                }
            }
        }
    }

    init {
        showPassword.value = false
        registrationInProgress.value = false

        loginEnabled.addSource(username) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(domain) {
            loginEnabled.value = isLoginButtonEnabled()
        }

        // TODO: handle formatting errors ?

        availableTransports.add(TransportType.Udp.name.uppercase(Locale.getDefault()))
        availableTransports.add(TransportType.Tcp.name.uppercase(Locale.getDefault()))
        availableTransports.add(TransportType.Tls.name.uppercase(Locale.getDefault()))
    }

    @UiThread
    fun login() {
        coreContext.postOnCoreThread { core ->
            core.loadConfigFromXml(corePreferences.thirdPartyDefaultValuesPath)

            // Remove sip: in front of domain, just in case...
            val domainValue = domain.value.orEmpty().trim()
            val domain = if (domainValue.startsWith("sip:")) {
                domainValue.substring("sip:".length)
            } else {
                domainValue
            }

            // Allow to enter SIP identity instead of simply username
            // in case identity domain doesn't match proxy domain
            val user = username.value.orEmpty().trim()
            val userId = authId.value.orEmpty().trim()
            val identity = if (user.startsWith("sip:")) {
                if (user.contains("@")) {
                    user
                } else {
                    "$user@$domain"
                }
            } else {
                if (user.contains("@")) {
                    "sip:$user"
                } else {
                    "sip:$user@$domain"
                }
            }
            val identityAddress = Factory.instance().createAddress(identity)

            newlyCreatedAuthInfo = Factory.instance().createAuthInfo(
                user,
                userId,
                password.value.orEmpty().trim(),
                null,
                null,
                null
            )
            core.addAuthInfo(newlyCreatedAuthInfo)

            val accountParams = core.createAccountParams()

            if (displayName.value.orEmpty().isNotEmpty()) {
                identityAddress?.displayName = displayName.value.orEmpty().trim()
            }
            accountParams.identityAddress = identityAddress

            val serverAddress = Factory.instance().createAddress("sip:$domain")
            serverAddress?.transport = when (transport.value.orEmpty().trim()) {
                TransportType.Tcp.name.uppercase(Locale.getDefault()) -> TransportType.Tcp
                TransportType.Tls.name.uppercase(Locale.getDefault()) -> TransportType.Tls
                else -> TransportType.Udp
            }
            accountParams.serverAddress = serverAddress

            val prefix = internationalPrefix.value.orEmpty().trim()
            val isoCountryCode = internationalPrefixIsoCountryCode.value.orEmpty()
            if (prefix.isNotEmpty()) {
                val prefixDigits = if (prefix.startsWith("+")) {
                    prefix.substring(1)
                } else {
                    prefix
                }
                if (prefixDigits.isNotEmpty()) {
                    Log.i(
                        "$TAG Setting international prefix [$prefixDigits]($isoCountryCode) in account params"
                    )
                    accountParams.internationalPrefix = prefixDigits
                    accountParams.internationalPrefixIsoCountryCode = isoCountryCode
                }
            }

            newlyCreatedAccount = core.createAccount(accountParams)

            registrationInProgress.postValue(true)
            core.addListener(coreListener)
            core.addAccount(newlyCreatedAccount)
        }
    }

    @UiThread
    fun toggleShowPassword() {
        showPassword.value = showPassword.value == false
    }

    @UiThread
    private fun isLoginButtonEnabled(): Boolean {
        // Password isn't mandatory as authentication could be Bearer
        return username.value.orEmpty().isNotEmpty() && domain.value.orEmpty().isNotEmpty()
    }
}
